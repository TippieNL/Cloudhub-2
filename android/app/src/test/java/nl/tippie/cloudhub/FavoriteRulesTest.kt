package nl.tippie.cloudhub

import nl.tippie.cloudhub.net.FileEntry
import nl.tippie.cloudhub.ui.FavoriteRules
import nl.tippie.cloudhub.ui.FavoriteRules.Filter
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the Favorites screen shows, and what a star does to it.
 *
 * The filters have to agree with the web app's -- a photo counted as "Other"
 * here and "Photos" there would read as two different collections -- and a
 * star has to land where the server will list it, or the list reshuffles
 * itself the moment it is next fetched.
 */
class FavoriteRulesTest {

    private fun file(path: String) = FileEntry(name = path.substringAfterLast('/'), path = path, isDirectory = false)

    private val photo = file("/Holiday/beach.jpg")
    private val raw = file("/Holiday/sky.avif")
    private val film = file("/Holiday/waves.mp4")
    private val song = file("/Music/song.mp3")
    private val notes = file("/Docs/notes.txt")
    private val all = listOf(photo, raw, film, song, notes)

    @Test fun `photos are images and videos are videos`() {
        assertEquals(listOf(photo, raw), all.filter(Filter.PHOTOS::matches))
        assertEquals(listOf(film), all.filter(Filter.VIDEOS::matches))
    }

    @Test fun `everything else is other, sound included, as on the web`() {
        assertEquals(listOf(song, notes), all.filter(Filter.OTHER::matches))
    }

    @Test fun `every favorite is in exactly one of the three, and all of them in All`() {
        val counts = FavoriteRules.counts(all)
        assertEquals(all.size, counts[Filter.ALL])
        assertEquals(all.size, counts.getValue(Filter.PHOTOS) + counts.getValue(Filter.VIDEOS) + counts.getValue(Filter.OTHER))
    }

    @Test fun `a filter with nothing left in it falls back to All`() {
        val noFilms = listOf(photo, notes)
        assertEquals(Filter.ALL, FavoriteRules.effective(Filter.VIDEOS, noFilms))
        assertEquals(Filter.PHOTOS, FavoriteRules.effective(Filter.PHOTOS, noFilms))
        assertEquals(Filter.ALL, FavoriteRules.effective(Filter.PHOTOS, emptyList()))
    }

    @Test fun `a new star goes to the top, where the server lists it`() {
        val after = FavoriteRules.afterToggle(listOf(film, notes), photo, starred = true)
        assertEquals(listOf(photo, film, notes), after)
    }

    @Test fun `starring what is already starred does not list it twice`() {
        val after = FavoriteRules.afterToggle(listOf(film, photo), photo, starred = true)
        assertEquals(listOf(photo, film), after)
    }

    @Test fun `unstarring takes it out and leaves the rest in order`() {
        val after = FavoriteRules.afterToggle(listOf(photo, film, notes), film, starred = false)
        assertEquals(listOf(photo, notes), after)
        assertFalse(after.any { it.path == film.path })
    }

    @Test fun `unstarring something not listed changes nothing`() {
        val before = listOf(photo, notes)
        assertTrue(FavoriteRules.afterToggle(before, song, starred = false) == before)
    }
}
