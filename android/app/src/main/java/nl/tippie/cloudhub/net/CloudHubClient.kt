package nl.tippie.cloudhub.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The single HTTP client every part of the app shares.
 *
 * Shared on purpose: thumbnails (Coil) and video playback (Media3) are
 * ordinary authenticated requests to the same server, so they need the same
 * cookie jar and the same certificate decisions. Giving them their own clients
 * is how you end up with a file list that loads and thumbnails that 401.
 */
class CloudHubClient(
    private val cookieStore: CookieStore,
    pins: PinnedCertificates,
) {
    /** Set after signing in; sent on every mutating request. */
    @Volatile var csrfToken: String = ""

    private val trustManager = PinningTrustManager(pins)

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) =
                cookieStore.save(url.host, cookies)

            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                cookieStore.load(url.host)
        })
        .sslSocketFactory(PinningTrustManager.sslSocketFactory(trustManager), trustManager)
        // Uploading a large video over a slow link must not time out mid-chunk.
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()
}

/**
 * Where the session cookie lives.
 *
 * Persisted rather than held in memory so the app does not ask for a password
 * every time it is opened, and so the server's periodic session rotation --
 * Auth::ROTATION_GRACE_SECONDS in src/Services/Auth.php -- is followed by
 * simply storing whatever Set-Cookie arrives.
 */
interface CookieStore {
    fun save(host: String, cookies: List<Cookie>)
    fun load(host: String): List<Cookie>
    fun clear()
}

/** Used by the JVM tests, and as the fallback before storage is ready. */
class InMemoryCookieStore : CookieStore {
    private val byHost = mutableMapOf<String, MutableList<Cookie>>()

    @Synchronized override fun save(host: String, cookies: List<Cookie>) {
        val existing = byHost.getOrPut(host) { mutableListOf() }
        for (cookie in cookies) {
            existing.removeAll { it.name == cookie.name }
            // A cookie the server expired is a sign-out, not a value to keep.
            if (cookie.expiresAt > System.currentTimeMillis()) existing.add(cookie)
        }
    }

    @Synchronized override fun load(host: String): List<Cookie> {
        val existing = byHost[host] ?: return emptyList()
        val now = System.currentTimeMillis()
        existing.removeAll { it.expiresAt <= now }
        return existing.toList()
    }

    @Synchronized override fun clear() = byHost.clear()
}
