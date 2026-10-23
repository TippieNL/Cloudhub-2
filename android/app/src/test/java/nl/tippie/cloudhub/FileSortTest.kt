package nl.tippie.cloudhub

import nl.tippie.cloudhub.net.FileEntry
import nl.tippie.cloudhub.ui.FilesState
import nl.tippie.cloudhub.ui.FilesState.Sort
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * A folder under each of the Sort by options.
 *
 * Natural Number is new; Name, Newest and Largest are pinned here exactly as
 * they were before it, folders first under every one of them.
 */
class FileSortTest {

    private fun folder(name: String, size: Long, modified: String) =
        FileEntry(name = name, path = "/Holiday/$name", isDirectory = true, size = size, modified = modified)

    private fun file(name: String, size: Long, modified: String) =
        FileEntry(name = name, path = "/Holiday/$name", isDirectory = false, size = size, modified = modified)

    // Deliberately in no order at all, as a server may list them.
    private val listing = listOf(
        file("Image 10.jpg", 300, "2026-03-01T10:00:00+00:00"),
        folder("Folder 10", 5, "2026-01-05T10:00:00+00:00"),
        file("Image 2.jpg", 100, "2026-01-01T10:00:00+00:00"),
        folder("Folder 2", 50, "2026-03-05T10:00:00+00:00"),
        file("image 1.jpg", 200, "2026-02-01T10:00:00+00:00"),
        folder("folder 1", 500, "2026-02-05T10:00:00+00:00"),
    )

    private fun names(sort: Sort, state: FilesState = FilesState(entries = listing)) =
        state.copy(sort = sort).visible.map { it.name }

    @Test fun `Natural Number counts, folders first and files after, by the same rules`() {
        assertEquals(
            listOf("folder 1", "Folder 2", "Folder 10", "image 1.jpg", "Image 2.jpg", "Image 10.jpg"),
            names(Sort.NATURAL),
        )
    }

    @Test fun `Name is as it was, a character at a time`() {
        // "Folder 10" before "Folder 2": what Name has always done, and why
        // Natural Number is offered beside it.
        assertEquals(
            listOf("folder 1", "Folder 10", "Folder 2", "image 1.jpg", "Image 10.jpg", "Image 2.jpg"),
            names(Sort.NAME),
        )
    }

    @Test fun `Newest is as it was`() {
        assertEquals(
            listOf("Folder 2", "folder 1", "Folder 10", "Image 10.jpg", "image 1.jpg", "Image 2.jpg"),
            names(Sort.NEWEST),
        )
    }

    @Test fun `Largest is as it was`() {
        assertEquals(
            listOf("folder 1", "Folder 2", "Folder 10", "Image 10.jpg", "image 1.jpg", "Image 2.jpg"),
            names(Sort.LARGEST),
        )
    }

    @Test fun `Name is still what a new screen starts on`() {
        assertEquals(Sort.NAME, FilesState().sort)
    }

    @Test fun `the filter box narrows, and what is left is still counted in order`() {
        val filtered = FilesState(entries = listing, query = "image")
        assertEquals(listOf("image 1.jpg", "Image 2.jpg", "Image 10.jpg"), names(Sort.NATURAL, filtered))
    }

    @Test fun `search results are sorted by it as well`() {
        val results = listOf(
            file("Scan 10.pdf", 1, "2026-01-01T00:00:00+00:00"),
            file("Scan 9.pdf", 1, "2026-01-01T00:00:00+00:00"),
            folder("Scans 2", 0, "2026-01-01T00:00:00+00:00"),
        )
        val searching = FilesState(entries = listing, searchResults = results)
        assertEquals(listOf("Scans 2", "Scan 9.pdf", "Scan 10.pdf"), names(Sort.NATURAL, searching))
    }

    @Test fun `sorting reorders what is shown and leaves the listing alone`() {
        val state = FilesState(entries = listing, sort = Sort.NATURAL)
        state.visible
        assertEquals(listing, state.entries)
    }

    @Test fun `a state is sorted once, however often it is read`() {
        val state = FilesState(entries = listing, sort = Sort.NATURAL)
        assertSame(state.visible, state.visible)
        // ...and a changed state is sorted afresh, not handed the old order.
        val resorted = state.copy(sort = Sort.LARGEST)
        assertNotSame(state.visible, resorted.visible)
        assertEquals("folder 1", resorted.visible.first().name)
        assertEquals("Image 10.jpg", resorted.visible[3].name)
    }
}
