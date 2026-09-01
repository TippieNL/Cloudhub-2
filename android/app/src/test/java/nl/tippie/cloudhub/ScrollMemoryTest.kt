package nl.tippie.cloudhub

import nl.tippie.cloudhub.ui.ScrollMemory
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coming back to where you were.
 *
 * Scrolling to the fortieth clip in a folder, watching it, and pressing Back
 * used to land you at the first: a list's scroll position belongs to the
 * composable that owns it, and opening a video takes that composable out of
 * the composition. One position was also shared by every folder, so it could
 * only ever describe the folder on screen.
 */
class ScrollMemoryTest {

    @Test
    fun `a folder is remembered where it was left`() {
        val memory = ScrollMemory()
        memory.remember("/Videos", index = 42, offset = 17)
        assertEquals(ScrollMemory.Place(42, 17), memory.placeOf("/Videos"))
    }

    @Test
    fun `each folder keeps its own place`() {
        // The bug in the other direction: one position for the whole app means
        // walking up a folder lands wherever the child was scrolled to.
        val memory = ScrollMemory()
        memory.remember("/Videos", 42, 0)
        memory.remember("/Videos/2026", 3, 0)
        assertEquals(42, memory.placeOf("/Videos")?.index)
        assertEquals(3, memory.placeOf("/Videos/2026")?.index)
    }

    @Test
    fun `a folder never visited starts at the top`() {
        assertNull(ScrollMemory().placeOf("/Somewhere"))
    }

    @Test
    fun `the newest position for a folder is the one kept`() {
        val memory = ScrollMemory()
        memory.remember("/Videos", 10, 0)
        memory.remember("/Videos", 80, 5)
        assertEquals(ScrollMemory.Place(80, 5), memory.placeOf("/Videos"))
    }

    @Test
    fun `a browse cannot remember folders without limit`() {
        val memory = ScrollMemory()
        repeat(ScrollMemory.REMEMBERED_FOLDERS + 20) { memory.remember("/folder-$it", it, 0) }
        assertNull(memory.placeOf("/folder-0"), "the oldest folder should have been dropped")
        // ...and the folder just left is the one most likely to be returned to.
        val newest = ScrollMemory.REMEMBERED_FOLDERS + 19
        assertEquals(newest, memory.placeOf("/folder-$newest")?.index)
    }

    @Test
    fun `a nonsense position is not stored as one`() {
        val memory = ScrollMemory()
        memory.remember("/Videos", index = -1, offset = -5)
        assertEquals(ScrollMemory.Place(0, 0), memory.placeOf("/Videos"))
    }

    @Test
    fun `a folder can be forgotten`() {
        val memory = ScrollMemory()
        memory.remember("/Videos", 5, 0)
        memory.forget("/Videos")
        assertNull(memory.placeOf("/Videos"))
    }

    @Test
    fun `what is remembered survives being written down and read back`() {
        // The Saver flattens this; a position that cannot round-trip is a
        // position lost the first time the screen is rebuilt.
        val memory = ScrollMemory()
        memory.remember("/Videos", 42, 17)
        memory.remember("/Photos/My Holiday", 7, 3)

        val restored = ScrollMemory(memory.snapshot())
        assertEquals(ScrollMemory.Place(42, 17), restored.placeOf("/Videos"))
        assertEquals(ScrollMemory.Place(7, 3), restored.placeOf("/Photos/My Holiday"))
    }

    /* ---- coming back to the file you were on ------------------------------ */

    @Test
    fun `the file just closed is found in the list`() {
        val paths = listOf("/a.jpg", "/b.jpg", "/c.jpg")
        assertEquals(2, ScrollMemory.indexOfPath(paths, "/c.jpg"))
    }

    @Test
    fun `a file no longer in the list is not looked for`() {
        // Deleted while it was open, or the folder was refiltered underneath.
        assertEquals(-1, ScrollMemory.indexOfPath(listOf("/a.jpg"), "/gone.jpg"))
        assertEquals(-1, ScrollMemory.indexOfPath(listOf("/a.jpg"), null))
    }

    @Test
    fun `a photo swiped past the screenful is scrolled to`() {
        // Opened the third photo, swiped to the fortieth, pressed Back.
        assertTrue(ScrollMemory.shouldReveal(target = 40, firstVisible = 0, lastVisible = 8))
    }

    @Test
    fun `a photo already on screen is left where it is`() {
        // Scrolling it up to the top edge would be a jump for no reason: the
        // eye is already on it.
        assertFalse(ScrollMemory.shouldReveal(target = 4, firstVisible = 0, lastVisible = 8))
        assertFalse(ScrollMemory.shouldReveal(target = 0, firstVisible = 0, lastVisible = 8))
        assertFalse(ScrollMemory.shouldReveal(target = 8, firstVisible = 0, lastVisible = 8))
    }

    @Test
    fun `a photo above the screenful is scrolled to as well`() {
        assertTrue(ScrollMemory.shouldReveal(target = 2, firstVisible = 20, lastVisible = 28))
    }

    @Test
    fun `a file that is not in the list is never scrolled to`() {
        assertFalse(ScrollMemory.shouldReveal(target = -1, firstVisible = 0, lastVisible = 8))
    }
}
