package nl.tippie.cloudhub.ui

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nl.tippie.cloudhub.CloudHubApp
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.FileEntry
import kotlin.math.abs

/**
 * One page of the photo viewer: a photo drawn to fit with its shape kept,
 * which pinches and double-taps to zoom, pans and flings when zoomed, and
 * hands a drag to the pager when the drag is the pager's (see [PhotoZoom]).
 *
 * It is drawn in up to three layers, each fitted to the page the same way so
 * they line up exactly:
 *
 *  - the grid's thumbnail, usually still in memory, so there is a picture the
 *    instant the page is -- soft, until
 *  - the photo itself, decoded to fit the page, replaces it; and once zoomed
 *    far enough for the difference to show,
 *  - a larger decode of the same bytes is drawn over that.
 *
 * Each arrives on top of the last, so the page never goes blank waiting for
 * a better picture, and a sharper one that fails to decode -- too big for a
 * small phone -- costs nothing but the sharpness.
 */
// canPan is the overload that lets a drag fall through to the pager, and it is
// still marked experimental in foundation 1.7 -- opted into deliberately: it is
// the API for this, and the alternative is a hand-written pointer loop that
// would do the same thing with more to get wrong.
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PhotoPage(
    api: CloudHubApi,
    entry: FileEntry,
    /** The page the pager has settled on. Only it zooms, and only it decodes a sharper picture. */
    current: Boolean,
    onTap: () -> Unit,
    /** The photo went from fitting the page to zoomed in. */
    onZoomedIn: () -> Unit,
    /** The photo itself has arrived, so its bytes are in the image cache for the details. */
    onLoaded: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Zoom belongs to the photo on show, so leaving it and coming back shows
    // the picture rather than where somebody had magnified it.
    val zoom = remember { PhotoZoomState(scope) }
    val decay = rememberSplineBasedDecay<Float>()
    val reduceMotion = LocalReduceMotion.current
    val tap by rememberUpdatedState(onTap)
    val zoomedIn by rememberUpdatedState(onZoomedIn)
    val arrived by rememberUpdatedState(onLoaded)

    /*
     * SVG is refused by the preview route -- served inline from the server's
     * origin it could run script -- and is not something the image loader
     * draws either. Asking anyway would be a request certain to fail, and a
     * Retry that could never help.
     */
    val drawable = entry.ext != "svg"

    var attempt by remember { mutableIntStateOf(0) }
    var thumb by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    var photo by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    // Whether a larger decode would show more, and whether one has been asked for.
    var sharpenable by remember { mutableStateOf(false) }
    var sharp by remember { mutableStateOf(false) }

    // Versioned like a thumbnail, so a file replaced under the same name is
    // never drawn -- or shared -- from the cache's copy of the old one.
    val previewUrl = remember(entry.path, entry.modified) { api.previewUrl(entry.path, entry.modified).toString() }
    // The very URL the grid drew this photo's card from, so it is in memory.
    val thumbUrl = remember(entry.path, entry.modified) { api.thumbnailUrl(entry).toString() }
    val photoRequest = remember(previewUrl, attempt) {
        ImageRequest.Builder(context)
            .data(previewUrl)
            // A retry has to be a different request, or it is the one that failed.
            .setParameter("attempt", attempt, memoryCacheKey = null)
            // Straight in, over the thumbnail. The thumbnail goes the moment
            // this arrives, so a fade would fade up from black, not from it.
            .crossfade(false)
            .build()
    }
    val sharpRequest = remember(previewUrl) {
        ImageRequest.Builder(context)
            .data(previewUrl)
            .size(PhotoZoom.DETAIL_EDGE, PhotoZoom.DETAIL_EDGE)
            .scale(Scale.FIT)
            // Never larger than the photo really is.
            .precision(Precision.INEXACT)
            // Its own place in the memory cache: filed under the URL alone,
            // each decode would push the other one out.
            .memoryCacheKey("$previewUrl#sharp")
            .build()
    }

    val showing = photo is AsyncImagePainter.State.Success || thumb is AsyncImagePainter.State.Success
    val zoomable = current && showing && drawable

    LaunchedEffect(current) { if (!current) zoom.reset() }
    LaunchedEffect(zoom) {
        snapshotFlow { PhotoZoom.isZoomed(zoom.scale) }.collect { if (it) zoomedIn() }
    }
    LaunchedEffect(current, sharpenable) {
        if (!current || !sharpenable) {
            sharp = false
            return@LaunchedEffect
        }
        snapshotFlow { zoom.scale > PhotoZoom.DETAIL_SCALE }.first { it }
        sharp = true
    }

    val transform = rememberTransformableState { zoomChange, pan, _ -> zoom.transform(zoomChange, pan) }

    Box(
        Modifier
            .fillMaxSize()
            // A zoomed photo is larger than its page; without this it would be
            // drawn over its neighbours as they slide in.
            .clipToBounds()
            .onSizeChanged { zoom.measured(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(zoom) { followFingers(zoom, decay) }
            /*
             * canPan is what fixes the swipe.
             *
             * The old gesture detector consumed every drag past the touch slop,
             * zoomed in or not, so the pager under it never saw one and swiping
             * did nothing. Declining a drag here leaves it for the pager, which
             * is exactly what "show me the next photo" is. The width is the
             * photo's as drawn, not the page's: a photo narrower than the page
             * has less room to pan.
             */
            .transformable(
                state = transform,
                canPan = { pan ->
                    PhotoZoom.panBelongsToPhoto(zoom.scale, zoom.offsetX, pan.x, pan.y, zoom.drawnW, zoom.viewportW)
                },
                enabled = zoomable,
            )
            .pointerInput(zoomable, reduceMotion) {
                detectTapGestures(
                    onTap = { tap() },
                    onDoubleTap = { at -> if (zoomable) zoom.toggleAt(at, animate = !reduceMotion) },
                )
            }
            // The controls come and go with a tap, which a screen reader has
            // to be able to make too.
            .semantics { onClick(label = "Show or hide the controls") { tap(); true } },
        contentAlignment = Alignment.Center,
    ) {
        if (drawable) {
            // One transform for every layer, read while drawing, so a pinch
            // moves pixels without recomposing anything.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom.scale
                        scaleY = zoom.scale
                        translationX = zoom.offsetX
                        translationY = zoom.offsetY
                    },
            ) {
                if (photo !is AsyncImagePainter.State.Success) {
                    // The grid's own thumbnail, from the cache it was just
                    // drawn from, while the original arrives.
                    AsyncImage(
                        model = thumbUrl,
                        contentDescription = null,
                        imageLoader = CloudHubApp.thumbnailsOf(context),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                        onState = { state ->
                            thumb = state
                            if (state is AsyncImagePainter.State.Success) {
                                val drawn = state.result.drawable
                                zoom.drew(drawn.intrinsicWidth.toFloat(), drawn.intrinsicHeight.toFloat())
                            }
                        },
                    )
                }
                AsyncImage(
                    model = photoRequest,
                    contentDescription = entry.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    onState = { state ->
                        photo = state
                        if (state is AsyncImagePainter.State.Success) {
                            val drawn = state.result.drawable
                            zoom.drew(drawn.intrinsicWidth.toFloat(), drawn.intrinsicHeight.toFloat())
                            // Never for an animation: two copies playing out of
                            // step would show through each other's clear pixels.
                            sharpenable = drawn !is android.graphics.drawable.Animatable &&
                                PhotoZoom.needsDetail(
                                    drawn.intrinsicWidth, drawn.intrinsicHeight,
                                    zoom.viewportW.toInt(), zoom.viewportH.toInt(),
                                )
                            arrived()
                        }
                    },
                )
                if (sharp) {
                    AsyncImage(
                        model = sharpRequest,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        PageStatus(
            drawable = drawable,
            photo = photo,
            hasThumbnail = thumb is AsyncImagePainter.State.Success,
            onRetry = { attempt++ },
        )
    }
}

/**
 * What the page says when the photo is not simply there: still coming, not
 * possible to draw, or failed with a way to try again.
 */
@Composable
private fun PageStatus(
    drawable: Boolean,
    photo: AsyncImagePainter.State,
    hasThumbnail: Boolean,
    onRetry: () -> Unit,
) {
    val loading = drawable && (photo is AsyncImagePainter.State.Loading || photo is AsyncImagePainter.State.Empty)
    // Held back for a moment, so a photo that comes straight from the cache
    // does not flash a spinner on its way in.
    var spin by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        spin = false
        if (loading) {
            delay(SPINNER_DELAY_MS)
            spin = true
        }
    }

    when {
        !drawable -> PageMessage(
            icon = { Icon(Icons.Default.Image, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(48.dp)) },
            title = "SVG drawings can't be shown here",
            detail = "Download it, or open it in another app from More.",
        )
        photo is AsyncImagePainter.State.Error -> PageMessage(
            icon = { Icon(Icons.Default.BrokenImage, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(48.dp)) },
            title = if (hasThumbnail) "The full photo did not load" else "This photo did not load",
            detail = photo.result.throwable.message,
            action = { OutlinedButton(onClick = onRetry) { Text("Retry", color = Color.White) } },
        )
        // On a dark disc: it may be spinning over a bright, blurry thumbnail.
        loading && spin -> Box(
            Modifier.background(SCRIM, CircleShape).padding(10.dp),
        ) {
            CircularProgressIndicator(
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.2f),
                strokeWidth = 3.dp,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/** Behind white text or a spinner that may be drawn over a photo. */
private val SCRIM = Color.Black.copy(alpha = 0.55f)

@Composable
private fun PageMessage(
    icon: @Composable () -> Unit,
    title: String,
    detail: String?,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier
            .padding(32.dp)
            .background(SCRIM, RoundedCornerShape(20.dp))
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Spacer(Modifier.height(14.dp))
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        detail?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        action?.let {
            Spacer(Modifier.height(18.dp))
            it()
        }
    }
}

/**
 * Where the fingers are, and how fast they were going when they let go.
 *
 * Listens on the Initial pass and consumes nothing: who owns a gesture stays
 * transformable's decision and the pager's, exactly as before. This only
 * supplies what transformable does not report -- the point a pinch is centred
 * on, and the speed a drag ends at -- and stops any animation the moment a
 * finger lands, so a fling can be caught.
 */
private suspend fun PointerInputScope.followFingers(zoom: PhotoZoomState, decay: DecayAnimationSpec<Float>) {
    val tracker = VelocityTracker()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        zoom.begin(down.position)
        tracker.resetTracking()
        tracker.addPosition(down.uptimeMillis, down.position)
        var fingers = 1
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            val centroid = event.calculateCentroid(useCurrent = true)
            if (centroid.isSpecified) zoom.centroid = centroid
            // A finger landing or lifting moves the centroid without anything
            // having moved; a speed measured across that would be a jump.
            if (pressed.size != fingers) {
                tracker.resetTracking()
                fingers = pressed.size
            }
            if (centroid.isSpecified) tracker.addPosition(pressed.first().uptimeMillis, centroid)
        }
        zoom.release(if (fingers == 1) tracker.calculateVelocity() else Velocity.Zero, decay)
    }
}

/**
 * One photo's zoom and pan, and the animation moving them, if any.
 *
 * Plain state rather than an Animatable per value: a pinch writes all three
 * at once, every frame, and a double tap or a fling drives them together --
 * so there is one owner, and one job that the next touch cancels.
 */
@Stable
internal class PhotoZoomState(private val scope: CoroutineScope) {

    var scale by mutableFloatStateOf(PhotoZoom.MIN_SCALE)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set

    /** The page, as measured. */
    var viewportW = 0f
        private set
    var viewportH = 0f
        private set

    // The picture's own size, from whichever decode arrived last. Only its
    // shape matters here, and every decode of one photo has the same shape.
    private var imageW = 0f
    private var imageH = 0f

    /** The photo as drawn at 1x: fitted to the page, its shape kept. */
    val drawnW: Float get() = imageW * PhotoZoom.fitScale(imageW, imageH, viewportW, viewportH)
    val drawnH: Float get() = imageH * PhotoZoom.fitScale(imageW, imageH, viewportW, viewportH)

    /**
     * Where the fingers are, from the gesture observer. transformable reports
     * how far they moved, not where they are, and a pinch zooms about them.
     */
    var centroid: Offset = Offset.Unspecified

    /** Whether the gesture under way has moved the picture, rather than the pager. */
    private var panned = false
    private var motion: Job? = null

    fun measured(width: Float, height: Float) {
        viewportW = width
        viewportH = height
        keepOnScreen()
    }

    fun drew(width: Float, height: Float) {
        if (width <= 0f || height <= 0f || (width == imageW && height == imageH)) return
        imageW = width
        imageH = height
        keepOnScreen()
    }

    /** A turned phone, or a picture that arrived: still within reach either way. */
    private fun keepOnScreen() {
        offsetX = PhotoZoom.clampPan(offsetX, scale, drawnW, viewportW)
        offsetY = PhotoZoom.clampPan(offsetY, scale, drawnH, viewportH)
    }

    /** A finger landed: whatever was moving the picture stops where it is. */
    fun begin(at: Offset) {
        motion?.cancel()
        motion = null
        panned = false
        centroid = at
    }

    /** One step of a pinch or a drag, as transformable reports it. */
    fun transform(zoomChange: Float, pan: Offset) {
        // Where the fingers were before this step moved them, from the
        // middle of the page: the point that has to stay under them.
        val fingers = if (centroid.isSpecified) centroid - pan else Offset(viewportW / 2f, viewportH / 2f)
        val focusX = fingers.x - viewportW / 2f
        val focusY = fingers.y - viewportH / 2f

        val next = PhotoZoom.scaled(scale, zoomChange)
        val x = PhotoZoom.zoomedAbout(offsetX, focusX, scale, next) + pan.x
        val y = PhotoZoom.zoomedAbout(offsetY, focusY, scale, next) + pan.y
        scale = next
        if (PhotoZoom.isZoomed(next)) {
            // Clamped to the photo as drawn, so a picture cannot be dragged
            // off the screen, or into its own black bars, and left there.
            offsetX = PhotoZoom.clampPan(x, next, drawnW, viewportW)
            offsetY = PhotoZoom.clampPan(y, next, drawnH, viewportH)
            if (pan != Offset.Zero) panned = true
        } else {
            offsetX = 0f
            offsetY = 0f
        }
    }

    /** Double tap: into the point tapped, or back out to the whole photo. */
    fun toggleAt(tap: Offset, animate: Boolean) {
        val target = PhotoZoom.afterDoubleTap(scale, PhotoZoom.fillScale(drawnW, drawnH, viewportW, viewportH))
        if (!PhotoZoom.isZoomed(target)) {
            moveTo(PhotoZoom.MIN_SCALE, 0f, 0f, animate)
            return
        }
        val x = PhotoZoom.zoomedAbout(offsetX, tap.x - viewportW / 2f, scale, target)
        val y = PhotoZoom.zoomedAbout(offsetY, tap.y - viewportH / 2f, scale, target)
        moveTo(
            target,
            PhotoZoom.clampPan(x, target, drawnW, viewportW),
            PhotoZoom.clampPan(y, target, drawnH, viewportH),
            animate,
        )
    }

    /**
     * The fingers let go. A drag that was moving the picture carries on and
     * slows, stopping at the photo's edge; one that was the pager's, or a
     * pinch, does nothing more.
     */
    fun release(velocity: Velocity, decay: DecayAnimationSpec<Float>) {
        if (!panned || !PhotoZoom.isZoomed(scale)) return
        val limitX = PhotoZoom.panLimit(scale, drawnW, viewportW)
        val limitY = PhotoZoom.panLimit(scale, drawnH, viewportH)
        motion?.cancel()
        motion = scope.launch {
            launch { glide(offsetX, velocity.x, limitX, decay) { offsetX = it } }
            launch { glide(offsetY, velocity.y, limitY, decay) { offsetY = it } }
        }
    }

    fun reset() {
        motion?.cancel()
        motion = null
        scale = PhotoZoom.MIN_SCALE
        offsetX = 0f
        offsetY = 0f
    }

    private fun moveTo(targetScale: Float, x: Float, y: Float, animate: Boolean) {
        motion?.cancel()
        if (!animate) {
            motion = null
            scale = targetScale
            offsetX = x
            offsetY = y
            return
        }
        val fromScale = scale
        val fromX = offsetX
        val fromY = offsetY
        motion = scope.launch {
            animate(0f, 1f, animationSpec = tween(ZOOM_MS, easing = FastOutSlowInEasing)) { t, _ ->
                scale = fromScale + (targetScale - fromScale) * t
                offsetX = fromX + (x - fromX) * t
                offsetY = fromY + (y - fromY) * t
            }
        }
    }

    /** One axis of a fling, which stops dead at the photo's edge rather than overshooting it. */
    private suspend fun glide(
        from: Float,
        velocity: Float,
        limit: Float,
        decay: DecayAnimationSpec<Float>,
        set: (Float) -> Unit,
    ) {
        if (limit <= 0f || abs(velocity) < MIN_FLING) return
        AnimationState(initialValue = from, initialVelocity = velocity.coerceIn(-MAX_FLING, MAX_FLING))
            .animateDecay(decay) {
                val kept = value.coerceIn(-limit, limit)
                set(kept)
                if (kept != value) cancelAnimation()
            }
    }

    private companion object {
        const val ZOOM_MS = 260
        /** Pixels a second; slower than this a drag simply stops where it was let go. */
        const val MIN_FLING = 300f
        const val MAX_FLING = 12_000f
    }
}

private const val SPINNER_DELAY_MS = 400L
