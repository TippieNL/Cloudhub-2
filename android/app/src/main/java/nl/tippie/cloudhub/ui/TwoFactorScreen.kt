package nl.tippie.cloudhub.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.SmsFailed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import nl.tippie.cloudhub.net.TwoFactorOverview

/**
 * Settings > Two-step verification: on or off, the number codes go to, and
 * the recovery codes -- the same choices as the web app's Security dialog,
 * over the same calls.
 *
 * Each change asks for the current password here and for a texted code from
 * the server, so a phone left unlocked is not enough to turn it off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TwoFactorScreen(
    model: TwoFactorModel,
    username: String?,
    server: String,
    onBack: () -> Unit,
) {
    val ui by model.state.collectAsState()
    val context = LocalContext.current
    var confirmLeave by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { model.open() }

    /*
     * Back steps out of a change before it leaves the screen: a code already
     * sent stops working, and recovery codes on show are not walked away
     * from without a word -- they are never shown again.
     */
    fun leave() {
        when (ui.step) {
            is TwoFactorStep.Codes -> confirmLeave = true
            is TwoFactorStep.Start, is TwoFactorStep.Code -> model.cancel()
            TwoFactorStep.Summary -> onBack()
        }
    }
    BackHandler(onBack = ::leave)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Two-step verification", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = ::leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            StatusCard(ui)

            ui.message?.let { MessageBanner(it) }

            when (val step = ui.step) {
                TwoFactorStep.Summary -> ui.overview?.let { Actions(it, ui.busy, model::begin) }
                is TwoFactorStep.Start -> StartForm(step, ui, model)
                is TwoFactorStep.Code -> CodeStepForm(step, ui.busy, model)
                is TwoFactorStep.Codes -> RecoveryCodes(
                    codes = step.codes,
                    onCopy = { copyCodes(context, TwoFactorText.codesNote(step.codes, username, server)) },
                    onShare = { shareCodes(context, TwoFactorText.codesNote(step.codes, username, server)) },
                    onDone = model::codesSaved,
                )
            }

            Text(
                "Codes come by text message. That protects your account from a stolen password, but not from " +
                    "someone who takes over your phone number, so keep your recovery codes somewhere safe.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
            )
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave without saving?") },
            text = { Text("Your new recovery codes will not be shown again. You can create new ones later.") },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; model.codesSaved(); onBack() }) { Text("Leave") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Stay") } },
        )
    }
}

/** On or off, and what that means for signing in. */
@Composable
private fun StatusCard(ui: TwoFactorUi) {
    val o = ui.overview
    SettingsGroup("Status") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Code by text message", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        ui.loading && o == null -> "Loading…"
                        o == null -> "Could not load the current setting."
                        else -> TwoFactorText.summary(o)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (o != null) {
                Spacer(Modifier.width(12.dp))
                val on = o.enabled
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Text(
                        if (on) "On" else "Off",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBanner(message: TwoFactorMessage) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (message.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            message.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (message.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** What can be changed from here, given what the account and the server can do. */
@Composable
private fun Actions(o: TwoFactorOverview, busy: Boolean, onBegin: (TwoFactorAction) -> Unit) {
    if (!o.enabled && !o.available) return
    if (!o.enabled) {
        SettingsGroup("Change") {
            SettingsRow(
                icon = Icons.Default.Sms,
                title = "Turn on",
                supporting = "We text a code to your phone to check the number",
                enabled = !busy,
                onClick = { onBegin(TwoFactorAction.TURN_ON) },
                trailing = { Chevron() },
            )
        }
        return
    }
    SettingsGroup("Change") {
        SettingsRow(
            icon = Icons.Default.PhoneAndroid,
            title = "Change number",
            supporting = if (o.smsAvailable) "Codes go to a different phone" else "Needs text messages, which this server cannot send now",
            enabled = !busy && o.smsAvailable,
            onClick = { onBegin(TwoFactorAction.CHANGE_PHONE) },
            trailing = { Chevron() },
        )
        SettingsDivider()
        SettingsRow(
            icon = Icons.Default.Key,
            title = "New recovery codes",
            supporting = "The ones you have now stop working",
            enabled = !busy,
            onClick = { onBegin(TwoFactorAction.NEW_RECOVERY_CODES) },
            trailing = { Chevron() },
        )
        SettingsDivider()
        SettingsRow(
            icon = Icons.Default.SmsFailed,
            title = "Turn off",
            supporting = "Signing in takes your password only",
            tone = RowTone.DANGER,
            enabled = !busy,
            onClick = { onBegin(TwoFactorAction.TURN_OFF) },
        )
    }
}

/** The password, and for a number the number. */
@Composable
private fun StartForm(step: TwoFactorStep.Start, ui: TwoFactorUi, model: TwoFactorModel) {
    var phone by remember(step) { mutableStateOf("") }
    var password by remember(step) { mutableStateOf("") }
    val wantsPhone = step.action.wire == "phone"
    val first = remember { FocusRequester() }
    LaunchedEffect(step) { runCatching { first.requestFocus() } }

    StepCard {
        Text(TwoFactorText.startIntro(step.action, ui.overview), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(14.dp))
        if (wantsPhone) {
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it.take(40) },
                label = { Text("Mobile number, with country code") },
                placeholder = { Text("+31 6 12345678") },
                singleLine = true,
                enabled = !ui.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().focusRequester(first),
            )
            Spacer(Modifier.height(10.dp))
        }
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Current password") },
            singleLine = true,
            enabled = !ui.busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { model.submitStart(password, phone) }),
            modifier = Modifier.fillMaxWidth().then(if (wantsPhone) Modifier else Modifier.focusRequester(first)),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = model::cancel, enabled = !ui.busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text("Cancel")
            }
            Button(
                onClick = { model.submitStart(password, phone) },
                enabled = !ui.busy,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                if (ui.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text("Send code")
            }
        }
        // Phone gone: the current phone is answered with a recovery code, and
        // nothing is texted to it. Not for turning it on -- there is no
        // current phone yet.
        if (ui.overview?.enabled == true) {
            TextButton(
                onClick = { model.submitStart(password, phone, useRecoveryCode = true) },
                enabled = !ui.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            ) { Text("No access to your phone? Use a recovery code") }
        }
    }
}

/** The texted code -- or, for the current phone, a recovery code. */
@Composable
private fun CodeStepForm(step: TwoFactorStep.Code, busy: Boolean, model: TwoFactorModel) {
    var code by remember(step.stage.stage, step.recovery) { mutableStateOf("") }
    LaunchedEffect(step.rejected) { if (step.rejected > 0) code = "" }
    var now by remember { mutableLongStateOf(model.clock()) }
    LaunchedEffect(step.resendAt) {
        while (true) {
            now = model.clock()
            if (now >= step.resendAt) break
            delay(250)
        }
    }
    val field = remember { FocusRequester() }
    LaunchedEffect(step.stage.stage, step.recovery) { runCatching { field.requestFocus() } }
    val length = step.stage.codeLength

    StepCard {
        Text(step.prompt, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { typed ->
                val cleaned = CodeInput.clean(typed, step.recovery, length)
                val wasComplete = CodeInput.complete(code, step.recovery, length)
                code = cleaned
                if (!wasComplete && CodeInput.complete(cleaned, step.recovery, length)) model.confirm(cleaned)
            },
            label = { Text(if (step.recovery) "Recovery code" else "Code from the text message") },
            singleLine = true,
            enabled = !busy,
            textStyle = if (step.recovery) LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
            else LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 22.sp,
                letterSpacing = 6.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
            ),
            keyboardOptions = if (step.recovery) {
                KeyboardOptions(keyboardType = KeyboardType.Text, capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false, imeAction = ImeAction.Done)
            } else {
                KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
            },
            keyboardActions = KeyboardActions(onDone = { model.confirm(code) }),
            modifier = Modifier.fillMaxWidth().focusRequester(field),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = model::cancel, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text("Cancel")
            }
            Button(onClick = { model.confirm(code) }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text("Verify")
            }
        }
        if (!step.recovery) {
            val wait = step.resendWait(now)
            TextButton(
                onClick = model::resend,
                enabled = !busy && wait == 0,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            ) { Text(if (wait > 0) "Send a new code ($wait s)" else "Send a new code") }
        }
        if (step.stage.recoveryAllowed) {
            TextButton(
                onClick = model::switchMethod,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            ) { Text(if (step.recovery) "Use a texted code instead" else "Use a recovery code instead") }
        }
    }
}

/** Shown once, so the screen says so, and offers ways to keep them. */
@Composable
private fun RecoveryCodes(codes: List<String>, onCopy: () -> Unit, onShare: () -> Unit, onDone: () -> Unit) {
    StepCard {
        Text("Save your recovery codes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "If you lose your phone, each one signs you in once in place of a texted code. They are not shown again.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(14.dp))
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // One numbered column: a code broken across two lines is a code
            // copied wrong. Selectable, for taking just one of them.
            SelectionContainer {
                Column(Modifier.padding(vertical = 12.dp, horizontal = 16.dp)) {
                    codes.forEachIndexed { i, c ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                            Text(
                                "${i + 1}.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(32.dp),
                            )
                            Text(c, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Copy") }
            OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Share") }
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("I have saved them") }
    }
}

@Composable
private fun StepCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun Chevron() {
    Icon(
        Icons.Default.ChevronRight,
        null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}

/**
 * Onto the clipboard, marked sensitive: Android 13 and later then leave the
 * codes out of the "copied" preview and the keyboard's clipboard history
 * suggestions, where they would otherwise sit on show.
 */
private fun copyCodes(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Recovery codes", text)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    clipboard.setPrimaryClip(clip)
    android.widget.Toast.makeText(context, "Recovery codes copied", android.widget.Toast.LENGTH_SHORT).show()
}

/** To a password manager, a note, or wherever else the person keeps such things. */
private fun shareCodes(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "CloudHub recovery codes")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Save recovery codes"))
}
