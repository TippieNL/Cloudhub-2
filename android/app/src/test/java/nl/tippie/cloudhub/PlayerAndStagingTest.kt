package nl.tippie.cloudhub

import nl.tippie.cloudhub.data.ResumePolicy
import nl.tippie.cloudhub.ui.TapZone
import nl.tippie.cloudhub.ui.zoneAt
import nl.tippie.cloudhub.work.StagingSpace
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two decisions behind the player and the picker uploads.
 *
 * Both are pure functions precisely so they can be checked here: one needs a
 * half-watched film and the other needs a full phone, and neither is something
 * to go looking for by hand on a device.
 */
class ResumePolicyTest {

    @Test
    fun `the first seconds are not worth resuming`() {
        // Reopening a video you watched two seconds of should just start it.
        assertFalse(ResumePolicy.shouldResume(0, 600_000))
        assertFalse(ResumePolicy.shouldResume(2_000, 600_000))
        assertFalse(ResumePolicy.shouldResume(ResumePolicy.MIN_POSITION_MS - 1, 600_000))
    }

    @Test
    fun `the middle of a video resumes`() {
        assertTrue(ResumePolicy.shouldResume(ResumePolicy.MIN_POSITION_MS, 600_000))
        assertTrue(ResumePolicy.shouldResume(300_000, 600_000))
    }

    @Test
    fun `the last seconds are not worth resuming either`() {
        // Resuming a film 3 seconds from the end is worse than starting it.
        val duration = 600_000L
        assertFalse(ResumePolicy.shouldResume(duration, duration))
        assertFalse(ResumePolicy.shouldResume(duration - 3_000, duration))
        assertFalse(ResumePolicy.shouldResume(duration - ResumePolicy.END_MARGIN_MS, duration))
        assertTrue(ResumePolicy.shouldResume(duration - ResumePolicy.END_MARGIN_MS - 1, duration))
    }

    @Test
    fun `an unknown duration still resumes`() {
        // A live or still-loading source reports no duration; the position we
        // saved is all the information there is, and it is enough.
        assertTrue(ResumePolicy.shouldResume(60_000, 0))
        assertTrue(ResumePolicy.shouldResume(60_000, -1))
        assertFalse(ResumePolicy.shouldResume(1_000, 0))
    }

    @Test
    fun `the stored set is bounded, oldest first`() {
        val entries = (1..10).associate { "/video-$it.mp4" to it * 1000L }
        val kept = ResumePolicy.prune(entries, 4)

        assertEquals(4, kept.size)
        assertEquals(listOf("/video-7.mp4", "/video-8.mp4", "/video-9.mp4", "/video-10.mp4"), kept.keys.toList())
    }

    @Test
    fun `pruning under the limit changes nothing`() {
        val entries = mapOf("/a.mp4" to 1L, "/b.mp4" to 2L)
        assertEquals(entries, ResumePolicy.prune(entries, ResumePolicy.MAX_REMEMBERED))
        assertEquals(entries, ResumePolicy.prune(entries, 2))
        assertTrue(ResumePolicy.prune(entries, 0).isEmpty())
    }
}

class StagingSpaceTest {

    private val gb = 1024L * 1024 * 1024

    @Test
    fun `a 4 GB clip onto a phone with 1 GB free is refused`() {
        assertFalse(StagingSpace.hasRoom(freeBytes = 1 * gb, neededBytes = 4 * gb))
    }

    @Test
    fun `a 4 GB clip onto a phone with 8 GB free is allowed`() {
        assertTrue(StagingSpace.hasRoom(freeBytes = 8 * gb, neededBytes = 4 * gb))
    }

    @Test
    fun `headroom is left behind`() {
        // Exactly enough for the file but nothing over is still a refusal: an
        // upload must not be the thing that fills the phone.
        assertFalse(StagingSpace.hasRoom(freeBytes = 4 * gb, neededBytes = 4 * gb))
        assertFalse(StagingSpace.hasRoom(4 * gb + StagingSpace.HEADROOM_BYTES - 1, 4 * gb))
        assertTrue(StagingSpace.hasRoom(4 * gb + StagingSpace.HEADROOM_BYTES, 4 * gb))
    }

    @Test
    fun `an unknown size is allowed to try, unless the phone is already full`() {
        assertTrue(StagingSpace.hasRoom(freeBytes = 8 * gb, neededBytes = -1))
        assertFalse(StagingSpace.hasRoom(freeBytes = 0, neededBytes = -1))
        assertFalse(StagingSpace.hasRoom(StagingSpace.HEADROOM_BYTES, -1))
    }
}

/**
 * Where a tap on the video lands.
 *
 * The overlay this belongs to used to be three separate boxes, two of which
 * consumed a tap without acting on it -- so once the controls hid, they could
 * never be shown again. One detector plus this function is the shape that
 * cannot repeat that, and the geometry is checkable here rather than by
 * poking a phone.
 */
class TapZoneTest {

    private val width = 1000f

    @Test
    fun `the left edge seeks back`() {
        assertEquals(TapZone.SEEK_BACK, zoneAt(0f, width))
        assertEquals(TapZone.SEEK_BACK, zoneAt(120f, width))
    }

    @Test
    fun `the right edge seeks forward`() {
        assertEquals(TapZone.SEEK_FORWARD, zoneAt(width, width))
        assertEquals(TapZone.SEEK_FORWARD, zoneAt(880f, width))
    }

    @Test
    fun `the middle seeks neither way`() {
        assertEquals(TapZone.MIDDLE, zoneAt(width / 2, width))
        assertEquals(TapZone.MIDDLE, zoneAt(400f, width))
        assertEquals(TapZone.MIDDLE, zoneAt(600f, width))
    }

    @Test
    fun `the boundaries belong to the middle`() {
        // A double tap exactly on the seam should not seek: the middle is the
        // safe answer, since it only toggles the controls.
        assertEquals(TapZone.MIDDLE, zoneAt(350f, width))
        assertEquals(TapZone.MIDDLE, zoneAt(650f, width))
        assertEquals(TapZone.SEEK_BACK, zoneAt(349f, width))
        assertEquals(TapZone.SEEK_FORWARD, zoneAt(651f, width))
    }

    @Test
    fun `an unmeasured video does not divide by zero`() {
        // The width is zero until the first layout pass.
        assertEquals(TapZone.MIDDLE, zoneAt(0f, 0f))
        assertEquals(TapZone.MIDDLE, zoneAt(120f, 0f))
        assertEquals(TapZone.MIDDLE, zoneAt(120f, -50f))
    }

    @Test
    fun `the zones are fractions of the video, not fixed distances`() {
        // The discriminating cases: on a 400-wide video, 300px in is the
        // forward edge, while on a 2000-wide one the same 300px is still the
        // back edge. A zone measured in pixels gets both of these wrong.
        assertEquals(TapZone.SEEK_FORWARD, zoneAt(300f, 400f))
        assertEquals(TapZone.SEEK_BACK, zoneAt(300f, 2000f))
        assertEquals(TapZone.MIDDLE, zoneAt(200f, 400f))
        assertEquals(TapZone.MIDDLE, zoneAt(1000f, 2000f))
    }
}
