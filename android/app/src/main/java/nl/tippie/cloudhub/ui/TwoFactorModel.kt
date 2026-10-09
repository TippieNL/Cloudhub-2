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
import nl.tippie.cloudhub.net.TwoFactorOverview
import nl.tippie.cloudhub.net.TwoFactorStage

/**
 * The account's own two-step verification, as Settings shows it.
 *
 * Every change is the server's two calls -- start (the current password, and
 * for an address the address) and confirm (the emailed code) -- so the screen
 * is a small state machine over [TwoFactorUi.step]. Kept off the screen, as
 * the sign-in rules are, so it can be driven from a test with no server.
 */

/** What a change does. Turning it on and moving it are the same call on the server. */
enum class TwoFactorAction(val wire: String) {
    TURN_ON("email"),
    CHANGE_EMAIL("email"),
    NEW_RECOVERY_CODES("recovery"),
    TURN_OFF("disable"),
}

sealed interface TwoFactorStep {
    /** The settings as they are, and what can be changed. */
    data object Summary : TwoFactorStep

    /** Asking for the password -- and for an address, the address. */
    data class Start(val action: TwoFactorAction) : TwoFactorStep

    /** Waiting for the code the server emailed, or for a recovery code. */
    data class Code(
        val action: TwoFactorAction,
        val stage: TwoFactorStage,
        val recovery: Boolean = false,
        val resendAt: Long = 0,
        /** Counts refused codes, so the screen clears the field once for each. */
        val rejected: Int = 0,
        /** The new address as it was typed, shown in full where a typo is easiest to spot. */
        val address: String? = null,
    ) : TwoFactorStep {
        fun resendWait(now: Long): Int = if (resendAt <= now) 0 else ((resendAt - now + 999) / 1000).toInt()

        val prompt: String get() = when {
            recovery -> "Enter one of your recovery codes in place of a code from your current email address."
            stage.stage == "current" -> "Enter the code we sent to your current address, ${stage.emailHint ?: "your email"}."
            else -> "Enter the code we sent to ${address ?: stage.emailHint ?: "the new address"}. Not there? Check your spam folder, or the address."
        }
    }

    /** New recovery codes, shown once: the only time the server ever hands them out. */
    data class Codes(val codes: List<String>) : TwoFactorStep
}

data class TwoFactorMessage(val text: String, val isError: Boolean)

data class TwoFactorUi(
    val loading: Boolean = true,
    val overview: TwoFactorOverview? = null,
    val step: TwoFactorStep = TwoFactorStep.Summary,
    /** A request is in flight; buttons wait for it. */
    val busy: Boolean = false,
    val message: TwoFactorMessage? = null,
)

/** The calls the screen makes, so the rules can be tested without a server. */
interface TwoFactorCalls {
    suspend fun overview(): TwoFactorOverview
    suspend fun start(action: String, password: String, email: String?, useRecoveryCode: Boolean): TwoFactorStage
    suspend fun resend(): TwoFactorStage
    suspend fun confirm(code: String): TwoFactorStage
    suspend fun confirmRecoveryCode(code: String): TwoFactorStage
    suspend fun cancel()
}

class ApiTwoFactorCalls(private val api: CloudHubApi) : TwoFactorCalls {
    override suspend fun overview() = api.twoFactor()
    override suspend fun start(action: String, password: String, email: String?, useRecoveryCode: Boolean) =
        api.startTwoFactorChange(action, password, email, useRecoveryCode)
    override suspend fun resend() = api.resendTwoFactorCode()
    override suspend fun confirm(code: String) = api.confirmTwoFactorChange(code)
    override suspend fun confirmRecoveryCode(code: String) = api.confirmTwoFactorChangeWithRecoveryCode(code)
    override suspend fun cancel() { api.cancelTwoFactorChange() }
}

/** Words for the screen, decided without one. */
object TwoFactorText {

    /** The line under the On/Off badge. */
    fun summary(o: TwoFactorOverview): String = when {
        o.enabled -> buildString {
            val left = o.recoveryCodesLeft
            if (o.emailHint != null) append("Signing in asks for a code sent to ${o.emailHint}. ")
            else append("Signing in asks for a code, but there is no email address to send it to yet (codes used to come by text message): add one. ")
            append("$left recovery code${if (left == 1) "" else "s"} left.")
            if (!o.emailAvailable) append(" This server cannot send email right now: sign in with a recovery code until it can.")
            else if (left <= 2) append(" Create new ones soon.")
        }
        !o.schemaReady -> "Not available yet: the server's database needs updating (php database/migrate.php)."
        !o.emailAvailable -> "Not available: no mail server is set up on this server. An administrator can configure one."
        else -> "Off. Turn it on to be asked, after your password, for a code sent to your email."
    }

    /** What the start form explains, per change. */
    fun startIntro(action: TwoFactorAction, o: TwoFactorOverview?): String {
        // On from text-message days, with no address: a recovery code answers for it.
        val current = o?.emailHint?.let { "We will email a code to $it" }
            ?: "There is no email address for codes yet, so use a recovery code below"
        return when (action) {
            TwoFactorAction.TURN_ON ->
                "Enter the email address to send codes to, and your password. We will email a code to that address to make sure it is yours."
            TwoFactorAction.CHANGE_EMAIL ->
                "Enter the new email address and your password. We will email a code to the new address, and first to your current one unless you have just used it."
            TwoFactorAction.TURN_OFF ->
                "Enter your password. $current to confirm it is you."
            TwoFactorAction.NEW_RECOVERY_CODES ->
                "Enter your password. $current. Your current recovery codes then stop working."
        }
    }

    /** The recovery codes as a note to keep. */
    fun codesNote(codes: List<String>, username: String?, server: String): String = buildString {
        append("Recovery codes for ${username ?: "your account"} ($server)\n")
        append("Each signs you in once in place of an emailed code. Keep them somewhere safe.\n\n")
        codes.forEach { append(it).append('\n') }
    }
}

class TwoFactorModel(
    private val calls: TwoFactorCalls,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Milliseconds, for the resend countdown. */
    val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : ViewModel() {

    constructor(api: CloudHubApi) : this(ApiTwoFactorCalls(api))

    private val _state = MutableStateFlow(TwoFactorUi())
    val state: StateFlow<TwoFactorUi> = _state.asStateFlow()

    private fun update(change: (TwoFactorUi) -> TwoFactorUi) { _state.value = change(_state.value) }

    /** Arriving on the screen: whatever was half done last time is gone, so start from the settings. */
    fun open() {
        _state.value = TwoFactorUi(loading = true)
        load()
    }

    private fun load(message: TwoFactorMessage? = null) {
        viewModelScope.launch {
            val outcome = runCatching { withContext(io) { calls.overview() } }
            update { ui ->
                outcome.fold(
                    onSuccess = { ui.copy(loading = false, overview = it, message = message ?: ui.message) },
                    onFailure = { e -> ui.copy(loading = false, message = TwoFactorMessage(describe(e), true)) },
                )
            }
        }
    }

    fun begin(action: TwoFactorAction) {
        val ui = _state.value
        if (ui.busy || ui.step != TwoFactorStep.Summary) return
        update { it.copy(step = TwoFactorStep.Start(action), message = null) }
    }

    /**
     * The password, and for an address the address. With [useRecoveryCode]
     * the current address will be answered with a recovery code, and nothing
     * is sent to it -- the way through for someone who cannot get into their
     * mailbox. The address is checked by the server; here only that one was
     * typed at all.
     */
    fun submitStart(password: String, email: String, useRecoveryCode: Boolean = false) {
        val ui = _state.value
        val start = ui.step as? TwoFactorStep.Start ?: return
        if (ui.busy) return
        val wantsEmail = start.action.wire == "email"
        val address = email.trim()
        when {
            wantsEmail && !address.contains('@') ->
                return update { it.copy(message = TwoFactorMessage("Enter the email address to send codes to.", true)) }
            password.isEmpty() ->
                return update { it.copy(message = TwoFactorMessage("Enter your current password.", true)) }
        }
        update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(io) {
                    calls.start(start.action.wire, password, if (wantsEmail) address else null, useRecoveryCode)
                }
            }
            val now = clock()
            update { current ->
                if (current.step != start) return@update current.copy(busy = false)
                outcome.fold(
                    onSuccess = { stage ->
                        current.copy(
                            busy = false,
                            step = TwoFactorStep.Code(
                                action = start.action,
                                stage = stage,
                                recovery = useRecoveryCode && stage.recoveryAllowed,
                                resendAt = now + 1000L * (if (stage.sent) stage.resendIn else stage.error?.retryAfter ?: 0),
                                address = if (wantsEmail) address else null,
                            ),
                            message = stage.error?.let { TwoFactorMessage(it.message, true) },
                        )
                    },
                    onFailure = { e -> current.copy(busy = false, message = TwoFactorMessage(describe(e), true)) },
                )
            }
        }
    }

    fun resend() {
        val ui = _state.value
        val code = ui.step as? TwoFactorStep.Code ?: return
        if (ui.busy || code.recovery || code.resendWait(clock()) > 0) return
        update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val outcome = runCatching { withContext(io) { calls.resend() } }
            val now = clock()
            update { current ->
                val step = current.step as? TwoFactorStep.Code ?: return@update current.copy(busy = false)
                outcome.fold(
                    onSuccess = { stage ->
                        current.copy(
                            busy = false,
                            step = step.copy(stage = stage, resendAt = now + stage.resendIn * 1000L),
                            message = TwoFactorMessage("A new code is on its way to ${stage.emailHint ?: "your email"}.", false),
                        )
                    },
                    onFailure = { e ->
                        if (e is ApiError && e.code == NO_PENDING_CHANGE) {
                            current.copy(busy = false, step = TwoFactorStep.Summary, message = TwoFactorMessage(describe(e), true))
                        } else {
                            current.copy(
                                busy = false,
                                step = step.copy(resendAt = now + 1000L * ((e as? ApiError)?.retryAfter ?: 0)),
                                message = TwoFactorMessage(describe(e), true),
                            )
                        }
                    },
                )
            }
        }
    }

    fun confirm(typed: String) {
        val ui = _state.value
        val step = ui.step as? TwoFactorStep.Code ?: return
        if (ui.busy) return
        val value = CodeInput.clean(typed.trim(), step.recovery, step.stage.codeLength)
        if (value.isBlank()) {
            return update { it.copy(message = TwoFactorMessage(
                if (step.recovery) "Enter a recovery code." else "Enter the code from the email.", true)) }
        }
        val wasOn = ui.overview?.enabled == true
        update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(io) { if (step.recovery) calls.confirmRecoveryCode(value) else calls.confirm(value) }
            }
            val now = clock()
            val answer = outcome.getOrNull()
            update { current ->
                if (current.step !is TwoFactorStep.Code) return@update current.copy(busy = false)
                outcome.fold(
                    onSuccess = { stage ->
                        when {
                            // The current address is proven; now the new address's own code.
                            !stage.done -> current.copy(
                                busy = false,
                                step = TwoFactorStep.Code(
                                    action = step.action,
                                    stage = stage,
                                    resendAt = now + 1000L * (if (stage.sent) stage.resendIn else stage.error?.retryAfter ?: 0),
                                    address = step.address,
                                ),
                                message = stage.error?.let { TwoFactorMessage(it.message, true) }
                                    ?: TwoFactorMessage("Thanks. Now enter the code we sent to your new address.", false),
                            )
                            stage.recoveryCodes != null -> current.copy(
                                busy = false,
                                step = TwoFactorStep.Codes(stage.recoveryCodes),
                                message = TwoFactorMessage(
                                    if (wasOn) "New recovery codes are ready. The old ones no longer work."
                                    else "Two-step verification is on. Signing in will now ask for a code sent to your email.",
                                    false,
                                ),
                            )
                            else -> current.copy(
                                busy = false,
                                step = TwoFactorStep.Summary,
                                message = TwoFactorMessage(
                                    if (stage.enabled == true) "Done. Codes now go to ${stage.emailHint ?: "your new address"}."
                                    else "Two-step verification is off. Signing in takes your password only.",
                                    false,
                                ),
                            )
                        }
                    },
                    onFailure = { e ->
                        if (e is ApiError && e.code == NO_PENDING_CHANGE) {
                            current.copy(busy = false, step = TwoFactorStep.Summary, message = TwoFactorMessage(describe(e), true))
                        } else {
                            current.copy(
                                busy = false,
                                step = step.copy(rejected = step.rejected + if (e is ApiError) 1 else 0),
                                message = TwoFactorMessage(describe(e), true),
                            )
                        }
                    },
                )
            }
            if (answer?.done == true) load(_state.value.message)
        }
    }

    /** Between the emailed code and a recovery code, where the server allows one. */
    fun switchMethod() {
        val ui = _state.value
        val step = ui.step as? TwoFactorStep.Code ?: return
        if (ui.busy || !step.stage.recoveryAllowed) return
        update { it.copy(step = step.copy(recovery = !step.recovery), message = null) }
    }

    /**
     * Leave a change half done. The server forgets it too, so a code already
     * sent stops working -- best effort, since it lapses on its own as well.
     */
    fun cancel() {
        val step = _state.value.step
        if (step is TwoFactorStep.Code) viewModelScope.launch { runCatching { withContext(io) { calls.cancel() } } }
        if (step is TwoFactorStep.Start || step is TwoFactorStep.Code) {
            update { it.copy(step = TwoFactorStep.Summary, busy = false, message = null) }
        }
    }

    /** The recovery codes were kept; they are not shown again. */
    fun codesSaved() {
        if (_state.value.step is TwoFactorStep.Codes) update { it.copy(step = TwoFactorStep.Summary, message = null) }
    }

    private fun describe(e: Throwable): String = when (e) {
        is ApiError -> e.message ?: "That did not work."
        else -> "Could not reach the server."
    }

    private companion object {
        const val NO_PENDING_CHANGE = "TWO_FACTOR_NO_PENDING_CHANGE"
    }
}
