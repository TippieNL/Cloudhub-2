package nl.tippie.cloudhub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.FileEntry

@Composable
fun TextPrompt(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it },
                label = { Text(label) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = value.isNotBlank() && value.trim() != initial,
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Choose a destination by browsing, not by typing a path.
 *
 * Folders only: a file is never a valid destination, so it is not offered.
 */
@Composable
fun FolderPicker(
    api: CloudHubApi,
    startAt: String,
    title: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var path by remember { mutableStateOf(startAt) }
    var folders by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(path) {
        loading = true; error = null
        try {
            folders = api.list(path).filter { it.isDirectory }
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a folder") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (path != "/") {
                        IconButton(onClick = { path = path.substringBeforeLast('/', "").ifEmpty { "/" } }) {
                            Icon(Icons.Default.ArrowUpward, "Up one folder")
                        }
                    }
                    Text(if (path == "/") "Root" else path,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
                HorizontalDivider()
                when {
                    loading -> Box(Modifier.fillMaxWidth().padding(20.dp), Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(12.dp))
                    folders.isEmpty() -> Text("No folders here. Items will go into this one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp))
                    else -> LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(folders, key = { it.path }) { folder ->
                            ListItem(
                                headlineContent = { Text(folder.name, maxLines = 1) },
                                leadingContent = { Icon(Icons.Default.Folder, null) },
                                modifier = Modifier.clickable { path = folder.path },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(path) }) { Text(title) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileActionsSheet(
    entry: FileEntry,
    canWrite: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(entry.name, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), maxLines = 2)
        HorizontalDivider()
        Action(Icons.Default.OpenInNew, if (entry.isDirectory) "Open" else "Preview", onOpen)
        if (!entry.isDirectory) {
            Action(Icons.Default.Download, "Download", onDownload)
            Action(Icons.Default.Link, "Share link", onShare)
        }
        if (canWrite) {
            Action(Icons.Default.DriveFileRenameOutline, "Rename", onRename)
            Action(Icons.Default.DriveFileMove, "Move to…", onMove)
            Action(Icons.Default.ContentCopy, "Copy to…", onCopy)
            Action(Icons.Default.Delete, "Delete", onDelete, danger = true)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Action(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    ListItem(
        headlineContent = {
            Text(label, color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified)
        },
        leadingContent = {
            Icon(icon, null, tint = if (danger) MaterialTheme.colorScheme.error else LocalContentColor.current)
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * A share link is a public URL: anyone holding it can open the file without an
 * account, so the link, its lifetime and the way to revoke it are all shown
 * rather than the link being silently copied.
 */
@Composable
fun ShareDialog(
    api: CloudHubApi,
    entry: FileEntry,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
) {
    var link by remember { mutableStateOf<nl.tippie.cloudhub.net.ShareLink?>(null) }
    var hours by remember { mutableStateOf<Int?>(24) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun create() {
        busy = true; error = null
        scope.launch {
            try {
                link = api.createShare(entry.path, hours)
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) { create() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share link") },
        text = {
            Column {
                Text("Anyone with this link can view ${entry.name} without signing in.",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                when {
                    busy -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    link != null -> {
                        SelectionContainerCompat { Text(link!!.url, style = MaterialTheme.typography.bodySmall) }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            link!!.expiresAt?.let { "Expires $it" } ?: "Does not expire",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { link?.url?.let(onCopy) }, enabled = link != null) { Text("Copy link") }
        },
        dismissButton = {
            Row {
                link?.let { existing ->
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { api.revokeShare(existing.token) }
                            onDismiss()
                        }
                    }) { Text("Revoke") }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@Composable
private fun SelectionContainerCompat(content: @Composable () -> Unit) =
    androidx.compose.foundation.text.selection.SelectionContainer { content() }
