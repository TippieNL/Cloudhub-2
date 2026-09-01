package nl.tippie.cloudhub.data

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import nl.tippie.cloudhub.ui.PlaybackTuning
import java.io.File

/**
 * Video kept on the device, so watching something twice costs the network once.
 *
 * Without this, skipping back ten seconds re-fetches ten seconds that just
 * arrived, and re-opening last night's film downloads it again from the
 * beginning. The resume feature makes that worse rather than better: it drops
 * you halfway into a file the player then has to reach from scratch.
 *
 * One instance for the whole process, because SimpleCache refuses -- loudly
 * and at runtime -- to have two objects pointed at the same directory.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object MediaCache {

    private const val DIRECTORY = "media"
    private var cache: SimpleCache? = null

    @Synchronized
    fun get(context: Context): SimpleCache = cache ?: SimpleCache(
        directory(context),
        LeastRecentlyUsedCacheEvictor(PlaybackTuning.CACHE_BYTES),
        StandaloneDatabaseProvider(context.applicationContext),
    ).also { cache = it }

    private fun directory(context: Context) =
        File(context.applicationContext.cacheDir, DIRECTORY).apply { mkdirs() }

    /** What the cache is holding, for the Storage and Settings screens. */
    @Synchronized
    fun sizeBytes(context: Context): Long =
        cache?.cacheSpace ?: directory(context).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /**
     * Drop everything cached.
     *
     * Through the cache's own API rather than by deleting the directory: the
     * index is a database beside the files, and a directory emptied behind its
     * back leaves it describing videos that are no longer there.
     */
    @Synchronized
    fun clear(context: Context) {
        val live = cache
        if (live == null) {
            directory(context).walkBottomUp().forEach { runCatching { it.delete() } }
            return
        }
        for (key in live.keys.toList()) runCatching { live.removeResource(key) }
    }
}
