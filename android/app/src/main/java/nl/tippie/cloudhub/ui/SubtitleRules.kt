package nl.tippie.cloudhub.ui

/**
 * What an added subtitle file has to be called.
 *
 * Finding and showing subtitles is the player's business, and it looks for
 * files named after the film: `Holiday.en.srt` beside `Holiday.mp4`. Adding
 * one is therefore nothing but an upload with the right name, and this is
 * where that name is worked out -- the only part of it worth testing without
 * a phone in hand.
 */
object SubtitleRules {

    /** What may be attached. Anything else is not a subtitle, whatever it says. */
    val EXTENSIONS = setOf("srt", "vtt")

    fun isSubtitleFile(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in EXTENSIONS

    /**
     * The language a downloaded subtitle's own name is claiming.
     *
     * Files arrive called "The.Film.2024.1080p.WEB-DL.nl.srt", so the segment
     * before the extension is usually the answer -- but only when it looks
     * like a language code, because "WEB-DL" is not one. The guess is offered
     * for correction, never used silently.
     */
    fun guessLanguage(fileName: String): String {
        val stem = fileName.substringBeforeLast('.', fileName)
        if (!stem.contains('.')) return ""
        val last = stem.substringAfterLast('.').lowercase()
        return if (last.matches(Regex("[a-z]{2,3}"))) last else ""
    }

    /**
     * The name a subtitle has to have for the player to find it.
     *
     * `Holiday.mp4` plus "nl" gives `Holiday.nl.srt`; no language gives
     * `Holiday.srt`, which is the right answer when there is only one.
     */
    fun fileNameFor(videoName: String, language: String, extension: String): String {
        val stem = videoName.substringBeforeLast('.', videoName)
        val tag = language.trim().lowercase()
        val suffix = extension.trim().lowercase().removePrefix(".")
        return if (tag.isEmpty()) "$stem.$suffix" else "$stem.$tag.$suffix"
    }
}
