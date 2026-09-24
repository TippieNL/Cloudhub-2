package nl.tippie.cloudhub

import nl.tippie.cloudhub.net.SearchResult
import nl.tippie.cloudhub.ui.SearchRules
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How the All folders search is asked for, and what it says about itself.
 *
 * Every one of these was a way for a search to look broken: a search from a
 * subfolder that could not find what was one folder over, a slice loop that
 * never ends, and "No matches" for a search the server had only cut short.
 */
class SearchRulesTest {

    private fun slice(incomplete: Boolean, scanned: Int) =
        SearchResult(incomplete = incomplete, scanned = scanned)

    @Test
    fun `All folders searches every folder, not the one on screen`() {
        assertEquals("/", SearchRules.SCOPE)
    }

    @Test
    fun `a query needs two characters that are not spaces`() {
        assertFalse(SearchRules.canSearch("a"))
        assertFalse(SearchRules.canSearch(" a "))
        assertTrue(SearchRules.canSearch("ab"))
        assertTrue(SearchRules.canSearch("  ab  "))
    }

    @Test
    fun `an incomplete answer is asked for again`() {
        assertTrue(SearchRules.shouldContinue(slice(incomplete = true, scanned = 900), scannedBefore = -1, slicesSoFar = 1))
    }

    @Test
    fun `a complete answer ends the search`() {
        assertFalse(SearchRules.shouldContinue(slice(incomplete = false, scanned = 900), scannedBefore = -1, slicesSoFar = 1))
    }

    @Test
    fun `a slice that got no further ends it too`() {
        // It would get no further next time either: a server that cannot keep
        // what it walked answers the same slice for ever.
        assertFalse(SearchRules.shouldContinue(slice(incomplete = true, scanned = 900), scannedBefore = 900, slicesSoFar = 2))
    }

    @Test
    fun `and so does the slice bound`() {
        assertFalse(SearchRules.shouldContinue(slice(incomplete = true, scanned = 5000), scannedBefore = 10, slicesSoFar = SearchRules.MAX_SLICES))
    }

    @Test
    fun `a server that answers in one go is asked once`() {
        // Cloudhub-2's own server has no slices: no `incomplete`, which decodes as false.
        assertFalse(SearchRules.shouldContinue(SearchResult(scanned = 44_000), scannedBefore = -1, slicesSoFar = 1))
    }

    private fun summary(
        results: Int? = 0, truncated: Boolean = false, scanned: Int = 44_994,
        searching: Boolean = false, error: String? = null,
    ) = SearchRules.summary("holiday", results?.let { List(it) { "" } }, truncated, scanned, searching, error)

    @Test
    fun `no matches is only said once every folder was searched`() {
        assertEquals("No matches for \"holiday\" in any folder", summary())
    }

    @Test
    fun `a search stopped at a bound says where it stopped`() {
        assertEquals("No matches for \"holiday\" in the first 44994 files searched", summary(truncated = true))
    }

    @Test
    fun `a search still going says so, before and after its first matches`() {
        assertEquals("Searching all folders for \"holiday\"…", summary(results = null, searching = true))
        assertEquals("3 matches so far, still searching…", summary(results = 3, searching = true))
    }

    @Test
    fun `results say how many, and whether that is all`() {
        assertEquals("1 match in all folders", summary(results = 1))
        assertEquals("200 matches (showing the first 200)", summary(results = 200, truncated = true))
    }

    @Test
    fun `a failed search says it failed, not that nothing matched`() {
        assertEquals("Search failed: timeout", summary(error = "timeout"))
        assertEquals("4 matches so far; the search stopped: timeout", summary(results = 4, error = "timeout"))
    }
}
