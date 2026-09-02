package nl.tippie.cloudhub.work

import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nl.tippie.cloudhub.CloudHubApp
import nl.tippie.cloudhub.net.ApiError
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One queued upload.
 *
 * The bytes are copied into app-private cache when the upload is queued rather
 * than kept as a content:// URI: a grant from the share sheet is frequently
 * not persistable, so by the time the worker runs the URI can be dead. The
 * copy is deleted once the server has the file.
 */
@Serializable
data class QueuedUpload(
    val id: String,
    val name: String,
    val cachePath: String,
    val targetPath: String,
    val size: Long,
    val queuedAt: Long = System.currentTimeMillis(),
)

/**
 * An upload the server refused for good.
 *
 * Over quota, too large or forbidden: retrying cannot help, so the item is
 * dropped -- but it used to be dropped *silently*, and a file you were told was
 * queued simply never arrived with nothing anywhere to say why. Kept until it
 * is dismissed.
 */
@Serializable
data class UploadFailure(
    val name: String,
    val reason: String,
    val at: Long = System.currentTimeMillis(),
)

/** What staging a picked file did: it worked, the phone is full, or it could not be read. */
sealed interface StageResult {
    data class Staged(val upload: QueuedUpload) : StageResult
    data class NoRoom(val needed: Long, val free: Long) : StageResult
    data object Unreadable : StageResult
}

/**
 * Whether there is room to copy a file into app storage.
 *
 * Pure, so the decision can be tested without filling a disk.
 */
object StagingSpace {
    /** Left free after a staged copy, so the app is not the thing that fills the phone. */
    const val HEADROOM_BYTES = 64L * 1024 * 1024

    fun hasRoom(freeBytes: Long, neededBytes: Long): Boolean {
        // A provider that will not say how big the file is gets the benefit of
        // the doubt: the copy itself fails cleanly if it runs out.
        if (neededBytes < 0) return freeBytes > HEADROOM_BYTES
        return freeBytes - neededBytes >= HEADROOM_BYTES
    }
}

/**
 * The queue itself, persisted as one small file.
 *
 * A database would be more than a list of a few dozen records needs, and would
 * mean adding an annotation processor to the build for it.
 */
class UploadQueue(context: Context) {
    private val file = File(context.filesDir, "upload-queue.json")
    private val failureFile = File(context.filesDir, "upload-failures.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized fun all(): List<QueuedUpload> =
        if (!file.exists()) emptyList()
        else runCatching { json.decodeFromString<List<QueuedUpload>>(file.readText()) }.getOrDefault(emptyList())

    @Synchronized fun add(item: QueuedUpload) = write(all() + item)

    @Synchronized fun remove(id: String) = write(all().filterNot { it.id == id })

    /* ---- refusals -------------------------------------------------------
     *
     * A separate small file rather than a field on the queue: a refused upload
     * has left the queue by definition, and the tracker still has to be able
     * to say what happened to it.
     */

    @Synchronized fun failures(): List<UploadFailure> =
        if (!failureFile.exists()) emptyList()
        else runCatching { json.decodeFromString<List<UploadFailure>>(failureFile.readText()) }
            .getOrDefault(emptyList())

    @Synchronized fun recordFailure(failure: UploadFailure) {
        // Bounded: a queue stuck against a full quota would otherwise write a
        // record per attempt until the disk noticed.
        val kept = (failures() + failure).takeLast(MAX_FAILURES)
        runCatching { failureFile.writeText(json.encodeToString(kept)) }
    }

    @Synchronized fun clearFailures() { runCatching { failureFile.delete() } }

    private fun write(items: List<QueuedUpload>) {
        runCatching { file.writeText(json.encodeToString(items)) }
    }

    private companion object {
        const val MAX_FAILURES = 20
    }
}

/**
 * Sends queued uploads, resuming from what the server confirms.
 *
 * Runs under WorkManager so an upload outlives the app being closed -- better
 * than the web client can manage, which needs a page open to make progress.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as CloudHubApp
        val queue = UploadQueue(applicationContext)
        val api = app.api

        // The token can be stale after a restart; refreshing it is one request
        // and saves every write in this run failing with 419.
        runCatching { api.status() }.getOrElse { return Result.retry() }

        ensureChannel(applicationContext)
        // The batch's size, taken once: files leave the queue as they finish,
        // so measuring per item would make the notification go backwards.
        val batchBytes = queue.all().sumOf { it.size.coerceAtLeast(0) }
        var doneBytes = 0L

        for (item in queue.all()) {
            val source = File(item.cachePath)
            if (!source.isFile) {
                queue.remove(item.id)
                continue
            }
            try {
                var status = api.uploadInit(item.id, item.targetPath, item.name, item.size)
                var offset = status.received.coerceAtMost(item.size)

                while (offset < item.size) {
                    /*
                     * Asked again every chunk, not once: the point is to
                     * notice a video being opened part-way through an upload,
                     * and to speed back up the moment it is closed.
                     */
                    val watching = ForegroundMedia.inUse
                    val slice = UploadPacing.chunkBytes(status.chunkBytes, watching)
                    val end = minOf(offset + slice, item.size)
                    // Streamed from the file: a 4 GB video must not need 4 GB
                    // of heap to upload.
                    status = api.uploadChunk(item.id, offset, source.slice(offset, end))
                    if (status.received <= offset) return Result.retry()   // no progress; back off
                    offset = status.received
                    setProgress(workDataOf("id" to item.id, "sent" to offset, "total" to item.size))
                    notify(doneBytes + offset, batchBytes, queue.all().size, item.name)
                    // Leaves the link mostly free for whatever is on screen.
                    // Cancellable, so stopping the work is still immediate.
                    val pause = UploadPacing.pauseMillis(watching)
                    if (pause > 0) delay(pause)
                }

                api.uploadComplete(item.id)
                queue.remove(item.id)
                source.delete()
                doneBytes += item.size
            } catch (e: ApiError) {
                clearNotification()
                // Over quota, too large or forbidden: retrying changes nothing,
                // so the item is dropped rather than left cycling forever --
                // but recorded on the way out. Dropping it silently meant a
                // file you were told was queued never arrived, with nothing
                // anywhere to explain it.
                if (e.isOutOfSpace || e.status == 413 || e.isForbidden) {
                    queue.recordFailure(
                        UploadFailure(item.name, e.message ?: "The server refused this file"),
                    )
                    queue.remove(item.id)
                    source.delete()
                    continue
                }
                return Result.retry()
            } catch (e: Exception) {
                // The notification is ongoing; leaving it up over a network
                // outage would look like an upload that never moves.
                clearNotification()
                return Result.retry()   // the network went away; try again later
            }
        }
        clearNotification()
        return Result.success()
    }

    /* ---- the notification -------------------------------------------------
     *
     * A plain notification, updated as chunks land and cancelled when the queue
     * empties -- not a foreground service. setForeground() would additionally
     * need FOREGROUND_SERVICE_DATA_SYNC and a service type on targetSdk 34, and
     * it buys expedited scheduling this does not need; an ordinary notification
     * shows the same progress for one permission instead of two.
     *
     * Every call is best-effort. POST_NOTIFICATIONS can be refused, and an
     * upload must not fail because nobody wanted to be told about it.
     */

    private fun notify(done: Long, total: Long, filesLeft: Int, name: String?) {
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) return

        val percent = if (total <= 0) 0 else ((done * 100) / total).toInt().coerceIn(0, 100)
        val text = buildString {
            name?.let { append(it) }
            if (filesLeft > 1) {
                if (isNotEmpty()) append(" · ")
                append("$filesLeft files left")
            }
        }.ifEmpty { "Uploading" }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Uploading to CloudHub — $percent%")
            .setContentText(text)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun clearNotification() {
        runCatching { NotificationManagerCompat.from(applicationContext).cancel(NOTIFICATION_ID) }
    }

    companion object {
        /** The unique work name, also how the tracker finds this worker. */
        const val WORK = "cloudhub-uploads"

        private const val CHANNEL = "cloudhub-uploads"
        private const val NOTIFICATION_ID = 4021

        /**
         * Created once, before anything is posted.
         *
         * On API 26+ a notification to a channel that does not exist is
         * dropped silently, which is a maddening thing to debug.
         */
        fun ensureChannel(context: Context) {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
            val channel = android.app.NotificationChannel(
                CHANNEL, "Uploads", android.app.NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Progress while files are being uploaded" }
            runCatching {
                context.getSystemService(android.app.NotificationManager::class.java)
                    ?.createNotificationChannel(channel)
            }
        }

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK,
                // APPEND_OR_REPLACE would start a second pass over the same
                // queue; one worker draining it in order is what we want.
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<UploadWorker>()
                    .setConstraints(Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                    .build(),
            )
        }

        /**
         * Copy the bytes somewhere they will still exist when the worker runs.
         *
         * A picker URI's grant cannot be made persistable, so copying is what
         * lets an upload survive the app closing -- but it also means a 4 GB
         * clip needs 4 GB free while it is staged. The space is checked before
         * the copy starts, so a phone that is full says so rather than failing
         * part-way through with an IOException nobody can act on.
         */
        fun stage(context: Context, uri: Uri, name: String): StageResult {
            val dir = File(context.filesDir, "uploads").apply { mkdirs() }
            val needed = sizeOf(context, uri)
            val free = runCatching { dir.usableSpace }.getOrDefault(0L)
            if (!StagingSpace.hasRoom(free, needed)) return StageResult.NoRoom(needed, free)

            val id = "a" + java.util.UUID.randomUUID().toString().replace("-", "").take(20)
            val target = File(dir, id)
            return runCatching {
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                StageResult.Staged(QueuedUpload(id, name, target.absolutePath, "/", target.length()))
            }.getOrElse {
                // Half a file left in filesDir is invisible and never collected.
                target.delete()
                if (it is java.io.IOException && dir.usableSpace < StagingSpace.HEADROOM_BYTES)
                    StageResult.NoRoom(needed, 0L)
                else StageResult.Unreadable
            }
        }

        /** What the provider says the file weighs, or -1 when it will not say. */
        private fun sizeOf(context: Context, uri: Uri): Long {
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                        return cursor.getLong(index)
                    }
                }
            }
            runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
                    if (fd.length >= 0) return fd.length
                }
            }
            return -1L
        }
    }
}

/**
 * A RequestBody over one slice of a file, streamed rather than buffered.
 *
 * Reading the slice into a ByteArray first would mean holding a chunk of the
 * video in memory on top of what OkHttp needs; on a phone with a 4 GB file
 * that is the difference between working and being killed.
 */
private fun File.slice(start: Long, end: Long): RequestBody = object : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaTypeOrNull()

    override fun contentLength() = end - start

    override fun writeTo(sink: BufferedSink) {
        inputStream().use { stream ->
            // skip() may return short; loop until the offset is reached.
            var skipped = 0L
            while (skipped < start) {
                val n = stream.skip(start - skipped)
                if (n <= 0) throw java.io.IOException("Could not seek to $start in $name")
                skipped += n
            }
            val buffer = ByteArray(64 * 1024)
            var remaining = end - start
            while (remaining > 0) {
                val wanted = minOf(buffer.size.toLong(), remaining).toInt()
                val read = stream.read(buffer, 0, wanted)
                if (read <= 0) throw java.io.IOException("File ended early while uploading $name")
                sink.write(buffer, 0, read)
                remaining -= read
            }
        }
    }
}
