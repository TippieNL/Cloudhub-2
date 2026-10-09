package nl.tippie.cloudhub.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.tippie.cloudhub.net.ApiError
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.CodeSent
import nl.tippie.cloudhub.net.LoginResult
import nl.tippie.cloudhub.net.SecondStep

/**
 * What the sign-in screen is doing.
 *
 * A sealed set rather than a pile of booleans: "busy and also succeeded" and
 * "showing an error while submitting" are states the screen must animate
 * between, and they cannot be reached at all if only one of them can be true.
 */
sealed interface SignInUiState {
    /** Nothing in flight. Carries the last failure so the form can show it. */
    data class Idle(val error: SignInError? = null) : SignInUiState

    data object Submitting : SignInUiState

    /** Signed in; the screen plays its success animation before navigating. */
    data object Success : SignInUiState

    /** A failed attempt, distinct from Idle so the shake fires exactly once. */
    data class Failed(val error: SignInError) : SignInUiState

    /**
     * The password was right, and the account also wants the code texted to
     * its phone. The server has not signed this session in, and will not
     * until the code is checked.
     */
    data class Code(val step: CodeStep) : SignInUiState
}

/**
 * The code step of signing in, for an account with SMS two-step verification.
 *
 * Times are on the view model's clock, so the resend countdown can be checked
 * without waiting a minute for it.
 */
data class CodeStep(
    /** The last two digits of the number the code goes to. */
    val phoneEnding: String? = null,
    val codeLength: Int = 6,
    /** False when the server cannot text this account: a recovery code is the only way in. */
    val smsAvailable: Boolean = true,
    /** Entering one of the account's recovery codes rather than a texted code. */
    val recovery: Boolean = false,
    /** A text is being asked for. */
    val sending: Boolean = false,
    /** A code is being checked. */
    val verifying: Boolean = false,
    /** When another code may be asked for. */
    val resendAt: Long = 0,
    /** How the text is coming along: sending, sent, sent earlier. */
    val notice: String? = null,
    val error: String? = null,
    /** Counts refused codes, so the screen shakes and clears the field once for each. */
    val rejected: Int = 0,
) {
    /** Whole seconds before "Send a new code" works again; 0 once it does. */
    fun resendWait(now: Long): Int = if (resendAt <= now) 0 else ((resendAt - now + 999) / 1000).toInt()

    fun canResend(now: Long): Boolean =
        smsAvailable && !recovery && !sending && !verifying && resendWait(now) == 0

    /** What the step asks for, in a sentence. */
    val prompt: String get() = when {
        recovery -> "Enter one of the recovery codes you saved when you turned on two-step verification. Each one works once."
        phoneEnding != null -> "Enter the $codeLength-digit code we sent to your phone number ending in $phoneEnding."
        else -> "Enter the $codeLength-digit code we sent to your phone."
    }
}

/**
 * What a typed code is cleaned to before it is sent.
 *
 * A texted code is digits only -- pasted from a message it can arrive with
 * spaces or a trailing full stop -- and never longer than the code. A
 * recovery code is left to the server, which ignores case, spaces and dashes,
 * so only its length is bounded here.
 */
object CodeInput {
    fun clean(typed: String, recovery: Boolean, length: Int): String =
        if (recovery) typed.take(64) else typed.filter(Char::isDigit).take(length)

    /** Complete enough to check without the Verify button. */
    fun complete(cleaned: String, recovery: Boolean, length: Int): Boolean = !recovery && cleaned.length == length
}

/**
 * The calls the code step makes, so its rules can be driven from a test with
 * no server -- the same reason [SignInViewModel] takes its login as a function.
 */
interface SecondStepCalls {
    suspend fun send(): CodeSent
    suspend fun verify(code: String): LoginResult
    suspend fun verifyRecoveryCode(code: String): LoginResult
    suspend fun cancel()
}

/** The real thing, over the API. */
class ApiSecondStep(private val api: CloudHubApi) : SecondStepCalls {
    override suspend fun send() = api.sendSignInCode()
    override suspend fun verify(code: String) = api.verifySignIn(code)
    override suspend fun verifyRecoveryCode(code: String) = api.verifySignInWithRecoveryCode(code)
    override suspend fun cancel() { api.cancelSignIn() }
}

/**
 * Why an attempt did not go through.
 *
 * Which *field* is at fault is part of the error, so the screen can mark that
 * field rather than dropping one message under the whole form.
 */
data class SignInError(
    val message: String,
    val field: Field? = null,
) {
    enum class Field { USERNAME, PASSWORD }
}

/**
 * Whether the form can be sent, decided without a server or a screen.
 *
 * Trimming lives here too: a username pasted from a password manager
 * frequently arrives with a trailing space, and the server would reject it
 * with an unhelpful "invalid credentials".
 */
object SignInForm {

    /** The trimmed username, or the reason it cannot be sent. */
    fun validate(username: String, password: String): Result {
        val name = username.trim()
        if (name.isEmpty()) {
            return Result.Invalid(SignInError("Enter your username.", SignInError.Field.USERNAME))
        }
        // Not trimmed: a space can be a legitimate character in a password,
        // and silently removing one turns a correct password into a wrong one.
        if (password.isEmpty()) {
            return Result.Invalid(SignInError("Enter your password.", SignInError.Field.PASSWORD))
        }
        return Result.Valid(name, password)
    }

    sealed interface Result {
        data class Valid(val username: String, val password: String) : Result
        data class Invalid(val error: SignInError) : Result
    }
}

/**
 * The server address as a person should read it.
 *
 * The scheme and any trailing slash carry nothing worth the width, and the
 * percent-escapes actively get in the way: a CloudHub installed in a folder
 * with a space in its name showed as "Cloud%20File%20Hub". This line exists so
 * you can check which server you are about to hand a password to, and encoding
 * makes that harder rather than easier.
 *
 * Display only -- the stored URL is never rewritten from this.
 */
fun displayServer(url: String): String {
    val trimmed = url.trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .trimEnd('/')
    // URLDecoder is a form decoder, so it would also turn a literal + in a
    // folder name into a space -- a wrong answer about which server this is.
    // Escaping it first makes it round-trip back to itself.
    val plusSafe = trimmed.replace("+", "%2B")
    // A malformed escape is shown as typed rather than throwing: an address
    // that cannot be tidied is still worth reading.
    return runCatching { java.net.URLDecoder.decode(plusSafe, "UTF-8") }.getOrDefault(trimmed)
}

/**
 * Sign-in, kept off the screen.
 *
 * The composable renders [state] and calls [submit]; it holds no credentials
 * logic of its own, so the rules below can be exercised without a device.
 *
 * It takes the one call it needs rather than the whole API, which is what lets
 * the state machine be driven from a test with no server and no network -- the
 * transitions and the double-submission guard are the parts most likely to
 * break, and the hardest to check by hand on a phone.
 */
class SignInViewModel(
    private val login: suspend (username: String, password: String) -> LoginResult,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val secondStep: SecondStepCalls? = null,
    /** Milliseconds, for the resend countdown; monotonic, so a clock change cannot stall it. */
    val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : ViewModel() {

    constructor(api: CloudHubApi) : this(
        login = { username, password -> api.login(username, password) },
        secondStep = ApiSecondStep(api),
    )

    private val _state = MutableStateFlow<SignInUiState>(SignInUiState.Idle())
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    /**
     * Set when the sign-in that just succeeded spent a recovery code: how
     * many are left, which is worth saying before they run out. Taken once.
     */
    private var recoveryCodesLeft: Int? = null

    fun takeRecoveryNotice(): String? = recoveryCodesLeft?.let { left ->
        recoveryCodesLeft = null
        "Signed in with a recovery code; $left left. If your phone is gone, change the number in " +
            "Settings under Two-step verification."
    }

    fun submit(username: String, password: String) {
        // The guard for a double tap, a stray IME "Go" landing on the same
        // press, and the button being hit again during the request. It has to
        // be here: the screen can disable the button, but disabling it is a
        // frame late and does nothing about the keyboard action.
        if (_state.value is SignInUiState.Submitting) return
        // The code step has its own form; a password arriving during it is stale.
        if (_state.value is SignInUiState.Code) return

        when (val form = SignInForm.validate(username, password)) {
            is SignInForm.Result.Invalid -> {
                _state.value = SignInUiState.Failed(form.error)
                return
            }
            is SignInForm.Result.Valid -> {
                _state.value = SignInUiState.Submitting
                viewModelScope.launch {
                    _state.value = attempt(form.username, form.password)
                }
            }
        }
    }

    private suspend fun attempt(username: String, password: String): SignInUiState = try {
        val result = withContext(io) { login(username, password) }
        val second = result.twoFactor
        when {
            result.success -> SignInUiState.Success
            // Right password, and a code to come. An app built without the
            // code step could only have shown the server's message here.
            second != null && secondStep != null -> codeStep(second)
            second != null -> SignInUiState.Failed(SignInError(
                "This account uses two-step verification, which this version of the app cannot complete."))
            else -> SignInUiState.Failed(SignInError("Sign in failed. Check your username and password."))
        }
    } catch (e: ApiError) {
        SignInUiState.Failed(SignInError(e.message ?: "Sign in failed."))
    } catch (e: Exception) {
        SignInUiState.Failed(SignInError(e.message ?: "Could not reach the server."))
    }

    /* ---- the code step --------------------------------------------------- */

    /**
     * Carry on with a sign-in the server is still waiting on -- when the app
     * was closed on the code step and /api/auth/status says so. Only from
     * rest: a sign-in under way here already knows where it is.
     */
    fun resume(info: SecondStep) {
        if (secondStep == null) return
        val current = _state.value
        if (current is SignInUiState.Idle || current is SignInUiState.Failed) _state.value = codeStep(info)
    }

    /**
     * The step, as it opens. Texting the code is asked for here rather than
     * by the login answer, so the code is sent only to an app that can take
     * it -- and only once, unless the app comes back to a code still valid.
     */
    private fun codeStep(info: SecondStep): SignInUiState.Code {
        val step = CodeStep(
            phoneEnding = info.phoneEnding,
            codeLength = info.codeLength,
            smsAvailable = info.smsAvailable,
            recovery = !info.smsAvailable,
        )
        return when {
            !info.smsAvailable -> SignInUiState.Code(step.copy(
                notice = "This server cannot send text messages right now. Use one of your recovery codes, or ask your administrator."))
            info.codeSent -> SignInUiState.Code(step.copy(
                notice = "A code was already sent, and still works.",
                resendAt = clock() + info.resendIn * 1000L))
            else -> {
                val sending = step.copy(sending = true, notice = "Sending a code…")
                viewModelScope.launch { deliver() }
                SignInUiState.Code(sending)
            }
        }
    }

    /** "Send a new code": a fresh code, and the one before it stops working. */
    fun sendCode() {
        val step = (_state.value as? SignInUiState.Code)?.step ?: return
        if (!step.canResend(clock())) return
        _state.value = SignInUiState.Code(step.copy(sending = true, notice = "Sending a code…", error = null))
        viewModelScope.launch { deliver() }
    }

    private suspend fun deliver() {
        val calls = secondStep ?: return
        val outcome = runCatching { withContext(io) { calls.send() } }
        val step = (_state.value as? SignInUiState.Code)?.step ?: return
        val now = clock()
        outcome.fold(
            onSuccess = { sent ->
                val minutes = maxOf(1, ((sent.expiresIn ?: 300) + 30) / 60)
                _state.value = SignInUiState.Code(step.copy(
                    sending = false,
                    phoneEnding = sent.phoneEnding ?: step.phoneEnding,
                    notice = "Code sent. It works for $minutes minute${if (minutes == 1) "" else "s"}.",
                    error = null,
                    resendAt = now + sent.resendIn * 1000L,
                ))
            },
            onFailure = { e ->
                if (e is ApiError && e.isUnauthorized) return backToPassword(e)
                _state.value = SignInUiState.Code(step.copy(
                    sending = false,
                    notice = null,
                    error = if (e is ApiError) e.message else "Could not reach the server.",
                    resendAt = now + ((e as? ApiError)?.retryAfter ?: 0) * 1000L,
                ))
            },
        )
    }

    /** Check what was typed: the texted code, or a recovery code. */
    fun verify(typed: String) {
        val step = (_state.value as? SignInUiState.Code)?.step ?: return
        // The same guard as submit(): a sixth digit landing as the Verify
        // button is pressed must not send the code twice.
        if (step.verifying) return
        val calls = secondStep ?: return
        val code = CodeInput.clean(typed.trim(), step.recovery, step.codeLength)
        if (code.isBlank()) {
            _state.value = SignInUiState.Code(step.copy(
                error = if (step.recovery) "Enter a recovery code." else "Enter the code from the text message."))
            return
        }
        _state.value = SignInUiState.Code(step.copy(verifying = true, error = null))
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(io) { if (step.recovery) calls.verifyRecoveryCode(code) else calls.verify(code) }
            }
            val now = (_state.value as? SignInUiState.Code)?.step ?: return@launch
            outcome.fold(
                onSuccess = { result ->
                    if (result.success) {
                        recoveryCodesLeft = result.recoveryCodesLeft
                        _state.value = SignInUiState.Success
                    } else {
                        _state.value = SignInUiState.Code(now.copy(
                            verifying = false, error = "That did not work.", rejected = now.rejected + 1))
                    }
                },
                onFailure = { e ->
                    if (e is ApiError && e.isUnauthorized) return@launch backToPassword(e)
                    _state.value = SignInUiState.Code(now.copy(
                        verifying = false,
                        error = if (e is ApiError) e.message else "Could not reach the server.",
                        rejected = if (e is ApiError) now.rejected + 1 else now.rejected,
                    ))
                },
            )
        }
    }

    /** Between a texted code and a recovery code. Without SMS, recovery is all there is. */
    fun switchMethod() {
        val step = (_state.value as? SignInUiState.Code)?.step ?: return
        if (step.verifying || !step.smsAvailable) return
        _state.value = SignInUiState.Code(step.copy(recovery = !step.recovery, error = null))
    }

    /**
     * "Back to sign in". The server forgets the waiting sign-in too, so a
     * code already sent stops working; best effort, because the password
     * form is shown either way and the sign-in lapses on its own.
     */
    fun cancelCode() {
        if (_state.value !is SignInUiState.Code) return
        _state.value = SignInUiState.Idle()
        val calls = secondStep ?: return
        viewModelScope.launch { runCatching { withContext(io) { calls.cancel() } } }
    }

    /** The waiting sign-in lapsed or was replaced: the password has to be entered again. */
    private fun backToPassword(e: ApiError) {
        _state.value = SignInUiState.Failed(SignInError(
            e.message ?: "Your sign-in timed out. Enter your password again.", SignInError.Field.PASSWORD))
    }

    /**
     * Forget the last sign-in, for the next visit to the screen: after signing
     * out, or moving to another server. Without this the screen came back
     * still showing Success, and went straight on to the files of an account
     * that was no longer signed in.
     */
    fun reset() {
        recoveryCodesLeft = null
        _state.value = SignInUiState.Idle()
    }

    /**
     * Settle back to Idle once the screen has played the failure animation,
     * keeping the message so it stays under the field it belongs to.
     */
    fun failureShown() {
        val current = _state.value
        if (current is SignInUiState.Failed) _state.value = SignInUiState.Idle(current.error)
    }

    /** Clear the error as soon as the user starts fixing it. */
    fun editing() {
        val current = _state.value
        if (current is SignInUiState.Idle && current.error != null) {
            _state.value = SignInUiState.Idle()
        }
    }
}
