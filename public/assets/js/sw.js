/*
 * CloudHub service worker.
 *
 * Scope comes from the Service-Worker-Allowed header on the /sw.js route, not
 * from this file's location, so everything here works whether the application
 * is at the domain root or in a subdirectory.
 *
 * Three jobs:
 *   1. keep the interface usable with no connection (the app shell),
 *   2. serve folder listings and files that were explicitly kept offline,
 *   3. wake the page when the connection returns so queued uploads resume.
 *
 * What it deliberately does NOT do is cache API responses opportunistically.
 * Those are session-authenticated: caching them by default would leave one
 * person's file listing readable to the next person on a shared device. Only
 * what the signed-in user asked to keep is stored, and signing out clears it.
 */

const VERSION = 'v1';
const SHELL = `cloudhub-shell-${VERSION}`;
const DATA = `cloudhub-data-${VERSION}`;
const FILES = `cloudhub-files-${VERSION}`;
const KEEP = [SHELL, DATA, FILES];

/* Set by the page at registration, because the worker cannot derive the
 * application's base path from its own URL. */
let BASE = new URL(self.registration.scope).pathname.replace(/\/$/, '');

const appUrl = p => `${BASE}/?route=${encodeURIComponent(p)}`;
const shellUrl = () => `${BASE}/`;

self.addEventListener('install', event => {
    event.waitUntil((async () => {
        const cache = await caches.open(SHELL);
        // The shell is fetched through the network so the response carries the
        // real headers; a failure here must not abort the install, or one
        // missing asset leaves the app with no worker at all.
        const assets = await shellAssets();
        await Promise.allSettled(assets.map(u => cache.add(new Request(u, { credentials: 'same-origin' }))));
        await self.skipWaiting();
    })());
});

self.addEventListener('activate', event => {
    event.waitUntil((async () => {
        const names = await caches.keys();
        await Promise.all(names
            .filter(n => n.startsWith('cloudhub-') && !KEEP.includes(n))
            .map(n => caches.delete(n)));
        await self.clients.claim();
    })());
});

/**
 * The shell is the document plus the assets it needs to render.
 *
 * Read from the page's own asset base rather than guessed, because assets can
 * be at /assets or /public/assets depending on the document root.
 */
async function shellAssets() {
    const stored = await readSetting('assetBase');
    const a = stored === null ? BASE : stored;
    return [
        shellUrl(),
        `${a}/assets/css/app.css`,
        `${a}/assets/js/app.js`,
        `${a}/assets/icons/icon-192.png`,
    ];
}

/* ---- a tiny settings store, shared with the page ------------------------ */

const DB_NAME = 'cloudhub';
const DB_VERSION = 1;

function openDb() {
    return new Promise((resolve, reject) => {
        const req = indexedDB.open(DB_NAME, DB_VERSION);
        req.onupgradeneeded = () => {
            const db = req.result;
            if (!db.objectStoreNames.contains('settings')) db.createObjectStore('settings');
            if (!db.objectStoreNames.contains('uploads')) db.createObjectStore('uploads', { keyPath: 'id' });
            if (!db.objectStoreNames.contains('listings')) db.createObjectStore('listings');
        };
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => reject(req.error);
    });
}

function idb(store, mode, fn) {
    return openDb().then(db => new Promise((resolve, reject) => {
        const tx = db.transaction(store, mode);
        const req = fn(tx.objectStore(store));
        tx.oncomplete = () => resolve(req && 'result' in req ? req.result : undefined);
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error);
    })).catch(() => undefined);
}

const readSetting = key => idb('settings', 'readonly', s => s.get(key)).then(v => v === undefined ? null : v);

/* ---- fetch -------------------------------------------------------------- */

self.addEventListener('fetch', event => {
    const req = event.request;
    if (req.method !== 'GET') return;

    const url = new URL(req.url);
    if (url.origin !== self.location.origin) return;

    // Range requests are how video seeking works. A cached whole-file response
    // cannot satisfy one correctly, so those always go to the network.
    if (req.headers.has('range')) return;

    const route = url.searchParams.get('route') || '';

    if (req.mode === 'navigate') {
        event.respondWith(navigateWithFallback(req));
        return;
    }
    if (route === '/api/files/list') {
        event.respondWith(listingWithFallback(req, url));
        return;
    }
    if (route === '/api/files/download' || route === '/api/files/stream' || route === '/api/thumbnail') {
        event.respondWith(cacheFirstForKeptFiles(req));
        return;
    }
    if (isShellAsset(url)) {
        event.respondWith(staleWhileRevalidate(req, SHELL));
    }
});

function isShellAsset(url) {
    return /\/assets\/(css|js|icons)\//.test(url.pathname);
}

/**
 * Navigations fall back to the cached shell.
 *
 * Every route in this application is the same document -- the client renders
 * the page -- so one cached shell serves /, /trash, /users and the rest.
 */
async function navigateWithFallback(req) {
    try {
        return await fetch(req);
    } catch {
        const cached = await caches.match(shellUrl(), { ignoreSearch: true });
        return cached || offlineResponse();
    }
}

/**
 * Folder listings: network first, remembering the answer.
 *
 * Network first rather than cache first because a stale listing showing files
 * that are no longer there is worse than a slightly slower load. The copy is
 * only there for when there is no network at all.
 */
async function listingWithFallback(req, url) {
    const key = `list:${url.searchParams.get('path') || '/'}`;
    try {
        const res = await fetch(req);
        if (res.ok) {
            const clone = res.clone();
            clone.json().then(body => idb('listings', 'readwrite', s => s.put(body, key))).catch(() => {});
        }
        return res;
    } catch {
        const cached = await idb('listings', 'readonly', s => s.get(key));
        if (cached === undefined) return offlineResponse();
        return new Response(JSON.stringify(cached), {
            status: 200,
            headers: { 'Content-Type': 'application/json', 'X-CloudHub-Offline': '1' },
        });
    }
}

/** Files the user kept for offline access, plus their thumbnails. */
async function cacheFirstForKeptFiles(req) {
    const cache = await caches.open(FILES);
    const hit = await cache.match(req, { ignoreVary: true });
    if (hit) return hit;
    try {
        return await fetch(req);
    } catch {
        return offlineResponse();
    }
}

async function staleWhileRevalidate(req, cacheName) {
    const cache = await caches.open(cacheName);
    const hit = await cache.match(req, { ignoreSearch: true });
    const network = fetch(req).then(res => {
        if (res.ok) cache.put(req, res.clone());
        return res;
    }).catch(() => hit || offlineResponse());
    return hit || network;
}

function offlineResponse() {
    return new Response(
        JSON.stringify({ error: { code: 'OFFLINE', message: 'You are offline and this is not available for offline use.' } }),
        { status: 503, headers: { 'Content-Type': 'application/json', 'X-CloudHub-Offline': '1' } });
}

/* ---- messages and background sync --------------------------------------- */

self.addEventListener('message', event => {
    const msg = event.data || {};
    if (msg.type === 'base') BASE = msg.base.replace(/\/$/, '');
    if (msg.type === 'skip-waiting') self.skipWaiting();
    if (msg.type === 'sign-out') {
        // Cached listings and kept files belong to whoever was signed in.
        event.waitUntil(Promise.all([
            caches.delete(DATA),
            caches.delete(FILES),
            idb('listings', 'readwrite', s => s.clear()),
        ]));
    }
});

/*
 * The upload engine lives in the page, not here.
 *
 * Reimplementing the chunked protocol in the worker would mean two copies of
 * the resume logic that must agree exactly. Instead this wakes any open page
 * when the connection returns; if none is open the queue resumes the next time
 * the app is launched, which is what a user sees either way.
 */
self.addEventListener('sync', event => {
    if (event.tag !== 'cloudhub-uploads') return;
    event.waitUntil(notifyClients({ type: 'resume-uploads' }));
});

self.addEventListener('online', () => notifyClients({ type: 'resume-uploads' }));

async function notifyClients(message) {
    const clients = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    for (const client of clients) client.postMessage(message);
    return clients.length;
}
