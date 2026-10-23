package nl.tippie.cloudhub.ui

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.FileEntry
import nl.tippie.cloudhub.work.ForegroundMedia

/**
 * Full-screen image viewing, swiping between everything in the folder.
 *
 * The web app opens one file at a time; on a phone, moving between photos with
 * a swipe is the whole point, so the viewer takes the folder rather than a
 * single file.
 *
 * The photo has the whole screen, behind the bars as well as between them, and
 * the controls are drawn over it rather than beside it -- so showing and hiding
 * them never moves the picture. A tap hides them and the system bars together
 * (immersive mode), zooming in does the same, and another tap brings them all
 * back. What the bars offer: the name, "3 of 12", and the star at the top;
 * Share, Download, Details and More at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ImageViewer(
    api: CloudHubApi,
    images: List<FileEntry>,
    startAt: Int,
    /** Handed the photo on show when it closes, so the folder comes back to it. */
    onBack: (String?) -> Unit,
    /** The starred paths, so the star on the bar is right for the photo on show. */
    favorites: Set<String> = emptySet(),
    onToggleFavorite: ((FileEntry) -> Unit)? = null,
    /** Whether this account may rename and delete; every other action is anyone's. */
    canWrite: Boolean = false,
    /** Save the photo to the phone's Downloads, as the file menu does. */
    onDownload: ((FileEntry) -> Unit)? = null,
    /** The public-link dialog, which the app draws over whichever screen asked. */
    onShareLink: ((FileEntry) -> Unit)? = null,
    /** A photo here was renamed or deleted, so the folder underneath is out of date. */
    onFilesChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val reduceMotion = LocalReduceMotion.current

    // The viewer's own copy of the row, so a delete or a rename shows at once
    // rather than when the folder is next fetched. Every change goes through
    // ViewerRules.
    var photos by remember { mutableStateOf(images) }
    val pager = rememberPagerState(initialPage = startAt.coerceIn(0, maxOf(0, images.size - 1))) { photos.size }
    // Clamped: for a frame after the last photo is deleted the pager still
    // points one past the end, and the controls would flicker out and back.
    val current = photos.getOrNull(pager.currentPage.coerceAtMost(photos.lastIndex))

    /*
     * The controls and the system bars come and go together.
     *
     * Saveable, so turning the phone keeps a photo full screen. The bars come
     * back whenever the viewer is left, however it is left: see SystemBarsHidden.
     */
    var chrome by rememberSaveable { mutableStateOf(true) }
    SystemBarsHidden(hidden = !chrome)

    val close = { onBack(photos.getOrNull(pager.currentPage)?.path) }
    // Back by gesture or button lands on the photo on show, exactly as the
    // arrow does; it used to go through the app's own handler, which pops the
    // screen without saying where the viewer had got to.
    BackHandler(onBack = close)

    /*
     * Photos and a queued upload share one connection, and the upload will
     * take all of it unless told otherwise -- which is what left a picture
     * loading for a second at a time while a video was going up.
     */
    DisposableEffect(Unit) {
        ForegroundMedia.enter()
        onDispose { ForegroundMedia.leave() }
    }

    // A rename or delete whose answer never arrived -- the viewer closed
    // first -- has still changed the folder underneath.
    var unsettled by remember { mutableStateOf(false) }
    val filesChanged by rememberUpdatedState(onFilesChanged)
    DisposableEffect(Unit) { onDispose { if (unsettled) filesChanged() } }

    // Photos whose bytes have arrived, so Details knows when there is more to read.
    var loaded by remember { mutableStateOf(emptySet<String>()) }

    var preparing by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<FileEntry?>(null) }
    var renaming by remember { mutableStateOf<FileEntry?>(null) }
    var deleting by remember { mutableStateOf<FileEntry?>(null) }

    /** The photo itself to another app: Share, Open with, Set as. */
    fun handOff(entry: FileEntry, how: LocalCopies.HandOff) {
        if (preparing) return
        preparing = true
        scope.launch {
            var problem: String? = null
            try {
                val uri = LocalCopies.copyOf(context, api, entry, api.previewUrl(entry.path, entry.modified).toString())
                context.startActivity(LocalCopies.intentFor(how, uri, entry))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ActivityNotFoundException) {
                problem = "No app on this phone can take it"
            } catch (e: Exception) {
                problem = "Could not get ${entry.name} ready: ${e.message ?: "the server did not answer"}"
            } finally {
                preparing = false
            }
            // Shown after the copy is over, so the bar is free again while it is up.
            problem?.let { snackbar.showSnackbar(it) }
        }
    }

    /** Leave once nothing is left to show; the folder the viewer returns to says the rest. */
    fun closeEmpty(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        onBack(null)
    }

    /**
     * After a photo has left the row. The pager follows each page by its path,
     * so it stays on whatever is on show -- the photo that moved into the
     * gap, if it was this one -- and needs telling only when it is left past
     * the end. Moving it otherwise would yank back somebody who had swiped
     * on while the request was out.
     */
    suspend fun settleAfterRemoval() {
        val page = ViewerRules.pageAfterRemoval(pager.currentPage, photos.size)
        if (page != pager.currentPage) pager.scrollToPage(page)
    }

    /**
     * Delete, then offer Undo for as long as the snackbar is up.
     *
     * The server moves the file to the trash unless it is set to delete
     * outright, and its reply says which -- with the trash entry that Undo
     * restores. A permanent delete has nothing to undo, and is not offered it.
     */
    fun delete(entry: FileEntry) {
        val at = photos.indexOfFirst { it.path == entry.path }
        if (at < 0) return
        unsettled = true
        scope.launch {
            val result = try {
                api.delete(entry.path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                unsettled = false
                snackbar.showSnackbar(e.message ?: "That did not work")
                return@launch
            }
            unsettled = false
            filesChanged()
            val said = result.message.ifBlank { if (result.trashed) "Moved to trash" else "Deleted" }
            photos = ViewerRules.without(photos, entry.path)
            if (photos.isEmpty()) {
                closeEmpty(said)
                return@launch
            }
            settleAfterRemoval()

            val trashId = result.id?.takeIf { result.trashed }
            val answer = snackbar.showSnackbar(
                said,
                actionLabel = if (trashId != null) "Undo" else null,
                duration = SnackbarDuration.Long,
            )
            if (answer != SnackbarResult.ActionPerformed || trashId == null) return@launch

            unsettled = true
            try {
                val back = api.restore(trashId)
                unsettled = false
                filesChanged()
                // Where it went back to, which is not its old name if that was taken meanwhile.
                val path = back.path ?: entry.path
                photos = ViewerRules.restored(photos, entry.copy(path = path, name = path.substringAfterLast('/')), at)
                pager.scrollToPage(photos.indexOfFirst { it.path == path }.coerceAtLeast(0))
                snackbar.showSnackbar(back.message.ifBlank { "Restored" })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                unsettled = false
                snackbar.showSnackbar(e.message ?: "Could not undo that. It is in the trash.")
            }
        }
    }

    fun rename(entry: FileEntry, newName: String) {
        // Confirming the prompt unchanged is not a rename, as in the file list.
        if (!FilesViewModel.isRename(entry.name, newName)) return
        val asked = FilesViewModel.joinPath(entry.path.substringBeforeLast('/', ""), newName)
        unsettled = true
        scope.launch {
            try {
                val result = api.rename(entry.path, asked)
                unsettled = false
                filesChanged()
                val path = result.path ?: asked
                photos = ViewerRules.renamed(photos, entry, path, result.name ?: path.substringAfterLast('/'))
                // The server picks a free name when the one asked for is taken,
                // and says so; a bare "Renamed" would hide that.
                val said = result.message.ifBlank { "Renamed" }
                if (photos.isEmpty()) {
                    closeEmpty(said)
                    return@launch
                }
                // Renamed into something that is not a photo, it has left the row.
                settleAfterRemoval()
                snackbar.showSnackbar(said)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                unsettled = false
                snackbar.showSnackbar(e.message ?: "That did not work")
            }
        }
    }

    // Insets as if the bars were showing, so the controls sit in the same
    // place whether or not they are -- and do not slide as the bars return.
    val bars = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
    val appear = if (reduceMotion) EnterTransition.None else fadeIn(tween(CHROME_FADE_MS))
    val vanish = if (reduceMotion) ExitTransition.None else fadeOut(tween(CHROME_FADE_MS))

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        /*
         * The pager's own swipe, kept out of the system's way.
         *
         * With gesture navigation the strips down each side of the screen
         * belong to Android: a swipe that starts there is Back, whatever the
         * app underneath thinks. In a gallery, where swiping sideways is the
         * whole interaction, a photo swiped from the very edge would leave the
         * viewer instead of turning the page.
         *
         * systemGestureExclusion() asks for those strips back, and the system
         * grants at most 200dp of height per edge -- so Back by gesture still
         * works above and below the excluded band, as do the toolbar arrow and
         * the three-button Back. This is a carousel, the case the API exists
         * for, not a way of switching the gesture off.
         */
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().systemGestureExclusion(),
            // The photo either side is composed, and loading, before it is
            // swiped to -- rather than arriving black and filling in.
            beyondViewportPageCount = 1,
            pageSpacing = 16.dp,
            // Keyed by path, so a deleted photo takes its zoom and its pictures
            // with it instead of handing them to the one that moves into its place.
            key = { photos[it].path },
        ) { page ->
            val entry = photos[page]
            PhotoPage(
                api = api,
                entry = entry,
                current = page == pager.settledPage,
                onTap = { chrome = !chrome },
                onZoomedIn = { chrome = false },
                onLoaded = { loaded = loaded + entry.path },
            )
        }

        CompositionLocalProvider(LocalContentColor provides Color.White) {
            AnimatedVisibility(
                visible = chrome && current != null,
                enter = appear,
                exit = vanish,
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                ViewerTopBar(
                    name = current?.name.orEmpty(),
                    counter = ViewerRules.counter(pager.currentPage, photos.size),
                    insets = bars,
                    onBack = close,
                ) {
                    // Looking at the photo is when you decide you like it.
                    if (current != null && onToggleFavorite != null) {
                        FavoriteToggle(starred = current.path in favorites, onClick = { onToggleFavorite(current) })
                    }
                }
            }

            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                SnackbarHost(
                    snackbar,
                    // With the bar gone the snackbar sits at the bottom edge,
                    // clear of where the navigation bar comes back.
                    if (chrome) Modifier else Modifier.windowInsetsPadding(bars.only(WindowInsetsSides.Bottom)),
                )
                AnimatedVisibility(visible = chrome && current != null, enter = appear, exit = vanish) {
                    ViewerBottomBar(insets = bars, busy = preparing) {
                        val entry = current ?: return@ViewerBottomBar
                        ViewerAction(Icons.Default.Share, "Share", enabled = !preparing) {
                            handOff(entry, LocalCopies.HandOff.SHARE)
                        }
                        if (onDownload != null) {
                            ViewerAction(Icons.Default.Download, "Download") { onDownload(entry) }
                        }
                        ViewerAction(Icons.Default.Info, "Details") { details = entry }
                        Box {
                            ViewerAction(Icons.Default.MoreVert, "More") { more = true }
                            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                // A public link is a different thing from sending
                                // the photo: anyone holding it can open the file.
                                onShareLink?.let { share ->
                                    MoreItem(Icons.Default.Link, "Share link…") { more = false; share(entry) }
                                }
                                MoreItem(Icons.AutoMirrored.Filled.OpenInNew, "Open with…", enabled = !preparing) {
                                    more = false
                                    handOff(entry, LocalCopies.HandOff.OPEN)
                                }
                                MoreItem(Icons.Default.Wallpaper, "Set as…", enabled = !preparing) {
                                    more = false
                                    handOff(entry, LocalCopies.HandOff.SET_AS)
                                }
                                if (canWrite) {
                                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                                    MoreItem(Icons.Default.DriveFileRenameOutline, "Rename…") {
                                        more = false
                                        renaming = entry
                                    }
                                    MoreItem(Icons.Default.Delete, "Delete", danger = true) {
                                        more = false
                                        deleting = entry
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    details?.let { entry ->
        var facts by remember(entry.path) { mutableStateOf<PhotoFacts?>(null) }
        // Read again once the photo arrives, if Details was opened before it had.
        val arrived = entry.path in loaded
        LaunchedEffect(entry.path, arrived) {
            facts = PhotoFacts.cached(context, api.previewUrl(entry.path, entry.modified).toString()) ?: facts
        }
        PropertiesSheet(entry = entry, onDismiss = { details = null }, photo = facts)
    }

    renaming?.let { entry ->
        TextPrompt("Rename", "New name", entry.name,
            onDismiss = { renaming = null },
            onConfirm = { renaming = null; rename(entry, it) })
    }

    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this photo?") },
            text = {
                Text("${entry.name} goes to the trash, where it can be restored — unless this server " +
                    "is set to delete files permanently.")
            },
            confirmButton = {
                TextButton(onClick = { deleting = null; delete(entry) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/** How long the controls take to fade, in and out. */
private const val CHROME_FADE_MS = 180

/**
 * Name, counter and star, over a shade that keeps white text readable on a
 * white sky without putting a solid band across the photo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerTopBar(
    name: String,
    counter: String?,
    insets: WindowInsets,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                // Small, under the name: a place marker, not a heading.
                counter?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.75f))
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
        actions = actions,
        windowInsets = insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
            actionIconContentColor = Color.White,
        ),
        modifier = Modifier.background(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.72f),
                0.6f to Color.Black.copy(alpha = 0.4f),
                1f to Color.Transparent,
            )
        ),
    )
}

@Composable
private fun ViewerBottomBar(
    insets: WindowInsets,
    /** A copy of the photo is being made for another app. */
    busy: Boolean,
    actions: @Composable RowScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.4f to Color.Black.copy(alpha = 0.4f),
                    1f to Color.Black.copy(alpha = 0.72f),
                )
            )
            .windowInsetsPadding(insets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(top = 20.dp, bottom = 6.dp),
    ) {
        // Always this tall, so the buttons do not jump when a copy starts.
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (busy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.2f),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

/** An icon with its name under it: four actions is few enough to label every one. */
@Composable
private fun ViewerAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = if (enabled) Color.White else Color.White.copy(alpha = 0.4f)
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .widthIn(min = 64.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}

@Composable
private fun MoreItem(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    // The menu is drawn in the app's theme, not the viewer's white-on-black.
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    DropdownMenuItem(
        text = {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
        leadingIcon = { Icon(icon, null, tint = tint) },
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 14.dp),
        modifier = Modifier.widthIn(min = 200.dp),
    )
}

/**
 * The star on a viewer's bar: gold when starred, an outline when not.
 *
 * The icon changes the moment it is tapped; that change is the confirmation,
 * as it is in every photo app, rather than a message over the picture.
 */
@Composable
fun FavoriteToggle(starred: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            if (starred) Icons.Default.Star else Icons.Default.StarBorder,
            if (starred) "Remove from favorites" else "Add to favorites",
            tint = if (starred) FAVORITE_GOLD else LocalContentColor.current,
        )
    }
}

/** The other half of "delete is recoverable". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(api: CloudHubApi, canWrite: Boolean, onBack: () -> Unit) {
    var listing by remember { mutableStateOf<nl.tippie.cloudhub.net.TrashListing?>(null) }
    var busy by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        busy = true
        runCatching { api.trash() }
            .onSuccess { listing = it }
            .onFailure { note = it.message }
        busy = false
    }

    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(note) { note?.let { snackbar.showSnackbar(it); note = null } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Trash") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (canWrite && (listing?.entries?.isNotEmpty() == true)) {
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { api.emptyTrash() }
                                    .onSuccess { note = it.message }
                                    .onFailure { note = it.message }
                                reload()
                            }
                        }) { Text("Empty") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            listing?.let { data ->
                Text(
                    when {
                        !data.enabled -> "This server deletes files permanently; nothing is kept here."
                        data.retentionDays > 0 -> "Deleted items are kept for ${data.retentionDays} days."
                        else -> "Deleted items are kept until the trash is emptied."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                if (data.entries.isEmpty()) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) { Text("The trash is empty.") }
                } else {
                    LazyColumn {
                        items(data.entries, key = { it.id }) { entry ->
                            ListItem(
                                headlineContent = { Text(entry.name, maxLines = 1) },
                                supportingContent = {
                                    Text(
                                        buildString {
                                            append(if (entry.isDirectory) "Folder · ${entry.files} files"
                                                   else humanBytes(entry.bytes))
                                            append(" · from ")
                                            append(entry.originalPath.substringBeforeLast('/', "").ifEmpty { "/" })
                                            entry.deletedBy?.let { append(" · by $it") }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                                trailingContent = {
                                    if (canWrite) Row {
                                        TextButton(onClick = {
                                            scope.launch {
                                                runCatching { api.restore(entry.id) }
                                                    .onSuccess { note = it.message }
                                                    .onFailure { note = it.message }
                                                reload()
                                            }
                                        }) { Text("Restore") }
                                    }
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
