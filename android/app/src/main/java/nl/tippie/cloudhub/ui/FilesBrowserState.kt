package nl.tippie.cloudhub.ui

/**
 * What the content area of the file browser should be showing.
 *
 * One decision in one place, rather than a chain of conditions inside the
 * composable. The rules are easy to get subtly wrong -- the old screen showed
 * "This folder is empty" when the *server was unreachable*, which is a
 * confident wrong answer -- and as a function they can be tested.
 */
enum class Shown {
    /** Loading, with nothing yet worth keeping on screen. */
    SKELETON,

    /** Entries to draw. A refresh over existing entries stays here. */
    CONTENT,

    /** The server answered, and the folder really has nothing in it. */
    EMPTY,

    /** There are entries, but the filter or the search matched none of them. */
    NO_MATCHES,

    /** The listing failed. Never confused with an empty folder. */
    ERROR,
}

/** How the last attempt to list a folder ended. */
enum class LoadState { LOADING, READY, FAILED }

/**
 * Which of the five states the browser is in.
 *
 * Order matters here: a failure outranks everything, and content already on
 * screen outranks a refresh's spinner -- reloading a folder you are looking at
 * should not blank it.
 */
fun browserState(
    load: LoadState,
    hasEntries: Boolean,
    hasVisible: Boolean,
    filtering: Boolean,
): Shown = when {
    load == LoadState.FAILED -> Shown.ERROR
    // A refresh keeps what is on screen; only a load with nothing to show
    // falls back to the skeleton.
    load == LoadState.LOADING && !hasEntries -> Shown.SKELETON
    hasVisible -> Shown.CONTENT
    // Still loading, but there is something to look at.
    load == LoadState.LOADING -> Shown.CONTENT
    // "Nothing matched" and "this folder is empty" are different facts, and
    // telling someone their folder is empty when they have simply mistyped a
    // search is the sort of thing that gets an app deleted.
    filtering -> Shown.NO_MATCHES
    else -> Shown.EMPTY
}

/**
 * When a skeleton may appear, and for how long at least.
 *
 * Both numbers exist to stop a flicker. A folder that lists in 40ms should
 * never show a skeleton at all; one that shows a skeleton for 30ms before the
 * answer lands looks like a glitch rather than loading.
 */
object SkeletonTiming {
    /** Below this, a load finishes before the skeleton is ever drawn. */
    const val DELAY_MS = 120L

    /** Once drawn, it stays at least this long. */
    const val MIN_SHOWN_MS = 400L

    /** How much longer to hold a skeleton that has been up for [shownForMs]. */
    fun lingerMs(shownForMs: Long): Long =
        (MIN_SHOWN_MS - shownForMs).coerceAtLeast(0L)
}

/**
 * How many skeleton cards to draw.
 *
 * Derived from the grid actually on screen rather than fixed, so a tablet in
 * landscape fills its viewport and a phone does not draw two screens of cards
 * nobody will see. One extra row is included so the first scroll does not
 * reveal a bare gap under the placeholders.
 */
fun skeletonCount(columns: Int, viewportHeightDp: Int, cardHeightDp: Int): Int {
    val safeColumns = columns.coerceAtLeast(1)
    val safeCard = cardHeightDp.coerceAtLeast(1)
    val rows = (viewportHeightDp / safeCard) + 1
    // Always at least one row: a viewport can be reported as zero on the very
    // first frame, and no skeleton at all reads as a frozen screen.
    return (rows.coerceAtLeast(1) * safeColumns).coerceAtMost(MAX_SKELETON_CARDS)
}

/** Past this the cards are off-screen in any layout, and drawing them is waste. */
const val MAX_SKELETON_CARDS = 40
