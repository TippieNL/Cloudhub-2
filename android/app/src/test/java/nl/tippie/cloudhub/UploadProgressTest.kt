package nl.tippie.cloudhub

import nl.tippie.cloudhub.ui.UploadProgress
import nl.tippie.cloudhub.work.QueuedUpload
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How far a batch of uploads has got.
 *
 * The trap this exists for: the queue *shrinks*. A finished file is removed
 * from it, so a fraction computed over "what is left" lurches backwards every
 * time something completes — and that is only visible on a real upload of
 * several files over a slow connection, which is not something to go looking
 * for by hand.
 */
class UploadProgressTest {

    private val mb = 1024L * 1024
    private var next = 0

    private fun file(size: Long, name: String = "file-${next++}") =
        QueuedUpload(id = name, name = name, cachePath = "/tmp/$name", targetPath = "/", size = size)

    @Test
    fun `progress is measured in bytes, not in files`() {
        // A 10 KB note finishing beside a 4 GB video is not half the work.
        val note = file(10 * 1024, "note.txt")
        val video = file(4096 * mb, "holiday.mp4")
        val total = note.size + video.size

        // The note is done and gone; only the video is left, untouched.
        val summary = UploadProgress.summarise(listOf(video), video.id, 0, total)

        assertTrue(summary.fraction < 0.01f, "reads ${summary.fraction} — counting files, not bytes")
    }

    @Test
    fun `the fraction does not go backwards as files finish`() {
        val a = file(100 * mb, "a")
        val b = file(100 * mb, "b")
        val batch = a.size + b.size

        // Half of the first file sent.
        val midway = UploadProgress.summarise(listOf(a, b), a.id, 50 * mb, batch)
        // The first file has completed and left the queue.
        val afterFirst = UploadProgress.summarise(listOf(b), b.id, 0, batch)

        assertEquals(0.25f, midway.fraction)
        assertTrue(
            afterFirst.fraction >= midway.fraction,
            "went from ${midway.fraction} to ${afterFirst.fraction}",
        )
    }

    @Test
    fun `the batch total is a high-water mark`() {
        // Files completing must not shrink it...
        assertEquals(200 * mb, UploadProgress.batchTotal(200 * mb, 120 * mb))
        // ...but files added mid-batch must raise it, or the bar hits 100%
        // and keeps going.
        assertEquals(300 * mb, UploadProgress.batchTotal(200 * mb, 300 * mb))
    }

    @Test
    fun `files added mid-batch extend the bar rather than overflowing it`() {
        val existing = file(100 * mb, "a")
        val added = file(100 * mb, "b")

        val total = UploadProgress.batchTotal(100 * mb, existing.size + added.size)
        val summary = UploadProgress.summarise(listOf(existing, added), existing.id, 0, total)

        assertEquals(200 * mb, summary.totalBytes)
        assertEquals(0f, summary.fraction)
        assertEquals(2, summary.filesLeft)
    }

    @Test
    fun `an empty queue is finished, not nought per cent`() {
        val summary = UploadProgress.summarise(emptyList(), null, 0, 500 * mb)
        assertFalse(summary.active)
        assertTrue(summary.finished)
        assertEquals(0, summary.filesLeft)
    }

    @Test
    fun `an empty queue that never had anything says nothing at all`() {
        // Opening the app with no uploads must not flash "all done".
        val summary = UploadProgress.summarise(emptyList(), null, 0, 0)
        assertFalse(summary.active)
        assertFalse(summary.finished)
    }

    @Test
    fun `a resumed upload reporting a stale offset is clamped`() {
        // /api/uploads/init reports what the server already holds, which can
        // exceed what this file needs if a previous run left bytes behind.
        val only = file(10 * mb, "resumed")
        val summary = UploadProgress.summarise(listOf(only), only.id, 999 * mb, only.size)

        assertEquals(1f, summary.fraction)
        assertEquals(only.size, summary.doneBytes)
    }

    @Test
    fun `progress for a file that is not in the queue is ignored`() {
        // The worker's last report can name a file that has since completed.
        val waiting = file(10 * mb, "waiting")
        val summary = UploadProgress.summarise(listOf(waiting), "some-other-id", 5 * mb, waiting.size)

        assertEquals(0f, summary.fraction)
        assertEquals(null, summary.currentName)
    }

    @Test
    fun `a zero-byte file does not divide by zero`() {
        val empty = file(0, "empty.txt")
        val summary = UploadProgress.summarise(listOf(empty), empty.id, 0, 0)

        assertEquals(0f, summary.fraction)
        assertTrue(summary.active)
    }

    @Test
    fun `the file in flight is named`() {
        val a = file(10 * mb, "first.jpg")
        val b = file(10 * mb, "second.jpg")
        val summary = UploadProgress.summarise(listOf(a, b), b.id, mb, 20 * mb)

        assertEquals("second.jpg", summary.currentName)
        assertEquals(2, summary.filesLeft)
    }
}
