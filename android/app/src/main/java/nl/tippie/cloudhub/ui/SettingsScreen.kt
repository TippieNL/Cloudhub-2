package nl.tippie.cloudhub.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.tippie.cloudhub.data.Settings
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.User

/**
 * Everything about the app that was previously only changeable by reinstalling.
 *
 * Built from state that already exists -- the stored server address, the
 * remembered username, the grid/list default, the saved video positions and
 * the upload queue -- rather than from new preferences invented for the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    api: CloudHubApi,
    settings: Settings,
    user: User?,
    appVersion: String,
    queuedUploads: Int,
    cacheBytes: Long,
    videoCacheBytes: Long,
    theme: ThemeChoice,
    onTheme: (ThemeChoice) -> Unit,
    onClearCache: () -> Unit,
    onClearVideoCache: () -> Unit,
    onChangeServer: () -> Unit,
    onOpenStorage: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var grid by remember { mutableStateOf(settings.gridView) }
    var remembered by remember { mutableStateOf(settings.rememberedUsername) }
    var positions by remember { mutableStateOf(settings.rememberedPositionCount()) }
    var changingPassword by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            Section("Account")
            Line("Signed in as", user?.username ?: "unknown")
            Line("Role", (user?.role ?: "viewer").replaceFirstChar { it.uppercase() })
            // Available to every role: the server exempts this one route from
            // the write capability so a viewer can rotate their own password.
            Tappable("Change password") { changingPassword = true }

            Spacer(Modifier.height(20.dp))
            Section("Server")
            Line("Address", displayServer(api.baseUrl))
            Tappable("Storage and space left", onOpenStorage)
            Tappable("Use a different server", onChangeServer)

            Spacer(Modifier.height(20.dp))
            Section("Appearance")
            ThemeChoice.entries.forEach { option ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = theme == option,
                            role = Role.RadioButton,
                            onClick = { onTheme(option) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = theme == option, onClick = null)
                    Spacer(Modifier.width(10.dp))
                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Toggle(
                label = "Open folders in the grid",
                detail = if (grid) "Pictures first" else "Names first",
                checked = grid,
                onChange = { grid = it; settings.gridView = it },
            )

            Spacer(Modifier.height(20.dp))
            Section("Playback and history")
            Line(
                "Videos with a saved position",
                if (positions == 0) "None" else "$positions video${plural(positions)}",
            )
            if (positions > 0) {
                Tappable("Forget saved positions") {
                    settings.forgetAllResumePositions()
                    positions = 0
                    scope.launch { snackbar.showSnackbar("Saved positions forgotten") }
                }
            }
            remembered?.let {
                Tappable("Forget the remembered username ($it)") {
                    settings.rememberedUsername = null
                    remembered = null
                    scope.launch { snackbar.showSnackbar("Username forgotten") }
                }
            }

            Spacer(Modifier.height(20.dp))
            Section("On this device")
            Line("Cached thumbnails", humanBytes(cacheBytes))
            Tappable("Clear the thumbnail cache") {
                onClearCache()
                scope.launch { snackbar.showSnackbar("Thumbnail cache cleared") }
            }
            // Video kept so that skipping back, or watching something twice,
            // costs the network once. It evicts itself least-recent-first, but
            // a cache with no way to see or empty it is a cache you resent.
            var video by remember { mutableStateOf(videoCacheBytes) }
            Line("Cached video", if (video == 0L) "Nothing yet" else humanBytes(video))
            Tappable("Clear the video cache") {
                onClearVideoCache()
                video = 0
                scope.launch { snackbar.showSnackbar("Video cache cleared") }
            }
            // Worth surfacing: an upload runs under WorkManager and survives
            // the app closing, so a queue can be non-empty with nothing on
            // screen to say so.
            Line(
                "Uploads waiting",
                if (queuedUploads == 0) "None" else "$queuedUploads file${plural(queuedUploads)}",
            )

            Spacer(Modifier.height(20.dp))
            Section("About")
            Line("CloudHub", appVersion)

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onSignOut,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text("Sign out") }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (changingPassword) {
        ChangePasswordDialog(
            onDismiss = { changingPassword = false },
            onSubmit = { current, replacement ->
                withContext(Dispatchers.IO) { api.changePassword(current, replacement) }
                changingPassword = false
                snackbar.showSnackbar("Password changed")
            },
        )
    }
}

/**
 * Change your own password.
 *
 * The current one is required by the server, not just by this dialog: proving
 * you know it is what stops a borrowed unlocked phone becoming a stolen
 * account.
 */
@Composable
private fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onSubmit: suspend (String, String) -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy) return
        val problem = passwordProblem(current, replacement, confirm)
        if (problem != null) { error = problem; return }
        busy = true
        error = null
        scope.launch {
            try {
                onSubmit(current, replacement)
            } catch (e: Exception) {
                error = e.message ?: "Could not change the password."
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Change password") },
        text = {
            Column {
                PasswordField("Current password", current, ImeAction.Next) { current = it; error = null }
                Spacer(Modifier.height(10.dp))
                PasswordField("New password", replacement, ImeAction.Next) { replacement = it; error = null }
                Spacer(Modifier.height(10.dp))
                PasswordField("Repeat the new password", confirm, ImeAction.Done) { confirm = it; error = null }
                AnimatedVisibility(visible = error != null, enter = fadeIn(), exit = fadeOut()) {
                    Text(
                        error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Change")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordField(label: String, value: String, ime: ImeAction, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ime),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Why a password change cannot be sent yet.
 *
 * Checked here so the obvious mistakes -- a typo in the repeat, or a new
 * password identical to the old one -- are answered instantly instead of by a
 * round trip. The server enforces its own minimum regardless.
 */
internal fun passwordProblem(current: String, replacement: String, confirm: String): String? = when {
    current.isEmpty() -> "Enter your current password."
    replacement.length < MIN_PASSWORD -> "The new password must be at least $MIN_PASSWORD characters."
    replacement != confirm -> "The two new passwords do not match."
    replacement == current -> "The new password is the same as the current one."
    else -> null
}

/** Matches USER_PASSWORD_MIN_LENGTH on the server. */
internal const val MIN_PASSWORD = 12

@Composable
private fun Tappable(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .wrapContentHeight(Alignment.CenterVertically),
    )
}

@Composable
private fun Toggle(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
