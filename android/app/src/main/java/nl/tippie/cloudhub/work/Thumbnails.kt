package nl.tippie.cloudhub.work

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.CancellationException
import nl.tippie.cloudhub.CloudHubApp
import nl.tippie.cloudhub.net.ApiError
import nl.tippie.cloudhub.net.ThumbnailScanProgress
import nl.tippie.cloudhub.ui.ThumbnailRules
import java.util.concurrent.TimeUnit

/**
 * Keeps the server's thumbnail scan going until every photo in the store has
 * a thumbnail.
 *
 * Photos already on the server before thumbnails were kept get theirs this way,
 * without anyone opening their folders or uploading them again; new uploads get
 * one from the server as they arrive. The work is the server's -- this asks
 * for one slice at a time, about eight seconds of it, and reports progress as
 * it goes -- so the phone spends a request every few seconds, not its battery
 * on decoding. Asking when the scan finished less than a day ago costs one
 * request that does nothing.
 *
 * Only for accounts that may write: running the scan is a write-guarded
 * request, as it is on the web. Everyone else's thumbnails are still made the
 * first time they are shown.
 */
class ThumbnailScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val api = (applicationContext as CloudHubApp).api
        val began = SystemClock.elapsedRealtime()
        return try {
            // The token can be stale after a restart, and every slice is a POST.
            api.status()
            // A server that keeps no thumbnails ahead of time says so by not
            // publishing the scan; there is nothing to ask it for.
            if (api.config().thumbnailScanSeconds == null) return Result.success()

            var scan = api.continueThumbnailScan(restart = inputData.getBoolean(KEY_RESTART, false))
            setProgress(scan.toData())
            while (ThumbnailRules.shouldContinue(scan, SystemClock.elapsedRealtime() - began)) {
                scan = api.continueThumbnailScan()
                setProgress(scan.toData())
            }
            when {
                // Busy is someone else's scan -- another phone, the server's own
                // tool -- which will finish it; this one has nothing to add.
                scan.done || scan.busy -> Result.success(scan.toData())
                // Out of time for this run, not out of work: the place is kept
                // on the server, and the next run carries on from it.
                else -> Result.retry()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            // Signed out, no write access, or a server without the scan: none
            // of that is helped by asking again.
            if (e.status in GIVE_UP) Result.success() else Result.retry()
        } catch (e: Exception) {
            Log.w(TAG, "thumbnail scan: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        /** The unique work name, also how Settings finds this worker. */
        const val WORK = "cloudhub-thumbnails"
        private const val TAG = "CloudHubThumbnails"
        private const val KEY_RESTART = "restart"
        private val GIVE_UP = setOf(400, 401, 403, 404, 405, 419)

        /**
         * Make sure the scan is going. Kept when one is already queued or
         * running, so asking on every start costs nothing; [restart] -- the
         * Settings button -- replaces it with one that begins again.
         */
        fun enqueue(context: Context, restart: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK,
                if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ThumbnailScanWorker>()
                    .setInputData(workDataOf(KEY_RESTART to restart))
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .setRequiresBatteryNotLow(true)
                            .build()
                    )
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }

        /** What a running or finished worker last reported, for Settings. */
        fun progressOf(data: Data): ThumbnailScanProgress? {
            if (!data.hasKeyWithValueOfType<Boolean>("done")) return null
            return ThumbnailScanProgress(
                started = data.getBoolean("started", false),
                done = data.getBoolean("done", false),
                busy = data.getBoolean("busy", false),
                phase = data.getString("phase"),
                found = data.getInt("found", 0),
                processed = data.getInt("processed", 0),
                generated = data.getInt("generated", 0),
                valid = data.getInt("valid", 0),
                adopted = data.getInt("adopted", 0),
                failed = data.getInt("failed", 0),
                skipped = data.getInt("skipped", 0),
                removed = data.getInt("removed", 0),
                startedAt = data.getString("startedAt"),
                finishedAt = data.getString("finishedAt"),
            )
        }

        private fun ThumbnailScanProgress.toData(): Data = workDataOf(
            "started" to started, "done" to done, "busy" to busy, "phase" to phase,
            "found" to found, "processed" to processed, "generated" to generated, "valid" to valid,
            "adopted" to adopted, "failed" to failed, "skipped" to skipped, "removed" to removed,
            "startedAt" to startedAt, "finishedAt" to finishedAt,
        )
    }
}
