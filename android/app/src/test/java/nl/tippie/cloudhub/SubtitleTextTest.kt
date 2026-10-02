package nl.tippie.cloudhub

import nl.tippie.cloudhub.ui.SubtitleText
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Subtitle bytes to WebVTT on the phone, for a server that only stores them.
 *
 * The same checks as the conversion half of tests/phase30_subtitles_test.php,
 * which holds SubtitleService::webVtt() on Cloudhub-2's server: a track has to
 * read the same whichever of the two converted it.
 */
class SubtitleTextTest {

    private val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    private fun srt(text: String) = SubtitleText.toWebVtt("Film.srt", text.toByteArray())

    /* ---- SubRip to WebVTT --------------------------------------------------- */

    private val converted = SubtitleText.toWebVtt("Convert.srt", bom + (
        "1\r\n00:00:01,000 --> 00:00:02,500\r\nFirst line\r\nSecond line\r\n\r\n" +
            "2\r\n00:01:02,5 --> 01:02:03,456\r\n<i>Italic</i> <font color=\"#ff0000\">red</font>\r\n" +
            "{\\an8}Top of screen\r\n").toByteArray())

    @Test fun `the conversion announces itself as WebVTT`() = assertTrue(converted.startsWith("WEBVTT\n\n"))

    @Test fun `commas become dots and every stamp is full width`() {
        assertTrue(converted.contains("00:00:01.000 --> 00:00:02.500"), converted)
        assertTrue(converted.contains("00:01:02.500 --> 01:02:03.456"), converted)
    }

    @Test fun `the cue numbers are gone`() =
        assertFalse(converted.lines().any { it.isNotEmpty() && it.all(Char::isDigit) }, converted)

    @Test fun `a cue keeps both of its lines`() = assertTrue(converted.contains("First line\nSecond line"))

    @Test fun `italics survive and colours do not`() {
        assertTrue(converted.contains("<i>Italic</i>"))
        assertFalse(converted.contains("font"))
    }

    @Test fun `positioning overrides are dropped, not shown`() {
        assertTrue(converted.contains("Top of screen"))
        assertFalse(converted.contains("{\\an8}"))
    }

    @Test fun `the BOM does not reach the player`() = assertFalse(converted.contains('﻿'))

    @Test fun `markup inside a cue arrives as text`() {
        val hostile = srt("1\n00:00:01,000 --> 00:00:02,000\n<script>alert(1)</script> & co\n")
        assertTrue(hostile.contains("&lt;script&gt;") && !hostile.contains("<script>"), hostile)
        assertTrue(hostile.contains("&amp; co"), hostile)
    }

    @Test fun `the whole of a small file comes out exactly`() {
        assertEquals(
            "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nHallo daar\n\n00:00:04.500 --> 00:00:05.500\nTot ziens\n",
            srt("1\n00:00:01,000 --> 00:00:03,000\nHallo daar\n\n2\n00:00:04,500 --> 00:00:05,500\nTot ziens\n"),
        )
    }

    @Test fun `a line that only looks like timing is dropped`() {
        assertFalse(srt("1\n00:00:01,000 --> soon\nText\n").contains("soon"))
    }

    /* ---- encodings ------------------------------------------------------------ */

    @Test fun `a Windows-1252 subtitle arrives readable`() {
        // "Café crème" as Windows writes it: é is 0xE9 and è 0xE8, which is not UTF-8.
        val latin = "1\n00:00:01,000 --> 00:00:02,000\nCaf".toByteArray() + byteArrayOf(0xE9.toByte()) +
            " cr".toByteArray() + byteArrayOf(0xE8.toByte()) + "me\n".toByteArray()
        assertTrue(SubtitleText.toWebVtt("Latin.srt", latin).contains("Café crème"))
    }

    @Test fun `Windows-1252's own characters are read as such`() {
        // 0x80 is the euro sign in Windows-1252 and nothing at all in Latin-1.
        assertEquals("€5", SubtitleText.toUtf8(byteArrayOf(0x80.toByte(), '5'.code.toByte())))
    }

    @Test fun `a UTF-8 subtitle is left alone`() =
        assertTrue(srt("1\n00:00:01,000 --> 00:00:02,000\nCafé crème\n").contains("Café crème"))

    @Test fun `a UTF-16 subtitle arrives readable, either way round`() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nCafé\n"
        val little = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val big = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)
        assertTrue(SubtitleText.toWebVtt("Utf16.srt", little).contains("Café"))
        assertTrue(SubtitleText.toWebVtt("Utf16.srt", big).contains("Café"))
    }

    /* ---- files that are already WebVTT ---------------------------------------- */

    @Test fun `a real WebVTT file keeps its header once, and its cue settings`() {
        val real = SubtitleText.toWebVtt("Real.vtt",
            "WEBVTT\n\nNOTE this is a comment\n\ncue-1\n00:00:01.000 --> 00:00:02.000 align:start\nAs written\n".toByteArray())
        assertEquals(1, Regex("WEBVTT").findAll(real).count())
        assertTrue(real.contains("00:00:01.000 --> 00:00:02.000 align:start"))
    }

    @Test fun `an srt named vtt is repaired rather than refused`() {
        val liar = SubtitleText.toWebVtt("Liar.VTT", "1\n00:00:01,000 --> 00:00:02,000\nActually SubRip\n".toByteArray())
        assertTrue(liar.startsWith("WEBVTT\n\n"), liar)
        assertTrue(liar.contains("00:00:01.000 --> 00:00:02.000"), liar)
    }

    @Test fun `short vtt stamps are written out in full`() {
        val vtt = SubtitleText.toWebVtt("Short.vtt", (String(bom) + "WEBVTT - Film\r\n\r\n00:01.000 --> 00:04.000\r\nHallo\r\n").toByteArray())
        assertTrue(vtt.contains("00:00:01.000 --> 00:00:04.000"), vtt)
        assertFalse(vtt.contains('\r'))
    }

    @Test fun `SubRip coordinates are not passed off as cue settings`() =
        assertTrue(srt("1\n00:00:01,000 --> 00:00:02,000  X1:100 X2:200\nPlaced\n")
            .contains("00:00:01.000 --> 00:00:02.000\nPlaced"))
}
