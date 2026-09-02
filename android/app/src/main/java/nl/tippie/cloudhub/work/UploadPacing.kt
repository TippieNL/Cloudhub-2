package nl.tippie.cloudhub.work

import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether a video or a photo is on screen right now.
 *
 * An upload and a video are two claims on one connection, and an upload made
 * of back-to-back chunks wins that argument completely: measured on an 8 Mbit
 * link, fetching 2 MB of video took 2.1s with the line free and 7.7-10.1s
 * while a file was uploading, and an image that answered in 2ms took up to
 * 1.2 seconds. Nothing on the server can fix that -- the same measurement with
 * four server workers instead of one was no better -- because the bytes are
 * simply not there to be had.
 *
 * So the uploader has to be told when someone is watching. A counter rather
 * than a flag: the player can be opened over the viewer, and the second screen
 * closing must not say the coast is clear while the first is still up.
 */
object ForegroundMedia {
    private val open = AtomicInteger(0)

    /** True while at least one player or viewer is on screen. */
    val inUse: Boolean get() = open.get() > 0

    fun enter() { open.incrementAndGet() }

    /** Clamped at zero: an unbalanced leave must not hide a later enter. */
    fun leave() { open.updateAndGet { if (it > 0) it - 1 else 0 } }

    /** Between tests, which share the process. */
    fun reset() { open.set(0) }
}

/**
 * How much of the link an upload may take.
 *
 * The chunk size is the unit of politeness here: the protocol lets a client
 * send anything up to the server's limit and answers with the offset it
 * reached, so sending less costs a round trip and nothing else. While
 * something is being watched the uploader sends a small slice and then waits,
 * which leaves the link mostly free; the same measurement as above, with an
 * upload paced this way running throughout, put 2 MB of video back at 2.2-2.5s
 * and images back at single-digit milliseconds.
 */
object UploadPacing {
    /** Sent per request while a video or photo is open, then a pause. */
    const val YIELD_CHUNK_BYTES = 256L * 1024

    /** Waited after each of those, so the upload takes roughly a sixth of the link. */
    const val YIELD_PAUSE_MS = 1500L

    /**
     * The most sent in one request otherwise.
     *
     * Below the server's 8 MB not to be gentle but to be quick: pacing can
     * only start at a chunk boundary, so the chunk size is also how long
     * opening a video waits for the upload to notice. 8 MB on a slow uplink is
     * the better part of a minute; this is a few seconds, and the extra round
     * trips are noise beside the bytes they carry.
     */
    const val IDLE_CHUNK_BYTES = 2L * 1024 * 1024

    /** Used when the server's answer is missing or nonsense. */
    const val FALLBACK_CHUNK_BYTES = 1L * 1024 * 1024

    /**
     * How many bytes to send in the next request.
     *
     * Never more than the server said it would take: a chunk over that limit
     * is refused with 413, which the worker treats as permanent.
     */
    fun chunkBytes(serverChunkBytes: Long, mediaOnScreen: Boolean): Long {
        val allowed = if (serverChunkBytes > 0) serverChunkBytes else FALLBACK_CHUNK_BYTES
        val wanted = if (mediaOnScreen) YIELD_CHUNK_BYTES else IDLE_CHUNK_BYTES
        return minOf(wanted, allowed)
    }

    /** How long to wait afterwards. Zero when nobody is watching. */
    fun pauseMillis(mediaOnScreen: Boolean): Long = if (mediaOnScreen) YIELD_PAUSE_MS else 0L

    /**
     * Roughly what the upload will use, in bytes per second.
     *
     * Only an estimate -- it ignores the time the chunk itself takes -- but it
     * is the number the pause is chosen for, and a test can hold it to a share
     * of a slow link rather than to the constants restated.
     */
    fun approximateRate(serverChunkBytes: Long, mediaOnScreen: Boolean): Long {
        val chunk = chunkBytes(serverChunkBytes, mediaOnScreen)
        val pause = pauseMillis(mediaOnScreen)
        if (pause <= 0) return Long.MAX_VALUE
        return chunk * 1000 / pause
    }
}
