<?php
declare(strict_types=1);

/**
 * The native Android client.
 *
 * The first Android build was a WebView, which meant re-supplying browser
 * behaviour by hand: blob downloads, the clipboard and target="_blank" all had
 * to be shimmed before the page worked. This replaces it with a native client
 * against the REST API, so none of that indirection exists.
 *
 * The API layer is verified for real rather than asserted: the JVM tests in
 * android/app/src/test run against a live CloudHub and exercise sign in, the
 * chunked upload including an interrupted one, download, rename, move, copy,
 * search, trash and restore, and share create/revoke. These checks pin the
 * decisions that a later edit could quietly undo.
 */
$root = dirname(__DIR__);
$android = $root.'/android/app/src/main';
$kotlin = $android.'/java/nl/tippie/cloudhub';

$read = fn(string $path): string => (string)@file_get_contents($path);

$manifest = $read($android.'/AndroidManifest.xml');
$gradle = $read($root.'/android/app/build.gradle');
$api = $read($kotlin.'/net/CloudHubApi.kt');
$client = $read($kotlin.'/net/CloudHubClient.kt');
$trust = $read($kotlin.'/net/CertificateTrust.kt');
$error = $read($kotlin.'/net/ApiError.kt');
$models = $read($kotlin.'/net/Models.kt');
$stores = $read($kotlin.'/data/Stores.kt');
$uploads = $read($kotlin.'/work/Uploads.kt');
$app = $read($kotlin.'/App.kt');
$main = $read($kotlin.'/MainActivity.kt');
$tests = $read($root.'/android/app/src/test/java/nl/tippie/cloudhub/ApiIntegrationTest.kt');
$player = $read($kotlin.'/ui/PlayerScreen.kt');
$files = $read($kotlin.'/ui/FilesScreen.kt');
$paths = $read($android.'/res/xml/file_paths.xml');
$pure = $read($root.'/android/app/src/test/java/nl/tippie/cloudhub/PlayerAndStagingTest.kt');
$script = $read($root.'/tools/build-apk.sh');
$gitignore = $read($root.'/.gitignore');

$checks = [];

// --- it really is native ------------------------------------------------
$checks['there is no WebView left'] =
    !is_file($kotlin.'/CloudHubBridge.java')
    && !is_file($android.'/assets/bridge.js')
    && !str_contains($main, 'WebView');
$checks['the UI is Compose'] =
    str_contains($gradle, 'buildFeatures { compose true }') && str_contains($main, 'setContent {');
$checks['it is a Kotlin project'] =
    str_contains($gradle, "id 'org.jetbrains.kotlin.android'") && is_file($kotlin.'/MainActivity.kt');

// --- identity ------------------------------------------------------------
$checks['the package id is unchanged, so it installs as an update'] =
    str_contains($gradle, 'applicationId "nl.tippie.cloudhub"');
// Pinned as "at least 2" rather than a fixed number, so a later release does
// not have to edit the test to say the same thing.
$checks['the version was raised past the WebView build'] =
    (bool)preg_match('/versionCode (\d+)/', $gradle, $v) && (int)$v[1] >= 2;
$checks['minSdk still covers Media3'] = str_contains($gradle, 'minSdk 24');

// --- one client, shared ---------------------------------------------------
// Separate clients are how you get a file list that loads and thumbnails that
// all fail with 401.
$checks['thumbnails use the same client as the API'] =
    str_contains($app, 'okHttpClient { client.okHttp }');
$checks['playback uses the same client as the API'] =
    str_contains($player, 'OkHttpDataSource.Factory(client.okHttp)');
$checks['the session is kept across restarts'] =
    str_contains($client, 'cookieJar(') && str_contains($stores, 'class PersistentCookieStore');
// The server rotates the session periodically; storing whatever arrives keeps
// up with it without anything to maintain by hand.
$checks['an expired cookie is dropped rather than replayed'] =
    str_contains($stores, 'if (cookie.expiresAt > System.currentTimeMillis())');
$checks['mutating calls carry the CSRF token'] =
    str_contains($api, 'header("X-CSRF-Token", client.csrfToken)');

// --- certificates ---------------------------------------------------------
// Trust-all would turn every install into a man-in-the-middle.
$checks['the platform trust manager gets first refusal'] =
    str_contains($trust, 'platform.checkServerTrusted(chain, authType)');
$checks['an unknown certificate raises a question, not a pass'] =
    str_contains($trust, 'throw UntrustedCertificate(')
    && !(bool)preg_match('/checkServerTrusted[^}]*\{\s*\}/s', $trust);
$checks['acceptance is pinned per certificate, not per host'] =
    str_contains($trust, 'if (pins.isPinned(fingerprint)) return')
    && str_contains($stores, 'class CertificatePins');
$checks['the fingerprint is shown before it is trusted'] =
    str_contains($read($kotlin.'/ui/Screens.kt'), 'certificate.fingerprint');

// --- the API contract -----------------------------------------------------
// The portable front-controller form works whether or not the deployment has
// URL rewriting; Http::requestPath() prefers it.
$checks['routes use the portable front-controller form'] =
    str_contains($api, 'addQueryParameter("route", route)');
/*
 * The trailing slash is load-bearing. Requests go to the front controller,
 * which is a directory; asking for it without the slash makes a web server
 * answer 301 to add one, and OkHttp follows a 301 by re-sending as a GET with
 * the body dropped. The query survives, so route=/api/auth/login arrived as a
 * GET, matched nothing, and came back "API endpoint not found" -- naming an
 * endpoint that plainly exists. Every write broke that way on a subdirectory
 * install: login, upload (PUT) and delete (DELETE) alike.
 */
$checks['requests address the front controller, not the directory'] =
    str_contains($api, 'val front = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"');
$checks['a redirected write is reported for what it is'] =
    str_contains($api, 'code = "REDIRECTED"');
$checks['the stored address is canonical'] =
    str_contains($stores, "url.toHttpUrl().toString().trimEnd('/')");
// The bug was invisible at a root install, which is all the live suite used to
// cover; these run with no server at all.
$checks['url building is covered without needing a server'] =
    is_file($root.'/android/app/src/test/java/nl/tippie/cloudhub/UrlBuildingTest.kt')
    && str_contains((string)@file_get_contents($root.'/android/app/src/test/java/nl/tippie/cloudhub/UrlBuildingTest.kt'),
        'a subdirectory install addresses the front controller, not the directory');
$checks['the error envelope is parsed, not guessed at'] =
    str_contains($error, 'val error: Body? = null') && str_contains($error, 'response.header("X-Request-ID")');
// A quota refusal is a 5xx that the caller can act on.
$checks['out of space is distinguishable'] = str_contains($error, 'val isOutOfSpace get() = status == 507');
// Two mistakes the live tests caught: the route is a DELETE, and it is keyed
// by token because a link outlives the path it was made from.
$checks['share revoke is a DELETE taking a token'] =
    str_contains($api, 'suspend fun revokeShare(token: String)')
    && str_contains($api, 'request("/api/shares/revoke", "DELETE"');
$checks['a bulk move reports failures per item'] =
    str_contains($models, 'val failed: List<Failure> = emptyList()');

// --- uploads ---------------------------------------------------------------
$checks['uploads resume from what the server confirms'] =
    str_contains($uploads, 'var offset = status.received.coerceAtMost(item.size)');
$checks['a chunk is streamed, not held in memory'] =
    str_contains($uploads, 'private fun File.slice(start: Long, end: Long): RequestBody')
    && str_contains($uploads, 'sink.write(buffer, 0, read)');
// WorkManager runs without the app open, which the web queue cannot do.
$checks['uploads outlive the app being closed'] =
    str_contains($uploads, 'class UploadWorker') && str_contains($uploads, 'CoroutineWorker');
// A share-sheet content:// grant is frequently not persistable, so the bytes
// are copied rather than the URI remembered.
$checks['queued bytes are staged, not referenced'] =
    str_contains($uploads, 'fun stage(context: Context, uri: Uri, name: String)')
    && str_contains($uploads, 'input.copyTo(output)');
$checks['a refusal that retrying cannot fix is dropped'] =
    str_contains($uploads, 'if (e.isOutOfSpace || e.status == 413 || e.isForbidden)');
$checks['a stalled chunk does not spin'] =
    str_contains($uploads, 'if (status.received <= offset) return Result.retry()');

// --- permissions -----------------------------------------------------------
$declarations = (string)preg_replace('/<!--.*?-->/s', '', $manifest);
$checks['no camera permission is demanded'] = !str_contains($declarations, 'android.permission.CAMERA');
$checks['no storage permission is demanded'] = !str_contains($declarations, 'EXTERNAL_STORAGE');
// The system photo picker returns only what was chosen, and the camera app owns
// the capture: none of this needs a media or recording permission.
$checks['no media permission is demanded'] =
    !str_contains($declarations, 'READ_MEDIA_IMAGES') && !str_contains($declarations, 'READ_MEDIA_VIDEO');
$checks['no recording permission is demanded'] =
    !str_contains($declarations, 'android.permission.RECORD_AUDIO');
$checks['only network permissions are requested'] = substr_count($declarations, '<uses-permission') === 2;
$checks['downloads go through MediaStore'] = str_contains($main, 'MediaStore.Downloads.EXTERNAL_CONTENT_URI');

// --- the share sheet --------------------------------------------------------
$checks['CloudHub appears in the share sheet'] =
    str_contains($manifest, 'android.intent.action.SEND')
    && str_contains($manifest, 'android.intent.action.SEND_MULTIPLE');
$checks['shared files join the upload queue'] =
    str_contains($main, 'private fun takeSharedFiles(intent: Intent?)') && str_contains($main, 'enqueue(uris)');

// --- the server address ------------------------------------------------------
$checks['the address is not baked into the APK'] =
    str_contains($stores, 'var serverUrl') && !preg_match('#https?://[a-z0-9.-]+\.[a-z]{2,}#i', $gradle);
$checks['a typed address is normalised'] = str_contains($stores, 'object ServerAddress');
$checks['plain http is flagged rather than silently accepted'] = str_contains($stores, 'fun isInsecure');

// --- the tests that do the real work -----------------------------------------
$checks['the API is tested against a live server'] =
    str_contains($tests, 'CLOUDHUB_TEST_URL') && str_contains($tests, 'class ApiIntegrationTest');
$checks['an interrupted upload is tested, not assumed'] =
    str_contains($tests, 'an interrupted upload resumes from the server offset')
    && str_contains($tests, 'assertEquals(90_000L, resumed.received)');
// Without a server the tests skip, so an ordinary build stays green.
$checks['the tests skip rather than fail with no server'] =
    str_contains($tests, 'assumeTrue("set CLOUDHUB_TEST_URL to run the API tests", baseUrl != null)');
$checks['the tests work in a folder of their own'] =
    str_contains($tests, 'private val scratch = "/_apitest_');

// --- build -------------------------------------------------------------------
$checks['the build script still builds and verifies'] =
    str_contains($script, 'assembleRelease') && str_contains($script, 'apksigner');
$checks['signing material is never committed'] =
    str_contains($gitignore, '/android/keystore.jks') && str_contains($gitignore, '/android/keystore.properties');
$checks['launcher icons still come from the shared generator'] =
    str_contains($read($root.'/tools/make-icons.php'), 'mipmap-')
    && is_file($android.'/res/mipmap-xxxhdpi/ic_launcher.png');

// --- the video player ---------------------------------------------------------
// A bare PlayerView plays and does nothing else; most of what a player needs is
// already in Media3 and merely has to be switched on.
$checks['the player offers fullscreen'] =
    str_contains($player, 'setFullscreenButtonClickListener');
// Back to un-maximise, not back out of the film.
$checks['back leaves fullscreen before it leaves the video'] =
    str_contains($player, 'BackHandler(enabled = fullscreen)');
$checks['the screen does not dim mid-film'] =
    str_contains($player, 'FLAG_KEEP_SCREEN_ON')
    && str_contains($player, 'clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)');
$checks['the player yields to calls and other apps'] =
    str_contains($player, '.setAudioAttributes(')
    && str_contains($player, '/* handleAudioFocus = */ true');
$checks['subtitle tracks can be turned on'] = str_contains($player, 'setShowSubtitleButton(true)');
$checks['there is no playlist to skip through'] =
    str_contains($player, 'setShowNextButton(false)') && str_contains($player, 'setShowPreviousButton(false)');
$checks['double-tap seeks by a set step'] =
    str_contains($player, 'setSeekBackIncrementMs(SEEK_STEP_MS)')
    && str_contains($player, 'setSeekForwardIncrementMs(SEEK_STEP_MS)');
// An overlay drawn over the visible transport controls swallows every button
// press, so the gesture zones exist only while the controller is hidden.
$checks['the seek gesture is gated on the controls being hidden'] =
    str_contains($player, 'if (!controlsVisible) {');

// --- resuming a half-watched video --------------------------------------------
$checks['positions are remembered per file'] =
    str_contains($stores, 'fun rememberResumePosition(path: String, positionMs: Long)')
    && str_contains($stores, 'fun resumePositionOf(path: String): Long');
$checks['a finished video is not resumed'] =
    str_contains($player, 'settings.forgetResumePosition(entry.path)');
$checks['the resume decision is a pure function'] =
    str_contains($stores, 'object ResumePolicy')
    && str_contains($stores, 'fun shouldResume(positionMs: Long, durationMs: Long): Boolean');
$checks['the remembered set is bounded'] =
    str_contains($stores, 'ResumePolicy.prune(updated, ResumePolicy.MAX_REMEMBERED)');
$checks['a resume can be declined'] = str_contains($player, 'Start over');

// --- uploading from the phone ---------------------------------------------------
// GetMultipleContents opens the document browser, not the gallery: photos were
// technically reachable and practically not.
$checks['the gallery picker asks for photos and videos'] =
    str_contains($main, 'ActivityResultContracts.PickMultipleVisualMedia')
    && str_contains($main, 'ActivityResultContracts.PickVisualMedia.ImageAndVideo');
$checks['the file browser is still there for a PDF'] =
    str_contains($main, 'ActivityResultContracts.GetMultipleContents()');
// TakePicturePreview hands back a ~150px thumbnail; uploading that is a bug.
$checks['the thumbnail-only camera contract is gone'] =
    !str_contains($main, 'TakePicturePreview');
$checks['photos and clips can both be captured'] =
    str_contains($main, 'ActivityResultContracts.TakePicture()')
    && str_contains($main, 'ActivityResultContracts.CaptureVideo()');
$checks['captures are written through a FileProvider'] =
    str_contains($main, 'FileProvider.getUriForFile(this, "$packageName.fileprovider"');
$checks['the provider is declared and scoped to the cache'] =
    str_contains($manifest, 'androidx.core.content.FileProvider')
    && str_contains($manifest, '${applicationId}.fileprovider')
    && str_contains($paths, '<cache-path name="captures" path="captures/" />');
$checks['a cancelled capture leaves nothing behind'] =
    str_contains($main, 'if (ok) enqueue(listOf(uri)) else deleteCapture(uri)');
// Five ways to add something needs labels; a camera glyph beside a film glyph
// says nothing about which one records.
$checks['the add actions are labelled, not bare icons'] =
    str_contains($files, 'SheetAction(Icons.Default.PhotoLibrary, "Photos & videos"')
    && str_contains($files, 'SheetAction(Icons.Default.PhotoCamera, "Take a photo"')
    && str_contains($files, 'SheetAction(Icons.Default.Videocam, "Record a video"')
    && str_contains($files, 'SheetAction(Icons.Default.UploadFile, "Any file"')
    && str_contains($files, 'SheetAction(Icons.Default.CreateNewFolder, "New folder"');

// --- staging a large video ------------------------------------------------------
// A picker URI cannot be made persistable, so the bytes are copied -- and a
// 4 GB clip needs 4 GB free while it is staged.
$checks['there is room to stage before the copy starts'] =
    str_contains($uploads, 'object StagingSpace')
    && str_contains($uploads, 'if (!StagingSpace.hasRoom(free, needed)) return StageResult.NoRoom(needed, free)');
$checks['a full phone says so instead of failing obscurely'] =
    str_contains($uploads, 'is NoRoom') || str_contains($uploads, 'StageResult.NoRoom')
    && str_contains($main, 'Not enough space on this phone');
$checks['a half-copied file is not left in app storage'] =
    str_contains($uploads, 'target.delete()');

// --- the tests that need no server ------------------------------------------------
$checks['the resume and space rules are tested on every build'] =
    str_contains($pure, 'class ResumePolicyTest') && str_contains($pure, 'class StagingSpaceTest')
    && !str_contains($pure, 'CLOUDHUB_TEST_URL');


$bad = false;
foreach ($checks as $name => $ok) {
    echo ($ok ? '[PASS] ' : '[FAIL] ').$name.PHP_EOL;
    $bad = $bad || !$ok;
}
exit($bad ? 1 : 0);
