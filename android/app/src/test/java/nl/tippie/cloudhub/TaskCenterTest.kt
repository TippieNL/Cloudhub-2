package nl.tippie.cloudhub

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.CloudHubClient
import nl.tippie.cloudhub.net.InMemoryCookieStore
import nl.tippie.cloudhub.net.PinnedCertificates
import nl.tippie.cloudhub.ui.TaskRules
import nl.tippie.cloudhub.work.TaskCenter
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The app's task follower against a real server with a task queue.
 *
 * What the screens rely on: a task queued from the phone is kicked, followed
 * and announced when it ends; checksums asked for here come back to be shown;
 * a ZIP asked for here lands in Downloads and its task goes; a task that ends
 * while the app is away is announced on return; and the follower stops
 * looking when there is nothing left to follow.
 *
 * Runs where CLOUDHUB_TEST_URL points at a Cloudhub-web; against a server
 * with no queue only the first test runs, and it checks the app offers none.
 */
class TaskCenterTest {

    companion object {
        private val baseUrl: String? = System.getenv("CLOUDHUB_TEST_URL")
        private val username: String = System.getenv("CLOUDHUB_TEST_USER") ?: "admin"
        private val password: String = System.getenv("CLOUDHUB_TEST_PASS") ?: "smoke-test-pass-123"

        private lateinit var api: CloudHubApi
        private var hasQueue = false
        private val scratch = "/_taskcenter_${System.currentTimeMillis()}"

        /** The app's main thread, for these tests: the follower runs on one thread. */
        private val executor = Executors.newSingleThreadExecutor()
        private val main = executor.asCoroutineDispatcher()

        @BeforeClass
        @JvmStatic
        fun signIn() {
            val url = baseUrl ?: return
            api = CloudHubApi(url, CloudHubClient(InMemoryCookieStore(), object : PinnedCertificates {
                override fun isPinned(fingerprint: String) = false
            }))
            runBlocking {
                api.login(username, password)
                hasQueue = runCatching { api.tasks().available }.getOrDefault(false)
                if (hasQueue) {
                    api.makeFolder(scratch)
                    api.makeFolder("$scratch/album")
                    put("$scratch/album/one.txt", "one".toByteArray())
                    put("$scratch/album/two.txt", "two".toByteArray())
                }
            }
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            if (baseUrl != null && hasQueue) runBlocking {
                runCatching { api.delete(scratch) }
                api.trash().entries.filter { it.originalPath.startsWith(scratch) }.forEach { entry ->
                    val purged = api.purge(entry.id)
                    purged.job?.takeIf { purged.queued }?.let { settle(it.id) }
                }
                api.clearTasks()
            }
            executor.shutdown()
        }

        private suspend fun put(path: String, bytes: ByteArray) {
            val id = "tc${System.nanoTime()}"
            api.uploadInit(id, path.substringBeforeLast('/'), path.substringAfterLast('/'), bytes.size.toLong())
            api.uploadChunk(id, 0, bytes.toRequestBody())
            api.uploadComplete(id)
        }

        /** Run the server's queue until a task ends, as a second device would. */
        private suspend fun settle(id: String) {
            val deadline = System.currentTimeMillis() + 60_000
            while (TaskRules.isActive(api.task(id))) {
                if (System.currentTimeMillis() > deadline) fail("task $id never finished")
                runCatching { api.runQueue() }
                delay(200)
            }
        }
    }

    /** What the follower saved to "Downloads". */
    private val saved = ConcurrentHashMap<String, ByteArray>()
    private lateinit var scope: CoroutineScope
    private lateinit var center: TaskCenter

    @Before
    fun setUp() {
        assumeTrue("set CLOUDHUB_TEST_URL to run the task follower tests", baseUrl != null)
        scope = CoroutineScope(SupervisorJob() + main)
        center = TaskCenter(api, save = { name, input -> saved[name] = input.readBytes() }, scope = scope)
    }

    @After
    fun tearDown() {
        if (::scope.isInitialized) scope.cancel()
    }

    /** Do something to the follower on its own thread, as the app's screens do. */
    private suspend fun <T> onMain(block: suspend TaskCenter.() -> T): T = withContext(main) { center.block() }

    private suspend fun awaitEvent(what: String, match: (TaskCenter.Event) -> Boolean): TaskCenter.Event =
        try {
            withTimeout(60_000) { center.events.first(match) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            fail("no $what within a minute; state ${center.state.value}")
        }

    private suspend fun eventually(what: String, check: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!check()) {
            if (System.currentTimeMillis() > deadline) fail("never: $what")
            delay(100)
        }
    }

    private fun checksumOf(vararg paths: String): JsonObject = buildJsonObject {
        putJsonArray("paths") { paths.forEach { add(it) } }
        put("algorithm", "sha256")
    }

    @Test
    fun `the follower learns whether the server has a queue`() = runBlocking<Unit> {
        onMain { start() }
        eventually("an answer about the queue") { onMain { state.value.supported != null } }
        // Cloudhub-2's own server answers 404: no queue, and nothing is offered.
        assertEquals(hasQueue, onMain { state.value.available })
        if (!hasQueue) assertFalse(onMain { looking }, "a server with no queue is not asked again")
    }

    @Test
    fun `a checksum queued here comes back to be shown, and the follower then rests`() = runBlocking<Unit> {
        assumeTrue("this server has no task queue", hasQueue)
        onMain { start() }
        val task = onMain { queue("checksum", checksumOf("$scratch/album/one.txt"), checksums = true) }
        // On the badge before the server has even been asked again.
        assertTrue(onMain { state.value.active } >= 1)

        val event = awaitEvent("checksum dialog") { it is TaskCenter.Event.Checksums } as TaskCenter.Event.Checksums
        assertEquals(task.id, event.task.id)
        val expected = MessageDigest.getInstance("SHA-256").digest("one".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(expected, TaskRules.checksums(event.task).single().hash)

        eventually("the follower stops once nothing is queued or running") {
            onMain { !looking && state.value.active == 0 }
        }
    }

    @Test
    fun `a ZIP asked for here lands in Downloads, and its task goes with it`() = runBlocking<Unit> {
        assumeTrue("this server has no task queue", hasQueue)
        onMain { start() }
        val task = onMain {
            queue("archive", buildJsonObject {
                putJsonArray("paths") { add("$scratch/album") }
                put("mode", "download")
            }, download = true)
        }
        awaitEvent("saved download") { it is TaskCenter.Event.Message && it.text == "Saved album.zip to Downloads" }

        val bytes = assertNotNull(saved["album.zip"], "nothing saved; saved ${saved.keys}")
        val names = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) names += (zip.nextEntry ?: break).name.substringAfterLast('/')
        }
        assertTrue(names.containsAll(setOf("one.txt", "two.txt")), "the ZIP holds $names")
        // Safely on the phone: the server's copy is one of the three an account may keep.
        eventually("the downloaded ZIP's task is removed") { api.tasks().jobs.none { it.id == task.id } }
    }

    @Test
    fun `a copy the server took to the background is followed and reloads the folder`() = runBlocking<Unit> {
        assumeTrue("this server has no task queue", hasQueue)
        onMain { start() }
        api.makeFolder("$scratch/big")
        repeat(205) { put("$scratch/big/f$it.txt", "$it".toByteArray()) }
        api.makeFolder("$scratch/dest")
        val result = api.copy(listOf("$scratch/big"), "$scratch/dest")
        val job = assertNotNull(result.job?.takeIf { result.queued }, "a 205-file copy was not queued: $result")
        // What FilesViewModel does with that answer.
        onMain { follow(job) }

        awaitEvent("folder reload") { it == TaskCenter.Event.FilesChanged }
        assertEquals(205, api.list("$scratch/dest/big").size)
    }

    @Test
    fun `a task that ends while the app is away is announced on return`() = runBlocking<Unit> {
        assumeTrue("this server has no task queue", hasQueue)
        onMain { start() }
        val task = onMain {
            val queued = queue("checksum", checksumOf("$scratch/album/two.txt"))
            // Straight to the background: nothing is fetched, so it stops looking.
            setForeground(false)
            queued
        }
        settle(task.id)
        assertFalse(onMain { looking }, "the follower kept polling in the background")

        onMain { setForeground(true) }
        awaitEvent("announcement") { it is TaskCenter.Event.Message && it.text == "Done: ${task.label}" }
    }

    @Test
    fun `cancelling, retrying and clearing go through the follower`() = runBlocking<Unit> {
        assumeTrue("this server has no task queue", hasQueue)
        onMain { start() }
        // Queued straight to the server, not kicked: it waits unless a
        // worker process runs the queue by itself.
        val waiting = api.queueTask("checksum", checksumOf("$scratch/album/one.txt"))
        val said = onMain { cancel(waiting.id) }
        assertTrue(said == "Cancelled" || said == "Stopping…", said)
        settle(waiting.id)
        if (api.task(waiting.id).status == TaskRules.CANCELLED) {
            onMain { retry(waiting.id) }
            eventually("the retried task finishes") { api.task(waiting.id).status == TaskRules.COMPLETED }
        }
        assertTrue(onMain { clear() } >= 1)
        assertTrue(api.tasks().jobs.none { it.id == waiting.id }, "a cleared task is still listed")
    }
}
