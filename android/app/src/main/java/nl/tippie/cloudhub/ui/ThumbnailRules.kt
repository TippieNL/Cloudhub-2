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

    /** Whether to ask for another slice. */
    fun shouldContinue(scan: ThumbnailScanProgress, elapsedMillis: Long): Boolean =
        !scan.done && !scan.busy && elapsedMillis < RUN_MILLIS

    /** Photos and videos that have a thumbnail, as far as the last scan knows. */
    fun ready(scan: ThumbnailScanProgress): Int = scan.generated + scan.valid + scan.adopted

    /** How far through the photos it is, or null while that cannot be said. */
    fun fraction(scan: ThumbnailScanProgress): Float? {
        if (scan.done) return 1f
        if (scan.phase != "make" || scan.found <= 0) return null
        return (scan.processed.toFloat() / scan.found).coerceIn(0f, 1f)
    }

    /**
     * One line for Settings. [scan] is null before the server has been asked,
     * or for a server that keeps no thumbnails ahead of time.
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
