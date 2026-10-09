package nl.tippie.cloudhub.net

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
    /**
     * Set while this session's password was right and its emailed code is
     * still awaited -- after the app was closed on the code step, say -- so
     * it can carry on there rather than ask for the password again.
     */
    val twoFactor: SecondStep? = null,
)

@Serializable
data class LoginResult(
    val success: Boolean = false,
    val user: User? = null,
    val csrfToken: String = "",
    /**
     * Set, with success false, when the password was right but the account
     * uses two-step verification: the session is not signed in until the
     * code is checked (CloudHubApi.verifySignIn).
     */
    val twoFactor: SecondStep? = null,
    /** After signing in with a recovery code: how many are left. */
    val recoveryCodesLeft: Int? = null,
)

/**
 * What the second step of signing in needs, as TwoFactor::signInInfo() on the
 * server describes it. Nothing has been emailed yet unless [codeSent] says so:
 * the code is sent when the app asks for it.
 */
@Serializable
data class SecondStep(
    /**
     * The address the code goes to, masked: "k•••@example.com". Null for an
     * account turned on when codes went by text message, which has none yet.
     */
    val emailHint: String? = null,
    val codeLength: Int = 6,
    /** False when no code can be emailed to this account: a recovery code is the only way in. */
    val emailAvailable: Boolean = true,
    val codeSent: Boolean = false,
    /** Seconds before another code may be asked for. */
    val resendIn: Int = 0,
)

/** A code was emailed: where to, for how long it works, and when another may be asked for. */
@Serializable
data class CodeSent(
    val emailHint: String? = null,
    val expiresIn: Int? = null,
    val resendIn: Int = 0,
)

/** GET /api/users/me/two-factor: this account's two-step verification, and what the server can do. */
@Serializable
data class TwoFactorOverview(
    /** The server can email codes and its database is ready, so it can be turned on. */
    val available: Boolean = false,
    val schemaReady: Boolean = true,
    val emailAvailable: Boolean = false,
    val enabled: Boolean = false,
    /** The address codes go to, masked; null while it is off, or on with no address yet. */
    val emailHint: String? = null,
    val recoveryCodesLeft: Int = 0,
)

/**
 * One step of a change to two-step verification: the code the server is
 * waiting for, and -- once [done] -- what changed.
 *
 * Turning it on, moving it to a new address, turning it off and new recovery
 * codes are each start -> confirm. Moving to a new address can take two
 * codes, the current address's first ([stage] "current") and then the new
 * one's ("new"); a confirm that is not [done] is the next of them.
 */
@Serializable
data class TwoFactorStage(
    val done: Boolean = false,
    /** current: the address codes go to now; new: the address being moved to. */
    val stage: String = "",
    /** The address this step's code went to, masked. */
    val emailHint: String? = null,
    val codeLength: Int = 6,
    /** The current address may be answered with a recovery code instead. Never a new one. */
    val recoveryAllowed: Boolean = false,
    val sent: Boolean = false,
    val resendIn: Int = 0,
    /** The change is under way but its email could not be sent; ask again, or use a recovery code. */
    val error: StageProblem? = null,
    /** Once done: whether it is now on. */
    val enabled: Boolean? = null,
    /** Once done, when it was turned on or new ones were asked for. Shown once, never again. */
    val recoveryCodes: List<String>? = null,
)

@Serializable
data class StageProblem(val message: String = "", val retryAfter: Int = 0)

@Serializable
data class SearchResult(
    val query: String = "",
    val results: List<FileEntry> = emptyList(),
    /** A cap was reached, so this is not the whole answer. */
    val truncated: Boolean = false,
    /**
     * The server stopped for time, not at a cap: the same request again carries
     * on where it stopped. Absent from servers that answer in one go.
     */
    val incomplete: Boolean = false,
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
    val deletedBy: String? = null,
)

@Serializable
data class TrashListing(
    val enabled: Boolean = true,
    val retentionDays: Int = 0,
    val entries: List<TrashEntry> = emptyList(),
)

/**
 * One subtitle file found beside a video, matching SubtitleService::tracksFor().
 *
 * `url` is where the track is fetched from, and it is always WebVTT however
 * the file on the server is written -- the server converts SubRip on the way
 * out, so nothing here has to know about .srt.
 */
@Serializable
data class SubtitleTrack(
    val id: String = "",
    val path: String = "",
    val name: String = "",
    val label: String = "",
    val language: String = "",
    val forced: Boolean = false,
    val url: String = "",
)

@Serializable
data class SubtitleListing(
    val path: String = "",
    val tracks: List<SubtitleTrack> = emptyList(),
)

@Serializable
data class ShareLink(
    val token: String = "",
    val url: String = "",
    val name: String = "",
    val kind: String = "",
    val expiresAt: String? = null,
)

/**
 * The account's favorites, from /api/favorites, most recently starred first.
 *
 * Each is an ordinary listing row -- the server builds them with the same
 * FileService::entry() a folder listing uses -- so every card, viewer and
 * player takes one without knowing where it came from.
 */
@Serializable
data class FavoritesListing(
    val favorites: List<FileEntry> = emptyList(),
    /** How many one account may keep. */
    val limit: Int = 0,
)

/** The answer to starring or unstarring, with the path as the server stored it. */
@Serializable
data class FavoriteResult(
    val success: Boolean = false,
    val favorite: Boolean = false,
    val path: String = "",
    val message: String = "",
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
    /** Media files walked. */
    val scanned: Int = 0,
    /** Progress hashing the files that share a size with another: done, of how many. */
    val hashed: Int = 0,
    val toHash: Int = 0,
    /** True when the walk hit the server's file limit: the result is partial. */
    val truncated: Boolean = false,
    val groups: List<DuplicateGroup> = emptyList(),
    /** Copies that could go: the sum over groups of (count - 1). */
    val duplicateFiles: Int = 0,
    val reclaimable: Long = 0,
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
    // /api/storage/usage sends `folders` as a list of per-folder rows, not a
    // count -- so a `folders: Int` here made kotlinx throw on the whole
    // payload (a JSON array is not an Int, and ignoreUnknownKeys does not cover
    // a type mismatch on a declared key), and every admin's serverStorage()
    // call failed silently, leaving the "By account" section blank. This
    // client does not display the per-folder breakdown, so the key is left as
    // a tolerated unknown rather than modelled.
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
