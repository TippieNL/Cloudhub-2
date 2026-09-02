package nl.tippie.cloudhub

import nl.tippie.cloudhub.net.DuplicateFile
import nl.tippie.cloudhub.net.DuplicateGroup
import nl.tippie.cloudhub.net.DuplicateScan
import nl.tippie.cloudhub.ui.DuplicateRules
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the duplicates screen offers to delete.
 *
 * This is a screen whose only action is deleting photos, so the rule that
 * matters most is the one that says a group always keeps a copy. A duplicate
 * finder that can empty a group has not deleted a duplicate; it has deleted
 * the photo.
 *
 * The server sends a path, a size and a modification time per copy and nothing
 * else -- no name, no folder, no suggestion of which to keep -- so the rest of
 * these cover the answers this side now has to work out for itself.
 */
class DuplicateRulesTest {

    private fun group(vararg files: Pair<String, Long>, bytes: Long = 1000) =
        DuplicateGroup(
            bytes = bytes,
            count = files.size,
            reclaimable = bytes * (files.size - 1),
            files = files.map { (path, mtime) -> DuplicateFile(path = path, bytes = bytes, mtime = mtime) },
        )

    private fun scan(vararg groups: DuplicateGroup, done: Boolean = true, toHash: Int = 0, hashed: Int = 0) =
        DuplicateScan(
            done = done, toHash = toHash, hashed = hashed, groups = groups.toList(),
            duplicateFiles = groups.sumOf { it.count - 1 },
            reclaimable = groups.sumOf { it.reclaimable },
        )

    /* ---- which copy goes ---------------------------------------------- */

    @Test
    fun `the extra copies are offered, and the kept one is not`() {
        val holiday = group("/Photos/a.jpg" to 100, "/Backup/a.jpg" to 200, "/Old/a.jpg" to 300)
        assertEquals(listOf("/Backup/a.jpg", "/Old/a.jpg"), DuplicateRules.removable(holiday, null))
    }

    @Test
    fun `the oldest copy is the one kept by default`() {
        // The original is the one that was there first; the copies came later.
        val holiday = group("/Backup/a.jpg" to 900, "/Photos/a.jpg" to 100, "/Old/a.jpg" to 500)
        assertEquals("/Photos/a.jpg", DuplicateRules.keeperFor(holiday, null))
    }

    @Test
    fun `copies written in the same second still get a stable answer`() {
        // A folder copied wholesale gives every file the same mtime, and a
        // suggestion that moved between scans would be impossible to trust.
        val holiday = group("/z/a.jpg" to 500, "/a/a.jpg" to 500, "/m/a.jpg" to 500)
        assertEquals("/a/a.jpg", DuplicateRules.keeperFor(holiday, null))
        assertEquals("/a/a.jpg", DuplicateRules.keeperFor(holiday, null))
    }

    @Test
    fun `choosing a different copy to keep changes what is offered`() {
        val holiday = group("/Photos/a.jpg" to 100, "/Backup/a.jpg" to 200)
        assertEquals(listOf("/Photos/a.jpg"), DuplicateRules.removable(holiday, "/Backup/a.jpg"))
    }

    @Test
    fun `a group of one has nothing to remove`() {
        assertEquals(emptyList(), DuplicateRules.removable(group("/Photos/only.jpg" to 1), null))
    }

    @Test
    fun `a keeper that is not in the group does not cost the last copy`() {
        // Should not happen; must not delete everything if it does.
        val holiday = group("/Photos/a.jpg" to 100, "/Backup/a.jpg" to 200)
        val offered = DuplicateRules.removable(holiday, "/gone.jpg")
        assertEquals(1, offered.size)
        assertTrue(offered.size < holiday.files.size)
    }

    @Test
    fun `selecting every extra copy leaves one of each`() {
        val groups = listOf(
            group("/a1.jpg" to 100, "/a2.jpg" to 200),
            group("/b1.mp4" to 100, "/b2.mp4" to 200, "/b3.mp4" to 300),
        )
        val offered = DuplicateRules.removableIn(groups, emptyMap()).toSet()
        assertEquals(setOf("/a2.jpg", "/b2.mp4", "/b3.mp4"), offered)
        // The safety rule, checked against what the bulk action produces.
        assertFalse(DuplicateRules.wouldEmptyAGroup(groups, offered))
    }

    @Test
    fun `a hand-made selection that empties a group is caught`() {
        val groups = listOf(group("/a1.jpg" to 100, "/a2.jpg" to 200))
        assertTrue(DuplicateRules.wouldEmptyAGroup(groups, setOf("/a1.jpg", "/a2.jpg")))
    }

    @Test
    fun `selecting nothing empties nothing`() {
        assertFalse(DuplicateRules.wouldEmptyAGroup(listOf(group("/a1.jpg" to 1, "/a2.jpg" to 2)), emptySet()))
    }

    @Test
    fun `what is reclaimed counts only what is selected`() {
        val groups = listOf(group("/a1.jpg" to 1, "/a2.jpg" to 2, bytes = 1500))
        assertEquals(1500, DuplicateRules.freedBy(groups, setOf("/a2.jpg")))
        assertEquals(0, DuplicateRules.freedBy(groups, emptySet()))
    }

    @Test
    fun `a file selected in another folder is not counted twice`() {
        val groups = listOf(group("/a1.jpg" to 1, "/a2.jpg" to 2, bytes = 1000))
        assertEquals(1000, DuplicateRules.freedBy(groups, setOf("/a2.jpg", "/elsewhere.jpg")))
    }

    /* ---- what the server does not send -------------------------------- */

    @Test
    fun `the name and folder come from the path`() {
        assertEquals("holiday.jpg", DuplicateRules.nameOf("/Photos/2024/holiday.jpg"))
        assertEquals("/Photos/2024", DuplicateRules.folderOf("/Photos/2024/holiday.jpg"))
        // A file in the root has a folder too, and it is not the empty string.
        assertEquals("clip.mp4", DuplicateRules.nameOf("/clip.mp4"))
        assertEquals("/", DuplicateRules.folderOf("/clip.mp4"))
    }

    @Test
    fun `groups of the same size are still told apart`() {
        // There is no hash in the reply and sizes repeat, so identity has to
        // come from somewhere that cannot collide.
        val one = group("/a1.jpg" to 1, "/a2.jpg" to 2, bytes = 1000)
        val two = group("/b1.jpg" to 1, "/b2.jpg" to 2, bytes = 1000)
        assertEquals("/a1.jpg", DuplicateRules.groupId(one))
        assertTrue(DuplicateRules.groupId(one) != DuplicateRules.groupId(two))
    }

    /* ---- the poll loop ------------------------------------------------- */

    @Test
    fun `polling stops when the server says it is done`() {
        assertFalse(DuplicateRules.shouldContinue(scan(done = true), slicesSoFar = 1))
        assertTrue(DuplicateRules.shouldContinue(scan(done = false), slicesSoFar = 1))
    }

    @Test
    fun `polling gives up rather than asking for ever`() {
        // "Post again while done is false" is a loop with no bound in it.
        assertFalse(DuplicateRules.shouldContinue(scan(done = false), DuplicateRules.MAX_SLICES))
    }

    @Test
    fun `progress is not guessed before there is anything to divide by`() {
        // The first slice walks the tree and hashes nothing.
        assertNull(DuplicateRules.progress(scan(done = false, toHash = 0)))
        assertEquals(0.5f, DuplicateRules.progress(scan(done = false, toHash = 10, hashed = 5)))
        assertEquals(1f, DuplicateRules.progress(scan(done = true)))
    }

    @Test
    fun `a slice that overruns its own denominator still reads as full`() {
        // The server counts a digest per stage, so hashed can pass toHash.
        assertEquals(1f, DuplicateRules.progress(scan(done = false, toHash = 4, hashed = 9)))
    }

    /* ---- after deleting ------------------------------------------------ */

    @Test
    fun `deleting copies updates the result without another scan`() {
        val before = scan(
            group("/a1.jpg" to 1, "/a2.jpg" to 2, "/a3.jpg" to 3, bytes = 1000),
            group("/b1.mp4" to 1, "/b2.mp4" to 2, bytes = 500),
        )
        val after = DuplicateRules.without(before, setOf("/a3.jpg"))
        assertEquals(2, after.groups.size)
        assertEquals(listOf("/a1.jpg", "/a2.jpg"), after.groups[0].files.map { it.path })
        assertEquals(2, after.groups[0].count)
        assertEquals(1000, after.groups[0].reclaimable)
        assertEquals(1500, after.reclaimable)
        assertEquals(2, after.duplicateFiles)
    }

    @Test
    fun `a group with one copy left is no longer a duplicate`() {
        val before = scan(group("/a1.jpg" to 1, "/a2.jpg" to 2, bytes = 1000))
        val after = DuplicateRules.without(before, setOf("/a2.jpg"))
        assertEquals(emptyList(), after.groups)
        assertEquals(0, after.reclaimable)
        assertEquals(0, after.duplicateFiles)
    }

    @Test
    fun `deleting nothing changes nothing`() {
        val before = scan(group("/a1.jpg" to 1, "/a2.jpg" to 2))
        assertEquals(before, DuplicateRules.without(before, emptySet()))
    }

    /* ---- what the header says ------------------------------------------ */

    @Test
    fun `the summary says what was found`() {
        assertEquals("No duplicates found", DuplicateRules.summary(0, 0) { "$it B" })
        assertEquals("1 set of copies · 500 B to reclaim", DuplicateRules.summary(1, 500) { "$it B" })
        assertEquals("4 sets of copies · 2000 B to reclaim", DuplicateRules.summary(4, 2000) { "$it B" })
    }
}
