package nl.tippie.cloudhub.ui

import nl.tippie.cloudhub.net.SearchResult

/**
 * How the All folders search is asked for, and what its header says.
 *
 * The search used to go wrong in ways that all looked like "search does not
 * work": it walked only the folder on screen, whatever the button said; an
 * edit to the query left the last query's results under the new text; and a
 * search the server had cut short came back as "No matches".
 */
object SearchRules {

    /** The server refuses anything shorter. */
    const val MIN_QUERY = 2

    /**
     * Where All folders searches from: everywhere, as the button and the empty
     * state ("Try All folders to search everywhere") both promise. It used to
     * be the folder on screen, so a search from inside DCIM could never find
     * anything in Documents.
     */
    const val SCOPE = "/"

    /**
     * How long the server may walk before answering with what it has.
     *
     * A server that supports it (Cloudhub-web) answers `incomplete` and is asked
     * again, so results appear in a second or two and grow; one that does not
     * ignores the parameter and answers in one go.
     */
    const val SLICE_MS = 2_000

    /** How long typing must pause before an edit searches again. */
    const val DEBOUNCE_MS = 400L

    /**
     * How many slices to ask for before giving up. At two seconds a slice this
     * is minutes of walking, far beyond any phone's storage; it exists so that
     * a server that never finishes is not asked for ever.
     */
    const val MAX_SLICES = 100

    /** Whether a query is long enough to send. */
    fun canSearch(query: String): Boolean = query.trim().length >= MIN_QUERY

    /**
     * Whether to ask for another slice: the server said there is more, it got
     * further than last time, and the bound has not been reached. A slice that
     * got no further would get no further next time either.
     */
    fun shouldContinue(found: SearchResult, scannedBefore: Int, slicesSoFar: Int): Boolean =
        found.incomplete && found.scanned > scannedBefore && slicesSoFar < MAX_SLICES

    /**
     * The line above the results.
     *
     * "No matches" is only said once every folder has been searched: a search
     * still running says so, and one that stopped at a bound says where.
     */
    fun summary(
        query: String,
        results: List<*>?,
        truncated: Boolean,
        scanned: Int,
        searching: Boolean,
        error: String?,
    ): String {
        val n = results?.size ?: 0
        val matches = "$n match${if (n == 1) "" else "es"}"
        return when {
            error != null && n > 0 -> "$matches so far; the search stopped: $error"
            error != null -> "Search failed: $error"
            searching && n == 0 -> "Searching all folders for \"$query\"…"
            searching -> "$matches so far, still searching…"
            n == 0 && truncated -> "No matches for \"$query\" in the first $scanned files searched"
            n == 0 -> "No matches for \"$query\" in any folder"
            truncated -> "$matches (showing the first $n)"
            else -> "$matches in all folders"
        }
    }
}
