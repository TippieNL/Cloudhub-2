package nl.tippie.cloudhub.ui

/**
 * Where the file list was, so coming back lands there.
 *
 * Two things sent you to the top. A list's scroll position is remembered by
 * the composable that owns it, and opening a video or a photo takes that
 * composable out of the composition entirely -- so scrolling to the fortieth
 * holiday clip, watching it, and coming back put you at the first. And one
 * scroll position is shared by every folder, so it could only ever describe
 * the folder on screen.
 *
 * This holds a position per folder. Bounded, because a long browse should not
 * accumulate folders forever, and dropped oldest-first: the folder you are
 * most likely to return to is the one you just left.
 *
 * A place is held by name as well as by number. Coming back to a folder
 * lists it again, and by then a file may have been added above, the sort
 * changed or a filter cleared -- the fortieth row is a different file, and
 * only its name still finds the one that was there.
 */
class ScrollMemory(initial: Map<String, Place> = emptyMap()) {

    /**
     * A place in a list: which item was first on screen and how far into it,
     * and which file that was. When the folder was left by opening one of its
     * own folders, also which one, and how far down the list it sat -- so
     * coming back puts that folder back under your finger.
     *
     * [bar] is how far the top bar was tucked away, as its height offset (0
     * shown, negative hidden). The bar hides as you scroll down and the list
     * moves up with it, so a place on screen is the list's position *and*
     * the bar's: restore one without the other and every row sits a bar's
     * height away from where you left it.
     */
    data class Place(
        val index: Int,
        val offset: Int,
        val key: String? = null,
        val opened: String? = null,
        val openedTop: Int = 0,
        val bar: Float = 0f,
    )

    private val places = LinkedHashMap<String, Place>().apply { putAll(initial) }

    fun remember(path: String, index: Int, offset: Int, key: String? = null, bar: Float = 0f) {
        // A negative offset is not a place; treat it as the top rather than
        // storing something a list cannot be scrolled to.
        put(path, Place(maxOf(0, index), maxOf(0, offset), key, bar = minOf(0f, bar)))
    }

    /**
     * Leaving [path] by opening its folder [opened], whose top was [openedTop]
     * pixels down the list (negative when it was partly scrolled off).
     *
     * Kept until the folder is scrolled again, which records an ordinary
     * place over it: a folder you have moved on from is no longer the place
     * to come back to.
     */
    fun rememberOpening(
        path: String, index: Int, offset: Int, key: String?, opened: String, openedTop: Int, bar: Float = 0f,
    ) {
        put(path, Place(maxOf(0, index), maxOf(0, offset), key, opened, openedTop, minOf(0f, bar)))
    }

    private fun put(path: String, place: Place) {
        places.remove(path)
        places[path] = place
        while (places.size > REMEMBERED_FOLDERS) places.remove(places.keys.first())
    }

    /** Null for a folder never visited, which means "start at the top". */
    fun placeOf(path: String): Place? = places[path]

    /** After a delete or a move, the position in that folder means nothing. */
    fun forget(path: String) { places.remove(path) }

    /** For the Saver, so a position survives the screen being rebuilt. */
    fun snapshot(): Map<String, Place> = LinkedHashMap(places)

    /**
     * Where a list should open: the item to put first, and the scroll offset
     * to give it -- negative to sit that far below the top.
     */
    data class Start(val index: Int, val offset: Int)

    companion object {
        /** Deep enough for any real tree; a browse cannot grow this without limit. */
        const val REMEMBERED_FOLDERS = 32

        val TOP = Start(0, 0)

        /**
         * Where to open a folder now listing [paths], last left at [place].
         *
         * The folder you opened, back at the height it had on screen; failing
         * that, the file that was first on screen, where it was; failing both
         * -- both gone -- the same row, or the last one if the list is now
         * shorter. Names come before numbers so that a list which changed
         * while you were away still lands on the same files: with nothing
         * changed, all three put every row exactly where it was.
         */
        fun startFor(place: Place?, paths: List<String>): Start {
            if (place == null || paths.isEmpty()) return TOP
            place.opened?.let { opened ->
                val at = paths.indexOf(opened)
                if (at >= 0) return Start(at, -place.openedTop)
            }
            place.key?.let { key ->
                val at = paths.indexOf(key)
                if (at >= 0) return Start(at, place.offset)
            }
            return if (place.index <= paths.lastIndex) Start(place.index, place.offset) else Start(paths.lastIndex, 0)
        }

        /** Where a file sits in the list on screen, or -1 if it is not in it. */
        fun indexOfPath(paths: List<String>, path: String?): Int =
            if (path == null) -1 else paths.indexOf(path)

        /**
         * Whether coming back should scroll to the file you were on.
         *
         * Swiping through thirty photos and coming back should land on the
         * one you ended at, not the one you opened. But if it is already on
         * screen, scrolling would shove it to the top edge for no reason --
         * the eye is already there, and moving the list under it is worse than
         * leaving it alone.
         */
        fun shouldReveal(target: Int, firstVisible: Int, lastVisible: Int): Boolean =
            target >= 0 && (target < firstVisible || target > lastVisible)
    }
}
