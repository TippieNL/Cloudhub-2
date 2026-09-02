package nl.tippie.cloudhub.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
    onOpenDuplicates: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    var grid by remember { mutableStateOf(settings.gridView) }
    var remembered by remember { mutableStateOf(settings.rememberedUsername) }
    var positions by remember { mutableStateOf(settings.rememberedPositionCount()) }
    var changingPassword by remember { mutableStateOf(false) }
    var video by remember { mutableStateOf(videoCacheBytes) }
    var thumbnails by remember { mutableStateOf(cacheBytes) }
    /* Anything that throws something away asks first, and says what. */
    var confirming by remember { mutableStateOf<Confirmation?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            // Large and collapsing: the title is the screen's own heading while
            // you are at the top of it, and gets out of the way as you scroll.
            LargeTopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            AccountHeader(
                username = user?.username ?: "Not signed in",
                role = (user?.role ?: "viewer").replaceFirstChar { it.uppercase() },
            )

            SettingsGroup("Account") {
                // Available to every role: the server exempts this one route
                // from the write capability so a viewer can rotate their own
                // password.
                SettingsRow(
                    icon = Icons.Default.Lock,
                    title = "Change password",
                    supporting = "Your current password is required",
                    onClick = { changingPassword = true },
                    trailing = { Chevron() },
                )
            }

            SettingsGroup("Server") {
                SettingsRow(
                    icon = Icons.Default.CloudQueue,
                    title = "Address",
                    supporting = displayServer(api.baseUrl),
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.PieChart,
                    title = "Storage and space left",
                    onClick = onOpenStorage,
                    trailing = { Chevron() },
                )
                SettingsDivider()
                // Beside Storage on purpose: both answer "where has my space
                // gone", and this one gives some of it back.
                SettingsRow(
                    icon = Icons.Default.ContentCopy,
                    title = "Find duplicates",
                    supporting = "Photos and videos stored more than once",
                    onClick = onOpenDuplicates,
                    trailing = { Chevron() },
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.SwapHoriz,
                    title = "Use a different server",
                    onClick = onChangeServer,
                    trailing = { Chevron() },
                )
            }

            SettingsGroup("Appearance") {
                Text(
                    "Theme",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp),
                )
                SegmentedChoice(
                    options = ThemeChoice.entries.toList(),
                    selected = theme,
                    label = { it.label },
                    onSelect = onTheme,
                )
                SettingsDivider()
                SettingsSwitchRow(
                    icon = Icons.Default.GridView,
                    title = "Open folders in the grid",
                    supporting = if (grid) "Pictures first, in a grid" else "Names first, in a list",
                    checked = grid,
                    onChange = { grid = it; settings.gridView = it },
                )
            }

            SettingsGroup("Playback and history") {
                SettingsRow(
                    icon = Icons.Default.PlayCircle,
                    title = "Videos with a saved position",
                    supporting = "Reopening one offers to carry on where you stopped",
                    trailing = {
                        SettingsValue(if (positions == 0) "None" else "$positions video${plural(positions)}")
                    },
                )
                if (positions > 0) {
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Default.DeleteSweep,
                        title = "Forget saved positions",
                        tone = RowTone.DANGER,
                        onClick = {
                            confirming = Confirmation(
                                title = "Forget saved positions?",
                                message = "$positions video${plural(positions)} will start from the " +
                                    "beginning next time. Nothing is deleted from the server.",
                                action = "Forget",
                            ) {
                                settings.forgetAllResumePositions()
                                positions = 0
                                scope.launch { snackbar.showSnackbar("Saved positions forgotten") }
                            }
                        },
                    )
                }
                remembered?.let { name ->
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Default.PersonOff,
                        title = "Forget the remembered username",
                        supporting = name,
                        tone = RowTone.DANGER,
                        onClick = {
                            settings.rememberedUsername = null
                            remembered = null
                            scope.launch { snackbar.showSnackbar("Username forgotten") }
                        },
                    )
                }
            }

            SettingsGroup("On this device") {
                SettingsRow(
                    icon = Icons.Default.Image,
                    title = "Cached thumbnails",
                    onClick = {
                        onClearCache()
                        thumbnails = 0
                        scope.launch { snackbar.showSnackbar("Thumbnail cache cleared") }
                    },
                    supporting = if (thumbnails == 0L) "Nothing cached" else "Tap to clear",
                    trailing = { SettingsValue(if (thumbnails == 0L) "Empty" else humanBytes(thumbnails)) },
                )
                SettingsDivider()
                // Video kept so that skipping back, or watching something
                // twice, costs the network once. It evicts itself
                // least-recent-first, but a cache with no way to see or empty
                // it is a cache you resent.
                SettingsRow(
                    icon = Icons.Default.Movie,
                    title = "Cached video",
                    onClick = {
                        onClearVideoCache()
                        video = 0
                        scope.launch { snackbar.showSnackbar("Video cache cleared") }
                    },
                    supporting = if (video == 0L) "Nothing cached" else "Tap to clear",
                    trailing = { SettingsValue(if (video == 0L) "Empty" else humanBytes(video)) },
                )
                SettingsDivider()
                // Worth surfacing: an upload runs under WorkManager and
                // survives the app closing, so a queue can be non-empty with
                // nothing on screen to say so.
                SettingsRow(
                    icon = Icons.Default.Upload,
                    title = "Uploads waiting",
                    supporting = "Uploads continue with the app closed",
                    trailing = {
                        SettingsValue(
                            if (queuedUploads == 0) "None" else "$queuedUploads file${plural(queuedUploads)}"
                        )
                    },
                )
            }

            SettingsGroup("About") {
                SettingsRow(
                    icon = Icons.Default.Info,
                    title = "CloudHub",
                    trailing = { SettingsValue(appVersion) },
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.AutoMirrored.Filled.Logout,
                    title = "Sign out",
                    tone = RowTone.DANGER,
                    onClick = {
                        confirming = Confirmation(
                            title = "Sign out?",
                            message = "Uploads still waiting will stay queued until you sign in again.",
                            action = "Sign out",
                            onConfirm = onSignOut,
                        )
                    },
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    confirming?.let { ask ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(ask.title) },
            text = { Text(ask.message) },
            confirmButton = {
                TextButton(
                    onClick = { confirming = null; ask.onConfirm() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(ask.action) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
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

/** What a destructive row asks before doing it. */
private data class Confirmation(
    val title: String,
    val message: String,
    val action: String,
    val onConfirm: () -> Unit,
)

@Composable
private fun Chevron() {
    Icon(
        Icons.Default.ChevronRight,
        null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}

