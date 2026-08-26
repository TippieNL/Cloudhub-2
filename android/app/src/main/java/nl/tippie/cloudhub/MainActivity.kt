package nl.tippie.cloudhub

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.tippie.cloudhub.net.FileEntry
import nl.tippie.cloudhub.ui.*
import nl.tippie.cloudhub.work.UploadQueue
import nl.tippie.cloudhub.work.UploadWorker

/** Where the app is; small enough that a navigation library would be ceremony. */
private sealed interface Screen {
    data object Setup : Screen
    data object SignIn : Screen
    data object Files : Screen
    data object Trash : Screen
    data class Images(val images: List<FileEntry>, val index: Int) : Screen
    data class Play(val entry: FileEntry) : Screen
}

class MainActivity : ComponentActivity() {

    private val app by lazy { CloudHubApp.from(this) }
    private lateinit var queue: UploadQueue

    /** Set when the file picker returns; consumed by the composition. */
    private val pendingUploads = mutableStateListOf<Uri>()
    private var uploadTarget = "/"

    private val pickFiles = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) enqueue(uris)
    }

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap == null) return@registerForActivityResult
        // TakePicturePreview hands back a thumbnail-sized bitmap; writing it
        // out keeps the upload path identical to every other source.
        lifecycleScope.launch(Dispatchers.IO) {
            val file = java.io.File(cacheDir, "capture-${System.currentTimeMillis()}.jpg")
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
            enqueue(listOf(Uri.fromFile(file)))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        queue = UploadQueue(this)
        takeSharedFiles(intent)

        setContent {
            CloudHubTheme {
                val model: FilesViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>) = FilesViewModel(app.api) as T
                })

                var screen by remember {
                    mutableStateOf<Screen>(
                        if (app.settings.serverUrl.isNullOrBlank()) Screen.Setup else Screen.SignIn
                    )
                }
                var sharing by remember { mutableStateOf<FileEntry?>(null) }
                val state by model.state.collectAsState()

                // Straight to the files if the stored session is still good.
                LaunchedEffect(Unit) {
                    if (screen is Screen.SignIn) {
                        val ok = runCatching { withContext(Dispatchers.IO) { app.api.status() }.authenticated }
                            .getOrDefault(false)
                        if (ok) { screen = Screen.Files; model.start() }
                    }
                }

                LaunchedEffect(state.path) { uploadTarget = state.path }

                when (val current = screen) {
                    is Screen.Setup -> SetupScreen(
                        api = app.api,
                        initial = app.settings.serverUrl,
                        onTrust = { app.pins.trust(it) },
                        onReady = { app.useServer(it); screen = Screen.SignIn },
                    )

                    is Screen.SignIn -> SignInScreen(
                        api = app.api,
                        serverUrl = app.settings.serverUrl.orEmpty(),
                        onSignedIn = { screen = Screen.Files; model.start(); UploadWorker.enqueue(this@MainActivity) },
                        onChangeServer = { screen = Screen.Setup },
                    )

                    is Screen.Files -> FilesScreen(
                        api = app.api,
                        model = model,
                        onOpenFile = { entry ->
                            screen = when (entry.kind) {
                                FileEntry.Kind.IMAGE -> {
                                    val images = state.visible.filter { it.kind == FileEntry.Kind.IMAGE }
                                    Screen.Images(images, images.indexOfFirst { it.path == entry.path })
                                }
                                FileEntry.Kind.VIDEO, FileEntry.Kind.AUDIO -> Screen.Play(entry)
                                // Anything with no viewer of its own is handed
                                // to whatever app on the phone does handle it.
                                else -> { openExternally(entry); current }
                            }
                        },
                        onOpenTrash = { screen = Screen.Trash },
                        onSignOut = {
                            lifecycleScope.launch {
                                runCatching { withContext(Dispatchers.IO) { app.api.logout() } }
                                app.settings.signOut()
                                screen = Screen.SignIn
                            }
                        },
                        onUpload = { pickFiles.launch("*/*") },
                        onCamera = { takePhoto.launch(null) },
                        onDownload = { download(it) },
                        onShare = { sharing = it },
                    )

                    is Screen.Trash -> TrashScreen(
                        api = app.api,
                        canWrite = state.canWrite,
                        onBack = { screen = Screen.Files; model.refresh() },
                    )

                    is Screen.Images -> ImageViewer(
                        api = app.api, images = current.images, startAt = current.index,
                        onBack = { screen = Screen.Files },
                    )

                    is Screen.Play -> PlayerScreen(
                        api = app.api, client = app.client, entry = current.entry,
                        onBack = { screen = Screen.Files },
                    )
                }

                sharing?.let { entry ->
                    ShareDialog(
                        api = app.api, entry = entry,
                        onDismiss = { sharing = null },
                        onCopy = { url ->
                            copyToClipboard(url)
                            Toast.makeText(this@MainActivity, "Link copied", Toast.LENGTH_SHORT).show()
                            sharing = null
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeSharedFiles(intent)
    }

    /* ---- uploads --------------------------------------------------------- */

    /** Files handed over by the Android share sheet. */
    private fun takeSharedFiles(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) enqueue(uris)
    }

    private fun enqueue(uris: List<Uri>) {
        lifecycleScope.launch(Dispatchers.IO) {
            var queued = 0
            for (uri in uris) {
                val staged = UploadWorker.stage(this@MainActivity, uri, displayName(uri))
                if (staged != null) {
                    queue.add(staged.copy(name = displayName(uri), targetPath = uploadTarget))
                    queued++
                }
            }
            withContext(Dispatchers.Main) {
                if (queued > 0) {
                    UploadWorker.enqueue(this@MainActivity)
                    Toast.makeText(this@MainActivity,
                        if (queued == 1) "1 file queued for upload" else "$queued files queued for upload",
                        Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Nothing could be read from that", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return uri.lastPathSegment ?: "shared-${System.currentTimeMillis()}"
    }

    /* ---- downloads -------------------------------------------------------- */

    private fun download(entry: FileEntry) {
        Toast.makeText(this, "Downloading ${entry.name}…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val message = try {
                app.api.openDownload(entry.path).use { body ->
                    saveToDownloads(this@MainActivity, entry.name, body.byteStream())
                }
                "Saved ${entry.name} to Downloads"
            } catch (e: Exception) {
                "Could not download ${entry.name}: ${e.message}"
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openExternally(entry: FileEntry) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(app.api.previewUrl(entry.path).toString()))
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "No app can open ${entry.name}", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("CloudHub", text))
    }
}

/** Stream into the device's Downloads folder without buffering the whole file. */
private fun saveToDownloads(context: Context, name: String, input: java.io.InputStream) {
    val safe = name.substringAfterLast('/').ifBlank { "download" }
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safe)
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val item = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw java.io.IOException("The Downloads folder refused the file")
        resolver.openOutputStream(item)?.use { input.copyTo(it) }
            ?: throw java.io.IOException("The Downloads folder could not be opened")
        values.clear()
        values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(item, values, null, null)
    } else {
        val dir = android.os.Environment
            .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        java.io.File(dir, safe).outputStream().use { input.copyTo(it) }
    }
}
