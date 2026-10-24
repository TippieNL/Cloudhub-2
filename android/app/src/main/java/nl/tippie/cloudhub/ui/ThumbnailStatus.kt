package nl.tippie.cloudhub.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import nl.tippie.cloudhub.data.Settings
import nl.tippie.cloudhub.net.ApiError
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.ThumbnailScanProgress
import nl.tippie.cloudhub.work.ThumbnailScanWorker

/** What Settings shows about thumbnails. */
data class ThumbnailStatus(
    /** How far the scan has got -- null while nothing is known. */
    val scan: ThumbnailScanProgress?,
    val running: Boolean,
    /** Asked for and not begun yet: it waits for the phone to be online. */
    val waiting: Boolean = false,
    /** And, being the automatic run rather than one asked for, for a battery that is not low. */
    val waitsForBattery: Boolean = false,
    /** False for a server without the scan, which makes thumbnails only as photos are shown; null until known. */
    val supported: Boolean? = null,
    /** Why the last run stopped short, until a run gets an answer again. */
    val error: String? = null,
)

/**
 * The worker's own progress while it runs -- it reports after every slice --
 * and the server's account otherwise: asked when the screen opens and again
 * whenever the worker stops, so a scan run from another phone, or from the
 * server's own tool, shows here too.
 */
@Composable
fun rememberThumbnailStatus(api: CloudHubApi, settings: Settings): ThumbnailStatus {
    val context = LocalContext.current
    val work = remember(context) { WorkManager.getInstance(context) }
    // Built once: a flow made during composition is a new subscription to
    // WorkManager's database on every recomposition.
    val infos by remember(work) { work.getWorkInfosForUniqueWorkFlow(ThumbnailScanWorker.WORK) }
        .collectAsState(initial = emptyList())
    val info = infos.firstOrNull { !it.state.isFinished } ?: infos.lastOrNull()
    val running = info?.state == WorkInfo.State.RUNNING
    // Enqueued and never run: held back by its constraints. Enqueued after a
    // run is a retry waiting its turn, and the server's account says more.
    val waiting = info?.state == WorkInfo.State.ENQUEUED && info.runAttemptCount == 0
    val waitsForBattery = waiting && info?.constraints?.requiresBatteryNotLow() == true

    var server by remember { mutableStateOf<ThumbnailScanProgress?>(null) }
    var supported by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(running) {
        if (running) return@LaunchedEffect
        try {
            server = api.thumbnailScan()
            supported = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            // A server from before the scan has no such route.
            if (e.status == 404) supported = false
        } catch (_: Exception) {
            // Offline: nothing more is known than before.
        }
    }
    // Read again whenever the work moves on: the worker writes it as it stops.
    val error = remember(info?.state, info?.runAttemptCount, server) { settings.thumbnailScanError }
    val live = if (running) info?.let { ThumbnailScanWorker.progressOf(it.progress) } else null
    return ThumbnailStatus(
        scan = live ?: server, running = running, waiting = waiting, waitsForBattery = waitsForBattery,
        supported = supported, error = error,
    )
}
