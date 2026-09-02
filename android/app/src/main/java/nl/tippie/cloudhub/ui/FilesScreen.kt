package nl.tippie.cloudhub.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.FileEntry

/**
 * The file browser.
 *
 * Grid by default because a phone showing photos wants pictures, not rows;
 * the list view is there for folders full of documents where the name is what
 * you are scanning for.
 *
 * While a folder loads it draws placeholder cards rather than a spinner --
 * built from the same [FileCardScaffold] as the real cards, so the content
 * arriving is a change of colour and not a change of layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    api: CloudHubApi,
    model: FilesViewModel,
    onOpenFile: (FileEntry) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenDuplicates: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
    onPickMedia: () -> Unit,
    onPickFile: () -> Unit,
    onTakePhoto: () -> Unit,
    onRecordVideo: () -> Unit,
    onDownload: (FileEntry) -> Unit,
    onShare: (FileEntry) -> Unit,
    onDismissUploadFailures: () -> Unit,
    /** A file to come back to -- the photo or video just closed. */
    revealPath: String? = null,
    onRevealed: () -> Unit = {},
) {
    val state by model.state.collectAsState()
    val uploads = rememberUploadState()
    val snackbar = remember { SnackbarHostState() }
    var showNewFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<FileEntry?>(null) }
    var picking by remember { mutableStateOf<PickerRequest?>(null) }
    var menuFor by remember { mutableStateOf<FileEntry?>(null) }
    var propertiesFor by remember { mutableStateOf<FileEntry?>(null) }
    var overflow by remember { mutableStateOf(false) }

    // Only action feedback goes through the snackbar now. A failed *listing*
    // is a state of the screen, not a message that scrolls away.
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            model.dismissMessage()
        }
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            BrowserTopBar(
                folder = state.path.substringAfterLast('/').ifEmpty { "CloudHub" },
                atRoot = state.path == "/",
                grid = state.grid,
                sort = state.sort,
                scrollBehavior = scrollBehavior,
                overflowOpen = overflow,
                onUp = { model.open(state.path.substringBeforeLast('/', "").ifEmpty { "/" }) },
                onToggleView = { model.setGrid(!state.grid) },
                onOverflow = { overflow = it },
                onRefresh = { model.refresh() },
                onSort = { model.setSort(it) },
                onTrash = onOpenTrash,
                onStorage = onOpenStorage,
                onDuplicates = onOpenDuplicates,
                onSettings = onOpenSettings,
                onSignOut = onSignOut,
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = state.canWrite && state.selected.isEmpty(),
                enter = scaleIn(spring(stiffness = 500f)) + fadeIn(),
                exit = scaleOut(tween(120)) + fadeOut(tween(120)),
            ) {
                UploadFab(
                    onPickMedia = onPickMedia,
                    onPickFile = onPickFile,
                    onTakePhoto = onTakePhoto,
                    onRecordVideo = onRecordVideo,
                    onNewFolder = { showNewFolder = true },
                )
            }
        },
        bottomBar = {
            // Docked rather than floating over the grid: an upload can take
            // minutes, and a bar that hides the last row of files for all of
            // them would be worse than no bar.
            UploadTracker(
                state = uploads,
                onDismissFailures = onDismissUploadFailures,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Breadcrumbs(state.path, model::open)

            SearchField(
                query = state.query,
                searching = state.searchResults != null,
                onQuery = model::setQuery,
                onSearchAll = model::searchEverywhere,
                onClear = model::clearSearch,
            )

            AnimatedVisibility(
                visible = state.searchResults != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                val results = state.searchResults.orEmpty()
                Text(
                    if (results.isEmpty()) "No matches for \"${state.query}\""
                    else "${results.size} match${if (results.size == 1) "" else "es"}" +
                        if (state.searchTruncated) " (showing the first ${results.size})" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            AnimatedVisibility(
                visible = state.selected.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                SelectionBar(
                    count = state.selected.size,
                    canWrite = state.canWrite,
                    onClear = model::clearSelection,
                    onMove = { picking = PickerRequest(state.selected.toList(), moving = true) },
                    onCopy = { picking = PickerRequest(state.selected.toList(), moving = false) },
                    onDelete = { model.delete(state.selected.toList()) },
                )
            }

            BrowserContent(
                api = api,
                state = state,
                onOpen = { entry -> if (entry.isDirectory) model.open(entry.path) else onOpenFile(entry) },
                onLongPress = model::toggleSelected,
                onMenu = { menuFor = it },
                onRetry = model::retry,
                onNewFolder = { showNewFolder = true },
                revealPath = revealPath,
                onRevealed = onRevealed,
            )
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
            onProperties = { menuFor = null; propertiesFor = entry },
        )
    }

    propertiesFor?.let { entry ->
        PropertiesSheet(entry = entry, onDismiss = { propertiesFor = null })
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

/* ---- the content area ----------------------------------------------------- */

/**
 * Skeleton, content, empty, no matches or error -- and the transitions between.
 *
 * Which one is [FilesState.shown]'s decision, made in one testable place. The
 * crossfade is the whole point: replacing placeholders with content in a single
 * frame is what makes an app feel like it stuttered rather than loaded.
 */
@Composable
private fun BrowserContent(
    api: CloudHubApi,
    state: FilesState,
    onOpen: (FileEntry) -> Unit,
    onLongPress: (String) -> Unit,
    onMenu: (FileEntry) -> Unit,
    onRetry: () -> Unit,
    onNewFolder: () -> Unit,
    revealPath: String?,
    onRevealed: () -> Unit,
) {
    val shown = state.shown
    // Held briefly past the answer so a fast folder does not flash a skeleton
    // and a slow one does not blink it away the instant it lands.
    val settled = rememberSettledSkeleton(shown)

    AnimatedContent(
        targetState = settled,
        transitionSpec = {
            // Content rises very slightly as it replaces the placeholders.
            (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 24 })
                .togetherWith(fadeOut(tween(160)))
        },
        label = "content",
        modifier = Modifier.fillMaxSize(),
    ) { target ->
        when (target) {
            Shown.SKELETON -> SkeletonList(grid = state.grid)
            Shown.ERROR -> LoadFailed(state.loadError, onRetry)
            Shown.EMPTY -> EmptyFolder(canWrite = state.canWrite, onNewFolder = onNewFolder)
            Shown.NO_MATCHES -> NoMatches(state.query)
            Shown.CONTENT -> EntryList(api, state, onOpen, onLongPress, onMenu, revealPath, onRevealed)
        }
    }
}

/**
 * The skeleton's own timing, kept out of the state machine.
 *
 * [browserState] says whether a skeleton is warranted; this says whether it is
 * worth *showing*, which is a different question and one only the passage of
 * time can answer.
 */
@Composable
private fun rememberSettledSkeleton(target: Shown): Shown {
    var settled by remember { mutableStateOf(target) }
    var shownAt by remember { mutableStateOf(0L) }

    LaunchedEffect(target) {
        if (target == Shown.SKELETON) {
            delay(SkeletonTiming.DELAY_MS)
            shownAt = System.currentTimeMillis()
            settled = target
        } else {
            if (settled == Shown.SKELETON && shownAt > 0L) {
                delay(SkeletonTiming.lingerMs(System.currentTimeMillis() - shownAt))
            }
            shownAt = 0L
            settled = target
        }
    }
    return settled
}

@Composable
private fun SkeletonList(grid: Boolean) {
    // One transition for the whole screen, its progress handed to every card.
    val progress = rememberShimmer()
    val height = LocalConfiguration.current.screenHeightDp

    Box(
        Modifier
            .fillMaxSize()
            // Announced once, rather than a dozen empty cards read as content.
            .semantics { contentDescription = "Loading this folder" },
    ) {
        if (grid) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val columns = (maxWidth / GRID_MIN_CELL).toInt().coerceAtLeast(1)
                val cardHeight = cardHeightDp((maxWidth.value / columns).toInt())
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = GRID_MIN_CELL),
                    contentPadding = PaddingValues(GRID_PADDING),
                    horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
                    verticalArrangement = Arrangement.spacedBy(GRID_GAP),
                    userScrollEnabled = false,
                ) {
                    items(skeletonCount(columns, height, cardHeight)) { SkeletonTile(progress) }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 6.dp),
                userScrollEnabled = false,
            ) {
                items(skeletonCount(1, height, ROW_HEIGHT.value.toInt())) { SkeletonRow(progress) }
            }
        }
    }
}

@Composable
private fun EntryList(
    api: CloudHubApi,
    state: FilesState,
    onOpen: (FileEntry) -> Unit,
    onLongPress: (String) -> Unit,
    onMenu: (FileEntry) -> Unit,
    revealPath: String? = null,
    onRevealed: () -> Unit = {},
) {
    val entries = state.visible
    val searching = state.searchResults != null

    /*
     * Where this folder was when you left it.
     *
     * The list states below are saveable, so the screen holder keeps them
     * while you are watching a video -- but there is one of them for every
     * folder, so it can only describe the folder on screen. The memory is what
     * makes walking up a folder land where you were rather than at the top.
     */
    val memory = rememberSaveable(saver = ScrollMemorySaver) { ScrollMemory() }
    val grid = rememberLazyGridState()
    val list = rememberLazyListState()
    /*
     * Read through functions rather than into values.
     *
     * snapshotFlow re-runs when state read *inside* its block changes; a
     * position captured out here would be one value the flow never sees change,
     * so the folder would be recorded once, at the top, and nowhere else.
     */
    val firstVisible = { if (state.grid) grid.firstVisibleItemIndex else list.firstVisibleItemIndex }
    val offset = { if (state.grid) grid.firstVisibleItemScrollOffset else list.firstVisibleItemScrollOffset }
    val lastVisible = {
        // Branched whole: the two layout infos have no common item type, so
        // asking one question of both does not compile.
        if (state.grid) (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: firstVisible())
        else (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: firstVisible())
    }

    // Restoration happens once per folder, and recording waits for it: a
    // recorder running first would write "the top" over the place being
    // restored, every time.
    var restored by remember(state.path) { mutableStateOf(false) }

    LaunchedEffect(state.path, state.grid) {
        // An empty list cannot be scrolled; wait for the folder to arrive.
        snapshotFlow { state.visible.size }.first { it > 0 }
        val place = memory.placeOf(state.path)
        if (place != null) {
            if (state.grid) grid.scrollToItem(place.index, place.offset)
            else list.scrollToItem(place.index, place.offset)
        }
        restored = true
    }

    LaunchedEffect(state.path, state.grid) {
        snapshotFlow { Triple(firstVisible(), offset(), state.visible.size) }
            .collect { (index, at, size) ->
                if (restored && size > 0) memory.remember(state.path, index, at)
            }
    }

    /*
     * Come back to the file you were on.
     *
     * Swiping through thirty photos and pressing Back should land on the last
     * one looked at, not the one opened -- but only when it is off screen,
     * since scrolling a file that is already visible up to the top edge is a
     * jump for no reason.
     */
    LaunchedEffect(revealPath, entries.size, restored) {
        if (revealPath == null || !restored || entries.isEmpty()) return@LaunchedEffect
        val target = ScrollMemory.indexOfPath(entries.map { it.path }, revealPath)
        if (ScrollMemory.shouldReveal(target, firstVisible(), lastVisible())) {
            if (state.grid) grid.scrollToItem(target) else list.scrollToItem(target)
        }
        onRevealed()
    }
    // Reset when the folder changes, so opening a folder plays the stagger
    // again rather than showing an already-arrived screen.
    val entrance = remember(state.path) { Animatable(0f) }
    val reduceMotion = LocalReduceMotion.current

    LaunchedEffect(state.path, reduceMotion) {
        if (reduceMotion) entrance.snapTo(1f)
        else entrance.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
    }

    if (state.grid) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = GRID_MIN_CELL),
            state = grid,
            contentPadding = PaddingValues(GRID_PADDING),
            horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
            verticalArrangement = Arrangement.spacedBy(GRID_GAP),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(entries) { index, entry ->
                FileTile(
                    api, entry, entry.path in state.selected, searching,
                    onOpen = { onOpen(entry) },
                    onLongPress = { onLongPress(entry.path) },
                    onMenu = { onMenu(entry) },
                    // animateItem gives filtering-as-you-type a re-flow rather
                    // than a snap; the stagger only touches the first screenful.
                    modifier = Modifier.animateItem().staggered(entrance.value, index),
                )
            }
        }
    } else {
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(vertical = 6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(entries) { index, entry ->
                FileRow(
                    api, entry, entry.path in state.selected, searching,
                    onOpen = { onOpen(entry) },
                    onLongPress = { onLongPress(entry.path) },
                    onMenu = { onMenu(entry) },
                    modifier = Modifier.animateItem().staggered(entrance.value, index),
                )
            }
        }
    }
}

/**
 * The remembered folders, written down.
 *
 * Flat on purpose -- path, index, offset repeating -- because a Saver has to
 * survive being written to a Bundle, and a map of data classes does not.
 */
private val ScrollMemorySaver = listSaver<ScrollMemory, Any>(
    save = { memory ->
        memory.snapshot().flatMap { (path, place) -> listOf(path, place.index, place.offset) }
    },
    restore = { flat ->
        val places = LinkedHashMap<String, ScrollMemory.Place>()
        flat.chunked(3).forEach { row ->
            if (row.size == 3) {
                places[row[0] as String] = ScrollMemory.Place(row[1] as Int, row[2] as Int)
            }
        }
        ScrollMemory(places)
    },
)

private val GRID_MIN_CELL = 158.dp
private val GRID_PADDING = 14.dp
private val GRID_GAP = 12.dp

/** Items past this appear at once: scrolling should never pay for an entrance. */
private const val STAGGER_LIMIT = 12

/**
 * Fade and rise, offset by position.
 *
 * Reads the one shared driver rather than starting an animation per card --
 * with fifty files on screen that would be fifty animation clocks for an
 * effect lasting half a second.
 */
private fun Modifier.staggered(progress: Float, index: Int): Modifier {
    if (index >= STAGGER_LIMIT || progress >= 1f) return this
    val start = index / (STAGGER_LIMIT * 1.6f)
    val local = ((progress - start) / (1f - start)).coerceIn(0f, 1f)
    return this.graphicsLayer {
        alpha = local
        translationY = (1f - local) * 40f
    }
}

/* ---- the states with no list ---------------------------------------------- */

@Composable
private fun EmptyFolder(canWrite: Boolean, onNewFolder: () -> Unit) {
    StateMessage(
        icon = { BrandMark(Modifier.size(64.dp)) },
        title = "This folder is empty",
        detail = if (canWrite) "Add files with the + button, or create a folder to organise them."
        else "Nothing has been put here yet.",
        action = if (canWrite) {
            { OutlinedButton(onClick = onNewFolder) { Text("Create a folder") } }
        } else null,
    )
}

@Composable
private fun NoMatches(query: String) {
    StateMessage(
        icon = {
            Icon(
                Icons.Default.Search, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(56.dp),
            )
        },
        title = "Nothing matched",
        detail = if (query.isBlank()) "Try a different search."
        else "No file here is called \"$query\". Try All folders to search everywhere.",
    )
}

/**
 * The listing failed.
 *
 * This state exists because the screen used to show "This folder is empty"
 * when the server was unreachable -- a wrong answer stated with confidence.
 */
@Composable
private fun LoadFailed(reason: String?, onRetry: () -> Unit) {
    StateMessage(
        icon = {
            Icon(
                Icons.Default.CloudOff, null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(56.dp),
            )
        },
        title = "Could not load this folder",
        detail = reason ?: "The server did not answer.",
        action = { Button(onClick = onRetry) { Text("Retry") } },
    )
}

/** One layout for all three, so they arrive the same way. */
@Composable
private fun StateMessage(
    icon: @Composable () -> Unit,
    title: String,
    detail: String,
    action: (@Composable () -> Unit)? = null,
) {
    val reduceMotion = LocalReduceMotion.current
    val appear = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduceMotion) appear.animateTo(1f, tween(340)) }

    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * 24f
            },
        ) {
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

/* ---- the bar, the crumbs and the search ------------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserTopBar(
    folder: String,
    atRoot: Boolean,
    grid: Boolean,
    sort: FilesState.Sort,
    scrollBehavior: TopAppBarScrollBehavior,
    overflowOpen: Boolean,
    onUp: () -> Unit,
    onToggleView: () -> Unit,
    onOverflow: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onSort: (FilesState.Sort) -> Unit,
    onTrash: () -> Unit,
    onStorage: () -> Unit,
    onDuplicates: () -> Unit,
    onSettings: () -> Unit,
    onSignOut: () -> Unit,
) {
    TopAppBar(
        scrollBehavior = scrollBehavior,
        title = {
            // Changing folders cross-fades the name rather than swapping it.
            AnimatedContent(
                targetState = folder,
                transitionSpec = { fadeIn(tween(220)).togetherWith(fadeOut(tween(140))) },
                label = "folder",
            ) { name ->
                Text(name, maxLines = 1, fontWeight = FontWeight.SemiBold)
            }
        },
        navigationIcon = {
            AnimatedVisibility(visible = !atRoot, enter = fadeIn(), exit = fadeOut()) {
                IconButton(onClick = onUp) { Icon(Icons.Default.ArrowBack, "Up one folder") }
            }
        },
        actions = {
            IconButton(onClick = onToggleView) {
                AnimatedContent(
                    targetState = grid,
                    transitionSpec = {
                        (fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.7f))
                            .togetherWith(fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.7f))
                    },
                    label = "view",
                ) { isGrid ->
                    Icon(
                        if (isGrid) Icons.Default.ViewList else Icons.Default.GridView,
                        if (isGrid) "Switch to list view" else "Switch to grid view",
                    )
                }
            }
            IconButton(onClick = { onOverflow(true) }) { Icon(Icons.Default.MoreVert, "More") }
            /*
             * The overflow menu.
             *
             * Every item carries an icon, and the sort you are actually on is
             * marked -- three lines reading "Sort by ..." with nothing to
             * distinguish them left you unable to tell what the folder was
             * sorted by without changing it to find out.
             *
             * Offset so the menu sits inboard of the screen edge rather than
             * flush against it.
             */
            DropdownMenu(
                expanded = overflowOpen,
                onDismissRequest = { onOverflow(false) },
                offset = DpOffset(x = (-8).dp, y = 4.dp),
                modifier = Modifier.widthIn(min = 220.dp),
            ) {
                MenuItem(Icons.Default.Refresh, "Refresh") { onOverflow(false); onRefresh() }

                MenuHeading("Sort by")
                SortItem("Name", Icons.Default.SortByAlpha, sort == FilesState.Sort.NAME) {
                    onOverflow(false); onSort(FilesState.Sort.NAME)
                }
                SortItem("Newest", Icons.Default.Schedule, sort == FilesState.Sort.NEWEST) {
                    onOverflow(false); onSort(FilesState.Sort.NEWEST)
                }
                SortItem("Largest", Icons.Default.DataUsage, sort == FilesState.Sort.LARGEST) {
                    onOverflow(false); onSort(FilesState.Sort.LARGEST)
                }

                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                MenuItem(Icons.Default.Delete, "Trash") { onOverflow(false); onTrash() }
                MenuItem(Icons.Default.PieChart, "Storage") { onOverflow(false); onStorage() }
                // Next to Storage: it is a way of getting space back, which is
                // what somebody looking at Storage is usually after.
                MenuItem(Icons.Default.ContentCopy, "Duplicates") { onOverflow(false); onDuplicates() }
                MenuItem(Icons.Default.Settings, "Settings") { onOverflow(false); onSettings() }

                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                MenuItem(Icons.AutoMirrored.Filled.Logout, "Sign out", danger = true) {
                    onOverflow(false); onSignOut()
                }
            }
        },
    )
}

/**
 * One row of the overflow menu.
 *
 * A leading icon on every item, so the menu is scannable by shape rather than
 * by reading four words at a time.
 */
@Composable
private fun MenuItem(
    icon: ImageVector,
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    DropdownMenuItem(
        text = {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        },
        leadingIcon = { Icon(icon, null, tint = tint) },
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp),
    )
}

/**
 * A sort option, with a tick when it is the one in force.
 *
 * The trailing slot rather than the leading one: the icons stay in a column so
 * the list still scans, and the tick reads as state rather than as another
 * thing to press.
 */
@Composable
private fun SortItem(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.primary else Color.Unspecified,
            )
        },
        leadingIcon = {
            Icon(
                icon, null,
                tint = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            if (active) Icon(Icons.Default.Check, "Sorted by $label", tint = MaterialTheme.colorScheme.primary)
        },
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp),
    )
}

/** A quiet label over a group, so "Name" is obviously a sort and not a screen. */
@Composable
private fun MenuHeading(text: String) {
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 4.dp),
    )
}

/**
 * Root › Photos, each part a target.
 *
 * Scrolled to the end on navigation: with a deep path the crumb you just
 * opened is the one off the right edge, which is the one worth seeing.
 */
@Composable
private fun Breadcrumbs(path: String, onOpen: (String) -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(path) { scroll.animateScrollTo(scroll.maxValue) }

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crumb("Root", onClick = { onOpen("/") })
        var walked = ""
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            walked += "/$part"
            val target = walked
            Text(
                "›",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
            Crumb(part, onClick = { onOpen(target) })
        }
    }
}

@Composable
private fun Crumb(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/**
 * Search.
 *
 * Typing filters what is on screen without a request; All folders asks the
 * server to walk the tree, which is the web app's This folder / All folders
 * split and the reason the two are not merged into one live search.
 */
@Composable
private fun SearchField(
    query: String,
    searching: Boolean,
    onQuery: (String) -> Unit,
    onSearchAll: () -> Unit,
    onClear: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val keyboard = LocalSoftwareKeyboardController.current

    val border by animateColorAsState(
        if (focused) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant,
        tween(220), label = "search-border",
    )
    val container by animateColorAsState(
        if (focused) MaterialTheme.colorScheme.surface
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        tween(220), label = "search-fill",
    )

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("Search") },
            singleLine = true,
            interactionSource = interaction,
            shape = RoundedCornerShape(26.dp),
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                AnimatedVisibility(
                    visible = query.isNotEmpty(),
                    enter = fadeIn(tween(140)) + scaleIn(tween(140), initialScale = 0.7f),
                    exit = fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.7f),
                ) {
                    IconButton(onClick = onClear) { Icon(Icons.Default.Close, "Clear search") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onSearchAll() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = border,
                unfocusedBorderColor = border,
                focusedContainerColor = container,
                unfocusedContainerColor = container,
            ),
            modifier = Modifier.weight(1f),
        )
        AnimatedVisibility(
            visible = query.length >= 2 && !searching,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            Row {
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { keyboard?.hide(); onSearchAll() }) { Text("All folders") }
            }
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
    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp)),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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

/**
 * The add button, and the sheet behind it.
 *
 * Five ways to add something is more than a column of bare icons can explain --
 * a camera glyph and a film glyph side by side tell you nothing about which one
 * records -- so the button opens the same labelled sheet pattern the file menu
 * uses.
 *
 * There is no "new album": CloudHub stores folders, and nothing else.
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
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && !LocalReduceMotion.current) 0.92f else 1f,
        spring(dampingRatio = 0.55f, stiffness = 800f),
        label = "fab",
    )

    FloatingActionButton(
        onClick = { open = true },
        interactionSource = interaction,
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
        modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        Icon(Icons.Default.Add, "Add to this folder")
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
