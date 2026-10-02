package nl.tippie.cloudhub

import nl.tippie.cloudhub.work.UploadRefusal
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which refusals end an upload, and which are tried again.
 *
 * A refusal taken for passing used to be retried for ever at the head of the
 * queue, holding up every file behind it; one taken for final drops a file
 * that would have finished. The statuses are the ones UploadService gives.
 */
class UploadRefusalTest {

    @Test fun `a refused name, type or size ends the upload, wherever it happens`() {
        for (status in listOf(400, 403, 413, 415, 422, 507)) {
            assertTrue(UploadRefusal.isFinal(status, starting = true), "$status when starting")
            assertTrue(UploadRefusal.isFinal(status, starting = false), "$status part-way")
        }
    }

    @Test fun `a missing folder or a clashing id ends it when starting`() {
        assertTrue(UploadRefusal.isFinal(404, starting = true))
        assertTrue(UploadRefusal.isFinal(409, starting = true))
    }

    @Test fun `but part-way they mean start again, which resumes`() {
        // "Upload session not found or expired", "Upload offset mismatch",
        // "Upload is incomplete": opening it again picks up from what the
        // server holds.
        assertFalse(UploadRefusal.isFinal(404, starting = false))
        assertFalse(UploadRefusal.isFinal(409, starting = false))
    }

    @Test fun `signing in again, waiting or the server's own trouble are tried again`() {
        for (status in listOf(401, 419, 408, 429, 500, 502, 503, 504)) {
            assertFalse(UploadRefusal.isFinal(status, starting = true), "$status when starting")
            assertFalse(UploadRefusal.isFinal(status, starting = false), "$status part-way")
        }
    }
}
