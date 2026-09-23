package nl.tippie.cloudhub.ui

import nl.tippie.cloudhub.net.FileEntry

/**
 * The Favorites screen's filters, and what a star does to the list on screen.
 *
 * Apart from the composable because these are the parts that are easy to get
 * subtly wrong and cheap to test: a filter left pressed over nothing, or a list
 * that disagrees with the star on the card it came from.
 */
object FavoriteRules {

    /** The same four the web app's Favorites page offers. */
    enum class Filter(val label: String) {
        ALL("All"),
        PHOTOS("Photos"),
        VIDEOS("Videos"),

        /** Everything that is neither: documents, music, archives. */
        OTHER("Other");

        fun matches(entry: FileEntry): Boolean = when (this) {
            ALL -> true
            PHOTOS -> entry.kind == FileEntry.Kind.IMAGE
            VIDEOS -> entry.kind == FileEntry.Kind.VIDEO
            OTHER -> entry.kind != FileEntry.Kind.IMAGE && entry.kind != FileEntry.Kind.VIDEO
        }
    }

    fun counts(entries: List<FileEntry>): Map<Filter, Int> =
        Filter.entries.associateWith { filter -> entries.count(filter::matches) }

    /**
     * The filter actually in force.
     *
     * One that has emptied -- the last video unstarred -- falls back to All
     * rather than leaving an empty screen behind a selected chip.
     */
    fun effective(chosen: Filter, entries: List<FileEntry>): Filter =
        if (chosen != Filter.ALL && entries.none(chosen::matches)) Filter.ALL else chosen

    /**
     * The favorites after a star or an unstar, before the server is asked again.
     *
     * A new star goes to the top, where the server will list it: favorites are
     * ordered most recently starred first.
     */
    fun afterToggle(entries: List<FileEntry>, entry: FileEntry, starred: Boolean): List<FileEntry> {
        val others = entries.filter { it.path != entry.path }
        return if (starred) listOf(entry) + others else others
    }
}
