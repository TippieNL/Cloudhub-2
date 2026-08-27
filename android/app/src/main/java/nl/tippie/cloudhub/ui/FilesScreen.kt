package nl.tippie.cloudhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.FileEntry
import java.util.Locale

/**
 * The file browser.
 *
 * Grid by default because a phone showing photos wants pictures, not rows;
 * the list view is there for folders full of documents where the name is what
 * you are scanning for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    api: CloudHubApi,
    model: FilesViewModel,
    onOpenFile: (FileEntry) -> Unit,
    onOpenTrash: () -> Unit,
    onSignOut: () -> Unit,
    onPickMedia: () -> Unit,
    onPickFile: () -> Unit,
    onTakePhoto: () -> Unit,
    onRecordVideo: () -> Unit,
    onDownload: (FileEntry) -> Unit,
    onShare: (FileEntry) -> Unit,
) {
    val state by model.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showNewFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<FileEntry?>(null) }
    var picking by remember { mutableStateOf<PickerRequest?>(null) }
    var menuFor by remember { mutableStateOf<FileEntry?>(null) }
    var overflow by remember { mutableStateOf(false) }

    LaunchedEffect(state.message, state.error) {
        val note = state.error ?: state.message
        if (note != null) {
            snackbar.showSnackbar(note)
            model.dismissMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(state.path.substringAfterLast('/').ifEmpty { "CloudHub" }, maxLines = 1) },
                navigationIcon = {
                    if (state.path != "/") {
                        IconButton(onClick = { model.open(state.path.substringBeforeLast('/', "").ifEmpty { "/" }) }) {
                            Icon(Icons.Default.ArrowBack, "Up one folder")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { model.setGrid(!state.grid) }) {
                        Icon(if (state.grid) Icons.Default.ViewList else Icons.Default.GridView,
                            if (state.grid) "List view" else "Grid view")
                    }
                    IconButton(onClick = { overflow = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                        DropdownMenuItem(text = { Text("Refresh") },
                            onClick = { overflow = false; model.refresh() })
                        DropdownMenuItem(text = { Text("Sort by name") },
                            onClick = { overflow = false; model.setSort(FilesState.Sort.NAME) })
                        DropdownMenuItem(text = { Text("Sort by newest") },
                            onClick = { overflow = false; model.setSort(FilesState.Sort.NEWEST) })
                        DropdownMenuItem(text = { Text("Sort by largest") },
                            onClick = { overflow = false; model.setSort(FilesState.Sort.LARGEST) })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Trash") },
                            onClick = { overflow = false; onOpenTrash() })
                        DropdownMenuItem(text = { Text("Sign out") },
                            onClick = { overflow = false; onSignOut() })
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.canWrite && state.selected.isEmpty()) {
                UploadFab(
                    onPickMedia = onPickMedia,
                    onPickFile = onPickFile,
                    onTakePhoto = onTakePhoto,
                    onRecordVideo = onRecordVideo,
                    onNewFolder = { showNewFolder = true },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Breadcrumbs(state.path, model::open)
            SearchBar(
                query = state.query,
                searching = state.searchResults != null,
                onQuery = model::setQuery,
                onSearchAll = model::searchEverywhere,
                onClear = model::clearSearch,
            )
            state.searchResults?.let { results ->
                Text(
                    if (results.isEmpty()) "No matches for \"${state.query}\""
                    else "${results.size} match${if (results.size == 1) "" else "es"}" +
                        if (state.searchTruncated) " (showing the first ${results.size})" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (state.selected.isNotEmpty()) {
                SelectionBar(
                    count = state.selected.size,
                    canWrite = state.canWrite,
                    onClear = model::clearSelection,
                    onMove = { picking = PickerRequest(state.selected.toList(), moving = true) },
                    onCopy = { picking = PickerRequest(state.selected.toList(), moving = false) },
                    onDelete = { model.delete(state.selected.toList()) },
                )
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            val entries = state.visible
            if (entries.isEmpty() && !state.loading) {
                EmptyFolder(state.searchResults != null)
            } else if (state.grid) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(entries, key = { it.path }) { entry ->
                        FileTile(api, entry, entry.path in state.selected, state.searchResults != null,
                            onOpen = { if (entry.isDirectory) model.open(entry.path) else onOpenFile(entry) },
                            onLongPress = { model.toggleSelected(entry.path) },
                            onMenu = { menuFor = entry })
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(entries, key = { it.path }) { entry ->
                        FileRow(api, entry, entry.path in state.selected, state.searchResults != null,
                            onOpen = { if (entry.isDirectory) model.open(entry.path) else onOpenFile(entry) },
                            onLongPress = { model.toggleSelected(entry.path) },
                            onMenu = { menuFor = entry })
                    }
                }
            }
        }
    }

    menuFor?.let { entry ->
        FileActionsSheet(
            entry = entry,
            canWrite = state.canWrite,
            onDismiss = { menuFor = null },
            onOpen = { menuFor = null; if (entry.isDirectory) model.open(entry.path) else onOpenFile(entry) },
            onDownload = { menuFor = null; onDownload(entry) },
            onShare = { menuFor = null; onShare(entry) },
            onRename = { menuFor = null; renaming = entry },
            onMove = { menuFor = null; picking = PickerRequest(listOf(entry.path), moving = true) },
            onCopy = { menuFor = null; picking = PickerRequest(listOf(entry.path), moving = false) },
            onDelete = { menuFor = null; model.delete(listOf(entry.path)) },
        )
    }

    if (showNewFolder) {
        TextPrompt("New folder", "Folder name", "",
            onDismiss = { showNewFolder = false },
            onConfirm = { showNewFolder = false; model.makeFolder(it) })
    }

    renaming?.let { entry ->
        TextPrompt("Rename", "New name", entry.name,
            onDismiss = { renaming = null },
            onConfirm = { renaming = null; model.rename(entry, it) })
    }

    picking?.let { request ->
        FolderPicker(
            api = api,
            startAt = state.path,
            title = if (request.moving) "Move here" else "Copy here",
            onDismiss = { picking = null },
            onPick = { destination ->
                picking = null
                if (request.moving) model.move(request.paths, destination)
                else model.copy(request.paths, destination)
            },
        )
    }
}

private data class PickerRequest(val paths: List<String>, val moving: Boolean)

@Composable
private fun Breadcrumbs(path: String, onOpen: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onOpen("/") }) { Text("Root") }
        var walked = ""
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            walked += "/$part"
            val target = walked
            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onOpen(target) }) { Text(part, maxLines = 1) }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    searching: Boolean,
    onQuery: (String) -> Unit,
    onSearchAll: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("Search") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = onClear) { Icon(Icons.Default.Close, "Clear") }
            },
            modifier = Modifier.weight(1f),
        )
        // The web app's This folder / All folders split: typing filters what is
        // on screen, and asking explicitly walks the tree on the server.
        if (query.length >= 2 && !searching) {
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = onSearchAll) { Text("All folders") }
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    canWrite: Boolean,
    onClear: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClear) { Icon(Icons.Default.Close, "Clear selection") }
            Text("$count selected", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            if (canWrite) {
                TextButton(onClick = onMove) { Text("Move") }
                TextButton(onClick = onCopy) { Text("Copy") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun EmptyFolder(searching: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            if (searching) "Nothing matched." else "This folder is empty.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The add button, and the sheet behind it.
 *
 * Five ways to add something is more than a column of bare icons can explain --
 * a camera glyph and a film glyph side by side tell you nothing about which one
 * records -- so the button opens the same labelled sheet pattern the file menu
 * uses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UploadFab(
    onPickMedia: () -> Unit,
    onPickFile: () -> Unit,
    onTakePhoto: () -> Unit,
    onRecordVideo: () -> Unit,
    onNewFolder: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    FloatingActionButton(onClick = { open = true }) {
        Icon(Icons.Default.Add, "Add")
    }

    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Text(
                "Add to this folder",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            SheetAction(Icons.Default.PhotoLibrary, "Photos & videos", onClick = { open = false; onPickMedia() })
            SheetAction(Icons.Default.PhotoCamera, "Take a photo", onClick = { open = false; onTakePhoto() })
            SheetAction(Icons.Default.Videocam, "Record a video", onClick = { open = false; onRecordVideo() })
            SheetAction(Icons.Default.UploadFile, "Any file", onClick = { open = false; onPickFile() })
            SheetAction(Icons.Default.CreateNewFolder, "New folder", onClick = { open = false; onNewFolder() })
            Spacer(Modifier.height(20.dp))
        }
    }
}

/* ---- one file ----------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileTile(
    api: CloudHubApi,
    entry: FileEntry,
    selected: Boolean,
    showFolder: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onMenu: () -> Unit,
) {
    Card(
        modifier = Modifier.combinedClickableCompat(onOpen, onLongPress),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Thumbnail(api, entry, Modifier.fillMaxSize())
        }
        Column(Modifier.padding(8.dp)) {
            Text(entry.name, style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    subtitle(entry, showFolder),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onMenu, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.MoreVert, "Actions", Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    api: CloudHubApi,
    entry: FileEntry,
    selected: Boolean,
    showFolder: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onMenu: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickableCompat(onOpen, onLongPress)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Thumbnail(api, entry, Modifier.fillMaxSize()) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle(entry, showFolder), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onMenu) { Icon(Icons.Default.MoreVert, "Actions") }
    }
}

/**
 * A picture where there is one, an icon where there is not.
 *
 * Videos the server has no cached frame for are decoded on the device by
 * Coil's video decoder pointed at the stream, so the grid is not a wall of
 * identical placeholders.
 */
@Composable
private fun Thumbnail(api: CloudHubApi, entry: FileEntry, modifier: Modifier) {
    val context = LocalContext.current
    when (entry.kind) {
        FileEntry.Kind.IMAGE -> AsyncImage(
            model = ImageRequest.Builder(context).data(api.thumbnailUrl(entry).toString()).build(),
            contentDescription = entry.name,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
        FileEntry.Kind.VIDEO -> Box(modifier, contentAlignment = Alignment.Center) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(if (entry.hasThumbnail) api.thumbnailUrl(entry).toString()
                          else api.streamUrl(entry.path).toString())
                    .build(),
                contentDescription = entry.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Icon(Icons.Default.PlayCircle, null, Modifier.size(34.dp))
        }
        else -> Icon(iconFor(entry), null, Modifier.size(30.dp))
    }
}

private fun iconFor(entry: FileEntry): ImageVector = when (entry.kind) {
    FileEntry.Kind.FOLDER -> Icons.Default.Folder
    FileEntry.Kind.AUDIO -> Icons.Default.MusicNote
    FileEntry.Kind.PDF -> Icons.Default.PictureAsPdf
    FileEntry.Kind.TEXT -> Icons.Default.Description
    else -> Icons.Default.InsertDriveFile
}

private fun subtitle(entry: FileEntry, showFolder: Boolean): String {
    val head = if (entry.isDirectory) "Folder" else humanBytes(entry.size)
    // In search results the folder is the useful half; in a listing it is
    // already obvious from where you are.
    if (!showFolder) return head
    val parent = entry.path.substringBeforeLast('/', "").ifEmpty { "/" }
    return "$head · in $parent"
}

fun humanBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
    return String.format(Locale.US, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[index])
}

/** Long-press to select is the Android idiom for what the web app does with checkboxes. */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: () -> Unit) =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)
