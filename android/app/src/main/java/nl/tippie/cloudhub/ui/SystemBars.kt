package nl.tippie.cloudhub.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Immersive mode: the status and navigation bars out of the way while [hidden].
 *
 * A swipe in from an edge brings them back for a moment -- the system's own
 * transient bars -- without leaving immersive mode, so the clock can be
 * checked without the photo moving or anything having to be tapped.
 *
 * Applied again on every resume: another app's window coming and going (the
 * share sheet, a chooser) can hand the bars back to the system, and coming
 * back to them drawn over a photo that is meant to fill the screen would look
 * broken. On the way out the bars are always given back, whatever [hidden]
 * was -- a screen that left immersive mode on would leave the rest of the app
 * without a status bar.
 */
@Composable
internal fun SystemBarsHidden(hidden: Boolean) {
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window } ?: return
    val controller = remember(window) { WindowInsetsControllerCompat(window, window.decorView) }

    DisposableEffect(controller) {
        val before = controller.systemBarsBehavior
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = before
        }
    }

    LifecycleResumeEffect(controller, hidden) {
        if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
        onPauseOrDispose { }
    }
}
