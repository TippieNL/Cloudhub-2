package nl.tippie.cloudhub.ui

import nl.tippie.cloudhub.net.ThumbnailScanProgress

/**
 * When the background thumbnail scan carries on, and what Settings says about it.
 *
 * The work is the server's -- it has the photos; the phone would have to
 * download every original to make a thumbnail of it -- so the app's part is
 * to keep asking while there is work, stop when there is none, and say how
 * far it has got.
 */
object ThumbnailRules {

    /**
     * How long one worker run keeps asking.
     *
     * WorkManager stops a worker that is not in the foreground after about ten
     * minutes. Stopping short of that and asking to be run again loses
     * nothing -- the scan's place is kept on the server -- where being stopped
     * mid-request would throw a slice's answer away.
     */
    const val RUN_MILLIS = 8 * 60_000L

    /**
     * How long to wait when someone else's slice holds the scan before asking
     * again. A slice is about eight seconds, so one wait usually sees it out.
     */
    const val BUSY_WAIT_MILLIS = 10_000L

    /**
     * Busy answers in a row before a run leaves the scan to whoever holds it
     * and comes back later: about a minute, which an abandoned slice -- one
     * whose worker was stopped while the server carried on -- never lasts.
     */
    const val BUSY_TRIES = 6

    /** What the worker does with a slice's answer. */
    enum class Next {
        /** Ask for the next slice straight away. */
        ASK_AGAIN,
        /** Someone else's slice holds the scan: wait for it, then ask again. */
        WAIT,
        /** Every photo has been checked. */
        DONE,
        /** Out of time for this run, or busy for too long: come back later. */
        LATER,
    }

    fun next(scan: ThumbnailScanProgress, elapsedMillis: Long, busyInARow: Int): Next = when {
        // Busy says nothing about this request having run, even when the last
        // scan reads as done -- so a check asked for has not begun yet.
        scan.busy -> if (busyInARow < BUSY_TRIES && elapsedMillis + BUSY_WAIT_MILLIS < RUN_MILLIS) Next.WAIT else Next.LATER
        scan.done -> Next.DONE
        elapsedMillis >= RUN_MILLIS -> Next.LATER
        else -> Next.ASK_AGAIN
    }

    /** Photos and videos that have a thumbnail, as far as the last scan knows. */
    fun ready(scan: ThumbnailScanProgress): Int = scan.generated + scan.valid + scan.adopted

    /** How far through the photos it is, or null while that cannot be said. */
    fun fraction(scan: ThumbnailScanProgress): Float? {
        if (scan.done) return 1f
        if (scan.phase != "make" || scan.found <= 0) return null
        return (scan.processed.toFloat() / scan.found).coerceIn(0f, 1f)
    }

    /**
     * One line for Settings, saying first what is happening on this phone --
     * a check waiting for a connection, or one that stopped short -- and
     * otherwise how far the server has got.
     */
    fun summary(status: ThumbnailStatus, number: (Int) -> String = { "%,d".format(it) }): String = when {
        status.supported == false -> "Made as photos are first shown"
        status.running -> summary(status.scan, running = true, number = number)
        status.waiting -> if (status.waitsForBattery) "Waiting for a connection and battery…" else "Waiting for a connection…"
        status.error != null -> "Stopped: ${status.error} · tries again later"
        else -> summary(status.scan, running = false, number = number)
    }

    /**
     * How far the server has got. [scan] is null before the server has been
     * asked, or for a server that keeps no thumbnails ahead of time.
     */
    fun summary(scan: ThumbnailScanProgress?, running: Boolean, number: (Int) -> String = { "%,d".format(it) }): String =
        when {
            scan == null -> if (running) "Getting ready…" else "Made as photos are first shown"
            !scan.done && scan.busy -> "Being prepared elsewhere · ${number(scan.processed)} of ${number(scan.found)} checked"
            !scan.started -> "Not prepared yet"
            !scan.done && scan.phase == "walk" -> "Looking for photos · ${number(scan.found)} found"
            !scan.done && scan.phase == "make" -> "Preparing · ${number(scan.processed)} of ${number(scan.found)}"
            !scan.done -> "Tidying up…"
            scan.failed > 0 -> "${number(ready(scan))} ready · ${number(scan.failed)} could not be read"
            ready(scan) == 0 -> "No photos to prepare"
            else -> "All ${number(ready(scan))} ready"
        }
}
