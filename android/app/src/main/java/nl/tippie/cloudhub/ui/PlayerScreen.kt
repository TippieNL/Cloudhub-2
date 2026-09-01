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
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import nl.tippie.cloudhub.data.MediaCache
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
    // The controller starts hidden -- PlayerView has not been told to show it.
    // Claiming otherwise left the gesture overlay uncomposed for the first
    // taps and composed for good after the first genuine hide.
    var controlsVisible by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<PlayerView?>(null) }
    var seekFeedback by remember { mutableStateOf<Boolean?>(null) }   // true = forward

    /*
     * Ask the view, not our copy of what the view said.
     *
     * controlsVisible is a mirror kept by a listener, and a mirror can be
     * stale -- it already was at startup. Reading the controller's own state
     * means a stale mirror can at worst compose an overlay that still works,
     * never one that traps you.
     */
    val toggleControls = {
        view?.let { if (it.isControllerFullyVisible) it.hideController() else it.showController() }
        Unit
    }

    val player = remember {
        /*
         * Read through a disk cache, so bytes are fetched once.
         *
         * Skipping back ten seconds used to re-fetch ten seconds that had just
         * arrived, and re-opening a film downloaded it again from the start --
         * which resume makes worse, dropping you halfway into a file the
         * player then has to reach from scratch.
         *
         * FLAG_IGNORE_CACHE_ON_ERROR: a cache that cannot be written must cost
         * a cache, never the video.
         */
        val source = CacheDataSource.Factory()
            .setCache(MediaCache.get(context))
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(client.okHttp))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .apply {
                // A film bigger than the cache cannot be held by it, and
                // trying churns the whole cache while it plays -- including
                // evicting spans still being read, which is how a large video
                // stops playing rather than merely playing uncached. Those
                // read through: no writes, but anything already cached is
                // still served from there.
                if (!PlaybackTuning.mayCache(entry.size)) setCacheWriteDataSinkFactory(null)
            }

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(source))
            // Media3's defaults are written for the public internet: 2.5
            // seconds of video buffered before anything is shown, and nothing
            // kept behind the playhead. See ui/PlaybackTuning.kt.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        PlaybackTuning.MIN_BUFFER_MS,
                        PlaybackTuning.MAX_BUFFER_MS,
                        PlaybackTuning.BUFFER_FOR_PLAYBACK_MS,
                        PlaybackTuning.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                    )
                    .setBackBuffer(PlaybackTuning.BACK_BUFFER_MS, /* retainBackBufferFromKeyframe = */ true)
                    .build()
            )
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
                setMediaItem(
                    MediaItem.Builder()
                        .setUri(api.streamUrl(entry.path).toString())
                        // Keyed on the URL alone, replacing a file with a
                        // different video of the same name would play the old
                        // one out of the cache for good.
                        .setCustomCacheKey(PlaybackTuning.cacheKey(entry.path, entry.modified))
                        .build()
                )
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
             * Tap to show or hide the controls; double-tap the sides to seek.
             *
             * One detector for the whole video rather than a box per zone. The
             * three separate boxes this replaces each needed their own onTap
             * handler and two of them did not have one -- so once the controls
             * hid, every tap landed on an overlay that consumed it and did
             * nothing, and they could never be brought back.
             *
             * Still only composed while the controls are hidden: over the
             * visible transport controls it would swallow every button press.
             */
            if (!controlsVisible) {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { toggleControls() },
                            onDoubleTap = { offset ->
                                when (zoneAt(offset.x, size.width.toFloat())) {
                                    TapZone.SEEK_BACK -> { player.seekBack(); seekFeedback = false }
                                    TapZone.SEEK_FORWARD -> { player.seekForward(); seekFeedback = true }
                                    // No seek in the middle, so it does what a
                                    // single tap there would.
                                    TapZone.MIDDLE -> toggleControls()
                                }
                            },
                        )
                    }
                )
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

/**
 * Which third of the video a tap landed in.
 *
 * Pure, so the geometry can be checked without a device -- and separating it
 * from the gesture handling is what leaves one place where a tap is acted on
 * rather than three that each had to remember to.
 */
internal enum class TapZone { SEEK_BACK, MIDDLE, SEEK_FORWARD }

internal fun zoneAt(x: Float, width: Float): TapZone {
    // A width of zero is possible before the first layout pass; treating it as
    // the middle means a tap toggles rather than seeking somewhere arbitrary.
    if (width <= 0f) return TapZone.MIDDLE
    val fraction = x / width
    return when {
        fraction < SEEK_ZONE -> TapZone.SEEK_BACK
        fraction > 1f - SEEK_ZONE -> TapZone.SEEK_FORWARD
        else -> TapZone.MIDDLE
    }
}

/** How much of each edge seeks. The middle third is left for plain taps. */
private const val SEEK_ZONE = 0.35f

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
