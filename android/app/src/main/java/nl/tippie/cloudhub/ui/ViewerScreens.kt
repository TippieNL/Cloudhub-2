package nl.tippie.cloudhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.CloudHubClient
import nl.tippie.cloudhub.net.FileEntry

/**
 * Full-screen image viewing, swiping between everything in the folder.
 *
 * The web app opens one file at a time; on a phone, moving between photos with
 * a swipe is the whole point, so the viewer takes the folder rather than a
 * single file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewer(api: CloudHubApi, images: List<FileEntry>, startAt: Int, onBack: () -> Unit) {
    val pager = rememberPagerState(initialPage = startAt.coerceIn(0, maxOf(0, images.size - 1))) { images.size }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(images.getOrNull(pager.currentPage)?.name.orEmpty(), maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.6f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().padding(padding)) { page ->
            var scale by remember(page) { mutableFloatStateOf(1f) }
            var offsetX by remember(page) { mutableFloatStateOf(0f) }
            var offsetY by remember(page) { mutableFloatStateOf(0f) }

            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = api.previewUrl(images[page].path).toString(),
                    contentDescription = images[page].name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .graphicsLayer(scaleX = scale, scaleY = scale,
                            translationX = offsetX, translationY = offsetY)
                        .pointerInput(page) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                // Panning only means something once zoomed in.
                                if (scale > 1f) { offsetX += pan.x; offsetY += pan.y }
                                else { offsetX = 0f; offsetY = 0f }
                            }
                        },
                )
            }
        }
    }
}

/**
 * Video and audio.
 *
 * ExoPlayer fetches through the app's own OkHttp client so the request carries
 * the session cookie, and because the server answers byte ranges with 206
 * (serve_file_range in public/index.php) seeking works with no extra work.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(api: CloudHubApi, client: CloudHubClient, entry: FileEntry, onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember {
        val factory = OkHttpDataSource.Factory(client.okHttp)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(api.streamUrl(entry.path).toString()))
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(entry.name, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.6f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; useController = true } },
            modifier = Modifier.fillMaxSize().padding(padding),
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
