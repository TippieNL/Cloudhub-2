package nl.tippie.cloudhub

import nl.tippie.cloudhub.net.ThumbnailScanProgress
import nl.tippie.cloudhub.ui.ThumbnailRules
import nl.tippie.cloudhub.ui.ThumbnailRules.Next
import nl.tippie.cloudhub.ui.ThumbnailStatus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The background thumbnail scan: when the app keeps asking the server for
 * another slice, and what Settings says about how far it has got.
 */
class ThumbnailRulesTest {

    private val making = ThumbnailScanProgress(
        started = true, phase = "make", found = 2_000, processed = 500, generated = 400, valid = 100,
    )

    private fun summary(scan: ThumbnailScanProgress?, running: Boolean = false) =
        ThumbnailRules.summary(scan, running) { it.toString() }

    @Test fun `the worker asks for another slice while there is work and time`() {
        assertEquals(Next.ASK_AGAIN, ThumbnailRules.next(making, elapsedMillis = 0, busyInARow = 0))
        assertEquals(Next.ASK_AGAIN, ThumbnailRules.next(making, ThumbnailRules.RUN_MILLIS - 1, 0))
    }

    @Test fun `and stops when every photo is checked, or before WorkManager would stop it`() {
        assertEquals(Next.DONE, ThumbnailRules.next(making.copy(done = true, phase = "done"), 0, 0))
        assertEquals(Next.LATER, ThumbnailRules.next(making, ThumbnailRules.RUN_MILLIS, 0), "its place is kept on the server")
        assertTrue(ThumbnailRules.RUN_MILLIS < 10 * 60_000L, "WorkManager stops a worker at about ten minutes")
    }

    @Test fun `a busy answer is waited out rather than taken for done`() {
        // What "Check now" got when the last request was still running on the
        // server: busy, with the old scan's account, which reads as done.
        val busyButDone = making.copy(done = true, phase = "done", busy = true)
        assertEquals(Next.WAIT, ThumbnailRules.next(busyButDone, 0, busyInARow = 1),
            "giving up here lost the check that was asked for")
        assertEquals(Next.WAIT, ThumbnailRules.next(making.copy(busy = true), 0, busyInARow = 1))
        assertTrue(ThumbnailRules.BUSY_WAIT_MILLIS > 8_000, "a wait sees out a server slice of about eight seconds")
    }

    @Test fun `but not for ever`() {
        val busy = making.copy(busy = true)
        assertEquals(Next.LATER, ThumbnailRules.next(busy, 0, busyInARow = ThumbnailRules.BUSY_TRIES),
            "someone else's scan for a minute is a real one; come back later")
        assertEquals(Next.LATER, ThumbnailRules.next(busy, ThumbnailRules.RUN_MILLIS - 1_000, busyInARow = 1),
            "no wait that would run past the run's end")
    }

    @Test fun `progress is a fraction only while thumbnails are being made`() {
        assertEquals(0.25f, ThumbnailRules.fraction(making))
        assertNull(ThumbnailRules.fraction(making.copy(phase = "walk")), "the total is not known while it is still counting")
        assertNull(ThumbnailRules.fraction(making.copy(found = 0)))
        assertEquals(1f, ThumbnailRules.fraction(making.copy(done = true, phase = "done")))
        assertEquals(1f, ThumbnailRules.fraction(making.copy(processed = 2_500)), "never past the end")
    }

    @Test fun `ready counts what was made, what was already there, and video frames brought across`() {
        assertEquals(507, ThumbnailRules.ready(making.copy(adopted = 7)))
    }

    @Test fun `settings says where the scan is`() {
        assertEquals("Made as photos are first shown", summary(null))
        assertEquals("Getting ready…", summary(null, running = true))
        assertEquals("Not prepared yet", summary(ThumbnailScanProgress()))
        assertEquals("Looking for photos · 120 found", summary(making.copy(phase = "walk", found = 120, processed = 0)))
        assertEquals("Preparing · 500 of 2000", summary(making))
        assertEquals("Tidying up…", summary(making.copy(phase = "sweep")))
        assertEquals("Being prepared elsewhere · 500 of 2000 checked", summary(making.copy(busy = true)))
    }

    @Test fun `and how it ended`() {
        val done = making.copy(done = true, phase = "done", processed = 2_000, generated = 1_900, valid = 97, failed = 3)
        assertEquals("1997 ready · 3 could not be read", summary(done))
        assertEquals("All 1997 ready", summary(done.copy(failed = 0)))
        assertEquals("No photos to prepare", summary(ThumbnailScanProgress(started = true, done = true, phase = "done")))
    }

    @Test fun `what is happening on the phone comes before the server's account`() {
        val status = ThumbnailStatus(scan = making, running = false, supported = true)
        fun line(s: ThumbnailStatus) = ThumbnailRules.summary(s) { it.toString() }
        assertEquals("Preparing · 500 of 2000", line(status))
        assertEquals("Preparing · 500 of 2000", line(status.copy(running = true)))
        assertEquals("Waiting for a connection…", line(status.copy(waiting = true)))
        assertEquals("Waiting for a connection and battery…", line(status.copy(waiting = true, waitsForBattery = true)))
        assertEquals("Stopped: no connection to the server · tries again later",
            line(status.copy(error = "no connection to the server")))
        assertEquals("Preparing · 500 of 2000", line(status.copy(running = true, error = "old news")),
            "a run under way is the news, not why the last one stopped")
        assertEquals("Made as photos are first shown", line(ThumbnailStatus(null, running = false, supported = false)))
    }

    @Test fun `numbers are grouped for reading unless told otherwise`() {
        assertEquals(
            "Preparing · ${"%,d".format(500)} of ${"%,d".format(2_000)}",
            ThumbnailRules.summary(making, running = true),
        )
    }
}
