package nl.tippie.cloudhub.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * A grid whose card size can be changed where it is.
 *
 * Two fingers spread the cards larger and pinch them smaller, a step at a
 * time; with a keyboard and mouse attached -- a Fold on a desk -- Ctrl and the
 * wheel do the same. One finger still scrolls and taps as before: the gesture
 * only takes over once a second finger is down.
 *
 * Also decides, from the width the cards actually come out at, whether they
 * need the larger thumbnail ([LocalLargeThumbnails]), so a step that makes
 * cards big enough to blur the small one fetches the sharp one instead.
 */
@Composable
fun SizedGrid(
    level: Int,
    onLevel: (Int) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    content: @Composable (GridCells) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val currentLevel by rememberUpdatedState(level)
    val change by rememberUpdatedState { next: Int ->
        if (next != currentLevel) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onLevel(next)
        }
    }

    BoxWithConstraints(
        modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var zoom = 1f
                    do {
                        // The Initial pass sees the event before the grid
                        // does, so a two-finger move resizes instead of
                        // scrolling.
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val (next, rest) = ThumbnailSizes.afterPinch(currentLevel, zoom * event.calculateZoom())
                            zoom = rest
                            change(next)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Scroll && event.keyboardModifiers.isCtrlPressed) {
                            val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            change(ThumbnailSizes.afterWheel(currentLevel, delta))
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            },
    ) {
        val cell = ThumbnailSizes.cellWidth(level)
        val available = (maxWidth - GRID_PADDING * 2).value
        val card = gridCardWidthDp(available, cell.value, GRID_GAP.value)
        val large = ThumbnailSizes.wantsLargeThumbnail(card, LocalDensity.current.density)
        CompositionLocalProvider(LocalLargeThumbnails provides large) {
            content(GridCells.Adaptive(minSize = cell))
        }
    }
}
