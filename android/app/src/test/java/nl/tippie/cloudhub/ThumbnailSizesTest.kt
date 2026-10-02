package nl.tippie.cloudhub

import nl.tippie.cloudhub.data.ViewPrefs
import nl.tippie.cloudhub.work.TaskCenter
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.CloudHubClient
import nl.tippie.cloudhub.net.InMemoryCookieStore
import nl.tippie.cloudhub.net.PinnedCertificates
import nl.tippie.cloudhub.ui.FilesViewModel
import nl.tippie.cloudhub.ui.ThumbnailSizes
import nl.tippie.cloudhub.ui.gridCardWidthDp
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The grid's card sizes: the steps, how a pinch or the wheel moves between
 * them, when the larger thumbnail is worth fetching, and that the choice
 * survives a restart.
 */
class ThumbnailSizesTest {

    @Test fun `the default is the size the grid has always had`() {
        assertEquals(158, ThumbnailSizes.CELL_WIDTHS_DP[ThumbnailSizes.DEFAULT])
        assertEquals(ThumbnailSizes.CELL_WIDTHS_DP.sorted(), ThumbnailSizes.CELL_WIDTHS_DP)
        assertEquals(ThumbnailSizes.CELL_WIDTHS_DP.size, ThumbnailSizes.LABELS.size)
    }

    @Test fun `out-of-range levels are held at the ends`() {
        assertEquals(0, ThumbnailSizes.clamp(-3))
        assertEquals(ThumbnailSizes.MAX, ThumbnailSizes.clamp(99))
    }

    @Test fun `a small pinch does nothing, and is kept for the next event`() {
        assertEquals(2 to 1.1f, ThumbnailSizes.afterPinch(2, 1.1f))
        assertEquals(2 to 0.9f, ThumbnailSizes.afterPinch(2, 0.9f))
    }

    @Test fun `spreading the fingers makes the cards larger, pinching smaller`() {
        assertEquals(3, ThumbnailSizes.afterPinch(2, 1.3f).first)
        assertEquals(1, ThumbnailSizes.afterPinch(2, 0.75f).first)
    }

    @Test fun `one sweep can take several steps, and what is left over carries on`() {
        val (level, rest) = ThumbnailSizes.afterPinch(1, 1.25f * 1.25f * 1.1f)
        assertEquals(3, level)
        assertEquals(1.1f, rest, 0.001f)
    }

    @Test fun `past either end the excess is dropped, so the way back responds at once`() {
        assertEquals(ThumbnailSizes.MAX to 1f, ThumbnailSizes.afterPinch(ThumbnailSizes.MAX, 3f))
        assertEquals(0 to 1f, ThumbnailSizes.afterPinch(0, 0.2f))
    }

    @Test fun `ctrl and the wheel step one size a notch, away from you is larger`() {
        assertEquals(3, ThumbnailSizes.afterWheel(2, -1f))
        assertEquals(1, ThumbnailSizes.afterWheel(2, 1f))
        assertEquals(2, ThumbnailSizes.afterWheel(2, 0f))
        assertEquals(ThumbnailSizes.MAX, ThumbnailSizes.afterWheel(ThumbnailSizes.MAX, -1f))
    }

    @Test fun `card widths follow the grid's own arithmetic`() {
        // A 411dp phone less 2 x 14dp padding, the default step: two columns.
        assertEquals((383f - 12f) / 2, gridCardWidthDp(383f, 158f, 12f), 0.01f)
        // Never fewer than one column, however wide the step.
        assertEquals(300f, gridCardWidthDp(300f, 330f, 12f), 0.01f)
    }

    @Test fun `the default grid on a phone keeps the small thumbnail`() {
        val card = gridCardWidthDp(383f, 158f, 12f)
        assertFalse(ThumbnailSizes.wantsLargeThumbnail(card, density = 2.625f))
    }

    @Test fun `big cards ask for the sharp one`() {
        val oneColumn = gridCardWidthDp(383f, 200f, 12f)
        assertTrue(ThumbnailSizes.wantsLargeThumbnail(oneColumn, density = 2.625f))
        // An unfolded inner screen at the second-largest step.
        val unfolded = gridCardWidthDp(852f, 256f, 12f)
        assertTrue(ThumbnailSizes.wantsLargeThumbnail(unfolded, density = 2.33f))
    }

    /** Never asked anything: these tests only touch the view model's own state. */
    private fun offlineApi() = CloudHubApi(
        "http://localhost",
        CloudHubClient(InMemoryCookieStore(), object : PinnedCertificates {
            override fun isPinned(fingerprint: String) = false
        }),
    )

    private class MemoryPrefs(override var gridView: Boolean, override var thumbnailSize: Int) : ViewPrefs

    /** A follower that is never started: nothing here queues a task. */
    private fun viewModel(prefs: ViewPrefs) = offlineApi().let { api ->
        FilesViewModel(api, TaskCenter(api, save = { _, _ -> }), prefs)
    }

    @Test fun `the browser opens as it was left`() {
        val prefs = MemoryPrefs(gridView = false, thumbnailSize = 4)
        val model = viewModel(prefs)
        assertFalse(model.state.value.grid)
        assertEquals(4, model.state.value.thumbnailSize)
    }

    @Test fun `changes are remembered, and clamped`() {
        val prefs = MemoryPrefs(gridView = true, thumbnailSize = ThumbnailSizes.DEFAULT)
        val model = viewModel(prefs)
        model.growThumbnails()
        assertEquals(ThumbnailSizes.DEFAULT + 1, prefs.thumbnailSize)
        model.setThumbnailSize(50)
        assertEquals(ThumbnailSizes.MAX, model.state.value.thumbnailSize)
        assertEquals(ThumbnailSizes.MAX, prefs.thumbnailSize)
        model.setGrid(false)
        assertFalse(prefs.gridView)
    }

    @Test fun `a stored size from a damaged preferences file is clamped`() {
        val model = viewModel(MemoryPrefs(true, -7))
        assertEquals(0, model.state.value.thumbnailSize)
    }
}
