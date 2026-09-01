package nl.tippie.cloudhub.ui

/**
 * What a video tile is allowed to cost.
 *
 * A video the server has no cached frame for is drawn by decoding a frame on
 * the device -- and a frame can only be decoded from bytes that have arrived,
 * so asking for the video's URL means fetching the video. A folder of ten
 * holiday clips is then gigabytes of traffic to draw ten small pictures, over
 * the same connection playback is trying to use.
 *
 * Two rules fix that. Ask for a prefix rather than the file, since the frame
 * wanted is at the start; and hand the decoded frame back to the server, so
 * the next device to open the folder is served a thumbnail measured in
 * kilobytes and nobody decodes it again.
 */
object VideoThumbnails {

    /**
     * How much of a video to ask for.
     *
     * Enough to carry the index and the first frames of anything written for
     * streaming. Files whose index sits at the end will fail to decode from
     * this, which is what [mayFetchWholeFile] is for.
     */
    const val PREFIX_BYTES = 4L * 1024 * 1024

    /**
     * The largest file worth fetching whole after a prefix fails to decode.
     *
     * The old behaviour, kept for the clips where it is affordable: a phone
     * video of a few dozen megabytes is a fair trade for a picture, a 4 GB
     * film is not, and the film gets the icon instead.
     */
    const val WHOLE_FILE_LIMIT = 64L * 1024 * 1024

    /** The server refuses anything over 1280px; this is a tile, not a poster. */
    const val MAX_EDGE_PX = 640

    /** Quality for the WebP handed back to the server. */
    const val QUALITY = 70

    /** `bytes=0-…`, or null when the file is small enough that a range is pointless. */
    fun rangeHeader(size: Long): String? =
        if (size in 1..PREFIX_BYTES) null else "bytes=0-${PREFIX_BYTES - 1}"

    fun mayFetchWholeFile(size: Long): Boolean = size <= WHOLE_FILE_LIMIT

    /**
     * The frame, scaled to something worth sending.
     *
     * Never upscaled -- a 320px frame stays 320px rather than being blown up
     * and sent as a bigger file that shows no more.
     */
    fun scaledSize(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val longest = maxOf(width, height)
        if (longest <= MAX_EDGE_PX) return width to height
        val factor = MAX_EDGE_PX.toDouble() / longest
        return maxOf(1, Math.round(width * factor).toInt()) to
            maxOf(1, Math.round(height * factor).toInt())
    }

    /**
     * Frames already sent this session, so a tile scrolling back into view
     * does not re-upload one the server has.
     *
     * Bounded, because a long browse should not accumulate paths forever; the
     * server's own "already have one" answer is the real guard, this only
     * saves the request.
     */
    private const val REMEMBERED = 256
    private val contributed = LinkedHashSet<String>()

    @Synchronized
    fun markContributed(path: String): Boolean {
        if (!contributed.add(path)) return false
        while (contributed.size > REMEMBERED) contributed.remove(contributed.first())
        return true
    }

    @Synchronized
    fun forgetContributions() = contributed.clear()
}
