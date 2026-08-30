package nl.tippie.cloudhub.ui

/**
 * How much space is left, and what "left" even means.
 *
 * Three things can be the real ceiling and only one of them applies: a
 * per-account quota, the whole-store limit, or the disk itself. Picking the
 * wrong one tells someone they have room when they do not, so the rule lives
 * here as a pure function rather than as a chain of conditions inside a
 * composable, and every case below is a test.
 */
object StorageMeter {

    /** Past this the bar turns to the error colour and the wording changes. */
    const val WARN_AT = 0.90f

    enum class Against {
        /** This account's own quota. */
        QUOTA,

        /** The limit configured for the whole file store. */
        STORE,

        /** No limit is configured anywhere, so the disk is the limit. */
        DISK,
    }

    data class Reading(
        val against: Against,
        val usedBytes: Long,
        val totalBytes: Long,
        val remainingBytes: Long,
        val fraction: Float,
    ) {
        val nearlyFull get() = fraction >= WARN_AT

        /** True when the ceiling is simply the drive, with nothing configured. */
        val unlimited get() = against == Against.DISK
    }

    /**
     * A quota outranks the store limit, which outranks the disk.
     *
     * The fallback to the disk matters: on a self-hosted box with neither
     * limit set, the drive really is the ceiling, and telling someone their
     * storage is "unlimited" while their disk fills up would be worse than
     * saying nothing.
     */
    fun of(
        usedBytes: Long,
        quotaBytes: Long,
        storeUsedBytes: Long,
        storeLimitBytes: Long,
        diskFreeBytes: Long,
        diskTotalBytes: Long,
    ): Reading = when {
        quotaBytes > 0 -> reading(Against.QUOTA, usedBytes, quotaBytes)
        storeLimitBytes > 0 -> reading(Against.STORE, storeUsedBytes, storeLimitBytes)
        else -> reading(
            Against.DISK,
            // What the drive holds, not what CloudHub put there: everything
            // else on the disk is equally in the way of the next upload.
            used = (diskTotalBytes - diskFreeBytes).coerceAtLeast(0),
            total = diskTotalBytes,
        )
    }

    private fun reading(against: Against, used: Long, total: Long): Reading {
        val safeUsed = used.coerceAtLeast(0)
        // A total of zero happens before the first measurement lands, and
        // dividing by it would make the bar NaN rather than empty.
        val fraction = if (total <= 0) 0f else (safeUsed.toFloat() / total).coerceIn(0f, 1f)
        return Reading(
            against = against,
            usedBytes = safeUsed,
            totalBytes = total.coerceAtLeast(0),
            // Overshooting a quota is possible -- it is checked before an
            // upload, not enforced on disk -- and "-2 GB left" is nonsense.
            remainingBytes = (total - safeUsed).coerceAtLeast(0),
            fraction = fraction,
        )
    }
}
