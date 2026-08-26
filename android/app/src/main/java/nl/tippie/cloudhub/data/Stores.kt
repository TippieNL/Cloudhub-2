package nl.tippie.cloudhub.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nl.tippie.cloudhub.net.CookieStore
import nl.tippie.cloudhub.net.PinnedCertificates
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File

/**
 * Small, boring persistence.
 *
 * SharedPreferences rather than DataStore: everything here is read at startup
 * on a handful of keys, and DataStore's coroutine API would have to be awaited
 * from OkHttp's synchronous cookie jar -- a needless piece of bridging for a
 * few strings.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("cloudhub", Context.MODE_PRIVATE)

    var serverUrl: String?
        get() = prefs.getString("server_url", null)
        set(value) = prefs.edit().putString("server_url", value).apply()

    /** Grid or list, remembered per install like the web app does. */
    var gridView: Boolean
        get() = prefs.getBoolean("grid_view", true)
        set(value) = prefs.edit().putBoolean("grid_view", value).apply()

    fun signOut() = prefs.edit().remove("cookies").apply()

    fun forgetServer() = prefs.edit().clear().apply()

    internal fun readCookies(): String? = prefs.getString("cookies", null)
    internal fun writeCookies(value: String) = prefs.edit().putString("cookies", value).apply()

    internal fun readPins(): Set<String> = prefs.getStringSet("cert_pins", emptySet()) ?: emptySet()
    internal fun addPin(fingerprint: String) =
        prefs.edit().putStringSet("cert_pins", readPins() + fingerprint).apply()
}

/**
 * The session cookie, kept across app restarts.
 *
 * Without persistence the app would ask for a password every time it was
 * opened. The server rotates the session periodically, so whatever arrives in
 * Set-Cookie replaces what is held -- there is nothing to keep in step by hand.
 */
class PersistentCookieStore(private val settings: Settings) : CookieStore {

    @Serializable private data class Stored(
        val host: String, val name: String, val value: String,
        val expiresAt: Long, val path: String, val secure: Boolean, val httpOnly: Boolean,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val cookies = mutableListOf<Stored>()

    init {
        settings.readCookies()?.let { raw ->
            runCatching { json.decodeFromString<List<Stored>>(raw) }
                .getOrNull()?.let { cookies.addAll(it) }
        }
    }

    @Synchronized override fun save(host: String, incoming: List<Cookie>) {
        for (cookie in incoming) {
            cookies.removeAll { it.host == host && it.name == cookie.name }
            // An expiry in the past is the server signing us out.
            if (cookie.expiresAt > System.currentTimeMillis()) {
                cookies.add(Stored(host, cookie.name, cookie.value, cookie.expiresAt,
                    cookie.path, cookie.secure, cookie.httpOnly))
            }
        }
        persist()
    }

    @Synchronized override fun load(host: String): List<Cookie> {
        val now = System.currentTimeMillis()
        if (cookies.removeAll { it.expiresAt <= now }) persist()
        return cookies.filter { it.host == host }.mapNotNull { stored ->
            runCatching {
                Cookie.Builder()
                    .name(stored.name).value(stored.value)
                    .domain(stored.host).path(stored.path)
                    .expiresAt(stored.expiresAt)
                    .apply { if (stored.secure) secure(); if (stored.httpOnly) httpOnly() }
                    .build()
            }.getOrNull()
        }
    }

    @Synchronized override fun clear() {
        cookies.clear()
        persist()
    }

    private fun persist() = settings.writeCookies(json.encodeToString(cookies.toList()))
}

/**
 * Certificates the user has explicitly accepted.
 *
 * Stored by fingerprint, never by host: accepting one certificate must not
 * become blanket trust for whatever that host presents next week.
 */
class CertificatePins(private val settings: Settings) : PinnedCertificates {
    override fun isPinned(fingerprint: String) = fingerprint in settings.readPins()
    fun trust(fingerprint: String) = settings.addPin(fingerprint)
}

/**
 * Turn a typed address into an origin.
 *
 * People type "files.example.com", "https://files.example.com/" and
 * ".../index.php" and mean the same server. Returns null when nothing usable
 * is left, so the caller can say so rather than failing later with a blank
 * screen.
 */
object ServerAddress {
    fun normalise(input: String?): String? {
        if (input == null) return null
        var url = input.trim()
        if (url.isEmpty()) return null
        if (!url.matches(Regex("(?i)^https?://.*"))) url = "https://$url"
        url = url.trimEnd('/')
        if (url.endsWith("/index.php", ignoreCase = true)) url = url.dropLast(10)
        if (url.isEmpty()) return null
        return runCatching { url.toHttpUrl(); url }.getOrNull()
    }

    fun isInsecure(url: String) = url.startsWith("http://", ignoreCase = true)
}
