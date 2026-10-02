package nl.tippie.cloudhub.ui

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * A subtitle file's bytes as WebVTT the player can read.
 *
 * SubtitleService::webVtt() on Cloudhub-2's server, ported line for line, for
 * a server that has no subtitle route of its own: Cloudhub-web serves the file
 * only as it is stored, so the conversion happens here instead. Keeping the
 * two the same means a track reads the same whichever server it came from.
 *
 * Two things stop "just play the .srt" from working:
 *
 *   SRT has no encoding. A subtitle written on Windows is very often
 *   Windows-1252, and read as UTF-8 every accented character becomes a
 *   replacement glyph -- exactly the half of a Dutch subtitle a person
 *   notices. A byte-order mark is believed; anything else that is not valid
 *   UTF-8 is read as Windows-1252, which cannot fail.
 *
 *   SRT is not WebVTT: commas in the timestamps, sequence numbers between
 *   cues, and formatting from other tools that is not meant to be shown.
 */
object SubtitleText {

    /** The most of one track that is read, as on the server (MAX_BYTES in SubtitleService.php). */
    const val MAX_BYTES = SubtitleRules.MAX_BYTES

    /** WebVTT for a file of this name, whatever it was written as. */
    fun toWebVtt(fileName: String, raw: ByteArray): String {
        val text = toUtf8(raw)
        return if (fileName.substringAfterLast('.', "").equals("vtt", ignoreCase = true)) normaliseVtt(text)
        else srtToVtt(text)
    }

    /** Bytes to text: a BOM is believed, valid UTF-8 kept, and anything else read as Windows-1252. */
    fun toUtf8(raw: ByteArray): String {
        fun starts(vararg prefix: Int) = raw.size >= prefix.size && prefix.indices.all { raw[it] == prefix[it].toByte() }
        return when {
            starts(0xEF, 0xBB, 0xBF) -> String(raw, 3, raw.size - 3, Charsets.UTF_8)
            starts(0xFF, 0xFE) -> String(raw, 2, raw.size - 2, Charsets.UTF_16LE)
            starts(0xFE, 0xFF) -> String(raw, 2, raw.size - 2, Charsets.UTF_16BE)
            else -> try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw))
                    .toString()
            } catch (e: CharacterCodingException) {
                String(raw, WINDOWS_1252)
            }
        }
    }

    /** A .vtt as the player wants it: a real header, real timestamps. */
    fun normaliseVtt(text: String): String {
        val lines = lines(text).map { line -> if (line.contains("-->")) timingLine(line) ?: line else line }
        var body = lines.joinToString("\n")
        // A file named .vtt that is really an SRT is common enough to be worth
        // handling: without the header the player rejects it outright.
        if (!body.trimStart().startsWith("WEBVTT")) body = "WEBVTT\n\n" + body.trimStart('\n')
        return body.trimEnd('\n') + "\n"
    }

    /** SubRip to WebVTT: a header, dotted timestamps, no sequence numbers. */
    fun srtToVtt(text: String): String {
        val out = ArrayList<String>()
        for (line in lines(text)) {
            if (line.contains("-->")) {
                // Not a timing line after all: dropped, as the server drops it.
                val timing = timingLine(line) ?: continue
                // The cue number ahead of it, which WebVTT has no use for.
                if (out.isNotEmpty() && out.last().trim().let { it.isNotEmpty() && it.all(Char::isDigit) }) {
                    out.removeAt(out.lastIndex)
                }
                out += timing
                continue
            }
            out += if (line.isBlank()) "" else cueText(line)
        }
        return "WEBVTT\n\n" + out.joinToString("\n").trim('\n') + "\n"
    }

    private fun lines(text: String) = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')

    private const val STAMP = """(?:\d{1,3}:)?\d{1,3}:\d{1,2}[,.]\d{1,3}"""
    private val TIMING = Regex("""^\s*($STAMP)\s*-->\s*($STAMP)\s*(.*)$""")
    private val VTT_SETTINGS = Regex("""^(?:align|position|line|size|vertical|region):""")

    /**
     * `00:01:02,500 --> 00:01:05,000  X1:100` as WebVTT, or null when the
     * line only looked like a timing line. SubRip's coordinates mean nothing
     * to WebVTT; WebVTT's own cue settings are kept.
     */
    private fun timingLine(line: String): String? {
        val match = TIMING.find(line) ?: return null
        val (start, end, rest) = match.destructured
        val settings = rest.trim().takeIf { it.isNotEmpty() && VTT_SETTINGS.containsMatchIn(it) }
        return "${timestamp(start)} --> ${timestamp(end)}" + (settings?.let { " $it" } ?: "")
    }

    /** `1:02,5` or `00:01:02,500` as `00:01:02.500`. */
    private fun timestamp(value: String): String {
        val parts = value.trim().replace(',', '.').split(':').toMutableList()
        val seconds = parts.removeLastOrNull() ?: "0"
        val minutes = parts.removeLastOrNull() ?: "0"
        val hours = parts.removeLastOrNull() ?: "0"
        val whole = seconds.substringBefore('.')
        val fraction = seconds.substringAfter('.', "0").take(3).padEnd(3, '0')
        return "%02d:%02d:%02d.%03d".format(
            java.util.Locale.ROOT, hours.toIntOrZero(), minutes.toIntOrZero(), whole.toIntOrZero(), fraction.toIntOrZero(),
        )
    }

    private fun String.toIntOrZero() = toIntOrNull() ?: 0

    private val ASS_OVERRIDE = Regex("""\{\\[^}]*\}""")
    private val FONT_TAG = Regex("""</?font[^>]*>""", RegexOption.IGNORE_CASE)
    private val ESCAPED_STYLE = Regex("""&lt;(/?[ibu])&gt;""", RegexOption.IGNORE_CASE)

    /**
     * One line of cue text: everything escaped, then the inline tags WebVTT
     * shares with SRT put back, so markup inside a subtitle cannot arrive as
     * markup. Positioning from ASS-flavoured files and `<font>` colours have
     * no WebVTT equivalent and are dropped.
     */
    private fun cueText(line: String): String {
        val plain = FONT_TAG.replace(ASS_OVERRIDE.replace(line, ""), "")
        val escaped = plain.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return ESCAPED_STYLE.replace(escaped) { "<${it.groupValues[1]}>" }
    }

    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")
}
