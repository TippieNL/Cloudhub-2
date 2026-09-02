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
}
