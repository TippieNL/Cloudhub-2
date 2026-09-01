package nl.tippie.cloudhub.ui

/**
 * What Back means, wherever you are in the app.
 *
 * The app had one back handler in it -- the player's, and only while
 * fullscreen. Everything else let Back fall through to the Activity, which
 * finishes: three folders deep, in Settings, or looking at a photo, a Back
 * press closed CloudHub outright. With gesture navigation that press is a
 * swipe in from the edge of the screen, which is easy to do by accident and
 * indistinguishable from a swipe meant for the app, so the app appeared to
 * quit at random.
 *
 * The rules live here, apart from the composition, because they are the part
 * worth testing: on a device this is one gesture that either does the right
 * thing or loses your place.
 */
object BackRules {

    enum class Outcome {
        /** Undo a multi-select before anything else: it is the most recent thing done. */
        CLEAR_SELECTION,

        /** Then a search, which is a filter over the folder rather than a place. */
        CLEAR_SEARCH,

        /** Then walk up the folder tree, one level per press. */
        GO_UP_A_FOLDER,

        /** Then leave this screen for the one it was opened from. */
        PREVIOUS_SCREEN,

        /**
         * And only at the root of everything: let Android close the app.
         *
         * Not handled by us at all -- the callback is disabled so the system
         * does what it does in every other app, including the predictive-back
         * animation that shows the home screen behind. Consuming this would be
         * the "app you cannot leave", which is worse than the bug.
         */
        LEAVE_THE_APP,
    }

    /** Everything the decision depends on. */
    data class Where(
        /** Screens on the stack, including the one on show. */
        val depth: Int,
        val onFiles: Boolean,
        val selection: Int,
        val searching: Boolean,
        val folder: String,
    )

    fun next(where: Where): Outcome = when {
        where.onFiles && where.selection > 0 -> Outcome.CLEAR_SELECTION
        where.onFiles && where.searching -> Outcome.CLEAR_SEARCH
        where.onFiles && where.folder != ROOT -> Outcome.GO_UP_A_FOLDER
        where.depth > 1 -> Outcome.PREVIOUS_SCREEN
        else -> Outcome.LEAVE_THE_APP
    }

    const val ROOT = "/"

    /**
     * The folder one level up.
     *
     * "/Photos/2026" -> "/Photos" -> "/", and the root stays put: there is
     * nowhere above it, and a back press there is the app's to hand over.
     */
    fun parentOf(folder: String): String {
        if (folder == ROOT || folder.isEmpty()) return ROOT
        return folder.trimEnd('/').substringBeforeLast('/', "").ifEmpty { ROOT }
    }

    /**
     * The stack after opening a screen.
     *
     * Opening the screen already on show does not stack a second copy, or Back
     * would need pressing twice for no visible reason -- easy to do when a
     * button is tapped twice.
     */
    fun <T> pushed(stack: List<T>, screen: T): List<T> =
        if (stack.lastOrNull() == screen) stack else stack + screen

    /** The stack after Back. The root is never popped; leaving is Android's job. */
    fun <T> popped(stack: List<T>): List<T> =
        if (stack.size <= 1) stack else stack.dropLast(1)
}
