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

private val Light = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E9FA),
    onPrimaryContainer = Color(0xFF06375F),
    secondary = Accent,
    onSecondary = Color.White,
    background = Color(0xFFF7F7F8),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9EAEE),
    onSurfaceVariant = Color(0xFF5A5C63),
    outline = Color(0xFF9A9CA3),
    outlineVariant = Color(0xFFD6D7DC),
    onBackground = Color(0xFF18181B),
    onSurface = Color(0xFF18181B),
    error = Color(0xFFB42318),
    onError = Color.White,
    errorContainer = Color(0xFFFBE3E1),
    onErrorContainer = Color(0xFF6E1610),
)

private val Dark = darkColorScheme(
    primary = BrandDark,
    onPrimary = Color(0xFF04243F),
    primaryContainer = Color(0xFF12456E),
    onPrimaryContainer = Color(0xFFD3E7F9),
    secondary = AccentDark,
    onSecondary = Color(0xFF1B1440),
    background = Color(0xFF151515),
    surface = Color(0xFF1D1D1D),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFFB4B5BA),
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
