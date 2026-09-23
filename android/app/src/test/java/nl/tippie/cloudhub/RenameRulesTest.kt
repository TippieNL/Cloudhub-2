package nl.tippie.cloudhub

import nl.tippie.cloudhub.ui.FilesViewModel
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the rename prompt's answer is sent at all.
 *
 * The prompt opens filled in with the current name. Confirming it unchanged
 * used to send the rename anyway, and a server that never overwrites on rename
 * answered by moving the file to "name (2)".
 */
class RenameRulesTest {

    @Test
    fun `confirming the prompt unchanged is not a rename`() {
        assertFalse(FilesViewModel.isRename("holiday.jpg", "holiday.jpg"))
    }

    @Test
    fun `an empty answer is not a rename`() {
        assertFalse(FilesViewModel.isRename("holiday.jpg", ""))
        assertFalse(FilesViewModel.isRename("holiday.jpg", "   "))
    }

    @Test
    fun `a change of case is a rename`() {
        // Case-insensitive storage treats these as one file, and the server
        // renames rather than inventing "Holiday (2).jpg".
        assertTrue(FilesViewModel.isRename("holiday.jpg", "Holiday.jpg"))
    }

    @Test
    fun `a new name is a rename`() {
        assertTrue(FilesViewModel.isRename("holiday.jpg", "beach.jpg"))
    }
}
