package nl.tippie.cloudhub

import nl.tippie.cloudhub.data.DownloadRules
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Saving a download on the versions MediaStore cannot.
 *
 * Android 9 and older write the file into Downloads themselves, which needs
 * the storage permission -- without it every download there failed -- and
 * means picking the name here: nothing else stops a second IMG_0001.jpg
 * replacing the first without a word.
 */
class DownloadRulesTest {

    @Test fun `only Android 9 and older need the storage permission`() {
        for (sdk in 24..28) assertTrue(DownloadRules.needsStoragePermission(sdk), "API $sdk")
        for (sdk in listOf(29, 30, 33, 34, 35)) assertFalse(DownloadRules.needsStoragePermission(sdk), "API $sdk")
    }

    private fun free(name: String, vararg taken: String) = DownloadRules.freeName(name) { it in taken }

    @Test fun `a free name is kept as it is`() {
        assertEquals("IMG_0001.jpg", free("IMG_0001.jpg"))
    }

    @Test fun `a taken name gets a number before the extension, as MediaStore does`() {
        assertEquals("IMG_0001 (1).jpg", free("IMG_0001.jpg", "IMG_0001.jpg"))
        assertEquals("IMG_0001 (2).jpg", free("IMG_0001.jpg", "IMG_0001.jpg", "IMG_0001 (1).jpg"))
    }

    @Test fun `only the last extension is kept apart`() {
        assertEquals("backup.tar (1).gz", free("backup.tar.gz", "backup.tar.gz"))
    }

    @Test fun `a name with no extension, or a hidden file's, is numbered at the end`() {
        assertEquals("README (1)", free("README", "README"))
        assertEquals(".nomedia (1)", free(".nomedia", ".nomedia"))
    }

    @Test fun `each name is asked about once, and the first free one is chosen`() {
        // The real check is File.createNewFile(), which creates what it finds
        // free -- asked twice about one name, it would call it taken the
        // second time. So each candidate is asked about once, in order.
        val existing = mutableSetOf("a.jpg", "a (1).jpg")
        val asked = mutableListOf<String>()
        val chosen = DownloadRules.freeName("a.jpg") { name -> asked += name; !existing.add(name) }
        assertEquals("a (2).jpg", chosen)
        assertEquals(listOf("a.jpg", "a (1).jpg", "a (2).jpg"), asked)
    }

    @Test fun `a folder full of copies still saves the file under a new name`() {
        val chosen = DownloadRules.freeName("a.jpg") { true }
        assertNotEquals("a.jpg", chosen)
        assertTrue(chosen.startsWith("a (") && chosen.endsWith(").jpg"), chosen)
    }
}
