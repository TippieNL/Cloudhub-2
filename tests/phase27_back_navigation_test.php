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
$checks['opening a screen pushes and leaving it pops'] =
    substr_count($main, 'go(Screen.') >= 6
    && substr_count($main, 'onBack = { back()') >= 4;
// Signing in or out is a new beginning: Back must not walk into the session
// that just ended.
$checks['signing in and out clears the history'] =
    str_contains($main, 'fun reset(next: Screen)')
    && str_contains($main, 'reset(Screen.SignIn)')
    && str_contains($main, 'reset(Screen.Files)');

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
