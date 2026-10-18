package nl.tippie.cloudhub

import nl.tippie.cloudhub.ui.PhotoZoom
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who owns a drag in the photo viewer.
 *
 * The viewer is a pager, so a sideways swipe should show the next photo -- and
 * it did nothing at all. The zoom detector on each photo consumed every drag
 * that passed the touch slop, zoomed in or not, so the pager under it never
 * saw one.
 *
 * The rule is about ownership: a photo that fits the screen has nothing to pan
 * and hands the drag over; a zoomed photo keeps it until its edge arrives.
 */
class PhotoZoomTest {

    private val width = 1080f

    /* ---- the swipe that did nothing --------------------------------------- */

    @Test
    fun `a drag across a photo that fits the screen is the pager's`() {
        assertFalse(PhotoZoom.panBelongsToPhoto(PhotoZoom.MIN_SCALE, 0f, dragX = -300f, dragY = 0f, width = width))
    }

    @Test
    fun `a photo a hair over one to one still pages`() {
        // A pinch that ends at "1" lands either side of it; an exact test would
        // leave photos that cannot be swiped and nothing to show why.
        assertFalse(PhotoZoom.panBelongsToPhoto(1.001f, 0f, dragX = -300f, dragY = 0f, width = width))
    }

    @Test
    fun `a zoomed photo keeps the drag`() {
        assertTrue(PhotoZoom.panBelongsToPhoto(3f, 0f, dragX = -300f, dragY = 0f, width = width))
    }

    @Test
    fun `a zoomed photo dragged to its edge hands the next drag over`() {
        // Panned fully left; dragging further that way means the next photo,
        // not a picture that refuses to move.
        val limit = PhotoZoom.panLimit(3f, width)
        assertFalse(PhotoZoom.panBelongsToPhoto(3f, -limit, dragX = -300f, dragY = 0f, width = width))
        // ...but dragging back into the picture is still the picture's.
        assertTrue(PhotoZoom.panBelongsToPhoto(3f, -limit, dragX = 300f, dragY = 0f, width = width))
    }

    @Test
    fun `a vertical drag on a zoomed photo is never the pager's`() {
        // A pager pages sideways; handing it a vertical drag means a zoomed
        // photo that cannot be panned up or down.
        val limit = PhotoZoom.panLimit(3f, width)
        assertTrue(PhotoZoom.panBelongsToPhoto(3f, -limit, dragX = -10f, dragY = 400f, width = width))
    }

    @Test
    fun `a photo with nowhere to pan does not hold the drag`() {
        // Zero width happens before the first layout; holding gestures then
        // would make the first swipe of a freshly opened photo do nothing.
        assertFalse(PhotoZoom.panBelongsToPhoto(3f, 0f, dragX = -300f, dragY = 0f, width = 0f))
    }

    /* ---- zoom ------------------------------------------------------------- */

    @Test
    fun `zoom is bounded at both ends`() {
        assertEquals(PhotoZoom.MAX_SCALE, PhotoZoom.scaled(5f, 4f))
        assertEquals(PhotoZoom.MIN_SCALE, PhotoZoom.scaled(1.2f, 0.1f))
    }

    @Test
    fun `double tap zooms in, and again zooms out`() {
        val zoomed = PhotoZoom.afterDoubleTap(PhotoZoom.MIN_SCALE)
        assertTrue(PhotoZoom.isZoomed(zoomed))
        assertEquals(PhotoZoom.MIN_SCALE, PhotoZoom.afterDoubleTap(zoomed))
    }

    /* ---- staying on screen ------------------------------------------------ */

    @Test
    fun `a photo cannot be dragged off the screen`() {
        val limit = PhotoZoom.panLimit(2f, width)
        assertEquals(limit, PhotoZoom.clampPan(99_999f, 2f, width))
        assertEquals(-limit, PhotoZoom.clampPan(-99_999f, 2f, width))
    }

    @Test
    fun `an unzoomed photo has nowhere to go`() {
        assertEquals(0f, PhotoZoom.panLimit(PhotoZoom.MIN_SCALE, width))
        assertEquals(0f, PhotoZoom.clampPan(500f, PhotoZoom.MIN_SCALE, width))
    }

    @Test
    fun `the room to pan is the overflow, halved`() {
        // Twice the size means one screen of overflow, half of it either way.
        assertEquals(width / 2, PhotoZoom.panLimit(2f, width))
    }

    /* ---- the photo as drawn, not the page ---------------------------------
     *
     * A 4:3 landscape photo on a 1080 x 2400 portrait phone is drawn 1080 x 810,
     * with black above and below. The room to pan has to be measured against
     * that, or a zoomed photo slides into its own black bars.
     */

    private val pageW = 1080f
    private val pageH = 2400f
    private val fit = PhotoZoom.fitScale(4000f, 3000f, pageW, pageH)
    private val drawnW = 4000f * fit
    private val drawnH = 3000f * fit

    @Test
    fun `a photo is fitted whole, its shape kept`() {
        assertEquals(1080f, drawnW, 0.5f)
        assertEquals(810f, drawnH, 0.5f)
        assertEquals(4f / 3f, drawnW / drawnH, 0.001f)
    }

    @Test
    fun `nothing is fitted before both sizes are known`() {
        assertEquals(0f, PhotoZoom.fitScale(0f, 3000f, pageW, pageH))
        assertEquals(0f, PhotoZoom.fitScale(4000f, 3000f, 0f, 0f))
    }

    @Test
    fun `a zoomed landscape photo cannot be dragged into its black bars`() {
        // At 2x it is 1620 tall on a 2400 page: it still fits, so it stays put.
        assertEquals(0f, PhotoZoom.panLimit(2f, drawnH, pageH))
        assertEquals(0f, PhotoZoom.clampPan(500f, 2f, drawnH, pageH))
        // Measured against the page, as it used to be, it could go 1200px.
        assertEquals(1200f, PhotoZoom.panLimit(2f, pageH))
    }

    @Test
    fun `once it outgrows the page it pans by its own overflow`() {
        // 810 x 4 = 3240 on a 2400 page: 840 too tall, 420 each way.
        assertEquals(420f, PhotoZoom.panLimit(4f, drawnH, pageH), 0.5f)
        // Sideways it fills the width, so that is the page's own rule.
        assertEquals(PhotoZoom.panLimit(4f, pageW), PhotoZoom.panLimit(4f, drawnW, pageW), 0.5f)
    }

    @Test
    fun `a photo narrower than the page hands sideways drags to the pager`() {
        // A portrait photo on a landscape tablet, zoomed 2x, is still narrower
        // than the screen: there is nothing to pan sideways, so it pages.
        assertFalse(PhotoZoom.panBelongsToPhoto(2f, 0f, dragX = -300f, dragY = 0f, width = 600f, viewport = 1600f))
        // Up and down it overflows, and that drag is the photo's.
        assertTrue(PhotoZoom.panBelongsToPhoto(2f, 0f, dragX = -10f, dragY = 300f, width = 600f, viewport = 1600f))
    }

    /* ---- zooming into a point --------------------------------------------- */

    /** The point of the picture under [focus], in the picture's own unzoomed pixels. */
    private fun under(focus: Float, offset: Float, scale: Float) = (focus - offset) / scale

    @Test
    fun `zooming keeps the point under the fingers where it is`() {
        val focus = 360f
        for ((offset, from, to) in listOf(Triple(0f, 1f, 2f), Triple(-200f, 2f, 3.5f), Triple(150f, 4f, 1.5f))) {
            val moved = PhotoZoom.zoomedAbout(offset, focus, from, to)
            assertEquals(under(focus, offset, from), under(focus, moved, to), 0.01f)
        }
    }

    @Test
    fun `zooming about the middle of the page does not move the picture`() {
        assertEquals(0f, PhotoZoom.zoomedAbout(0f, 0f, 1f, 3f))
    }

    @Test
    fun `a pinch in and back out leaves the picture where it was`() {
        val there = PhotoZoom.zoomedAbout(-120f, 300f, 2f, 5f)
        assertEquals(-120f, PhotoZoom.zoomedAbout(there, 300f, 5f, 2f), 0.01f)
    }

    /* ---- double tap ------------------------------------------------------- */

    @Test
    fun `double tap goes in far enough to fill the screen`() {
        val fill = PhotoZoom.fillScale(drawnW, drawnH, pageW, pageH)
        // 2400 / 810: the photo covers the page top to bottom.
        assertEquals(2.963f, fill, 0.01f)
        assertEquals(fill, PhotoZoom.afterDoubleTap(PhotoZoom.MIN_SCALE, fill))
    }

    @Test
    fun `double tap still goes in by the usual step when the photo already fills the screen`() {
        // A screenshot is the page's own shape.
        val fill = PhotoZoom.fillScale(pageW, pageH, pageW, pageH)
        assertEquals(PhotoZoom.MIN_SCALE, fill)
        assertEquals(PhotoZoom.DOUBLE_TAP_SCALE, PhotoZoom.afterDoubleTap(PhotoZoom.MIN_SCALE, fill))
    }

    @Test
    fun `double tap on a panorama stops at the most zoom there is`() {
        val fill = PhotoZoom.fillScale(1080f, 180f, pageW, pageH)
        assertEquals(PhotoZoom.MAX_SCALE, PhotoZoom.afterDoubleTap(PhotoZoom.MIN_SCALE, fill))
    }

    /* ---- the sharper decode ----------------------------------------------- */

    @Test
    fun `a sharper decode is asked for only when the first was cut down to fit`() {
        // Shrunk to the page's width: a bigger decode has more to show.
        assertTrue(PhotoZoom.needsDetail(1080, 810, 1080, 2400))
        // Smaller than the page both ways: that is all the photo there is.
        assertFalse(PhotoZoom.needsDetail(800, 600, 1080, 2400))
        // Nothing decoded, or no page yet.
        assertFalse(PhotoZoom.needsDetail(0, 0, 1080, 2400))
        assertFalse(PhotoZoom.needsDetail(1080, 810, 0, 0))
    }

    @Test
    fun `the sharper decode stays inside what a phone can draw`() {
        // A 50 MP photo fitted into the cap is about 4096 x 3072 -- 50 MB at
        // four bytes a pixel, against the 100 MB the platform will draw.
        assertTrue(PhotoZoom.DETAIL_EDGE.toLong() * PhotoZoom.DETAIL_EDGE * 3 / 4 * 4 < 100L * 1024 * 1024)
    }
}
