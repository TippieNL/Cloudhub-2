package nl.tippie.cloudhub.ui

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
import nl.tippie.cloudhub.net.LoginResult

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
) : ViewModel() {

    constructor(api: CloudHubApi) : this({ username, password -> api.login(username, password) })

    private val _state = MutableStateFlow<SignInUiState>(SignInUiState.Idle())
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    fun submit(username: String, password: String) {
        // The guard for a double tap, a stray IME "Go" landing on the same
        // press, and the button being hit again during the request. It has to
        // be here: the screen can disable the button, but disabling it is a
        // frame late and does nothing about the keyboard action.
        if (_state.value is SignInUiState.Submitting) return

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
        if (result.success) SignInUiState.Success
        else SignInUiState.Failed(SignInError("Sign in failed. Check your username and password."))
    } catch (e: ApiError) {
        SignInUiState.Failed(SignInError(e.message ?: "Sign in failed."))
    } catch (e: Exception) {
        SignInUiState.Failed(SignInError(e.message ?: "Could not reach the server."))
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
