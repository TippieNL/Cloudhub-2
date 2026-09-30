package nl.tippie.cloudhub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nl.tippie.cloudhub.net.BackgroundTask
import nl.tippie.cloudhub.work.TaskCenter

/**
 * The account's background tasks: what is queued, what is running and how
 * far along, and what finished -- the same list as the web app's Tasks page.
 *
 * The list is followed while the screen is open and anything is queued or
 * running. A finished archive is downloaded from here; a failed or cancelled
 * task can be tried again, and a copy tried again carries on where it
 * stopped rather than starting over.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    tasks: TaskCenter,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val state by tasks.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Finished tasks are only listed while the screen is open.
    DisposableEffect(Unit) {
        tasks.setScreenOpen(true)
        onDispose { tasks.setScreenOpen(false) }
    }

    /** One button's work; a refusal is said, not swallowed. */
    fun act(block: suspend () -> String?) {
        scope.launch {
            val note = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "That did not work"
            }
            note?.let { snackbar.showSnackbar(it) }
        }
    }

    val list = state.list
    val finished = list.jobs.any { it.canRemove }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Tasks", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    if (state.available && finished) {
                        TextButton(onClick = {
                            act { tasks.clear().let { "$it task${if (it == 1) "" else "s"} removed" } }
                        }) { Text("Clear finished") }
                    }
                    IconButton(onClick = { tasks.refresh() }) { Icon(Icons.Default.Refresh, "Refresh") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                TaskRules.note(list),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            state.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                )
            }
            when {
                state.supported == null && state.error == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                !state.available -> Empty(
                    if (state.supported == false) "This server has no background tasks. It needs Cloudhub-web."
                    else "Copies, deletions and duplicate scans still work; they run while the app waits, as they did before."
                )
                // Until the first full list arrives, the one on hand holds
                // only what is running.
                !state.complete && list.jobs.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                list.jobs.isEmpty() -> Empty("No background tasks.")
                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(list.jobs, key = { it.id }) { task ->
                        TaskCard(
                            task = task,
                            onDownload = { tasks.download(task) },
                            onCancel = { act { tasks.cancel(task.id) } },
                            onRetry = { act { tasks.retry(task.id); "Queued again" } },
                            onRemove = { act { tasks.remove(task.id); null } },
                            onCopy = onCopy,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}

@Composable
private fun TaskCard(
    task: BackgroundTask,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                StatusLabel(task)
            }
            task.target?.takeIf { it.isNotBlank() }?.let {
                Text("in $it", style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }

            if (TaskRules.showsBar(task)) {
                val fraction = TaskRules.fraction(task)
                val bar = Modifier.fillMaxWidth().padding(top = 10.dp)
                if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = bar)
                else LinearProgressIndicator(bar)
            }
            val progress = TaskRules.progressText(task) { humanBytes(it) }
            if (progress.isNotEmpty() || task.currentItem != null) {
                Text(
                    listOfNotNull(progress.ifEmpty { null }, task.currentItem).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            task.error?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp))
            }
            TaskRules.resultText(task) { humanBytes(it) }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            }
            val failures = TaskRules.failures(task)
            if (failures.isNotEmpty()) {
                Column(Modifier.padding(top = 4.dp)) {
                    // A long list is summed up rather than pushing the card off the screen.
                    failures.take(MAX_FAILURES_SHOWN).forEach {
                        Text("${it.path}: ${it.message}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    if (failures.size > MAX_FAILURES_SHOWN) {
                        Text("and ${failures.size - MAX_FAILURES_SHOWN} more", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            val sums = TaskRules.checksums(task)
            if (sums.isNotEmpty()) {
                SelectionContainer(Modifier.padding(top = 6.dp)) {
                    Text(
                        TaskRules.checksumLines(sums),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            Text(
                TaskRules.timesText(task),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                modifier = Modifier.padding(top = 8.dp),
            )

            val anyAction = task.hasDownload || task.canCancel || task.canRetry || task.canRemove || sums.isNotEmpty()
            if (anyAction) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (sums.isNotEmpty()) TextButton(onClick = { onCopy(TaskRules.checksumLines(sums)) }) { Text("Copy") }
                    if (task.canCancel) TextButton(onClick = onCancel) { Text("Cancel") }
                    if (task.canRetry) TextButton(onClick = onRetry) { Text("Retry") }
                    if (task.canRemove) TextButton(onClick = onRemove) { Text("Remove") }
                    if (task.hasDownload) Button(onClick = onDownload) { Text("Download") }
                }
            }
        }
    }
}

@Composable
private fun StatusLabel(task: BackgroundTask) {
    val scheme = MaterialTheme.colorScheme
    val (background, foreground) = when {
        task.status == TaskRules.FAILED -> scheme.errorContainer to scheme.onErrorContainer
        task.status == TaskRules.COMPLETED -> scheme.primaryContainer to scheme.onPrimaryContainer
        TaskRules.isActive(task) -> scheme.secondaryContainer to scheme.onSecondaryContainer
        else -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    Surface(color = background, contentColor = foreground, shape = MaterialTheme.shapes.small) {
        Text(
            TaskRules.statusLabel(task),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * The SHA-256 of files asked for on this phone, shown as soon as it is ready:
 * a 64-character digest is no use in a snackbar, and the point of asking is
 * to compare it with another.
 */
@Composable
fun ChecksumDialog(task: BackgroundTask, onCopy: (String) -> Unit, onDismiss: () -> Unit) {
    val sums = TaskRules.checksums(task)
    val lines = TaskRules.checksumLines(sums)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SHA-256") },
        text = {
            // A checksum of a whole selection can be a long list.
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                sums.forEach { sum ->
                    Text(sum.path.substringAfterLast('/'), style = MaterialTheme.typography.labelLarge)
                    SelectionContainer {
                        Text(sum.hash, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 10.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCopy(lines); onDismiss() }) { Text("Copy") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private const val MAX_FAILURES_SHOWN = 5
