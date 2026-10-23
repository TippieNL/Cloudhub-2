package nl.tippie.cloudhub.ui

import nl.tippie.cloudhub.net.DuplicateGroup
import nl.tippie.cloudhub.net.DuplicateScan
import nl.tippie.cloudhub.net.FileEntry
import nl.tippie.cloudhub.ui.FavoriteRules.Filter

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

    /* ---- Photos, Videos, Other ---------------------------------------------
     *
     * The four filters Favorites offers, so "Photos" means one thing across the
     * app. They narrow what is shown and nothing else: the scan covers every
     * file, so switching never scans again -- and what Move to trash and
     * "select every extra copy" act on is the sets on show, never one out of
     * sight.
     */

    /** Which filter a set belongs to: Photos, Videos or Other, never All. */
    fun kindOf(group: DuplicateGroup): Filter = when (group.kind) {
        "image" -> Filter.PHOTOS
        "video" -> Filter.VIDEOS
        null -> byNames(group)
        else -> Filter.OTHER
    }

    /**
     * A server that does not say what a set is -- an older build, or another
     * one -- is read by its copies' names, the way the server would: a photo
     * if any copy is named as one, then a video. Byte-identical copies can
     * still be named differently, "holiday.jpg" beside "holiday.jpg.bak".
     */
    private fun byNames(group: DuplicateGroup): Filter {
        val kinds = group.files.map { kindOfName(it.path) }
        return when {
            Filter.PHOTOS in kinds -> Filter.PHOTOS
            Filter.VIDEOS in kinds -> Filter.VIDEOS
            else -> Filter.OTHER
        }
    }

    private fun kindOfName(path: String): Filter {
        val ext = nameOf(path).substringAfterLast('.', "").lowercase()
        return when {
            ext in FileEntry.IMAGE || ext in CAMERA_STILLS -> Filter.PHOTOS
            ext in FileEntry.VIDEO -> Filter.VIDEOS
            else -> Filter.OTHER
        }
    }

    /**
     * Photos the app cannot draw but a server files as photos -- what an
     * iPhone and a scanner save. A duplicate finder still files them there.
     */
    private val CAMERA_STILLS = setOf("heic", "heif", "tif", "tiff")

    fun matches(filter: Filter, group: DuplicateGroup): Boolean =
        filter == Filter.ALL || kindOf(group) == filter

    fun counts(groups: List<DuplicateGroup>): Map<Filter, Int> =
        Filter.entries.associateWith { filter -> groups.count { matches(filter, it) } }

    /**
     * The filter actually in force. One that has emptied -- its last set
     * deleted -- falls back to All, as on Favorites, rather than leaving an
     * empty screen behind a selected chip.
     */
    fun effective(chosen: Filter, groups: List<DuplicateGroup>): Filter =
        if (chosen != Filter.ALL && groups.none { matches(chosen, it) }) Filter.ALL else chosen

    fun shown(groups: List<DuplicateGroup>, filter: Filter): List<DuplicateGroup> =
        groups.filter { matches(filter, it) }

    /**
     * The copies ticked among the sets on show: what Move to trash acts on. A
     * copy ticked under Photos stays ticked while Videos is looked at, but is
     * never deleted from there unseen.
     */
    fun selectedIn(groups: List<DuplicateGroup>, selected: Set<String>): Set<String> =
        groups.flatMapTo(LinkedHashSet()) { group -> group.files.map { it.path }.filter { it in selected } }

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

    /**
     * What the scan is doing right now, under the summary. "Files", since the
     * scan here covers every kind -- and says nothing wrong of a server whose
     * scan does not.
     */
    fun activity(scan: DuplicateScan): String = when {
        scan.done -> "${scan.scanned} files checked"
        scan.toHash <= 0 -> "Looking through ${scan.scanned} files…"
        else -> "Comparing ${scan.hashed} of ${scan.toHash} possible matches…"
    }
}
