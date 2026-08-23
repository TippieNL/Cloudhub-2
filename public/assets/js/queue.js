/*
 * Durable upload queue.
 *
 * The resumable chunk protocol has always been able to continue an interrupted
 * upload -- /api/uploads/init reports how many bytes the server already holds.
 * What was missing was anything to continue *from*: the id and the confirmed
 * offset were kept in localStorage, which stores strings, so once the tab
 * closed the browser had no handle on the file's bytes and "resume" could only
 * ever mean "start again".
 *
 * Here the file itself lives in IndexedDB, so an upload survives the app being
 * closed, the device being locked, the connection dropping, and the phone
 * being restarted. On the next launch the queue picks up from the offset the
 * server confirms.
 *
 * The engine deliberately stays in the page rather than in the service worker.
 * Reimplementing the chunk protocol there would mean two copies of the resume
 * logic that must agree exactly; instead the worker wakes an open page when
 * the connection returns, and if no page is open the queue continues the next
 * time the app is launched. Either way the user sees their upload finish.
 */

const Q = (() => {
    const store = window.CloudHubPWA?.cfhStore;
    const FRONT = window.CLOUDHUB_FRONT || ((window.CLOUDHUB_BASE || '') ? `${window.CLOUDHUB_BASE}/` : '/');
    const url = p => `${FRONT}?route=${encodeURIComponent(p)}`;

    const state = {
        running: false,
        csrf: '',
        current: null,
        cancelled: new Set(),
    };

    /* ---- storage -------------------------------------------------------- */

    const put = item => store('uploads', 'readwrite', s => s.put(item));
    const get = id => store('uploads', 'readonly', s => s.get(id));
    const del = id => store('uploads', 'readwrite', s => s.delete(id));
    const all = () => store('uploads', 'readonly', s => s.getAll()).then(v => v || []);

    /** The server accepts /^[A-Za-z0-9_-]{8,128}$/ and uses it to find the staging directory. */
    function newId() {
        const bytes = crypto.getRandomValues(new Uint8Array(12));
        return 'q' + [...bytes].map(b => b.toString(16).padStart(2, '0')).join('');
    }

    function changed() {
        document.dispatchEvent(new CustomEvent('cfh-queue-changed'));
    }

    /* ---- public API ----------------------------------------------------- */

    async function add(files, targetPath) {
        const added = [];
        for (const file of files) {
            const item = {
                id: newId(),
                name: file.name || `shared-${Date.now()}`,
                type: file.type || 'application/octet-stream',
                size: file.size,
                targetPath: targetPath || '/',
                // The bytes themselves. This is the whole point of the store.
                blob: file,
                offset: 0,
                state: 'pending',
                error: '',
                addedAt: Date.now(),
            };
            await put(item);
            added.push(item.id);
        }
        changed();
        requestSync();
        run();
        return added;
    }

    async function remove(id) {
        state.cancelled.add(id);
        await del(id);
        changed();
    }

    async function retry(id) {
        const item = await get(id);
        if (!item) return;
        // Start the retry from what the server confirms rather than from the
        // last offset this device recorded; they can differ after a crash
        // mid-chunk.
        await put({ ...item, state: 'pending', error: '' });
        changed();
        run();
    }

    async function clearFinished() {
        for (const item of await all()) if (item.state === 'done') await del(item.id);
        changed();
    }

    /* ---- the engine ----------------------------------------------------- */

    /**
     * Background Sync lets the browser wake the app when connectivity returns,
     * even after it has been closed. It is Chromium-only, so it is a bonus on
     * top of the online event rather than the mechanism relied upon.
     */
    async function requestSync() {
        try {
            const reg = await navigator.serviceWorker?.ready;
            await reg?.sync?.register('cloudhub-uploads');
        } catch { /* unsupported, or denied: the online event still fires */ }
    }

    async function csrfToken() {
        if (state.csrf) return state.csrf;
        const r = await fetch(url('/api/auth/status'), { credentials: 'same-origin' });
        const d = await r.json();
        if (!d.authenticated) throw Object.assign(new Error('Sign in to continue uploading'), { signedOut: true });
        state.csrf = d.csrfToken || '';
        return state.csrf;
    }

    function post(route, body) {
        return fetch(url(route), {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': state.csrf },
            body: JSON.stringify(body),
        });
    }

    async function readError(res, fallback) {
        try {
            const d = await res.json();
            return d.error?.message || fallback;
        } catch {
            return fallback;
        }
    }

    async function sendChunk(id, offset, blob) {
        const res = await fetch(`${url('/api/uploads/chunk')}&id=${encodeURIComponent(id)}`, {
            method: 'PUT',
            credentials: 'same-origin',
            headers: {
                'Content-Type': 'application/octet-stream',
                'X-Upload-Offset': String(offset),
                'X-CSRF-Token': state.csrf,
            },
            body: blob,
        });
        if (!res.ok) throw Object.assign(new Error(await readError(res, `Chunk failed (HTTP ${res.status})`)), { status: res.status });
        return res.json();
    }

    async function uploadOne(item) {
        await csrfToken();

        const init = await post('/api/uploads/init', {
            uploadId: item.id, targetPath: item.targetPath,
            name: item.name, size: item.size, conflict: 'rename',
        });
        if (!init.ok) {
            const message = await readError(init, `Upload refused (HTTP ${init.status})`);
            // 507 is the quota talking, 413 the size limit: retrying changes
            // nothing, so the item is failed rather than left cycling.
            throw Object.assign(new Error(message), { status: init.status, permanent: [413, 507, 403].includes(init.status) });
        }
        let status = await init.json();
        const chunkBytes = status.chunkBytes || 8 * 1024 * 1024;
        let offset = Math.min(status.received || 0, item.size);

        while (offset < item.size) {
            if (state.cancelled.has(item.id)) throw Object.assign(new Error('Cancelled'), { cancelled: true });
            const end = Math.min(offset + chunkBytes, item.size);
            status = await sendChunk(item.id, offset, item.blob.slice(offset, end));
            offset = status.received;
            // Persisted per chunk, so a crash costs at most one chunk.
            await put({ ...item, offset, state: 'uploading' });
            changed();
        }

        const done = await post('/api/uploads/complete', { id: item.id });
        if (!done.ok) throw new Error(await readError(done, 'Could not finalise the upload'));
        return done.json();
    }

    async function run() {
        if (state.running) return;
        if (!navigator.onLine) return;
        state.running = true;
        try {
            while (true) {
                const items = await all();
                const next = items.find(i => i.state === 'pending' || i.state === 'uploading');
                if (!next) break;

                state.current = next.id;
                await put({ ...next, state: 'uploading', error: '' });
                changed();

                try {
                    await uploadOne(next);
                    await put({ ...next, state: 'done', offset: next.size, error: '' });
                    changed();
                    document.dispatchEvent(new CustomEvent('cfh-queue-uploaded', { detail: { targetPath: next.targetPath } }));
                } catch (err) {
                    if (err.cancelled) continue;
                    if (err.signedOut) { changed(); break; }
                    // A network failure is not a failure of the upload: leave
                    // it pending so it resumes rather than asking the user to
                    // press retry for something they did not do wrong.
                    const transient = !err.permanent && (err.status === undefined || err.status >= 500 || !navigator.onLine);
                    await put({ ...next, state: transient ? 'pending' : 'failed', error: err.message });
                    changed();
                    if (transient) { requestSync(); break; }
                }
            }
        } finally {
            state.running = false;
            state.current = null;
        }
    }

    /* ---- wake-ups -------------------------------------------------------- */

    window.addEventListener('online', () => run());
    document.addEventListener('cfh-resume-uploads', () => run());
    // Coming back to the app is the most common moment a stalled queue can
    // make progress again.
    document.addEventListener('visibilitychange', () => { if (!document.hidden) run(); });
    // Signing in is the other: the queue stops rather than failing when there
    // is no session, so it has to be told when one appears.
    document.addEventListener('cfh-signed-in', () => { state.csrf = ''; run(); });

    /*
     * Start on load. This is the whole point of a durable queue -- an upload
     * interrupted by the app closing has to continue by itself, and none of
     * the events above fire on a fresh launch. Deferred past first paint so a
     * queued upload does not compete with rendering the file list.
     */
    if (document.readyState === 'complete') setTimeout(run, 500);
    else window.addEventListener('load', () => setTimeout(run, 500));

    return { add, remove, retry, clearFinished, list: all, run, get current() { return state.current; } };
})();

window.CloudHubQueue = Q;
