package nl.tippie.cloudhub

import nl.tippie.cloudhub.ui.SubtitleRules
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a subtitle picked from the phone has to be called.
 *
 * The player finds subtitles by name -- `Holiday.en.srt` beside `Holiday.mp4`
 * -- so a file attached under the wrong one is a file that is simply never
 * seen again, which is the failure worth a test.
 */
class SubtitleRulesTest {

    /* ---- adding one ------------------------------------------------------ */

    @Test
    fun `a subtitle is named after the film it belongs to`() {
        assertEquals("Holiday.nl.srt", SubtitleRules.fileNameFor("Holiday.mp4", "nl", "srt"))
        assertEquals("Holiday.srt", SubtitleRules.fileNameFor("Holiday.mp4", "", "srt"))
        // A film with dots in its name keeps all of them but the last.
        assertEquals("The.Film.2024.en.vtt", SubtitleRules.fileNameFor("The.Film.2024.mkv", "EN", "VTT"))
    }

    @Test
    fun `only a subtitle file may be attached`() {
        assertTrue(SubtitleRules.isSubtitleFile("Holiday.srt"))
        assertTrue(SubtitleRules.isSubtitleFile("Holiday.VTT"))
        assertTrue(!SubtitleRules.isSubtitleFile("Holiday.mp4"))
        assertTrue(!SubtitleRules.isSubtitleFile("subtitles"))
    }

    @Test
    fun `the language a downloaded subtitle claims is offered`() {
        assertEquals("nl", SubtitleRules.guessLanguage("The.Film.2024.1080p.WEB-DL.nl.srt"))
        assertEquals("en", SubtitleRules.guessLanguage("Holiday.en.srt"))
        // Not a language code, so nothing is guessed rather than something
        // wrong being filled in.
        assertEquals("", SubtitleRules.guessLanguage("The.Film.2024.1080p.WEB-DL.srt"))
        assertEquals("", SubtitleRules.guessLanguage("subtitles.srt"))
    }
}
