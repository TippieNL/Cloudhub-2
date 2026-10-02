package nl.tippie.cloudhub.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowWidthSizeClass

/*
 * The app on a foldable.
 *
 * A Pixel Fold is two devices in one: folded, an ordinary phone; unfolded, a
 * nearly square screen around 880dp wide; half-folded flat on a table, a
 * screen with a hinge across the middle. These decide which of those the app
 * is on now. All of them read the window rather than the device, so they are
 * equally right in split screen, on a tablet or in a desktop window -- and they
 * change as the phone is folded, without the activity restarting (the manifest
 * keeps configuration changes for itself).
 */

/** Wide enough for a navigation rail beside the content: unfolded, or a phone on its side. */
@Composable
fun isWideWindow(): Boolean =
    currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass != WindowWidthSizeClass.COMPACT

/**
 * Where the hinge is when the phone stands half-folded like a laptop.
 *
 * Returns the hinge's top edge in window pixels, or null when the phone is not
 * in that posture. A video then belongs above the fold and its controls
 * below, where they can be reached without holding the screen up.
 */
@Composable
fun tabletopHingeTop(): Float? {
    val posture = currentWindowAdaptiveInfo().windowPosture
    if (!posture.isTabletop) return null
    return posture.hingeList.firstOrNull()?.bounds?.top
}

/** The width text-heavy screens keep to on a wide screen; about a phone and a half. */
val READABLE_MAX_WIDTH = 720.dp

/**
 * Keep a screen of settings or figures a readable width, centred.
 *
 * Unfolded, a row of label-and-switch stretched to 880dp puts the switch a
 * hand's width from what it controls. Applied after the scroll modifier, so
 * the whole width still scrolls.
 */
fun Modifier.readableWidth(): Modifier =
    this.wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = READABLE_MAX_WIDTH)

/** The places the rail offers, in the order the overflow menu lists them. */
enum class RailPlace(val label: String, val icon: ImageVector) {
    FILES("Files", Icons.Default.Folder),
    FAVORITES("Favorites", Icons.Default.Star),
    TRASH("Trash", Icons.Default.Delete),
    STORAGE("Storage", Icons.Default.PieChart),
    DUPLICATES("Duplicates", Icons.Default.ContentCopy),
    SETTINGS("Settings", Icons.Default.Settings),
}

/**
 * The app's places down the side, when there is room for them.
 *
 * On a phone they live in the overflow menu, two taps away; unfolded there is
 * width to spare, and the rail puts every place one tap away from every other.
 * [current] null means the screen on show is not one of them, and no rail is
 * drawn -- the viewer and the player keep the whole screen.
 */
@Composable
fun WithRail(
    current: RailPlace?,
    onSelect: (RailPlace) -> Unit,
    content: @Composable () -> Unit,
) {
    if (current == null || !isWideWindow()) {
        content()
        return
    }
    Row(Modifier.fillMaxSize()) {
        NavigationRail {
            Spacer(Modifier.height(8.dp))
            RailPlace.entries.forEach { place ->
                NavigationRailItem(
                    selected = place == current,
                    onClick = { if (place != current) onSelect(place) },
                    icon = { Icon(place.icon, null) },
                    label = { Text(place.label) },
                )
            }
        }
        Box(Modifier.weight(1f)) { content() }
    }
}
