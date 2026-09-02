package nl.tippie.cloudhub

import nl.tippie.cloudhub.work.ForegroundMedia
import nl.tippie.cloudhub.work.UploadPacing
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How much of the connection an upload is allowed to take.
 *
 * The bug these rules exist for is not subtle: with a file going up, a video
 * would not start and photos took a second each, because the upload had the
 * whole link. What is worth testing is not that the constants are what they
 * are, but the two things that would quietly bring the bug back -- a chunk
 * larger than the server will accept, and a pause that never happens.
 */
class UploadPacingTest {

    @After fun tidy() = ForegroundMedia.reset()

    @Test
    fun `nothing on screen means full-size chunks and no waiting`() {
        val chunk = UploadPacing.chunkBytes(8L * 1024 * 1024, mediaOnScreen = false)
        assertEquals(UploadPacing.IDLE_CHUNK_BYTES, chunk)
        assertEquals(0, UploadPacing.pauseMillis(false))
    }

    @Test
    fun `a video on screen makes the upload send far less at a time`() {
        val watching = UploadPacing.chunkBytes(8L * 1024 * 1024, mediaOnScreen = true)
        val alone = UploadPacing.chunkBytes(8L * 1024 * 1024, mediaOnScreen = false)
        assertTrue(watching < alone / 4, "$watching is not much smaller than $alone")
        assertTrue(UploadPacing.pauseMillis(true) > 0)
    }

    @Test
    fun `a chunk is never larger than the server said it would take`() {
        // The server's limit is configurable -- UPLOAD_CHUNK_MB -- and an
        // oversized chunk is refused with 413, which the worker treats as
        // permanent and drops the file for.
        val small = 128L * 1024
        assertEquals(small, UploadPacing.chunkBytes(small, mediaOnScreen = false))
        assertEquals(small, UploadPacing.chunkBytes(small, mediaOnScreen = true))
    }

    @Test
    fun `a server that says nothing sensible still gets a real chunk`() {
        for (nonsense in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertTrue(UploadPacing.chunkBytes(nonsense, false) > 0, "chunk for $nonsense")
            assertTrue(UploadPacing.chunkBytes(nonsense, true) > 0, "chunk for $nonsense")
        }
    }

    @Test
    fun `while watching, the upload leaves most of a slow link alone`() {
        // 8 Mbit -- the link the contention was measured on. A sixth of it is
        // the difference between a video that plays and one that does not.
        val slowLink = 1024L * 1024
        val paced = UploadPacing.approximateRate(8L * 1024 * 1024, mediaOnScreen = true)
        assertTrue(paced <= slowLink / 4, "$paced B/s is too much of $slowLink B/s")
    }

    @Test
    fun `opening a video is noticed within one chunk`() {
        // Pacing can only begin at a chunk boundary, so the idle chunk is also
        // the wait before it takes effect. On a 1 MB/s uplink this is seconds,
        // not the best part of a minute the server's 8 MB would have been.
        assertTrue(UploadPacing.IDLE_CHUNK_BYTES <= 2L * 1024 * 1024)
    }

    @Test
    fun `the viewer and the player can be open at once`() {
        assertFalse(ForegroundMedia.inUse)
        ForegroundMedia.enter()
        ForegroundMedia.enter()
        ForegroundMedia.leave()
        // A player opened over the viewer: closing it does not mean nobody is
        // looking at anything.
        assertTrue(ForegroundMedia.inUse)
        ForegroundMedia.leave()
        assertFalse(ForegroundMedia.inUse)
    }

    @Test
    fun `an unbalanced leave does not stop a later screen counting`() {
        ForegroundMedia.leave()
        ForegroundMedia.leave()
        ForegroundMedia.enter()
        assertTrue(ForegroundMedia.inUse)
    }
}
