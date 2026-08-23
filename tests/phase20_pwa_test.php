<?php
declare(strict_types=1);

/**
 * Progressive web app: installable, and usable with no connection.
 *
 * Two things here are served by PHP rather than as static files, and both for
 * the same reason: they have to know where the application lives.
 * Http::basePath() can be '' or a subdirectory, and Http::assetBase() can
 * differ from it again depending on whether the document root is the project
 * or public/. A hand-written manifest cannot express that, and a worker served
 * from /assets/js/ would have a scope of /assets/js/ and control nothing.
 *
 * Behaviour was exercised in Chromium, including with the server stopped --
 * Playwright's setOffline does not reach the worker's own fetches, so an
 * uncached request still got a real 404 through it. These assertions pin the
 * parts that must not silently regress.
 */
$root = dirname(__DIR__);
$index = (string)file_get_contents($root.'/public/index.php');
$view = (string)file_get_contents($root.'/views/pages/app.php');
$sw = (string)file_get_contents($root.'/public/assets/js/sw.js');
$pwa = (string)file_get_contents($root.'/public/assets/js/pwa.js');
$app = (string)file_get_contents($root.'/public/assets/js/app.js');
$css = (string)file_get_contents($root.'/public/assets/css/app.css');
$security = (string)file_get_contents($root.'/src/Services/Security.php');

$checks = [];

// --- icons --------------------------------------------------------------
$icons = $root.'/public/assets/icons';
foreach (['icon-192.png' => 192, 'icon-512.png' => 512, 'icon-maskable-512.png' => 512, 'apple-touch-icon.png' => 180] as $file => $size) {
    $path = $icons.'/'.$file;
    $info = is_file($path) ? @getimagesize($path) : false;
    $checks["$file is a $size px PNG"] =
        $info !== false && $info[0] === $size && $info[1] === $size && $info[2] === IMAGETYPE_PNG;
}
$checks['the icons are reproducible, not hand-placed binaries'] = is_file($root.'/tools/make-icons.php');
// A launcher crops icons to its own shape, so the mark has to sit inside the
// safe zone and the background has to reach every corner.
$checks['the maskable icon fills its canvas'] = (function () use ($root): bool {
    $im = @imagecreatefrompng($root.'/public/assets/icons/icon-maskable-512.png');
    if ($im === false) return false;
    foreach ([[2, 2], [509, 2], [2, 509], [509, 509]] as [$x, $y]) {
        $c = imagecolorsforindex($im, imagecolorat($im, $x, $y));
        if ($c['alpha'] > 0 || $c['red'] !== 0x14 || $c['green'] !== 0x79 || $c['blue'] !== 0xc9) {
            imagedestroy($im);
            return false;
        }
    }
    imagedestroy($im);
    return true;
})();

// --- the manifest -------------------------------------------------------
$checks['the manifest is generated, not static'] =
    str_contains($index, "\$path === '/manifest.webmanifest'") && !is_file($root.'/public/manifest.webmanifest');
$checks['start_url and scope carry the base path'] =
    str_contains($index, "'start_url' => \$app,") && str_contains($index, "'scope' => \$app,");
$checks['it asks to launch without browser chrome'] = str_contains($index, "'display' => 'standalone'");
// Android draws the splash screen from these two plus the 512 icon.
$checks['the splash screen has a colour and a background'] =
    str_contains($index, "'theme_color' => '#1479c9'") && str_contains($index, "'background_color' => '#f7f7f8'");
$checks['a maskable icon is declared'] = str_contains($index, "'purpose' => 'maskable'");
$checks['it registers as an Android share target'] =
    str_contains($index, "'share_target' => [") && str_contains($index, "'enctype' => 'multipart/form-data'");
// Http::json() sends application/json and no-store; browsers want the manifest
// media type, and re-fetching an unchanged manifest every launch is waste.
$checks['the manifest is sent with its own media type and may be cached'] =
    str_contains($index, "header('Content-Type: application/manifest+json; charset=utf-8');")
    && str_contains($index, "header('Cache-Control: public,max-age=3600');");
$checks['the page links the manifest through the front controller'] =
    str_contains($view, 'rel="manifest" href="<?= htmlspecialchars($frontController, ENT_QUOTES) ?>manifest.webmanifest"');
// iOS ignores the manifest for both of these.
$checks['iOS gets its own icon and title'] =
    str_contains($view, 'rel="apple-touch-icon"') && str_contains($view, 'name="apple-mobile-web-app-title"');

// --- the worker route ---------------------------------------------------
// A worker's default scope is its own directory, so without this header the
// file under assets/ could never control the application routes.
$checks['the worker route widens its scope'] =
    str_contains($index, "header('Service-Worker-Allowed: '.(\$basePath === '' ? '/' : \$basePath.'/'));");
$checks['the worker is served as javascript'] =
    str_contains($index, "header('Content-Type: text/javascript; charset=utf-8');");
$checks['a stale worker cannot pin the caches'] =
    (bool)preg_match("/sw\.js.*?header\('Cache-Control: no-cache'\)/s", $index);
$checks['the manifest and worker need no session'] = (function () use ($index): bool {
    // Both must be reachable before anyone signs in: the browser fetches the
    // manifest to decide installability, and the worker registers on the
    // login screen.
    $guard = strpos($index, '$isAuthEndpoint = str_starts_with');
    return $guard !== false
        && strpos($index, "\$path === '/manifest.webmanifest'") < $guard
        && strpos($index, "\$path === '/sw.js'") < $guard;
})();
$checks['the policy already allows a worker'] = str_contains($security, "worker-src 'self'");

// --- the worker ---------------------------------------------------------
$checks['the shell is precached'] = str_contains($sw, "caches.open(SHELL)");
$checks['a navigation with no network falls back to the shell'] =
    str_contains($sw, "async function navigateWithFallback(req)") && str_contains($sw, 'caches.match(shellUrl()');
// A stale listing showing files that are no longer there is worse than a
// slightly slower load.
$checks['listings are network first, cached only as a fallback'] = (function () use ($sw): bool {
    $at = strpos($sw, 'async function listingWithFallback');
    if ($at === false) return false;
    $body = substr($sw, $at, 900);
    return strpos($body, 'await fetch(req)') < strpos($body, "idb('listings', 'readonly'");
})();
$checks['an uncached folder says it is offline rather than failing silently'] =
    str_contains($sw, "'OFFLINE'") && str_contains($sw, 'X-CloudHub-Offline');
// A cached whole-file response cannot satisfy a byte range, so video seeking
// has to reach the network.
$checks['range requests are never served from a cache'] =
    str_contains($sw, "if (req.headers.has('range')) return;");
$checks['only GET is intercepted'] = str_contains($sw, "if (req.method !== 'GET') return;");
$checks['other origins are left alone'] = str_contains($sw, 'if (url.origin !== self.location.origin) return;');
// API responses are session-authenticated: caching them by default would leave
// one person's file listing readable to the next person on a shared device.
$checks['signing out clears what was cached for that account'] =
    str_contains($sw, "if (msg.type === 'sign-out')")
    && str_contains($sw, 'caches.delete(FILES)')
    && str_contains($app, "postMessage({ type: 'sign-out' })");
$checks['old cache versions are removed on activate'] =
    str_contains($sw, "n.startsWith('cloudhub-') && !KEEP.includes(n)");
$checks['the worker is told where the assets live'] =
    str_contains($sw, "readSetting('assetBase')") && str_contains($pwa, "cfhSetting.set('assetBase', PWA_ASSETS)");

// --- registration and state ---------------------------------------------
$checks['registration uses the header-widened scope'] =
    str_contains($pwa, "navigator.serviceWorker.register(\n            `\${PWA_FRONT}sw.js`, { scope: PWA_FRONT })");
// Over plain HTTP on a LAN address registration throws; that must not take the
// rest of the application with it.
$checks['an insecure context degrades instead of breaking'] =
    str_contains($pwa, 'if (!window.isSecureContext) return null;');
$checks['a failed registration is caught'] = str_contains($pwa, 'Service worker registration failed');
// localStorage stores strings, so an interrupted upload could not be resumed
// after the app closed: the offset survived but the bytes did not.
$checks['durable storage is IndexedDB, which can hold a blob'] =
    str_contains($pwa, "indexedDB.open(CFH_DB, CFH_DB_VERSION)") && str_contains($pwa, "createObjectStore('uploads'");
// Private browsing and a full disk both make IndexedDB throw, and neither
// should break file browsing.
$checks['a storage failure is not an error to show'] =
    (bool)preg_match('/\} catch \{\s*return undefined;\s*\}/', $pwa);

// --- install and offline UI ---------------------------------------------
$checks['the install prompt is deferred, not fired immediately'] =
    str_contains($pwa, "window.addEventListener('beforeinstallprompt', event => {") && str_contains($pwa, 'event.preventDefault();');
$checks['the prompt is single-use'] = str_contains($pwa, 'pwa.installPrompt = null;');
$checks['the install button appears only when the browser offers it'] =
    str_contains($view, 'id="install-app"') && str_contains($view, 'hidden>Install<')
    && str_contains($app, "document.addEventListener('cfh-installable'");
$checks['being offline is shown'] =
    str_contains($view, 'id="offline-banner"') && str_contains($css, '#offline-banner{');
// Showing these enabled just produces a failed request and a confusing error.
$checks['actions that need the network are disabled while offline'] =
    str_contains($app, "document.querySelectorAll('#mkdir,#upload-btn,#selection-move,#selection-copy,#selection-delete,#delete-selected')");
$checks['the installed app clears the system status bar'] =
    str_contains($css, '@media(display-mode:standalone){') && str_contains($css, 'env(safe-area-inset-top)');

// --- the upload queue ---------------------------------------------------
$queue = (string)file_get_contents($root.'/public/assets/js/queue.js');

// The chunk protocol could always resume -- init reports how many bytes the
// server holds. What was missing was anything to resume from: localStorage
// stores strings, so once the tab closed the bytes were gone.
$checks['the queued file itself is stored, not just its name'] =
    str_contains($queue, 'blob: file,') && str_contains($pwa, "createObjectStore('uploads', { keyPath: 'id' })");
$checks['progress is persisted per chunk'] =
    str_contains($queue, "await put({ ...item, offset, state: 'uploading' });");
$checks['the queue resumes from what the server confirms'] =
    str_contains($queue, "let offset = Math.min(status.received || 0, item.size);");
// None of the wake-up events fire on a fresh launch, so without this an
// upload interrupted by the app closing would sit there forever -- which is
// the entire point of a durable queue.
$checks['the queue starts itself on load'] =
    str_contains($queue, "if (document.readyState === 'complete') setTimeout(run, 500);")
    && str_contains($queue, "else window.addEventListener('load', () => setTimeout(run, 500));");
$checks['a queue that survived the app closing is rendered'] =
    (bool)preg_match("/document\.addEventListener\('cfh-queue-changed', renderQueue\);\s*(\/\/[^\n]*\n\s*)*renderQueue\(\);/", $app);
$checks['it resumes when the connection returns'] =
    str_contains($queue, "window.addEventListener('online', () => run());")
    && str_contains($queue, "document.addEventListener('cfh-resume-uploads', () => run());");
$checks['it resumes when a session appears'] =
    str_contains($queue, "document.addEventListener('cfh-signed-in'") && str_contains($app, "new CustomEvent('cfh-signed-in')");
// Background Sync is Chromium-only, so it is a bonus on top of the online
// event rather than the mechanism relied upon.
$checks['background sync is requested but not depended on'] =
    str_contains($queue, "reg?.sync?.register('cloudhub-uploads')") && str_contains($queue, 'async function requestSync()');
$checks['the worker wakes an open page rather than uploading itself'] =
    str_contains($sw, "if (event.tag !== 'cloudhub-uploads') return;") && str_contains($sw, "notifyClients({ type: 'resume-uploads' })");
// A network failure is not a failure of the upload; asking the user to press
// retry for something they did not do wrong is wrong.
$checks['a network failure leaves the item queued, not failed'] =
    str_contains($queue, 'const transient = !err.permanent && (err.status === undefined || err.status >= 500 || !navigator.onLine);');
// Retrying a quota or size refusal changes nothing.
$checks['a refusal that retrying cannot fix is failed outright'] =
    str_contains($queue, 'permanent: [413, 507, 403].includes(init.status)');
$checks['only one upload runs at a time'] = str_contains($queue, 'if (state.running) return;');

// --- camera, gallery and the share target -------------------------------
// capture= is the only difference between the two inputs: it asks Android for
// the camera rather than the picker.
$checks['the camera control asks for the camera'] =
    str_contains($view, 'id="camera-input" type="file" accept="image/*" capture="environment"');
$checks['the gallery control takes several images or videos'] =
    str_contains($view, 'id="gallery-input" type="file" accept="image/*,video/*" multiple');
// The same input must accept the same file twice in a row.
$checks['picking the same file twice still fires'] = str_contains($app, "e.target.value = '';");
$checks['the worker intercepts the share POST'] =
    str_contains($sw, "if (req.method === 'POST' && new URL(req.url).searchParams.get('route') === '/share-target')")
    && str_contains($sw, 'async function acceptShare(req)');
$checks['shared files go into the durable queue, not one blocking request'] =
    (bool)preg_match("/acceptShare[\s\S]{0,1200}idb\('uploads', 'readwrite'/", $sw);
$checks['the share sheet is never left hanging on an error'] =
    (bool)preg_match("/acceptShare[\s\S]{0,1800}catch \{[\s\S]{0,200}Response\.redirect/", $sw);
// Without this Android's share sheet would land on a 404 in the window where
// the app is installed but the worker is not yet controlling the page.
$checks['a server-side fallback exists for the share target'] =
    str_contains($index, "if (\$path === '/share-target' && \$method === 'POST') {")
    && str_contains($index, "header('Location: '.\$app.'?route=%2F&shared=1&queued=0', true, 303);");

// --- keeping files offline ----------------------------------------------
// A page that opened the cache itself would have to know the version string,
// and would write into an orphaned cache the moment the worker updated.
$checks['the cache name lives only in the worker'] =
    str_contains($sw, "if (msg.type === 'keep-offline')") && !str_contains($app, 'cloudhub-files');
$checks['the page asks the worker over a message channel'] =
    str_contains($app, 'function askWorker(message)') && str_contains($app, 'new MessageChannel()');
// A worker being replaced never answers.
$checks['a worker that never answers does not hang the page'] =
    str_contains($app, 'setTimeout(() => resolve(null), 15000);');
// Without it the offline listing shows broken tiles for files that are there.
$checks['a thumbnail is kept alongside the file'] =
    str_contains($sw, 'await cache.add(new Request(thumbUrl(path)');
$checks['a file with no thumbnail still counts as kept'] =
    str_contains($sw, 'thumbUrl(path), { credentials: \'same-origin\' })).catch(() => {});');
$checks['the kept set is derived from the cache, so it cannot drift'] =
    str_contains($sw, 'async function listOffline()') && str_contains($sw, "url.searchParams.get('route') !== '/api/files/download'");
$checks['kept files are shown in the listing'] = str_contains($app, 'offline-badge');
$checks['a file that could not be kept is not shown as if it had been'] =
    str_contains($app, "res.result.failed.length ? 'That file could not be saved for offline use'");

$bad = false;
foreach ($checks as $name => $ok) {
    echo ($ok ? '[PASS] ' : '[FAIL] ').$name.PHP_EOL;
    $bad = $bad || !$ok;
}
exit($bad ? 1 : 0);
