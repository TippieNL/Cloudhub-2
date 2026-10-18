package nl.tippie.cloudhub

import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.test.TestScope
import nl.tippie.cloudhub.ui.PhotoZoom
import nl.tippie.cloudhub.ui.PhotoZoomState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One photo's zoom, driven the way the gestures drive it.
 *
 * PhotoZoomTest checks the arithmetic; this checks it is applied the right
 * way round -- that a pinch zooms into the fingers and not the middle of the
 * screen, that a double tap goes into what was tapped, and that nothing can
 * leave the picture somewhere it cannot be dragged back from. Animations need
 * a frame clock, so the double taps here are the reduced-motion kind, which
 * land in one step at the same place.
 */
class PhotoZoomStateTest {

    /** A 4:3 landscape photo on a 1080 x 2400 portrait phone: drawn 1080 x 810. */
    private fun photo() = PhotoZoomState(TestScope()).apply {
        measured(1080f, 2400f)
        drew(4000f, 3000f)
    }

    private val middle = Offset(540f, 1200f)

    /** The point of the picture under a point on screen, in the picture's own unzoomed pixels. */
    private fun PhotoZoomState.under(at: Offset) = Offset(
        (at.x - 540f - offsetX) / scale,
        (at.y - 1200f - offsetY) / scale,
    )

    private fun PhotoZoomState.pinch(at: Offset, by: Float, pan: Offset = Offset.Zero) {
        centroid = at + pan
        transform(by, pan)
    }

    @Test
    fun `a pinch zooms into the fingers, not the middle of the screen`() {
        val zoom = photo()
        val fingers = Offset(900f, 1300f)
        zoom.begin(fingers)
        val before = zoom.under(fingers)
        zoom.pinch(fingers, by = 2f)
        assertEquals(2f, zoom.scale)
        val after = zoom.under(fingers)
        assertEquals(before.x, after.x, 0.5f)
        // Not quite exact vertically: at 2x the photo is still shorter than the
        // page, so it is held in the middle rather than allowed to drift.
        assertEquals(0f, zoom.offsetY)
    }

    @Test
    fun `a pinch about the middle does not move the photo`() {
        val zoom = photo()
        zoom.begin(middle)
        zoom.pinch(middle, by = 3f)
        assertEquals(0f, zoom.offsetX)
        assertEquals(0f, zoom.offsetY)
    }

    @Test
    fun `fingers that move while pinching carry the picture with them`() {
        val zoom = photo()
        zoom.begin(middle)
        zoom.pinch(middle, by = 3f)
        val grabbed = zoom.under(middle)
        zoom.pinch(middle, by = 1f, pan = Offset(-150f, 0f))
        // The bit of the picture that was under the fingers still is.
        assertEquals(grabbed.x, zoom.under(middle + Offset(-150f, 0f)).x, 0.5f)
    }

    @Test
    fun `a zoomed landscape photo cannot be dragged into its black bars`() {
        val zoom = photo()
        zoom.begin(middle)
        zoom.pinch(middle, by = 2f)
        zoom.pinch(middle, by = 1f, pan = Offset(0f, 600f))
        assertEquals(0f, zoom.offsetY)
        // Sideways it overflows by 1080, so 540 each way and no further.
        zoom.pinch(middle, by = 1f, pan = Offset(-5000f, 0f))
        assertEquals(-540f, zoom.offsetX, 0.5f)
    }

    @Test
    fun `pinching back to the whole photo puts it back in the middle`() {
        val zoom = photo()
        zoom.begin(Offset(1000f, 1200f))
        zoom.pinch(Offset(1000f, 1200f), by = 3f)
        zoom.pinch(Offset(1000f, 1200f), by = 0.1f)
        assertEquals(PhotoZoom.MIN_SCALE, zoom.scale)
        assertEquals(0f, zoom.offsetX)
        assertEquals(0f, zoom.offsetY)
    }

    @Test
    fun `a double tap zooms into what was tapped, far enough to fill the screen`() {
        val zoom = photo()
        val tapped = Offset(1000f, 1200f)
        zoom.toggleAt(tapped, animate = false)
        // 2400 / 810: covering the page top to bottom.
        assertEquals(2.963f, zoom.scale, 0.01f)
        // The right-hand side was tapped, so the picture moved left to show it...
        assertTrue(zoom.offsetX < 0f)
        // ...and the tapped point is still under the finger: 460px right of
        // the middle at 1x, so 460px of the picture's own from its centre.
        assertEquals(460f, zoom.under(tapped).x, 0.5f)
    }

    @Test
    fun `a tap near the edge goes as far as the edge and no further`() {
        val zoom = photo()
        zoom.toggleAt(Offset(1080f, 1200f), animate = false)
        assertEquals(-PhotoZoom.panLimit(zoom.scale, 1080f, 1080f), zoom.offsetX, 0.5f)
    }

    @Test
    fun `a second double tap goes back to the whole photo`() {
        val zoom = photo()
        zoom.toggleAt(Offset(1000f, 1500f), animate = false)
        zoom.toggleAt(Offset(200f, 300f), animate = false)
        assertEquals(PhotoZoom.MIN_SCALE, zoom.scale)
        assertEquals(0f, zoom.offsetX)
        assertEquals(0f, zoom.offsetY)
    }

    @Test
    fun `turning the phone keeps a zoomed photo within reach`() {
        val zoom = photo()
        zoom.begin(middle)
        zoom.pinch(middle, by = 3f)
        zoom.pinch(middle, by = 1f, pan = Offset(-5000f, 0f))
        // Landscape: the photo is now drawn 1440 x 1080 on a 2400 x 1080 page.
        zoom.measured(2400f, 1080f)
        val limit = PhotoZoom.panLimit(3f, 1440f, 2400f)
        assertEquals(-limit, zoom.offsetX, 0.5f)
    }

    @Test
    fun `leaving the photo forgets the zoom`() {
        val zoom = photo()
        zoom.toggleAt(Offset(1000f, 1200f), animate = false)
        zoom.reset()
        assertEquals(PhotoZoom.MIN_SCALE, zoom.scale)
        assertEquals(0f, zoom.offsetX)
        assertEquals(0f, zoom.offsetY)
    }
}
