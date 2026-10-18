<?php
declare(strict_types=1);

/**
 * The photo viewer: immersive, zoomable, and something to act from.
 *
 * The arithmetic is tested on the JVM -- the geometry in PhotoZoomTest, the
 * gestures applied to it in PhotoZoomStateTest, the row of photos after an
 * edit in ViewerRulesTest, and the wording of the details in
 * PhotoDetailsTest. These pin the wiring none of them can reach: the
 * decisions a later edit could quietly undo.
 */
$root = dirname(__DIR__);
$android = $root.'/android/app/src/main';
$kotlin = $android.'/java/nl/tippie/cloudhub';
$readKt = fn(string $path): string => (string)@file_get_contents($kotlin.'/'.$path);
$readTest = fn(string $name): string =>
    (string)@file_get_contents($root.'/android/app/src/test/java/nl/tippie/cloudhub/'.$name);

$viewer = $readKt('ui/ViewerScreens.kt');
$page = $readKt('ui/PhotoPage.kt');
$zoom = $readKt('ui/PhotoZoom.kt');
$bars = $readKt('ui/SystemBars.kt');
$copies = $readKt('ui/LocalCopies.kt');
$facts = $readKt('ui/PhotoFacts.kt');
$rules = $readKt('ui/ViewerRules.kt');
$dialogs = $readKt('ui/Dialogs.kt');
$main = $readKt('MainActivity.kt');
$api = $readKt('net/CloudHubApi.kt');
$models = $readKt('net/Models.kt');
$paths = (string)@file_get_contents($android.'/res/xml/file_paths.xml');
$manifest = (string)@file_get_contents($android.'/AndroidManifest.xml');

$checks = [];

/* --- immersive -----------------------------------------------------------
 *
 * The photo has the whole screen and the controls are drawn over it, so they
 * come and go without moving the picture -- and the system bars go with them.
 */
$checks['a tap hides the controls and the system bars together'] =
    str_contains($viewer, 'SystemBarsHidden(hidden = !chrome)')
    && str_contains($viewer, 'onTap = { chrome = !chrome }');
$checks['zooming in gets the controls out of the way'] =
    str_contains($viewer, 'onZoomedIn = { chrome = false }')
    && str_contains($page, 'snapshotFlow { PhotoZoom.isZoomed(zoom.scale) }');
$checks['hidden bars come back for a moment with a swipe'] =
    str_contains($bars, 'BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE')
    && str_contains($bars, 'controller.hide(WindowInsetsCompat.Type.systemBars())');
// A viewer that left immersive mode on would leave the app without a status bar.
$checks['the bars always come back when the viewer is left'] =
    (bool)preg_match('/onDispose \{\s*controller\.show\(WindowInsetsCompat\.Type\.systemBars\(\)\)/', $bars);
// A share sheet or a chooser coming and going can hand the bars back.
$checks['immersive mode is applied again on every resume'] =
    str_contains($bars, 'LifecycleResumeEffect(controller, hidden)');
$checks['the controls do not slide as the bars come and go'] =
    str_contains($viewer, 'WindowInsets.systemBarsIgnoringVisibility');
// Dark icons on the black viewer were all but invisible in light mode.
$checks['bar icons are light over the black screens'] =
    str_contains($main, 'val onBlack = screen is Screen.Images || screen is Screen.Play')
    && str_contains($main, 'isAppearanceLightStatusBars = !dark && !onBlack');

/* --- gestures ------------------------------------------------------------ */

$checks['a pinch zooms about the fingers'] =
    str_contains($page, 'event.calculateCentroid(useCurrent = true)')
    && str_contains($page, 'PhotoZoom.zoomedAbout(offsetX, focusX, scale, next)')
    && str_contains($readTest('PhotoZoomStateTest.kt'), 'a pinch zooms into the fingers, not the middle of the screen');
// It only listens: who owns a drag stays transformable's decision and the
// pager's, which is what keeps a swipe turning the page.
$checks['the gesture observer consumes nothing'] = (function () use ($page): bool {
    $start = strpos($page, 'private suspend fun PointerInputScope.followFingers(');
    $end = $start === false ? false : strpos($page, "\n}\n", $start);
    if ($start === false || $end === false) return false;
    $body = substr($page, $start, $end - $start);
    // A call that consumes -- consume(), consumeAllChanges() -- not the word
    // inside awaitFirstDown(requireUnconsumed = false).
    return str_contains($body, 'PointerEventPass.Initial') && !preg_match('/\.consume\w*\(/', $body);
})();
$checks['a double tap zooms into the point tapped'] =
    str_contains($page, 'onDoubleTap = { at -> if (zoomable) zoom.toggleAt(at, animate = !reduceMotion) }')
    && str_contains($readTest('PhotoZoomStateTest.kt'), 'a double tap zooms into what was tapped');
$checks['a zoomed photo can be flung, and stops at its edge'] =
    str_contains($page, 'tracker.calculateVelocity()')
    && str_contains($page, 'if (kept != value) cancelAnimation()');
$checks['every layer keeps the photo\'s shape'] =
    substr_count($page, 'contentScale = ContentScale.Fit') === 3
    && str_contains($zoom, 'min(viewportW / imageW, viewportH / imageH)');
// A zoomed photo is larger than its page, and would be drawn over its neighbours.
$checks['a zoomed page does not draw over the next one'] = str_contains($page, '.clipToBounds()');

/* --- what is drawn -------------------------------------------------------- */

$checks['the grid thumbnail stands in while the photo loads'] =
    str_contains($page, 'api.thumbnailUrl(entry).toString()')
    && str_contains($page, 'if (photo !is AsyncImagePainter.State.Success) {');
// The thumbnail goes the moment the photo lands, so a fade would come up from black.
$checks['the photo replaces its thumbnail without a dip to black'] =
    str_contains($page, '.crossfade(false)');
$checks['a zoomed photo is drawn from a larger decode, capped'] =
    str_contains($page, '.size(PhotoZoom.DETAIL_EDGE, PhotoZoom.DETAIL_EDGE)')
    && str_contains($page, '.precision(Precision.INEXACT)')
    && str_contains($zoom, 'const val DETAIL_EDGE = 4096');
// Filed under the URL alone, the two decodes would push each other out of memory.
$checks['the two decodes are cached apart'] = str_contains($page, '.memoryCacheKey("$previewUrl#sharp")');
$checks['the photos either side are loading before they are shown'] =
    str_contains($viewer, 'beyondViewportPageCount = 1');
$checks['pages are keyed by path, so a delete does not hand its zoom on'] =
    str_contains($viewer, 'key = { photos[it].path }');
// A file replaced under the same name must not be drawn, or shared, from the
// cache's copy of the old one.
$checks['the preview URL carries the file version'] =
    str_contains($api, 'fun previewUrl(path: String, version: String? = null): HttpUrl')
    && str_contains($page, 'api.previewUrl(entry.path, entry.modified)');
$checks['a photo that failed can be tried again'] =
    str_contains($page, 'onRetry = { attempt++ }')
    && str_contains($page, '.setParameter("attempt", attempt, memoryCacheKey = null)');

/* --- the counter ------------------------------------------------------------ */

$checks['there is a counter, and not for a single photo'] =
    str_contains($rules, 'fun counter(index: Int, count: Int): String?')
    && str_contains($rules, 'if (count <= 1) null else')
    && str_contains($viewer, 'ViewerRules.counter(pager.currentPage, photos.size)')
    && str_contains($readTest('ViewerRulesTest.kt'), 'assertEquals("3 of 12", ViewerRules.counter(2, 12))');

/* --- the actions ------------------------------------------------------------ */

foreach (['Share', 'Download', 'Details', 'More'] as $label) {
    $checks["the bar offers $label"] = str_contains($viewer, '"'.$label.'"');
}
// Sharing a photo means sharing its bytes: the app it goes to cannot sign in
// to the server.
$checks['Share sends the photo itself'] =
    str_contains($copies, 'Intent(Intent.ACTION_SEND)')
    && str_contains($copies, 'putExtra(Intent.EXTRA_STREAM, uri)')
    && str_contains($copies, 'addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)');
$checks['the bytes are reused from the cache rather than downloaded twice'] =
    str_contains($copies, 'context.imageLoader.diskCache?.openSnapshot(cachedAs)')
    && str_contains($copies, 'api.openDownload(entry.path)');
$checks['a half-copied photo is never handed out'] = str_contains($copies, 'folder.deleteRecursively()');
// One folder for these, beside the camera's; nothing else in app storage.
$checks['only the shared copies folder is exposed'] =
    str_contains($paths, '<cache-path name="shared" path="shared/" />')
    && str_contains($paths, '<cache-path name="captures" path="captures/" />')
    && substr_count($paths, '-path ') === 2;
$checks['copies are cleared after a day'] =
    str_contains($copies, 'const val KEEP_MS = 24L * 60 * 60 * 1000')
    && str_contains($copies, 'if (isStale(it.lastModified(), now)) it.deleteRecursively()');
$checks['Download is the file menu\'s download'] =
    str_contains($main, 'onDownload = { download(it) },')
    && str_contains($viewer, 'onDownload(entry)');
$checks['More offers a public link, another app, and Set as'] =
    str_contains($viewer, '"Share link…"') && str_contains($viewer, '"Open with…"') && str_contains($viewer, '"Set as…"')
    && str_contains($copies, 'Intent(Intent.ACTION_VIEW)') && str_contains($copies, 'Intent(Intent.ACTION_ATTACH_DATA)');
// The viewer role can look and share, but not rename or delete.
$checks['rename and delete are for accounts that can write'] = (function () use ($viewer): bool {
    $gate = strpos($viewer, 'if (canWrite) {');
    $rename = strpos($viewer, '"Rename…"');
    $delete = strpos($viewer, '"Delete", danger = true');
    return $gate !== false && $rename !== false && $delete !== false && $gate < $rename && $gate < $delete;
})() && str_contains($main, 'canWrite = state.canWrite,');
$checks['a delete asks first'] = str_contains($viewer, 'Text("Delete this photo?")');
// The server's reply names the trash entry, which is what Undo restores.
$checks['a delete into the trash can be undone'] =
    str_contains($models, 'val id: String? = null')
    && str_contains($viewer, 'val trashId = result.id?.takeIf { result.trashed }')
    && str_contains($viewer, 'api.restore(trashId)');
// The server picks a free name when the one asked for is taken.
$checks['a rename uses the path the server chose'] =
    str_contains($models, 'val path: String? = null')
    && str_contains($viewer, 'val path = result.path ?: asked');
// Including when the viewer closed before the answer came back.
$checks['the folder underneath is refreshed after an edit'] =
    str_contains($main, 'onFilesChanged = { model.refresh() },')
    && str_contains($viewer, 'DisposableEffect(Unit) { onDispose { if (unsettled) filesChanged() } }');

/* --- details ------------------------------------------------------------------ */

$checks['details read the photo from the cache, not a new request'] =
    str_contains($facts, 'context.imageLoader.diskCache?.openSnapshot(url)')
    && str_contains($viewer, 'PropertiesSheet(entry = entry, onDismiss = { details = null }, photo = facts)');
$checks['details include what the camera wrote'] =
    str_contains($facts, 'ExifInterface.TAG_DATETIME_ORIGINAL')
    && str_contains($facts, 'ExifInterface.TAG_MODEL')
    && str_contains($dialogs, 'Property("Dimensions", it)')
    && str_contains($dialogs, 'Property("Camera", it)');
// A portrait photo stored sideways is a portrait photo.
$checks['dimensions are reported the right way up'] =
    str_contains($facts, 'if (orientation in SIDEWAYS) width = height.also { height = width }');

/* --- leaving -------------------------------------------------------------------- */

// System Back went through the app's own handler, which pops the screen
// without saying which photo was on show -- so the folder did not come back to it.
$checks['Back by gesture says which photo the viewer closed on'] =
    str_contains($viewer, 'BackHandler(onBack = close)')
    && str_contains($viewer, 'val close = { onBack(photos.getOrNull(pager.currentPage)?.path) }');

/* --- and nothing new to ask for ------------------------------------------------- */

$declarations = (string)preg_replace('/<!--.*?-->/s', '', $manifest);
$checks['sharing needs no new permission'] = substr_count($declarations, '<uses-permission') === 3;

$bad = false;
foreach ($checks as $name => $ok) {
    echo ($ok ? '[PASS] ' : '[FAIL] ').$name.PHP_EOL;
    $bad = $bad || !$ok;
}
exit($bad ? 1 : 0);
