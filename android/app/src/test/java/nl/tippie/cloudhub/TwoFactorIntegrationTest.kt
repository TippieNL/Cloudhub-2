package nl.tippie.cloudhub

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.tippie.cloudhub.net.ApiError
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.CloudHubClient
import nl.tippie.cloudhub.net.InMemoryCookieStore
import nl.tippie.cloudhub.net.PinnedCertificates
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Two-step verification against a real, running CloudHub: the app's calls
 * end to end, codes and all.
 *
 * Needs a server that sends its email to the stand-in SMTP server in
 * tests/http/smtp_sink.php, and the folder that one records the mail in:
 *
 *     php tests/http/smtp_sink.php 8902 /tmp/cloudhub-mail &
 *     SMTP_HOST=127.0.0.1 SMTP_PORT=8902 SMTP_ENCRYPTION=none \
 *       MAIL_FROM_ADDRESS=codes@cloudhub.test php -S 127.0.0.1:8901 -t public router.php
 *     CLOUDHUB_TEST_2FA_URL=http://127.0.0.1:8901 \
 *     CLOUDHUB_TEST_MAIL_DIR=/tmp/cloudhub-mail gradle test
 *
 * Without both it skips, like ApiIntegrationTest without CLOUDHUB_TEST_URL.
 * It signs in as the administrator (CLOUDHUB_TEST_USER/PASS) only to create
 * an account of its own and to delete it afterwards: turning two-step
 * verification on for a shared account would lock the other tests out of it.
 * Each run uses a fresh address, so the server's per-address limit starts
 * empty; its per-client limit (TWO_FACTOR_EMAIL_IP_PER_HOUR, 20) is what
 * stops many runs in an hour.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TwoFactorIntegrationTest {

    companion object {
        private val baseUrl: String? = System.getenv("CLOUDHUB_TEST_2FA_URL")
        private val mailbox: File? = System.getenv("CLOUDHUB_TEST_MAIL_DIR")?.let { File(it, "received.jsonl") }
        private val adminUser = System.getenv("CLOUDHUB_TEST_USER") ?: "admin"
        private val adminPass = System.getenv("CLOUDHUB_TEST_PASS") ?: "smoke-test-pass-123"

        private val stamp = System.currentTimeMillis().toString().takeLast(8)
        private val username = "app2fa_$stamp"
        private const val PASSWORD = "app-2fa-pass-123456"
        /** An address of our own each run, at a domain reserved for examples: nobody's mailbox. */
        private val email = "app2fa.$stamp@example.org"
        /** How the server shows it: the first letter and the domain. */
        private val hint = "a•••@example.org"

        private lateinit var admin: Pair<CloudHubApi, CloudHubClient>
        private var userId = 0
        private var ready = false

        /** Shared between the ordered tests below. */
        private var signedIn: CloudHubApi? = null
        private var recoveryCodes: List<String> = emptyList()

        private fun newApi(): Pair<CloudHubApi, CloudHubClient> {
            val client = CloudHubClient(InMemoryCookieStore(), object : PinnedCertificates {
                override fun isPinned(fingerprint: String) = false
            })
            return CloudHubApi(baseUrl!!, client) to client
        }

        /** The admin-only user routes the app itself never calls. */
        private fun adminCall(method: String, route: String, body: String?): String {
            val (api, client) = admin
            val request = Request.Builder()
                .url(api.url(route))
                .method(method, body?.toRequestBody("application/json".toMediaType()))
                .header("X-CSRF-Token", client.csrfToken)
                .build()
            client.okHttp.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) fail("$method $route: HTTP ${response.code} $text")
                return text
            }
        }

        @BeforeClass
        @JvmStatic
        fun setUp() {
            if (baseUrl == null || mailbox == null) return
            admin = newApi()
            runBlocking {
                assertTrue(admin.first.login(adminUser, adminPass).success, "the administrator could not sign in")
            }
            val created = adminCall("POST", "/api/users",
                """{"username":"$username","password":"$PASSWORD","role":"editor"}""")
            userId = Json.parseToJsonElement(created).jsonObject["id"]!!.jsonPrimitive.int
            ready = true
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            if (ready && userId > 0) runCatching { adminCall("DELETE", "/api/users/$userId", "{}") }
        }

        /** The newest code the stand-in SMTP server took for our address. */
        private fun latestCode(): String {
            val mail = mailbox!!.readLines()
                .map { Json.parseToJsonElement(it).jsonObject }
                .lastOrNull { m -> m["to"]?.jsonArray?.any { it.jsonPrimitive.content == email } == true }
                ?: fail("nothing in ${mailbox.path} for $email")
            // Quoted-printable: undo the soft line breaks; the code line is plain digits.
            val data = mail["data"]!!.jsonPrimitive.content.replace("=\r\n", "")
            return Regex("""\r\n {4}(\d{6})\r\n""").find(data)?.groupValues?.get(1)
                ?: fail("no code in the email")
        }
    }

    private fun requireServer() {
        assumeTrue("set CLOUDHUB_TEST_2FA_URL and CLOUDHUB_TEST_MAIL_DIR to run the two-step tests", baseUrl != null && mailbox != null)
        assertTrue(ready, "set-up failed")
    }

    private suspend fun expectRefusal(code: String, block: suspend () -> Unit): ApiError {
        try {
            block()
        } catch (e: ApiError) {
            assertEquals(code, e.code, e.message)
            return e
        }
        fail("expected $code")
    }

    @Test fun `01 a password alone signs in while it is off`() = runBlocking<Unit> {
        requireServer()
        val (api, _) = newApi()
        val result = api.login(username, PASSWORD)
        assertTrue(result.success)
        assertNull(result.twoFactor)
        val overview = api.twoFactor()
        assertTrue(overview.available, "the server must be able to email: SMTP_HOST pointing at the stand-in")
        assertFalse(overview.enabled)
    }

    @Test fun `02 turning it on takes the password and the new address's code`() = runBlocking<Unit> {
        requireServer()
        val (api, _) = newApi()
        api.login(username, PASSWORD)

        expectRefusal("FORBIDDEN") { api.startTwoFactorChange("email", "not-the-password-1", email) }
        expectRefusal("VALIDATION_FAILED") { api.startTwoFactorChange("email", PASSWORD, "not an address") }

        val stage = api.startTwoFactorChange("email", PASSWORD, email)
        assertEquals("new", stage.stage)
        assertTrue(stage.sent)
        assertEquals(hint, stage.emailHint)
        assertFalse(stage.recoveryAllowed, "a new address is proven by its own code only")

        val wrong = expectRefusal("TWO_FACTOR_CODE_INVALID") { api.confirmTwoFactorChange(if (latestCode() == "000000") "111111" else "000000") }
        assertNotNull(wrong.attemptsLeft)

        val done = api.confirmTwoFactorChange(latestCode())
        assertTrue(done.done)
        assertEquals(true, done.enabled)
        assertEquals(10, done.recoveryCodes?.size)
        recoveryCodes = done.recoveryCodes!!

        val overview = api.twoFactor()
        assertTrue(overview.enabled)
        assertEquals(hint, overview.emailHint)
        assertEquals(10, overview.recoveryCodesLeft)
    }

    @Test fun `03 now the password is only half of signing in`() = runBlocking<Unit> {
        requireServer()
        val (api, client) = newApi()
        val result = api.login(username, PASSWORD)

        assertFalse(result.success)
        val second = assertNotNull(result.twoFactor)
        assertEquals(hint, second.emailHint)
        assertEquals(6, second.codeLength)
        assertTrue(second.emailAvailable)
        assertFalse(second.codeSent, "nothing is emailed until the app asks")
        assertTrue(client.csrfToken.isNotEmpty(), "the code step needs the new session's token")

        // Not signed in: files are refused, and the status says a code is awaited.
        expectRefusal("UNAUTHORIZED") { api.list("/") }
        val status = api.status()
        assertFalse(status.authenticated)
        assertNotNull(status.twoFactor)

        // A wrong password is still a wrong password.
        expectRefusal("UNAUTHORIZED") { newApi().first.login(username, "not-the-password-1") }
    }

    @Test fun `04 the emailed code signs in`() = runBlocking<Unit> {
        requireServer()
        val (api, _) = newApi()
        api.login(username, PASSWORD)

        val sent = api.sendSignInCode()
        assertEquals(hint, sent.emailHint)
        assertEquals(300, sent.expiresIn)
        assertTrue(sent.resendIn > 0)
        // Asking again at once is refused, with how long to wait.
        val wait = expectRefusal("TWO_FACTOR_RESEND_COOLDOWN") { api.sendSignInCode() }
        assertTrue((wait.retryAfter ?: 0) > 0)

        val code = latestCode()
        val wrong = expectRefusal("TWO_FACTOR_CODE_INVALID") { api.verifySignIn(if (code == "000000") "111111" else "000000") }
        assertEquals(4, wrong.attemptsLeft)

        val result = api.verifySignIn(code)
        assertTrue(result.success)
        assertEquals(username, result.user?.username)
        assertTrue(api.status().authenticated)
        api.list("/")
        signedIn = api

        // Used once, it is gone.
        val again = newApi().first
        again.login(username, PASSWORD)
        expectRefusal("TWO_FACTOR_CODE_NOT_SENT") { again.verifySignIn(code) }
    }

    @Test fun `05 a recovery code signs in once`() = runBlocking<Unit> {
        requireServer()
        val (api, _) = newApi()
        api.login(username, PASSWORD)
        val result = api.verifySignInWithRecoveryCode(recoveryCodes[0].uppercase())
        assertTrue(result.success)
        assertEquals(9, result.recoveryCodesLeft)

        val (other, _) = newApi()
        other.login(username, PASSWORD)
        expectRefusal("TWO_FACTOR_RECOVERY_INVALID") { other.verifySignInWithRecoveryCode(recoveryCodes[0]) }
    }

    @Test fun `06 going back forgets the waiting sign-in`() = runBlocking<Unit> {
        requireServer()
        val (api, _) = newApi()
        api.login(username, PASSWORD)
        api.cancelSignIn()
        expectRefusal("TWO_FACTOR_EXPIRED") { api.sendSignInCode() }
        assertNull(api.status().twoFactor)
    }

    @Test fun `07 turning it off takes the current address's code`() = runBlocking<Unit> {
        requireServer()
        val api = signedIn ?: fail("test 04 did not sign in")
        val stage = api.startTwoFactorChange("disable", PASSWORD)
        assertEquals("current", stage.stage)
        assertTrue(stage.recoveryAllowed)
        assertTrue(stage.sent)

        val done = api.confirmTwoFactorChange(latestCode())
        assertTrue(done.done)
        assertEquals(false, done.enabled)
        assertFalse(api.twoFactor().enabled)

        val (fresh, _) = newApi()
        assertTrue(fresh.login(username, PASSWORD).success, "the password alone signs in again")
    }
}
