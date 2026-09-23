package nl.tippie.cloudhub.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.FileEntry

/**
 * Every file this account has starred, wherever it lives.
 *
 * A place to find a file rather than to manage it: the sheet offers what
 * reading a file needs -- open, download, share, show in its folder, unstar --
 * and leaves renaming, moving and deleting to the folder the file is in, one
 * tap away. The list is the server's, most recently starred first, and is
 * fetched again each time the screen opens so a star added on another device
 * is here too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    api: CloudHubApi,
    model: FilesViewModel,
    /** The file tapped, and the favorites on show, for the viewer to swipe through. */
    onOpenFile: (FileEntry, List<FileEntry>) -> Unit,
    onShowInFolder: (FileEntry) -> Unit,
    onDownload: (FileEntry) -> Unit,
    onShare: (FileEntry) -> Unit,
    onBack: () -> Unit,
) {
    val state by model.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var chosen by rememberSaveable { mutableStateOf(FavoriteRules.Filter.ALL) }
    var menuFor by remember { mutableStateOf<FileEntry?>(null) }
    var propertiesFor by remember { mutableStateOf<FileEntry?>(null) }

    LaunchedEffect(Unit) { model.loadFavorites() }
    // A star the server refused comes back with its reason.
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            model.dismissMessage()
        }
    }

    val entries = state.favoriteEntries
    val filter = FavoriteRules.effective(chosen, entries)
    val shown = entries.filter(filter::matches)
    val counts = FavoriteRules.counts(entries)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Favorites") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = model::loadFavorites) { Icon(Icons.Default.Refresh, "Refresh") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.favoritesLoad == LoadState.LOADING) LinearProgressIndicator(Modifier.fillMaxWidth())

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FavoriteRules.Filter.entries.forEach { option ->
                    val count = counts[option] ?: 0
                    FilterChip(
                        selected = option == filter,
                        onClick = { chosen = option },
                        // A filter with nothing in it is shown, so the counts
                        // add up, but cannot be chosen.
                        enabled = option == FavoriteRules.Filter.ALL || count > 0,
                        label = { Text("${option.label} ($count)") },
                    )
                }
            }

            when {
                entries.isEmpty() && state.favoritesLoad == LoadState.FAILED ->
                    FavoritesMessage(
                        icon = { Icon(Icons.Default.CloudOff, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(56.dp)) },
                        title = "Could not load your favorites",
                        detail = state.favoritesError ?: "The server did not answer.",
                        action = { Button(onClick = model::loadFavorites) { Text("Retry") } },
                    )

                entries.isEmpty() && state.favoritesLoad == LoadState.READY ->
                    FavoritesMessage(
                        icon = { Icon(Icons.Default.StarBorder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(56.dp)) },
                        title = "No favorites yet",
                        detail = "Star a photo, a video or any file from its menu, or from the viewer, and it will be kept here.",
                    )

                state.grid -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = GRID_MIN_CELL),
                    contentPadding = PaddingValues(GRID_PADDING),
                    horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
                    verticalArrangement = Arrangement.spacedBy(GRID_GAP),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(shown, key = { it.path }) { entry ->
                        FileTile(
                            api, entry, selected = false, showFolder = true,
                            onOpen = { onOpenFile(entry, shown) },
                            // No selection here, so a long press asks what to do.
                            onLongPress = { menuFor = entry },
                            onMenu = { menuFor = entry },
                            modifier = Modifier.animateItem(),
                            favorite = true,
                        )
                    }
                }

                else -> LazyColumn(contentPadding = PaddingValues(vertical = 6.dp), modifier = Modifier.fillMaxSize()) {
                    items(shown, key = { it.path }) { entry ->
                        FileRow(
                            api, entry, selected = false, showFolder = true,
                            onOpen = { onOpenFile(entry, shown) },
                            onLongPress = { menuFor = entry },
                            onMenu = { menuFor = entry },
                            modifier = Modifier.animateItem(),
                            favorite = true,
                        )
                    }
                }
            }
        }
    }

    menuFor?.let { entry ->
        FileActionsSheet(
            entry = entry,
            // Managing a file happens in its folder; see the note above.
            canWrite = false,
            onDismiss = { menuFor = null },
            onOpen = { menuFor = null; onOpenFile(entry, shown) },
            onDownload = { menuFor = null; onDownload(entry) },
            onShare = { menuFor = null; onShare(entry) },
            onRename = {}, onMove = {}, onCopy = {}, onDelete = {}, onAddSubtitles = {},
            onProperties = { menuFor = null; propertiesFor = entry },
            favorite = entry.path in state.favorites,
            onFavorite = { menuFor = null; model.toggleFavorite(entry) },
            onShowInFolder = { menuFor = null; onShowInFolder(entry) },
        )
    }

    propertiesFor?.let { entry -> PropertiesSheet(entry = entry, onDismiss = { propertiesFor = null }) }
}

@Composable
private fun FavoritesMessage(
    icon: @Composable () -> Unit,
    title: String,
    detail: String,
    action: (@Composable () -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            icon()
            Spacer(Modifier.height(18.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            action?.let {
                Spacer(Modifier.height(22.dp))
                it()
            }
        }
    }
}
