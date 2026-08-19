const BASE = (window.CLOUDHUB_BASE || '').replace(/\/$/, '');
const FRONT = window.CLOUDHUB_FRONT || (BASE ? `${BASE}/` : '/');
const appUrl = p => {
    const raw = p.startsWith('/') ? p : '/' + p;
    const q = raw.indexOf('?');
    const route = q >= 0 ? raw.slice(0, q) : raw;
    const query = q >= 0 ? raw.slice(q + 1) : '';
    return `${FRONT}?route=${encodeURIComponent(route)}${query ? '&' + query : ''}`;
};
/** Per-tab memory of the open folder, so returning from the player lands back there. */
const rememberedPath = (() => {
    try { return sessionStorage.getItem('cfh_path') || '/'; } catch { return '/'; }
})();

const S = {
    path: rememberedPath,
    files: [],
    selected: new Set(),
    view: localStorage.getItem('cfh_view') || 'grid',
    sort: localStorage.getItem('cfh_sort') || 'name-asc',
    thumbnailUrls: new Set()
};
const $ = s => document.querySelector(s);
const toast = m => {
    const t = $('#toast');
    t.textContent = m;
    t.style.display = 'block';
    setTimeout(() => t.style.display = 'none', 2200);
};

async function api(url, opt = {}) {
    url = appUrl(url);
    opt.headers = { ...(opt.headers || {}) };
    const method = (opt.method || 'GET').toUpperCase();
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && S.csrf) opt.headers['X-CSRF-Token'] = S.csrf;
    if (opt.body && !(opt.body instanceof FormData)) {
        opt.headers['Content-Type'] = 'application/json';
        opt.body = JSON.stringify(opt.body);
    }
    const r = await fetch(url, { ...opt, credentials: 'same-origin' });
    if (r.status === 401) {
        $('#login').style.display = 'flex';
        throw Error('Authentication required');
    }
    if (!r.ok) {
        let m = `HTTP ${r.status}`, code = 'HTTP_ERROR', requestId = '';
        try {
            const d = await r.json();
            m = d.error?.message || m;
            code = d.error?.code || code;
            requestId = d.requestId || '';
        } catch {}
        const e = Error(m);
        e.code = code;
        e.status = r.status;
        e.requestId = requestId;
        throw e;
    }
    return r;
}

async function login(u, p) {
    const r = await fetch(appUrl('/api/auth/login'), {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: u, password: p })
    });
    const d = await r.json().catch(() => ({}));
    if (!r.ok) throw Error(d.error?.message || 'Sign in failed');
    S.csrf = d.csrfToken || '';
    $('#login').style.display = 'none';
    await route();
}

$('#login-form').addEventListener('submit', async e => {
    e.preventDefault();
    try {
        await login($('#username').value, $('#password').value);
        $('#login-error').textContent = '';
        $('#password').value = '';
    } catch (x) {
        $('#login-error').textContent = x.message;
    }
});

$('#logout').addEventListener('click', async () => {
    try {
        await api('/api/auth/logout', { method: 'POST' });
    } catch {}
    S.csrf = '';
    $('#login').style.display = 'flex';
});

$('#theme').addEventListener('click', () => {
    document.documentElement.classList.toggle('dark');
    localStorage.setItem('theme', document.documentElement.classList.contains('dark') ? 'dark' : 'light');
});
if (localStorage.getItem('theme') === 'dark') document.documentElement.classList.add('dark');

function fmt(n) {
    if (n < 1024) return `${n} B`;
    if (n < 1048576) return `${(n / 1024).toFixed(1)} KB`;
    if (n < 1073741824) return `${(n / 1048576).toFixed(1)} MB`;
    return `${(n / 1073741824).toFixed(1)} GB`;
}

function crumbs() {
    const parts = S.path.split('/').filter(Boolean);
    let cur = '';
    const items = [`<button data-p="/">Root</button>`];
    for (const part of parts) {
        cur += '/' + part;
        items.push(`<span aria-hidden="true">›</span><button data-p="${esc(cur)}">${esc(part)}</button>`);
    }
    $('#breadcrumbs').innerHTML = items.join('');
    $('#breadcrumbs').querySelectorAll('button').forEach(b => {
        b.addEventListener('click', () => loadFiles(b.dataset.p));
    });
}

async function loadFiles(p = S.path) {
    S.selected.clear();
    let response;
    try {
        response = await api(`/api/files/list?path=${encodeURIComponent(p)}`);
    } catch (e) {
        // A remembered folder can be renamed or deleted between visits. Falling
        // back to the root beats leaving the file list empty; anything else
        // (no session, server error) is the caller's to handle.
        if (p === '/' || e.status === 401) throw e;
        toast('That folder is no longer available');
        p = '/';
        response = await api('/api/files/list?path=%2F');
    }
    S.path = p;
    try { sessionStorage.setItem('cfh_path', p); } catch {}
    S.files = await response.json();
    crumbs();
    renderFiles();
}

function sortedFiles() {
    const q = $('#search').value.trim().toLowerCase();
    const files = S.files.filter(f => f.name.toLowerCase().includes(q));
    const [key, dir] = S.sort.split('-');
    const factor = dir === 'asc' ? 1 : -1;
    return files.sort((a, b) => {
        if (a.isDirectory !== b.isDirectory) return a.isDirectory ? -1 : 1;
        if (key === 'size') return ((a.size || 0) - (b.size || 0)) * factor;
        if (key === 'date') return (new Date(a.modified) - new Date(b.modified)) * factor;
        return a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' }) * factor;
    });
}

function updateSelectionUI() {
    const n = S.selected.size, bar = $('#selection-bar');
    bar.hidden = n === 0;
    $('#selection-count').textContent = `${n} selected`;
    document.querySelectorAll('[data-sel]').forEach(c => c.checked = S.selected.has(decodeURIComponent(c.dataset.sel)));
    document.querySelectorAll('.file').forEach(card => card.classList.toggle('selected', S.selected.has(decodeURIComponent(card.dataset.path))));
}

function toggleSelection(path, checked) {
    checked ? S.selected.add(path) : S.selected.delete(path);
    updateSelectionUI();
}


/**
 * Generate video thumbnails entirely in the browser.
 *
 * No FFmpeg, PHP video decoder or server-side frame extraction is required.
 * The browser fetches a short/seekable media stream, decodes one frame with
 * its native video codec and paints that frame into a Canvas.
 */
function cleanupVideoThumbnailUrls() {
    for (const url of S.thumbnailUrls) URL.revokeObjectURL(url);
    S.thumbnailUrls.clear();
}

function waitForVideoEvent(video, eventName, timeoutMs = 15000) {
    return new Promise((resolve, reject) => {
        let settled = false;
        const cleanup = () => {
            video.removeEventListener(eventName, onEvent);
            video.removeEventListener('error', onError);
            clearTimeout(timer);
        };
        const finish = (fn, value) => {
            if (settled) return;
            settled = true;
            cleanup();
            fn(value);
        };
        const onEvent = () => finish(resolve);
        const onError = () => finish(reject, new Error('Browser could not decode the video'));
        const timer = setTimeout(() => finish(reject, new Error('Video thumbnail timed out')), timeoutMs);
        video.addEventListener(eventName, onEvent, { once: true });
        video.addEventListener('error', onError, { once: true });
    });
}

async function captureVideoFrame(button) {
    const source = button.dataset.videoThumb;
    const image = button.querySelector('img');
    if (!source || !image || !document.contains(button)) return;

    const status = button.querySelector('.video-thumb-status');
    const video = document.createElement('video');
    video.preload = 'auto';
    video.muted = true;
    video.playsInline = true;
    video.controls = false;
    video.disablePictureInPicture = true;
    video.src = source;

    try {
        video.load();
        if (video.readyState < HTMLMediaElement.HAVE_METADATA) {
            await waitForVideoEvent(video, 'loadedmetadata');
        }

        if (!video.videoWidth || !video.videoHeight) {
            throw new Error('Video dimensions are unavailable');
        }

        const duration = Number.isFinite(video.duration) ? video.duration : 0;
        const target = duration > 0
            ? Math.min(Math.max(duration * 0.08, 0.25), Math.max(0, duration - 0.05))
            : 0;

        if (target > 0.05) {
            await new Promise((resolve, reject) => {
                let settled = false;
                const cleanup = () => {
                    video.removeEventListener('seeked', onSeeked);
                    video.removeEventListener('error', onError);
                };
                const onSeeked = () => {
                    if (settled) return;
                    settled = true;
                    cleanup();
                    resolve();
                };
                const onError = () => {
                    if (settled) return;
                    settled = true;
                    cleanup();
                    reject(new Error('Video seek failed'));
                };
                video.addEventListener('seeked', onSeeked, { once: true });
                video.addEventListener('error', onError, { once: true });
                try {
                    video.currentTime = target;
                } catch (error) {
                    onError();
                }
            });
        } else if (video.readyState < HTMLMediaElement.HAVE_CURRENT_DATA) {
            await waitForVideoEvent(video, 'loadeddata');
        }

        const maxWidth = 480;
        const width = Math.min(video.videoWidth, maxWidth);
        const height = Math.max(1, Math.round(width * video.videoHeight / video.videoWidth));
        const canvas = document.createElement('canvas');
        canvas.width = width;
        canvas.height = height;

        const context = canvas.getContext('2d', { alpha: false });
        if (!context) throw new Error('Canvas is unavailable');

        context.drawImage(video, 0, 0, width, height);

        const blob = await new Promise((resolve, reject) => {
            canvas.toBlob(value => value ? resolve(value) : reject(new Error('Canvas encoding failed')), 'image/webp', 0.82);
        }).catch(async () => new Promise((resolve, reject) => {
            canvas.toBlob(value => value ? resolve(value) : reject(new Error('Canvas encoding failed')), 'image/jpeg', 0.82);
        }));

        const objectUrl = URL.createObjectURL(blob);
        S.thumbnailUrls.add(objectUrl);
        if (!document.contains(button)) {
            URL.revokeObjectURL(objectUrl);
            S.thumbnailUrls.delete(objectUrl);
            return;
        }

        image.src = objectUrl;
        image.removeAttribute('hidden');
        if (status) status.remove();
    } catch (error) {
        /*
         * Canvas extraction is not available in every Android WebView.
         * Keep a native <video> fallback showing the same decoded frame.
         */
        try {
            video.controls = false;
            video.style.width = '100%';
            video.style.height = '100%';
            video.style.objectFit = 'cover';
            video.style.pointerEvents = 'none';
            button.replaceChild(video, image);
            if (status) status.remove();
            if (Number.isFinite(video.duration) && video.duration > 0) {
                video.currentTime = Math.min(video.duration * 0.08, Math.max(0, video.duration - 0.05));
            }
        } catch {
            if (status) status.textContent = 'Video thumbnail unavailable';
            console.warn('Cloud File Hub video thumbnail:', error);
        }
    } finally {
        if (video.parentNode !== button) {
            video.removeAttribute('src');
            video.load();
        }
    }
}

function initVideoThumbnails() {
    cleanupVideoThumbnailUrls();

    const buttons = [...document.querySelectorAll('.thumb-preview.video-thumb[data-video-thumb]')];
    if (!buttons.length) return;

    const queue = [];
    let active = 0;
    const concurrency = 2;

    const pump = () => {
        while (active < concurrency && queue.length) {
            const button = queue.shift();
            if (!button || !document.contains(button)) continue;
            active++;
            captureVideoFrame(button)
                .catch(error => console.warn('Cloud File Hub video thumbnail:', error))
                .finally(() => {
                    active--;
                    pump();
                });
        }
    };

    const enqueue = button => {
        if (button.dataset.thumbnailQueued === '1') return;
        button.dataset.thumbnailQueued = '1';
        queue.push(button);
        pump();
    };

    if ('IntersectionObserver' in window) {
        const observer = new IntersectionObserver(entries => {
            for (const entry of entries) {
                if (!entry.isIntersecting) continue;
                observer.unobserve(entry.target);
                enqueue(entry.target);
            }
        }, { rootMargin: '240px' });

        buttons.forEach(button => observer.observe(button));
    } else {
        buttons.forEach(enqueue);
    }
}

/** Render files in either responsive grid or compact list mode. */
function renderFiles() {
    cleanupVideoThumbnailUrls();
    const list = $('#file-list');
    const imageExt = new Set(['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg', 'avif']);
    const videoExt = new Set(['mp4', 'webm', 'ogv', 'ogg', 'mov', 'm4v', 'avi', 'mkv', 'mpeg', 'mpg', '3gp', '3g2', 'ts', 'm2ts', 'mts']);
    const audioExt = new Set(['mp3', 'wav', 'ogg', 'oga', 'm4a', 'aac', 'flac']);
    list.className = S.view === 'list' ? 'file-list-view' : 'file-grid';
    list.innerHTML = sortedFiles().map(f => {
        const encoded = encodeURIComponent(f.path);
        const ext = (f.name.includes('.') ? f.name.split('.').pop() : '').toLowerCase();
        const preview = f.isDirectory ? '' : `<button data-preview="${encoded}">Play</button>`;
        const thumbnailUrl = appUrl('/api/thumbnail?path=' + encodeURIComponent(f.path) + '&v=' + encodeURIComponent(f.modified || ''));
        let thumb = '<span class="file-icon">📄</span>';

        if (f.isDirectory) {
            thumb = '<span class="folder-icon">📁</span>';
        } else if (imageExt.has(ext)) {
            thumb = `<button class="thumb-preview" data-preview="${encoded}" aria-label="Preview ${esc(f.name)}">
                <img src="${thumbnailUrl}" alt="" loading="lazy" decoding="async">
            </button>`;
        } else if (videoExt.has(ext)) {
            const streamUrl = appUrl('/api/files/stream?path=' + encodeURIComponent(f.path));
            thumb = `<button class="thumb-preview video-thumb" data-preview="${encoded}" data-video-thumb="${streamUrl}" aria-label="Play ${esc(f.name)}">
                <img alt="" loading="lazy" decoding="async">
                <span class="video-thumb-status" aria-hidden="true">Generating thumbnail…</span>
                <span class="video-thumb-play" aria-hidden="true">▶</span>
            </button>`;
        } else if (audioExt.has(ext)) {
            thumb = '<span class="file-icon">🎵</span>';
        }

        return `<article class="file" data-path="${encoded}" tabindex="0">
        <div class="file-select"><input type="checkbox" data-sel="${encoded}" aria-label="Select ${esc(f.name)}"></div>
        <div class="thumb">${thumb}</div>
        <div class="file-info"><div class="name">${esc(f.name)}</div><div class="meta">${f.isDirectory ? 'Folder' : fmt(f.size)} · ${new Date(f.modified).toLocaleString()}</div></div>
        <div class="actions">${f.isDirectory ? `<button data-open="${encoded}">Open</button>` : `${preview}<button data-down="${encoded}">Download</button><button data-share="${encoded}">Share</button>`}<button data-menu="${encoded}" aria-label="More actions">⋮</button></div>
        </article>`;
    }).join('');

    // Images still use the authenticated server-side image thumbnail endpoint.
    document.querySelectorAll('.thumb-preview:not(.video-thumb) img').forEach(img => {
        img.addEventListener('error', () => {
            const button = img.closest('.thumb-preview');
            if (!button) return;
            button.replaceWith(Object.assign(document.createElement('span'), {
                className: 'file-icon',
                textContent: '🖼️'
            }));
        }, { once: true });
    });

    initVideoThumbnails();

    document.querySelectorAll('[data-open]').forEach(b => {
        b.addEventListener('click', e => {
            e.stopPropagation();
            loadFiles(decodeURIComponent(b.dataset.open));
        });
    });
    document.querySelectorAll('[data-preview]').forEach(b => {
        b.addEventListener('click', e => {
            e.stopPropagation();
            openPreview(decodeURIComponent(b.dataset.preview));
        });
    });
    document.querySelectorAll('[data-down]').forEach(b => {
        b.addEventListener('click', e => {
            e.stopPropagation();
            download(decodeURIComponent(b.dataset.down));
        });
    });
    document.querySelectorAll('[data-share]').forEach(b => {
        b.addEventListener('click', e => {
            e.stopPropagation();
            share(decodeURIComponent(b.dataset.share));
        });
    });
    document.querySelectorAll('[data-menu]').forEach(b => {
        b.addEventListener('click', e => {
            e.stopPropagation();
            showContextMenu(decodeURIComponent(b.dataset.menu), e.clientX, e.clientY);
        });
    });
    document.querySelectorAll('[data-sel]').forEach(c => {
        c.addEventListener('change', () => toggleSelection(decodeURIComponent(c.dataset.sel), c.checked));
    });
    document.querySelectorAll('.file').forEach(card => {
        card.addEventListener('contextmenu', e => {
            e.preventDefault();
            showContextMenu(decodeURIComponent(card.dataset.path), e.clientX, e.clientY);
        });
        card.addEventListener('dblclick', () => {
            const p = decodeURIComponent(card.dataset.path);
            const f = S.files.find(x => x.path === p);
            f?.isDirectory ? loadFiles(p) : openPreview(p);
        });
    });
    updateSelectionUI();
}

/**
 * Opens the integrated file preview dialog.
 */
async function openPreview(path) {
    const name = path.split('/').pop() || path;
    const ext = (name.includes('.') ? name.split('.').pop() : '').toLowerCase();
    const url = appUrl('/api/files/preview?path=' + encodeURIComponent(path));
    const imageExt = new Set(['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg', 'avif']);
    const videoExt = new Set(['mp4', 'webm', 'ogv', 'mov', 'm4v']);
    const audioExt = new Set(['mp3', 'wav', 'ogg', 'oga', 'm4a', 'aac', 'flac']);
    const textExt = new Set([
        'txt', 'md', 'log', 'json', 'xml', 'csv', 'html', 'htm', 'css', 'js',
        'mjs', 'ts', 'tsx', 'jsx', 'php', 'sql', 'ini', 'env', 'yml', 'yaml',
        'sh', 'bat', 'ps1', 'py', 'java', 'kt', 'c', 'h', 'cpp', 'hpp'
    ]);

    let body = '';
    if (imageExt.has(ext)) body = `<img class="preview-image" src="${url}" alt="${esc(name)}">`;
    else if (videoExt.has(ext)) body = `<div class="preview-unsupported preview-media-gate"><div class="preview-file-icon">🎬</div><p>Video playback is restricted to the cfh-player.</p><button data-open-player>Play</button><button data-preview-download>Download file</button></div>`;
    else if (audioExt.has(ext)) body = `<div class="preview-audio-wrap"><div class="preview-file-icon">🎵</div><audio class="preview-audio" src="${url}" controls preload="metadata"></audio></div>`;
    else if (ext === 'pdf') body = `<iframe class="preview-frame" src="${url}" title="${esc(name)}"></iframe>`;
    else if (textExt.has(ext)) {
        body = '<div class="preview-loading">Loading preview…</div>';
    } else {
        body = `<div class="preview-unsupported"><div class="preview-file-icon">📄</div><p>No inline preview is available for this file type.</p><button data-preview-download>Download file</button></div>`;
    }

    showPreviewDialog(name, body);

    if (textExt.has(ext)) {
        try {
            const response = await api('/api/files/preview?path=' + encodeURIComponent(path));
            const text = await response.text();
            const limit = 500000;
            const shown = text.length > limit ? text.slice(0, limit) : text;
            $('#preview-body').innerHTML = `<pre class="preview-text">${esc(shown)}${text.length > limit ? '\n\n[Preview truncated at 500 KB]' : ''}</pre>`;
        } catch (error) {
            $('#preview-body').innerHTML = `<div class="preview-error"><strong>Preview failed</strong><p>${esc(error.message)}</p></div>`;
        }
    }

    const openPlayerBtn = document.querySelector('[data-open-player]');
    if (openPlayerBtn) openPlayerBtn.addEventListener('click', () => {
        window.location.href = appUrl('/play?path=' + encodeURIComponent(path));
    });

    const dl = document.querySelector('[data-preview-download]');
    if (dl) dl.addEventListener('click', () => download(path));
}

/** Creates the modal shell used by all preview types. */
function showPreviewDialog(name, body) {
    closePreview();
    const overlay = document.createElement('div');
    overlay.id = 'preview-overlay';
    overlay.className = 'preview-overlay';
    overlay.innerHTML = `<section class="preview-dialog" role="dialog" aria-modal="true" aria-labelledby="preview-title"><header class="preview-header"><h2 id="preview-title">${esc(name)}</h2><button id="preview-close" class="preview-close" aria-label="Close preview">×</button></header><div id="preview-body" class="preview-body">${body}</div></section>`;
    document.body.appendChild(overlay);
    $('#preview-close').addEventListener('click', closePreview);
    overlay.addEventListener('click', e => {
        if (e.target === overlay) closePreview();
    });
    document.addEventListener('keydown', previewEscape);
}

/** Closes the preview and releases media elements/resources. */
function closePreview() {
    const overlay = $('#preview-overlay');
    if (overlay) overlay.remove();
    document.removeEventListener('keydown', previewEscape);
}

function previewEscape(event) {
    if (event.key === 'Escape') closePreview();
}

function esc(s) {
    return s.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

async function download(p) {
    const r = await api(`/api/files/download?path=${encodeURIComponent(p)}`);
    const blob = await r.blob(), u = URL.createObjectURL(blob), a = document.createElement('a');
    a.href = u;
    a.download = p.split('/').pop();
    a.click();
    URL.revokeObjectURL(u);
}

/**
 * Share dialog.
 *
 * A share link is a public URL: anyone holding it can view the file without an
 * account, so the link, its lifetime and the way to revoke it are all shown
 * explicitly rather than silently copied to the clipboard.
 */
const shareUI = {
    overlay: $('#share-overlay'),
    name: $('#share-file-name'),
    expiry: $('#share-expiry'),
    result: $('#share-result'),
    url: $('#share-url'),
    note: $('#share-expiry-note'),
    message: $('#share-message'),
    copy: $('#share-copy'),
    open: $('#share-open'),
    revoke: $('#share-revoke'),
    path: null,
    token: null
};

function shareStatus(type, text) {
    shareUI.message.className = `status-message ${type}`;
    shareUI.message.textContent = text;
    shareUI.message.hidden = false;
}

function shareReset() {
    shareUI.token = null;
    shareUI.result.hidden = true;
    shareUI.url.value = '';
    shareUI.note.textContent = '';
    shareUI.message.hidden = true;
    shareUI.copy.disabled = true;
    shareUI.open.hidden = true;
    shareUI.revoke.hidden = true;
}

function closeShare() {
    shareUI.overlay.hidden = true;
    shareUI.path = null;
    shareReset();
}

function formatExpiry(iso) {
    if (!iso) return 'This link never expires.';
    const when = new Date(iso);
    return Number.isNaN(when.getTime())
        ? 'This link expires.'
        : `This link expires on ${when.toLocaleString()}.`;
}

/**
 * Create a link, or fetch the one this file already has.
 *
 * `hours` omitted means "whatever the server already has, else the configured
 * default" -- the server reuses any live link for the file, so asking for a
 * specific lifetime on open would claim a lifetime the link may not have.
 */
async function shareCreate(hours) {
    shareUI.copy.disabled = true;
    shareStatus('', 'Creating link\u2026');
    try {
        const body = { filePath: shareUI.path };
        if (hours !== undefined) body.expiresInHours = hours;
        const d = await (await api('/api/shares/create', {
            method: 'POST',
            body
        })).json();
        shareUI.token = d.token;
        shareUI.url.value = d.url;
        shareUI.open.href = d.url;
        shareUI.note.textContent = formatExpiry(d.expiresAt);
        shareUI.result.hidden = false;
        shareUI.open.hidden = false;
        shareUI.revoke.hidden = false;
        shareUI.copy.disabled = false;
        shareUI.message.hidden = true;
    } catch (e) {
        shareReset();
        shareStatus('error', e.message);
    }
}

async function share(p) {
    shareUI.path = p;
    shareReset();
    shareUI.name.textContent = p.split('/').pop() || p;
    shareUI.expiry.value = '';
    shareUI.overlay.hidden = false;
    await shareCreate();
}

/**
 * Changing the lifetime replaces the link: the server reuses any live link for
 * a file, so the old token has to go before a new lifetime can take effect.
 * The previous URL stops working, which is the honest outcome to show.
 */
shareUI.expiry.addEventListener('change', async () => {
    if (!shareUI.path || shareUI.expiry.value === '') return;
    const hours = Number(shareUI.expiry.value) || 0;
    if (shareUI.token) {
        try {
            await api('/api/shares/revoke', { method: 'DELETE', body: { token: shareUI.token } });
        } catch {}
        shareUI.token = null;
    }
    await shareCreate(hours);
    shareUI.expiry.value = '';
});

shareUI.copy.addEventListener('click', async () => {
    const url = shareUI.url.value;
    if (!url) return;
    try {
        await navigator.clipboard.writeText(url);
        shareStatus('success', 'Link copied to the clipboard.');
    } catch {
        // Clipboard access needs a secure context; selecting the text lets the
        // user copy it manually over plain http.
        shareUI.url.select();
        shareStatus('', 'Press Ctrl/Cmd+C to copy the selected link.');
    }
});

shareUI.revoke.addEventListener('click', async () => {
    if (!shareUI.token) return;
    if (!await askConfirm('Revoke share link', 'The existing link will stop working immediately. Continue?', 'Revoke')) return;
    try {
        await api('/api/shares/revoke', { method: 'DELETE', body: { token: shareUI.token } });
        shareReset();
        shareStatus('success', 'Share link revoked.');
    } catch (e) {
        shareStatus('error', e.message);
    }
});

$('#share-close').addEventListener('click', closeShare);
shareUI.overlay.addEventListener('click', e => {
    if (e.target === shareUI.overlay) closeShare();
});
document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && !shareUI.overlay.hidden) closeShare();
});

function askConfirm(title, message, okText = 'Confirm') {
    return new Promise(resolve => {
        const overlay = $('#confirm-overlay');
        const dialog = $('#confirm-dialog');
        const cancelBtn = $('#confirm-cancel');
        $('#confirm-title').textContent = title;
        $('#confirm-message').textContent = message;
        $('#confirm-ok').textContent = okText;
        overlay.hidden = false;

        const onCancel = () => finish(false);
        const onSubmit = e => {
            e.preventDefault();
            finish(true);
        };
        const finish = v => {
            overlay.hidden = true;
            dialog.removeEventListener('submit', onSubmit);
            cancelBtn.removeEventListener('click', onCancel);
            resolve(v);
        };
        dialog.addEventListener('submit', onSubmit);
        cancelBtn.addEventListener('click', onCancel);
    });
}

function askInput(title, label, value = '') {
    return new Promise(resolve => {
        const overlay = $('#input-overlay');
        const dialog = $('#input-dialog');
        const cancelBtn = $('#input-cancel');
        const input = $('#dialog-input');

        $('#input-title').textContent = title;
        $('#input-label').firstChild.textContent = label;
        input.value = value;
        overlay.hidden = false;
        setTimeout(() => input.focus(), 0);

        const onCancel = () => finish('');
        const onSubmit = e => {
            e.preventDefault();
            finish(input.value.trim());
        };
        const finish = v => {
            overlay.hidden = true;
            dialog.removeEventListener('submit', onSubmit);
            cancelBtn.removeEventListener('click', onCancel);
            resolve(v);
        };
        dialog.addEventListener('submit', onSubmit);
        cancelBtn.addEventListener('click', onCancel);
    });
}

async function del(p) {
    const name = p.split('/').pop();
    if (!await askConfirm('Delete item', `Delete "${name}"? This cannot be undone.`, 'Delete')) return;
    try {
        await api('/api/files/delete', {
            method: 'DELETE',
            body: { path: p }
        });
        await loadFiles();
    } catch (e) {
        toast(e.message);
    }
}

async function ren(p) {
    const old = p.split('/').pop();
    const n = await askInput('Rename item', 'New name', old);
    if (!n || n === old) return;
    const parent = p.substring(0, p.lastIndexOf('/'));
    try {
        await api('/api/files/rename', {
            method: 'POST',
            body: { oldPath: p, newPath: `${parent}/${n}` }
        });
        await loadFiles();
    } catch (e) {
        toast(e.message);
    }
}

async function makeFolder() {
    const n = await askInput('New folder', 'Folder name');
    if (!n) return;
    try {
        await api('/api/files/mkdir', {
            method: 'POST',
            body: { path: (S.path === '/' ? '' : S.path) + '/' + n }
        });
        await loadFiles();
    } catch (e) {
        toast(e.message);
    }
}

async function deleteSelected() {
    const files = [...S.selected];
    if (!files.length) return;
    if (!await askConfirm('Delete selected', `Delete ${files.length} selected item${files.length === 1 ? '' : 's'}? This cannot be undone.`, 'Delete')) return;
    let failed = 0;
    for (const path of files) {
        try {
            await api('/api/files/delete', {
                method: 'DELETE',
                body: { path }
            });
        } catch {
            failed++;
        }
    }
    await loadFiles();
    toast(failed ? `${failed} item(s) could not be deleted` : 'Selected items deleted');
}

function showContextMenu(path, x, y) {
    const f = S.files.find(item => item.path === path);
    const menu = $('#file-context');
    if (!f) return;
    menu.innerHTML = `${f.isDirectory ? '<button data-cmd="open">Open</button>' : '<button data-cmd="preview">Preview</button><button data-cmd="download">Download</button><button data-cmd="share">Share</button>'}<button data-cmd="rename">Rename</button><button data-cmd="delete" class="danger-text">Delete</button>`;
    menu.hidden = false;
    menu.style.left = `${Math.min(x, window.innerWidth - 190)}px`;
    menu.style.top = `${Math.min(y, window.innerHeight - 240)}px`;
    menu.querySelectorAll('button').forEach(b => b.addEventListener('click', () => {
        menu.hidden = true;
        const c = b.dataset.cmd;
        if (c === 'open') loadFiles(path);
        if (c === 'preview') openPreview(path);
        if (c === 'download') download(path);
        if (c === 'share') share(path);
        if (c === 'rename') ren(path);
        if (c === 'delete') del(path);
    }));
}

document.addEventListener('click', e => {
    if (!e.target.closest('#file-context') && !e.target.closest('[data-menu]')) $('#file-context').hidden = true;
});
$('#mkdir').addEventListener('click', makeFolder);
$('#refresh').addEventListener('click', () => loadFiles());
$('#search').addEventListener('input', renderFiles);
$('#sort-files').value = S.sort;
$('#sort-files').addEventListener('change', e => {
    S.sort = e.target.value;
    localStorage.setItem('cfh_sort', S.sort);
    renderFiles();
});

function setView(v) {
    S.view = v;
    localStorage.setItem('cfh_view', v);
    $('#grid-view').classList.toggle('active', v === 'grid');
    $('#list-view').classList.toggle('active', v === 'list');
    renderFiles();
}

$('#grid-view').addEventListener('click', () => setView('grid'));
$('#list-view').addEventListener('click', () => setView('list'));
$('#grid-view').classList.toggle('active', S.view === 'grid');
$('#list-view').classList.toggle('active', S.view === 'list');
$('#select-all').addEventListener('click', () => {
    sortedFiles().forEach(f => S.selected.add(f.path));
    updateSelectionUI();
});
$('#clear-selection').addEventListener('click', () => {
    S.selected.clear();
    updateSelectionUI();
});
$('#delete-selected').addEventListener('click', deleteSelected);
$('#selection-delete').addEventListener('click', deleteSelected);

/**
 * Resumable upload controller.
 */
const uploadUI = {
    overlay: $('#upload-overlay'),
    form: $('#upload-form'),
    input: $('#upload-input'),
    submit: $('#upload-submit'),
    selection: $('#upload-selection'),
    message: $('#upload-message'),
    progressWrap: $('#upload-progress-wrap'),
    progress: $('#upload-progress'),
    percent: $('#upload-progress-percent'),
    label: $('#upload-progress-label'),
    bytes: $('#upload-progress-bytes'),
    conflict: $('#upload-conflict'),
    dropzone: $('#upload-dropzone'),
    queue: $('#upload-queue'),
    files: [],
    limits: window.CLOUDHUB_UPLOAD_LIMITS || {
        maxFiles: 20, maxMb: 2048, chunkMb: 8, retryCount: 3, conflict: 'rename'
    },
    xhr: null,
    cancelled: false,
    currentUploadId: null
};
uploadUI.conflict.value = uploadUI.limits.conflict || 'rename';

function resetUploadUI() {
    uploadUI.form.reset();
    uploadUI.files = [];
    uploadUI.queue.innerHTML = '';
    uploadUI.conflict.value = uploadUI.limits.conflict || 'rename';
    uploadUI.submit.disabled = true;
    uploadUI.selection.textContent = 'No files selected.';
    uploadUI.message.hidden = true;
    uploadUI.message.className = 'status-message';
    uploadUI.progressWrap.hidden = true;
    uploadUI.progress.value = 0;
    uploadUI.percent.textContent = '0%';
    uploadUI.bytes.textContent = '';
    uploadUI.cancelled = false;
    uploadUI.currentUploadId = null;
}

function openUpload() {
    resetUploadUI();
    $('#upload-target').textContent = S.path === '/' ? 'Root' : S.path;
    uploadUI.overlay.hidden = false;
    document.body.style.overflow = 'hidden';
}

async function closeUpload() {
    uploadUI.cancelled = true;
    if (uploadUI.xhr) {
        uploadUI.xhr.abort();
        uploadUI.xhr = null;
    }
    if (uploadUI.currentUploadId) {
        try {
            await api('/api/uploads/cancel', {
                method: 'DELETE',
                body: { id: uploadUI.currentUploadId }
            });
        } catch {}
        forgetUpload(uploadUI.currentUploadId);
        uploadUI.currentUploadId = null;
    }
    uploadUI.overlay.hidden = true;
    document.body.style.overflow = '';
}

function uploadStatus(type, message) {
    uploadUI.message.className = `status-message ${type}`;
    uploadUI.message.textContent = message;
    uploadUI.message.hidden = false;
}

function validateUploadFiles(files) {
    if (!files.length) return 'Choose at least one file.';
    if (files.length > uploadUI.limits.maxFiles) return `You can upload at most ${uploadUI.limits.maxFiles} files at once.`;
    const max = uploadUI.limits.maxMb * 1024 * 1024;
    const large = files.find(f => f.size > max);
    return large ? `${large.name} exceeds the ${fmt(max)} per-file limit.` : '';
}

function setUploadFiles(files) {
    const seen = new Set();
    uploadUI.files = [...files].filter(f => {
        const k = `${f.name}|${f.size}|${f.lastModified}`;
        if (seen.has(k)) return false;
        seen.add(k);
        return true;
    });
    renderUploadSelection();
}

function renderUploadSelection() {
    const files = uploadUI.files, problem = validateUploadFiles(files);
    uploadUI.selection.textContent = files.length ? `${files.length} file${files.length === 1 ? '' : 's'} queued` : 'No files selected.';
    uploadUI.queue.innerHTML = files.map((f, i) => `<div class="queue-item" data-q="${i}"><div class="queue-line"><span class="upload-file-name">${esc(f.name)}</span><span>${fmt(f.size)}</span></div><progress max="100" value="0"></progress><div class="queue-status">Waiting</div></div>`).join('');
    uploadUI.submit.disabled = !!problem || !files.length;
    if (problem) uploadStatus('error', problem);
    else uploadUI.message.hidden = true;
}

function uploadKey(file) {
    const raw = `${S.path}|${file.name}|${file.size}|${file.lastModified}`;
    let h1 = 2166136261, h2 = 2246822519;
    for (let i = 0; i < raw.length; i++) {
        const c = raw.charCodeAt(i);
        h1 = Math.imul(h1 ^ c, 16777619);
        h2 = Math.imul(h2 ^ c, 3266489917);
    }
    return `u_${(h1 >>> 0).toString(36)}_${(h2 >>> 0).toString(36)}_${file.size.toString(36)}`;
}

function rememberUpload(id, file) {
    localStorage.setItem(`cfh_upload_${id}`, JSON.stringify({
        name: file.name, size: file.size, path: S.path, modified: file.lastModified, at: Date.now()
    }));
}

function forgetUpload(id) {
    localStorage.removeItem(`cfh_upload_${id}`);
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

function sendChunk(id, offset, blob, onProgress) {
    return new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        uploadUI.xhr = xhr;
        xhr.open('PUT', appUrl(`/api/uploads/chunk?id=${encodeURIComponent(id)}`));
        xhr.withCredentials = true;
        xhr.setRequestHeader('Content-Type', 'application/octet-stream');
        xhr.setRequestHeader('X-Upload-Offset', String(offset));
        if (S.csrf) xhr.setRequestHeader('X-CSRF-Token', S.csrf);
        xhr.responseType = 'json';
        xhr.upload.onprogress = e => {
            if (e.lengthComputable) onProgress(e.loaded, e.total);
        };
        xhr.onload = () => {
            uploadUI.xhr = null;
            const d = xhr.response || {};
            if (xhr.status >= 200 && xhr.status < 300) resolve(d);
            else reject(Object.assign(new Error(d.error?.message || `Chunk failed (HTTP ${xhr.status})`), { status: xhr.status }));
        };
        xhr.onerror = () => {
            uploadUI.xhr = null;
            reject(Object.assign(new Error('Network error while sending chunk.'), { status: 0 }));
        };
        xhr.onabort = () => {
            uploadUI.xhr = null;
            reject(Object.assign(new Error('Upload cancelled.'), { cancelled: true }));
        };
        xhr.send(blob);
    });
}

async function uploadOneFile(file, fileIndex, fileCount, totalBefore, totalBytes) {
    const id = uploadKey(file);
    uploadUI.currentUploadId = id;
    rememberUpload(id, file);
    let state = await (await api('/api/uploads/init', {
        method: 'POST',
        body: { uploadId: id, targetPath: S.path, name: file.name, size: file.size, conflict: uploadUI.conflict.value }
    })).json();
    const chunkBytes = state.chunkBytes || uploadUI.limits.chunkMb * 1024 * 1024;
    let offset = Math.min(state.received || 0, file.size);
    while (offset < file.size) {
        if (uploadUI.cancelled) throw new Error('Upload cancelled.');
        const end = Math.min(offset + chunkBytes, file.size);
        const blob = file.slice(offset, end);
        let attempt = 0;
        while (true) {
            try {
                const confirmedOffset = offset;
                state = await sendChunk(id, offset, blob, loaded => {
                    const overall = totalBefore + confirmedOffset + loaded;
                    const pct = totalBytes ? Math.min(99, Math.floor((overall / totalBytes) * 100)) : 0;
                    uploadUI.progress.value = pct;
                    uploadUI.percent.textContent = `${pct}%`;
                    uploadUI.label.textContent = `Uploading ${fileIndex + 1} of ${fileCount}: ${file.name}`;
                    uploadUI.bytes.textContent = `${fmt(overall)} of ${fmt(totalBytes)} · chunk ${fmt(loaded)} of ${fmt(blob.size)}`;
                    const row = uploadUI.queue.querySelector(`[data-q="${fileIndex}"]`);
                    if (row) {
                        const fp = file.size ? Math.min(100, Math.floor(((confirmedOffset + loaded) / file.size) * 100)) : 100;
                        row.querySelector('progress').value = fp;
                        row.querySelector('.queue-status').textContent = `Uploading · ${fp}%`;
                    }
                });
                offset = state.received;
                break;
            } catch (err) {
                if (err.cancelled || uploadUI.cancelled) throw err;
                attempt++;
                if (attempt > uploadUI.limits.retryCount) throw new Error(`Failed to upload ${file.name} after ${uploadUI.limits.retryCount} retries: ${err.message}`);
                uploadUI.label.textContent = `Retrying chunk for ${file.name}…`;
                const retryRow = uploadUI.queue.querySelector(`[data-q="${fileIndex}"]`);
                if (retryRow) retryRow.querySelector('.queue-status').textContent = `Retrying chunk…`;
                await sleep(Math.min(5000, 500 * Math.pow(2, attempt - 1)));
                try {
                    const status = await (await api('/api/uploads/status?id=' + encodeURIComponent(id))).json();
                    if (status.received !== offset) {
                        offset = status.received;
                        break;
                    }
                } catch {}
            }
        }
    }
    uploadUI.label.textContent = `Finalising ${file.name}…`;
    const done = await (await api('/api/uploads/complete', {
        method: 'POST',
        body: { id }
    })).json();
    forgetUpload(id);
    uploadUI.currentUploadId = null;
    return done;
}

async function uploadFilesResumable(files) {
    const totalBytes = files.reduce((n, f) => n + f.size, 0);
    let completedBytes = 0;
    const results = [];
    for (let i = 0; i < files.length; i++) {
        const result = await uploadOneFile(files[i], i, files.length, completedBytes, totalBytes);
        const row = uploadUI.queue.querySelector(`[data-q="${i}"]`);
        if (row) {
            row.querySelector('progress').value = 100;
            row.querySelector('.queue-status').textContent = 'Complete';
        }
        completedBytes += files[i].size;
        results.push(result);
    }
    return results;
}

$('#upload-btn').addEventListener('click', openUpload);
$('#upload-close').addEventListener('click', closeUpload);
$('#upload-cancel').addEventListener('click', closeUpload);
uploadUI.overlay.addEventListener('click', e => {
    if (e.target === uploadUI.overlay) closeUpload();
});
uploadUI.input.addEventListener('change', () => setUploadFiles(uploadUI.input.files));
['dragenter', 'dragover'].forEach(type => uploadUI.dropzone.addEventListener(type, e => {
    e.preventDefault();
    uploadUI.dropzone.classList.add('dragging');
}));
['dragleave', 'drop'].forEach(type => uploadUI.dropzone.addEventListener(type, e => {
    e.preventDefault();
    uploadUI.dropzone.classList.remove('dragging');
}));
uploadUI.dropzone.addEventListener('drop', e => setUploadFiles(e.dataTransfer.files));
uploadUI.form.addEventListener('submit', async e => {
    e.preventDefault();
    const files = uploadUI.files;
    const problem = validateUploadFiles(files);
    if (problem) {
        uploadStatus('error', problem);
        return;
    }
    uploadUI.cancelled = false;
    uploadUI.submit.disabled = true;
    uploadUI.input.disabled = true;
    uploadUI.conflict.disabled = true;
    uploadUI.progressWrap.hidden = false;
    uploadUI.message.hidden = true;
    uploadUI.progress.value = 0;
    uploadUI.percent.textContent = '0%';
    uploadUI.label.textContent = 'Preparing resumable upload…';
    try {
        const results = await uploadFilesResumable(files);
        uploadUI.progress.value = 100;
        uploadUI.percent.textContent = '100%';
        uploadUI.label.textContent = 'Upload complete';
        const renamed = results.filter((r, i) => r.name !== files[i].name);
        uploadStatus('success', `${files.length} file(s) uploaded successfully.${renamed.length ? ' ' + renamed.length + ' conflicting file(s) were renamed.' : ''}`);
        await loadFiles();
        setTimeout(() => {
            if (!uploadUI.xhr) closeUpload();
        }, 1600);
    } catch (err) {
        uploadStatus('error', err.message || 'Upload failed. You can retry to resume from the last confirmed chunk.');
        uploadUI.label.textContent = uploadUI.cancelled ? 'Upload cancelled' : 'Upload paused/failed';
    } finally {
        uploadUI.input.disabled = false;
        uploadUI.conflict.disabled = false;
        if (!uploadUI.overlay.hidden) uploadUI.submit.disabled = false;
    }
});

async function downloadSelected() {
    if (!S.selected.size) return toast('Select files first');
    const r = await api('/api/files/download-zip', {
        method: 'POST',
        body: { files: [...S.selected] }
    });
    const blob = await r.blob(), u = URL.createObjectURL(blob), a = document.createElement('a');
    a.href = u;
    a.download = 'download.zip';
    a.click();
    URL.revokeObjectURL(u);
}
$('#zip').addEventListener('click', downloadSelected);
$('#selection-download').addEventListener('click', downloadSelected);

async function servers() {
    const list = await (await api('/api/servers')).json();
    $('#server-list').innerHTML = list.map(s => `<div class="server"><strong>${esc(s.name)}</strong> <small>${s.type}${s.isDefault ? ' · default' : ''}${s.isActive ? ' · active' : ' · inactive'}</small><div class="actions"><button data-toggle="${s.id}">Toggle</button><button data-default="${s.id}">Set default</button><button data-sdel="${s.id}">Delete</button></div></div>`).join('');
    
    document.querySelectorAll('[data-toggle]').forEach(b => b.addEventListener('click', async () => {
        await api(`/api/servers/${b.dataset.toggle}/toggle`, { method: 'POST' });
        servers();
    }));
    document.querySelectorAll('[data-default]').forEach(b => b.addEventListener('click', async () => {
        await api(`/api/servers/${b.dataset.default}/set-default`, { method: 'POST' });
        servers();
    }));
    document.querySelectorAll('[data-sdel]').forEach(b => b.addEventListener('click', async () => {
        if (await askConfirm('Delete server', 'Delete this storage server?', 'Delete')) {
            await api(`/api/servers/${b.dataset.sdel}`, { method: 'DELETE' });
            servers();
        }
    }));
}

$('#add-server').addEventListener('click', () => $('#server-form').hidden = false);
$('#cancel-server').addEventListener('click', () => $('#server-form').hidden = true);
$('#server-form').addEventListener('submit', async e => {
    e.preventDefault();
    const f = new FormData(e.target);
    try {
        await api('/api/servers', {
            method: 'POST',
            body: {
                name: f.get('name'),
                type: f.get('type'),
                config: JSON.parse(f.get('config')),
                isActive: f.get('isActive') === 'on',
                isDefault: f.get('isDefault') === 'on'
            }
        });
        e.target.reset();
        e.target.hidden = true;
        servers();
    } catch (x) {
        toast(x.message);
    }
});

async function route() {
    const p = window.CLOUDHUB_ROUTE || new URLSearchParams(location.search).get('route') || '/';
    ['files', 'servers', 'browse'].forEach(x => $(`#${x}-page`).hidden = true);
    document.querySelectorAll('nav a').forEach(a => a.classList.toggle('active', (a.dataset.route || '/') === p));
    if (p === '/servers') {
        $('#servers-page').hidden = false;
        await servers();
    } else if (p === '/browse') {
        $('#browse-page').hidden = false;
        const a = await (await api('/api/servers/active')).json();
        $('#active-servers').innerHTML = a.map(s => `<div class="server">${esc(s.name)} · ${s.type}</div>`).join('');
    } else {
        $('#files-page').hidden = false;
        await loadFiles();
    }
}

(async () => {
    try {
        const r = await fetch(appUrl('/api/auth/status'), { credentials: 'same-origin' });
        const d = await r.json();
        if (d.authenticated) {
            S.csrf = d.csrfToken || '';
            $('#login').style.display = 'none';
            await route();
        } else {
            $('#login').style.display = 'flex';
        }
    } catch {
        $('#login').style.display = 'flex';
    }
})();

