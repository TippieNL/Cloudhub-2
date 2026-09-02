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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import nl.tippie.cloudhub.data.MediaCache
import nl.tippie.cloudhub.net.FileEntry
import nl.tippie.cloudhub.ui.*
import nl.tippie.cloudhub.work.StageResult
import nl.tippie.cloudhub.work.UploadQueue
import nl.tippie.cloudhub.work.UploadWorker

/**
 * Where the app is; small enough that a navigation library would be ceremony.
 *
 * Each screen carries a key, which is what the state holder files its scroll
 * position and its text fields under. It is the identity of the screen rather
 * than of the visit, so leaving for a video and coming back finds the same
 * drawer of state.
 */
private sealed interface Screen {
    val key: String

    data object Setup : Screen { override val key = "setup" }
    /** Checking whether the stored session is still good; see the effect below. */
    data object Restoring : Screen { override val key = "restoring" }
    data object SignIn : Screen { override val key = "signin" }
    data object Files : Screen { override val key = "files" }
    data object Trash : Screen { override val key = "trash" }
    data object Storage : Screen { override val key = "storage" }
    data object Duplicates : Screen { override val key = "duplicates" }
    data object SettingsScreen : Screen { override val key = "settingsscreen" }
    data class Images(val images: List<FileEntry>, val index: Int) : Screen {
        override val key get() = "images"
    }
    data class Play(val entry: FileEntry) : Screen {
        // Per video: two films should not share a drawer of state just
        // because both of them are "the player".
        override val key get() = "play:${entry.path}"
    }
}

class MainActivity : ComponentActivity() {

    private val app by lazy { CloudHubApp.from(this) }
    private lateinit var queue: UploadQueue

    /** Set when the file picker returns; consumed by the composition. */
    private val pendingUploads = mutableStateListOf<Uri>()
    private var uploadTarget = "/"

    /**
     * The gallery: Android's own photo picker, showing photos and videos
     * together. It needs no permission at all -- not READ_MEDIA_IMAGES, not
     * READ_MEDIA_VIDEO -- because the system runs the picker and hands back
     * only what was chosen. On older devices the contract falls back to
     * ACTION_OPEN_DOCUMENT by itself.
     */
    private val pickMedia = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKED)
    ) { uris ->
        if (uris.isNotEmpty()) enqueue(uris)
    }

    /** The document browser, kept alongside the gallery: it can offer a PDF. */
    private val pickFiles = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) enqueue(uris)
    }

    /**
     * Asked for when an upload is first queued, not at launch.
     *
     * A permission prompt with no context is noise; one that appears as you
     * start an upload explains itself. The answer changes nothing about the
     * upload -- only whether it can be watched from outside the app.
     */
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun requestNotificationsOnce() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        if (app.settings.notificationsAsked) return
        app.settings.notificationsAsked = true
        askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Where the camera was told to write; consumed when it reports success. */
    private var captureUri: Uri? = null

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        finishCapture(ok)
    }

    private val recordVideo = registerForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        finishCapture(ok)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        queue = UploadQueue(this)
        takeSharedFiles(intent)

        // Edge to edge, so the sign-in gradient runs behind the status bar
        // rather than stopping under a band of solid colour. Every other
        // screen is a Scaffold, which pads for the system bars itself.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            // Read before the theme is applied, because it decides the theme.
            var theme by remember { mutableStateOf(ThemeChoice.of(app.settings.themeChoice)) }

            CloudHubTheme(theme) {
                // Transparent bars mean the icons have to be told which way to
                // contrast; without this they are white on a near-white
                // background in light mode and invisible.
                val dark = androidx.compose.foundation.isSystemInDarkTheme()
                LaunchedEffect(dark) {
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }

                val model: FilesViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>) = FilesViewModel(app.api) as T
                })
                val signIn: SignInViewModel = viewModel(
                    key = "sign-in",
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>) = SignInViewModel(app.api) as T
                    },
                )

                /*
                 * Where the app is, and how it got there.
                 *
                 * A stack rather than a single value, because Back has to
                 * return you to the screen you came from: opening Storage from
                 * Settings and pressing Back used to drop you at the file list,
                 * having forgotten Settings entirely. The rules for what Back
                 * does from any given place are in ui/BackRules.kt.
                 */
                val stack = remember {
                    mutableStateListOf<Screen>(
                        if (app.settings.serverUrl.isNullOrBlank()) Screen.Setup else Screen.Restoring
                    )
                }
                val screen = stack.last()
                /** Open a screen, remembering the one it was opened from. */
                fun go(next: Screen) {
                    val updated = BackRules.pushed(stack.toList(), next)
                    stack.clear(); stack.addAll(updated)
                }
                /** Back: to the previous screen, or nothing if this is the first. */
                fun back() {
                    val updated = BackRules.popped(stack.toList())
                    stack.clear(); stack.addAll(updated)
                }
                /** Sign in, sign out, change server: a new beginning, no history. */
                fun reset(next: Screen) {
                    stack.clear(); stack.add(next)
                }
                var sharing by remember { mutableStateOf<FileEntry?>(null) }
                /*
                 * The file a viewer was closed on, so the list comes back to
                 * it. Swiping through thirty photos and pressing Back should
                 * land on the last one looked at, not the one opened.
                 */
                var reveal by remember { mutableStateOf<String?>(null) }
                val state by model.state.collectAsState()

                // Straight to the files if the stored session is still good.
                //
                // Resolved from Restoring rather than from SignIn: rendering
                // the sign-in screen first meant a launch that was already
                // signed in flashed a half-animated login form on its way past.
                LaunchedEffect(Unit) {
                    if (screen is Screen.Restoring) {
                        val ok = runCatching { withContext(Dispatchers.IO) { app.api.status() }.authenticated }
                            .getOrDefault(false)
                        if (ok) { reset(Screen.Files); model.start() } else reset(Screen.SignIn)
                    }
                }

                LaunchedEffect(state.path) { uploadTarget = state.path }

                /*
                 * One handler for the whole app, consulted before Android's
                 * own. Disabled at the root, which is what lets the system
                 * close the app normally -- including the predictive-back
                 * animation on Android 13 and up. The player registers its own
                 * while fullscreen; a nested handler is asked first, so
                 * un-maximising still wins over leaving the video.
                 */
                val outcome = BackRules.next(
                    BackRules.Where(
                        depth = stack.size,
                        onFiles = screen is Screen.Files,
                        selection = state.selected.size,
                        searching = state.filtering,
                        folder = state.path,
                    )
                )
                BackHandler(enabled = outcome != BackRules.Outcome.LEAVE_THE_APP) {
                    when (outcome) {
                        BackRules.Outcome.CLEAR_SELECTION -> model.clearSelection()
                        BackRules.Outcome.CLEAR_SEARCH -> model.clearSearch()
                        BackRules.Outcome.GO_UP_A_FOLDER -> model.open(BackRules.parentOf(state.path))
                        BackRules.Outcome.PREVIOUS_SCREEN -> back()
                        // Never reached: the handler is disabled for this.
                        BackRules.Outcome.LEAVE_THE_APP -> Unit
                    }
                }

                /*
                 * Keeps each screen's state while you are on another one.
                 *
                 * A list's scroll position belongs to the composable that owns
                 * it, and opening a video takes that composable out of the
                 * composition -- so scrolling to the fortieth clip, watching
                 * it, and coming back put you at the first. The holder keeps
                 * the drawer of saved state per screen key and hands it back
                 * on return, which is what a navigation library would do here.
                 */
                val screenState = rememberSaveableStateHolder()
                /*
                 * A popped screen's state is finished with.
                 *
                 * Kept, it outranks the arguments the screen is next opened
                 * with: the viewer remembers which photo is on show, so
                 * opening the next photo restored the page from last time and
                 * showed the previous photo. Done after composition rather
                 * than in back(), because the screen on its way out saves its
                 * state as it is disposed -- removing it first would simply be
                 * undone.
                 */
                val known = remember { mutableSetOf<String>() }
                LaunchedEffect(stack.map { it.key }) {
                    val live = stack.map { it.key }.toSet()
                    BackRules.forgotten(known, live).forEach { screenState.removeState(it) }
                    known.clear()
                    known.addAll(live)
                }
                // A signed-out session's screens are not worth restoring into
                // the next one's, and could show the previous account's place.
                LaunchedEffect(state.user?.username) {
                    if (state.user == null) screenState.removeState(Screen.Files.key)
                }

                screenState.SaveableStateProvider(screen.key) {
                when (val current = screen) {
                    is Screen.Setup -> SetupScreen(
                        api = app.api,
                        initial = app.settings.serverUrl,
                        onTrust = { app.pins.trust(it) },
                        onReady = { app.useServer(it); reset(Screen.SignIn) },
                    )

                    is Screen.Restoring -> RestoringScreen()

                    is Screen.SignIn -> SignInScreen(
                        model = signIn,
                        serverUrl = app.settings.serverUrl.orEmpty(),
                        rememberedUsername = app.settings.rememberedUsername,
                        onSignedIn = { username, remember ->
                            app.settings.rememberedUsername = if (remember) username else null
                            reset(Screen.Files)
                            model.start()
                            UploadWorker.enqueue(this@MainActivity)
                        },
                        onChangeServer = { go(Screen.Setup) },
                    )

                    is Screen.Files -> FilesScreen(
                        api = app.api,
                        model = model,
                        onOpenFile = { entry ->
                            when (entry.kind) {
                                FileEntry.Kind.IMAGE -> {
                                    val images = state.visible.filter { it.kind == FileEntry.Kind.IMAGE }
                                    go(Screen.Images(images, images.indexOfFirst { it.path == entry.path }))
                                }
                                FileEntry.Kind.VIDEO, FileEntry.Kind.AUDIO -> go(Screen.Play(entry))
                                // Anything with no viewer of its own is handed
                                // to whatever app on the phone does handle it,
                                // which is not a screen of ours to go back from.
                                else -> openExternally(entry)
                            }
                        },
                        onOpenTrash = { go(Screen.Trash) },
                        onOpenStorage = { go(Screen.Storage) },
                        onOpenDuplicates = { go(Screen.Duplicates) },
                        onOpenSettings = { go(Screen.SettingsScreen) },
                        onSignOut = {
                            lifecycleScope.launch {
                                runCatching { withContext(Dispatchers.IO) { app.api.logout() } }
                                app.settings.signOut()
                                reset(Screen.SignIn)
                            }
                        },
                        onPickMedia = {
                            pickMedia.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        },
                        onPickFile = { pickFiles.launch("*/*") },
                        onTakePhoto = { startCapture("jpg") { takePhoto.launch(it) } },
                        onRecordVideo = { startCapture("mp4") { recordVideo.launch(it) } },
                        onDownload = { download(it) },
                        onShare = { sharing = it },
                        onDismissUploadFailures = { queue.clearFailures() },
                        revealPath = reveal,
                        onRevealed = { reveal = null },
                    )

                    is Screen.Duplicates -> DuplicatesScreen(
                        api = app.api,
                        canWrite = state.canWrite,
                        onBack = { back(); model.refresh() },
                    )

                    is Screen.Storage -> StorageScreen(
                        api = app.api,
                        onBack = { back() },
                    )

                    is Screen.SettingsScreen -> SettingsScreen(
                        api = app.api,
                        settings = app.settings,
                        user = state.user,
                        appVersion = appVersion(),
                        queuedUploads = queue.all().size,
                        cacheBytes = thumbnailCacheBytes(),
                        videoCacheBytes = MediaCache.sizeBytes(this@MainActivity),
                        theme = theme,
                        onTheme = { theme = it; app.settings.themeChoice = it.name },
                        onClearCache = { clearThumbnailCache() },
                        onClearVideoCache = { MediaCache.clear(this@MainActivity) },
                        onChangeServer = { go(Screen.Setup) },
                        onOpenStorage = { go(Screen.Storage) },
                        onOpenDuplicates = { go(Screen.Duplicates) },
                        onSignOut = {
                            lifecycleScope.launch {
                                runCatching { withContext(Dispatchers.IO) { app.api.logout() } }
                                app.settings.signOut()
                                reset(Screen.SignIn)
                            }
                        },
                        onBack = { back() },
                    )

                    is Screen.Trash -> TrashScreen(
                        api = app.api,
                        canWrite = state.canWrite,
                        onBack = { back(); model.refresh() },
                    )

                    is Screen.Images -> ImageViewer(
                        api = app.api, images = current.images, startAt = current.index,
                        onBack = { photo -> reveal = photo; back() },
                    )

                    is Screen.Play -> PlayerScreen(
                        api = app.api, client = app.client, settings = app.settings,
                        entry = current.entry,
                        onBack = { reveal = current.entry.path; back() },
                    )
                }
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

    /* ---- what settings reports ------------------------------------------- */

    private fun appVersion(): String = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        "${info.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)})"
    }.getOrDefault("unknown")

    /**
     * What Coil's disk cache is holding.
     *
     * Measured rather than tracked: the loader writes to it in the background
     * and nothing else here would know when it grew.
     */
    private fun thumbnailCacheBytes(): Long = runCatching {
        java.io.File(cacheDir, "image_cache").walkBottomUp()
            .filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    private fun clearThumbnailCache() {
        runCatching { app.newImageLoader().diskCache?.clear() }
        runCatching { app.newImageLoader().memoryCache?.clear() }
    }

    /* ---- camera ----------------------------------------------------------- */

    /**
     * Point the camera app at a file of ours and launch it.
     *
     * The full-resolution frame is written to a FileProvider URI rather than
     * returned as an extra: an Intent extra can only carry a thumbnail, and a
     * 150px photo is not worth uploading. Neither capture needs the CAMERA
     * permission, and recording needs no RECORD_AUDIO either -- the camera app
     * owns the capture and holds its own permissions.
     */
    private fun startCapture(extension: String, launch: (Uri) -> Unit) {
        val uri = try {
            val dir = java.io.File(cacheDir, "captures").apply { mkdirs() }
            val file = java.io.File(dir, "capture-${System.currentTimeMillis()}.$extension")
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not prepare the camera: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        captureUri = uri
        runCatching { launch(uri) }.onFailure {
            captureUri = null
            Toast.makeText(this, "No camera app is available", Toast.LENGTH_LONG).show()
        }
    }

    /** Queue what the camera wrote, or clean up after a cancelled capture. */
    private fun finishCapture(ok: Boolean) {
        val uri = captureUri ?: return
        captureUri = null
        if (ok) enqueue(listOf(uri)) else deleteCapture(uri)
    }

    private fun deleteCapture(uri: Uri) {
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: return
        runCatching { java.io.File(java.io.File(cacheDir, "captures"), name).delete() }
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
            var full: StageResult.NoRoom? = null
            var unreadable = 0
            for (uri in uris) {
                when (val staged = UploadWorker.stage(this@MainActivity, uri, displayName(uri))) {
                    is StageResult.Staged -> {
                        queue.add(staged.upload.copy(name = displayName(uri), targetPath = uploadTarget))
                        queued++
                    }
                    is StageResult.NoRoom -> full = staged
                    StageResult.Unreadable -> unreadable++
                }
            }
            withContext(Dispatchers.Main) {
                if (queued > 0) {
                    requestNotificationsOnce()
                    UploadWorker.enqueue(this@MainActivity)
                    Toast.makeText(this@MainActivity,
                        if (queued == 1) "1 file queued for upload" else "$queued files queued for upload",
                        Toast.LENGTH_SHORT).show()
                }
                // Space is the actionable failure, so it is the one that is
                // named; "nothing could be read" would send you looking in the
                // wrong place entirely.
                full?.let {
                    val needed = if (it.needed >= 0) humanBytes(it.needed) else "that file"
                    Toast.makeText(this@MainActivity,
                        "Not enough space on this phone: $needed to copy, ${humanBytes(it.free)} free",
                        Toast.LENGTH_LONG).show()
                }
                if (full == null && unreadable > 0 && queued == 0) {
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

    private companion object {
        /** The photo picker's own ceiling; asking for more than it allows throws. */
        const val MAX_PICKED = 30
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
