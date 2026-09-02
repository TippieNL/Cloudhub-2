package nl.tippie.cloudhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.FileEntry
import nl.tippie.cloudhub.work.ForegroundMedia

/**
 * Full-screen image viewing, swiping between everything in the folder.
 *
 * The web app opens one file at a time; on a phone, moving between photos with
 * a swipe is the whole point, so the viewer takes the folder rather than a
 * single file.
 */
// canPan is the overload that lets a drag fall through to the pager, and it is
// still marked experimental in foundation 1.7 -- opted into deliberately: it is
// the API for this, and the alternative is a hand-written pointer loop that
// would do the same thing with more to get wrong.
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ImageViewer(
    api: CloudHubApi,
    images: List<FileEntry>,
    startAt: Int,
    /** Handed the photo on show when it closes, so the folder comes back to it. */
    onBack: (String?) -> Unit,
) {
    val pager = rememberPagerState(initialPage = startAt.coerceIn(0, maxOf(0, images.size - 1))) { images.size }

    /*
     * Photos and a queued upload share one connection, and the upload will
     * take all of it unless told otherwise -- which is what left a picture
     * loading for a second at a time while a video was going up.
     */
    DisposableEffect(Unit) {
        ForegroundMedia.enter()
        onDispose { ForegroundMedia.leave() }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(images.getOrNull(pager.currentPage)?.name.orEmpty(), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { onBack(images.getOrNull(pager.currentPage)?.path) }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.6f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
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
            modifier = Modifier.fillMaxSize().padding(padding).systemGestureExclusion(),
        ) { page ->
            // Zoom belongs to the photo on show, so leaving it and coming back
            // shows the picture rather than where somebody had magnified it.
            var scale by remember(page) { mutableFloatStateOf(PhotoZoom.MIN_SCALE) }
            var offsetX by remember(page) { mutableFloatStateOf(0f) }
            var offsetY by remember(page) { mutableFloatStateOf(0f) }
            var size by remember(page) { mutableStateOf(IntSize.Zero) }

            val transform = rememberTransformableState { zoomChange, pan, _ ->
                scale = PhotoZoom.scaled(scale, zoomChange)
                if (PhotoZoom.isZoomed(scale)) {
                    // Clamped, so a picture cannot be flung off the screen and
                    // left there with nothing to drag back.
                    offsetX = PhotoZoom.clampPan(offsetX + pan.x, scale, size.width.toFloat())
                    offsetY = PhotoZoom.clampPan(offsetY + pan.y, scale, size.height.toFloat())
                } else {
                    offsetX = 0f
                    offsetY = 0f
                }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .onSizeChanged { size = it }
                    /*
                     * canPan is what fixes the swipe.
                     *
                     * The old gesture detector consumed every drag past the
                     * touch slop, zoomed in or not, so the pager under it never
                     * saw one and swiping did nothing. Declining a drag here
                     * leaves it for the pager, which is exactly what "show me
                     * the next photo" is.
                     */
                    .transformable(
                        state = transform,
                        canPan = { pan ->
                            PhotoZoom.panBelongsToPhoto(scale, offsetX, pan.x, pan.y, size.width.toFloat())
                        },
                    )
                    .pointerInput(page) {
                        detectTapGestures(
                            onDoubleTap = {
                                scale = PhotoZoom.afterDoubleTap(scale)
                                offsetX = 0f
                                offsetY = 0f
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = api.previewUrl(images[page].path).toString(),
                    contentDescription = images[page].name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale, scaleY = scale,
                            translationX = offsetX, translationY = offsetY,
                        ),
                )
            }
        }
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
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
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
