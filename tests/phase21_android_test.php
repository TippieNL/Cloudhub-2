<?php
declare(strict_types=1);

/**
 * The Android app.
 *
 * CloudHub is a PHP server application, so the APK is a client pointing at the
 * user's own server. A Trusted Web Activity would have been the natural home
 * for the progressive web app, but it fixes one origin at build time and needs
 * Digital Asset Links verification against a publicly trusted certificate --
 * which a private or VPN-only domain cannot satisfy. Hence a WebView, and
 * hence the shims below: a WebView is not Chrome, and three things the web app
 * already relies on are inert inside one.
 *
 * The build itself proves the Java compiles and the APK is signed. These
 * assertions pin the decisions that a later edit could quietly undo.
 */
$root = dirname(__DIR__);
$android = $root.'/android/app/src/main';

$manifest = (string)@file_get_contents($android.'/AndroidManifest.xml');
$gradle = (string)@file_get_contents($root.'/android/app/build.gradle');
$bridgeJs = (string)@file_get_contents($android.'/assets/bridge.js');
$bridgeJava = (string)@file_get_contents($android.'/java/nl/tippie/cloudhub/CloudHubBridge.java');
$mainJava = (string)@file_get_contents($android.'/java/nl/tippie/cloudhub/MainActivity.java');
$configJava = (string)@file_get_contents($android.'/java/nl/tippie/cloudhub/ServerConfig.java');
$setupJava = (string)@file_get_contents($android.'/java/nl/tippie/cloudhub/SetupActivity.java');
$netConfig = (string)@file_get_contents($android.'/res/xml/network_security_config.xml');
$script = (string)@file_get_contents($root.'/tools/build-apk.sh');
$gitignore = (string)@file_get_contents($root.'/.gitignore');
$appJs = (string)@file_get_contents($root.'/public/assets/js/app.js');

$checks = [];

// --- identity -----------------------------------------------------------
$checks['the package id is nl.tippie.cloudhub'] =
    str_contains($gradle, 'applicationId "nl.tippie.cloudhub"') && str_contains($gradle, "namespace 'nl.tippie.cloudhub'");
$checks['the launcher label is CloudHub'] =
    str_contains((string)@file_get_contents($android.'/res/values/strings.xml'), '<string name="app_name">CloudHub</string>');
// ServiceWorkerController, which the offline features need, arrived in API 24.
$checks['minSdk covers the service worker API'] = str_contains($gradle, 'minSdk 24');
$checks['it targets a current platform'] = str_contains($gradle, 'targetSdk 34');

// --- what a WebView breaks, and the shims -------------------------------
// The page downloads by pointing an anchor at a blob: URL and clicking it.
// WebView's DownloadListener only ever sees http(s), so without this every
// download and the whole ZIP export silently does nothing.
$checks['blob downloads are intercepted'] =
    str_contains($bridgeJs, 'HTMLAnchorElement.prototype.click') && str_contains($bridgeJs, "href.indexOf('blob:') === 0");
$checks['a download is streamed, not sent as one string'] =
    str_contains($bridgeJs, 'function sendBlob(token, blob)') && str_contains($bridgeJava, 'appendBlobChunk');
$checks['downloads land in the Downloads folder'] =
    str_contains($bridgeJava, 'MediaStore.Downloads.EXTERNAL_CONTENT_URI');
// The name comes from page JavaScript, so "../../evil" must not become a path.
$checks['a download filename cannot escape its folder'] =
    str_contains($bridgeJava, 'static String safeFileName(String name)')
    && str_contains($bridgeJava, "cleaned.lastIndexOf('/')");
$checks['an oversized download is refused rather than allocated'] =
    str_contains($bridgeJava, 'MAX_BLOB_BYTES') && str_contains($bridgeJava, 'total > MAX_BLOB_BYTES');

// navigator.clipboard does not exist outside a secure context, and the share
// dialog's one useful action needs it.
$checks['the clipboard is shimmed only when missing'] =
    str_contains($bridgeJs, 'if (!navigator.clipboard)') && str_contains($bridgeJava, 'ClipboardManager');

// The share dialog opens a public link with target="_blank".
$checks['target=_blank links reach a real browser'] =
    str_contains($mainJava, 'setSupportMultipleWindows(true)') && str_contains($mainJava, 'onCreateWindow');
$checks['links off the server open outside the app'] =
    str_contains($mainJava, 'if (server != null && uri.toString().startsWith(server)) return false;');

// --- certificates -------------------------------------------------------
// A blanket proceed() turns every install into a silent man-in-the-middle.
$checks['an untrusted certificate is not waved through'] =
    str_contains($mainJava, 'onReceivedSslError')
    && !(bool)preg_match('/onReceivedSslError[^}]*\{\s*handler\.proceed\(\);\s*\}/s', $mainJava);
$checks['the user is shown the fingerprint before trusting it'] =
    str_contains($mainJava, 'private String fingerprintOf(SslCertificate certificate)')
    && str_contains($mainJava, 'MessageDigest.getInstance("SHA-256")');
// "Yes once" must not become "trust anything this host ever presents".
$checks['acceptance is pinned to one certificate, not one host'] =
    str_contains($configJava, 'public void pinCertificate(String host, String sha256)')
    && str_contains($mainJava, 'config.isPinned(host, fingerprint)');
$checks['a private certificate authority is trusted if the device has it'] =
    str_contains($netConfig, '<certificates src="user" />');

// --- camera and gallery -------------------------------------------------
$checks['the file chooser is handled'] = str_contains($mainJava, 'onShowFileChooser');
$checks['the camera is offered when the page asks for it'] =
    str_contains($mainJava, 'params.isCaptureEnabled()') && str_contains($mainJava, 'ACTION_IMAGE_CAPTURE');
// ACTION_IMAGE_CAPTURE needs no runtime permission unless the app declares
// one; declaring it forces a prompt that buys nothing.
// Compare the declarations, not the file: the manifest's own comment explains
// why CAMERA is absent, and naming it there must not read as declaring it.
$checks['no camera permission is demanded'] = (function () use ($manifest): bool {
    $declarations = preg_replace('/<!--.*?-->/s', '', $manifest);
    return !str_contains((string)$declarations, 'android.permission.CAMERA');
})();
$checks['only network permissions are requested'] =
    substr_count((string)preg_replace('/<!--.*?-->/s', '', $manifest), '<uses-permission') === 2
    && str_contains($manifest, 'android.permission.INTERNET');
$checks['the camera gets a scoped URI, not app storage'] =
    str_contains($manifest, 'androidx.core.content.FileProvider') && str_contains($mainJava, 'FileProvider.getUriForFile');

// --- the Android share sheet --------------------------------------------
$checks['CloudHub appears in the share sheet'] =
    str_contains($manifest, 'android.intent.action.SEND') && str_contains($manifest, 'android.intent.action.SEND_MULTIPLE');
// Reuses the durable, resumable queue rather than a second uploader written
// in Java that would have to agree with it exactly.
$checks['shared files go to the existing upload queue'] =
    str_contains($bridgeJs, 'queue.add(files, path)') && str_contains($appJs, 'window.CloudHubApp');
$checks['the page is told when a share arrives while it is open'] =
    str_contains($mainJava, 'onNewIntent') && str_contains($mainJava, 'CloudHubAndroid.collectShared()');
$checks['a share that cannot be read says so'] = str_contains($mainJava, 'share_read_failed');

// --- the server address --------------------------------------------------
// One build has to work for any deployment, and a self-hosted server moves.
$checks['the server address is not baked into the APK'] =
    str_contains($configJava, 'KEY_URL') && !preg_match('#https?://[a-z0-9.-]+\.[a-z]{2,}#i', $gradle);
$checks['a typed address is normalised'] = str_contains($configJava, 'public static String normalise(String input)');
// A typo otherwise shows up much later as a blank screen with no explanation.
$checks['the address is checked before it is accepted'] =
    str_contains($setupJava, '/?route=%2Fapi%2Fauth%2Fstatus');
// A private CA behind a VPN is the arrangement this app is for.
$checks['a certificate error does not fail the check'] =
    str_contains($setupJava, 'catch (javax.net.ssl.SSLException e)');
$checks['plain http is allowed but its cost is stated'] =
    str_contains($netConfig, 'cleartextTrafficPermitted="true"')
    && str_contains((string)@file_get_contents($android.'/res/values/strings.xml'), 'refuses to run a service worker');
$checks['the server can be changed later'] = str_contains($mainJava, 'menu_change_server');

// --- build ---------------------------------------------------------------
$checks['the build script is executable'] = is_executable($root.'/tools/build-apk.sh');
$checks['it installs the SDK only when missing'] =
    str_contains($script, 'if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]');
$checks['it generates a keystore only once'] = str_contains($script, 'if [ ! -f "$KEYSTORE" ]');
// A keystore committed to a repository is a published signing key.
$checks['signing material is never committed'] =
    str_contains($gitignore, '/android/keystore.jks') && str_contains($gitignore, '/android/keystore.properties');
$checks['the SDK and build output are not committed'] =
    str_contains($gitignore, '/android-sdk/') && str_contains($gitignore, '/android/app/build/');
$checks['the build verifies what it produced'] =
    str_contains($script, 'apksigner') && str_contains($script, 'aapt2') && str_contains($script, 'dump badging');
// Generated from the same mark, so the web app and the installed app cannot
// drift apart.
$checks['launcher icons come from the shared generator'] =
    str_contains((string)@file_get_contents($root.'/tools/make-icons.php'), 'mipmap-')
    && is_file($android.'/res/mipmap-xxxhdpi/ic_launcher.png');
$checks['an adaptive icon is provided for modern launchers'] =
    is_file($android.'/res/mipmap-anydpi-v26/ic_launcher.xml')
    && is_file($android.'/res/mipmap-xxxhdpi/ic_launcher_foreground.png');

// --- the web app is untouched by the app ---------------------------------
// bridge.js is injected by the shell; the page must keep working in a plain
// browser where none of it exists.
$checks['the web app does not depend on the Android bridge'] =
    !str_contains($appJs, 'CloudHubNative') && !str_contains($appJs, '__cloudhubBridgeReady');
$checks['the shims run only inside the app'] = str_contains($bridgeJs, 'if (!native || window.__cloudhubBridgeReady) return;');

$bad = false;
foreach ($checks as $name => $ok) {
    echo ($ok ? '[PASS] ' : '[FAIL] ').$name.PHP_EOL;
    $bad = $bad || !$ok;
}
exit($bad ? 1 : 0);
