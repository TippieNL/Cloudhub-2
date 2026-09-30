package nl.tippie.cloudhub

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import nl.tippie.cloudhub.net.*
import nl.tippie.cloudhub.ui.DuplicateRules
import nl.tippie.cloudhub.ui.TaskRules
import okhttp3.RequestBody.Companion.toRequestBody
import nl.tippie.cloudhub.ui.SearchRules
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The API layer against a real, running CloudHub.
 *
 * These run on the host JVM rather than a device, which is the point: the half
 * of the app that talks to the server can be proven for real without an
 * emulator. Only the Compose UI is left needing a phone.
 *
 * Start a server first and point CLOUDHUB_TEST_URL at it; without that the
 * tests skip rather than fail, so an ordinary `gradle build` stays green.
 *
 *     php -S 127.0.0.1:8900 -t public
 *     CLOUDHUB_TEST_URL=http://127.0.0.1:8900 gradle test
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ApiIntegrationTest {

    companion object {
        private val baseUrl: String? = System.getenv("CLOUDHUB_TEST_URL")
        private val username: String = System.getenv("CLOUDHUB_TEST_USER") ?: "admin"
        private val password: String = System.getenv("CLOUDHUB_TEST_PASS") ?: "smoke-test-pass-123"

        private lateinit var api: CloudHubApi
        private var signedIn = false

        /** Folder this run owns, so a failure never damages real files. */
        private val scratch = "/_apitest_${System.currentTimeMillis()}"

        @BeforeClass
        @JvmStatic
        fun signIn() {
            val url = baseUrl ?: return
            val client = CloudHubClient(InMemoryCookieStore(), object : PinnedCertificates {
                override fun isPinned(fingerprint: String) = false
            })
            api = CloudHubApi(url, client)
            runBlocking {
                val result = api.login(username, password)
                signedIn = result.success
                api.makeFolder(scratch)
            }
        }
    }

    private fun requireServer() {
        assumeTrue("set CLOUDHUB_TEST_URL to run the API tests", baseUrl != null)
        assertTrue(signedIn, "sign in failed; check CLOUDHUB_TEST_USER/PASS")
    }

    /** Put bytes at a path, through the ordinary chunked upload. */
    private suspend fun put(path: String, bytes: ByteArray) {
        val folder = path.substringBeforeLast('/')
        val name = path.substringAfterLast('/')
        val id = "fixture${System.nanoTime()}"
        api.uploadInit(id, folder, name, bytes.size.toLong())
        api.uploadChunk(id, 0, bytes.toRequestBody())
        api.uploadComplete(id)
    }

    @Test fun `01 sign in returns a user and a csrf token`() = runBlocking {
        requireServer()
        val status = api.status()
        assertTrue(status.authenticated)
        assertEquals(username, status.user?.username)
        assertTrue(status.csrfToken.isNotBlank(), "no CSRF token, so every write would be refused")
    }

    @Test fun `02 the scratch folder is listed`() = runBlocking {
        requireServer()
        val root = api.list("/")
        assertTrue(root.any { it.path == scratch && it.isDirectory }, "scratch folder missing from $root")
    }

    @Test fun `03 upload sends a file in chunks and completes`() = runBlocking {
        requireServer()
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        val id = "test${System.nanoTime()}"

        val init = api.uploadInit(id, scratch, "chunked.bin", bytes.size.toLong())
        assertEquals(0L, init.received)

        var offset = 0L
        while (offset < bytes.size) {
            val end = minOf(offset + 100_000, bytes.size.toLong())
            val slice = bytes.copyOfRange(offset.toInt(), end.toInt())
            val status = api.uploadChunk(id, offset, slice.toRequestBody())
            // The server reports what it holds; trusting the local count is
            // exactly the bug that makes resume unreliable.
            assertEquals(end, status.received)
            offset = status.received
        }

        val done = api.uploadComplete(id)
        assertTrue(done.success)
        assertEquals("chunked.bin", done.name)

        val listed = api.list(scratch)
        val uploaded = listed.firstOrNull { it.name == "chunked.bin" }
        assertNotNull(uploaded, "uploaded file not in the listing")
        assertEquals(bytes.size.toLong(), uploaded.size)
    }

    @Test fun `04 an interrupted upload resumes from the server offset`() = runBlocking {
        requireServer()
        val bytes = ByteArray(250_000) { (it % 97).toByte() }
        val id = "resume${System.nanoTime()}"

        api.uploadInit(id, scratch, "resumed.bin", bytes.size.toLong())
        // Send only the first slice, then abandon it as a crash would.
        api.uploadChunk(id, 0, bytes.copyOfRange(0, 90_000).toRequestBody())

        // A fresh init is what the app does on relaunch: it must report the
        // bytes already held rather than starting again from zero.
        val resumed = api.uploadInit(id, scratch, "resumed.bin", bytes.size.toLong())
        assertEquals(90_000L, resumed.received)

        var offset = resumed.received
        while (offset < bytes.size) {
            val end = minOf(offset + 100_000, bytes.size.toLong())
            offset = api.uploadChunk(id, offset,
                bytes.copyOfRange(offset.toInt(), end.toInt()).toRequestBody()).received
        }
        assertTrue(api.uploadComplete(id).success)

        val uploaded = api.list(scratch).first { it.name == "resumed.bin" }
        assertEquals(bytes.size.toLong(), uploaded.size, "resumed upload is the wrong length")
    }

    @Test fun `05 download returns the bytes that were uploaded`() = runBlocking {
        requireServer()
        val body = api.openDownload("$scratch/chunked.bin")
        val received = body.use { it.bytes() }
        assertEquals(300_000, received.size)
        assertEquals((7 % 251).toByte(), received[7], "downloaded content does not match")
    }

    @Test fun `06 rename move and copy do what they say`() = runBlocking {
        requireServer()
        api.makeFolder("$scratch/sub")

        assertTrue(api.rename("$scratch/chunked.bin", "$scratch/renamed.bin").success)
        assertTrue(api.list(scratch).any { it.name == "renamed.bin" })

        val moved = api.move(listOf("$scratch/renamed.bin"), "$scratch/sub")
        assertEquals(1, moved.completed)
        assertTrue(moved.failed.isEmpty(), "move reported ${moved.failed}")
        assertTrue(api.list("$scratch/sub").any { it.name == "renamed.bin" })

        val copied = api.copy(listOf("$scratch/sub/renamed.bin"), scratch)
        assertEquals(1, copied.completed)
        assertTrue(api.list(scratch).any { it.name == "renamed.bin" })
    }

    @Test fun `07 moving a folder into itself is refused per item`() = runBlocking {
        requireServer()
        val result = api.move(listOf("$scratch/sub"), "$scratch/sub")
        assertEquals(0, result.completed)
        assertEquals(1, result.failed.size, "a refused move must be reported, not swallowed")
    }

    @Test fun `08 search finds a file in a subfolder`() = runBlocking {
        requireServer()
        val found = api.search("renamed", scratch)
        assertTrue(found.results.any { it.path == "$scratch/sub/renamed.bin" },
            "recursive search missed it: ${found.results.map { it.path }}")
    }

    /**
     * What All folders does: from the root, in slices, until the server says
     * the answer is whole. A server without slices answers in one go and the
     * loop ends after one request; either way the file must be found.
     */
    @Test fun `08b an all-folders search in slices finds it from the root`() = runBlocking {
        requireServer()
        var found = api.search("renamed", SearchRules.SCOPE, 250)
        var slices = 1
        var scanned = -1
        while (SearchRules.shouldContinue(found, scanned, slices)) {
            scanned = found.scanned
            found = api.search("renamed", SearchRules.SCOPE, 250)
            slices++
        }
        assertFalse(found.incomplete, "gave up after $slices slices at ${found.scanned} entries")
        assertTrue(found.results.any { it.path == "$scratch/sub/renamed.bin" },
            "all-folders search missed it after $slices slices: ${found.results.map { it.path }}")
    }

    @Test fun `09 delete goes to the trash and restores`() = runBlocking {
        requireServer()
        val deleted = api.delete("$scratch/renamed.bin")
        assertTrue(deleted.success)
        assertTrue(api.list(scratch).none { it.name == "renamed.bin" })

        if (!deleted.trashed) return@runBlocking   // trash disabled on this server
        val entry = api.trash().entries.firstOrNull { it.originalPath == "$scratch/renamed.bin" }
            ?: fail("deleted file is not in the trash")
        assertTrue(api.restore(entry.id).success)
        assertTrue(api.list(scratch).any { it.name == "renamed.bin" }, "restore did not put it back")
    }

    @Test fun `10 a share link is created and revoked`() = runBlocking {
        requireServer()
        val share = api.createShare("$scratch/renamed.bin", 24)
        assertTrue(share.token.isNotBlank())
        assertTrue(share.url.contains(share.token), "share url does not carry the token: ${share.url}")
        assertNotNull(share.expiresAt, "an expiry was asked for but not set")
        // Revoked by token: a link outlives the path it was created from.
        assertTrue(api.revokeShare(share.token).success)
    }

    @Test fun `11 a missing file reports a typed error, not a crash`() = runBlocking {
        requireServer()
        try {
            api.list("$scratch/does-not-exist")
            fail("expected the server to refuse")
        } catch (e: ApiError) {
            assertEquals(404, e.status)
            assertTrue(e.message!!.isNotBlank(), "the error carried no message to show")
        }
    }

    @Test fun `12 a reserved path is refused`() = runBlocking {
        requireServer()
        try {
            api.list("/.trash")
            fail("the trash must not be browsable through the file routes")
        } catch (e: ApiError) {
            assertEquals(403, e.status)
        }
    }

    @Test fun `13 the server publishes the duplicate finder's limits`() = runBlocking {
        requireServer()
        val config = api.config()
        // Their absence is how a build without the feature says so, which is
        // what the screen checks before offering to scan.
        assertNotNull(config.duplicateScanSeconds, "no duplicate finder on this server")
        assertNotNull(config.duplicateMaxFiles, "the walk limit was not published")
        // assertNotNull returns the value, so the block would not be Unit.
        assertTrue(config.duplicateMinBytes != null, "the minimum size was not published")
    }

    @Test fun `14 a duplicate scan polls to done and finds a planted copy`() = runBlocking {
        requireServer()
        // Two identical files and one the same size but not the same bytes:
        // the decoy is what proves the answer is the content, not the size.
        val photo = ByteArray(60_000) { ((it * 31) % 251).toByte() }
        val decoy = ByteArray(60_000) { ((it * 17) % 241).toByte() }
        put("$scratch/original.jpg", photo)
        api.makeFolder("$scratch/copies")
        put("$scratch/copies/original.jpg", photo)
        put("$scratch/decoy.jpg", decoy)

        var slices = 1
        var scan = api.startDuplicateScan()
        while (DuplicateRules.shouldContinue(scan, slices)) {
            scan = api.continueDuplicateScan()
            slices++
        }
        assertTrue(scan.done, "the scan never finished after $slices slices")

        val planted = scan.groups.firstOrNull { group ->
            group.files.any { it.path == "$scratch/original.jpg" }
        }
        assertNotNull(planted, "the planted copy was not found: ${scan.groups.map { g -> g.files.map { it.path } }}")
        assertEquals(2, planted.count)
        assertEquals(
            listOf("$scratch/copies/original.jpg", "$scratch/original.jpg"),
            planted.files.map { it.path }.sorted(),
        )
        assertEquals(60_000L, planted.bytes)
        // bytes x (copies - 1): what deleting the extra copy gives back, never
        // the whole group.
        assertEquals(60_000L, planted.reclaimable)
        // Every copy carries what the screen needs to name and sort it.
        assertTrue(planted.files.all { it.mtime > 0 }, "no modification time: ${planted.files}")

        assertTrue(
            scan.groups.none { g -> g.files.any { it.path == "$scratch/decoy.jpg" } },
            "a file of the same size but different bytes was called a duplicate",
        )
    }

    @Test fun `15 the last scan is readable without doing the work again`() = runBlocking {
        requireServer()
        val again = api.lastDuplicateScan()
        assertTrue(again.started, "the finished scan was not kept")
        assertTrue(again.done)
        assertTrue(
            again.groups.any { g -> g.files.any { it.path == "$scratch/original.jpg" } },
            "reading the saved scan lost the group the scan found",
        )
    }

    @Test fun `16 a scan can be thrown away`() = runBlocking {
        requireServer()
        assertTrue(api.forgetDuplicateScan().success)
        val empty = api.lastDuplicateScan()
        // The one reply that means "nothing has ever been scanned here", which
        // is different from "nothing was found".
        assertFalse(empty.started)
        assertEquals(emptyList(), empty.groups)
    }

    @Test fun `17 a file is starred, listed and unstarred`() = runBlocking {
        requireServer()
        put("$scratch/starred.jpg", "not really a photo".toByteArray())
        val added = api.addFavorite("$scratch/starred.jpg")
        assertTrue(added.success && added.favorite)
        // A second star is not an error, and does not list the file twice.
        assertTrue(api.addFavorite("$scratch/starred.jpg").success)

        val listed = api.favorites().favorites.filter { it.path == "$scratch/starred.jpg" }
        assertEquals(1, listed.size)
        // An ordinary listing row: the cards and viewers take it as it is.
        assertEquals("starred.jpg", listed.single().name)
        assertEquals(FileEntry.Kind.IMAGE, listed.single().kind)

        assertFalse(api.removeFavorite("$scratch/starred.jpg").favorite)
        assertFalse(api.favorites().favorites.any { it.path == "$scratch/starred.jpg" })
    }

    @Test fun `18 a star follows its file when it is renamed`() = runBlocking {
        requireServer()
        put("$scratch/before.txt", "follows".toByteArray())
        api.addFavorite("$scratch/before.txt")
        api.rename("$scratch/before.txt", "$scratch/after.txt")
        val paths = api.favorites().favorites.map { it.path }
        assertTrue("$scratch/after.txt" in paths, "the star did not follow the rename: $paths")
        assertFalse("$scratch/before.txt" in paths)
        // And it is removed under the name it has now.
        assertFalse(api.removeFavorite("$scratch/after.txt").favorite)
        assertFalse(api.favorites().favorites.any { it.path == "$scratch/after.txt" })
    }

    @Test fun `19 a folder cannot be starred`() = runBlocking {
        requireServer()
        try {
            api.addFavorite(scratch)
            fail("a folder was starred")
        } catch (e: ApiError) {
            assertEquals(400, e.status)
        }
    }

    /* ---- background tasks (Cloudhub-web) ------------------------------------------
     *
     * Only a server with a task queue has these routes. Cloudhub-2's own server
     * answers 404, which is what the app reads as "offer none of it", so there
     * the tests below only check that answer and skip the rest.
     */

    /** The server's tasks, or null where it has no queue. */
    private suspend fun queueOrNull(): TaskList? = try {
        api.tasks()
    } catch (e: ApiError) {
        if (e.status == 404) null else throw e
    }

    private fun requireQueue() {
        requireServer()
        val list = runBlocking { queueOrNull() }
        assumeTrue("this server has no task queue", list != null && list.available)
    }

    /** Wait for a task to end, asking the server to run the queue as the app does. */
    private suspend fun settle(id: String, timeoutMs: Long = 120_000): BackgroundTask {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val task = api.task(id)
            if (!TaskRules.isActive(task)) return task
            if (System.currentTimeMillis() > deadline) fail("task $id was still ${task.status} after ${timeoutMs}ms")
            runCatching { api.runQueue() }
            delay(250)
        }
    }

    @Test fun `20 a server says whether it has a task queue`() = runBlocking {
        requireServer()
        // Either a list, or the 404 the app reads as "no queue here" -- never
        // anything else, or the app would offer tasks it cannot run.
        val list = queueOrNull()
        if (list != null) {
            assertTrue(list.runner in setOf("cli", "inline", "none"), "unknown runner ${list.runner}")
            assertEquals(list.jobs.count(TaskRules::isActive), list.active)
        }
    }

    @Test fun `21 a checksum runs in the background and gives the file's SHA-256`() = runBlocking {
        requireQueue()
        val bytes = "checksum me\n".toByteArray()
        put("$scratch/sum.txt", bytes)
        val queued = api.queueTask("checksum", buildJsonObject {
            putJsonArray("paths") { add("$scratch/sum.txt") }
            put("algorithm", "sha256")
        })
        assertEquals("checksum", queued.type)
        assertTrue(TaskRules.isActive(queued), "a new task is queued, not ${queued.status}")

        val done = settle(queued.id)
        assertEquals(TaskRules.COMPLETED, done.status, "checksum ended ${done.status}: ${done.error}")
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(listOf(TaskRules.Checksum("$scratch/sum.txt", expected)), TaskRules.checksums(done))
        assertTrue(api.removeTask(done.id).success)
    }

    @Test fun `22 a folder is zipped on the server and downloaded`() = runBlocking {
        requireQueue()
        api.makeFolder("$scratch/zipme")
        put("$scratch/zipme/a.txt", "alpha".toByteArray())
        put("$scratch/zipme/b.txt", "bravo".toByteArray())

        val queued = api.queueTask("archive", buildJsonObject {
            putJsonArray("paths") { add("$scratch/zipme") }
            put("mode", "download")
        })
        val done = settle(queued.id)
        assertEquals(TaskRules.COMPLETED, done.status, "archive ended ${done.status}: ${done.error}")
        assertTrue(done.hasDownload, "a finished download archive offers no download")
        assertEquals("zipme.zip", TaskRules.downloadName(done))

        val entries = mutableMapOf<String, String>()
        api.openTaskDownload(done.id).use { body ->
            ZipInputStream(body.byteStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) entries[entry.name.substringAfterLast('/')] = zip.readBytes().decodeToString()
                }
            }
        }
        assertEquals(mapOf("a.txt" to "alpha", "b.txt" to "bravo"), entries)

        // Removing it removes the archive with it.
        assertTrue(api.removeTask(done.id).success)
        try {
            api.task(done.id)
            fail("a removed task is still there")
        } catch (e: ApiError) {
            assertEquals(404, e.status)
        }
    }

    @Test fun `23 a ZIP is made beside its folder and extracted again`() = runBlocking {
        requireQueue()
        val made = settle(api.queueTask("archive", buildJsonObject {
            putJsonArray("paths") { add("$scratch/zipme") }
            put("mode", "save")
            put("name", "packed.zip")
        }).id)
        assertEquals(TaskRules.COMPLETED, made.status, "compress ended ${made.status}: ${made.error}")
        assertTrue(api.list(scratch).any { it.name == "packed.zip" }, "the ZIP was not saved beside its folder")

        val unpacked = settle(api.queueTask("extract", buildJsonObject { put("path", "$scratch/packed.zip") }).id)
        assertEquals(TaskRules.COMPLETED, unpacked.status, "extract ended ${unpacked.status}: ${unpacked.error}")
        val text = TaskRules.resultText(unpacked) { "$it" }
        assertNotNull(text, "an extraction says what it did")
        assertTrue(text.startsWith("Extracted 2 files into $scratch/packed"), text)
        val folder = api.list(scratch).first { it.isDirectory && it.name.startsWith("packed") }
        val inside = api.list(folder.path).flatMap { if (it.isDirectory) api.list(it.path) else listOf(it) }
        assertEquals(setOf("a.txt", "b.txt"), inside.map { it.name }.toSet())
    }

    @Test fun `24 a copy too large to wait for is queued and followed to the end`() = runBlocking {
        requireQueue()
        // More files than the server copies while a request waits (200 by default).
        api.makeFolder("$scratch/many")
        repeat(205) { put("$scratch/many/f$it.txt", "file $it".toByteArray()) }
        api.makeFolder("$scratch/copyto")

        val result = api.copy(listOf("$scratch/many"), "$scratch/copyto")
        assertTrue(result.queued, "a 205-file copy was done in the request: $result")
        val task = assertNotNull(result.job)
        assertEquals("copy", task.type)

        val done = settle(task.id)
        assertEquals(TaskRules.COMPLETED, done.status, "copy ended ${done.status}: ${done.error}")
        assertEquals(emptyList(), TaskRules.failures(done))
        assertEquals(205, api.list("$scratch/copyto/many").size)

        // A small copy is still done at once, as before there was a queue.
        val small = api.copy(listOf("$scratch/zipme/a.txt"), "$scratch/copyto")
        assertFalse(small.queued)
        assertEquals(1, small.completed)
    }

    @Test fun `25 a queued task can be cancelled, retried and removed`() = runBlocking {
        requireQueue()
        // Nothing asks the server to run this, so it is still waiting when
        // cancelled -- unless a worker process runs the queue by itself.
        val queued = api.queueTask("checksum", buildJsonObject {
            putJsonArray("paths") { add("$scratch/sum.txt") }
            put("algorithm", "sha256")
        })
        val cancel = api.cancelTask(queued.id)
        assertTrue(cancel.status in setOf("cancelled", "cancelling"), "cancel answered ${cancel.status}")
        val stopped = settle(queued.id)
        if (stopped.status == TaskRules.CANCELLED) {
            assertTrue(stopped.canRetry && stopped.canRemove && !stopped.canCancel)
            val again = api.retryTask(queued.id)
            assertNotNull(again.job, "a retry answers with the task")
            assertEquals(TaskRules.COMPLETED, settle(queued.id).status)
        }
        assertTrue(api.clearTasks().removed >= 1, "clearing finished tasks removed nothing")
        assertTrue(api.tasks().jobs.none { it.id == queued.id }, "a cleared task is still listed")
    }

    @Test fun `26 a duplicate scan runs as a task and its findings are read as it goes`() = runBlocking {
        requireQueue()
        val queued = try {
            api.queueTask("duplicates", buildJsonObject { put("path", "/") })
        } catch (e: ApiError) {
            // Somebody else's scan is running: one at a time.
            assumeTrue("a duplicate scan is already running on this server", e.status != 409)
            throw e
        }
        val done = settle(queued.id, timeoutMs = 300_000)
        assertEquals(TaskRules.COMPLETED, done.status, "the scan ended ${done.status}: ${done.error}")
        val scan = api.lastDuplicateScan()
        assertTrue(scan.done, "the task finished but the scan it drove did not")
        // The copies test 14 planted are found again, by the worker this time.
        assertTrue(
            scan.groups.any { g -> g.files.any { it.path == "$scratch/original.jpg" } },
            "the background scan missed the planted copy",
        )
        assertTrue(TaskRules.resultText(done) { "$it" } != null, "a finished scan says what it found")
    }

    @Test fun `99 clean up`() = runBlocking {
        requireServer()
        api.delete(scratch)
        for (entry in api.trash().entries.filter { it.originalPath.startsWith(scratch) }) {
            // A large folder is purged in the background on a server with a
            // queue; seen through, so the run leaves no task waiting behind it.
            val purged = api.purge(entry.id)
            if (purged.queued) purged.job?.let { settle(it.id) }
        }
        if (queueOrNull()?.available == true) api.clearTasks()
    }
}
