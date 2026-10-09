package nl.tippie.cloudhub

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nl.tippie.cloudhub.net.ApiError
import nl.tippie.cloudhub.net.CodeSent
import nl.tippie.cloudhub.net.LoginResult
import nl.tippie.cloudhub.net.SecondStep
import nl.tippie.cloudhub.net.StageProblem
import nl.tippie.cloudhub.net.TwoFactorOverview
import nl.tippie.cloudhub.net.TwoFactorStage
import nl.tippie.cloudhub.net.User
import nl.tippie.cloudhub.ui.CodeInput
import nl.tippie.cloudhub.ui.CodeStep
import nl.tippie.cloudhub.ui.SecondStepCalls
import nl.tippie.cloudhub.ui.SignInError
import nl.tippie.cloudhub.ui.SignInUiState
import nl.tippie.cloudhub.ui.SignInViewModel
import nl.tippie.cloudhub.ui.TwoFactorAction
import nl.tippie.cloudhub.ui.TwoFactorCalls
import nl.tippie.cloudhub.ui.TwoFactorModel
import nl.tippie.cloudhub.ui.TwoFactorStep
import nl.tippie.cloudhub.ui.TwoFactorText
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * SMS two-step verification in the app: the code step of signing in, and the
 * settings screen's changes. Both are driven here through fakes of the calls
 * they make, with a clock the test moves, so the countdowns and the guards
 * against double submission can be pinned without a phone or a minute's wait.
 * ApiIntegrationTest proves the same calls against a real server.
 */

/** What is typed into a code field, cleaned the way it is sent. */
class CodeInputTest {

    @Test
    fun `a texted code keeps its digits only, and no more of them than the code has`() {
        assertEquals("123456", CodeInput.clean("123 456", recovery = false, length = 6))
        assertEquals("123456", CodeInput.clean(" 123456.", recovery = false, length = 6))
        assertEquals("123456", CodeInput.clean("1234567", recovery = false, length = 6))
        assertEquals("", CodeInput.clean("abc", recovery = false, length = 6))
    }

    @Test
    fun `a recovery code is left for the server to normalise`() {
        // The server ignores case, spaces and dashes; the app must not strip
        // the letters a recovery code is mostly made of.
        assertEquals("abcd-efgh-jkmn-pqrs", CodeInput.clean("abcd-efgh-jkmn-pqrs", recovery = true, length = 6))
        assertEquals(64, CodeInput.clean("x".repeat(100), recovery = true, length = 6).length)
    }

    @Test
    fun `only a full texted code is complete enough to send by itself`() {
        assertTrue(CodeInput.complete("123456", recovery = false, length = 6))
        assertFalse(CodeInput.complete("12345", recovery = false, length = 6))
        // A recovery code has no length to recognise it by: it waits for Verify.
        assertFalse(CodeInput.complete("abcdefghjkmnpqrs", recovery = true, length = 6))
    }
}

class CodeStepTest {

    @Test
    fun `the resend wait rounds up, and is zero once it has passed`() {
        val step = CodeStep(resendAt = 60_000)
        assertEquals(60, step.resendWait(0))
        assertEquals(1, step.resendWait(59_500))
        assertEquals(0, step.resendWait(60_000))
        assertEquals(0, step.resendWait(90_000))
    }

    @Test
    fun `a new code cannot be asked for while one is being sent, checked, or waited on`() {
        assertTrue(CodeStep().canResend(0))
        assertFalse(CodeStep(resendAt = 10_000).canResend(0))
        assertFalse(CodeStep(sending = true).canResend(0))
        assertFalse(CodeStep(verifying = true).canResend(0))
        assertFalse(CodeStep(recovery = true).canResend(0))
        assertFalse(CodeStep(smsAvailable = false).canResend(0))
    }

    @Test
    fun `the prompt names the number's last digits, or asks for a recovery code`() {
        assertTrue(CodeStep(phoneEnding = "78").prompt.contains("ending in 78"))
        assertTrue(CodeStep(phoneEnding = "78", codeLength = 6).prompt.contains("6-digit"))
        assertTrue(CodeStep(recovery = true).prompt.contains("recovery codes"))
    }
}

/** A server for the code step, scripted per test. */
private class FakeSecondStep : SecondStepCalls {
    var sends = 0
    var verified = mutableListOf<String>()
    var recoveryVerified = mutableListOf<String>()
    var cancels = 0
    var send: suspend () -> CodeSent = { CodeSent(sent = true, phoneEnding = "78", expiresIn = 300, resendIn = 60) }
    var verify: suspend (String) -> LoginResult = { LoginResult(success = true, user = User(1, "alice", "editor")) }
    var verifyRecovery: suspend (String) -> LoginResult = { LoginResult(success = true, recoveryCodesLeft = 9) }

    override suspend fun send(): CodeSent { sends++; return send.invoke() }
    override suspend fun verify(code: String): LoginResult { verified += code; return verify.invoke(code) }
    override suspend fun verifyRecoveryCode(code: String): LoginResult { recoveryVerified += code; return verifyRecovery.invoke(code) }
    override suspend fun cancel() { cancels++ }
}

class SignInCodeStepTest {

    private val dispatcher = StandardTestDispatcher()
    private var now = 1_000_000L

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val needsCode = LoginResult(success = false, csrfToken = "t", twoFactor = SecondStep(phoneEnding = "78"))

    private fun model(fake: FakeSecondStep, login: suspend (String, String) -> LoginResult = { _, _ -> needsCode }) =
        SignInViewModel(login, io = dispatcher, secondStep = fake, clock = { now })

    private fun TestScope.signedInToCode(fake: FakeSecondStep): SignInViewModel {
        val model = model(fake)
        model.submit("alice", "right-password")
        dispatcher.scheduler.advanceUntilIdle()
        return model
    }

    private fun SignInViewModel.step(): CodeStep = assertIs<SignInUiState.Code>(state.value).step

    @Test
    fun `a right password for an account with two-step verification moves to the code, and texts it`() = runTest(dispatcher) {
        val fake = FakeSecondStep()
        val model = signedInToCode(fake)

        val step = model.step()
        assertEquals(1, fake.sends, "the code is asked for once, when the step opens")
        assertEquals("78", step.phoneEnding)
        assertFalse(step.sending)
        assertEquals("Code sent. It works for 5 minutes.", step.notice)
        assertEquals(60, step.resendWait(now), "the countdown starts from the server's resendIn")
    }

    @Test
    fun `with no way to text, the step asks for a recovery code and sends nothing`() = runTest(dispatcher) {
        val fake = FakeSecondStep()
        val model = model(fake) { _, _ -> needsCode.copy(twoFactor = SecondStep(smsAvailable = false)) }
        model.submit("alice", "right-password")
        dispatcher.scheduler.advanceUntilIdle()

        val step = model.step()
        assertTrue(step.recovery)
        assertEquals(0, fake.sends)
        assertTrue(step.notice!!.contains("cannot send text messages"))
        // And there is nothing to switch to.
        model.switchMethod()
        assertTrue(model.step().recovery)
    }

    @Test
    fun `coming back to a code still valid does not send another`() = runTest(dispatcher) {
        val fake = FakeSecondStep()
        val model = model(fake)
        model.resume(SecondStep(phoneEnding = "78", codeSent = true, resendIn = 42))
        dispatcher.scheduler.advanceUntilIdle()

        val step = model.step()
        assertEquals(0, fake.sends)
        assertEquals(42, step.resendWait(now))
        assertEquals("A code was already sent, and still works.", step.notice)
    }

    @Test
    fun `resuming does nothing to a sign-in already under way`() = runTest(dispatcher) {
        val fake = FakeSecondStep()
        val model = signedInToCode(fake)
        val before = model.state.value
        model.resume(SecondStep(phoneEnding = "11", codeSent = true))
        assertEquals(before, model.state.value)
    }

    @Test
    fun `the right code signs in, sent as digits only`() = runTest(dispatcher) {
        val fake = FakeSecondStep()
        val model = signedInToCode(fake)

        model.verify(" 123 456 ")
        assertTrue(model.step().verifying)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("123456"), fake.verified)
        assertEquals(SignInUiState.Success, model.state.value)
        assertNull(model.takeRecoveryNotice(), "a texted code leaves nothing to say")
    }

    @Test
    fun `a wrong code stays on the step, says what the server said, and is cleared`() = runTest(dispatcher) {
        val fake = FakeSecondStep()
        fake.verify = { throw ApiError(422, "TWO_FACTOR_CODE_INVALID", "That code is not right. 4 attempts left.", attemptsLeft = 4) }
        val model = signedInToCode(fake)

        model.verify("000000")
        dispatcher.scheduler.advanceUntilIdle()

        val step = model.step()
        assertFalse(step.verifying)
        assertEquals("That code is not right. 4 attempts left.", step.error)
        assertEquals(1, step.rejected, "the screen shakes and empties the field once per refusal")
    }

    @Test
    fun `a sign-in that lapsed goes back to the password`() = runTest(dispatcher) {
        val fake = FakeSecondStep()
        fake.verify = { throw ApiError(401, "TWO_FACTOR_EXPIRED", "Your sign-in timed out. Enter your password again.") }
        val model = signedInToCode(fake)

        model.verify("123456")
        dispatcher.scheduler.advanceUntilIdle()

        val failed = assertIs<SignInUiState.Failed>(model.state.value)
        assertEquals("Your sign-in timed out. Enter your password again.", failed.error.message)
        assertEquals(SignInError.Field.PASSWORD, failed.error.field)
    }

    @Test
    fun `a lapsed sign-in found while sending also goes back to the password`() = runTest(dispatcher) {
        val fake = FakeSecondStep()
        fake.send = { throw ApiError(401, "TWO_FACTOR_EXPIRED", "Your sign-in timed out. Enter your password again.") }
        val model = signedInToCode(fake)
        assertIs<SignInUiState.Failed>(model.state.value)
    }

    @Test
    fun `a new code waits for the countdown`() = runTest(dispatcher) {
        val fake = FakeSecondStep()
        val model = signedInToCode(fake)

        model.sendCode()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, fake.sends, "refused during the countdown, without asking the server")

        now += 60_000
        model.sendCode()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, fake.sends)
    }

    @Test
    fun `a refused send says why and waits as long as the server asks`() = runTest(dispatcher) {
        val fake = FakeSecondStep()
        fake.send = { throw ApiError(429, "TWO_FACTOR_SMS_LIMIT", "Too many text messages were sent.", retryAfter = 600) }
        val model = signedInToCode(fake)

        val step = model.step()
        assertEquals("Too many text messages were sent.", step.error)
        assertEquals(600, step.resendWait(now))
        assertFalse(step.sending)
    }

    @Test
    fun `an unreachable server while sending leaves the step usable`() = runTest(dispatcher) {
        val fake = FakeSecondStep()
        fake.send = { throw java.io.IOException("timeout") }
        val model = signedInToCode(fake)

        val step = model.step()
        assertEquals("Could not reach the server.", step.error)
        assertTrue(step.canResend(now), "and a new code can be asked for straight away")
    }

    @Test
    fun `a second press while a code is being checked sends nothing more`() = runTest(dispatcher) {
        val fake = FakeSecondStep()
        val gate = CompletableDeferred<Unit>()
        fake.verify = { gate.await(); LoginResult(success = true) }
        val model = signedInToCode(fake)

        model.verify("123456")
        dispatcher.scheduler.advanceUntilIdle()
        model.verify("123456")
        model.verify("123456")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, fake.verified.size)

        gate.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(SignInUiState.Success, model.state.value)
    }

    @Test
    fun `nothing typed is never sent`() = runTest(dispatcher) {
        val fake = FakeSecondStep()
        val model = signedInToCode(fake)

        model.verify("   ")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(emptyList(), fake.verified)
        assertEquals("Enter the code from the text message.", model.step().error)
    }

    @Test
    fun `a recovery code signs in, and how many are left is said once`() = runTest(dispatcher) {
        val fake = FakeSecondStep()
        val model = signedInToCode(fake)

        model.switchMethod()
        assertTrue(model.step().recovery)
        model.verify("abcd-efgh-jkmn-pqrs")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("abcd-efgh-jkmn-pqrs"), fake.recoveryVerified)
        assertEquals(emptyList(), fake.verified)
        assertEquals(SignInUiState.Success, model.state.value)
        val notice = model.takeRecoveryNotice()
        assertTrue(notice!!.contains("9 left"))
        assertNull(model.takeRecoveryNotice())
    }

    @Test
    fun `back to sign in forgets the step here and on the server`() = runTest(dispatcher) {
        val fake = FakeSecondStep()
        val model = signedInToCode(fake)

        model.cancelCode()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(SignInUiState.Idle(), model.state.value)
        assertEquals(1, fake.cancels)
    }

    @Test
    fun `a password submitted during the code step is ignored`() = runTest(dispatcher) {
        var logins = 0
        val fake = FakeSecondStep()
        val model = model(fake) { _, _ -> logins++; needsCode }
        model.submit("alice", "right-password")
        dispatcher.scheduler.advanceUntilIdle()
        model.submit("alice", "right-password")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, logins)
    }

    @Test
    fun `an app without the code step says so rather than claiming a wrong password`() = runTest(dispatcher) {
        val model = SignInViewModel({ _, _ -> needsCode }, io = dispatcher)
        model.submit("alice", "right-password")
        dispatcher.scheduler.advanceUntilIdle()
        val failed = assertIs<SignInUiState.Failed>(model.state.value)
        assertTrue(failed.error.message.contains("two-step verification"))
    }

    @Test
    fun `after signing out the screen starts again rather than at Success`() = runTest(dispatcher) {
        // The view model outlives the screen: without reset() the sign-in
        // screen came back showing Success and went straight on to the files.
        val model = SignInViewModel({ _, _ -> LoginResult(success = true) }, io = dispatcher)
        model.submit("alice", "right-password")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(SignInUiState.Success, model.state.value)

        model.reset()
        assertEquals(SignInUiState.Idle(), model.state.value)
    }
}

/** The account's settings, scripted per test. */
private class FakeTwoFactor : TwoFactorCalls {
    var overview = TwoFactorOverview(available = true, schemaReady = true, smsAvailable = true)
    var started = mutableListOf<List<Any?>>()
    var confirmed = mutableListOf<String>()
    var recoveryConfirmed = mutableListOf<String>()
    var resends = 0
    var cancels = 0
    var overviews = 0
    var start: suspend () -> TwoFactorStage = { TwoFactorStage(action = "phone", stage = "new", phoneEnding = "12", sent = true, resendIn = 60) }
    var confirm: suspend (String) -> TwoFactorStage = {
        TwoFactorStage(done = true, enabled = true, phoneEnding = "12", recoveryCodes = List(10) { i -> "code-$i" }, recoveryCodesLeft = 10)
    }
    var resend: suspend () -> TwoFactorStage = { TwoFactorStage(action = "phone", stage = "new", phoneEnding = "12", sent = true, resendIn = 60) }

    override suspend fun overview(): TwoFactorOverview { overviews++; return overview }
    override suspend fun start(action: String, password: String, phone: String?, useRecoveryCode: Boolean): TwoFactorStage {
        started += listOf(action, password, phone, useRecoveryCode); return start.invoke()
    }
    override suspend fun resend(): TwoFactorStage { resends++; return resend.invoke() }
    override suspend fun confirm(code: String): TwoFactorStage { confirmed += code; return confirm.invoke(code) }
    override suspend fun confirmRecoveryCode(code: String): TwoFactorStage { recoveryConfirmed += code; return confirm.invoke(code) }
    override suspend fun cancel() { cancels++ }
}

class TwoFactorSettingsTest {

    private val dispatcher = StandardTestDispatcher()
    private var now = 5_000_000L

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun opened(fake: FakeTwoFactor): TwoFactorModel {
        val model = TwoFactorModel(fake, io = dispatcher, clock = { now })
        model.open()
        dispatcher.scheduler.advanceUntilIdle()
        return model
    }

    @Test
    fun `opening reads the settings`() = runTest(dispatcher) {
        val fake = FakeTwoFactor()
        val model = opened(fake)
        assertFalse(model.state.value.loading)
        assertEquals(fake.overview, model.state.value.overview)
        assertEquals(TwoFactorStep.Summary, model.state.value.step)
    }

    @Test
    fun `turning it on takes a number, the password and the code, and shows the recovery codes`() = runTest(dispatcher) {
        val fake = FakeTwoFactor()
        val model = opened(fake)

        model.begin(TwoFactorAction.TURN_ON)
        model.submitStart("pw-123456789012", " +31 6 12345612 ")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf<Any?>("phone", "pw-123456789012", "+31 6 12345612", false), fake.started.single())
        val code = assertIs<TwoFactorStep.Code>(model.state.value.step)
        assertEquals(60, code.resendWait(now))
        assertTrue(code.prompt.contains("ending in 12"))

        fake.overview = fake.overview.copy(enabled = true, phoneEnding = "12", recoveryCodesLeft = 10)
        model.confirm("123456")
        dispatcher.scheduler.advanceUntilIdle()

        val codes = assertIs<TwoFactorStep.Codes>(model.state.value.step)
        assertEquals(10, codes.codes.size)
        assertTrue(model.state.value.message!!.text.startsWith("Two-step verification is on"))
        assertTrue(model.state.value.overview!!.enabled, "the settings are read again afterwards")

        model.codesSaved()
        assertEquals(TwoFactorStep.Summary, model.state.value.step)
    }

    @Test
    fun `a number and a password are asked for before anything is sent`() = runTest(dispatcher) {
        val fake = FakeTwoFactor()
        val model = opened(fake)
        model.begin(TwoFactorAction.TURN_ON)

        model.submitStart("pw-123456789012", "  ")
        assertTrue(model.state.value.message!!.isError)
        model.submitStart("", "+31612345612")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.state.value.message!!.text.contains("password"))
        assertEquals(0, fake.started.size)
    }

    @Test
    fun `moving to a new number proves the current phone, then the new one`() = runTest(dispatcher) {
        val fake = FakeTwoFactor()
        fake.overview = fake.overview.copy(enabled = true, phoneEnding = "78", recoveryCodesLeft = 10)
        fake.start = { TwoFactorStage(action = "phone", stage = "current", phoneEnding = "78", recoveryAllowed = true, sent = true, resendIn = 60) }
        fake.confirm = { TwoFactorStage(done = false, action = "phone", stage = "new", phoneEnding = "12", sent = true, resendIn = 60) }
        val model = opened(fake)

        model.begin(TwoFactorAction.CHANGE_PHONE)
        model.submitStart("pw-123456789012", "+31612345612")
        dispatcher.scheduler.advanceUntilIdle()
        val current = assertIs<TwoFactorStep.Code>(model.state.value.step)
        assertTrue(current.prompt.contains("current phone number, ending in 78"))

        model.confirm("111111")
        dispatcher.scheduler.advanceUntilIdle()
        val next = assertIs<TwoFactorStep.Code>(model.state.value.step)
        assertEquals("new", next.stage.stage)
        assertFalse(next.stage.recoveryAllowed, "a new number is only ever proven by its own code")
        assertTrue(model.state.value.message!!.text.contains("new number ending in 12"))

        fake.confirm = { TwoFactorStage(done = true, enabled = true, phoneEnding = "12", recoveryCodesLeft = 10) }
        model.confirm("222222")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(TwoFactorStep.Summary, model.state.value.step)
        assertEquals("Done. Codes now go to your phone number ending in 12.", model.state.value.message!!.text)
        assertEquals(listOf("111111", "222222"), fake.confirmed)
    }

    @Test
    fun `turning it off with a lost phone takes a recovery code and texts nothing`() = runTest(dispatcher) {
        val fake = FakeTwoFactor()
        fake.overview = fake.overview.copy(enabled = true, phoneEnding = "78", recoveryCodesLeft = 3)
        fake.start = { TwoFactorStage(action = "disable", stage = "current", phoneEnding = "78", recoveryAllowed = true, sent = false) }
        fake.confirm = { TwoFactorStage(done = true, enabled = false) }
        val model = opened(fake)

        model.begin(TwoFactorAction.TURN_OFF)
        model.submitStart("pw-123456789012", "", useRecoveryCode = true)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf<Any?>("disable", "pw-123456789012", null, true), fake.started.single())
        assertTrue(assertIs<TwoFactorStep.Code>(model.state.value.step).recovery)

        model.confirm("abcd-efgh-jkmn-pqrs")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("abcd-efgh-jkmn-pqrs"), fake.recoveryConfirmed)
        assertEquals("Two-step verification is off. Signing in takes your password only.", model.state.value.message!!.text)
    }

    @Test
    fun `a wrong code keeps the step and says why`() = runTest(dispatcher) {
        val fake = FakeTwoFactor()
        fake.confirm = { throw ApiError(422, "TWO_FACTOR_CODE_INVALID", "That code is not right. 4 attempts left.") }
        val model = opened(fake)
        model.begin(TwoFactorAction.TURN_ON)
        model.submitStart("pw-123456789012", "+31612345612")
        dispatcher.scheduler.advanceUntilIdle()

        model.confirm("000000")
        dispatcher.scheduler.advanceUntilIdle()
        val step = assertIs<TwoFactorStep.Code>(model.state.value.step)
        assertEquals(1, step.rejected)
        assertEquals("That code is not right. 4 attempts left.", model.state.value.message!!.text)
    }

    @Test
    fun `a change that timed out on the server goes back to the settings`() = runTest(dispatcher) {
        val fake = FakeTwoFactor()
        fake.confirm = { throw ApiError(409, "TWO_FACTOR_NO_PENDING_CHANGE", "That change has timed out or was replaced. Start again.") }
        val model = opened(fake)
        model.begin(TwoFactorAction.TURN_ON)
        model.submitStart("pw-123456789012", "+31612345612")
        dispatcher.scheduler.advanceUntilIdle()

        model.confirm("123456")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(TwoFactorStep.Summary, model.state.value.step)
        assertTrue(model.state.value.message!!.isError)
    }

    @Test
    fun `a text that could not be sent still opens the code step, with the reason and a wait`() = runTest(dispatcher) {
        val fake = FakeTwoFactor()
        fake.start = {
            TwoFactorStage(action = "phone", stage = "new", phoneEnding = "12", sent = false,
                error = StageProblem("SMS_UNAVAILABLE", "The text message could not be sent just now.", 60))
        }
        val model = opened(fake)
        model.begin(TwoFactorAction.TURN_ON)
        model.submitStart("pw-123456789012", "+31612345612")
        dispatcher.scheduler.advanceUntilIdle()

        val step = assertIs<TwoFactorStep.Code>(model.state.value.step)
        assertEquals(60, step.resendWait(now))
        assertEquals("The text message could not be sent just now.", model.state.value.message!!.text)
    }

    @Test
    fun `a new code waits for the countdown, then says where it went`() = runTest(dispatcher) {
        val fake = FakeTwoFactor()
        val model = opened(fake)
        model.begin(TwoFactorAction.TURN_ON)
        model.submitStart("pw-123456789012", "+31612345612")
        dispatcher.scheduler.advanceUntilIdle()

        model.resend()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, fake.resends)

        now += 60_000
        model.resend()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, fake.resends)
        assertTrue(model.state.value.message!!.text.contains("ending in 12"))
        assertEquals(60, assertIs<TwoFactorStep.Code>(model.state.value.step).resendWait(now))
    }

    @Test
    fun `cancelling tells the server only when it has a change under way`() = runTest(dispatcher) {
        val fake = FakeTwoFactor()
        val model = opened(fake)

        model.begin(TwoFactorAction.TURN_ON)
        model.cancel()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, fake.cancels, "nothing was started on the server yet")

        model.begin(TwoFactorAction.TURN_ON)
        model.submitStart("pw-123456789012", "+31612345612")
        dispatcher.scheduler.advanceUntilIdle()
        model.cancel()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, fake.cancels)
        assertEquals(TwoFactorStep.Summary, model.state.value.step)
    }
}

class TwoFactorTextTest {

    @Test
    fun `the summary says what signing in will ask for`() {
        val on = TwoFactorOverview(available = true, smsAvailable = true, enabled = true, phoneEnding = "78", recoveryCodesLeft = 10)
        assertEquals("Signing in asks for a code texted to your phone number ending in 78. 10 recovery codes left.", TwoFactorText.summary(on))
        assertTrue(TwoFactorText.summary(on.copy(recoveryCodesLeft = 1)).contains("1 recovery code left. Create new ones soon."))
        assertTrue(TwoFactorText.summary(on.copy(smsAvailable = false)).contains("sign in with a recovery code"))
    }

    @Test
    fun `when it cannot be turned on, the summary says why`() {
        assertTrue(TwoFactorText.summary(TwoFactorOverview(schemaReady = false)).contains("migrate.php"))
        assertTrue(TwoFactorText.summary(TwoFactorOverview(schemaReady = true, smsAvailable = false)).contains("no text-message service"))
        assertTrue(TwoFactorText.summary(TwoFactorOverview(available = true, schemaReady = true, smsAvailable = true)).startsWith("Off."))
    }

    @Test
    fun `the saved note names the account and the server, and carries every code`() {
        val note = TwoFactorText.codesNote(listOf("aaaa-bbbb", "cccc-dddd"), "alice", "files.example.com")
        assertTrue(note.contains("alice (files.example.com)"))
        assertTrue(note.contains("aaaa-bbbb\ncccc-dddd\n"))
    }
}

/** The details a two-step refusal carries, read off the server's envelope. */
class ApiErrorDetailsTest {

    private fun response(code: Int, retryAfter: String? = null): Response = Response.Builder()
        .request(Request.Builder().url("https://example.com/?route=%2Fapi").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("x")
        .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
        .build()

    @Test
    fun `attempts left and the wait come from the error's details`() {
        val e = ApiError.from(response(422),
            """{"error":{"code":"TWO_FACTOR_CODE_INVALID","message":"Wrong.","details":{"attemptsLeft":3}},"requestId":"r1"}""")
        assertEquals("TWO_FACTOR_CODE_INVALID", e.code)
        assertEquals(3, e.attemptsLeft)
        assertNull(e.retryAfter)

        val wait = ApiError.from(response(429, retryAfter = "45"),
            """{"error":{"code":"TWO_FACTOR_RESEND_COOLDOWN","message":"Wait.","details":{"retryAfter":45}}}""")
        assertEquals(45, wait.retryAfter)
    }

    @Test
    fun `a Retry-After header alone is enough`() {
        val e = ApiError.from(response(503, retryAfter = "60"), """{"error":{"code":"SMS_UNAVAILABLE","message":"Down."}}""")
        assertEquals(60, e.retryAfter)
    }

    @Test
    fun `odd details never break reading the error`() {
        val e = ApiError.from(response(422), """{"error":{"code":"X","message":"M","details":{"attemptsLeft":"many","retryAfter":[1]}}}""")
        assertEquals("M", e.message)
        assertNull(e.attemptsLeft)
        assertNull(e.retryAfter)
    }
}
