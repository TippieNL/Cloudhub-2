package nl.tippie.cloudhub

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nl.tippie.cloudhub.net.ApiError
import nl.tippie.cloudhub.net.LoginResult
import nl.tippie.cloudhub.ui.Motion
import nl.tippie.cloudhub.ui.SignInError
import nl.tippie.cloudhub.ui.SignInForm
import nl.tippie.cloudhub.ui.SignInUiState
import nl.tippie.cloudhub.ui.SignInViewModel
import nl.tippie.cloudhub.ui.displayServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What the sign-in form will and will not send.
 *
 * Pure, so it runs on every build with no server and no device.
 */
class SignInFormTest {

    @Test
    fun `a blank username is refused, and says which field`() {
        val result = SignInForm.validate("", "hunter2")
        val invalid = assertIs<SignInForm.Result.Invalid>(result)
        assertEquals(SignInError.Field.USERNAME, invalid.error.field)
    }

    @Test
    fun `whitespace is not a username`() {
        assertIs<SignInForm.Result.Invalid>(SignInForm.validate("   ", "hunter2"))
        assertIs<SignInForm.Result.Invalid>(SignInForm.validate("\t\n", "hunter2"))
    }

    @Test
    fun `a blank password is refused, and says which field`() {
        val result = SignInForm.validate("admin", "")
        val invalid = assertIs<SignInForm.Result.Invalid>(result)
        assertEquals(SignInError.Field.PASSWORD, invalid.error.field)
    }

    @Test
    fun `the username is trimmed`() {
        // A password manager routinely pastes a trailing space, and the server
        // would answer with an unhelpful "invalid credentials".
        val valid = assertIs<SignInForm.Result.Valid>(SignInForm.validate("  admin ", "hunter2"))
        assertEquals("admin", valid.username)
    }

    @Test
    fun `the password is left exactly as typed`() {
        // A space is a legitimate password character; trimming one turns a
        // correct password into a wrong one.
        val valid = assertIs<SignInForm.Result.Valid>(SignInForm.validate("admin", " hunter2 "))
        assertEquals(" hunter2 ", valid.password)
    }
}

/**
 * The state machine behind the button.
 *
 * These are the transitions the screen animates between, and the guard that
 * stops two taps becoming two sign-in requests -- both awkward to provoke by
 * hand on a phone and cheap to pin here.
 */
class SignInStateTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun model(login: suspend (String, String) -> LoginResult) =
        SignInViewModel(login, io = dispatcher)

    @Test
    fun `a good password goes Idle then Submitting then Success`() = runTest(dispatcher) {
        val model = model { _, _ -> LoginResult(success = true) }

        assertIs<SignInUiState.Idle>(model.state.value)
        model.submit("admin", "hunter2")
        assertEquals(SignInUiState.Submitting, model.state.value)

        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(SignInUiState.Success, model.state.value)
    }

    @Test
    fun `a rejected password ends in Failed`() = runTest(dispatcher) {
        val model = model { _, _ -> LoginResult(success = false) }

        model.submit("admin", "wrong")
        dispatcher.scheduler.advanceUntilIdle()

        assertIs<SignInUiState.Failed>(model.state.value)
    }

    @Test
    fun `the server's own message is shown rather than something vaguer`() = runTest(dispatcher) {
        val model = model { _, _ -> throw ApiError(429, "TOO_MANY_REQUESTS", "Too many attempts. Try again later.") }

        model.submit("admin", "hunter2")
        dispatcher.scheduler.advanceUntilIdle()

        val failed = assertIs<SignInUiState.Failed>(model.state.value)
        assertEquals("Too many attempts. Try again later.", failed.error.message)
    }

    @Test
    fun `an unreachable server fails rather than hanging`() = runTest(dispatcher) {
        val model = model { _, _ -> throw java.io.IOException("Unable to resolve host") }

        model.submit("admin", "hunter2")
        dispatcher.scheduler.advanceUntilIdle()

        assertIs<SignInUiState.Failed>(model.state.value)
    }

    @Test
    fun `a second tap during a request is ignored`() = runTest(dispatcher) {
        // The guard that matters: without it an impatient double tap sends two
        // sign-in requests, and the second lands on a rotated session.
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val model = model { _, _ -> calls++; gate.await(); LoginResult(success = true) }

        model.submit("admin", "hunter2")
        dispatcher.scheduler.advanceUntilIdle()

        model.submit("admin", "hunter2")
        model.submit("admin", "hunter2")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, calls)

        gate.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(SignInUiState.Success, model.state.value)
    }

    @Test
    fun `an empty form never reaches the server`() = runTest(dispatcher) {
        var calls = 0
        val model = model { _, _ -> calls++; LoginResult(success = true) }

        model.submit("", "")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, calls)
        assertIs<SignInUiState.Failed>(model.state.value)
    }

    @Test
    fun `a failure settles to Idle but keeps its message`() = runTest(dispatcher) {
        // Failed is a moment, not a resting place: it fires the shake once and
        // then settles, or the shake would replay on every recomposition.
        val model = model { _, _ -> LoginResult(success = false) }

        model.submit("admin", "wrong")
        dispatcher.scheduler.advanceUntilIdle()
        model.failureShown()

        val idle = assertIs<SignInUiState.Idle>(model.state.value)
        assertTrue(idle.error != null)
    }

    @Test
    fun `typing clears the error`() = runTest(dispatcher) {
        val model = model { _, _ -> LoginResult(success = false) }

        model.submit("admin", "wrong")
        dispatcher.scheduler.advanceUntilIdle()
        model.failureShown()
        model.editing()

        assertEquals(null, assertIs<SignInUiState.Idle>(model.state.value).error)
    }
}

/**
 * Accessibility > Remove animations.
 *
 * The platform reports it as an animator duration scale of exactly zero; every
 * other value is a real animation speed, not a request for stillness.
 */
class ReduceMotionTest {

    @Test
    fun `a zero animator scale means reduce motion`() {
        assertTrue(Motion.reduced(0f))
    }

    @Test
    fun `the normal scale does not`() {
        assertFalse(Motion.reduced(1f))
    }

    @Test
    fun `a slowed or hurried scale is still not a request for stillness`() {
        assertFalse(Motion.reduced(0.5f))
        assertFalse(Motion.reduced(2f))
        assertFalse(Motion.reduced(10f))
    }
}

/**
 * The server address, as shown under the title.
 *
 * It is the one line on the sign-in screen carrying information -- which server
 * is about to receive a password -- so it has to be readable. A CloudHub in a
 * folder with a space in its name was rendering as "Cloud%20File%20Hub".
 */
class DisplayServerTest {

    @Test
    fun `percent escapes are decoded`() {
        assertEquals(
            "100.90.78.46:8000/Cloud File Hub",
            displayServer("http://100.90.78.46:8000/Cloud%20File%20Hub"),
        )
    }

    @Test
    fun `the scheme is dropped`() {
        assertEquals("files.example.com", displayServer("https://files.example.com"))
        assertEquals("files.example.com", displayServer("http://files.example.com"))
    }

    @Test
    fun `a trailing slash goes`() {
        // The app stores the address with the slash the front controller needs;
        // showing it adds nothing.
        assertEquals("files.example.com", displayServer("https://files.example.com/"))
        assertEquals("example.com/hub", displayServer("https://example.com/hub/"))
    }

    @Test
    fun `an already-clean address is left alone`() {
        assertEquals("files.example.com", displayServer("files.example.com"))
        assertEquals("192.168.1.4:8080", displayServer("192.168.1.4:8080"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("files.example.com", displayServer("  https://files.example.com/  "))
    }

    @Test
    fun `a plus in a folder name stays a plus`() {
        // URLDecoder is a *form* decoder and would read + as a space, which is
        // the wrong answer about which server this is.
        assertEquals("example.com/C++ notes", displayServer("https://example.com/C++%20notes"))
        assertEquals("example.com/a+b", displayServer("https://example.com/a+b"))
    }

    @Test
    fun `a malformed escape is shown as typed rather than throwing`() {
        // A lone % is not a valid escape. An address that cannot be tidied is
        // still worth reading, and a crash on the sign-in screen is not a fix.
        assertEquals("example.com/100%", displayServer("https://example.com/100%"))
        assertEquals("example.com/a%zz", displayServer("https://example.com/a%zz"))
    }
}
