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
 */
class ScrollMemory(initial: Map<String, Place> = emptyMap()) {

    /** A place in a list: which item, and how far into it. */
    data class Place(val index: Int, val offset: Int)

    private val places = LinkedHashMap<String, Place>().apply { putAll(initial) }

    fun remember(path: String, index: Int, offset: Int) {
        // A negative offset is not a place; treat it as the top rather than
        // storing something a list cannot be scrolled to.
        val place = Place(maxOf(0, index), maxOf(0, offset))
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

    companion object {
        /** Deep enough for any real tree; a browse cannot grow this without limit. */
        const val REMEMBERED_FOLDERS = 32

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
