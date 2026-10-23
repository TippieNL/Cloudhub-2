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
/*
 * Walking back up a folder landed at the top, every time -- three causes,
 * each pinned here so none can come back:
 *
 *  - the memory lived inside the list, and the list is taken down whenever
 *    the placeholders take its place, so a slow folder below wiped it;
 *  - one scroll state was shared by every folder, and opening a folder
 *    empties the list before the next arrives -- a lazy list measured empty
 *    resets to the top, and the recorder wrote that down as the place left;
 *  - the effects read the state they were started with, so restoring could
 *    wait for ever on a list that was empty when it began.
 */
$entryList = (string)preg_replace('/^.*?(private fun EntryList\(.*?)\n\/\*\*.*$/s', '$1', $files);
$browser = (string)preg_replace('/^.*?(private fun BrowserContent\(.*?)\n\/\*\*.*$/s', '$1', $files);
$checks['the memory is held above the placeholders, not in the list they replace'] =
    str_contains($browser, 'val memory = rememberSaveable(saver = ScrollMemorySaver) { ScrollMemory() }')
    && str_contains($browser, 'EntryList(api, state, memory, bar,')
    && !str_contains($entryList, 'rememberSaveable(saver = ScrollMemorySaver)');
// Created already at the remembered place, so the first frame of a folder is
// the right one: nothing is restored after the fact, so nothing jumps -- and
// created inline, a state would be a different object every recomposition.
$checks['each arrival gets a scroll state created at its place'] =
    str_contains($entryList, 'val arrival = Arrival(state.path, state.grid, searching)')
    && str_contains($entryList, 'ScrollMemory.startFor(memory.placeOf(state.path), entries.map { it.path })')
    && str_contains($entryList, 'val grid = remember(arrival) { LazyGridState(start.index, start.offset) }')
    && str_contains($entryList, 'val list = remember(arrival) { LazyListState(start.index, start.offset) }')
    && str_contains($files, 'state = grid,')
    && str_contains($files, 'state = list,')
    && !str_contains($files, 'state = rememberLazyGridState()');
$checks['a list is never measured empty, so it never resets to the top'] =
    str_contains($entryList, "if (entries.isEmpty()) {\n        // A folder on its way: nothing to measure until its items are here.\n        Box(Modifier.fillMaxSize())");
$checks['a position is kept per folder, with tests'] =
    str_contains($memory, 'class ScrollMemory')
    && str_contains($entryList, 'memory.remember(arrival.path, index, at, now.getOrNull(index)?.path, tucked)')
    && str_contains($memoryTests, 'each folder keeps its own place');
// snapshotFlow re-runs on state read *inside* its block: a position captured
// outside is one value the flow never sees change.
$checks['the position is read inside the flow, not captured outside it'] =
    str_contains($entryList, 'snapshotFlow { Triple(firstVisible(), offset(), laidOut()) to bar.heightOffset }');
// Nothing before the state's first layout, when it only holds where it was
// created; and the newest list, not the one the effect started with.
$checks['nothing is recorded before the folder has been laid out'] =
    str_contains($entryList, 'if (items > 0 && now.isNotEmpty()) {')
    && str_contains($entryList, 'val latest by rememberUpdatedState(entries)');
// Search results are not the folder.
$checks['search results are not recorded as the folder'] =
    str_contains($entryList, 'if (arrival.searching) return@LaunchedEffect')
    && str_contains($entryList, 'if (searching) ScrollMemory.TOP');
/*
 * The folder you opened, under your finger again: taken at the tap, the last
 * moment the list on screen is this folder's, and found by name on the way
 * back -- a file added, the sort changed or a filter cleared does not land
 * you on a neighbour.
 */
$checks['the folder opened is remembered at the tap, by name'] =
    str_contains($entryList, 'memory.rememberOpening(')
    && substr_count($entryList, 'onOpen = { leaving(index, entry); onOpen(entry) },') === 2
    && str_contains($memory, 'fun startFor(place: Place?, paths: List<String>): Start {')
    && str_contains($memoryTests, 'it is found by name when the list has changed meanwhile');
// The bar hides as you scroll and the list moves with it, so a place on screen
// is the list's position and the bar's.
$checks['the bar comes back with the place'] =
    str_contains($entryList, 'bar.heightOffset = memory.placeOf(arrival.path)?.bar ?: 0f')
    && str_contains($entryList, 'if (!scrolls) bar.heightOffset = 0f')
    && str_contains($files, 'bar = scrollBehavior.state,');
$checks['what is remembered survives the screen being rebuilt'] =
    str_contains($files, 'rememberSaveable(saver = ScrollMemorySaver)')
    && str_contains($files, 'private val ScrollMemorySaver = listSaver<ScrollMemory, Any>')
    && str_contains($files, 'flat.chunked(7).forEach { row ->')
    && str_contains($memoryTests, 'names and the bar survive being written down and read back');
// Swiping through thirty photos and coming back should land on the one you
// ended at, not the one you opened.
// The viewer keeps its own copy of the row, `photos`, so a delete or a rename
// in it shows at once; the photo on show is read from that copy.
$checks['the viewer says which file it was closed on'] =
    str_contains($viewerScreens, 'onBack: (String?) -> Unit')
    && str_contains($viewerScreens, 'onBack(photos.getOrNull(pager.currentPage)?.path)')
    && str_contains($main, 'onBack = { photo -> reveal = photo; back() }')
    && str_contains($main, 'onBack = { reveal = current.entry.path; back() }');
$checks['and the list comes back to it'] =
    str_contains($entryList, 'ScrollMemory.indexOfPath(latest.map { it.path }, revealPath)')
    && str_contains($entryList, 'ScrollMemory.shouldReveal(target, firstVisible(), lastVisible())')
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
// Each page of the viewer -- the photo and its gestures -- is ui/PhotoPage.kt.
$page = $readKt('ui/PhotoPage.kt');

// The width handed over is the photo's as drawn, not the page's: a photo
// narrower than the screen has less room to pan before the pager should get
// the drag.
$checks['a drag is offered to the pager rather than swallowed'] =
    str_contains($page, '.transformable(')
    && str_contains($page, 'canPan = { pan ->')
    && str_contains($page, 'PhotoZoom.panBelongsToPhoto(zoom.scale, zoom.offsetX, pan.x, pan.y, zoom.drawnW, zoom.viewportW)');
// The detector that consumed everything.
$checks['the gesture detector that ate the swipe is gone'] =
    !str_contains($viewerScreens, 'detectTransformGestures') && !str_contains($page, 'detectTransformGestures');
$checks['who owns a drag is decided in one place, with tests'] =
    str_contains($zoom, 'object PhotoZoom')
    && str_contains($zoom, 'fun panBelongsToPhoto(')
    && str_contains($zoomTests, "a drag across a photo that fits the screen is the pager's");
// Zooming has to keep working, or the fix trades one gesture for another.
$checks['pinch and double tap still zoom'] =
    str_contains($page, 'rememberTransformableState')
    && str_contains($page, 'PhotoZoom.scaled(scale, zoomChange)')
    && str_contains($page, 'onDoubleTap = {')
    && str_contains($zoomTests, 'double tap zooms in, and again zooms out');
// A picture flung off the screen leaves nothing to drag back. Clamped to the
// photo as drawn: clamped to the page, a zoomed landscape photo could still be
// dragged up and down into its own black bars.
$checks['a zoomed photo cannot be dragged off the screen'] =
    str_contains($page, 'PhotoZoom.clampPan(x, next, drawnW, viewportW)')
    && str_contains($page, 'PhotoZoom.clampPan(y, next, drawnH, viewportH)')
    && str_contains($zoomTests, 'a photo cannot be dragged off the screen')
    && str_contains($zoomTests, 'a zoomed landscape photo cannot be dragged into its black bars');
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
