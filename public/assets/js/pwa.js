/*
 * Progressive web app support: install, offline state, and the durable store
 * that the upload queue and the offline file list are both built on.
 *
 * Loaded before app.js so the worker registers even if the application script
 * later fails, and so the shared IndexedDB helpers exist before anything uses
 * them.
 */

const PWA_BASE = (window.CLOUDHUB_BASE || '').replace(/\/$/, '');
const PWA_FRONT = window.CLOUDHUB_FRONT || (PWA_BASE ? `${PWA_BASE}/` : '/');
const PWA_ASSETS = (window.CLOUDHUB_ASSETS ?? PWA_BASE).replace(/\/$/, '');

/* ---- durable store ------------------------------------------------------
 *
 * IndexedDB rather than localStorage because the upload queue has to hold the
 * file's bytes. localStorage stores strings only, so today an interrupted
 * upload cannot actually be resumed after the app closes: the id and the
 * server-confirmed offset survive, but the browser has no handle on the file
 * any more. A Blob in IndexedDB survives.
 */
const CFH_DB = 'cloudhub';
const CFH_DB_VERSION = 1;

function cfhOpenDb() {
    return new Promise((resolve, reject) => {
        const req = indexedDB.open(CFH_DB, CFH_DB_VERSION);
        req.onupgradeneeded = () => {
            const db = req.result;
            if (!db.objectStoreNames.contains('settings')) db.createObjectStore('settings');
            if (!db.objectStoreNames.contains('uploads')) db.createObjectStore('uploads', { keyPath: 'id' });
            if (!db.objectStoreNames.contains('listings')) db.createObjectStore('listings');
        };
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => reject(req.error);
        req.onblocked = () => reject(new Error('The database is in use by another tab.'));
    });
}

/**
 * Run one transaction and resolve with its request's result.
 *
 * Every caller treats a storage failure as "this feature is unavailable"
 * rather than an error to show, so this resolves undefined instead of
 * rejecting: private browsing and a full disk both make IndexedDB throw, and
 * neither should break file browsing.
 */
async function cfhStore(store, mode, fn) {
    try {
        const db = await cfhOpenDb();
        return await new Promise((resolve, reject) => {
            const tx = db.transaction(store, mode);
            const req = fn(tx.objectStore(store));
            tx.oncomplete = () => resolve(req && 'result' in req ? req.result : undefined);
            tx.onerror = () => reject(tx.error);
            tx.onabort = () => reject(tx.error);
        });
    } catch {
        return undefined;
    }
}

const cfhSetting = {
    get: key => cfhStore('settings', 'readonly', s => s.get(key)).then(v => v === undefined ? null : v),
    set: (key, value) => cfhStore('settings', 'readwrite', s => s.put(value, key)),
};

/* ---- service worker ----------------------------------------------------- */

const pwa = {
    registration: null,
    installPrompt: null,
    online: navigator.onLine,
};

async function registerWorker() {
    if (!('serviceWorker' in navigator)) return null;
    // A worker needs a secure context. Over plain HTTP on a LAN address the
    // registration throws, which must not take the rest of the app with it.
    if (!window.isSecureContext) return null;

    try {
        // Scope comes from the Service-Worker-Allowed header the route sends,
        // so the worker at /assets/js/sw.js can control the whole application.
        const registration = await navigator.serviceWorker.register(
            `${PWA_FRONT}sw.js`, { scope: PWA_FRONT });
        pwa.registration = registration;

        // The worker cannot work out where the static assets live; only the
        // page knows, because the server told it.
        await cfhSetting.set('assetBase', PWA_ASSETS);
        navigator.serviceWorker.controller?.postMessage({ type: 'base', base: PWA_FRONT });
        return registration;
    } catch (err) {
        console.warn('Service worker registration failed:', err.message);
        return null;
    }
}

/* ---- install ------------------------------------------------------------
 *
 * Chromium fires beforeinstallprompt when the app qualifies and lets the page
 * defer it. iOS has no such event, so there the button explains the manual
 * Share > Add to Home Screen route instead of pretending to install.
 */
window.addEventListener('beforeinstallprompt', event => {
    event.preventDefault();
    pwa.installPrompt = event;
    document.dispatchEvent(new CustomEvent('cfh-installable'));
});

window.addEventListener('appinstalled', () => {
    pwa.installPrompt = null;
    document.dispatchEvent(new CustomEvent('cfh-installed'));
});

async function promptInstall() {
    if (!pwa.installPrompt) return 'unavailable';
    pwa.installPrompt.prompt();
    const { outcome } = await pwa.installPrompt.userChoice;
    // The event is single-use: a dismissed prompt cannot be replayed, and the
    // browser sends a fresh one when it decides to offer again.
    pwa.installPrompt = null;
    return outcome;
}

/** True when the app is running from the home screen rather than a browser tab. */
function isInstalled() {
    return window.matchMedia('(display-mode: standalone)').matches
        || window.matchMedia('(display-mode: minimal-ui)').matches
        || window.navigator.standalone === true;
}

/* ---- connection state --------------------------------------------------- */

function setOnline(online) {
    pwa.online = online;
    document.documentElement.classList.toggle('offline', !online);
    document.dispatchEvent(new CustomEvent('cfh-connection', { detail: { online } }));
}

window.addEventListener('online', () => setOnline(true));
window.addEventListener('offline', () => setOnline(false));

navigator.serviceWorker?.addEventListener('message', event => {
    if (event.data?.type === 'resume-uploads') {
        document.dispatchEvent(new CustomEvent('cfh-resume-uploads'));
    }
});

setOnline(navigator.onLine);
registerWorker();

window.CloudHubPWA = { pwa, promptInstall, isInstalled, cfhStore, cfhSetting, registerWorker };
