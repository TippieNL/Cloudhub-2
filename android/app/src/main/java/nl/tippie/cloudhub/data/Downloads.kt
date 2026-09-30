package nl.tippie.cloudhub.data

import android.content.Context

/** Stream into the device's Downloads folder without buffering the whole file. */
fun saveToDownloads(context: Context, name: String, input: java.io.InputStream) {
    val safe = name.substringAfterLast('/').ifBlank { "download" }
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safe)
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val item = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw java.io.IOException("The Downloads folder refused the file")
        try {
            resolver.openOutputStream(item)?.use { input.copyTo(it) }
                ?: throw java.io.IOException("The Downloads folder could not be opened")
        } catch (e: Exception) {
            // A download cut short is not left behind as a truncated file --
            // a ZIP that stops halfway opens as a damaged archive, or not at
            // all, and looks like the server's fault.
            runCatching { resolver.delete(item, null, null) }
            throw e
        }
        values.clear()
        values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(item, values, null, null)
    } else {
        val dir = android.os.Environment
            .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        java.io.File(dir, safe).outputStream().use { input.copyTo(it) }
    }
}
