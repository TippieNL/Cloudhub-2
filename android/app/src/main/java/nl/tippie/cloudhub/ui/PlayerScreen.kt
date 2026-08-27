package nl.tippie.cloudhub.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import nl.tippie.cloudhub.data.ResumePolicy
import nl.tippie.cloudhub.data.Settings
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.CloudHubClient
import nl.tippie.cloudhub.net.FileEntry

/** How far a double-tap jumps, matching the player's own seek increments. */
private const val SEEK_STEP_MS = 10_000L

/**
 * Video and audio playback.
 *
 * ExoPlayer fetches through the app's own OkHttp client, so the request
 * carries the session cookie, and because the server answers byte ranges with
 * 206 (serve_file_range in public/index.php) seeking works with nothing extra.
 *
 * Media3 already provides more than the first version switched on: the
 * transport controls, a settings menu offering playback speed and track
 * selection, and a fullscreen button. Most of what follows is turning those on
 * and giving the fullscreen button something to do.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    api: CloudHubApi,
    client: CloudHubClient,
    settings: Settings,
    entry: FileEntry,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val snackbar = remember { SnackbarHostState() }

    var fullscreen by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var view by remember { mutableStateOf<PlayerView?>(null) }
    var seekFeedback by remember { mutableStateOf<Boolean?>(null) }   // true = forward

    val player = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(client.okHttp)))
            // Ten seconds is what the double-tap zones promise, so the buttons
            // and the gestures must agree.
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            // Pauses for a phone call and ducks for a notification instead of
            // talking over them.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(api.streamUrl(entry.path).toString()))
                prepare()
                playWhenReady = true
            }
    }

    /* ---- resume ---------------------------------------------------------
     *
     * Seeking is deferred until the duration is known, because whether a
     * position is worth returning to depends on how close it is to the end.
     */
    var offeredResume by remember { mutableStateOf(false) }
    LaunchedEffect(player) {
        val saved = settings.resumePositionOf(entry.path)
        if (saved <= 0) { offeredResume = true; return@LaunchedEffect }
        snapshotFlowOfDuration(player).collect { duration ->
            if (offeredResume) return@collect
            if (ResumePolicy.shouldResume(saved, duration)) {
                player.seekTo(saved)
                offeredResume = true
                val result = snackbar.showSnackbar(
                    message = "Resumed at ${formatTime(saved)}",
                    actionLabel = "Start over",
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    player.seekTo(0)
                    settings.forgetResumePosition(entry.path)
                }
            } else {
                offeredResume = true
            }
        }
    }

    // Written while playing as well as on the way out: an app killed in the
    // background never reaches onDispose.
    LaunchedEffect(player) {
        while (true) {
            delay(5_000)
            if (player.isPlaying) settings.rememberResumePosition(entry.path, player.currentPosition)
        }
    }

    DisposableEffect(player, entry.path) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Nobody wants the screen to lock halfway through a film.
                activity?.window?.let { window ->
                    if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                // A finished video is finished, not paused near the end.
                if (state == Player.STATE_ENDED) settings.forgetResumePosition(entry.path)
            }
        }
        player.addListener(listener)
        onDispose {
            if (player.playbackState != Player.STATE_ENDED) {
                settings.rememberResumePosition(entry.path, player.currentPosition)
            }
            player.removeListener(listener)
            player.release()
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.setFullscreen(false)
        }
    }

    // Applied as a side effect rather than inside the click handler, so the
    // window and the state cannot disagree after a rotation.
    LaunchedEffect(fullscreen) { activity?.setFullscreen(fullscreen) }

    // Back should un-maximise before it leaves the video; being thrown out of
    // a film because you wanted the bars back is the classic annoyance here.
    //
    // It is routed through the view's own button rather than setting the state
    // directly: PlayerView owns its fullscreen icon and offers no way to tell
    // it that icon has gone stale, so the click that flips our state has to be
    // the same click it uses to flip its own.
    BackHandler(enabled = fullscreen) {
        val button = view?.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_fullscreen)
        if (button != null) button.performClick() else fullscreen = false
    }

    Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AnimatedVisibility(visible = !fullscreen, enter = fadeIn(), exit = fadeOut()) {
                TopAppBar(
                    title = { Text(entry.name, maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black.copy(alpha = 0.6f),
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                    ),
                )
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                // In fullscreen the video takes the whole window, bars and all.
                .padding(if (fullscreen) PaddingValues(0.dp) else padding)
        ) {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).also { view = it }.apply {
                        this.player = player
                        useController = true
                        setShowSubtitleButton(true)
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        setFullscreenButtonClickListener { fullscreen = !fullscreen }
                        setControllerVisibilityListener(
                            PlayerView.ControllerVisibilityListener { visibility ->
                                controlsVisible = visibility == android.view.View.VISIBLE
                            }
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            /*
             * Double-tap to seek.
             *
             * Only while the controller is hidden: an overlay on top of the
             * visible transport controls would swallow every button press.
             * With the controls up, taps belong to them.
             */
            if (!controlsVisible) {
                Row(Modifier.fillMaxSize()) {
                    SeekZone(Modifier.weight(1f)) {
                        player.seekBack(); seekFeedback = false
                    }
                    // The middle is left alone so a tap there just shows the
                    // controls rather than jumping the video.
                    Box(
                        Modifier.weight(0.6f).fillMaxHeight().pointerInput(Unit) {
                            detectTapGestures { /* falls through to show controls */ }
                        }
                    )
                    SeekZone(Modifier.weight(1f)) {
                        player.seekForward(); seekFeedback = true
                    }
                }
            }

            seekFeedback?.let { forward ->
                LaunchedEffect(forward, seekFeedback) {
                    delay(600)
                    seekFeedback = null
                }
                Box(
                    Modifier.fillMaxSize().padding(horizontal = 48.dp),
                    contentAlignment = if (forward) Alignment.CenterEnd else Alignment.CenterStart,
                ) {
                    Box(
                        Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (forward) Icons.Default.Forward10 else Icons.Default.Replay10,
                            if (forward) "Forward ten seconds" else "Back ten seconds",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeekZone(modifier: Modifier, onDoubleTap: () -> Unit) {
    Box(
        modifier.fillMaxHeight().pointerInput(Unit) {
            detectTapGestures(onDoubleTap = { onDoubleTap() })
        }
    )
}

/**
 * Immersive fullscreen: no system bars, and landscape for anything shot that
 * way. Restoring UNSPECIFIED rather than PORTRAIT leaves the phone's own
 * rotation setting in charge once the video is done.
 */
private fun Activity.setFullscreen(enabled: Boolean) {
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    if (enabled) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    } else {
        // Back to the app's baseline, which is edge-to-edge -- restoring `true`
        // here would lay every screen out differently after a video than before
        // one.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        controller.show(WindowInsetsCompat.Type.systemBars())
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Emits the duration as the player learns it, so resume can wait for it. */
private fun snapshotFlowOfDuration(player: ExoPlayer) = kotlinx.coroutines.flow.flow {
    repeat(40) {                       // ~10s of waiting, then give up quietly
        emit(if (player.duration == C.TIME_UNSET) 0L else player.duration)
        if (player.duration != C.TIME_UNSET) return@flow
        delay(250)
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
