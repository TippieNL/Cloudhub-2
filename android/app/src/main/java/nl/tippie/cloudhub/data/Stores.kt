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

    /**
     * The last username, when the sign-in screen was asked to remember it.
     *
     * Only the name: the password is never stored. The session cookie already
     * survives a restart, so this is for the case where it has lapsed and the
     * form has to be filled in again.
     */
    var rememberedUsername: String?
        get() = prefs.getString("remembered_username", null)
        set(value) =
            if (value.isNullOrBlank()) prefs.edit().remove("remembered_username").apply()
            else prefs.edit().putString("remembered_username", value).apply()

    /**
     * Which theme to use: follow the system, or override it.
     *
     * Stored as the enum's own name so the value is readable in the
     * preferences file and survives reordering the enum.
     */
    var themeChoice: String?
        get() = prefs.getString("theme_choice", null)
        set(value) = prefs.edit().putString("theme_choice", value).apply()

    /** How many videos have a remembered position, for the settings screen. */
    fun rememberedPositionCount(): Int = readResumeMap().size

    /** Forget every remembered position. */
    fun forgetAllResumePositions() = prefs.edit().remove("resume_positions").apply()

    /** Grid or list, remembered per install like the web app does. */
    var gridView: Boolean
        get() = prefs.getBoolean("grid_view", true)
        set(value) = prefs.edit().putBoolean("grid_view", value).apply()

    /* ---- where playback got to ------------------------------------------
     *
     * Kept as one string rather than a key per file, so the whole set can be
     * pruned in a single write and preferences cannot grow without limit as
     * more videos are watched.
     */
    fun resumePositionOf(path: String): Long = readResumeMap()[path] ?: 0L

    fun rememberResumePosition(path: String, positionMs: Long) {
        val updated = readResumeMap().toMutableMap()
        if (positionMs <= 0) updated.remove(path) else updated[path] = positionMs
        writeResumeMap(ResumePolicy.prune(updated, ResumePolicy.MAX_REMEMBERED))
    }

    fun forgetResumePosition(path: String) = rememberResumePosition(path, 0L)

    private fun readResumeMap(): Map<String, Long> =
        runCatching {
            Json.decodeFromString<Map<String, Long>>(prefs.getString("resume_positions", null) ?: "{}")
        }.getOrDefault(emptyMap())

    private fun writeResumeMap(value: Map<String, Long>) {
        runCatching { prefs.edit().putString("resume_positions", Json.encodeToString(value)).apply() }
    }

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
        // Stored in OkHttp's canonical form, so a typed space and a typed %20
        // become one value rather than two spellings of the same server.
        return runCatching { url.toHttpUrl().toString().trimEnd('/') }.getOrNull()
    }

    fun isInsecure(url: String) = url.startsWith("http://", ignoreCase = true)
}

/**
 * Whether a remembered position is worth returning to, and how many to keep.
 *
 * Pure on purpose. SharedPreferences needs a device, but the rules that
 * actually have edge cases -- the two ends of a video, and eviction -- do not,
 * so they are separated out and tested on the host.
 */
object ResumePolicy {

    /** Below this, starting over is what the viewer wanted anyway. */
    const val MIN_POSITION_MS = 10_000L

    /** Within this of the end, the video is finished, not paused. */
    const val END_MARGIN_MS = 15_000L

    /** Enough for a long watchlist; bounded so preferences stay small. */
    const val MAX_REMEMBERED = 200

    /**
     * Resuming a film two seconds from the end is worse than starting it, and
     * so is resuming ten seconds in. Both ends are excluded.
     *
     * A duration of zero means the player does not know it yet -- live, or not
     * prepared -- in which case a stored position is still worth honouring.
     */
    fun shouldResume(positionMs: Long, durationMs: Long): Boolean {
        if (positionMs < MIN_POSITION_MS) return false
        if (durationMs <= 0) return true
        return positionMs < durationMs - END_MARGIN_MS
    }

    /**
     * Keep the most recent entries.
     *
     * Positions are milliseconds into a file, not timestamps, so they cannot
     * order themselves; insertion order is the only recency available, and
     * LinkedHashMap preserves it.
     */
    fun prune(entries: Map<String, Long>, limit: Int): Map<String, Long> {
        if (limit <= 0) return emptyMap()
        if (entries.size <= limit) return entries
        return entries.entries.drop(entries.size - limit).associate { it.key to it.value }
    }
}
