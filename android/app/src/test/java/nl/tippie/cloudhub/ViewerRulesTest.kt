package nl.tippie.cloudhub

import nl.tippie.cloudhub.net.FileEntry
import nl.tippie.cloudhub.ui.ViewerRules
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The row of photos in the viewer, and what an edit does to it.
 *
 * Deleting the photo on show has to land on a neighbour rather than off the
 * end of the pager, Undo has to put it back where it was, and a rename has to
 * keep the photo -- at its new path -- unless it is no longer a photo at all.
 */
class ViewerRulesTest {

    private fun photo(path: String, extension: String? = null) =
        FileEntry(name = path.substringAfterLast('/'), path = path, isDirectory = false, extension = extension)

    private val a = photo("/Holiday/a.jpg")
    private val b = photo("/Holiday/b.jpg")
    private val c = photo("/Holiday/c.jpg")
    private val row = listOf(a, b, c)

    /* ---- the counter ------------------------------------------------------ */

    @Test fun `the counter says where you are, counting from one`() {
        assertEquals("3 of 12", ViewerRules.counter(2, 12))
        assertEquals("1 of 2", ViewerRules.counter(0, 2))
    }

    @Test fun `a single photo has no counter`() {
        // "1 of 1" says nothing a glance at the screen does not.
        assertNull(ViewerRules.counter(0, 1))
        assertNull(ViewerRules.counter(0, 0))
    }

    @Test fun `the counter never claims a photo that is not there`() {
        // The pager can briefly sit past the end while a delete lands.
        assertEquals("3 of 3", ViewerRules.counter(5, 3))
    }

    /* ---- deleting --------------------------------------------------------- */

    @Test fun `deleting a photo shows the one that moved into its place`() {
        val left = ViewerRules.without(row, b.path)
        assertEquals(listOf(a, c), left)
        assertEquals(1, ViewerRules.pageAfterRemoval(1, left.size))
        assertEquals(c, left[ViewerRules.pageAfterRemoval(1, left.size)])
    }

    @Test fun `deleting the last photo shows the new last one`() {
        val left = ViewerRules.without(row, c.path)
        assertEquals(b, left[ViewerRules.pageAfterRemoval(2, left.size)])
    }

    @Test fun `deleting the only photo leaves nowhere to be`() {
        assertEquals(emptyList(), ViewerRules.without(listOf(a), a.path))
        assertEquals(0, ViewerRules.pageAfterRemoval(0, 0))
    }

    @Test fun `undo puts the photo back where it was`() {
        val left = ViewerRules.without(row, b.path)
        assertEquals(row, ViewerRules.restored(left, b, 1))
    }

    @Test fun `undo after the row has shrunk still puts it back, at the end`() {
        assertEquals(listOf(a, c), ViewerRules.restored(listOf(a), c, 7))
    }

    @Test fun `undo never shows a photo twice`() {
        assertEquals(row, ViewerRules.restored(row, b, 0))
    }

    /* ---- renaming --------------------------------------------------------- */

    @Test fun `a renamed photo keeps its place, at its new path`() {
        val renamed = ViewerRules.renamed(row, b, "/Holiday/beach.jpg", "beach.jpg")
        assertEquals(listOf("/Holiday/a.jpg", "/Holiday/beach.jpg", "/Holiday/c.jpg"), renamed.map { it.path })
        assertEquals("beach.jpg", renamed[1].name)
    }

    @Test fun `the name the server chose is the one used`() {
        // Asked for a.jpg, which is taken: the server says it used "a (2).jpg".
        val renamed = ViewerRules.renamed(row, b, "/Holiday/a (2).jpg", "a (2).jpg")
        assertEquals("/Holiday/a (2).jpg", renamed[1].path)
    }

    @Test fun `the type follows the new name, not the old extension`() {
        // The listing sends ".jpg" with the file; a rename to .png must not keep it.
        val listed = photo("/Holiday/b.jpg", extension = ".jpg")
        val renamed = ViewerRules.renamed(listOf(listed), listed, "/Holiday/b.png", "b.png")
        assertEquals("png", renamed.single().ext)
    }

    @Test fun `renamed into something that is not a photo, it leaves the viewer`() {
        val renamed = ViewerRules.renamed(row, b, "/Holiday/b.txt", "b.txt")
        assertEquals(listOf(a, c), renamed)
    }
}
