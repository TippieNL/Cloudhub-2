package nl.tippie.cloudhub.ui

import nl.tippie.cloudhub.net.FileEntry
import nl.tippie.cloudhub.net.SubtitleTrack
import java.security.MessageDigest

/**
 * Which subtitle files belong to a video, found from folder listings.
 *
 * SubtitleService::tracksFor() on Cloudhub-2's server, ported, for a server
 * with no subtitle route of its own (Cloudhub-web): the same names are
 * claimed, with the same labels in the same order, so the menu reads the same
 * on either. Playing `Holiday.mp4` claims `Holiday.srt`, `Holiday.en.srt`,
 * `Holiday.nl.forced.srt` and the `Subs/` folder ripping tools leave beside a
 * film -- but not `Holiday 2.srt`, which is another film's.
 *
 * Tracks found here carry no `url`: the file is only fetched, and converted,
 * when the player wants it (SubtitleLoader.playable).
 */
object SidecarSubtitles {

    /** Videos a `Subs/` folder may belong to, when it holds just one. */
    private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "ogv", "mov", "m4v", "avi", "mkv",
        "mpeg", "mpg", "3gp", "3g2", "ts", "m2ts", "mts")

    /** Folder names ripping tools use for subtitles beside a film. */
    private val SIDECAR_DIRS = setOf("subs", "subtitles", "sub", "subtitle")

    /** Directory entries looked at per folder, so a huge folder stays cheap. */
    private const val MAX_SCANNED = 5000

    /** Tracks offered for one video. Past this the menu is unusable anyway. */
    const val MAX_TRACKS = 24

    /**
     * Every subtitle track beside one video. [list] lists a folder, as the
     * app's file browser does; a folder that cannot be listed has nothing in it.
     */
    suspend fun find(video: FileEntry, list: suspend (String) -> List<FileEntry>): List<SubtitleTrack> {
        if (video.isDirectory) return emptyList()
        val dir = video.path.substringBeforeLast('/', "").ifEmpty { "/" }
        val stem = filenameOf(video.name)
        val found = LinkedHashMap<String, Candidate>()

        val siblings = entries(dir, list)
        for (entry in siblings) {
            if (!entry.isDirectory && matchesStem(entry.name, stem)) take(entry, stem, found)
        }

        // A `Subs/` folder beside the film. Its files are named for the
        // language and not for the film ("English.srt"), so they are only
        // claimed when there is one film for them to belong to -- otherwise a
        // folder of episodes would show every episode the same tracks.
        val loneVideo = loneVideo(siblings, video)
        for (folder in siblings) {
            if (!folder.isDirectory || folder.name.lowercase() !in SIDECAR_DIRS) continue
            for (inner in entries(folder.path, list)) {
                if (inner.isDirectory) {
                    // Subs/<film name>/English.srt
                    if (!inner.name.equals(stem, ignoreCase = true)) continue
                    for (leaf in entries(inner.path, list)) take(leaf, stem, found)
                    continue
                }
                if (loneVideo || matchesStem(inner.name, stem)) take(inner, stem, found)
            }
        }
        return finish(found.values.toList())
    }

    /** What a file is called without its extension, as PHP's pathinfo() has it. */
    internal fun filenameOf(name: String) = if (name.contains('.')) name.substringBeforeLast('.') else name

    private fun extensionOf(name: String) = name.substringAfterLast('.', "").lowercase()

    /** Names in one folder, bounded, without dot files. */
    private suspend fun entries(folder: String, list: suspend (String) -> List<FileEntry>): List<FileEntry> =
        runCatching { list(folder) }.getOrDefault(emptyList())
            .filterNot { it.name.startsWith(".") }
            .take(MAX_SCANNED)

    /** `Holiday.srt` and `Holiday.en.forced.srt`, but not `Holiday 2.srt`. */
    internal fun matchesStem(name: String, stem: String): Boolean {
        val base = filenameOf(name)
        return base.equals(stem, ignoreCase = true) || base.startsWith("$stem.", ignoreCase = true)
    }

    /** Whether this video is the only one in its folder. */
    private fun loneVideo(siblings: List<FileEntry>, video: FileEntry): Boolean {
        val videos = siblings.count { !it.isDirectory && extensionOf(it.name) in VIDEO_EXTENSIONS }
        return videos == 1 && extensionOf(video.name) in VIDEO_EXTENSIONS
    }

    private class Candidate(val track: SubtitleTrack, val named: Boolean)

    /** Record one file, if it is a subtitle worth offering. */
    private fun take(entry: FileEntry, stem: String, found: MutableMap<String, Candidate>) {
        if (found.size >= MAX_TRACKS || entry.isDirectory) return
        if (extensionOf(entry.name) !in SubtitleRules.EXTENSIONS) return
        if (entry.size <= 0 || entry.size > SubtitleRules.MAX_BYTES) return
        if (entry.path in found) return

        val tags = tagsOf(filenameOf(entry.name), stem)
        found[entry.path] = Candidate(
            SubtitleTrack(
                // As the server makes it: stable, and saying nothing about the filesystem.
                id = sha256(entry.path).take(16),
                path = entry.path,
                name = entry.name,
                label = tags.label,
                language = tags.language,
                forced = tags.forced,
            ),
            named = tags.language.isNotEmpty(),
        )
    }

    /** What a file's name says about its track. */
    data class Tags(val label: String, val language: String, val forced: Boolean, val hearingImpaired: Boolean)

    /**
     * `en`, `forced` and `SDH` out of what is left of a file name once the
     * video's own name is taken off the front. The language is claimed first,
     * which is what keeps `Film.hi.srt` Hindi while `Film.en.hi.srt` is
     * English for the hearing impaired.
     */
    fun tagsOf(base: String, stem: String): Tags {
        val rest = if (base.startsWith(stem, ignoreCase = true)) base.substring(stem.length) else base
        val parts = rest.trim('.', '_', '-', ' ', '\t').split(Regex("""[._\-\s]+""")).map { it.trim() }

        var language = ""
        var forced = false
        var sdh = false
        val extra = mutableListOf<String>()
        for (part in parts) {
            if (part.isEmpty() || part.all(Char::isDigit)) continue
            val key = part.lowercase()
            if (language.isEmpty() && key in LANGUAGE_ALIASES) {
                language = LANGUAGE_ALIASES.getValue(key)
                continue
            }
            when (FLAGS[key]) {
                "forced" -> { forced = true; continue }
                "sdh" -> { sdh = true; continue }
            }
            if (extra.size < 3 && part.length <= 24) extra += part
        }

        var label = if (language.isNotEmpty()) LANGUAGE_NAMES[language] ?: language.uppercase()
        else extra.takeIf { it.isNotEmpty() }?.joinToString(" ") ?: "Subtitles"
        if (language.isNotEmpty() && extra.isNotEmpty()) label += " (${extra.joinToString(" ")})"
        if (sdh) label += " (SDH)"
        if (forced) label += " (Forced)"
        return Tags(label, language, forced, sdh)
    }

    /**
     * Named languages first and alphabetically, so the menu reads the same on
     * every video, and no two entries reading the same.
     */
    private fun finish(found: List<Candidate>): List<SubtitleTrack> {
        val sorted = found.sortedWith(
            compareByDescending<Candidate> { it.named }
                .thenComparator { a, b -> a.track.label.compareTo(b.track.label, ignoreCase = true) }
                .thenComparator { a, b -> a.track.name.compareTo(b.track.name, ignoreCase = true) }
        ).map { it.track }
        val counts = sorted.groupingBy { it.label }.eachCount()
        return sorted
            .map { if ((counts[it.label] ?: 0) > 1) it.copy(label = "${it.label} · ${it.name}") else it }
            .take(MAX_TRACKS)
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Tag spellings that name a language, mapped to one code per language. */
    private val LANGUAGE_ALIASES = mapOf(
        "en" to "en", "eng" to "en", "english" to "en",
        "nl" to "nl", "nld" to "nl", "dut" to "nl", "dutch" to "nl", "nederlands" to "nl",
        "de" to "de", "deu" to "de", "ger" to "de", "german" to "de", "deutsch" to "de",
        "fr" to "fr", "fra" to "fr", "fre" to "fr", "french" to "fr",
        "es" to "es", "spa" to "es", "spanish" to "es", "espanol" to "es",
        "it" to "it", "ita" to "it", "italian" to "it",
        "pt" to "pt", "por" to "pt", "portuguese" to "pt",
        "pt-br" to "pt-BR", "ptbr" to "pt-BR", "pob" to "pt-BR", "brazilian" to "pt-BR",
        "sv" to "sv", "swe" to "sv", "swedish" to "sv",
        "da" to "da", "dan" to "da", "danish" to "da",
        "no" to "no", "nor" to "no", "nob" to "no", "norwegian" to "no",
        "fi" to "fi", "fin" to "fi", "finnish" to "fi",
        "is" to "is", "isl" to "is", "ice" to "is",
        "pl" to "pl", "pol" to "pl", "polish" to "pl",
        "ru" to "ru", "rus" to "ru", "russian" to "ru",
        "uk" to "uk", "ukr" to "uk", "ukrainian" to "uk",
        "cs" to "cs", "ces" to "cs", "cze" to "cs", "czech" to "cs",
        "sk" to "sk", "slk" to "sk", "slo" to "sk",
        "sl" to "sl", "slv" to "sl",
        "hr" to "hr", "hrv" to "hr", "croatian" to "hr",
        "sr" to "sr", "srp" to "sr", "serbian" to "sr",
        "bg" to "bg", "bul" to "bg",
        "ro" to "ro", "ron" to "ro", "rum" to "ro", "romanian" to "ro",
        "hu" to "hu", "hun" to "hu", "hungarian" to "hu",
        "el" to "el", "ell" to "el", "gre" to "el", "greek" to "el",
        "tr" to "tr", "tur" to "tr", "turkish" to "tr",
        "he" to "he", "heb" to "he", "hebrew" to "he",
        "ar" to "ar", "ara" to "ar", "arabic" to "ar",
        "fa" to "fa", "fas" to "fa", "per" to "fa", "persian" to "fa",
        "hi" to "hi", "hin" to "hi", "hindi" to "hi",
        "zh" to "zh", "chi" to "zh", "zho" to "zh", "chinese" to "zh",
        "ja" to "ja", "jpn" to "ja", "japanese" to "ja",
        "ko" to "ko", "kor" to "ko", "korean" to "ko",
        "th" to "th", "tha" to "th", "thai" to "th",
        "vi" to "vi", "vie" to "vi", "vietnamese" to "vi",
        "id" to "id", "ind" to "id", "indonesian" to "id",
        "ms" to "ms", "msa" to "ms", "may" to "ms",
        "et" to "et", "est" to "et", "lv" to "lv", "lav" to "lv", "lt" to "lt", "lit" to "lt",
        "ca" to "ca", "cat" to "ca", "gl" to "gl", "glg" to "gl", "eu" to "eu", "eus" to "eu", "baq" to "eu",
    )

    /** How each code is written in the menu. */
    private val LANGUAGE_NAMES = mapOf(
        "en" to "English", "nl" to "Dutch", "de" to "German", "fr" to "French",
        "es" to "Spanish", "it" to "Italian", "pt" to "Portuguese", "pt-BR" to "Portuguese (Brazil)",
        "sv" to "Swedish", "da" to "Danish", "no" to "Norwegian", "fi" to "Finnish",
        "is" to "Icelandic", "pl" to "Polish", "ru" to "Russian", "uk" to "Ukrainian",
        "cs" to "Czech", "sk" to "Slovak", "sl" to "Slovenian", "hr" to "Croatian",
        "sr" to "Serbian", "bg" to "Bulgarian", "ro" to "Romanian", "hu" to "Hungarian",
        "el" to "Greek", "tr" to "Turkish", "he" to "Hebrew", "ar" to "Arabic",
        "fa" to "Persian", "hi" to "Hindi", "zh" to "Chinese", "ja" to "Japanese",
        "ko" to "Korean", "th" to "Thai", "vi" to "Vietnamese", "id" to "Indonesian",
        "ms" to "Malay", "et" to "Estonian", "lv" to "Latvian", "lt" to "Lithuanian",
        "ca" to "Catalan", "gl" to "Galician", "eu" to "Basque",
    )

    /** Tags that describe the track rather than name its language. */
    private val FLAGS = mapOf(
        "forced" to "forced", "foreign" to "forced",
        "sdh" to "sdh", "cc" to "sdh", "hi" to "sdh", "hearingimpaired" to "sdh",
    )
}
