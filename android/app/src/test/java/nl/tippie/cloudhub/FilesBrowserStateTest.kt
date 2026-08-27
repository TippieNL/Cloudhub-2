package nl.tippie.cloudhub

import nl.tippie.cloudhub.ui.LoadState
import nl.tippie.cloudhub.ui.MAX_SKELETON_CARDS
import nl.tippie.cloudhub.ui.Shown
import nl.tippie.cloudhub.ui.SkeletonTiming
import nl.tippie.cloudhub.ui.browserState
import nl.tippie.cloudhub.ui.skeletonCount
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the file browser draws, and when.
 *
 * These are the rules that decide whether someone sees their files, a
 * placeholder, "empty", or an error -- and getting one wrong tells them
 * something untrue about their own storage. Pure, so they run with no server
 * and no device.
 */
class BrowserStateTest {

    @Test
    fun `a failed load is an error, never an empty folder`() {
        // The defect this replaced: the failure went to a snackbar that cleared
        // itself, and the content area fell through to "This folder is empty"
        // -- a confident wrong answer about someone's files.
        val shown = browserState(
            load = LoadState.FAILED,
            hasEntries = false,
            hasVisible = false,
            filtering = false,
        )
        assertEquals(Shown.ERROR, shown)
    }

    @Test
    fun `a failure while entries are on screen is still an error`() {
        // A refresh that fails must say so rather than silently leaving stale
        // entries looking current.
        assertEquals(
            Shown.ERROR,
            browserState(LoadState.FAILED, hasEntries = true, hasVisible = true, filtering = false),
        )
    }

    @Test
    fun `a first load shows the skeleton`() {
        assertEquals(
            Shown.SKELETON,
            browserState(LoadState.LOADING, hasEntries = false, hasVisible = false, filtering = false),
        )
    }

    @Test
    fun `a refresh keeps the content on screen`() {
        // Reloading the folder you are looking at should not blank it.
        assertEquals(
            Shown.CONTENT,
            browserState(LoadState.LOADING, hasEntries = true, hasVisible = true, filtering = false),
        )
    }

    @Test
    fun `a confirmed empty folder says so, and never shows a skeleton`() {
        val shown = browserState(LoadState.READY, hasEntries = false, hasVisible = false, filtering = false)
        assertEquals(Shown.EMPTY, shown)
        assertTrue(shown != Shown.SKELETON)
    }

    @Test
    fun `a filter that matches nothing is not an empty folder`() {
        // Telling someone their folder is empty because they mistyped a search
        // is the sort of thing that gets an app deleted.
        assertEquals(
            Shown.NO_MATCHES,
            browserState(LoadState.READY, hasEntries = true, hasVisible = false, filtering = true),
        )
    }

    @Test
    fun `a search that returns nothing is not an empty folder either`() {
        // A server search sets no entries of its own; filtering is what tells
        // the two cases apart.
        assertEquals(
            Shown.NO_MATCHES,
            browserState(LoadState.READY, hasEntries = false, hasVisible = false, filtering = true),
        )
    }

    @Test
    fun `entries are shown once they are there`() {
        assertEquals(
            Shown.CONTENT,
            browserState(LoadState.READY, hasEntries = true, hasVisible = true, filtering = false),
        )
    }
}

/** Neither flashing a skeleton nor blinking it away. */
class SkeletonTimingTest {

    @Test
    fun `a skeleton just shown is held for the minimum`() {
        assertEquals(SkeletonTiming.MIN_SHOWN_MS, SkeletonTiming.lingerMs(0))
        assertEquals(SkeletonTiming.MIN_SHOWN_MS - 100, SkeletonTiming.lingerMs(100))
    }

    @Test
    fun `a skeleton that has been up long enough goes at once`() {
        assertEquals(0L, SkeletonTiming.lingerMs(SkeletonTiming.MIN_SHOWN_MS))
        assertEquals(0L, SkeletonTiming.lingerMs(5_000))
    }

    @Test
    fun `the linger is never negative`() {
        // A negative delay would throw rather than return immediately.
        assertTrue(SkeletonTiming.lingerMs(Long.MAX_VALUE) >= 0)
    }

    @Test
    fun `a fast load is given a chance to finish first`() {
        assertTrue(SkeletonTiming.DELAY_MS > 0)
        assertTrue(SkeletonTiming.DELAY_MS < SkeletonTiming.MIN_SHOWN_MS)
    }
}

/**
 * How many placeholders to draw.
 *
 * Derived from the grid on screen rather than hard-coded, which is what lets
 * one skeleton serve a phone in portrait and a tablet in landscape.
 */
class SkeletonCountTest {

    @Test
    fun `it fills the viewport at the given column count`() {
        // 800dp tall, 200dp cards: 4 rows plus one for the scroll, 2 across.
        assertEquals(10, skeletonCount(columns = 2, viewportHeightDp = 800, cardHeightDp = 200))
    }

    @Test
    fun `a wider grid gets more cards`() {
        val phone = skeletonCount(columns = 2, viewportHeightDp = 800, cardHeightDp = 200)
        val tablet = skeletonCount(columns = 4, viewportHeightDp = 800, cardHeightDp = 200)
        assertTrue(tablet > phone)
    }

    @Test
    fun `a taller viewport gets more cards`() {
        val short = skeletonCount(columns = 2, viewportHeightDp = 400, cardHeightDp = 200)
        val tall = skeletonCount(columns = 2, viewportHeightDp = 1200, cardHeightDp = 200)
        assertTrue(tall > short)
    }

    @Test
    fun `it never returns nothing`() {
        // A viewport can measure as zero on the first frame, and a screen with
        // no placeholders at all reads as frozen.
        assertTrue(skeletonCount(columns = 1, viewportHeightDp = 0, cardHeightDp = 200) >= 1)
        assertTrue(skeletonCount(columns = 0, viewportHeightDp = 0, cardHeightDp = 0) >= 1)
    }

    @Test
    fun `it is capped, so an enormous screen does not draw hundreds`() {
        assertEquals(
            MAX_SKELETON_CARDS,
            skeletonCount(columns = 12, viewportHeightDp = 4_000, cardHeightDp = 100),
        )
    }
}
