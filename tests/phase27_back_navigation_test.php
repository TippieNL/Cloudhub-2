<?php
declare(strict_types=1);

/**
 * Back, and the edge swipe that performs it.
 *
 * The rules themselves are tested on the JVM in BackRulesTest. What cannot be
 * tested there is the wiring: that a handler exists at all, that it is the one
 * the screens go through, and that nothing has quietly gone back to assigning
 * a screen variable -- which is what made a Back press close the app from
 * everywhere except a fullscreen video.
 */
$root = dirname(__DIR__);
$kotlin = $root.'/android/app/src/main/java/nl/tippie/cloudhub';
$readKt = fn(string $path): string => (string)@file_get_contents($kotlin.'/'.$path);

$main = $readKt('MainActivity.kt');
$rules = $readKt('ui/BackRules.kt');
$player = $readKt('ui/PlayerScreen.kt');
$viewer = $readKt('ui/ViewerScreens.kt');
$manifest = (string)@file_get_contents($root.'/android/app/src/main/AndroidManifest.xml');
$tests = (string)@file_get_contents($root.'/android/app/src/test/java/nl/tippie/cloudhub/BackRulesTest.kt');

$checks = [];

/* --- there is a handler at all ---------------------------------------------
 *
 * Before this, the only BackHandler in the app was the player's, enabled only
 * while fullscreen. Everywhere else Back reached the Activity and finished it.
 */
$checks['the app handles Back rather than letting it close the Activity'] =
    str_contains($main, 'BackHandler(enabled = outcome != BackRules.Outcome.LEAVE_THE_APP)')
    && str_contains($main, 'import androidx.activity.compose.BackHandler');
$checks['what Back does is decided in one place, with tests'] =
    str_contains($rules, 'object BackRules')
    && str_contains($rules, 'fun next(where: Where): Outcome')
    && str_contains($tests, 'class BackRulesTest');
// Every outcome must be acted on, or a press somewhere does nothing at all.
foreach (['CLEAR_SELECTION' => 'model.clearSelection()',
          'CLEAR_SEARCH' => 'model.clearSearch()',
          'GO_UP_A_FOLDER' => 'model.open(BackRules.parentOf(state.path))',
          'PREVIOUS_SCREEN' => 'back()'] as $outcome => $action) {
    $checks["Back $outcome is wired to something"] =
        str_contains($main, "BackRules.Outcome.$outcome -> $action");
}

/* --- the app is still leavable ---------------------------------------------
 *
 * The opposite failure, and the worse one: a Back press at the root must close
 * the app exactly as it does in every other app. That means *not* handling it,
 * so the system runs its own animation.
 */
$checks['at the root the system is left to close the app'] =
    str_contains($rules, 'LEAVE_THE_APP')
    && str_contains($main, 'BackRules.Outcome.LEAVE_THE_APP -> Unit')
    && !str_contains($main, 'finishAffinity')
    && !str_contains($main, 'override fun onBackPressed');
// The modern path: with this declared, the same callbacks drive the
// predictive-back animation on Android 13+ instead of a stale API.
$checks['the platform back API is opted into'] =
    str_contains($manifest, 'android:enableOnBackInvokedCallback="true"');

/* --- screens remember where they came from ---------------------------------
 *
 * Storage opened from Settings used to return to the file list, having
 * forgotten Settings entirely.
 */
$checks['screens are a stack, not a single value'] =
    str_contains($main, 'val stack = remember {')
    && str_contains($main, 'mutableStateListOf<Screen>')
    && str_contains($main, 'BackRules.pushed(stack.toList(), next)')
    && str_contains($main, 'BackRules.popped(stack.toList())');
// The regression that started this: a screen assigned rather than pushed has
// no history behind it, so Back falls through to the Activity.
$checks['no screen is assigned behind the stack\'s back'] =
    !preg_match('/\bscreen = Screen\./', $main);
// Counted as "every onBack ends in back()" rather than by literal spelling:
// two of them also record the file to come back to, and a screen whose Back
// does anything but pop is one you cannot leave properly.
$checks['opening a screen pushes and leaving it pops'] =
    substr_count($main, 'go(Screen.') >= 6
    && preg_match_all('/onBack = \{[^}]*back\(\)/', $main) >= 5;
// Signing in or out is a new beginning: Back must not walk into the session
// that just ended.
$checks['signing in and out clears the history'] =
    str_contains($main, 'fun reset(next: Screen)')
    && str_contains($main, 'reset(Screen.SignIn)')
    && str_contains($main, 'reset(Screen.Files)');

/* --- coming back where you left off ----------------------------------------
 *
 * A list's scroll position belongs to the composable that owns it, and opening
 * a video takes that composable out of the composition -- so scrolling to the
 * fortieth clip, watching it, and pressing Back landed you at the first.
 */
$files = $readKt('ui/FilesScreen.kt');
$memory = $readKt('ui/ScrollMemory.kt');
$viewerScreens = $readKt('ui/ViewerScreens.kt');
$memoryTests = (string)@file_get_contents($root.'/android/app/src/test/java/nl/tippie/cloudhub/ScrollMemoryTest.kt');

$checks['a screen\'s state is kept while you are on another one'] =
    str_contains($main, 'val screenState = rememberSaveableStateHolder()')
    && str_contains($main, 'screenState.SaveableStateProvider(screen.key)');
// Filed under the screen, not under the visit, or the drawer handed back on
// return is a different one.
$checks['every screen has a stable key to file it under'] =
    str_contains($main, 'private sealed interface Screen {')
    && str_contains($main, 'val key: String')
    && str_contains($main, 'override val key = "files"');
// The list state has to be the one the effects read; created inline, it is a
// different object each time and nothing can restore it.
$checks['the list keeps a scroll position that can be restored'] =
    str_contains($files, 'val grid = rememberLazyGridState()')
    && str_contains($files, 'val list = rememberLazyListState()')
    && str_contains($files, 'state = grid,')
    && str_contains($files, 'state = list,')
    && !str_contains($files, 'state = rememberLazyGridState()');
$checks['a position is kept per folder, with tests'] =
    str_contains($memory, 'class ScrollMemory')
    && str_contains($files, 'memory.remember(state.path, index, at)')
    && str_contains($files, 'memory.placeOf(state.path)')
    && str_contains($memoryTests, 'each folder keeps its own place');
// Recording before restoring writes "the top" over the place being restored,
// every single time.
// snapshotFlow re-runs on state read *inside* its block: a position captured
// outside is one value the flow never sees change, so the folder would be
// recorded once, at the top, and never again.
$checks['the position is read inside the flow, not captured outside it'] =
    str_contains($files, 'snapshotFlow { Triple(firstVisible(), offset(), state.visible.size) }');
$checks['recording waits until the place has been restored'] =
    str_contains($files, 'if (restored && size > 0) memory.remember')
    && str_contains($files, 'restored = true');
// A folder that has not arrived cannot be scrolled.
$checks['restoring waits for the folder to arrive'] =
    str_contains($files, 'snapshotFlow { state.visible.size }.first { it > 0 }');
$checks['what is remembered survives the screen being rebuilt'] =
    str_contains($files, 'rememberSaveable(saver = ScrollMemorySaver)')
    && str_contains($files, 'private val ScrollMemorySaver = listSaver<ScrollMemory, Any>');
// Swiping through thirty photos and coming back should land on the one you
// ended at, not the one you opened.
$checks['the viewer says which file it was closed on'] =
    str_contains($viewerScreens, 'onBack: (String?) -> Unit')
    && str_contains($viewerScreens, 'onBack(images.getOrNull(pager.currentPage)?.path)')
    && str_contains($main, 'onBack = { photo -> reveal = photo; back() }')
    && str_contains($main, 'onBack = { reveal = current.entry.path; back() }');
$checks['and the list comes back to it'] =
    str_contains($files, 'ScrollMemory.indexOfPath(entries.map { it.path }, revealPath)')
    && str_contains($files, 'ScrollMemory.shouldReveal(target, firstVisible(), lastVisible())')
    && str_contains($memoryTests, 'a photo already on screen is left where it is');
/*
 * ...but a screen that has been closed is over.
 *
 * Kept, its state outranks the arguments the screen is next opened with. The
 * viewer remembers which photo is on show, so opening the next photo restored
 * the page from last time and showed the previous photo instead.
 */
$checks['a closed screen does not keep its state for the next visit'] =
    str_contains($rules, 'fun forgotten(known: Set<String>, live: Set<String>): Set<String> = known - live')
    && str_contains($main, 'BackRules.forgotten(known, live).forEach { screenState.removeState(it) }')
    && str_contains($tests, "a popped screen's state is not kept for the next visit");
// Done after composition: the screen on its way out saves its state as it is
// disposed, so removing it any earlier is simply undone.
$checks['the state is dropped after the screen has gone, not before'] =
    str_contains($main, 'LaunchedEffect(stack.map { it.key }) {')
    && !str_contains($main, 'screenState.removeState(leaving');
// Someone else's place is not yours.
$checks['signing out drops the remembered place'] =
    str_contains($main, 'screenState.removeState(Screen.Files.key)');

/* --- the swipe that showed nothing ------------------------------------------
 *
 * The viewer is a pager, so a sideways swipe should show the next photo. The
 * zoom detector on each photo consumed every drag past the touch slop -- zoomed
 * in or not -- so the pager under it never saw one and the swipe did nothing.
 */
$zoom = $readKt('ui/PhotoZoom.kt');
$zoomTests = (string)@file_get_contents($root.'/android/app/src/test/java/nl/tippie/cloudhub/PhotoZoomTest.kt');

$checks['a drag is offered to the pager rather than swallowed'] =
    str_contains($viewerScreens, '.transformable(')
    && str_contains($viewerScreens, 'canPan = { pan ->')
    && str_contains($viewerScreens, 'PhotoZoom.panBelongsToPhoto(scale, offsetX, pan.x, pan.y, size.width.toFloat())');
// The detector that consumed everything.
$checks['the gesture detector that ate the swipe is gone'] =
    !str_contains($viewerScreens, 'detectTransformGestures');
$checks['who owns a drag is decided in one place, with tests'] =
    str_contains($zoom, 'object PhotoZoom')
    && str_contains($zoom, 'fun panBelongsToPhoto(')
    && str_contains($zoomTests, "a drag across a photo that fits the screen is the pager's");
// Zooming has to keep working, or the fix trades one gesture for another.
$checks['pinch and double tap still zoom'] =
    str_contains($viewerScreens, 'rememberTransformableState')
    && str_contains($viewerScreens, 'PhotoZoom.scaled(scale, zoomChange)')
    && str_contains($viewerScreens, 'onDoubleTap = {')
    && str_contains($zoomTests, 'double tap zooms in, and again zooms out');
// A picture flung off the screen leaves nothing to drag back.
$checks['a zoomed photo cannot be dragged off the screen'] =
    str_contains($viewerScreens, 'PhotoZoom.clampPan(offsetX + pan.x, scale, size.width.toFloat())')
    && str_contains($zoomTests, 'a photo cannot be dragged off the screen');
// A vertical drag handed to a pager is a zoomed photo that cannot pan up.
$checks['a vertical drag stays with the photo'] =
    str_contains($zoom, 'if (abs(dragY) > abs(dragX)) return true')
    && str_contains($zoomTests, "a vertical drag on a zoomed photo is never the pager's");

/* --- the gestures that conflict --------------------------------------------
 *
 * Under gesture navigation the strips down each side belong to Android. In a
 * gallery, where swiping sideways is the whole interaction, a photo swiped
 * from the edge would leave the viewer instead of turning the page.
 */
$checks['the photo pager asks for the edge strips back'] =
    str_contains($viewer, 'systemGestureExclusion()')
    && str_contains($viewer, 'import androidx.compose.foundation.systemGestureExclusion');
// Only the pager: excluding elsewhere would be switching the gesture off.
$checks['nothing else excludes the system gestures'] =
    substr_count($readKt('ui/FilesScreen.kt'), 'systemGestureExclusion') === 0
    && substr_count($player, 'systemGestureExclusion') === 0;
// The player's own handler must still win over the app's while fullscreen,
// or Back leaves the video instead of un-maximising it.
$checks['fullscreen still un-maximises before it leaves'] =
    str_contains($player, 'BackHandler(enabled = fullscreen)');

$bad = false;
foreach ($checks as $name => $ok) {
    echo ($ok ? '[PASS] ' : '[FAIL] ').$name.PHP_EOL;
    $bad = $bad || !$ok;
}
exit($bad ? 1 : 0);
