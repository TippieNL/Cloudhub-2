package nl.tippie.cloudhub.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One row of a folder listing, matching FileService::entry() exactly. */
@Serializable
data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val modified: String = "",
    val extension: String? = null,
    /** The server already has a cached frame for this video. */
    val hasThumbnail: Boolean = false,
) {
    val ext: String get() = (extension ?: name.substringAfterLast('.', "")).removePrefix(".").lowercase()

    val kind: Kind get() = when {
        isDirectory -> Kind.FOLDER
        ext in IMAGE -> Kind.IMAGE
        ext in VIDEO -> Kind.VIDEO
        ext in AUDIO -> Kind.AUDIO
        ext == "pdf" -> Kind.PDF
        ext in TEXT -> Kind.TEXT
        else -> Kind.OTHER
    }

    enum class Kind { FOLDER, IMAGE, VIDEO, AUDIO, PDF, TEXT, OTHER }

    companion object {
        // Kept in step with the sets in public/assets/js/app.js.
        val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "avif")
        val VIDEO = setOf("mp4", "webm", "ogv", "ogg", "mov", "m4v", "avi", "mkv",
            "mpeg", "mpg", "3gp", "3g2", "ts", "m2ts", "mts")
        val AUDIO = setOf("mp3", "wav", "oga", "m4a", "aac", "flac", "opus")
        val TEXT = setOf("txt", "md", "csv", "log", "json", "xml", "yml", "yaml")
    }
}

@Serializable
data class User(val id: Int = 0, val username: String = "", val role: String = "viewer") {
    val canWrite get() = role == "editor" || role == "admin"
    val isAdmin get() = role == "admin"
}

@Serializable
data class AuthStatus(
    val authenticated: Boolean = false,
    val user: User? = null,
    val csrfToken: String = "",
)

@Serializable
data class LoginResult(
    val success: Boolean = false,
    val user: User? = null,
    val csrfToken: String = "",
)

@Serializable
data class SearchResult(
    val query: String = "",
    val results: List<FileEntry> = emptyList(),
    /** A cap was reached, so this is not the whole answer. */
    val truncated: Boolean = false,
    val scanned: Int = 0,
)

@Serializable
data class UploadStatus(
    val id: String = "",
    val name: String = "",
    val size: Long = 0,
    /** What the server actually holds. A resume continues from here. */
    val received: Long = 0,
    val complete: Boolean = false,
    val chunkBytes: Long = 8L * 1024 * 1024,
)

@Serializable
data class UploadComplete(val success: Boolean = false, val name: String = "", val path: String = "")

@Serializable
data class TrashEntry(
    val id: String = "",
    val name: String = "",
    val originalPath: String = "",
    val isDirectory: Boolean = false,
    val bytes: Long = 0,
    val files: Int = 0,
    val deletedAt: String = "",
    val deletedBy: String? = null,
)

@Serializable
data class TrashListing(
    val enabled: Boolean = true,
    val retentionDays: Int = 0,
    val entries: List<TrashEntry> = emptyList(),
)

@Serializable
data class ShareLink(
    val token: String = "",
    val url: String = "",
    val name: String = "",
    val kind: String = "",
    val expiresAt: String? = null,
)

@Serializable
data class SimpleResult(
    val success: Boolean = false,
    val message: String = "",
    val trashed: Boolean = false,
)

/** A bulk move or copy: partial success is reported per item, never hidden. */
@Serializable
data class RelocateResult(
    val success: Boolean = false,
    val completed: Int = 0,
    val failed: List<Failure> = emptyList(),
) {
    @Serializable data class Failure(val path: String = "", val message: String = "")
}

@Serializable
data class ServerConfigInfo(
    val readOnly: Boolean = false,
    val allowDelete: Boolean = true,
    val allowOverwrite: Boolean = true,
    val maxUploadMb: Int = 2048,
    val maxUploadFiles: Int = 20,
    val chunkMb: Int = 8,
)
