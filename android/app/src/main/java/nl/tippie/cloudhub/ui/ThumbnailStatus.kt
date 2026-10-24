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
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.ThumbnailScanProgress
import nl.tippie.cloudhub.work.ThumbnailScanWorker

/** What Settings shows about thumbnails. [scan] is null while nothing is known. */
data class ThumbnailStatus(val scan: ThumbnailScanProgress?, val running: Boolean)

/**
 * The worker's own progress while it runs -- it reports after every slice --
 * and the server's account otherwise: asked when the screen opens and again
 * whenever the worker stops, so a scan run from another phone, or from the
 * server's own tool, shows here too.
 */
@Composable
fun rememberThumbnailStatus(api: CloudHubApi): ThumbnailStatus {
    val context = LocalContext.current
    val work = remember(context) { WorkManager.getInstance(context) }
    // Built once: a flow made during composition is a new subscription to
    // WorkManager's database on every recomposition.
    val infos by remember(work) { work.getWorkInfosForUniqueWorkFlow(ThumbnailScanWorker.WORK) }
        .collectAsState(initial = emptyList())
    val info = infos.firstOrNull { !it.state.isFinished } ?: infos.lastOrNull()
    val running = info?.state == WorkInfo.State.RUNNING

    var server by remember { mutableStateOf<ThumbnailScanProgress?>(null) }
    LaunchedEffect(running) {
        if (!running) server = runCatching { api.thumbnailScan() }.getOrNull()
    }
    val live = if (running) info?.let { ThumbnailScanWorker.progressOf(it.progress) } else null
    return ThumbnailStatus(scan = live ?: server, running = running)
}
