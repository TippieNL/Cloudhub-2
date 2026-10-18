package nl.tippie.cloudhub.data

/**
 * Where a download is saved, and under what name.
 *
 * Android 10 and up save through MediaStore's Downloads collection, which needs
 * no permission and picks a free name itself. Android 9 and older have no such
 * collection: the only way into the shared Downloads folder is to write the
 * file there, which takes WRITE_EXTERNAL_STORAGE -- declared for those versions
 * only, so 10 and up never see it -- and the name has to be picked here, or an
 * earlier download with the same name is quietly overwritten.
 *
 * Before this, those versions got neither: every download failed with a
 * permission error, and would have overwritten if it had not.
 */
object DownloadRules {

    /** Android 9: the last version without MediaStore.Downloads. */
    private const val LAST_WITHOUT_MEDIASTORE_DOWNLOADS = 28

    fun needsStoragePermission(sdkInt: Int): Boolean = sdkInt <= LAST_WITHOUT_MEDIASTORE_DOWNLOADS

    /**
     * [name], or the first of "name (1).ext", "name (2).ext", ... that [isTaken]
     * says is free -- the pattern MediaStore uses on newer versions, so a phone
     * names a second copy the same way whichever version it runs.
     *
     * [isTaken] may claim the name as it asks, as File.createNewFile() does:
     * that is what stops two downloads of one name picking the same free one.
     */
    fun freeName(name: String, isTaken: (String) -> Boolean): String {
        if (!isTaken(name)) return name
        val dot = name.lastIndexOf('.')
        // A leading dot starts a hidden file's name; it is not an extension.
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (n in 1..MAX_TRIES) {
            val candidate = "$base ($n)$ext"
            if (!isTaken(candidate)) return candidate
        }
        // A folder with a thousand copies of one name has something else wrong
        // with it; a name nothing can share still saves the file.
        return "$base (${System.currentTimeMillis()})$ext"
    }

    private const val MAX_TRIES = 999
}
