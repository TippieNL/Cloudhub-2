package nl.tippie.cloudhub.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import nl.tippie.cloudhub.net.ApiError
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.DuplicateGroup
import nl.tippie.cloudhub.net.DuplicateScan

/**
 * The same photo, twice.
 *
 * A phone backing up beside a manual copy, a folder duplicated "just in case",
 * a holiday imported from two cameras. The server finds files that are
 * byte-for-byte identical -- never merely similar -- so what is offered here
 * is safe to delete.
 *
 * The scan is a poll loop rather than one request: hashing a library does not
 * finish inside the time a phone will wait for a reply, so the server does a
 * slice of the work per request and says how far it has got. Groups appear as
 * they are confirmed, which means the screen is useful before the scan ends.
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
    var scan by remember { mutableStateOf<DuplicateScan?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    /** Null while it has not been asked; false on a server without the feature. */
    var supported by remember { mutableStateOf<Boolean?>(null) }
    var minBytes by remember { mutableStateOf<Long?>(null) }
    /** True when the poll loop gave up rather than the scan finishing. */
    var gaveUp by remember { mutableStateOf(false) }
    /** Per group, the copy to keep: the oldest until changed. */
    val keeping = remember { mutableStateMapOf<String, String>() }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirming by remember { mutableStateOf(false) }
    /** All, Photos, Videos or Other: what is shown, never what is scanned. */
    var chosen by rememberSaveable { mutableStateOf(FavoriteRules.Filter.ALL) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    /**
     * Ask for slices until the server says it is done.
     *
     * Every reply carries everything found so far, so each one is shown rather
     * than waiting for the end. Leaving the screen cancels the coroutine and
     * with it the loop; the server keeps the scan, and opening the screen
     * again reads it back without repeating the work.
     */
    suspend fun runScan() {
        busy = true
        error = null
        gaveUp = false
        try {
            var slices = 1
            var latest = withContext(Dispatchers.IO) { api.startDuplicateScan() }
            scan = latest
            keeping.clear()
            selected = emptySet()
            while (DuplicateRules.shouldContinue(latest, slices)) {
                latest = withContext(Dispatchers.IO) { api.continueDuplicateScan() }
                scan = latest
                slices++
            }
            gaveUp = !latest.done
        } catch (e: ApiError) {
            // A viewer may read a scan but not start one, which is the server
            // saying something specific rather than something going wrong.
            error = if (e.status == 403) "Your account can see a scan but not start one." else e.message
        } catch (e: Exception) {
            error = e.message
        } finally {
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        busy = true
        try {
            // The limits, and whether this build has the feature at all: a
            // server without a duplicate finder does not publish them.
            val config = withContext(Dispatchers.IO) { api.config() }
            supported = config.duplicateScanSeconds != null
            minBytes = config.duplicateMinBytes
            if (supported == true) {
                val last = withContext(Dispatchers.IO) { api.lastDuplicateScan() }
                scan = last
                // Nothing has ever been scanned here. Starting one is what the
                // screen is for -- but only an account that may.
                if (!last.started && canWrite) runScan() else busy = false
            } else {
                busy = false
            }
        } catch (e: Exception) {
            error = e.message
            supported = supported ?: false
            busy = false
        }
    }

    // Everything the scan found, and the part of it the filter shows. Every
    // action below -- selecting the extras, the count, Move to trash and the
    // check that no set is emptied -- works on `groups`, the sets on show.
    val found = scan?.groups.orEmpty()
    val filter = DuplicateRules.effective(chosen, found)
    val groups = DuplicateRules.shown(found, filter)
    val picked = DuplicateRules.selectedIn(groups, selected)
    val freed = DuplicateRules.freedBy(groups, selected)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Duplicates", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    // Selecting every extra copy in one go, which is the whole
                    // point of the screen for anyone with a lot of them. The
                    // extras of what is on show; a choice made under another
                    // filter is left as it was.
                    IconButton(
                        onClick = { selected = selected + DuplicateRules.removableIn(groups, keeping) },
                        enabled = canWrite && groups.isNotEmpty(),
                    ) { Icon(Icons.Default.DoneAll, "Select every extra copy") }
                    IconButton(
                        onClick = { scope.launch { runScan() } },
                        enabled = canWrite && !busy && supported == true,
                    ) { Icon(Icons.Default.Refresh, "Scan again") }
                },
            )
        },
        bottomBar = {
            AnimatedVisibility(visible = picked.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${picked.size} selected", fontWeight = FontWeight.Medium)
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
            Header(scan, groups, busy, error, gaveUp, minBytes, canWrite, supported)

            // The same four as Favorites, counted in sets. One with nothing in
            // it is shown, so the counts add up, but cannot be chosen.
            if (found.isNotEmpty()) {
                val counts = DuplicateRules.counts(found)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FavoriteRules.Filter.entries.forEach { option ->
                        val count = counts[option] ?: 0
                        FilterChip(
                            selected = option == filter,
                            onClick = { chosen = option },
                            enabled = option == FavoriteRules.Filter.ALL || count > 0,
                            label = { Text("${option.label} ($count)") },
                        )
                    }
                }
            }

            when {
                supported == false -> Message(
                    "This server does not have the duplicate finder. It needs a newer " +
                        "CloudHub than the one it is running."
                )

                busy && found.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }

                scan?.started == false -> Message(
                    if (canWrite) "Nothing has been scanned yet. Tap the refresh button to start."
                    else "Nothing has been scanned yet, and your account cannot start a scan."
                )

                // A filter with nothing in it falls back to All, so this is
                // the scan having found nothing at all.
                groups.isEmpty() && error == null ->
                    Message("Nothing here is stored twice.")

                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(groups, key = { DuplicateRules.groupId(it) }) { group ->
                        GroupCard(
                            api = api,
                            group = group,
                            canWrite = canWrite,
                            keeping = DuplicateRules.keeperFor(group, keeping[DuplicateRules.groupId(group)]),
                            selected = selected,
                            onKeep = { path ->
                                keeping[DuplicateRules.groupId(group)] = path
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
        // Against the sets on show, which are the ones a delete from here
        // touches: a set is of one kind, so it is all on show or not at all.
        val emptied = DuplicateRules.wouldEmptyAGroup(groups, selected)
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Move ${picked.size} to the trash?") },
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
                        // What is ticked among the sets on show, and nothing
                        // ticked under another filter.
                        val going = picked
                        scope.launch {
                            // One request per file: the server has no bulk
                            // delete, and each of these is an ordinary delete
                            // that goes to the trash and is audited.
                            val gone = withContext(Dispatchers.IO) {
                                going.filter { path -> runCatching { api.delete(path) }.isSuccess }.toSet()
                            }
                            // The scan is not re-run for this: what was deleted
                            // is known, so the list is brought up to date here
                            // rather than hashing the library again.
                            scan = scan?.let { DuplicateRules.without(it, gone) }
                            // A choice made under another filter outlives this one.
                            selected = selected - going
                            snackbar.showSnackbar(
                                if (gone.size == going.size) "Moved ${gone.size} to the trash"
                                else "${going.size - gone.size} could not be deleted"
                            )
                        }
                    },
                ) { Text("Move to trash") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Message(text: String) {
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
private fun Header(
    scan: DuplicateScan?,
    /** The sets the filter shows, which is what the summary counts. */
    shown: List<DuplicateGroup>,
    busy: Boolean,
    error: String?,
    gaveUp: Boolean,
    minBytes: Long?,
    canWrite: Boolean,
    supported: Boolean?,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            DuplicateRules.summary(shown.size, shown.sumOf { it.reclaimable }) { humanBytes(it) },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            buildString {
                append("Files that are byte for byte identical. A photo saved again at another ")
                append("size is a different file and is not counted.")
                // Read from the server rather than assumed: it is why a folder
                // of tiny files never appears here.
                minBytes?.takeIf { it > 0 }?.let { append(" Anything under ${humanBytes(it)} is skipped.") }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        if (scan != null && supported == true && scan.started) {
            Text(
                DuplicateRules.activity(scan),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            // A determinate bar once there is something to be determinate
            // about; the first slice walks the tree and hashes nothing.
            if (busy) {
                val fraction = DuplicateRules.progress(scan)
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }

        // A scan that hit the server's file limit has found real duplicates
        // but not necessarily all of them, and saying so is cheaper than
        // being wrong.
        if (scan?.truncated == true) {
            Notice("The store has more files than one scan covers, so there may be more duplicates.")
        }
        if (gaveUp) {
            Notice("The scan did not finish. What is listed is real; scan again to carry on.")
        }
        if (!canWrite && supported == true) {
            Notice("Your account can see duplicates but not start a scan or delete anything.")
        }
        error?.let { Notice(it) }
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 6.dp),
    )
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
                "${group.count} copies · ${humanBytes(group.bytes)} each",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${humanBytes(group.reclaimable)} to reclaim",
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
                        Text(
                            DuplicateRules.nameOf(file.path),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val folder = DuplicateRules.folderOf(file.path)
                        Text(
                            if (kept) "In $folder · kept" else "In $folder",
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
