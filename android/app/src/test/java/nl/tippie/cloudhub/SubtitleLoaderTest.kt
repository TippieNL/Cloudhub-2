package nl.tippie.cloudhub

import kotlinx.coroutines.runBlocking
import nl.tippie.cloudhub.data.SubtitleLoader
import nl.tippie.cloudhub.net.ApiError
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.CloudHubClient
import nl.tippie.cloudhub.net.FileEntry
import nl.tippie.cloudhub.net.InMemoryCookieStore
import nl.tippie.cloudhub.net.PinnedCertificates
import nl.tippie.cloudhub.ui.SidecarSubtitles
import nl.tippie.cloudhub.ui.SubtitleRules
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A subtitle added from the app is found again, on either server.
 *
 * The bug this holds shut: against Cloudhub-web, which has no subtitle route,
 * the app asked only /api/files/subtitles, got "API endpoint not found", and
 * so never saw a track -- a subtitle that had uploaded fine was then reported
 * as "did not arrive on the server". The upload here is the one the app's
 * queue makes, under the name SubtitleRules gives it.
 *
 * Against Cloudhub-2, which has the route, the server's own answer is also
 * compared with the app's for the same folder: the port must not drift.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SubtitleLoaderTest {

    companion object {
        private val baseUrl: String? = System.getenv("CLOUDHUB_TEST_URL")?.takeIf { it.isNotBlank() }
        private lateinit var api: CloudHubApi
        private val folder = "/_subload_${System.currentTimeMillis()}"
        private val cache: File = Files.createTempDirectory("subtitles").toFile()

        @BeforeClass @JvmStatic
        fun signIn() {
            val url = baseUrl ?: return
            api = CloudHubApi(url, CloudHubClient(InMemoryCookieStore(), object : PinnedCertificates {
                override fun isPinned(fingerprint: String) = false
            }))
            runBlocking {
                api.login(System.getenv("CLOUDHUB_TEST_USER") ?: "admin", System.getenv("CLOUDHUB_TEST_PASS") ?: "smoke-test-pass-123")
                api.makeFolder(folder)
            }
        }

        @AfterClass @JvmStatic
        fun tidy() {
            if (baseUrl != null) runBlocking { runCatching { api.delete(folder) } }
            cache.deleteRecursively()
        }

        /** As the upload queue sends it. */
        private suspend fun put(path: String, bytes: ByteArray) {
            val id = "subload${System.nanoTime()}"
            api.uploadInit(id, path.substringBeforeLast('/'), path.substringAfterLast('/'), bytes.size.toLong())
            api.uploadChunk(id, 0, bytes.toRequestBody())
            api.uploadComplete(id)
        }

        private suspend fun hasRoute(video: FileEntry): Boolean = try {
            api.subtitles(video.path); true
        } catch (e: ApiError) {
            if (SubtitleLoader.isMissingRoute(e)) false else throw e
        }
    }

    private fun requireServer() = assumeTrue("set CLOUDHUB_TEST_URL to run the subtitle tests", baseUrl != null)

    private suspend fun video(name: String): FileEntry = api.list(folder).first { it.name == name }

    @Test fun `01 a subtitle added from the app is found and plays as WebVTT`() = runBlocking {
        requireServer()
        put("$folder/Holiday.mp4", ByteArray(4096) { it.toByte() })
        // Dutch, written on Windows: é as 0xE9, which is not UTF-8.
        val name = SubtitleRules.fileNameFor("Holiday.mp4", "nl", "srt")
        put("$folder/$name", "1\r\n00:00:01,000 --> 00:00:03,000\r\nCaf".toByteArray() + byteArrayOf(0xE9.toByte()) + "\r\n".toByteArray())

        val loader = SubtitleLoader(api, cache)
        val found = loader.find(video("Holiday.mp4"))
        val track = assertNotNull(found.firstOrNull { it.name == name }, "$name was not found: $found")
        assertEquals("Dutch", track.label)
        assertEquals("nl", track.language)

        val playable = loader.playable(found).single { it.name == name }
        if (SubtitleLoader.isLocal(playable)) {
            // No route on this server: converted on the phone, and read from disk.
            val vtt = File(java.net.URI(playable.url)).readText()
            assertTrue(vtt.startsWith("WEBVTT\n\n"), vtt)
            assertTrue(vtt.contains("00:00:01.000 --> 00:00:03.000\nCafé"), vtt)
        } else {
            // The server converts it; its answer is what the player fetches.
            assertTrue(playable.url.isNotEmpty())
        }
    }

    @Test fun `02 replacing a track finds the one that is there`() = runBlocking {
        requireServer()
        // What the add flow asks before uploading: is this name taken?
        val found = SubtitleLoader(api, cache).find(video("Holiday.mp4"))
        assertTrue(found.any { it.path == "$folder/Holiday.nl.srt" }, "the existing track is not seen: $found")
    }

    @Test fun `03 the app's rules give what the server's give`() = runBlocking {
        requireServer()
        val tiny = "1\n00:00:01,000 --> 00:00:02,000\nx\n".toByteArray()
        for (name in listOf("Holiday.srt", "Holiday.en.srt", "Holiday.nl.forced.srt", "Holiday.en.sdh.vtt",
            "Holiday 2.srt", "Holiday.hi.srt", "Holiday.nl.hi.srt")) put("$folder/$name", tiny)
        api.makeFolder("$folder/Subs")
        put("$folder/Subs/English.srt", tiny)
        api.makeFolder("$folder/Subs/Holiday")
        put("$folder/Subs/Holiday/German.srt", tiny)

        val holiday = video("Holiday.mp4")
        assumeTrue("this server has no subtitle route to compare with", hasRoute(holiday))
        val server = api.subtitles(holiday.path).map { it.copy(url = "") }
        val app = SidecarSubtitles.find(holiday) { api.list(it) }
        assertEquals(server, app)
        assertTrue(app.size >= 8, "the folder's tracks were not all found: $app")
    }
}
