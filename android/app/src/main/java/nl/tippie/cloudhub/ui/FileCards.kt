package nl.tippie.cloudhub.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.FileEntry

/* -------------------------------------------------------------------------
 * Dimensions the card and its skeleton must agree on.
 *
 * A skeleton that merely resembles the real card is how a crossfade turns
 * into a jump: the placeholder is 4dp shorter, and every row below it shifts
 * when the content lands. These are shared so that cannot happen.
 * ---------------------------------------------------------------------- */

val CARD_CORNER = 18.dp
val CARD_PREVIEW_RATIO = 4f / 3f
val CARD_TEXT_HEIGHT = 58.dp
val ROW_HEIGHT = 68.dp

/** Roughly how tall a grid card is, for sizing the skeleton to the viewport. */
fun cardHeightDp(columnWidthDp: Int): Int =
    (columnWidthDp / CARD_PREVIEW_RATIO).toInt() + CARD_TEXT_HEIGHT.value.toInt()

/**
 * The grid card's shape, filled by whoever is calling.
 *
 * Both the real tile and its skeleton go through here, so the corners, the
 * preview ratio and the text block are defined once. Adding a line to the card
 * changes the placeholder in the same edit.
 */
@Composable
fun FileCardScaffold(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surface,
    preview: @Composable BoxScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(CARD_CORNER),
        color = container,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(CARD_PREVIEW_RATIO)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
                content = preview,
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = CARD_TEXT_HEIGHT)
                    .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
                content = body,
            )
        }
    }
}

/* ---- shimmer ------------------------------------------------------------- */

/**
 * The sweep that makes a skeleton read as loading rather than as broken.
 *
 * Deliberately one animation for a whole screen of cards: [rememberShimmer]
 * is called once by the list and its progress passed down, rather than each
 * placeholder starting an infinite transition of its own. Thirty cards each
 * driving their own would be thirty recomposition sources for one effect.
 */
@Composable
fun rememberShimmer(): Float {
    if (LocalReduceMotion.current) return SHIMMER_STILL
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "shimmer")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_250, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    ).value
}

/** With Reduce Motion on, the band sits still in the middle rather than sweeping. */
private const val SHIMMER_STILL = 0.5f

/**
 * A placeholder block: the base tint, and a soft band moving across it.
 *
 * Drawn in `drawWithCache` so the brush is rebuilt only when the size or the
 * progress changes, not on every composition of the card.
 */
fun Modifier.shimmer(progress: Float, base: Color, highlight: Color): Modifier =
    this.drawWithCache {
        val span = size.width * 2f
        val start = -span + progress * (size.width + span)
        val brush = Brush.linearGradient(
            colorStops = arrayOf(0f to base, 0.5f to highlight, 1f to base),
            start = Offset(start, 0f),
            end = Offset(start + span, size.height),
        )
        onDrawBehind { drawRect(brush) }
    }

/** One placeholder rectangle at the card's own corner radius. */
@Composable
fun SkeletonBlock(
    progress: Float,
    modifier: Modifier = Modifier,
    corner: Dp = 6.dp,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .shimmer(
                progress,
                // Low contrast on purpose: a placeholder that pulses brightly
                // draws the eye to the part of the screen with no information.
                base = scheme.onSurface.copy(alpha = 0.08f),
                highlight = scheme.onSurface.copy(alpha = 0.16f),
            )
    )
}

/**
 * A card-shaped placeholder.
 *
 * Same scaffold as the real card, so the crossfade cannot shift the layout.
 * Hidden from screen readers: TalkBack reading out a dozen empty cards as
 * content is worse than silence, and the list announces "Loading" once.
 */
@Composable
fun SkeletonTile(progress: Float, modifier: Modifier = Modifier) {
    FileCardScaffold(
        modifier = modifier.clearAndSetSemantics { },
        preview = { SkeletonBlock(progress, Modifier.fillMaxSize(), corner = 0.dp) },
        body = {
            SkeletonBlock(progress, Modifier.fillMaxWidth(0.75f).height(14.dp))
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkeletonBlock(progress, Modifier.width(52.dp).height(11.dp))
                Spacer(Modifier.weight(1f))
                // Where the overflow button will be, so it does not pop in.
                SkeletonBlock(progress, Modifier.size(18.dp), corner = 9.dp)
                Spacer(Modifier.width(10.dp))
            }
        },
    )
}

/** The list view's placeholder, matching [FileRow]'s height and rhythm. */
@Composable
fun SkeletonRow(progress: Float, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .padding(horizontal = 16.dp)
            .clearAndSetSemantics { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonBlock(progress, Modifier.size(46.dp), corner = 12.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            SkeletonBlock(progress, Modifier.fillMaxWidth(0.55f).height(14.dp))
            Spacer(Modifier.height(7.dp))
            SkeletonBlock(progress, Modifier.width(70.dp).height(11.dp))
        }
    }
}

/* ---- the real card ------------------------------------------------------- */

/**
 * One file or folder in the grid.
 *
 * The press feedback is a scale rather than a colour change: at this size a
 * tint is hard to see under a thumbnail, and a card that gives slightly under
 * the finger reads as responsive.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileTile(
    api: CloudHubApi,
    entry: FileEntry,
    selected: Boolean,
    showFolder: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && !LocalReduceMotion.current) 0.96f else 1f,
        spring(dampingRatio = 0.62f, stiffness = 700f),
        label = "press",
    )

    FileCardScaffold(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .combinedClickable(
                interactionSource = interaction,
                indication = androidx.compose.material3.ripple(),
                onClick = onOpen,
                onLongClick = onLongPress,
                onClickLabel = if (entry.isDirectory) "Open folder" else "Open file",
            ),
        container = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        preview = { Preview(api, entry, Modifier.fillMaxSize()) },
        body = {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    subtitle(entry, showFolder),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onMenu, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.MoreVert, "Actions for ${entry.name}", Modifier.size(18.dp))
                }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    api: CloudHubApi,
    entry: FileEntry,
    selected: Boolean,
    showFolder: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onLongPress,
                onClickLabel = if (entry.isDirectory) "Open folder" else "Open file",
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Preview(api, entry, Modifier.fillMaxSize()) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle(entry, showFolder), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onMenu) { Icon(Icons.Default.MoreVert, "Actions for ${entry.name}") }
    }
}

/* ---- previews ------------------------------------------------------------ */

/**
 * A picture where there is one, an icon where there is not.
 *
 * The image goes through SubcomposeAsyncImage so its own loading state gets the
 * same shimmer as the card skeleton -- the placeholder occupies the final size
 * from the first frame, so the thumbnail arriving changes nothing but colour.
 *
 * Folders have no preview: CloudHub's listing does not carry one, and fetching
 * each folder's contents to build a mosaic would be one extra request per card
 * on screen.
 */
@Composable
private fun Preview(api: CloudHubApi, entry: FileEntry, modifier: Modifier) {
    val context = LocalContext.current
    when (entry.kind) {
        FileEntry.Kind.FOLDER -> FolderGlyph(modifier)

        FileEntry.Kind.IMAGE -> ThumbnailImage(
            url = api.thumbnailUrl(entry).toString(),
            description = entry.name,
            modifier = modifier,
        )

        FileEntry.Kind.VIDEO -> Box(modifier, contentAlignment = Alignment.Center) {
            ThumbnailImage(
                url = if (entry.hasThumbnail) api.thumbnailUrl(entry).toString()
                else api.streamUrl(entry.path).toString(),
                description = entry.name,
                modifier = Modifier.fillMaxSize(),
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    Icons.Default.PlayArrow, null,
                    tint = Color.White,
                    modifier = Modifier.padding(6.dp),
                )
            }
        }

        else -> Icon(
            iconFor(entry), null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(30.dp),
        )
    }
}

@Composable
private fun ThumbnailImage(url: String, description: String, modifier: Modifier) {
    val context = LocalContext.current
    val progress = rememberShimmer()
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context).data(url).crossfade(true).build(),
        contentDescription = description,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    ) {
        when (painter.state) {
            is AsyncImagePainter.State.Loading ->
                SkeletonBlock(progress, Modifier.fillMaxSize(), corner = 0.dp)
            is AsyncImagePainter.State.Error -> Icon(
                Icons.Default.InsertDriveFile, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            else -> SubcomposeAsyncImageContent()
        }
    }
}

/**
 * A folder, tinted rather than grey.
 *
 * Folders make up most of a browsing screen, and a wall of identical grey
 * glyphs is what made the old grid look unfinished.
 */
@Composable
private fun FolderGlyph(modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier.background(
            Brush.linearGradient(
                listOf(
                    scheme.primary.copy(alpha = 0.16f),
                    scheme.secondary.copy(alpha = 0.10f),
                ),
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Folder, null,
            tint = scheme.primary.copy(alpha = 0.85f),
            modifier = Modifier.fillMaxSize(0.42f),
        )
    }
}

fun iconFor(entry: FileEntry): ImageVector = when (entry.kind) {
    FileEntry.Kind.FOLDER -> Icons.Default.Folder
    FileEntry.Kind.AUDIO -> Icons.Default.MusicNote
    FileEntry.Kind.PDF -> Icons.Default.PictureAsPdf
    FileEntry.Kind.TEXT -> Icons.Default.Description
    else -> Icons.Default.InsertDriveFile
}

fun subtitle(entry: FileEntry, showFolder: Boolean): String {
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
    return String.format(java.util.Locale.US, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[index])
}
