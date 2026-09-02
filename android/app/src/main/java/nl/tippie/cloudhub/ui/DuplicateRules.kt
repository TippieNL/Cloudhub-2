package nl.tippie.cloudhub.ui

import nl.tippie.cloudhub.net.DuplicateGroup

/**
 * What the duplicates screen offers to delete.
 *
 * The screen's whole job is deleting files, so the rules about *which* files
 * are the part worth being sure of. Two of them matter more than the rest:
 * every group keeps a copy, always -- a duplicate finder that can empty a
 * group has deleted the photo, not the duplicate -- and nothing is ever
 * deleted without being asked for.
 */
object DuplicateRules {

    /**
     * The copies to remove from one group: all of them except the one kept.
     *
     * Falls back to the first file if the server's suggestion is not in the
     * group, which should not happen and must not cost the last copy if it
     * does.
     */
    fun removable(group: DuplicateGroup, keeping: String?): List<String> {
        val paths = group.files.map { it.path }
        if (paths.size <= 1) return emptyList()
        val keep = keeping?.takeIf { it in paths }
            ?: group.keep.takeIf { it in paths }
            ?: paths.first()
        return paths.filter { it != keep }
    }

    /** Everything the "select the extra copies" action would remove. */
    fun removableIn(groups: List<DuplicateGroup>, keeping: Map<String, String>): List<String> =
        groups.flatMap { removable(it, keeping[it.hash]) }

    /** What deleting a set of copies gives back, for the button's label. */
    fun freedBy(groups: List<DuplicateGroup>, selected: Set<String>): Long =
        groups.sumOf { group -> group.files.filter { it.path in selected }.sumOf { it.bytes } }

    /**
     * True when a selection would leave a group with nothing in it.
     *
     * The one thing this screen must never do. Checked against the selection
     * rather than trusted to the rules that built it, because a selection can
     * also be made by hand.
     */
    fun wouldEmptyAGroup(groups: List<DuplicateGroup>, selected: Set<String>): Boolean =
        groups.any { group ->
            group.files.isNotEmpty() && group.files.all { it.path in selected }
        }

    /** A one-line summary of a scan, for the top of the screen. */
    fun summary(groupCount: Int, wastedBytes: Long, humanBytes: (Long) -> String): String = when {
        groupCount == 0 -> "No duplicates found"
        else -> "$groupCount ${if (groupCount == 1) "set" else "sets"} of copies · ${humanBytes(wastedBytes)} to reclaim"
    }
}
