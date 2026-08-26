package nl.tippie.cloudhub

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
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
    }

    fun useServer(url: String) {
        settings.serverUrl = url
        api.baseUrl = url
    }

    /**
     * Thumbnails go through the same client as everything else, so they carry
     * the session cookie and the same certificate decisions. A separate loader
     * is how you get a file list that loads and thumbnails that all 401.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { client.okHttp }
            .components {
                // A video the server has no cached frame for still gets a
                // picture, decoded from the stream on the device.
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()

    companion object {
        fun from(context: android.content.Context) =
            context.applicationContext as CloudHubApp
    }
}
