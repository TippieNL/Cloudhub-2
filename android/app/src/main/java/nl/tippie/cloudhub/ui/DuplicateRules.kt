package nl.tippie.cloudhub.ui

import nl.tippie.cloudhub.net.DuplicateGroup
import nl.tippie.cloudhub.net.DuplicateScan

/**
 * What the duplicates screen offers to delete, and when to stop asking.
 *
 * The screen's whole job is deleting files, so the rules about *which* files
 * are the part worth being sure of. Two of them matter more than the rest:
 * every group keeps a copy, always -- a duplicate finder that can empty a
 * group has deleted the photo, not the duplicate -- and nothing is ever
 * deleted without being asked for.
 *
 * The server sends a path, a size and a modification time per copy, and
 * nothing else: no name, no folder, and no opinion about which copy to keep.
 * All three are decided here.
 */
object DuplicateRules {

    /**
     * How many slices to ask for before giving up.
     *
     * The protocol is "post again while done is false", which is a loop with
     * no bound in it. A server that never finishes would otherwise be asked
     * for ever, from a phone. At the default eight seconds a slice this is
     * over an hour of scanning, so it stops nothing real.
     */
    const val MAX_SLICES = 500

    /** Whether to ask for another slice. */
    fun shouldContinue(scan: DuplicateScan, slicesSoFar: Int): Boolean =
        !scan.done && slicesSoFar < MAX_SLICES

    /**
     * A stable identity for a group.
     *
     * There is no hash in the reply and sizes repeat, so the first path is
     * used: a file belongs to exactly one group, which makes it unique, and it
     * survives the list being re-sorted between slices.
     */
    fun groupId(group: DuplicateGroup): String = group.files.firstOrNull()?.path ?: ""

    /** The file name, which the server does not send. */
    fun nameOf(path: String): String = path.substringAfterLast('/').ifEmpty { path }

    /** The folder it is in, likewise. */
    fun folderOf(path: String): String {
        val cut = path.substringBeforeLast('/', "")
        return if (cut.isEmpty()) "/" else cut
    }

    /**
     * Which copy to keep: the one chosen, or else the oldest.
     *
     * The oldest is the original in every ordinary story -- the phone backup,
     * the second import, the folder copied "just in case" all come later --
     * and the modification time is the only thing the server sends that can
     * tell them apart. Ties break on the path so the answer does not move
     * around between scans.
     */
    fun keeperFor(group: DuplicateGroup, chosen: String?): String {
        val paths = group.files.map { it.path }
        chosen?.let { if (it in paths) return it }
        return group.files
            .sortedWith(compareBy({ it.mtime }, { it.path }))
            .firstOrNull()?.path
            ?: ""
    }

    /** The copies to remove from one group: all of them except the one kept. */
    fun removable(group: DuplicateGroup, chosen: String?): List<String> {
        if (group.files.size <= 1) return emptyList()
        val keep = keeperFor(group, chosen)
        return group.files.map { it.path }.filter { it != keep }
    }

    /** Everything the "select the extra copies" action would remove. */
    fun removableIn(groups: List<DuplicateGroup>, keeping: Map<String, String>): List<String> =
        groups.flatMap { removable(it, keeping[groupId(it)]) }

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

    /**
     * How far along a running scan is, or null while that cannot be said.
     *
     * The first slice walks the tree and hashes nothing, so there is a real
     * moment with a count of files and no denominator; a bar that reads zero
     * then is honest, one that guesses is not.
     */
    fun progress(scan: DuplicateScan): Float? {
        if (scan.done) return 1f
        if (scan.toHash <= 0) return null
        return (scan.hashed.toFloat() / scan.toHash).coerceIn(0f, 1f)
    }

    /**
     * The same scan with some copies gone.
     *
     * Deleting does not re-run the scan -- that would mean hashing the library
     * again to learn something already known -- so the result is brought up to
     * date here instead. A group that no longer has two copies in it is not a
     * duplicate any more and leaves with them.
     */
    fun without(scan: DuplicateScan, removed: Set<String>): DuplicateScan {
        if (removed.isEmpty()) return scan
        val groups = scan.groups
            .map { group ->
                val kept = group.files.filterNot { it.path in removed }
                group.copy(
                    files = kept,
                    count = kept.size,
                    reclaimable = group.bytes * maxOf(0, kept.size - 1),
                )
            }
            .filter { it.files.size > 1 }
        return scan.copy(
            groups = groups,
            duplicateFiles = groups.sumOf { it.count - 1 },
            reclaimable = groups.sumOf { it.reclaimable },
        )
    }

    /** A one-line summary of a scan, for the top of the screen. */
    fun summary(groupCount: Int, reclaimable: Long, humanBytes: (Long) -> String): String = when {
        groupCount == 0 -> "No duplicates found"
        else -> "$groupCount ${if (groupCount == 1) "set" else "sets"} of copies · ${humanBytes(reclaimable)} to reclaim"
    }

    /** What the scan is doing right now, under the summary. */
    fun activity(scan: DuplicateScan): String = when {
        scan.done -> "${scan.scanned} photos and videos checked"
        scan.toHash <= 0 -> "Looking through ${scan.scanned} photos and videos…"
        else -> "Comparing ${scan.hashed} of ${scan.toHash} possible matches…"
    }
}
