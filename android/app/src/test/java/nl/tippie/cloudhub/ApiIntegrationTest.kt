package nl.tippie.cloudhub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import nl.tippie.cloudhub.net.*
import nl.tippie.cloudhub.ui.DuplicateRules
import nl.tippie.cloudhub.ui.FavoriteRules
import nl.tippie.cloudhub.ui.FilesViewModel
import nl.tippie.cloudhub.ui.LoadState
import okhttp3.RequestBody.Companion.toRequestBody
import nl.tippie.cloudhub.ui.SearchRules
import nl.tippie.cloudhub.ui.ThumbnailRules
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
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
        private lateinit var http: CloudHubClient
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
            http = client
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

        val renamed = api.rename("$scratch/chunked.bin", "$scratch/renamed.bin")
        assertTrue(renamed.success)
        // The photo viewer keeps a renamed photo on screen at the path this says.
        assertEquals("$scratch/renamed.bin", renamed.path, "rename did not say where the file is now")
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
        // The viewer's Undo restores by the id the delete answered with, and
        // shows the photo again at the path the restore answers with.
        assertEquals(entry.id, deleted.id, "the delete did not say which trash entry it made")
        val restored = api.restore(deleted.id!!)
        assertTrue(restored.success)
        assertEquals("$scratch/renamed.bin", restored.path, "restore did not say where it put the file")
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
        // Filed under Photos, whether the server says so or the names do.
        assertEquals(FavoriteRules.Filter.PHOTOS, DuplicateRules.kindOf(planted))
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

    @Test fun `20 a session the server has ended sends the files screen back to sign-in`() = runBlocking<Unit> {
        requireServer()
        // A client the server has never signed in: what an app holding an
        // expired session -- or a password-only one, after two-step
        // verification was turned on elsewhere -- looks like to it.
        val stranger = CloudHubApi(baseUrl!!, CloudHubClient(InMemoryCookieStore(), object : PinnedCertificates {
            override fun isPinned(fingerprint: String) = false
        }))
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val files = FilesViewModel(stranger)
            files.open("/")
            val deadline = System.currentTimeMillis() + 10_000
            while (files.state.value.load == LoadState.LOADING && System.currentTimeMillis() < deadline) delay(20)
            assertTrue(files.state.value.signedOut, "a 401 on the listing must read as signed out, not as an empty folder")
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * A real PNG -- a colour gradient, encoded here by hand, since the host
     * JVM's image classes are not on an Android test's classpath.
     */
    private fun png(width: Int, height: Int): ByteArray {
        val rows = ByteArrayOutputStream()
        for (y in 0 until height) {
            rows.write(0)  // no filter
            for (x in 0 until width) {
                rows.write(x * 255 / width)
                rows.write(y * 255 / height)
                rows.write(0x40)
            }
        }
        val out = ByteArrayOutputStream()
        fun chunk(type: String, data: ByteArray) {
            val crc = CRC32().apply { update(type.toByteArray()); update(data) }
            out.write(ByteBuffer.allocate(4).putInt(data.size).array())
            out.write(type.toByteArray())
            out.write(data)
            out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
        }
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        // 8-bit RGB, no interlace.
        chunk("IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(2).put(0).put(0).put(0).array())
        chunk("IDAT", ByteArrayOutputStream().also { z -> DeflaterOutputStream(z).use { it.write(rows.toByteArray()) } }.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    /** The width and height a WebP declares, from whichever of its three headers it has. */
    private fun webpSize(bytes: ByteArray): Pair<Int, Int> {
        fun u8(i: Int) = bytes[i].toInt() and 0xFF
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun u24(i: Int) = u16(i) or (u8(i + 2) shl 16)
        return when (String(bytes, 12, 4, Charsets.US_ASCII)) {
            "VP8 " -> (u16(26) and 0x3FFF) to (u16(28) and 0x3FFF)
            "VP8L" -> {
                val bits = u24(21) or (u8(24) shl 24)
                ((bits and 0x3FFF) + 1) to (((bits shr 14) and 0x3FFF) + 1)
            }
            "VP8X" -> (u24(24) + 1) to (u24(27) + 1)
            else -> fail("not a WebP: ${String(bytes, 0, 16, Charsets.US_ASCII)}")
        }
    }

    @Test fun `21 a photo's thumbnail is a small WebP, and the photo is untouched`() = runBlocking {
        requireServer()
        val config = api.config()
        assumeTrue("this server keeps no thumbnails ahead of time", config.thumbnailScanSeconds != null)
        assertEquals(config.thumbnailFormat, api.thumbnailFormat, "the app does not ask with the format the server published")

        val original = png(1200, 800)
        put("$scratch/sunset.png", original)
        val entry = api.list(scratch).first { it.name == "sunset.png" }
        val url = api.thumbnailUrl(entry)
        assertEquals(config.thumbnailFormat?.toString(), url.queryParameter("t"))
        assertEquals(thumbnailVersion(entry.modified, entry.size), url.queryParameter("v"))

        val thumbnail = http.okHttp.newCall(Request.Builder().url(url).build()).execute().use { response ->
            assertEquals(200, response.code, "the thumbnail was not served")
            assertEquals("image/webp", response.header("Content-Type")?.substringBefore(';')?.trim())
            assertTrue(response.header("Cache-Control").orEmpty().contains("immutable"),
                "a thumbnail's URL changes with its photo, so it can be kept for good")
            response.body!!.bytes()
        }
        assertEquals("RIFF", String(thumbnail, 0, 4, Charsets.US_ASCII))
        assertEquals("WEBP", String(thumbnail, 8, 4, Charsets.US_ASCII))
        val (w, h) = webpSize(thumbnail)
        assertEquals(512, maxOf(w, h), "not fitted into 512 px: ${w}x$h")
        assertEquals(1.5, w.toDouble() / h, 0.01, "the photo's shape was not kept: ${w}x$h")
        assertTrue(thumbnail.size < original.size / 4, "a thumbnail of ${thumbnail.size} bytes is no saving")

        val downloaded = api.openDownload("$scratch/sunset.png").use { it.bytes() }
        assertTrue(downloaded.contentEquals(original), "the original was changed by making its thumbnail")
    }

    @Test fun `22 the thumbnail scan runs in slices to done, and can be read back`() = runBlocking {
        requireServer()
        assumeTrue("this server keeps no thumbnails ahead of time", api.config().thumbnailScanSeconds != null)
        val began = System.currentTimeMillis()
        var slices = 1
        var scan = api.continueThumbnailScan(restart = true)
        while (ThumbnailRules.next(scan, System.currentTimeMillis() - began, 0) == ThumbnailRules.Next.ASK_AGAIN && slices < 500) {
            scan = api.continueThumbnailScan()
            slices++
        }
        if (scan.busy) return@runBlocking  // someone else's scan holds it; theirs will finish
        assertTrue(scan.done, "the scan never finished after $slices slices: $scan")
        assertEquals(scan.found, scan.processed, "not everything it found was looked at")
        assertTrue(ThumbnailRules.ready(scan) >= 1, "the photo from the last test was not counted: $scan")

        val read = api.thumbnailScan()
        assertTrue(read.done && read.startedAt == scan.startedAt, "reading the scan back gave another: $read")

        // Begun again with every thumbnail current: found, checked, none made.
        var again = api.continueThumbnailScan(restart = true)
        while (ThumbnailRules.next(again, System.currentTimeMillis() - began, 0) == ThumbnailRules.Next.ASK_AGAIN) {
            again = api.continueThumbnailScan()
        }
        if (again.busy) return@runBlocking
        assertEquals(0, again.generated, "a second scan made thumbnails that were already there: $again")
    }

    @Test fun `99 clean up`() = runBlocking {
        requireServer()
        api.delete(scratch)
        api.trash().entries.filter { it.originalPath.startsWith(scratch) }
            .forEach { api.purge(it.id) }
    }
}
