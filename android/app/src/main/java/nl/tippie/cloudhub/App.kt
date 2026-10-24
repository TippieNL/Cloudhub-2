package nl.tippie.cloudhub

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import nl.tippie.cloudhub.data.CertificatePins
import nl.tippie.cloudhub.data.PersistentCookieStore
import nl.tippie.cloudhub.data.Settings
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.CloudHubClient

/**
 * Everything long-lived, wired once.
 *
 * There is no dependency-injection framework: this app has one client, one API
 * and one settings store, and a container that hands those out is easier to
 * follow than the graph a framework would generate for it.
 */
class CloudHubApp : Application(), ImageLoaderFactory {

    lateinit var settings: Settings; private set
    lateinit var pins: CertificatePins; private set
    lateinit var client: CloudHubClient; private set
    lateinit var api: CloudHubApi; private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        pins = CertificatePins(settings)
        client = CloudHubClient(PersistentCookieStore(settings), pins)
        api = CloudHubApi(settings.serverUrl.orEmpty(), client)
        api.thumbnailFormat = settings.thumbnailFormat
        api.onThumbnailFormat = { settings.thumbnailFormat = it }
    }

    fun useServer(url: String) {
        settings.serverUrl = url
        api.baseUrl = url
        // Another server's format says nothing about this one's.
        api.thumbnailFormat = null
        settings.thumbnailFormat = null
    }

    /** One memory cache for both loaders: a thumbnail decoded for the grid is the one the viewer shows first. */
    private val memory: MemoryCache by lazy { MemoryCache.Builder(this).maxSizePercent(0.25).build() }

    /**
     * Thumbnails, with a disk cache of their own.
     *
     * One cache for everything meant every photo opened full size -- several
     * megabytes each -- pushed hundreds of 30 KB thumbnails out of it, and the
     * grid fetched them again on the next scroll. Apart, opening photos can
     * only push out other photos. 2% of the disk, between 32 MB and 512 MB:
     * room for well over ten thousand thumbnails on any phone that has it.
     *
     * The server marks a thumbnail cacheable for a year, and its URL changes
     * with the file and with the thumbnail format, so what is held here is
     * read from disk -- never asked for again -- until it is pushed out.
     */
    val thumbnails: ImageLoader by lazy {
        ImageLoader.Builder(this)
            .okHttpClient { client.okHttp }
            .memoryCache { memory }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("thumbnail_cache"))
                    .maxSizePercent(0.02)
                    .minimumMaxSizeBytes(32L * 1024 * 1024)
                    .maximumMaxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    /**
     * Thumbnails go through the same client as everything else, so they carry
     * the session cookie and the same certificate decisions. A separate loader
     * is how you get a file list that loads and thumbnails that all 401.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { client.okHttp }
            .memoryCache { memory }
            .components {
                // A video the server has no cached frame for still gets a
                // picture, decoded from the stream on the device.
                add(VideoFrameDecoder.Factory())
                // Animation, for the files that have it; a still image still
                // goes to the ordinary decoder. The platform's own decoder
                // from Android 9, which also plays animated WebP and HEIF,
                // and Coil's before that.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .crossfade(true)
            .build()

    companion object {
        fun from(context: android.content.Context) =
            context.applicationContext as CloudHubApp

        /** The thumbnail loader, or the ordinary one where there is no app to ask -- a preview, a test. */
        fun thumbnailsOf(context: android.content.Context): ImageLoader =
            (context.applicationContext as? CloudHubApp)?.thumbnails ?: coil.Coil.imageLoader(context)
    }
}
