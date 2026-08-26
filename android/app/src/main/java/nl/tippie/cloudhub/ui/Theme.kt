package nl.tippie.cloudhub.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The web app's palette, so the two do not look like different products. */
private val Brand = Color(0xFF1479C9)
private val BrandDark = Color(0xFF3296E6)

private val Light = lightColorScheme(
    primary = Brand,
    background = Color(0xFFF7F7F8),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF18181B),
    onSurface = Color(0xFF18181B),
    error = Color(0xFFB42318),
)

private val Dark = darkColorScheme(
    primary = BrandDark,
    background = Color(0xFF151515),
    surface = Color(0xFF1D1D1D),
    onBackground = Color(0xFFF3F3F3),
    onSurface = Color(0xFFF3F3F3),
    error = Color(0xFFE06C63),
)

@Composable
fun CloudHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
