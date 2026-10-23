package nl.tippie.cloudhub.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The geometry of a zoomed photo, and who owns a drag across it: the picture,
 * or the pager under it.
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
 *
 * Every length is in pixels, one axis at a time. "Drawn" is the photo as it
 * sits on the page at 1x -- fitted, its shape kept, so a landscape photo on a
 * portrait phone is drawn full width and a third of the height -- and
 * "viewport" is the page. They differ on at least one axis for nearly every
 * photo, which is what the first version missed: it measured the room to pan
 * against the page, so a zoomed landscape photo could be dragged up and down
 * into its own black bars and left half off the screen.
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

    /**
     * Double tap zooms in, or all the way back out if it is already zoomed.
     *
     * In as far as [fill] when that is further than the usual step: a
     * landscape photo on a portrait phone covers the screen at about 3x, and
     * stopping at 2.5x would leave bands of black above and below it for no
     * reason. Capped, so a panorama does not go to 13x.
     */
    fun afterDoubleTap(current: Float, fill: Float = MIN_SCALE): Float =
        if (isZoomed(current)) MIN_SCALE else max(DOUBLE_TAP_SCALE, fill).coerceAtMost(MAX_SCALE)

    fun isZoomed(scale: Float): Boolean = scale > MIN_SCALE + EPSILON

    /**
     * How much a picture of [imageW] by [imageH] is shrunk to fit the page:
     * all of it on screen, its shape kept. Zero until both sizes are known.
     */
    fun fitScale(imageW: Float, imageH: Float, viewportW: Float, viewportH: Float): Float =
        if (imageW <= 0f || imageH <= 0f || viewportW <= 0f || viewportH <= 0f) 0f
        else min(viewportW / imageW, viewportH / imageH)

    /** The zoom at which the drawn photo covers the page, with no black on either axis. */
    fun fillScale(drawnW: Float, drawnH: Float, viewportW: Float, viewportH: Float): Float =
        if (drawnW <= 0f || drawnH <= 0f) MIN_SCALE
        else max(viewportW / drawnW, viewportH / drawnH).coerceAtLeast(MIN_SCALE)

    /**
     * How far the picture can be dragged before its edge comes past the screen
     * edge -- half the overflow, since it is centred. Nothing while, at this
     * zoom, it still fits the page on that axis.
     *
     * [viewport] defaults to [drawn]: a photo that fills the page exactly.
     */
    fun panLimit(scale: Float, drawn: Float, viewport: Float = drawn): Float =
        maxOf(0f, (drawn * scale - viewport) / 2f)

    fun clampPan(value: Float, scale: Float, drawn: Float, viewport: Float = drawn): Float {
        val limit = panLimit(scale, drawn, viewport)
        return value.coerceIn(-limit, limit)
    }

    /**
     * Where the picture has to move so that zooming from [from] to [to] keeps
     * [focus] -- a point on screen, measured from the middle of the page --
     * over the same part of the photo.
     *
     * That is what makes a pinch zoom into the fingers rather than into the
     * middle of the screen, and a double tap into what was tapped.
     */
    fun zoomedAbout(offset: Float, focus: Float, from: Float, to: Float): Float =
        if (from <= 0f) offset else focus - (focus - offset) * (to / from)

    /**
     * Whether this drag is the photo's rather than the pager's.
     *
     * False hands the gesture to the pager, which is what makes a swipe turn
     * the page. True keeps it, which is what makes a zoomed photo pan.
     *
     * @param offsetX where the picture has been dragged to already
     * @param dragX sideways movement; positive drags the picture right
     * @param dragY vertical movement, which a pager has no use for
     * @param width the width the photo is drawn at, at 1x
     * @param viewport the width of the page it is drawn in
     */
    fun panBelongsToPhoto(
        scale: Float,
        offsetX: Float,
        dragX: Float,
        dragY: Float,
        width: Float,
        viewport: Float = width,
    ): Boolean {
        if (!isZoomed(scale)) return false
        // A pager only pages sideways, so a mostly-vertical drag is the
        // photo's whatever else is true.
        if (abs(dragY) > abs(dragX)) return true

        val limit = panLimit(scale, width, viewport)
        if (limit <= 0f) return false
        // Dragging right reveals the picture's left side, which is only
        // possible while that side is still off screen.
        return if (dragX > 0f) offsetX < limit - EPSILON else offsetX > -limit + EPSILON
    }

    /* ---- a sharper picture for a zoomed photo ------------------------------
     *
     * The photo is decoded to fit the page, which is what a screen can show at
     * 1x and keeps three pages of them cheap. Zoomed, that same picture is
     * stretched: at 3x every pixel of it covers nine of the screen's. So past
     * a little zoom the page asks for a second, larger decode of the bytes it
     * already has, and draws it over the first.
     */

    /** Zoomed past this, the page-sized picture starts to look soft. */
    const val DETAIL_SCALE = 1.3f

    /**
     * The longest edge the sharper picture is decoded to.
     *
     * A 12-megapixel phone photo at its own resolution, and a texture size
     * every GPU this app runs on can draw. Decoding a 50-megapixel photo whole
     * would make a 200 MB bitmap; capped here it is about 50.
     */
    const val DETAIL_EDGE = 4096

    /**
     * Whether a larger decode would show anything the first one does not.
     *
     * The first decode is fitted to [boxW] by [boxH], the page. A photo
     * smaller than the page comes back at its own size, smaller than the box
     * on both axes, and decoding it again larger gives the same pixels.
     */
    fun needsDetail(decodedW: Int, decodedH: Int, boxW: Int, boxH: Int): Boolean =
        decodedW > 0 && decodedH > 0 && boxW > 0 && boxH > 0 &&
            (decodedW >= boxW - 1 || decodedH >= boxH - 1)
}
