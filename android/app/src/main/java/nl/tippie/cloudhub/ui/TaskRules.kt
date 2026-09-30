package nl.tippie.cloudhub.ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import nl.tippie.cloudhub.net.BackgroundTask
import nl.tippie.cloudhub.net.FileEntry
import nl.tippie.cloudhub.net.TaskList
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What the app says about background tasks, and when it looks again.
 *
 * Long file operations on Cloudhub-web -- a large copy, a ZIP of a folder,
 * unpacking an archive, emptying a big trash, a duplicate scan -- run on the
 * server as tasks, and carry on when the app is closed. The words and the
 * decisions follow the web app's Tasks page, so both describe a task the same
 * way; they are kept here, apart from the screens, so they can be tested.
 */
object TaskRules {

    const val PENDING = "pending"
    const val PROCESSING = "processing"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    /** How often to look while something is queued or running. */
    const val POLL_MS = 1_500L

    /**
     * How often while nothing runs the queue ("none"): the tasks cannot move
     * until an administrator starts a worker, so looking often tells nothing.
     */
    const val IDLE_POLL_MS = 5_000L

    /** How long to wait after a look that failed. */
    const val RETRY_POLL_MS = 10_000L

    /**
     * The least time between two requests to run the queue. A queue that is
     * already running needs no second runner; one that is not is asked again
     * this often while tasks wait. Queueing a task always asks at once.
     */
    const val KICK_INTERVAL_MS = 5_000L

    /** Types that change what is in the store: the folder on screen is reloaded when one finishes. */
    val CHANGES_FILES = setOf("copy", "extract", "archive", "purge")

    fun isActive(task: BackgroundTask) = task.status == PENDING || task.status == PROCESSING

    fun statusLabel(task: BackgroundTask): String =
        if (task.cancelRequested && isActive(task)) "Stopping…"
        else when (task.status) {
            PENDING -> "Queued"
            PROCESSING -> "Running"
            COMPLETED -> "Completed"
            FAILED -> "Failed"
            CANCELLED -> "Cancelled"
            else -> task.status.replaceFirstChar { it.uppercase() }
        }

    /**
     * Whether to draw a progress bar. Not while queued: an indeterminate bar
     * says "busy", and a queued task is not doing anything yet.
     */
    fun showsBar(task: BackgroundTask) = task.status == PROCESSING || task.status == COMPLETED

    /** How far along, 0 to 1, or null while the server cannot say (an indeterminate bar). */
    fun fraction(task: BackgroundTask): Float? =
        if (task.status == COMPLETED) 1f else task.progress.percent?.let { (it / 100f).coerceIn(0f, 1f) }

    /** "3 of 12 · 25%", or "1.2 MB of 40 MB · 3%" for a task that counts bytes. */
    fun progressText(task: BackgroundTask, bytes: (Long) -> String): String {
        val p = task.progress
        if (task.status == PENDING) return "Waiting to start"
        if (p.total <= 0) return if (task.status == PROCESSING) "Starting…" else ""
        val amount = if (p.unit == "bytes") "${bytes(p.done)} of ${bytes(p.total)}" else "${p.done} of ${p.total}"
        return amount + (p.percent?.let { " · $it%" } ?: "")
    }

    /** What a finished task did, in a line; null when there is nothing to say. */
    fun resultText(task: BackgroundTask, bytes: (Long) -> String): String? {
        val r = task.result as? JsonObject ?: return null
        return when (task.type) {
            "copy" -> r.long("copied")?.let { copied ->
                "$copied of ${r.long("of") ?: copied} copied into ${r.string("destination") ?: "/"}"
            }
            "archive" -> r.string("name")?.let { name ->
                val files = r.long("files") ?: 0
                "${r.string("path") ?: name} · ${bytes(r.long("bytes") ?: 0)} · ${plural(files, "file")}"
            }
            "extract" -> r.string("path")?.let { path ->
                val skipped = r.long("skipped") ?: 0
                "Extracted ${plural(r.long("files") ?: 0, "file")} into $path" +
                    if (skipped > 0) " · ${plural(skipped, "link")} skipped" else ""
            }
            "purge" -> r.long("files")?.let { "${plural(it, "file")} deleted" }
            "thumbnails" -> r.long("made")?.let { made ->
                val skipped = r.long("skipped") ?: 0
                "$made made · ${r.long("existing") ?: 0} already there" + if (skipped > 0) " · $skipped skipped" else ""
            }
            "duplicates" -> r.long("groups")?.let { groups ->
                "${plural(groups, "group")} of duplicates · ${bytes(r.long("reclaimable") ?: 0)} reclaimable"
            }
            // Listed in full by checksums(); a line would only repeat it.
            else -> null
        }
    }

    /** One item a copy could not place, and why. */
    data class Failure(val path: String, val message: String)

    fun failures(task: BackgroundTask): List<Failure> =
        ((task.result as? JsonObject)?.get("failed") as? JsonArray).orEmpty().mapNotNull {
            val item = it as? JsonObject ?: return@mapNotNull null
            Failure(item.string("path") ?: return@mapNotNull null, item.string("message") ?: "")
        }

    /** One file's digest from a checksum task. */
    data class Checksum(val path: String, val hash: String)

    fun checksums(task: BackgroundTask): List<Checksum> =
        if (task.type != "checksum") emptyList()
        else ((task.result as? JsonObject)?.get("files") as? JsonArray).orEmpty().mapNotNull {
            val item = it as? JsonObject ?: return@mapNotNull null
            Checksum(item.string("path") ?: return@mapNotNull null, item.string("hash") ?: return@mapNotNull null)
        }

    /** The file a finished archive is saved as: the name it was made under. */
    fun downloadName(task: BackgroundTask): String =
        (task.result as? JsonObject)?.string("name")?.substringAfterLast('/')?.ifBlank { null } ?: "download.zip"

    /** The digests as sha256sum prints them, which is what anyone comparing them will have. */
    fun checksumLines(sums: List<Checksum>): String = sums.joinToString("\n") { "${it.hash}  ${it.path}" }

    /**
     * What to say when a task this app was watching finishes. Nothing for a
     * cancelled one: whoever cancelled it knows.
     */
    fun finishedMessage(task: BackgroundTask): String? = when (task.status) {
        COMPLETED -> "Done: ${task.label}"
        FAILED -> "Failed: ${task.label}" + (task.error?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
        else -> null
    }

    /**
     * Whether to ask the web server to run the queue. With the "inline"
     * runner it only works when a client asks, and a task that waits with
     * nothing running would wait for ever.
     */
    fun shouldKick(list: TaskList): Boolean =
        list.runner == "inline" && list.jobs.any { it.status == PENDING } && list.jobs.none { it.status == PROCESSING }

    /**
     * How long until the next look, or null to stop looking because nothing
     * is queued or running. A look that failed is tried again while there is
     * something to follow ([watching]), and otherwise left until the next
     * reason to look.
     */
    fun nextPollMs(list: TaskList?, failed: Boolean, watching: Boolean): Long? = when {
        failed -> if (watching) RETRY_POLL_MS else null
        list == null || list.active == 0 -> null
        list.runner == "none" -> IDLE_POLL_MS
        else -> POLL_MS
    }

    /** What the Tasks screen says above the list. */
    fun note(list: TaskList): String = when {
        !list.available -> list.message?.takeIf { it.isNotBlank() }
            ?: "Background tasks are not available on this server."
        list.runner == "none" && list.jobs.any { it.status == PENDING } ->
            "No background worker is running, so queued tasks are waiting. " +
                "An administrator can start one with: php tools/worker.php"
        else -> "Large copies, archives, extraction and deletions run on the server. " +
            "They carry on if you close the app."
    }

    /** "queued 14:02 · started 14:02 · finished 14:05", in the phone's time zone. */
    fun timesText(task: BackgroundTask, now: Date = Date(), zone: TimeZone = TimeZone.getDefault()): String {
        fun at(stamp: String?, word: String, dayToo: Boolean) =
            parseTime(stamp)?.let { "$word ${formatTime(it, now, zone, dayToo)}" }
        return listOfNotNull(
            at(task.createdAt, "queued", true),
            at(task.startedAt, "started", false),
            at(task.finishedAt, "finished", false),
            task.attempts.takeIf { it > 1 }?.let { "attempt $it" },
        ).joinToString(" · ")
    }

    /** The server's ISO 8601 stamps: 2026-09-30T19:23:09+00:00. */
    fun parseTime(stamp: String?): Date? {
        if (stamp.isNullOrBlank()) return null
        return runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(stamp) }.getOrNull()
    }

    private fun formatTime(at: Date, now: Date, zone: TimeZone, dayToo: Boolean): String {
        val sameDay = Calendar.getInstance(zone).run {
            time = at
            val day = get(Calendar.YEAR) * 1000 + get(Calendar.DAY_OF_YEAR)
            time = now
            day == get(Calendar.YEAR) * 1000 + get(Calendar.DAY_OF_YEAR)
        }
        val format = if (dayToo && !sameDay) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        else DateFormat.getTimeInstance(DateFormat.SHORT)
        format.timeZone = zone
        return format.format(at)
    }

    /* ---- what the file actions offer --------------------------------------- */

    fun isZip(entry: FileEntry) = !entry.isDirectory && entry.name.endsWith(".zip", ignoreCase = true)

    /**
     * The task-backed actions offered for one item or a selection.
     *
     * None at all where the server has no queue: each of these exists only as
     * a task. Compressing, extracting and making thumbnails write to the
     * store, so they are for editors; a ZIP to download and a checksum only
     * read, so anyone may ask for them, as on the web.
     */
    data class Offers(
        /** A folder has no plain Download; this is how one comes to the phone. */
        val zipDownload: Boolean = false,
        val compress: Boolean = false,
        val extract: Boolean = false,
        val checksum: Boolean = false,
        val thumbnails: Boolean = false,
    ) {
        val any get() = zipDownload || compress || extract || checksum || thumbnails
    }

    fun offersFor(entry: FileEntry, canWrite: Boolean, available: Boolean): Offers =
        if (!available) Offers()
        else Offers(
            zipDownload = entry.isDirectory,
            compress = canWrite,
            extract = canWrite && isZip(entry),
            checksum = !entry.isDirectory,
            thumbnails = canWrite && entry.isDirectory,
        )

    /** For several items: one ZIP of them all, and a checksum of each when all are files. */
    fun offersForSelection(entries: List<FileEntry>, canWrite: Boolean, available: Boolean): Offers =
        if (!available || entries.isEmpty()) Offers()
        else Offers(
            zipDownload = true,
            compress = canWrite,
            checksum = entries.none { it.isDirectory },
        )

    /** The name a ZIP is offered under: the item's own name, or "Archive" for several. */
    fun archiveName(paths: List<String>): String {
        val single = paths.singleOrNull()?.substringAfterLast('/')
        val stem = single?.replace(Regex("\\.[^./]+$"), "")?.ifEmpty { null } ?: "Archive"
        return "$stem.zip"
    }

    /* ---- plumbing ----------------------------------------------------------- */

    private fun plural(n: Long, word: String) = "$n $word${if (n == 1L) "" else "s"}"

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull
}
