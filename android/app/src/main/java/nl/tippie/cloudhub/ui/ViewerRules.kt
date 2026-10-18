package nl.tippie.cloudhub.ui

import nl.tippie.cloudhub.net.FileEntry

/**
 * What the photo viewer shows around the photos, and what an edit does to
 * the row of them.
 *
 * The viewer is opened with a folder's photos -- or the favorites' -- and
 * keeps its own copy of that list, because deleting or renaming the photo on
 * show has to change the pager there and then rather than when the folder is
 * next fetched. Every change to that list goes through here, so the page you
 * land on afterwards is decided in one tested place.
 */
object ViewerRules {

    /**
     * "3 of 12" -- or nothing at all for a single photo, where "1 of 1" says
     * nothing a glance at the screen does not.
     */
    fun counter(index: Int, count: Int): String? =
        if (count <= 1) null else "${index.coerceIn(0, count - 1) + 1} of $count"

    /** The photos with one gone: deleted, or renamed into something that is not a photo. */
    fun without(images: List<FileEntry>, path: String): List<FileEntry> =
        images.filterNot { it.path == path }

    /**
     * Where the pager goes when the photo on show leaves it: the one that
     * moved into its place, or the new last one if it was last.
     */
    fun pageAfterRemoval(index: Int, remaining: Int): Int =
        if (remaining <= 0) 0 else index.coerceIn(0, remaining - 1)

    /**
     * An undone delete: the photo goes back where it was.
     *
     * At its old position rather than at the end, so Undo shows the photo
     * that was there before -- and not twice, if the list somehow already has it.
     */
    fun restored(images: List<FileEntry>, entry: FileEntry, at: Int): List<FileEntry> {
        if (images.any { it.path == entry.path }) return images
        return images.toMutableList().apply { add(at.coerceIn(0, size), entry) }
    }

    /**
     * A rename, with the name and path the server says it ended up with --
     * which can differ from the one asked for, when that was taken.
     *
     * Renamed to something that is not a photo any more (a .jpg to a .txt) it
     * leaves the viewer: there is nothing left for the page to draw.
     */
    fun renamed(images: List<FileEntry>, old: FileEntry, newPath: String, newName: String): List<FileEntry> {
        val updated = old.copy(path = newPath, name = newName, extension = null)
        if (updated.kind != FileEntry.Kind.IMAGE) return without(images, old.path)
        return images.map { if (it.path == old.path) updated else it }
    }
}
