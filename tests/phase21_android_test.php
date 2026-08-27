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
$signIn = $read($kotlin.'/ui/SignInScreen.kt');
$signInVm = $read($kotlin.'/ui/SignInViewModel.kt');
$theme = $read($kotlin.'/ui/Theme.kt');
$signInTests = $read($root.'/android/app/src/test/java/nl/tippie/cloudhub/SignInStateTest.kt');
$themes = $read($android.'/res/values/themes.xml');
$cards = $read($kotlin.'/ui/FileCards.kt');
$browser = $read($kotlin.'/ui/FilesBrowserState.kt');
$viewModel = $read($kotlin.'/ui/FilesViewModel.kt');
$dialogs = $read($kotlin.'/ui/Dialogs.kt');
$browserTests = $read($root.'/android/app/src/test/java/nl/tippie/cloudhub/FilesBrowserStateTest.kt');
$brand = $read($kotlin.'/ui/Brand.kt');
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


// --- the sign-in screen ---------------------------------------------------------
// CloudHub has no self-service accounts: they are made on the server with
// tools/create-admin.php. A "Sign up" or "Forgot password" link would be a
// promise the product cannot keep.
$signInProse = (string)preg_replace('#/\*.*?\*/|//[^\n]*#s', '', $signIn);
foreach (['Sign up', 'Sign Up', 'Create account', 'Register', 'Forgot password', 'Reset password'] as $absent) {
    $checks["the sign-in screen does not offer \"$absent\""] = !str_contains($signInProse, $absent);
}
// The API accepts a username and a password and nothing else, so a social row
// would be buttons that cannot work.
$checks['no social sign-in buttons are shown'] =
    !preg_match('/\b(Google|Apple|Facebook|GitHub|Microsoft|OAuth|continue with)\b/i', $signInProse);
// Removing it would strand anyone whose server moved: it is the only route
// back to the address screen.
$checks['the way back to the server address survives'] =
    str_contains($signIn, 'Use a different server') && str_contains($signIn, 'onChangeServer');
$checks['the password can be revealed to check a typo'] =
    str_contains($signIn, 'Icons.Default.VisibilityOff') && str_contains($signIn, 'PasswordVisualTransformation()');
// Usernames, not email addresses -- an email keyboard would be wrong for them.
$checks['the username field is typed for a username'] =
    str_contains($signIn, 'capitalization = KeyboardCapitalization.None')
    && str_contains($signIn, 'autoCorrect = false')
    && !str_contains($signIn, 'KeyboardType.Email');
$checks['focus moves from the username to the password'] =
    str_contains($signIn, 'onNext = { focus.moveFocus(FocusDirection.Down) }');
$checks['the keyboard cannot cover the fields'] = str_contains($signIn, '.imePadding()');
$checks['the card stops growing on a tablet'] =
    str_contains($signIn, 'BoxWithConstraints') && str_contains($signIn, 'Modifier.width(400.dp)');
// Drawn once and shared by the sign-in screen and the empty folder, from the
// same unit-box geometry as the launcher icon.
$checks['the mark is the launcher icon, not a stock glyph'] =
    str_contains($brand, 'fun BrandMark') && !str_contains($brand, 'Icons.Default.CloudUpload')
    && str_contains($signIn, 'BrandMark(') && str_contains($files, 'BrandMark(');

// --- animation ------------------------------------------------------------------
$checks['the entrance is staggered from one driver'] =
    str_contains($signIn, 'private fun Staggered') && substr_count($signIn, 'Staggered(entrance, step =') >= 5;
$checks['the background moves on its own'] = str_contains($signIn, 'rememberInfiniteTransition');
$checks['the button becomes a loader and then a check'] =
    str_contains($signIn, 'AnimatedContent') && str_contains($signIn, 'SignInUiState.Submitting::class')
    && str_contains($signIn, 'SignInUiState.Success::class');
$checks['a failure shakes rather than just printing'] = str_contains($signIn, 'keyframes {');
$checks['validation errors grow into place'] =
    str_contains($signIn, 'expandVertically') && str_contains($signIn, 'shrinkVertically');
// Accessibility > Remove animations sets the animator scale to zero.
$checks['Reduce Motion is honoured'] =
    str_contains($theme, 'ANIMATOR_DURATION_SCALE') && str_contains($theme, 'LocalReduceMotion')
    && substr_count($signIn, 'reduceMotion') >= 4;

// --- state, kept out of the composable --------------------------------------------
$checks['authentication logic is not in the screen'] =
    str_contains($signInVm, 'class SignInViewModel') && !str_contains($signIn, 'api.login');
$checks['two taps cannot become two sign-in requests'] =
    str_contains($signInVm, 'if (_state.value is SignInUiState.Submitting) return');
$checks['a password is never trimmed'] =
    str_contains($signInVm, 'return Result.Valid(name, password)');
$checks['only the username is ever remembered'] =
    str_contains($stores, 'var rememberedUsername')
    && !preg_match('/put(String|Boolean)\("(remembered_)?password/', $stores);

// --- the launch path ----------------------------------------------------------------
// Rendering sign-in before the session check answers meant an already signed-in
// launch flashed a half-animated login form on its way to the files.
$checks['the login screen is not shown before the session check answers'] =
    str_contains($main, 'data object Restoring : Screen')
    && str_contains($main, 'if (screen is Screen.Restoring)')
    && str_contains($main, 'else screen = Screen.SignIn');
$checks['the app draws edge to edge'] =
    str_contains($main, 'WindowCompat.setDecorFitsSystemWindows(window, false)')
    && str_contains($themes, '@android:color/transparent');
// Restoring `true` here would lay every screen out differently after a video.
$checks['leaving a video does not undo edge to edge'] =
    !str_contains($player, 'setDecorFitsSystemWindows(window, true)');
$checks['transparent bars still get readable icons'] =
    str_contains($main, 'isAppearanceLightStatusBars = !dark');

$checks['the sign-in rules are tested without a server'] =
    str_contains($signInTests, 'class SignInStateTest')
    && str_contains($signInTests, 'a second tap during a request is ignored')
    && !str_contains($signInTests, 'CLOUDHUB_TEST_URL');


// --- the file browser -----------------------------------------------------------
// A skeleton that is a second layout merely resembling the card is how a
// crossfade becomes a jump. One scaffold, filled two ways, cannot drift.
$checks['the card and its skeleton share one layout'] =
    str_contains($cards, 'fun FileCardScaffold(')
    && substr_count($cards, 'FileCardScaffold(') >= 3;
$checks['the skeleton is sized from the real grid, not hard-coded'] =
    str_contains($browser, 'fun skeletonCount(columns: Int, viewportHeightDp: Int, cardHeightDp: Int)')
    && str_contains($files, 'skeletonCount(columns,');
// Thirty cards each driving an infinite transition is thirty clocks for one effect.
$checks['the shimmer is one animation for the whole screen'] =
    substr_count($cards, 'rememberInfiniteTransition') === 1
    && str_contains($cards, 'fun rememberShimmer(): Float')
    && str_contains($files, 'val progress = rememberShimmer()');
$checks['the shimmer holds still under Reduce Motion'] =
    str_contains($cards, 'if (LocalReduceMotion.current) return SHIMMER_STILL');
// TalkBack reading a dozen empty cards as content is worse than silence.
$checks['placeholders are not announced as content'] =
    str_contains($cards, 'clearAndSetSemantics { }')
    && str_contains($files, 'contentDescription = "Loading this folder"');
$checks['the skeleton crossfades rather than snapping'] =
    str_contains($files, 'AnimatedContent') && str_contains($files, 'Shown.SKELETON ->');
$checks['a fast load never flashes a skeleton'] =
    str_contains($browser, 'const val DELAY_MS') && str_contains($browser, 'const val MIN_SHOWN_MS')
    && str_contains($files, 'SkeletonTiming.lingerMs(');

// --- the states, decided once ------------------------------------------------------
// The defect this replaced: a failed listing went to a snackbar that cleared
// itself, and the screen then said "This folder is empty" about files it had
// never managed to ask for.
$checks['a failed listing cannot render as an empty folder'] =
    str_contains($browser, 'load == LoadState.FAILED -> Shown.ERROR')
    && str_contains($viewModel, 'val loadError: String?')
    && !str_contains($viewModel, 'copy(message = null, error = null)');
$checks['a refresh does not blank the screen'] =
    str_contains($browser, 'load == LoadState.LOADING && !hasEntries -> Shown.SKELETON');
$checks['a mistyped search is not called an empty folder'] =
    str_contains($browser, 'filtering -> Shown.NO_MATCHES');
$checks['a failure offers a retry'] =
    str_contains($viewModel, 'fun retry()') && str_contains($files, 'Text("Retry")');
$checks['the browser states are tested without a server'] =
    str_contains($browserTests, 'class BrowserStateTest')
    && str_contains($browserTests, 'a failed load is an error, never an empty folder')
    && !str_contains($browserTests, 'CLOUDHUB_TEST_URL');

// --- what the browser offers ----------------------------------------------------------
// CloudHub stores folders and nothing else; an album action would be a button
// with nothing behind it.
$checks['the add sheet offers no album'] =
    !preg_match('/\b(album|gallery)\b/i', (string)preg_replace('#/\*.*?\*/|//[^\n]*#s', '', $files));
$propertiesBody = (string)@substr($dialogs, (int)strpos($dialogs, 'fun PropertiesSheet('), 900);
$checks['file details are shown from what was already fetched'] =
    str_contains($dialogs, 'fun PropertiesSheet(') && !str_contains($propertiesBody, 'api.');
$checks['thumbnails hold their place while they load'] =
    str_contains($cards, 'AsyncImagePainter.State.Loading ->')
    && str_contains($cards, 'SkeletonBlock(progress, Modifier.fillMaxSize()');
$checks['folders get a mark rather than a blank tile'] =
    str_contains($cards, 'private fun FolderGlyph');
$checks['filtering re-flows the grid rather than snapping'] =
    str_contains($files, 'Modifier.animateItem()');
// An entrance on every item means scrolling pays for an animation forever.
$checks['the entrance stagger stops after the first screenful'] =
    str_contains($files, 'STAGGER_LIMIT') && str_contains($files, 'if (index >= STAGGER_LIMIT');
$checks['breadcrumb segments are targets, and scroll'] =
    str_contains($files, 'private fun Crumb(') && str_contains($files, 'horizontalScroll(scroll)')
    && str_contains($files, 'scroll.animateScrollTo(scroll.maxValue)');
$checks['the grid adapts to the screen it is on'] =
    str_contains($files, 'GridCells.Adaptive(minSize = GRID_MIN_CELL)');


$bad = false;
foreach ($checks as $name => $ok) {
    echo ($ok ? '[PASS] ' : '[FAIL] ').$name.PHP_EOL;
    $bad = $bad || !$ok;
}
exit($bad ? 1 : 0);
