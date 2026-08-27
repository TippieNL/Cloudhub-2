package nl.tippie.cloudhub.work

import android.content.Context
import android.net.Uri
import androidx.work.*
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
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized fun all(): List<QueuedUpload> =
        if (!file.exists()) emptyList()
        else runCatching { json.decodeFromString<List<QueuedUpload>>(file.readText()) }.getOrDefault(emptyList())

    @Synchronized fun add(item: QueuedUpload) = write(all() + item)

    @Synchronized fun remove(id: String) = write(all().filterNot { it.id == id })

    private fun write(items: List<QueuedUpload>) {
        runCatching { file.writeText(json.encodeToString(items)) }
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
                    val end = minOf(offset + status.chunkBytes, item.size)
                    // Streamed from the file: a 4 GB video must not need 4 GB
                    // of heap to upload.
                    status = api.uploadChunk(item.id, offset, source.slice(offset, end))
                    if (status.received <= offset) return Result.retry()   // no progress; back off
                    offset = status.received
                    setProgress(workDataOf("id" to item.id, "sent" to offset, "total" to item.size))
                }

                api.uploadComplete(item.id)
                queue.remove(item.id)
                source.delete()
            } catch (e: ApiError) {
                // Over quota, too large or forbidden: retrying changes nothing,
                // so the item is dropped rather than left cycling forever.
                if (e.isOutOfSpace || e.status == 413 || e.isForbidden) {
                    queue.remove(item.id)
                    source.delete()
                    continue
                }
                return Result.retry()
            } catch (e: Exception) {
                return Result.retry()   // the network went away; try again later
            }
        }
        return Result.success()
    }

    companion object {
        private const val WORK = "cloudhub-uploads"

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
