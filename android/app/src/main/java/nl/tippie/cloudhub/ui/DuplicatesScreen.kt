package nl.tippie.cloudhub.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.DuplicateGroup
import nl.tippie.cloudhub.net.DuplicateReport

/**
 * The same photo, twice.
 *
 * A phone backing up beside a manual copy, a folder duplicated "just in case",
 * a holiday imported from two cameras. The server finds files that are
 * byte-for-byte identical -- never merely similar -- so what is offered here
 * is safe to delete.
 *
 * Nothing is deleted without being asked for, every group always keeps a copy,
 * and what is deleted goes to the trash like any other delete, so a mistake is
 * recoverable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(
    api: CloudHubApi,
    canWrite: Boolean,
    onBack: () -> Unit,
) {
    var report by remember { mutableStateOf<DuplicateReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var everything by remember { mutableStateOf(false) }
    /** Per group, the copy to keep: the server's suggestion until changed. */
    val keeping = remember { mutableStateMapOf<String, String>() }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirming by remember { mutableStateOf(false) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    suspend fun load(refresh: Boolean) {
        busy = true
        error = null
        try {
            val found = withContext(Dispatchers.IO) { api.duplicates(refresh, everything) }
            report = found
            keeping.clear()
            selected = emptySet()
        } catch (e: Exception) {
            error = e.message
        } finally {
            busy = false
        }
    }

    LaunchedEffect(everything) { load(refresh = false) }

    val groups = report?.groups.orEmpty()
    val freed = DuplicateRules.freedBy(groups, selected)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Duplicates", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    // Selecting every extra copy in one go, which is the whole
                    // point of the screen for anyone with a lot of them.
                    IconButton(
                        onClick = { selected = DuplicateRules.removableIn(groups, keeping).toSet() },
                        enabled = canWrite && groups.isNotEmpty(),
                    ) { Icon(Icons.Default.DoneAll, "Select every extra copy") }
                    IconButton(onClick = { scope.launch { load(refresh = true) } }, enabled = !busy) {
                        Icon(Icons.Default.Refresh, "Scan again")
                    }
                },
            )
        },
        bottomBar = {
            AnimatedVisibility(visible = selected.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${selected.size} selected", fontWeight = FontWeight.Medium)
                            Text(
                                "${humanBytes(freed)} to reclaim",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(onClick = { confirming = true }) {
                            Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Move to trash")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Header(report, busy, error, everything, onScope = { everything = it })

            when {
                busy && report == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }

                groups.isEmpty() && error == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        if (everything) "Nothing in the store is stored twice."
                        else "No photo or video is stored twice.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(groups, key = { it.hash }) { group ->
                        GroupCard(
                            api = api,
                            group = group,
                            canWrite = canWrite,
                            keeping = keeping[group.hash] ?: group.keep,
                            selected = selected,
                            onKeep = { path ->
                                keeping[group.hash] = path
                                // The copy being kept cannot also be deleted.
                                selected = selected - path
                            },
                            onToggle = { path ->
                                selected = if (path in selected) selected - path else selected + path
                            },
                        )
                    }
                }
            }
        }
    }

    if (confirming) {
        val emptied = DuplicateRules.wouldEmptyAGroup(groups, selected)
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Move ${selected.size} to the trash?") },
            text = {
                Text(
                    if (emptied) {
                        // The one thing this screen must never do quietly.
                        "One set has every copy selected, which would delete the file itself. " +
                            "Leave one copy of each unselected."
                    } else {
                        "${humanBytes(freed)} will be reclaimed. Every set keeps a copy, and " +
                            "anything moved to the trash can be restored from there."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !emptied,
                    onClick = {
                        confirming = false
                        scope.launch {
                            val failures = withContext(Dispatchers.IO) {
                                selected.count { path -> runCatching { api.delete(path) }.isFailure }
                            }
                            snackbar.showSnackbar(
                                if (failures == 0) "Moved ${selected.size} to the trash"
                                else "$failures could not be deleted"
                            )
                            load(refresh = true)
                        }
                    },
                ) { Text("Move to trash") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Header(
    report: DuplicateReport?,
    busy: Boolean,
    error: String?,
    everything: Boolean,
    onScope: (Boolean) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            DuplicateRules.summary(report?.groupCount ?: 0, report?.wastedBytes ?: 0) { humanBytes(it) },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Files that are byte for byte identical. A photo saved again at another size is a " +
                "different file and is not counted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        // A scan that ran out of time has found real duplicates but not
        // necessarily all of them, and saying so is cheaper than being wrong.
        if (report?.complete == false) {
            Text(
                "The scan stopped early, so there may be more. Scan again to carry on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp))
        }

        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = !everything,
                onClick = { onScope(false) },
                label = { Text("Photos & videos") },
                enabled = !busy,
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = everything,
                onClick = { onScope(true) },
                label = { Text("Everything") },
                enabled = !busy,
            )
            if (busy) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun GroupCard(
    api: CloudHubApi,
    group: DuplicateGroup,
    canWrite: Boolean,
    keeping: String,
    selected: Set<String>,
    onKeep: (String) -> Unit,
    onToggle: (String) -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "${group.copies} copies · ${humanBytes(group.bytes)} each",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${humanBytes(group.wastedBytes)} to reclaim",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            group.files.forEach { file ->
                val kept = file.path == keeping
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = canWrite && !kept) { onToggle(file.path) }
                        .padding(vertical = 6.dp),
                ) {
                    AsyncImage(
                        model = api.thumbnailUrlFor(file.path).toString(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (kept) "In ${file.folder} · kept" else "In ${file.folder}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (kept) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (kept) {
                        TextButton(onClick = {}, enabled = false) { Text("Keep") }
                    } else {
                        Checkbox(
                            checked = file.path in selected,
                            onCheckedChange = { onToggle(file.path) },
                            enabled = canWrite,
                        )
                        TextButton(onClick = { onKeep(file.path) }, enabled = canWrite) { Text("Keep") }
                    }
                }
            }
        }
    }
}
