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
    /*
     * The duplicate finder's limits, and whether it is there at all.
     *
     * The server publishes these so a client reads them rather than keeping a
     * second copy of the defaults -- and their absence is how it says this
     * build has no duplicate finder, which is a better answer than a 404 from
     * a route the user was just offered.
     */
    val duplicateMinBytes: Long? = null,
    val duplicateScanSeconds: Int? = null,
    val duplicateMaxFiles: Int? = null,
)

/** What one account is using, from /api/storage/me -- readable by anyone. */
@Serializable
data class MyStorage(
    val usedBytes: Long = 0,
    val quotaBytes: Long = 0,
    val storeUsedBytes: Long = 0,
    val storageLimitBytes: Long = 0,
    val diskFreeBytes: Long = 0,
    val diskTotalBytes: Long = 0,
    val files: Int = 0,
    val folders: Int = 0,
    val trash: StorageBucket = StorageBucket(),
    val versions: StorageBucket = StorageBucket(),
    val cached: Boolean = false,
    val measuredAt: String? = null,
    val isAdmin: Boolean = false,
)

/**
 * One slice of a duplicate scan -- or, once `done`, its result.
 *
 * Hashing a media library does not finish inside one request on a phone, so
 * the server's contract is a poll loop: POST to start, POST again while `done`
 * is false, GET to read the last result without doing any work. Every reply
 * carries the whole picture so far, which is why the screen can list groups
 * while the scan is still running.
 *
 * Matches are byte-for-byte identical only. A photo saved again at another
 * size is a different file and is not reported -- calling that a duplicate is
 * how somebody loses their only copy of something.
 */
@Serializable
data class DuplicateScan(
    val path: String = "/",
    val done: Boolean = false,
    /**
     * Present, and false, only in the reply to a GET before anything has been
     * scanned. A slice of a real scan omits it, which is why it defaults true.
     */
    val started: Boolean = true,
    /** Media files walked, and how many shared a size with another. */
    val scanned: Int = 0,
    val candidates: Int = 0,
    /** Progress through the candidates, and how many were read rather than
     *  answered from the server's digest cache. */
    val hashed: Int = 0,
    val computed: Int = 0,
    val toHash: Int = 0,
    /** True when the walk hit the server's file limit: the result is partial. */
    val truncated: Boolean = false,
    val groups: List<DuplicateGroup> = emptyList(),
    /** Copies that could go: the sum over groups of (count - 1). */
    val duplicateFiles: Int = 0,
    val reclaimable: Long = 0,
    val startedAt: String? = null,
    val finishedAt: String? = null,
)

/** One set of files that are byte-for-byte the same. */
@Serializable
data class DuplicateGroup(
    val bytes: Long = 0,
    val count: Int = 0,
    /** What deleting all but one copy would give back: bytes x (count - 1). */
    val reclaimable: Long = 0,
    val files: List<DuplicateFile> = emptyList(),
)

/**
 * One copy.
 *
 * The path is all the server sends -- no name, no folder -- so both are taken
 * from it here rather than asked for.
 */
@Serializable
data class DuplicateFile(
    val path: String = "",
    val bytes: Long = 0,
    /** Seconds since the epoch, as the server stats it. */
    val mtime: Long = 0,
)

@Serializable
data class StorageBucket(val bytes: Long = 0, val files: Int = 0, val entries: Int = 0)

/** The whole-server report from /api/storage/usage. Admins only. */
@Serializable
data class ServerStorage(
    val bytes: Long = 0,
    val files: Int = 0,
    val folders: Int = 0,
    val diskFree: Long = 0,
    val diskTotal: Long = 0,
    val trash: StorageBucket = StorageBucket(),
    val versions: StorageBucket = StorageBucket(),
    val storageLimitBytes: Long = 0,
    val userQuotaBytes: Long = 0,
    val cached: Boolean = false,
    val measuredAt: String? = null,
    val byUser: List<AccountUsage> = emptyList(),
    val largest: List<LargestFile> = emptyList(),
)

@Serializable
data class AccountUsage(
    val userId: Int? = null,
    val username: String? = null,
    val bytes: Long = 0,
    val files: Int = 0,
)

@Serializable
data class LargestFile(val path: String = "", val bytes: Long = 0)
