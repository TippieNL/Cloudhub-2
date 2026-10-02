package nl.tippie.cloudhub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.tippie.cloudhub.net.ApiError
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.FileEntry
import nl.tippie.cloudhub.net.SubtitleTrack
import nl.tippie.cloudhub.ui.SidecarSubtitles
import nl.tippie.cloudhub.ui.SubtitleText
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The subtitle tracks of a video, whichever server it is on.
 *
 * Cloudhub-2's server finds the files beside a video and serves them as
 * WebVTT (/api/files/subtitles and /api/files/subtitle). Cloudhub-web has
 * neither route -- it answers "API endpoint not found" -- and the app only
 * ever asked those. On it no track was ever found, so subtitles added from
 * the app uploaded fine and were then reported as never having arrived.
 *
 * Where the routes are missing, the same work is done here: the folder
 * listing says what is beside the video (SidecarSubtitles, the server's
 * rules), and each file is fetched as stored and converted to UTF-8 WebVTT
 * (SubtitleText) in the app's cache, where the player reads it.
 *
 * Which of the two a server needs is learned from its first answer and kept
 * for the life of the process, so a server without the routes is not asked
 * for them again on every video.
 */
class SubtitleLoader(private val api: CloudHubApi, private val cacheDir: File) {

    /**
     * What is beside the video: names, labels and languages, with nothing
     * fetched. Throws when the server cannot be asked at all.
     */
    suspend fun find(video: FileEntry): List<SubtitleTrack> {
        val server = api.baseUrl
        if (routes[server] != false) {
            try {
                return api.subtitles(video.path).also { routes[server] = true }
            } catch (e: ApiError) {
                if (!isMissingRoute(e)) throw e
                routes[server] = false
            }
        }
        return SidecarSubtitles.find(video) { folder -> api.list(folder) }
    }

    /**
     * The tracks as the player reads them. The server's own are served
     * converted already; one found here is fetched and converted now, and
     * left out if that fails -- a subtitle that cannot be read must never
     * cost the film.
     */
    suspend fun playable(tracks: List<SubtitleTrack>): List<SubtitleTrack> =
        tracks.mapNotNull { track -> if (track.url.isNotEmpty()) track else runCatching { converted(track) }.getOrNull() }

    /** [find], then [playable]. */
    suspend fun tracksFor(video: FileEntry): List<SubtitleTrack> = playable(find(video))

    /**
     * One track fetched and written out as WebVTT, named after its content:
     * a track replaced on the server under the same name is a new file here,
     * so the player can never be handed the old one.
     */
    private suspend fun converted(track: SubtitleTrack): SubtitleTrack = withContext(Dispatchers.IO) {
        val raw = api.openDownload(track.path).use { body ->
            body.byteStream().use { input ->
                // Read one byte past the cap, to tell "at the cap" from "over it".
                val bytes = input.readNBytesCompat(SubtitleText.MAX_BYTES.toInt() + 1)
                require(bytes.size <= SubtitleText.MAX_BYTES) { "${track.name} is too large to be a subtitle track" }
                bytes
            }
        }
        val vtt = SubtitleText.toWebVtt(track.name, raw).toByteArray(Charsets.UTF_8)
        cacheDir.mkdirs()
        val file = File(cacheDir, sha256(vtt).take(32) + ".vtt")
        if (!file.isFile || file.length() != vtt.size.toLong()) {
            val partial = File(cacheDir, file.name + ".part")
            partial.writeBytes(vtt)
            if (!partial.renameTo(file)) { file.writeBytes(vtt); partial.delete() }
        }
        trim()
        // file:/..., which the player's DefaultDataSource reads from disk.
        track.copy(url = file.toURI().toString())
    }

    /** Converted tracks are small, but nothing else would ever remove them. */
    private fun trim() {
        val files = cacheDir.listFiles { f -> f.isFile && f.name.endsWith(".vtt") } ?: return
        if (files.size <= KEEP) return
        files.sortedBy { it.lastModified() }.take(files.size - KEEP).forEach { it.delete() }
    }

    companion object {
        /** Where converted tracks are kept, under the app's cache. */
        const val CACHE_DIR = "subtitles"

        /** Converted tracks kept on the phone; the oldest go first. */
        private const val KEEP = 200

        /** Per server address: whether it has the subtitle routes. */
        private val routes = ConcurrentHashMap<String, Boolean>()

        /**
         * The router's own answer for a route it does not have -- not a
         * route that exists answering that the video is not there.
         */
        fun isMissingRoute(e: ApiError) = e.status == 404 && e.message == "API endpoint not found"

        /** Whether a track is one this loader converted and wrote out. */
        fun isLocal(track: SubtitleTrack) = track.url.startsWith("file:")

        private fun sha256(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** InputStream.readNBytes(), which Android only has from API 33. */
        private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (out.size() < limit) {
                val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }
}
