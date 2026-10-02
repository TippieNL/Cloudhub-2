package nl.tippie.cloudhub.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ln

/**
 * How big the grid's cards are.
 *
 * A handful of fixed steps rather than a free size: a step is a size you can
 * get back to, a pinch that lands between two of them would give a different
 * grid every time, and the columns change in whole numbers anyway. Each step
 * is the narrowest a card may get; the grid fits as many as the window allows
 * and shares out the rest, so a step means fewer, larger cards on a phone and
 * more of them on an unfolded Fold.
 */
object ThumbnailSizes {
    /** The narrowest a card may be at each step, smallest first. */
    val CELL_WIDTHS_DP = listOf(104, 132, 158, 200, 256, 330)

    /** The size the grid has always had. */
    const val DEFAULT = 2

    val LABELS = listOf("Smallest", "Small", "Medium", "Large", "Larger", "Largest")

    const val MIN = 0
    val MAX = CELL_WIDTHS_DP.lastIndex

    fun clamp(level: Int): Int = level.coerceIn(MIN, MAX)

    fun cellWidth(level: Int): Dp = CELL_WIDTHS_DP[clamp(level)].dp

    fun label(level: Int): String = LABELS[clamp(level)]

    /**
     * Whether a card this wide wants the server's larger thumbnail.
     *
     * The small one is 300 pixels across, the large one 640. Stretched up to
     * twice its size the small one is still acceptable, and it is a quarter of
     * the bytes; beyond that -- one or two columns on a phone, the largest
     * steps on an unfolded Fold -- it turns visibly soft. The default size on
     * a phone stays on the small one, as it always has.
     */
    fun wantsLargeThumbnail(cardWidthDp: Float, density: Float): Boolean =
        cardWidthDp * density > SMALL_THUMBNAIL_PX * 2f

    const val SMALL_THUMBNAIL_PX = 300

    /**
     * How a pinch moves through the steps.
     *
     * The gesture reports zoom as a running product; each time it has grown or
     * shrunk by [STEP_ZOOM] since the last step, take one more. Measured in
     * logarithms so spreading the fingers and bringing them back together is
     * symmetrical, and a single sweep can take several steps.
     *
     * Returns the new level and the zoom left over, which the caller carries
     * into the next event so a slow pinch is not lost between them.
     */
    fun afterPinch(level: Int, accumulatedZoom: Float): Pair<Int, Float> {
        if (accumulatedZoom <= 0f) return clamp(level) to 1f
        val steps = (ln(accumulatedZoom) / ln(STEP_ZOOM)).toInt()
        if (steps == 0) return clamp(level) to accumulatedZoom
        val next = clamp(level + steps)
        // At either end the excess is thrown away rather than banked, so
        // pinching past the largest and back responds at once.
        if (next == clamp(level)) return next to 1f
        val used = Math.pow(STEP_ZOOM.toDouble(), (next - clamp(level)).toDouble()).toFloat()
        return next to accumulatedZoom / used
    }

    /** A quarter larger or smaller per step: deliberate, not twitchy. */
    const val STEP_ZOOM = 1.25f

    /** One notch of a mouse wheel with Ctrl held is one step; the sign says which way. */
    fun afterWheel(level: Int, scrollDelta: Float): Int =
        if (abs(scrollDelta) < 0.01f) clamp(level)
        // A wheel rolled away from you scrolls up, and zooms in, as in a browser.
        else clamp(level + if (scrollDelta < 0) 1 else -1)
}

/** Whether the cards on screen are wide enough to want the larger thumbnail. */
val LocalLargeThumbnails = staticCompositionLocalOf { false }

/**
 * How wide the grid's cards come out, as [androidx.compose.foundation.lazy.grid.GridCells.Adaptive]
 * computes it: as many columns of at least [minCellDp] as fit, sharing the rest.
 */
fun gridCardWidthDp(availableDp: Float, minCellDp: Float, gapDp: Float): Float {
    val columns = ((availableDp + gapDp) / (minCellDp + gapDp)).toInt().coerceAtLeast(1)
    return (availableDp - gapDp * (columns - 1)) / columns
}
