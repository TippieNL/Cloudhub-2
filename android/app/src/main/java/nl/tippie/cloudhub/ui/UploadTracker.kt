package nl.tippie.cloudhub.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import nl.tippie.cloudhub.work.QueuedUpload
import nl.tippie.cloudhub.work.UploadFailure
import nl.tippie.cloudhub.work.UploadQueue
import nl.tippie.cloudhub.work.UploadWorker

/** Everything the tracker draws, gathered in one place. */
data class UploadState(
    val summary: UploadProgress.Summary = UploadProgress.Idle,
    val queued: List<QueuedUpload> = emptyList(),
    val failures: List<UploadFailure> = emptyList(),
    val currentId: String? = null,
    val currentSent: Long = 0,
    val justFinished: Boolean = false,
)

/**
 * Watch the upload queue.
 *
 * The worker already publishes an id, a byte count and a total on every chunk
 * -- it has since uploads were built, and nothing read it. That progress flow
 * is also the change signal for the queue file, which is otherwise not
 * observable: every tick is a reason to re-read it.
 */
@Composable
fun rememberUploadState(): UploadState {
    val context = LocalContext.current
    val queue = remember(context) { UploadQueue(context) }
    val work = remember(context) { WorkManager.getInstance(context) }

    // Held across recompositions: this is the denominator that must not shrink
    // when a finished file leaves the queue.
    var batchTotal by remember { mutableStateOf(0L) }
    var justFinished by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(UploadState()) }

    val infos by work.getWorkInfosForUniqueWorkFlow(UploadWorker.WORK)
        .map { list -> list.firstOrNull { it.state == WorkInfo.State.RUNNING } ?: list.lastOrNull() }
        .collectAsState(initial = null)

    // Keyed on the progress data as well as the info, so every chunk re-reads
    // the queue rather than only every state change.
    LaunchedEffect(infos, infos?.progress) {
        val progress = infos?.progress
        val currentId = progress?.getString("id")
        val sent = progress?.getLong("sent", 0L) ?: 0L

        val queued = queue.all()
        val failures = queue.failures()
        val remainingBytes = queued.sumOf { it.size.coerceAtLeast(0) }

        if (queued.isEmpty()) {
            // The batch is over. Show "finished" only if there was one -- an
            // app opened with an empty queue should say nothing at all.
            justFinished = batchTotal > 0
            batchTotal = 0
        } else {
            batchTotal = UploadProgress.batchTotal(batchTotal, remainingBytes)
            justFinished = false
        }

        state = UploadState(
            summary = UploadProgress.summarise(queued, currentId, sent, batchTotal),
            queued = queued,
            failures = failures,
            currentId = currentId,
            currentSent = sent,
            justFinished = justFinished,
        )
    }

    // "All uploads finished" is worth a glance, not a permanent fixture.
    LaunchedEffect(state.justFinished) {
        if (state.justFinished) {
            delay(FINISHED_LINGER_MS)
            justFinished = false
            state = state.copy(justFinished = false)
        }
    }

    return state
}

private const val FINISHED_LINGER_MS = 2_600L

/**
 * The bar, docked at the bottom of the files screen.
 *
 * Visible only while there is something to say: uploading, just finished, or
 * something was refused and nobody has acknowledged it yet.
 */
@Composable
fun UploadTracker(state: UploadState, onDismissFailures: () -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val visible = state.summary.active || state.justFinished || state.failures.isNotEmpty()

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(280)) { it } + fadeIn(tween(280)),
        exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(200)),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = state.queued.isNotEmpty() || state.failures.isNotEmpty()) {
                    expanded = true
                },
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusIcon(state)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        // Cross-faded, so finishing does not snap the label.
                        AnimatedContent(
                            targetState = headline(state),
                            transitionSpec = { fadeIn(tween(200)).togetherWith(fadeOut(tween(140))) },
                            label = "headline",
                        ) { text ->
                            Text(
                                text,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        detail(state)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (state.queued.isNotEmpty() || state.failures.isNotEmpty()) {
                        Icon(
                            Icons.Default.KeyboardArrowUp, "Show every upload",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                AnimatedVisibility(visible = state.summary.active) {
                    Spacer(Modifier.height(10.dp))
                    ProgressBar(state.summary.fraction)
                }
            }
        }
    }

    if (expanded) {
        UploadSheet(
            state = state,
            onDismiss = { expanded = false },
            onDismissFailures = { onDismissFailures(); expanded = false },
        )
    }
}

@Composable
private fun StatusIcon(state: UploadState) {
    val scheme = MaterialTheme.colorScheme
    val (icon, tint) = when {
        state.summary.active -> Icons.Default.UploadFile to scheme.primary
        state.justFinished -> Icons.Default.CheckCircle to scheme.primary
        else -> Icons.Default.ErrorOutline to scheme.error
    }
    Surface(shape = RoundedCornerShape(50), color = tint.copy(alpha = 0.12f)) {
        Icon(icon, null, tint = tint, modifier = Modifier.padding(8.dp).size(20.dp))
    }
}

@Composable
private fun ProgressBar(fraction: Float, height: Dp = 8.dp) {
    val reduceMotion = LocalReduceMotion.current
    val animated by animateFloatAsState(
        fraction,
        if (reduceMotion) tween(0) else tween(320),
        label = "upload",
    )
    val bar by animateColorAsState(MaterialTheme.colorScheme.primary, tween(300), label = "bar")

    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(bar),
        )
    }
}

private typealias Dp = androidx.compose.ui.unit.Dp

private fun headline(state: UploadState): String = when {
    state.summary.active -> {
        val percent = (state.summary.fraction * 100).toInt()
        "Uploading — $percent%"
    }
    state.justFinished -> "All uploads finished"
    state.failures.size == 1 -> "1 upload was refused"
    else -> "${state.failures.size} uploads were refused"
}

private fun detail(state: UploadState): String? = when {
    state.summary.active -> buildString {
        state.summary.currentName?.let { append(it); append(" · ") }
        append("${state.summary.filesLeft} file${plural(state.summary.filesLeft)} left · ")
        append(humanBytes(state.summary.doneBytes))
        append(" of ")
        append(humanBytes(state.summary.totalBytes))
    }
    state.justFinished -> null
    else -> "Tap to see why"
}

/**
 * Every file in the batch: the one in flight, the ones waiting, and anything
 * the server would not take.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UploadSheet(state: UploadState, onDismiss: () -> Unit, onDismissFailures: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Uploads",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        if (state.summary.active) {
            Text(
                "${humanBytes(state.summary.doneBytes)} of ${humanBytes(state.summary.totalBytes)} sent",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
        HorizontalDivider()

        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(state.queued, key = { it.id }) { item ->
                val inFlight = item.id == state.currentId
                ListItem(
                    headlineContent = {
                        Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Column {
                            Text(
                                if (inFlight) "${humanBytes(state.currentSent)} of ${humanBytes(item.size)}"
                                else "Waiting · ${humanBytes(item.size)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            // Only the file actually moving gets a bar; a row of
                            // empty bars for files that have not started reads
                            // as stalled rather than as queued.
                            if (inFlight) {
                                Spacer(Modifier.height(6.dp))
                                ProgressBar(
                                    if (item.size <= 0) 0f
                                    else (state.currentSent.toFloat() / item.size).coerceIn(0f, 1f),
                                    height = 5.dp,
                                )
                            }
                        }
                    },
                    leadingContent = {
                        Icon(
                            if (inFlight) Icons.Default.UploadFile else Icons.Default.Schedule,
                            null,
                            tint = if (inFlight) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }

            if (state.failures.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Text(
                        "Refused",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 2.dp),
                    )
                }
                items(state.failures, key = { it.at.toString() + it.name }) { failure ->
                    ListItem(
                        headlineContent = {
                            Text(failure.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        // The server's own words: written for a person, and the
                        // same text the web client shows.
                        supportingContent = {
                            Text(failure.reason, style = MaterialTheme.typography.bodySmall)
                        },
                        leadingContent = {
                            Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                        },
                    )
                }
            }
        }

        if (state.failures.isNotEmpty()) {
            HorizontalDivider()
            TextButton(
                onClick = onDismissFailures,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            ) { Text("Dismiss") }
        }

        if (state.queued.isEmpty() && state.failures.isEmpty()) {
            Text(
                "Nothing is waiting to upload.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}
