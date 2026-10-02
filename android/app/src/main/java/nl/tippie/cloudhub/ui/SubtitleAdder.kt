package nl.tippie.cloudhub.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.FileEntry

/**
 * "Add subtitles…", from wherever a video is: its file menu or the player.
 *
 * Pick a .srt or .vtt, say what language it is -- the file's own name usually
 * answers that, so the prompt offers it -- and it is uploaded beside the video
 * under the name the players look for (`Holiday.nl.srt`). There is no subtitle
 * endpoint: sitting beside the film under that name is the whole wiring.
 *
 * A track already there under the same name is replaced, after asking, and
 * goes to the trash first. Uploads here keep both on a clash, so without that
 * step the second Dutch track became `Holiday.nl (2).srt`, which no player
 * recognises as Dutch.
 *
 * Returns the function that starts it for a given video. [onAdd] queues the
 * upload; [onMessage] reports a refusal.
 */
@Composable
fun rememberSubtitleAdder(
    api: CloudHubApi,
    onAdd: (video: FileEntry, file: Uri, name: String, language: String) -> Unit,
    onMessage: (String) -> Unit,
): (FileEntry) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var video by remember { mutableStateOf<FileEntry?>(null) }
    var picked by remember { mutableStateOf<Picked?>(null) }
    var replacing by remember { mutableStateOf<Pending?>(null) }

    val close = { video = null; picked = null; replacing = null }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        // A .srt has no agreed MIME type, so the picker cannot be narrowed to
        // one and the check is on the name instead.
        if (uri == null) { close(); return@rememberLauncherForActivityResult }
        val (name, size) = describe(context, uri)
        when {
            !SubtitleRules.isSubtitleFile(name) -> { close(); onMessage("Subtitles have to be a .srt or .vtt file") }
            // The server will not read a larger one, so it would upload and
            // then never appear in a menu -- which looks like a broken feature.
            size > SubtitleRules.MAX_BYTES -> { close(); onMessage("That file is too large to be a subtitle track") }
            else -> picked = Picked(uri, name)
        }
    }

    /** Upload, or ask first when a track of that name is already there. */
    fun proceed(pending: Pending) {
        scope.launch {
            val existing = runCatching { api.subtitles(pending.video.path) }.getOrDefault(emptyList())
                .firstOrNull { it.path.substringAfterLast('/') == pending.name }
            if (existing == null) {
                onAdd(pending.video, pending.file, pending.name, pending.language)
                close()
            } else {
                replacing = pending.copy(replaces = existing.path)
            }
        }
    }

    val current = video
    val chosen = picked
    if (current != null && chosen != null && replacing == null) {
        TextPrompt(
            "Add subtitles",
            // Empty is how an untagged track is asked for, which is the right
            // answer when the film has only one.
            "Language code — en, nl, de… (blank for none)",
            SubtitleRules.guessLanguage(chosen.name),
            onDismiss = close,
            onConfirm = { language ->
                val name = SubtitleRules.fileNameFor(current.name, language, chosen.name.substringAfterLast('.', ""))
                // Read as a pending upload, so the prompt is gone while the
                // existing tracks are looked up.
                picked = null
                proceed(Pending(current, chosen.uri, name, language.trim().lowercase()))
            },
        )
    }

    replacing?.let { pending ->
        AlertDialog(
            onDismissRequest = close,
            title = { Text("Replace the subtitles?") },
            text = { Text("${pending.name} is already beside this video. The current one goes to the trash.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val gone = runCatching { api.delete(pending.replaces!!) }.isSuccess
                        if (gone) onAdd(pending.video, pending.file, pending.name, pending.language)
                        else onMessage("Could not replace ${pending.name}")
                        close()
                    }
                }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
        )
    }

    return { target ->
        video = target
        picked = null
        replacing = null
        picker.launch("*/*")
    }
}

private data class Picked(val uri: Uri, val name: String)

private data class Pending(
    val video: FileEntry,
    val file: Uri,
    val name: String,
    val language: String,
    val replaces: String? = null,
)

/** A picked document's name and size: its content:// URI says neither. */
private fun describe(context: Context, uri: Uri): Pair<String, Long> {
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameAt = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeAt = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameAt >= 0) cursor.getString(nameAt).orEmpty() else ""
                val size = if (sizeAt >= 0 && !cursor.isNull(sizeAt)) cursor.getLong(sizeAt) else 0L
                return (name.ifBlank { uri.lastPathSegment.orEmpty() }) to size
            }
        }
    }
    return uri.lastPathSegment.orEmpty() to 0L
}
