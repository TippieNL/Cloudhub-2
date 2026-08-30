package nl.tippie.cloudhub.ui

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** The web app's palette, so the two do not look like different products. */
private val Brand = Color(0xFF1479C9)
private val BrandDark = Color(0xFF3296E6)

// A cooler second hue, used only for the sign-in background's gradient. One
// accent beside the brand blue reads as depth; a third would read as a theme.
private val Accent = Color(0xFF6F5BE8)
private val AccentDark = Color(0xFF8E7CF5)

/*
 * Every role, not most of them.
 *
 * A scheme built with lightColorScheme() keeps Material's *baseline* purple for
 * anything left unset -- and that baseline is not neutral. surfaceContainer is
 * what a DropdownMenu paints with, so the overflow menu came out lavender on a
 * blue app; surfaceTint washes every elevated surface with #6750A4. Leaving a
 * role out is not "use the default", it is "use somebody else's brand".
 */
internal val Light = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E9FA),
    onPrimaryContainer = Color(0xFF06375F),
    secondary = Accent,
    onSecondary = Color.White,
    tertiary = Color(0xFF2F7D63),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD3EFE4),
    onTertiaryContainer = Color(0xFF0C3A2C),
    background = Color(0xFFF7F7F8),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9EAEE),
    onSurfaceVariant = Color(0xFF5A5C63),
    // The elevation tint. Left unset it is Material's purple, which is what
    // gave every raised surface a lilac cast.
    surfaceTint = Brand,
    // The container ramp, lightest to darkest. Menus, sheets and search fields
    // pick from these, so they are the difference between a component looking
    // like part of this app or part of the sample one.
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFAFB),
    surfaceContainer = Color(0xFFF4F5F7),
    surfaceContainerHigh = Color(0xFFEEEFF2),
    surfaceContainerHighest = Color(0xFFE8E9ED),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDEDFE3),
    inverseSurface = Color(0xFF2E2F33),
    inverseOnSurface = Color(0xFFF2F2F4),
    inversePrimary = BrandDark,
    scrim = Color(0xFF000000),
    outline = Color(0xFF9A9CA3),
    outlineVariant = Color(0xFFD6D7DC),
    onBackground = Color(0xFF18181B),
    onSurface = Color(0xFF18181B),
    error = Color(0xFFB42318),
    onError = Color.White,
    errorContainer = Color(0xFFFBE3E1),
    onErrorContainer = Color(0xFF6E1610),
)

internal val Dark = darkColorScheme(
    primary = BrandDark,
    onPrimary = Color(0xFF04243F),
    primaryContainer = Color(0xFF12456E),
    onPrimaryContainer = Color(0xFFD3E7F9),
    secondary = AccentDark,
    onSecondary = Color(0xFF1B1440),
    tertiary = Color(0xFF6FCFAA),
    onTertiary = Color(0xFF04291E),
    tertiaryContainer = Color(0xFF14503D),
    onTertiaryContainer = Color(0xFFCDEEE0),
    background = Color(0xFF151515),
    surface = Color(0xFF1D1D1D),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFFB4B5BA),
    surfaceTint = BrandDark,
    surfaceContainerLowest = Color(0xFF101010),
    surfaceContainerLow = Color(0xFF1A1A1A),
    surfaceContainer = Color(0xFF212123),
    surfaceContainerHigh = Color(0xFF2A2A2C),
    surfaceContainerHighest = Color(0xFF343436),
    surfaceBright = Color(0xFF393939),
    surfaceDim = Color(0xFF121212),
    inverseSurface = Color(0xFFE6E6E8),
    inverseOnSurface = Color(0xFF1D1D1D),
    inversePrimary = Brand,
    scrim = Color(0xFF000000),
    outline = Color(0xFF75767C),
    outlineVariant = Color(0xFF3A3A3D),
    onBackground = Color(0xFFF3F3F3),
    onSurface = Color(0xFFF3F3F3),
    error = Color(0xFFE06C63),
    onError = Color(0xFF3E0906),
    errorContainer = Color(0xFF5A1A16),
    onErrorContainer = Color(0xFFF8D8D5),
)

/**
 * Which theme to use, when the phone's own setting is not the last word.
 *
 * The app followed the system with no way to override it. Resolving the
 * three-way choice is a pure function so the rule can be tested: "system"
 * defers, and the other two do not.
 */
enum class ThemeChoice {
    SYSTEM, LIGHT, DARK;

    companion object {
        fun of(name: String?): ThemeChoice =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SYSTEM

        fun resolve(choice: ThemeChoice, systemDark: Boolean): Boolean = when (choice) {
            SYSTEM -> systemDark
            LIGHT -> false
            DARK -> true
        }
    }

    val label: String get() = when (this) {
        SYSTEM -> "Follow the system"
        LIGHT -> "Light"
        DARK -> "Dark"
    }
}

/**
 * Whether the device has asked for less movement.
 *
 * Read once per composition tree and passed down, because every animated part
 * of the sign-in screen needs the answer and none of them should be reaching
 * for a ContentResolver of their own.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * Accessibility > Remove animations sets the animator duration scale to zero.
 *
 * There is no Compose API for this, and no broadcast when it changes -- it is
 * read at composition, which is the same moment the platform's own View
 * animations sample it.
 */
object Motion {
    /** A scale of exactly zero is the "remove animations" setting; anything else is not. */
    fun reduced(animatorScale: Float): Boolean = animatorScale == 0f
}

@Composable
fun CloudHubTheme(choice: ThemeChoice = ThemeChoice.SYSTEM, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        val scale = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f)
        Motion.reduced(scale)
    }

    CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
        MaterialTheme(
            colorScheme = if (ThemeChoice.resolve(choice, isSystemInDarkTheme())) Dark else Light,
            content = content,
        )
    }
}
