package nl.tippie.cloudhub

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import nl.tippie.cloudhub.ui.Dark
import nl.tippie.cloudhub.ui.Light
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every colour role belongs to this app, not to Material's sample one.
 *
 * The bug this exists to catch: `Theme.kt` set twenty roles and left thirteen
 * alone, and `lightColorScheme()` fills an unset role with Material's
 * *baseline* purple. `surfaceContainer` is what a DropdownMenu paints with, so
 * the overflow menu came out lavender on a blue app; `surfaceTint` washed every
 * elevated surface with #6750A4. Nobody noticed for five releases because the
 * app compiled perfectly and the holes only showed on one screen.
 *
 * Leaving a role out is not "use the default" -- it is "use somebody else's
 * brand", and that is not something a compiler can tell you.
 */
class ThemePaletteTest {

    /**
     * Roles this app actually paints with, and the components that reach for
     * them. Not the whole scheme: the ones nothing here uses are left to
     * Material deliberately rather than by omission.
     */
    private val required: List<Pair<String, (ColorScheme) -> androidx.compose.ui.graphics.Color>> = listOf(
        "primary" to { it.primary },
        "secondary" to { it.secondary },
        "tertiary" to { it.tertiary },
        "background" to { it.background },
        "surface" to { it.surface },
        "surfaceVariant" to { it.surfaceVariant },
        "onSurfaceVariant" to { it.onSurfaceVariant },
        "outline" to { it.outline },
        "outlineVariant" to { it.outlineVariant },
        // The elevation tint: unset, it lilacs every raised surface.
        "surfaceTint" to { it.surfaceTint },
        // The container ramp: menus, bottom sheets and search fields.
        "surfaceContainerLowest" to { it.surfaceContainerLowest },
        "surfaceContainerLow" to { it.surfaceContainerLow },
        "surfaceContainer" to { it.surfaceContainer },
        "surfaceContainerHigh" to { it.surfaceContainerHigh },
        "surfaceContainerHighest" to { it.surfaceContainerHighest },
        "surfaceBright" to { it.surfaceBright },
        "surfaceDim" to { it.surfaceDim },
        // Snackbars.
        "inverseSurface" to { it.inverseSurface },
        "inverseOnSurface" to { it.inverseOnSurface },
        "error" to { it.error },
    )

    /**
     * A role is fine if we set it to something of our own, and fine if it
     * happens to equal a baseline value that carries no hue -- pure white
     * really is the right lowest container in a light theme, and matching
     * Material there says nothing. What must never survive is a role still
     * holding one of Material's *tinted* purples.
     */
    private fun leftPurple(
        ours: ColorScheme,
        baseline: ColorScheme,
    ): List<String> = required
        .filter { (_, role) -> role(ours) == role(baseline) && !role(baseline).isNeutral() }
        .map { it.first }

    private fun androidx.compose.ui.graphics.Color.isNeutral(): Boolean =
        red == green && green == blue

    @Test
    fun `the light scheme leaves no role at Material's baseline`() {
        val missed = leftPurple(Light, lightColorScheme())
        if (missed.isNotEmpty()) fail("still Material's purple: ${missed.joinToString()}")
    }

    @Test
    fun `the dark scheme leaves no role at Material's baseline`() {
        val missed = leftPurple(Dark, darkColorScheme())
        if (missed.isNotEmpty()) fail("still Material's purple: ${missed.joinToString()}")
    }

    @Test
    fun `the check itself notices a tinted baseline value`() {
        // Guarding the guard: if isNeutral() were ever loosened to "roughly
        // grey", the lavender that started all this would slip through.
        val lavender = androidx.compose.ui.graphics.Color(0xFFF3EDF7)
        assertTrue(!lavender.isNeutral(), "the lavender that caused this would pass")
        assertTrue(androidx.compose.ui.graphics.Color.White.isNeutral())
    }

    @Test
    fun `menus and sheets are painted from this app's own greys`() {
        // The exact value that produced the lavender menu.
        val materialLavender = androidx.compose.ui.graphics.Color(0xFFF3EDF7)
        assertTrue(Light.surfaceContainer != materialLavender, "the menu is still lavender")
        assertTrue(Light.surfaceContainerLow != materialLavender)
    }

    @Test
    fun `the elevation tint is the brand colour`() {
        // Not a grey and not a purple: surfaceTint is meant to be the app's
        // primary, so a raised surface leans towards the brand.
        assertEquals(Light.primary, Light.surfaceTint)
        assertEquals(Dark.primary, Dark.surfaceTint)
    }

    @Test
    fun `the container ramp actually ramps`() {
        // A ramp where two steps are identical gives a menu no separation from
        // the surface behind it.
        val light = listOf(
            Light.surfaceContainerLowest, Light.surfaceContainerLow, Light.surfaceContainer,
            Light.surfaceContainerHigh, Light.surfaceContainerHighest,
        )
        assertEquals(light.size, light.distinct().size, "light ramp repeats a step")

        val dark = listOf(
            Dark.surfaceContainerLowest, Dark.surfaceContainerLow, Dark.surfaceContainer,
            Dark.surfaceContainerHigh, Dark.surfaceContainerHighest,
        )
        assertEquals(dark.size, dark.distinct().size, "dark ramp repeats a step")
    }

    @Test
    fun `light and dark are actually different schemes`() {
        // A copy-paste slip here is invisible until someone switches theme.
        assertTrue(Light.surface != Dark.surface)
        assertTrue(Light.surfaceContainer != Dark.surfaceContainer)
        assertTrue(Light.onSurface != Dark.onSurface)
    }
}
