package nl.tippie.cloudhub

import kotlinx.coroutines.runBlocking
import nl.tippie.cloudhub.net.FileEntry
import nl.tippie.cloudhub.net.SubtitleTrack
import nl.tippie.cloudhub.ui.SidecarSubtitles
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which files belong to which video, found from folder listings on a server
 * that has no subtitle route of its own.
 *
 * The same shapes as the discovery half of tests/phase30_subtitles_test.php,
 * which holds SubtitleService::tracksFor() on Cloudhub-2's server: a `Subs/`
 * folder beside one film and beside two, a name that merely starts the same,
 * an empty file. ApiIntegrationTest compares the two on a live server.
 */
class SidecarSubtitlesTest {

    /** A store as folder listings: path to what is in it. */
    private class Tree {
        val folders = mutableMapOf<String, MutableList<FileEntry>>()

        fun file(path: String, size: Long = 60) = add(path, isDirectory = false, size = size)
        fun folder(path: String) = add(path, isDirectory = true, size = 0)

        private fun add(path: String, isDirectory: Boolean, size: Long) {
            val parent = path.substringBeforeLast('/').ifEmpty { "/" }
            if (parent != "/" && folders.values.none { list -> list.any { it.path == parent } }) folder(parent)
            folders.getOrPut(parent) { mutableListOf() } += FileEntry(path.substringAfterLast('/'), path, isDirectory, size)
            if (isDirectory) folders.getOrPut(path) { mutableListOf() }
        }

        fun entry(path: String) = folders.values.flatten().first { it.path == path }

        fun tracks(video: String): List<SubtitleTrack> = runBlocking {
            SidecarSubtitles.find(entry(video)) { folders[it].orEmpty() }
        }
    }

    private val film = Tree().apply {
        file("/Film/Holiday.mp4", size = 5_000_000)
        file("/Film/Holiday.srt")
        file("/Film/Holiday.en.srt")
        file("/Film/Holiday.nl.forced.srt")
        file("/Film/Holiday.en.sdh.vtt")
        // Starts with the same letters, but is a different film.
        file("/Film/Holiday 2.srt")
        // Not a subtitle, and a subtitle with nothing in it.
        file("/Film/Holiday.txt")
        file("/Film/Holiday.de.srt", size = 0)
    }

    private fun byLabel(tracks: List<SubtitleTrack>) = tracks.associateBy { it.label }

    @Test fun `a sidecar named after the video is found, with its language and flags`() {
        val tracks = byLabel(film.tracks("/Film/Holiday.mp4"))
        assertTrue("Subtitles" in tracks, "plain sidecar: ${tracks.keys}")
        assertEquals("en", tracks["English"]?.language)
        assertEquals("nl", tracks["Dutch (Forced)"]?.language)
        assertEquals(true, tracks["Dutch (Forced)"]?.forced)
        assertEquals(false, tracks["English"]?.forced)
        assertTrue("English (SDH)" in tracks, "the vtt is offered too: ${tracks.keys}")
    }

    @Test fun `what is not this video's subtitle is not claimed`() {
        val paths = film.tracks("/Film/Holiday.mp4").map { it.path }
        assertFalse("/Film/Holiday 2.srt" in paths, "another film's subtitles")
        assertFalse("/Film/Holiday.txt" in paths, "not a subtitle")
        assertFalse("/Film/Holiday.de.srt" in paths, "an empty file")
    }

    @Test fun `named languages come first, alphabetically, and the plain one last`() {
        assertEquals(
            listOf("Dutch (Forced)", "English", "English (SDH)", "Subtitles"),
            film.tracks("/Film/Holiday.mp4").map { it.label },
        )
    }

    @Test fun `every track has a stable id of its own`() {
        val tracks = film.tracks("/Film/Holiday.mp4")
        assertEquals(tracks.size, tracks.map { it.id }.toSet().size)
        assertEquals(tracks.map { it.id }, film.tracks("/Film/Holiday.mp4").map { it.id })
        // The server's: the first 16 hex digits of the path's SHA-256.
        assertEquals(16, tracks.first().id.length)
    }

    @Test fun `tracks found here carry no address until they are converted`() =
        assertTrue(film.tracks("/Film/Holiday.mp4").all { it.url.isEmpty() })

    @Test fun `a Subs folder beside a single film is claimed, and one named after it`() {
        film.file("/Film/Subs/English.srt")
        film.file("/Film/Subs/Holiday/Dutch.srt")
        val paths = film.tracks("/Film/Holiday.mp4").map { it.path }
        assertTrue("/Film/Subs/English.srt" in paths, "$paths")
        assertTrue("/Film/Subs/Holiday/Dutch.srt" in paths, "$paths")
    }

    @Test fun `two tracks that read the same are told apart by name`() {
        film.file("/Film/Subs/English.srt")
        val english = film.tracks("/Film/Holiday.mp4").filter { it.language == "en" && !it.label.contains("SDH") }
        assertEquals(setOf("English · Holiday.en.srt", "English · English.srt"), english.map { it.label }.toSet())
    }

    @Test fun `a shared Subs folder is not spread over every episode`() {
        val season = Tree().apply {
            file("/Season/One.mkv", size = 9_000_000)
            file("/Season/Two.mkv", size = 9_000_000)
            file("/Season/Subs/English.srt")
            file("/Season/One.en.srt")
        }
        assertEquals(listOf("/Season/One.en.srt"), season.tracks("/Season/One.mkv").map { it.path })
    }

    @Test fun `hi alone is Hindi, and after a language it is hearing impaired`() {
        film.file("/Film/Holiday.hi.srt")
        film.file("/Film/Holiday.nl.hi.srt")
        val tracks = film.tracks("/Film/Holiday.mp4").associateBy { it.name }
        assertEquals("hi", tracks["Holiday.hi.srt"]?.language)
        assertEquals("Hindi", tracks["Holiday.hi.srt"]?.label)
        assertEquals("Dutch (SDH)", tracks["Holiday.nl.hi.srt"]?.label)
    }

    @Test fun `a video with nothing beside it has no tracks`() {
        film.file("/Film/Alone.mp4", size = 5_000_000)
        assertEquals(emptyList(), film.tracks("/Film/Alone.mp4"))
    }

    @Test fun `a film whose name has dots and dashes in it`() {
        val tree = Tree().apply {
            file("/Films/Film - Site.com.mp4", size = 5_000_000)
            file("/Films/Film - Site.com.nl.srt")
        }
        assertEquals(listOf("Dutch"), tree.tracks("/Films/Film - Site.com.mp4").map { it.label })
    }

    @Test fun `the names the app gives its own uploads are found as what they say`() {
        // SubtitleRules.fileNameFor: Holiday.mp4 and "nl" make Holiday.nl.srt.
        val tree = Tree().apply {
            file("/V/Holiday.mp4", size = 5_000_000)
            file("/V/Holiday.nl.srt")
            file("/V/Holiday.srt")
        }
        assertEquals(listOf("Dutch" to "nl", "Subtitles" to ""), tree.tracks("/V/Holiday.mp4").map { it.label to it.language })
    }

    @Test fun `a file over the cap is not offered`() {
        val tree = Tree().apply {
            file("/V/Big.mp4", size = 5_000_000)
            file("/V/Big.en.srt", size = 4_194_305)
        }
        assertEquals(emptyList(), tree.tracks("/V/Big.mp4"))
    }

    @Test fun `a folder that cannot be listed has nothing in it`() = runBlocking {
        val video = FileEntry("Holiday.mp4", "/Film/Holiday.mp4", false, 5_000_000)
        assertEquals(emptyList(), SidecarSubtitles.find(video) { throw java.io.IOException("offline") })
    }
}
