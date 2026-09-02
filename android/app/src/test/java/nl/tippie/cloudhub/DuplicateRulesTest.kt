package nl.tippie.cloudhub

import nl.tippie.cloudhub.net.DuplicateFile
import nl.tippie.cloudhub.net.DuplicateGroup
import nl.tippie.cloudhub.ui.DuplicateRules
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the duplicates screen offers to delete.
 *
 * This is a screen whose only action is deleting photos, so the rule that
 * matters most is the one that says a group always keeps a copy. A duplicate
 * finder that can empty a group has not deleted a duplicate; it has deleted
 * the photo.
 */
class DuplicateRulesTest {

    private fun group(vararg paths: String, keep: String = paths.first(), bytes: Long = 1000) =
        DuplicateGroup(
            hash = paths.joinToString("|"),
            bytes = bytes,
            wastedBytes = bytes * (paths.size - 1),
            copies = paths.size,
            keep = keep,
            files = paths.map { DuplicateFile(path = it, name = it.substringAfterLast('/'), bytes = bytes) },
        )

    @Test
    fun `the extra copies are offered, and the kept one is not`() {
        val holiday = group("/Photos/a.jpg", "/Backup/a.jpg", "/Old/a.jpg", keep = "/Photos/a.jpg")
        assertEquals(listOf("/Backup/a.jpg", "/Old/a.jpg"), DuplicateRules.removable(holiday, null))
    }

    @Test
    fun `choosing a different copy to keep changes what is offered`() {
        val holiday = group("/Photos/a.jpg", "/Backup/a.jpg", keep = "/Photos/a.jpg")
        assertEquals(listOf("/Photos/a.jpg"), DuplicateRules.removable(holiday, "/Backup/a.jpg"))
    }

    @Test
    fun `a group of one has nothing to remove`() {
        assertEquals(emptyList(), DuplicateRules.removable(group("/Photos/only.jpg"), null))
    }

    @Test
    fun `a keeper that is not in the group does not cost the last copy`() {
        // Should not happen; must not delete everything if it does.
        val holiday = group("/Photos/a.jpg", "/Backup/a.jpg", keep = "/gone.jpg")
        val offered = DuplicateRules.removable(holiday, "/also-gone.jpg")
        assertEquals(1, offered.size)
        assertTrue(offered.size < holiday.files.size)
    }

    @Test
    fun `selecting every extra copy leaves one of each`() {
        val groups = listOf(
            group("/a1.jpg", "/a2.jpg", keep = "/a1.jpg"),
            group("/b1.mp4", "/b2.mp4", "/b3.mp4", keep = "/b1.mp4"),
        )
        val offered = DuplicateRules.removableIn(groups, emptyMap()).toSet()
        assertEquals(setOf("/a2.jpg", "/b2.mp4", "/b3.mp4"), offered)
        // The safety rule, checked against what the bulk action produces.
        assertFalse(DuplicateRules.wouldEmptyAGroup(groups, offered))
    }

    @Test
    fun `a hand-made selection that empties a group is caught`() {
        val groups = listOf(group("/a1.jpg", "/a2.jpg"))
        assertTrue(DuplicateRules.wouldEmptyAGroup(groups, setOf("/a1.jpg", "/a2.jpg")))
    }

    @Test
    fun `selecting nothing empties nothing`() {
        assertFalse(DuplicateRules.wouldEmptyAGroup(listOf(group("/a1.jpg", "/a2.jpg")), emptySet()))
    }

    @Test
    fun `what is reclaimed counts only what is selected`() {
        val groups = listOf(group("/a1.jpg", "/a2.jpg", bytes = 1500))
        assertEquals(1500, DuplicateRules.freedBy(groups, setOf("/a2.jpg")))
        assertEquals(0, DuplicateRules.freedBy(groups, emptySet()))
    }

    @Test
    fun `a file selected in another folder is not counted twice`() {
        // Paths are unique; a selection carrying something not in these groups
        // must not inflate the figure on the button.
        val groups = listOf(group("/a1.jpg", "/a2.jpg", bytes = 1000))
        assertEquals(1000, DuplicateRules.freedBy(groups, setOf("/a2.jpg", "/elsewhere.jpg")))
    }

    @Test
    fun `the summary says what was found`() {
        assertEquals("No duplicates found", DuplicateRules.summary(0, 0) { "$it B" })
        assertEquals("1 set of copies · 500 B to reclaim", DuplicateRules.summary(1, 500) { "$it B" })
        assertEquals("4 sets of copies · 2000 B to reclaim", DuplicateRules.summary(4, 2000) { "$it B" })
    }
}
