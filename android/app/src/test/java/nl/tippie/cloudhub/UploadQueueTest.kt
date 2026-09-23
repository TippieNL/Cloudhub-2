package nl.tippie.cloudhub

import nl.tippie.cloudhub.work.QueuedUpload
import nl.tippie.cloudhub.work.UploadQueue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The queue file is shared by every UploadQueue instance.
 *
 * The worker and the activity each make their own, and @Synchronized locked
 * only the instance it was declared on: a file shared while the worker
 * removed a finished one could be read, overwritten by the other, and dropped
 * from the queue with nothing to say so.
 */
class UploadQueueTest {

    private fun item(n: Int) = QueuedUpload("q$n", "file-$n.jpg", "/cache/$n", "/", 10)

    @Test
    fun `two instances adding at once lose nothing`() {
        val dir = Files.createTempDirectory("uq").toFile()
        val first = UploadQueue(dir)
        val second = UploadQueue(dir)
        val start = CountDownLatch(1)
        val workers = listOf(first to 0, second to 1000).map { (queue, base) ->
            thread { start.await(); repeat(200) { queue.add(item(base + it)) } }
        }
        start.countDown()
        workers.forEach { it.join() }
        assertEquals(400, UploadQueue(dir).all().size)
        dir.deleteRecursively()
    }

    @Test
    fun `a removal in one instance keeps what another just added`() {
        val dir = Files.createTempDirectory("uq").toFile()
        val worker = UploadQueue(dir)
        val activity = UploadQueue(dir)
        repeat(100) { worker.add(item(it)) }
        val start = CountDownLatch(1)
        val remover = thread { start.await(); repeat(100) { worker.remove("q$it") } }
        val adder = thread { start.await(); repeat(100) { activity.add(item(5000 + it)) } }
        start.countDown()
        remover.join(); adder.join()
        val left = UploadQueue(dir).all().map { it.id }.toSet()
        assertEquals((0 until 100).map { "q${5000 + it}" }.toSet(), left)
        dir.deleteRecursively()
    }

    @Test
    fun `a write leaves no temporary file behind`() {
        val dir = Files.createTempDirectory("uq").toFile()
        UploadQueue(dir).add(item(1))
        assertFalse(File(dir, "upload-queue.json.tmp").exists())
        assertEquals(listOf("q1"), UploadQueue(dir).all().map { it.id })
        dir.deleteRecursively()
    }
}
