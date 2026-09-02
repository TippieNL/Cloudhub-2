package nl.tippie.cloudhub.ui

import kotlin.math.abs

/**
 * Who owns a drag in the photo viewer: the picture, or the pager under it.
 *
 * The viewer is a pager, so swiping sideways should show the next photo -- and
 * it did not. The zoom gesture detector sitting on top of each photo consumed
 * every drag that passed the touch slop, whether or not the photo was zoomed
 * in, so the pager never saw one and the swipe did nothing at all.
 *
 * The rule is about who the gesture belongs to. A photo that fits the screen
 * has nothing to pan, so a drag across it means "next photo". A zoomed photo
 * keeps the drag -- until its edge reaches the screen edge, at which point
 * continuing to drag that way means the next photo again.
 */
object PhotoZoom {

    const val MIN_SCALE = 1f
    const val MAX_SCALE = 6f

    /** Where a double tap takes a photo that is not already zoomed. */
    const val DOUBLE_TAP_SCALE = 2.5f

    /**
     * Float comparison needs room: a pinch that ends at "1" lands a hair either
     * side of it, and a photo a hair over 1 has nothing to pan but would keep
     * every swipe if the test were exact.
     */
    private const val EPSILON = 0.01f

    fun scaled(current: Float, by: Float): Float = (current * by).coerceIn(MIN_SCALE, MAX_SCALE)

    /** Double tap zooms in, or all the way back out if it is already zoomed. */
    fun afterDoubleTap(current: Float): Float =
        if (isZoomed(current)) MIN_SCALE else DOUBLE_TAP_SCALE

    fun isZoomed(scale: Float): Boolean = scale > MIN_SCALE + EPSILON

    /**
     * How far the picture can be dragged before its edge comes past the screen
     * edge -- half the overflow, since it is centred.
     */
    fun panLimit(scale: Float, size: Float): Float = maxOf(0f, (size * scale - size) / 2f)

    fun clampPan(value: Float, scale: Float, size: Float): Float {
        val limit = panLimit(scale, size)
        return value.coerceIn(-limit, limit)
    }

    /**
     * Whether this drag is the photo's rather than the pager's.
     *
     * False hands the gesture to the pager, which is what makes a swipe turn
     * the page. True keeps it, which is what makes a zoomed photo pan.
     *
     * @param offsetX where the picture has been dragged to already
     * @param dragX sideways movement; positive drags the picture right
     * @param dragY vertical movement, which a pager has no use for
     * @param width the width the photo is drawn in
     */
    fun panBelongsToPhoto(
        scale: Float,
        offsetX: Float,
        dragX: Float,
        dragY: Float,
        width: Float,
    ): Boolean {
        if (!isZoomed(scale)) return false
        // A pager only pages sideways, so a mostly-vertical drag is the
        // photo's whatever else is true.
        if (abs(dragY) > abs(dragX)) return true

        val limit = panLimit(scale, width)
        if (limit <= 0f) return false
        // Dragging right reveals the picture's left side, which is only
        // possible while that side is still off screen.
        return if (dragX > 0f) offsetX < limit - EPSILON else offsetX > -limit + EPSILON
    }
}
