package nl.tippie.cloudhub.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.FileEntry
import java.io.File
import java.util.UUID

/**
 * The photo itself, on this phone, for handing to another app.
 *
 * Sharing a photo means sharing its bytes -- a messaging app wants the
 * picture, not a link to a server it cannot sign in to -- and another app can
 * only read a file this one has and grants it. So the photo is copied into
 * the cache and offered through the app's FileProvider, scoped to that one
 * folder (res/xml/file_paths.xml) so nothing else in app storage is exposed.
 *
 * The bytes usually need no download at all: the viewer fetched them to draw
 * the photo, and the image cache still has them, keyed by the URL they came
 * from. Only a photo that has not finished loading is fetched again.
 */
internal object LocalCopies {

    /** The cache folder file_paths.xml exposes as "shared". */
    const val FOLDER = "shared"

    /**
     * How long a copy is kept. The app receiving it may read it long after
     * the share sheet closes -- a message queued while offline -- so it is not
     * deleted on the way out, only once it is a day old.
     */
    const val KEEP_MS = 24L * 60 * 60 * 1000

    enum class HandOff { SHARE, OPEN, SET_AS }

    /** The type a receiving app is told, from the extension the viewer already trusts. */
    fun mimeType(ext: String): String = when (ext.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "avif" -> "image/avif"
        else -> "image/*"
    }

    /**
     * The copy's file name: the photo's own, so the other app sees
     * "IMG_1234.jpg" rather than a number -- with anything that could reach
     * outside its folder taken out.
     */
    fun fileName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\')
            .filter { it != '\u0000' }
            .trim()
            .takeUnless { it.isEmpty() || it == "." || it == ".." }
            ?: "photo"

    fun isStale(modifiedAtMs: Long, nowMs: Long): Boolean = nowMs - modifiedAtMs > KEEP_MS

    /**
     * Copy [entry] into the shared folder and return the URI to hand out.
     *
     * [cachedAs] is the URL the viewer loaded the photo from, which is what
     * the image cache files it under.
     */
    // Coil 2 still marks reading its disk cache experimental. It is how the
    // bytes already on the phone are reused instead of downloaded twice, and
    // a change to it would be a compile error on the next Coil, not a surprise.
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    suspend fun copyOf(context: Context, api: CloudHubApi, entry: FileEntry, cachedAs: String): Uri =
        withContext(Dispatchers.IO) {
            val root = File(context.cacheDir, FOLDER)
            val now = System.currentTimeMillis()
            root.listFiles()?.forEach { if (isStale(it.lastModified(), now)) it.deleteRecursively() }

            // A folder per copy: two photos called IMG_0001.jpg from different
            // folders must not overwrite each other while one is still being read.
            val folder = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
            val file = File(folder, fileName(entry.name))
            try {
                val cached = context.imageLoader.diskCache?.openSnapshot(cachedAs)
                if (cached != null) {
                    cached.use { snapshot ->
                        snapshot.data.toFile().inputStream().use { input ->
                            file.outputStream().use { input.copyTo(it) }
                        }
                    }
                } else {
                    api.openDownload(entry.path).use { body ->
                        body.byteStream().use { input -> file.outputStream().use { input.copyTo(it) } }
                    }
                }
            } catch (e: Throwable) {
                // Half a photo is worse than none: the other app would show it.
                folder.deleteRecursively()
                throw e
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }

    /**
     * The chooser for one hand-off, with read access granted to whichever app
     * is picked -- and to nothing else.
     */
    fun intentFor(how: HandOff, uri: Uri, entry: FileEntry): Intent {
        val type = mimeType(entry.ext)
        val target = when (how) {
            HandOff.SHARE -> Intent(Intent.ACTION_SEND)
                .setType(type)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TITLE, entry.name)
            HandOff.OPEN -> Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
            // "Use as": the wallpaper picker, a contact's photo.
            HandOff.SET_AS -> Intent(Intent.ACTION_ATTACH_DATA)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .setDataAndType(uri, type)
                .putExtra("mimeType", type)
        }
        // The clip is what carries the grant through the chooser, and what the
        // share sheet draws its preview from.
        target.clipData = ClipData.newRawUri(entry.name, uri)
        target.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(target, when (how) {
            HandOff.SHARE -> null
            HandOff.OPEN -> "Open with"
            HandOff.SET_AS -> "Set as"
        })
    }
}
