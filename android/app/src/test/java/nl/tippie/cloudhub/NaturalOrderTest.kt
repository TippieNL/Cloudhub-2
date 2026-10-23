package nl.tippie.cloudhub

import nl.tippie.cloudhub.ui.NaturalOrder
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Natural Number sort: names in the order a person counts them.
 *
 * Every list here is given shuffled and checked against the order it should
 * come out in, so a comparator that merely left the input alone would fail.
 */
class NaturalOrderTest {

    /** Sorted from a few different starting orders, which must all agree. */
    private fun sorted(vararg names: String): List<String> {
        val results = (1..4).map { seed -> names.toList().shuffled(Random(seed)).sortedWith(NaturalOrder) }
        results.forEach { assertEquals(results.first(), it, "the order depended on the order it started in") }
        return results.first()
    }

    private fun assertOrder(vararg expected: String) =
        assertEquals(expected.toList(), sorted(*expected))

    /* ---- what was asked for ------------------------------------------- */

    @Test fun `plain numbers count up`() {
        assertOrder("1", "2", "3", "4", "5", "10", "11", "12")
    }

    @Test fun `a number after a word counts up`() {
        assertOrder("File 1", "File 2", "File 3", "File 10", "File 11")
        assertOrder("Folder 2", "Folder 10")
    }

    @Test fun `a number before an extension counts up`() {
        assertOrder("Image 1.jpg", "Image 2.jpg", "Image 10.jpg")
    }

    @Test fun `dotted numbers are compared section by section`() {
        assertOrder("1.2.3", "1.2.4", "1.2.10")
        // Read the way versions are: the second section is nine, then ten.
        assertOrder("1.2.3", "1.10")
        assertOrder("v1.2.3.zip", "v1.2.10.zip", "v1.10.0.zip")
    }

    @Test fun `every number in a name counts, not only the first`() {
        assertOrder("Season 1 Episode 2", "Season 1 Episode 10", "Season 2 Episode 1", "Season 10 Episode 1")
        assertOrder("IMG_2024_1_9.jpg", "IMG_2024_1_10.jpg", "IMG_2024_2_1.jpg", "IMG_2024_10_1.jpg")
    }

    /* ---- the letters around the numbers ------------------------------- */

    @Test fun `letters compare without regard to case`() {
        assertOrder("apple 2", "APPLE 3", "Apple 10", "banana 1", "Banana 2")
    }

    @Test fun `numbers sit where they do under Name, after a space and before letters`() {
        assertOrder("Track", "Track 1", "Track 2", "Track 10", "Track A", "TrackB")
    }

    @Test fun `a name that runs out first comes first`() {
        assertOrder("Folder", "Folder 2", "Folder 10", "Folder 10 old")
    }

    /* ---- extensions ----------------------------------------------------- */

    @Test fun `an original comes before its copy however the copy was named`() {
        // A character at a time, the space and the hyphen sort before the dot
        // and the underscore after it; compared before the extension, the
        // shorter name wins every time.
        for (copy in listOf("photo 2.jpg", "photo-2.jpg", "photo_2.jpg", "photo2.jpg", "photo (1).jpg")) {
            assertEquals(listOf("photo.jpg", copy), sorted(copy, "photo.jpg"), copy)
        }
        assertOrder("New Text Document.txt", "New Text Document (2).txt", "New Text Document (10).txt")
    }

    @Test fun `the number decides before the extension does`() {
        assertOrder("Image 2.png", "Image 10.jpg")
        assertOrder("Clip 9.mp4", "Clip 10.MOV", "Clip 11.avi")
    }

    @Test fun `the same name with different extensions goes by extension`() {
        assertOrder("notes", "notes.md", "notes.txt")
        assertOrder("report.DOCX", "report.pdf")
    }

    @Test fun `only something that reads as an extension is set aside`() {
        // All digits: part of the name, not an extension.
        assertOrder("Backup", "Backup.2023", "Backup.2024", "Backup.10000")
        // A space after the dot is a sentence, not an extension.
        assertOrder("Mr. Smith 2", "Mr. Smith 10")
        // A dotfile's dot is the start of its name, and a trailing dot is no extension.
        assertOrder(".hidden 2", ".hidden 10", ".nomedia")
        assertOrder("file 2.", "file 10.")
    }

    /* ---- names that read the same --------------------------------------- */

    @Test fun `fewer leading zeros come first when the numbers are equal`() {
        assertOrder("File 1", "File 01", "File 001", "File 2", "File 02")
        assertOrder("7", "007", "08", "10")
    }

    @Test fun `then upper case before lower`() {
        assertOrder("File 1", "file 1")
        assertOrder("IMG 1.JPG", "IMG 1.jpg", "img 1.jpg")
    }

    @Test fun `a difference in the letters outranks one in the zeros`() {
        assertOrder("File 01 a", "File 1 b")
    }

    @Test fun `numbers of any length compare without overflowing`() {
        assertOrder(
            "IMG 99999999999999999999.jpg",
            "IMG 123456789012345678901234567890.jpg",
            "IMG 123456789012345678901234567891.jpg",
        )
    }

    @Test fun `digits from other scripts stay text`() {
        // Not mixed in with 0-9; just a stable place to be.
        val names = sorted("file ٣", "file 3", "file 20")
        assertEquals(listOf("file 3", "file 20", "file ٣"), names)
    }

    @Test fun `empty names do not break it`() {
        assertOrder("", "0", "a")
    }

    /* ---- a comparator that sorting can rely on -------------------------- */

    private val awkward = listOf(
        "", "0", "00", "1", "01", "001", "2", "10", "a", "A", "a1", "a01", "A1", "a 1", "a-1", "a_1",
        "a.1", "a.b", "a.B", "a1.b", "a1.B", "a01.b", "a10.b", "a.", ".a", "..", ".", "1.2.3", "1.2.10",
        "1.10", "x 1 y 2", "x 1 y 10", "x 01 y 2", "File 1.jpg", "file 1.JPG", "File 1 (1).jpg",
        "photo.jpg", "photo 2.jpg", "photo_2.jpg", "Backup.2024", "Backup.old", "٣", "é 2", "É 10",
    )

    @Test fun `it is a total order on these names`() {
        for (a in awkward) for (b in awkward) {
            val ab = NaturalOrder.compare(a, b)
            val ba = NaturalOrder.compare(b, a)
            assertEquals(Integer.signum(ab), -Integer.signum(ba), "\"$a\" against \"$b\" is not antisymmetric")
            // Equal only when they are the same name, so the order never
            // falls back to whatever the server listed first.
            assertEquals(a == b, ab == 0, "\"$a\" and \"$b\" compared as ${if (ab == 0) "equal" else "different"}")
        }
        for (a in awkward) for (b in awkward) for (c in awkward) {
            if (NaturalOrder.compare(a, b) <= 0 && NaturalOrder.compare(b, c) <= 0 && NaturalOrder.compare(a, c) > 0) {
                fail("not transitive: \"$a\" <= \"$b\" <= \"$c\" but \"$a\" > \"$c\"")
            }
        }
    }

    @Test fun `a big folder sorts the same from any starting order`() {
        val names = (1..2_000).map { n ->
            listOf("IMG_$n.jpg", "Clip ${n % 97}.mp4", "v1.${n % 13}.${n % 7}", "Scan ${(n % 300).toString().padStart(3, '0')}.pdf")[n % 4]
        }.distinct()
        val once = names.shuffled(Random(1)).sortedWith(NaturalOrder)
        assertEquals(once, names.shuffled(Random(2)).sortedWith(NaturalOrder))
        assertTrue(once.zipWithNext().all { (x, y) -> NaturalOrder.compare(x, y) < 0 })
    }
}
