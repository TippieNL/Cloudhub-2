package nl.tippie.cloudhub

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.CloudHubClient
import nl.tippie.cloudhub.net.FileEntry
import nl.tippie.cloudhub.net.InMemoryCookieStore
import nl.tippie.cloudhub.net.PinnedCertificates
import nl.tippie.cloudhub.ui.mediaItemFor
import nl.tippie.cloudhub.ui.playerDataSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A subtitle beside a video reaches the player as a text track it can show.
 *
 * A real ExoPlayer, built the way PlayerScreen builds it, preparing a real
 * video and the subtitle beside it from a live server -- for the files people
 * actually have: Windows line endings, byte-order marks, UTF-16, Latin-1,
 * formatting tags. The player loads the video first and adds the tracks when
 * they arrive, so that path is exercised too. Skipped without
 * CLOUDHUB_TEST_URL, like the other live tests.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SubtitlePlaybackTest {

    companion object {
        private val baseUrl: String? = System.getenv("CLOUDHUB_TEST_URL")?.takeIf { it.isNotBlank() }
        private lateinit var client: CloudHubClient
        private lateinit var api: CloudHubApi
        private val folder = "/_subplay_${System.currentTimeMillis()}"

        @BeforeClass @JvmStatic
        fun signIn() {
            val url = baseUrl ?: return
            client = CloudHubClient(InMemoryCookieStore(), object : PinnedCertificates {
                override fun isPinned(fingerprint: String) = false
            })
            api = CloudHubApi(url, client)
            runBlocking {
                api.login(System.getenv("CLOUDHUB_TEST_USER") ?: "admin", System.getenv("CLOUDHUB_TEST_PASS") ?: "smoke-test-pass-123")
                api.makeFolder(folder)
            }
        }

        @AfterClass @JvmStatic
        fun tidy() {
            if (baseUrl != null) runBlocking { runCatching { api.delete(folder) } }
        }

        private val VIDEO by lazy { SubtitlePlaybackTest::class.java.classLoader!!.getResourceAsStream("sample.mp4")!!.readBytes() }
    }

    private fun upload(name: String, bytes: ByteArray) = runBlocking {
        val id = api.uploadInit("subplay" + System.nanoTime(), folder, name, bytes.size.toLong()).id
        api.uploadChunk(id, 0, bytes.toRequestBody("application/octet-stream".toMediaType()))
        api.uploadComplete(id)
    }

    /** Upload a video and its subtitle; play them as PlayerScreen does; return what the player saw. */
    private fun play(stem: String, subtitleName: String, subtitle: ByteArray, swappedIn: Boolean): Pair<List<String>, Throwable?> {
        assumeTrue("CLOUDHUB_TEST_URL is not set", baseUrl != null)
        upload("$stem.mp4", VIDEO)
        upload(subtitleName, subtitle)
        val entry: FileEntry = runBlocking { api.list(folder).first { it.name == "$stem.mp4" } }
        val tracks = runBlocking { api.subtitles(entry.path) }
        assertTrue(tracks.isNotEmpty(), "the server lists $subtitleName")

        val context = ApplicationProvider.getApplicationContext<Context>()
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(playerDataSource(context, client, entry)))
            .build()
        return try {
            if (swappedIn) {
                // As the screen does: the film first, the tracks when the lookup answers.
                player.setMediaItem(mediaItemFor(api, entry, emptyList()))
                player.prepare()
                TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_READY)
                player.setMediaItem(mediaItemFor(api, entry, tracks), player.currentPosition)
                player.prepare()
            } else {
                player.setMediaItem(mediaItemFor(api, entry, tracks))
                player.prepare()
            }
            val error = runCatching { TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_READY) }.exceptionOrNull()
            val text = player.currentTracks.groups
                .filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
                .map { it.mediaTrackGroup.getFormat(0).language ?: "" }
            text to (error ?: player.playerError)
        } finally {
            player.release()
        }
    }

    private fun assertPlayable(stem: String, subtitleName: String, subtitle: ByteArray) {
        for (swapped in listOf(false, true)) {
            val (text, error) = play("$stem-${if (swapped) "late" else "first"}", subtitleName.replace(stem, "$stem-${if (swapped) "late" else "first"}"), subtitle, swapped)
            assertNull(error, "$subtitleName (${if (swapped) "added after start" else "from the start"}) failed: $error")
            assertEquals(1, text.size, "$subtitleName gives one text track the player can show")
        }
    }

    private val cue = "1\n00:00:01,000 --> 00:00:04,000\nHallo daar\n\n2\n00:00:04,500 --> 00:00:05,500\nTot ziens\n"

    @Test fun `a plain srt`() = assertPlayable("Plain", "Plain.nl.srt", cue.toByteArray())

    @Test fun `windows line endings and a byte-order mark`() =
        assertPlayable("Crlf", "Crlf.nl.srt", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + cue.replace("\n", "\r\n").toByteArray())

    @Test fun `utf-16 as some tools write it`() =
        assertPlayable("Utf16", "Utf16.nl.srt", byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + cue.replace("\n", "\r\n").toByteArray(Charsets.UTF_16LE))

    @Test fun `latin-1 with accents`() =
        assertPlayable("Latin", "Latin.nl.srt", cue.replace("Hallo daar", "Café à côté").toByteArray(Charsets.ISO_8859_1))

    @Test fun `tags, positioning and no trailing newline`() =
        assertPlayable("Tags", "Tags.nl.srt",
            "1\n00:00:01,000 --> 00:00:04,000\n{\\an8}<i>Hallo</i> <font color=\"#ff0\">daar</font>\n\n2\n00:00:04,500 --> 00:00:05,500\n<b>Tot</b> ziens".toByteArray())

    @Test fun `a vtt with a header, a style block and a byte-order mark`() =
        assertPlayable("Vtt", "Vtt.nl.vtt", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "WEBVTT - Film\r\n\r\nSTYLE\r\n::cue { color: yellow }\r\n\r\n00:01.000 --> 00:04.000\r\nHallo daar\r\n".toByteArray())

    @Test fun `a film whose name has a dot and a dash in it`() =
        assertPlayable("Film - Site.com", "Film - Site.com.nl.srt", cue.toByteArray())
}
