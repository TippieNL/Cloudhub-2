package nl.tippie.cloudhub.ui

import nl.tippie.cloudhub.work.QueuedUpload

/**
 * How far a batch of uploads has got.
 *
 * The hard part is not drawing a bar, it is that the queue *shrinks*: a
 * finished file is removed from it, so a fraction computed over "what is left"
 * lurches backwards every time something completes. The denominator therefore
 * has to be remembered rather than recalculated, which is what [batchTotal]
 * does -- and that is exactly the kind of rule worth pinning in a test rather
 * than discovering on a phone with a slow connection.
 */
object UploadProgress {

    data class Summary(
        /** Something is queued or in flight. */
        val active: Boolean,
        val filesLeft: Int,
        /** The file being sent right now, if the worker is running. */
        val currentName: String?,
        val doneBytes: Long,
        val totalBytes: Long,
        val fraction: Float,
    ) {
        /** Everything queued has been sent. */
        val finished get() = !active && totalBytes > 0
    }

    val Idle = Summary(
        active = false, filesLeft = 0, currentName = null,
        doneBytes = 0, totalBytes = 0, fraction = 0f,
    )

    /**
     * The size of the batch, as a high-water mark.
     *
     * Files completing must not shrink it, or the bar goes backwards; files
     * *added* mid-batch must raise it, or the bar reaches 100% and keeps
     * uploading. Reset to zero by the caller once the queue empties, which is
     * what starts the next batch from scratch.
     */
    fun batchTotal(previousTotal: Long, remainingBytes: Long): Long =
        maxOf(previousTotal, remainingBytes)

    /**
     * @param remaining what is still queued, newest last
     * @param currentId the id the worker last reported progress for, if any
     * @param currentSent bytes the server has confirmed for that file
     * @param batchTotal from [batchTotal]
     */
    fun summarise(
        remaining: List<QueuedUpload>,
        currentId: String?,
        currentSent: Long,
        batchTotal: Long,
    ): Summary {
        if (remaining.isEmpty()) {
            // Not "0% of nothing": the batch is over, and the caller decides
            // whether that means idle or a moment of "all done".
            return Idle.copy(totalBytes = batchTotal)
        }

        val remainingBytes = remaining.sumOf { it.size.coerceAtLeast(0) }
        val current = remaining.firstOrNull { it.id == currentId }
        // Clamped: a resumed upload can report an offset from a previous run
        // that is past what this file needs.
        val sent = if (current == null) 0L else currentSent.coerceIn(0L, current.size)

        val total = maxOf(batchTotal, remainingBytes)
        // Everything already gone from the queue, plus the part of the file in
        // flight that has landed. Measured in bytes rather than files: a 10 KB
        // note finishing beside a 4 GB video is not half the work.
        val done = (total - remainingBytes + sent).coerceIn(0L, total)

        return Summary(
            active = true,
            filesLeft = remaining.size,
            currentName = current?.name,
            doneBytes = done,
            totalBytes = total,
            fraction = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f),
        )
    }
}
