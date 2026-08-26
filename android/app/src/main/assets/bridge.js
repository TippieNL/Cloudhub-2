/*
 * Shims injected into every CloudHub page inside the Android WebView.
 *
 * The web application is not modified for the app: it has to keep working in
 * an ordinary browser, where none of this exists and none of it is needed.
 * Everything here is guarded so that it only replaces something that is
 * actually missing or broken in a WebView.
 */
(function () {
    'use strict';

    var native = window.CloudHubNative;
    if (!native || window.__cloudhubBridgeReady) return;
    window.__cloudhubBridgeReady = true;

    /* ---- downloads ------------------------------------------------------
     *
     * The page downloads by fetching a blob, pointing an anchor at a blob: URL
     * and clicking it. WebView's DownloadListener only ever sees http(s), so
     * that anchor does nothing at all -- every download and the whole ZIP
     * export fail silently, which is worse than an error.
     *
     * Intercepting click at the prototype means both call sites are covered
     * without the page knowing, and a real browser is untouched.
     */
    var CHUNK = 512 * 1024;
    var nativeClick = HTMLElement.prototype.click;

    HTMLAnchorElement.prototype.click = function () {
        var href = this.getAttribute('href') || '';
        var isDownload = this.hasAttribute('download');
        if (isDownload && (href.indexOf('blob:') === 0 || href.indexOf('data:') === 0)) {
            saveThroughAndroid(href, this.getAttribute('download') || 'download');
            return;
        }
        return nativeClick.call(this);
    };

    function saveThroughAndroid(href, name) {
        fetch(href)
            .then(function (r) { return r.blob(); })
            .then(function (blob) {
                var token = 'dl_' + Date.now() + '_' + Math.random().toString(36).slice(2);
                return sendBlob(token, blob).then(function () {
                    var error = native.saveBlob(token, name, blob.type || '');
                    if (error) notify('Could not save the file: ' + error);
                });
            })
            .catch(function (err) { notify('Could not save the file: ' + err.message); });
    }

    /** Walk the blob in slices; one base64 string for a video would not fit. */
    function sendBlob(token, blob) {
        var offset = 0;
        function step() {
            if (offset >= blob.size) return Promise.resolve();
            var slice = blob.slice(offset, Math.min(offset + CHUNK, blob.size));
            offset += CHUNK;
            return slice.arrayBuffer().then(function (buffer) {
                if (!native.appendBlobChunk(token, toBase64(buffer))) {
                    throw new Error('the file is too large to save');
                }
                return step();
            });
        }
        return step();
    }

    function toBase64(buffer) {
        var bytes = new Uint8Array(buffer);
        var binary = '';
        // String.fromCharCode.apply blows the argument limit on a large slice.
        for (var i = 0; i < bytes.length; i += 8192) {
            binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 8192));
        }
        return btoa(binary);
    }

    /* ---- clipboard ------------------------------------------------------
     *
     * navigator.clipboard does not exist outside a secure context, and the
     * share dialog's only useful action is copying the link. Replaced only
     * when it is missing, so an HTTPS origin keeps the real implementation.
     */
    if (!navigator.clipboard) {
        try {
            Object.defineProperty(navigator, 'clipboard', {
                value: {
                    writeText: function (text) {
                        return native.copyText(String(text))
                            ? Promise.resolve()
                            : Promise.reject(new Error('The clipboard is unavailable'));
                    }
                },
                configurable: true
            });
        } catch (e) { /* a browser that refuses the definition already has one */ }
    }

    /* ---- files from the Android share sheet ------------------------------
     *
     * Rebuilt into real File objects and handed to the page's existing durable
     * upload queue, so a shared video gets the same resumable, survives-a-
     * restart treatment as one picked inside the app.
     */
    function collectShared() {
        var count = native.sharedCount();
        if (!count) return;

        var queue = window.CloudHubQueue;
        if (!queue) return;   // the app script has not loaded yet; retried below

        var files = [];
        for (var i = 0; i < count; i++) {
            var size = native.sharedSize(i);
            var parts = [];
            for (var offset = 0; offset < size; offset += CHUNK) {
                var b64 = native.sharedChunk(i, offset, CHUNK);
                if (!b64) break;
                parts.push(fromBase64(b64));
            }
            files.push(new File(parts, native.sharedName(i) || 'shared',
                { type: native.sharedMime(i) || 'application/octet-stream' }));
        }
        native.clearShared();

        if (!files.length) return;
        var path = (window.CloudHubApp && window.CloudHubApp.path) || '/';
        queue.add(files, path).then(function () {
            notify(files.length === 1
                ? 'Shared file queued for upload'
                : files.length + ' shared files queued for upload');
        });
    }

    function fromBase64(b64) {
        var binary = atob(b64);
        var bytes = new Uint8Array(binary.length);
        for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
        return bytes;
    }

    function notify(message) {
        // The page's own toast if it is up, so app messages look alike.
        if (typeof window.cfhToast === 'function') window.cfhToast(message);
        else console.log('[CloudHub] ' + message);
    }

    // The queue lives in queue.js, which may not have run yet on a cold start.
    var attempts = 0;
    (function waitForQueue() {
        if (window.CloudHubQueue) return collectShared();
        if (++attempts > 40) return;      // ~10s, then give up quietly
        setTimeout(waitForQueue, 250);
    })();

    window.CloudHubAndroid = { collectShared: collectShared };
})();
