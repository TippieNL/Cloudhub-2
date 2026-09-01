package nl.tippie.cloudhub.ui

/**
 * How playback is allowed to use the network and the disk.
 *
 * Media3's defaults are written for a phone streaming from the public
 * internet: buffer 2.5 seconds of video before showing anything, five seconds
 * after a stall, keep nothing behind the playhead. CloudHub streams from a
 * server the phone can usually reach in a millisecond or two, where those
 * numbers only add waiting -- and where the same file is often watched again.
 *
 * Kept as plain numbers, apart from the player that uses them, because the
 * relationships between them are the part that can be got wrong: Media3
 * rejects a buffer configuration whose bounds do not agree, and a phone is a
 * slow place to discover that.
 */
object PlaybackTuning {

    /**
     * How much of a video to hold before starting.
     *
     * A second rather than Media3's 2.5, because the wait is the thing being
     * removed: on a local network a second of video arrives in well under a
     * second, so the player is not trading stalls for it.
     */
    const val BUFFER_FOR_PLAYBACK_MS = 1_000

    /** After a stall, where the same reasoning applies with less confidence. */
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_000

    /** Media3's own defaults for the steady state, which are sensible. */
    const val MIN_BUFFER_MS = 15_000
    const val MAX_BUFFER_MS = 50_000

    /**
     * How much to keep *behind* the playhead.
     *
     * Zero by default, which is why skipping back ten seconds re-fetches ten
     * seconds you just watched. Thirty covers the double-tap and the scrub
     * that follows a "wait, what did they say".
     */
    const val BACK_BUFFER_MS = 30_000

    /** Bytes of video kept on disk between sessions, evicted least-recent first. */
    const val CACHE_BYTES = 256L * 1024 * 1024

    /**
     * Whether a file may be *written* to that cache.
     *
     * A film bigger than the cache cannot be held by it, and trying is worse
     * than not trying: playing it evicts every span it just wrote to make room
     * for the next one, so the cache is churned from end to end, everything
     * else in it is thrown away, and the file still is not cached. Worse, the
     * evictor can drop a span while it is being read, which is how a large
     * video stops playing rather than merely playing uncached.
     *
     * A quarter of the cache is the limit: a 64 MB clip can be held alongside
     * three others, a 4 GB film reads straight through and is never written.
     * Reading from the cache is unaffected -- something already cached still
     * comes from there.
     */
    fun mayCache(sizeBytes: Long): Boolean = sizeBytes in 1..(CACHE_BYTES / 4)

    /**
     * The cache key for a file, which has to change when the file does.
     *
     * Keyed on the URL alone, replacing holiday.mp4 with a different video of
     * the same name would play the old one out of the cache forever -- and
     * CloudHub explicitly supports replacing a file, keeping the previous one
     * as a version. The modification time the listing already carries is what
     * makes the key follow the contents.
     */
    fun cacheKey(path: String, modified: String): String = "$path|$modified"

    /**
     * True when the configuration satisfies the relationships Media3 enforces.
     *
     * Not defensive programming: it is the assertion the tests make, kept here
     * so the rule and the numbers cannot drift into different files.
     */
    fun bufferBoundsAreValid(): Boolean =
        BUFFER_FOR_PLAYBACK_MS in 1..MIN_BUFFER_MS &&
            BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS in BUFFER_FOR_PLAYBACK_MS..MIN_BUFFER_MS &&
            MIN_BUFFER_MS <= MAX_BUFFER_MS
}
