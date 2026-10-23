package nl.tippie.cloudhub

import nl.tippie.cloudhub.ui.LocalCopies
import nl.tippie.cloudhub.ui.PhotoFacts
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the viewer's Details says about a photo, and how a photo is handed to
 * another app.
 *
 * Reading the EXIF itself is the platform's job and needs a device; what can
 * go wrong here is the wording -- a camera named twice, a shutter speed as a
 * decimal, a camera with no clock reported as taken in the year zero.
 */
class PhotoDetailsTest {

    /* ---- dimensions ------------------------------------------------------- */

    @Test fun `dimensions come with the megapixels`() {
        assertEquals("4032 × 3024 · 12.2 MP", PhotoFacts.dimensions(4032, 3024))
        assertEquals("1080 × 2400 · 2.6 MP", PhotoFacts.dimensions(1080, 2400))
    }

    @Test fun `unknown dimensions are not shown`() {
        assertNull(PhotoFacts.dimensions(null, 3024))
        assertNull(PhotoFacts.dimensions(0, 0))
    }

    /* ---- when it was taken ------------------------------------------------ */

    @Test fun `the camera's date reads as a date`() {
        assertEquals("2024-05-17 14:03", PhotoFacts.taken("2024:05:17 14:03:22"))
    }

    @Test fun `trailing parts some cameras add are tolerated`() {
        assertEquals("2024-05-17 14:03", PhotoFacts.taken("2024:05:17 14:03:22.123"))
        assertEquals("2024-05-17 14:03", PhotoFacts.taken(" 2024:05:17 14:03:22 "))
    }

    @Test fun `a camera with no clock set has no date, rather than the year zero`() {
        assertNull(PhotoFacts.taken("0000:00:00 00:00:00"))
        assertNull(PhotoFacts.taken("    :  :     :  :  "))
        assertNull(PhotoFacts.taken(""))
        assertNull(PhotoFacts.taken(null))
    }

    /* ---- the camera ------------------------------------------------------- */

    @Test fun `make and model read as one name`() {
        assertEquals("Google Pixel 8", PhotoFacts.camera("Google", "Pixel 8"))
    }

    @Test fun `a model that already names the make does not say it twice`() {
        assertEquals("Canon EOS R6", PhotoFacts.camera("Canon", "Canon EOS R6"))
        assertEquals("samsung SM-S911B", PhotoFacts.camera("SAMSUNG", "samsung SM-S911B"))
    }

    @Test fun `either half is enough, and neither is nothing`() {
        assertEquals("iPhone 15", PhotoFacts.camera(null, "iPhone 15"))
        assertEquals("Apple", PhotoFacts.camera("Apple", "  "))
        assertNull(PhotoFacts.camera(" ", null))
    }

    /* ---- exposure --------------------------------------------------------- */

    @Test fun `exposure reads the way a camera shows it`() {
        assertEquals("f/1.7 · 1/120 s · ISO 50 · 6.9 mm", PhotoFacts.exposure(1.7, 1 / 120.0, 50, 6.9))
    }

    @Test fun `whole numbers lose their point zero`() {
        assertEquals("f/2 · 50 mm", PhotoFacts.exposure(2.0, null, null, 50.0))
    }

    @Test fun `shutter speeds below a second are fractions`() {
        assertEquals("1/8000 s", PhotoFacts.shutter(1 / 8000.0))
        assertEquals("1/2 s", PhotoFacts.shutter(0.5))
        assertEquals("2 s", PhotoFacts.shutter(2.0))
        assertEquals("1.5 s", PhotoFacts.shutter(1.5))
    }

    @Test fun `a photo with no exposure data has no exposure line`() {
        assertNull(PhotoFacts.exposure(null, null, null, null))
        assertNull(PhotoFacts.exposure(0.0, 0.0, 0, 0.0))
    }

    @Test fun `the size line comes from the facts`() {
        assertEquals("4000 × 3000 · 12.0 MP", PhotoFacts(width = 4000, height = 3000).dimensions)
        assertNull(PhotoFacts().dimensions)
    }

    /* ---- handing it to another app ---------------------------------------- */

    @Test fun `the receiving app is told the photo's real type`() {
        assertEquals("image/jpeg", LocalCopies.mimeType("jpg"))
        assertEquals("image/jpeg", LocalCopies.mimeType("JPEG"))
        assertEquals("image/png", LocalCopies.mimeType("png"))
        assertEquals("image/webp", LocalCopies.mimeType("webp"))
        assertEquals("image/svg+xml", LocalCopies.mimeType("svg"))
        assertEquals("image/avif", LocalCopies.mimeType("avif"))
        // Still an image, just not one with a name we know.
        assertEquals("image/*", LocalCopies.mimeType("heic"))
    }

    @Test fun `the copy keeps the photo's own name`() {
        assertEquals("IMG_1234.jpg", LocalCopies.fileName("IMG_1234.jpg"))
        assertEquals("Summer 2024 (2).jpg", LocalCopies.fileName("Summer 2024 (2).jpg"))
    }

    @Test fun `a name cannot reach outside the copy's folder`() {
        assertEquals("x.jpg", LocalCopies.fileName("../../x.jpg"))
        assertEquals("x.jpg", LocalCopies.fileName("..\\..\\x.jpg"))
        assertEquals("photo", LocalCopies.fileName(".."))
        assertEquals("photo", LocalCopies.fileName(""))
        assertEquals("ab.jpg", LocalCopies.fileName("a\u0000b.jpg"))
    }

    @Test fun `copies are kept for a day, for apps that read them late`() {
        val now = 10L * 24 * 60 * 60 * 1000
        assertFalse(LocalCopies.isStale(now - 60_000, now))
        assertFalse(LocalCopies.isStale(now - LocalCopies.KEEP_MS, now))
        assertTrue(LocalCopies.isStale(now - LocalCopies.KEEP_MS - 1, now))
    }
}
