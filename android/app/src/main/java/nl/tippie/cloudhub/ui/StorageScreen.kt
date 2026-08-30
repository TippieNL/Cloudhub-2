package nl.tippie.cloudhub.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.MyStorage
import nl.tippie.cloudhub.net.ServerStorage

/**
 * How much space is left.
 *
 * Every account gets its own figures from /api/storage/me; an admin
 * additionally gets the whole-server report and the recalculate button, which
 * is the only thing that makes the server walk the entire store.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(api: CloudHubApi, onBack: () -> Unit) {
    var mine by remember { mutableStateOf<MyStorage?>(null) }
    var server by remember { mutableStateOf<ServerStorage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    suspend fun load(refresh: Boolean) {
        busy = true
        error = null
        try {
            val own = withContext(Dispatchers.IO) { api.myStorage() }
            mine = own
            // Only asked for when it will be allowed; a 403 here would replace
            // a screen that was working with an error about someone else's
            // figures.
            server = if (own.isAdmin) {
                runCatching { withContext(Dispatchers.IO) { api.serverStorage(refresh) } }.getOrNull()
            } else null
        } catch (e: Exception) {
            error = e.message ?: "Could not reach the server."
        } finally {
            busy = false
        }
    }

    LaunchedEffect(Unit) { load(refresh = false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    if (mine?.isAdmin == true) {
                        IconButton(
                            onClick = { scope.launch { load(refresh = true) } },
                            enabled = !busy,
                        ) { Icon(Icons.Default.Refresh, "Recalculate") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            when {
                error != null -> StorageError(error!!) { scope.launch { load(refresh = false) } }

                mine == null -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 80.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                else -> {
                    val m = mine!!
                    val reading = StorageMeter.of(
                        usedBytes = m.usedBytes,
                        quotaBytes = m.quotaBytes,
                        storeUsedBytes = m.storeUsedBytes,
                        storeLimitBytes = m.storageLimitBytes,
                        diskFreeBytes = m.diskFreeBytes,
                        diskTotalBytes = m.diskTotalBytes,
                    )

                    Spacer(Modifier.height(12.dp))
                    SpaceLeft(reading, busy)

                    Spacer(Modifier.height(24.dp))
                    Section("What is stored")
                    Line("Files", "${m.files}")
                    Line("Folders", "${m.folders}")
                    if (m.quotaBytes > 0) Line("Your files", humanBytes(m.usedBytes))
                    Line("In the trash", "${humanBytes(m.trash.bytes)} · ${m.trash.entries} item${plural(m.trash.entries)}")
                    // The history has a real cost, and this is where it shows.
                    Line("Previous versions", "${humanBytes(m.versions.bytes)} · ${m.versions.files} file${plural(m.versions.files)}")

                    Spacer(Modifier.height(24.dp))
                    Section("The disk")
                    Line("Free", humanBytes(m.diskFreeBytes))
                    Line("Total", humanBytes(m.diskTotalBytes))
                    if (m.storageLimitBytes > 0) Line("Store limit", humanBytes(m.storageLimitBytes))
                    if (m.quotaBytes > 0) Line("Your quota", humanBytes(m.quotaBytes))

                    server?.let { s ->
                        if (s.byUser.isNotEmpty()) {
                            Spacer(Modifier.height(24.dp))
                            Section("By account")
                            s.byUser.sortedByDescending { it.bytes }.forEach { row ->
                                Line(
                                    row.username ?: "unattributed",
                                    "${humanBytes(row.bytes)} · ${row.files} file${plural(row.files)}",
                                )
                            }
                        }
                    }

                    // Measuring is cached; saying so explains why a number can
                    // lag a big upload, and what the refresh button is for.
                    m.measuredAt?.let {
                        Spacer(Modifier.height(20.dp))
                        Text(
                            if (m.cached) "Measured $it. Figures are cached between measurements."
                            else "Measured just now.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

/**
 * The headline, and the bar under it.
 *
 * What the bar is measured against is [StorageMeter]'s decision, not this
 * composable's; all it does is say which one was chosen.
 */
@Composable
private fun SpaceLeft(reading: StorageMeter.Reading, busy: Boolean) {
    val reduceMotion = LocalReduceMotion.current
    val fraction by animateFloatAsState(
        reading.fraction,
        if (reduceMotion) tween(0) else tween(600),
        label = "fill",
    )
    val bar by animateColorAsState(
        if (reading.nearlyFull) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        tween(400),
        label = "bar",
    )

    Text(
        humanBytes(reading.remainingBytes),
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        "free",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(16.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(bar),
        )
    }

    Spacer(Modifier.height(10.dp))
    Text(
        buildString {
            append(humanBytes(reading.usedBytes))
            append(" of ")
            append(humanBytes(reading.totalBytes))
            append(" used · ")
            append(
                when (reading.against) {
                    StorageMeter.Against.QUOTA -> "your quota"
                    StorageMeter.Against.STORE -> "the server's limit"
                    // Naming it matters: with no limit set, the drive is the
                    // ceiling, and "unlimited" would be a comfortable lie.
                    StorageMeter.Against.DISK -> "the disk, with no limit set"
                }
            )
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    AnimatedVisibility(visible = reading.nearlyFull, enter = fadeIn(), exit = fadeOut()) {
        Text(
            "Nearly full. Emptying the trash or discarding old versions frees space.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 10.dp),
        )
    }

    AnimatedVisibility(visible = busy, enter = fadeIn(), exit = fadeOut()) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
    }
}

@Composable
private fun StorageError(reason: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.CloudOff, null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(52.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("Could not read storage", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            reason,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
internal fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
internal fun Line(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun plural(n: Int) = if (n == 1) "" else "s"
