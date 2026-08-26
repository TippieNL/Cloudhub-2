package nl.tippie.cloudhub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.tippie.cloudhub.data.ServerAddress
import nl.tippie.cloudhub.net.ApiError
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.UntrustedCertificate

/**
 * First run: where is the server?
 *
 * The address is checked before it is accepted, because a typo otherwise shows
 * up much later as a blank screen with no explanation.
 */
@Composable
fun SetupScreen(
    api: CloudHubApi,
    initial: String?,
    onTrust: (String) -> Unit,
    onReady: (String) -> Unit,
) {
    var input by remember { mutableStateOf(initial.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var certificate by remember { mutableStateOf<UntrustedCertificate?>(null) }
    val scope = rememberCoroutineScope()

    fun attempt() {
        val url = ServerAddress.normalise(input)
        if (url == null) { error = "Enter a server address."; return }
        busy = true; error = null
        scope.launch {
            val previous = api.baseUrl
            api.baseUrl = url
            try {
                // /api/auth/status answers without a session and returns JSON,
                // so a captive portal or an unrelated web server is not
                // mistaken for a CloudHub.
                withContext(Dispatchers.IO) { api.status() }
                onReady(url)
            } catch (e: ApiError) {
                // Any structured reply means we reached a CloudHub.
                onReady(url)
            } catch (e: Exception) {
                api.baseUrl = previous
                val untrusted = generateSequence(e as Throwable?) { it.cause }
                    .filterIsInstance<UntrustedCertificate>().firstOrNull()
                if (untrusted != null) certificate = untrusted
                else error = e.message ?: "Could not reach that server."
            } finally {
                busy = false
            }
        }
    }

    certificate?.let { untrusted ->
        CertificateDialog(
            certificate = untrusted,
            onTrust = { onTrust(untrusted.fingerprint); certificate = null; attempt() },
            onDismiss = { certificate = null },
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Connect to your server", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Enter the address of your CloudHub server. You can change it later.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Server address") },
            placeholder = { Text("files.example.com") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(18.dp))
        Button(onClick = ::attempt, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("Connect")
        }
        if (ServerAddress.isInsecure(input.trim())) {
            Spacer(Modifier.height(20.dp))
            Text(
                "Over plain http the app cannot use a secure connection. " +
                    "Prefer https:// where you can.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SignInScreen(api: CloudHubApi, serverUrl: String, onSignedIn: () -> Unit, onChangeServer: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (username.isBlank() || password.isBlank()) { error = "Enter your username and password."; return }
        busy = true; error = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { api.login(username.trim(), password) }
                if (result.success) onSignedIn() else error = "Sign in failed."
            } catch (e: ApiError) {
                error = e.message
            } catch (e: Exception) {
                error = e.message ?: "Could not reach the server."
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("CloudHub", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            serverUrl,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = username, onValueChange = { username = it },
            label = { Text("Username") }, singleLine = true, enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Password") }, singleLine = true, enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(18.dp))
        Button(onClick = ::submit, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Sign in")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onChangeServer) { Text("Use a different server") }
    }
}

/**
 * A certificate the device does not trust.
 *
 * The fingerprint is shown because that is the only thing the user can
 * meaningfully check. Accepting pins this certificate, not this host.
 */
@Composable
fun CertificateDialog(
    certificate: UntrustedCertificate,
    onTrust: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Certificate not trusted") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("The server presented a certificate this device does not trust (${certificate.reason}).")
                Spacer(Modifier.height(12.dp))
                Text("SHA-256", style = MaterialTheme.typography.labelMedium)
                Text(certificate.fingerprint, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Only continue if this is your server's fingerprint. It will be remembered, " +
                        "and you will be asked again if it ever changes.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onTrust) { Text("Trust this certificate") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
