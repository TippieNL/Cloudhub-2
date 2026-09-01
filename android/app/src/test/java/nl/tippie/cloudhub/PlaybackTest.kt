package nl.tippie.cloudhub

import nl.tippie.cloudhub.ui.PlaybackTuning
import nl.tippie.cloudhub.ui.VideoThumbnails
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What playback is allowed to cost.
 *
 * The player itself needs a device. These are the rules underneath it: the
 * buffer bounds Media3 rejects at runtime, the cache key that decides whether
 * a replaced file plays as itself or as the video it replaced, and the size of
 * the request a video tile makes -- which used to be "the whole video".
 */
class PlaybackTest {

    private val mb = 1024L * 1024

    @Before
    fun clean() = VideoThumbnails.forgetContributions()

    /* ---- buffering ------------------------------------------------------- */

    @Test
    fun `the buffer bounds are ones Media3 will accept`() {
        // DefaultLoadControl throws on a configuration whose bounds disagree,
        // and a phone is a slow place to find that out.
        assertTrue(PlaybackTuning.bufferBoundsAreValid())
    }

    @Test
    fun `playback starts on less video than Media3 waits for by default`() {
        // The default is 2500ms before the first frame is shown. On a server
        // the phone reaches in a millisecond, that is pure waiting.
        assertTrue(
            PlaybackTuning.BUFFER_FOR_PLAYBACK_MS < 2_500,
            "start-up buffer is ${PlaybackTuning.BUFFER_FOR_PLAYBACK_MS}ms",
        )
        assertTrue(PlaybackTuning.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS < 5_000)
    }

    @Test
    fun `something is kept behind the playhead`() {
        // Zero is the default, and why skipping back ten seconds re-fetches
        // ten seconds that had just arrived.
        assertTrue(PlaybackTuning.BACK_BUFFER_MS >= 10_000)
    }

    /* ---- the cache key --------------------------------------------------- */

    @Test
    fun `a replaced file is not played from the old file's cache`() {
        val before = PlaybackTuning.cacheKey("/Videos/holiday.mp4", "2026-01-01T10:00:00Z")
        val after = PlaybackTuning.cacheKey("/Videos/holiday.mp4", "2026-06-01T09:30:00Z")
        assertNotEquals(before, after)
    }

    @Test
    fun `the same file keeps the same key`() {
        val stamp = "2026-01-01T10:00:00Z"
        assertEquals(
            PlaybackTuning.cacheKey("/Videos/holiday.mp4", stamp),
            PlaybackTuning.cacheKey("/Videos/holiday.mp4", stamp),
        )
    }

    @Test
    fun `two files of the same name in different folders are different videos`() {
        assertNotEquals(
            PlaybackTuning.cacheKey("/A/clip.mp4", "x"),
            PlaybackTuning.cacheKey("/B/clip.mp4", "x"),
        )
    }

    /* ---- what may be written to the cache ---------------------------------
     *
     * A film bigger than the cache cannot be held by it, and trying is worse
     * than not trying: playing it evicts the spans it just wrote to make room
     * for the next ones, churning the whole cache and gaining nothing -- and
     * the evictor can drop a span while it is being read, which is how a large
     * video stops playing rather than merely playing uncached.
     */

    @Test
    fun `a film bigger than the cache is not written to it`() {
        assertFalse(PlaybackTuning.mayCache(4096 * mb))
        assertFalse(PlaybackTuning.mayCache(PlaybackTuning.CACHE_BYTES))
    }

    @Test
    fun `a clip that fits alongside others is cached`() {
        assertTrue(PlaybackTuning.mayCache(20 * mb))
    }

    @Test
    fun `what is cacheable leaves room for more than one`() {
        // A file allowed to fill the whole cache evicts everything else in it.
        val largest = (1..2000).map { it * mb }.filter { PlaybackTuning.mayCache(it) }.max()
        assertTrue(largest * 2 <= PlaybackTuning.CACHE_BYTES, "largest cacheable is $largest")
    }

    @Test
    fun `a file of unknown size is not cached`() {
        // The listing gives 0 for something it could not measure; writing an
        // unbounded stream into a bounded cache is the case above again.
        assertFalse(PlaybackTuning.mayCache(0))
    }

    /* ---- what a tile costs ------------------------------------------------ */

    @Test
    fun `drawing a tile asks for a prefix, not the video`() {
        val fourGb = 4096 * mb
        assertEquals("bytes=0-${VideoThumbnails.PREFIX_BYTES - 1}", VideoThumbnails.rangeHeader(fourGb))
    }

    @Test
    fun `a file smaller than the prefix is asked for whole`() {
        // Ranging a 200 KB clip buys nothing and costs a header.
        assertNull(VideoThumbnails.rangeHeader(200 * 1024))
    }

    @Test
    fun `a huge file that will not decode from a prefix gets the icon`() {
        assertFalse(VideoThumbnails.mayFetchWholeFile(4096 * mb))
        // A phone clip is still worth fetching for a picture.
        assertTrue(VideoThumbnails.mayFetchWholeFile(30 * mb))
    }

    @Test
    fun `the frame is small enough for the server to accept`() {
        // /api/thumbnail/video refuses anything over 1280px a side.
        val (width, height) = VideoThumbnails.scaledSize(3840, 2160)
        assertTrue(maxOf(width, height) <= VideoThumbnails.MAX_EDGE_PX, "${width}x$height")
        // ...and the shape is kept, or the tile shows a squashed frame.
        assertEquals(3840.0 / 2160, width.toDouble() / height, 0.01)
    }

    @Test
    fun `a frame already small is not blown up`() {
        assertEquals(320 to 180, VideoThumbnails.scaledSize(320, 180))
    }

    @Test
    fun `a frame is offered to the server once, not once per scroll`() {
        assertTrue(VideoThumbnails.markContributed("/Videos/holiday.mp4"))
        assertFalse(VideoThumbnails.markContributed("/Videos/holiday.mp4"))
        assertTrue(VideoThumbnails.markContributed("/Videos/other.mp4"))
    }

    @Test
    fun `remembering what was contributed does not grow without limit`() {
        repeat(400) { VideoThumbnails.markContributed("/Videos/clip-$it.mp4") }
        // The oldest are forgotten, so a long browse cannot accumulate paths.
        assertTrue(VideoThumbnails.markContributed("/Videos/clip-0.mp4"))
    }
}
