package nl.tippie.cloudhub

import nl.tippie.cloudhub.ui.BackRules
import nl.tippie.cloudhub.ui.BackRules.Outcome
import org.junit.Test
import kotlin.test.assertEquals

/**
 * What Back does, from everywhere.
 *
 * The bug these exist for: the app had one back handler in it, the player's,
 * and only while fullscreen. Everywhere else Back fell through to the Activity
 * and finished it -- so three folders deep, or in Settings, or looking at a
 * photo, Back closed CloudHub. Under gesture navigation that press is a swipe
 * from the edge of the screen, which is easy to make by accident, so the app
 * seemed to quit at random.
 *
 * Every case below is a gesture someone will make on a phone, where getting it
 * wrong means losing your place.
 */
class BackRulesTest {

    private fun where(
        depth: Int = 1,
        onFiles: Boolean = true,
        selection: Int = 0,
        searching: Boolean = false,
        folder: String = "/",
    ) = BackRules.Where(depth, onFiles, selection, searching, folder)

    /* ---- the app closing when it should not ------------------------------- */

    @Test
    fun `back in a subfolder goes up, not out`() {
        assertEquals(Outcome.GO_UP_A_FOLDER, BackRules.next(where(folder = "/Photos/2026")))
    }

    @Test
    fun `back on a screen opened from another returns to it`() {
        assertEquals(Outcome.PREVIOUS_SCREEN, BackRules.next(where(depth = 2, onFiles = false)))
    }

    @Test
    fun `back inside a photo returns to the folder`() {
        // The viewer is a screen, so it is a pop -- not an exit, which is what
        // it used to be.
        assertEquals(Outcome.PREVIOUS_SCREEN, BackRules.next(where(depth = 2, onFiles = false)))
    }

    @Test
    fun `back with things selected clears the selection first`() {
        // Selecting fifteen files three folders deep and pressing Back must
        // not throw away both the selection and the folder.
        assertEquals(
            Outcome.CLEAR_SELECTION,
            BackRules.next(where(selection = 15, folder = "/Photos/2026")),
        )
    }

    @Test
    fun `back while searching clears the search before leaving the folder`() {
        assertEquals(Outcome.CLEAR_SEARCH, BackRules.next(where(searching = true, folder = "/Photos")))
    }

    @Test
    fun `each press undoes one thing`() {
        // Selection, then search, then folder, then screen: the order they
        // were done in, undone one press at a time.
        assertEquals(Outcome.CLEAR_SELECTION, BackRules.next(where(selection = 2, searching = true, folder = "/a")))
        assertEquals(Outcome.CLEAR_SEARCH, BackRules.next(where(searching = true, folder = "/a")))
        assertEquals(Outcome.GO_UP_A_FOLDER, BackRules.next(where(folder = "/a")))
        assertEquals(Outcome.LEAVE_THE_APP, BackRules.next(where()))
    }

    /* ---- the app closing when it should ----------------------------------- */

    @Test
    fun `back at the root of everything leaves the app`() {
        // The other half of the requirement: an app you cannot get out of is
        // worse than one that quits too easily.
        assertEquals(Outcome.LEAVE_THE_APP, BackRules.next(where(depth = 1, folder = "/")))
    }

    @Test
    fun `back on the sign-in screen leaves the app`() {
        assertEquals(Outcome.LEAVE_THE_APP, BackRules.next(where(depth = 1, onFiles = false)))
    }

    @Test
    fun `a selection somewhere other than the file list does not hold back`() {
        // The state belongs to the file list; a stale count must not trap Back
        // on a screen that has nothing to do with it.
        assertEquals(
            Outcome.PREVIOUS_SCREEN,
            BackRules.next(where(depth = 2, onFiles = false, selection = 3, searching = true, folder = "/a")),
        )
    }

    /* ---- walking up the tree ---------------------------------------------- */

    @Test
    fun `the parent folder is one level up`() {
        assertEquals("/Photos", BackRules.parentOf("/Photos/2026"))
        assertEquals("/", BackRules.parentOf("/Photos"))
    }

    @Test
    fun `the root has no parent`() {
        // Walking off the top would turn Back into a no-op the user cannot
        // escape from.
        assertEquals("/", BackRules.parentOf("/"))
        assertEquals("/", BackRules.parentOf(""))
    }

    @Test
    fun `a trailing slash does not cost a press`() {
        assertEquals("/Photos", BackRules.parentOf("/Photos/2026/"))
    }

    @Test
    fun `folder names with spaces and dots are ordinary`() {
        assertEquals("/My Files", BackRules.parentOf("/My Files/v1.2 final"))
    }

    /* ---- the stack --------------------------------------------------------- */

    @Test
    fun `opening a screen remembers where it came from`() {
        val stack = BackRules.pushed(listOf("files", "settings"), "storage")
        assertEquals(listOf("files", "settings", "storage"), stack)
        // ...and back returns to Settings, not to the file list.
        assertEquals(listOf("files", "settings"), BackRules.popped(stack))
    }

    @Test
    fun `tapping the same thing twice does not need two presses to undo`() {
        val stack = BackRules.pushed(listOf("files", "settings"), "settings")
        assertEquals(listOf("files", "settings"), stack)
    }

    /* ---- state that outlives its screen ------------------------------------
     *
     * A screen keeps its state while you are away on another one. A popped
     * screen, though, is over: the next visit opens with its own arguments,
     * and state kept from last time outranks them. That is what made the photo
     * viewer show the previous photo -- it remembers which page is on show, so
     * opening the next photo restored the page from the visit before.
     */

    @Test
    fun `a popped screen's state is not kept for the next visit`() {
        // Opened a photo, went back, opened another: the viewer must not start
        // on the page the last visit ended on.
        assertEquals(setOf("images"), BackRules.forgotten(setOf("files", "images"), setOf("files")))
    }

    @Test
    fun `a screen you are still on keeps its state`() {
        // Watching a video: the file list underneath must not be forgotten, or
        // coming back lands at the top again.
        assertEquals(emptySet(), BackRules.forgotten(setOf("files", "play:/a.mp4"), setOf("files", "play:/a.mp4")))
    }

    @Test
    fun `the file list is never forgotten`() {
        // It is the root and is never popped, which is what lets where you
        // were in it survive a video.
        assertEquals(emptySet(), BackRules.forgotten(setOf("files"), setOf("files")))
    }

    @Test
    fun `several screens closed at once are all forgotten`() {
        assertEquals(
            setOf("settings", "storage"),
            BackRules.forgotten(setOf("files", "settings", "storage"), setOf("files")),
        )
    }

    @Test
    fun `the first screen is never popped`() {
        // Leaving the app is Android's to do, not something to fake by
        // emptying the stack and rendering nothing.
        assertEquals(listOf("files"), BackRules.popped(listOf("files")))
        assertEquals(emptyList<String>(), BackRules.popped(emptyList()))
    }
}
