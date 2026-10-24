package nl.tippie.cloudhub.work

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import nl.tippie.cloudhub.CloudHubApp
import nl.tippie.cloudhub.net.ApiError
import nl.tippie.cloudhub.net.ThumbnailScanProgress
import nl.tippie.cloudhub.ui.ThumbnailRules
import java.io.IOException
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
        val app = applicationContext as CloudHubApp
        return try {
            scan(app)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            // Signed out, no write access, or a server without the scan: none
            // of that is helped by asking again.
            if (e.status in GIVE_UP) Result.success() else stopped(app, e.message ?: "the server said ${e.status}")
        } catch (e: IOException) {
            stopped(app, "no connection to the server")
        } catch (e: Exception) {
            Log.w(TAG, "thumbnail scan: ${e.message}")
            stopped(app, e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun scan(app: CloudHubApp): Result {
        val api = app.api
        val began = SystemClock.elapsedRealtime()
        // The token can be stale after a restart, and every slice is a POST.
        api.status()
        // A server that keeps no thumbnails ahead of time says so by not
        // publishing the scan; there is nothing to ask it for.
        if (api.config().thumbnailScanSeconds == null) return Result.success()

        // Asked for until a slice has really run: an answer of busy means this
        // request did nothing, so a check that begins again has not begun yet.
        var restart = inputData.getBoolean(KEY_RESTART, false)
        var busyInARow = 0
        while (true) {
            val scan = api.continueThumbnailScan(restart)
            setProgress(scan.toData())
            if (app.settings.thumbnailScanError != null) app.settings.thumbnailScanError = null
            if (scan.busy) busyInARow++ else { busyInARow = 0; restart = false }
            when (ThumbnailRules.next(scan, SystemClock.elapsedRealtime() - began, busyInARow)) {
                ThumbnailRules.Next.ASK_AGAIN -> Unit
                // Someone else's slice holds the scan: another phone, the
                // server's own tool -- or this phone's last request, which the
                // server carries on with after the worker that sent it was
                // stopped. A slice lasts seconds; wait it out.
                ThumbnailRules.Next.WAIT -> delay(ThumbnailRules.BUSY_WAIT_MILLIS)
                ThumbnailRules.Next.DONE -> return Result.success(scan.toData())
                // Out of time for this run, or someone else's scan for a good
                // while: the place is kept on the server, and the next run
                // carries on from it.
                ThumbnailRules.Next.LATER -> return Result.retry()
            }
        }
    }

    /** Asked again later; until then Settings says why it stopped. */
    private fun stopped(app: CloudHubApp, reason: String): Result {
        app.settings.thumbnailScanError = reason
        return Result.retry()
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
         * Settings button -- replaces it with one that begins again, and runs
         * on a low battery too: it was asked for, and the phone's part is only
         * waiting on the server's answers.
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
                            .setRequiresBatteryNotLow(!restart)
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
