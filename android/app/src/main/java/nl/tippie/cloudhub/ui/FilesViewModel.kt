package nl.tippie.cloudhub.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.tippie.cloudhub.net.ApiError
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.FileEntry
import nl.tippie.cloudhub.net.User

/** What is on screen, and how it got there. */
data class FilesState(
    val path: String = "/",
    val entries: List<FileEntry> = emptyList(),
    val load: LoadState = LoadState.LOADING,
    /**
     * Why the listing failed, kept until it is retried.
     *
     * Separate from [message], which is transient snackbar feedback for an
     * action ("Renamed", "Moved to trash"). Routing a failed *listing* through
     * the snackbar cleared it a moment later and left the content area showing
     * "This folder is empty" -- a wrong answer stated confidently.
     */
    val loadError: String? = null,
    val message: String? = null,
    val selected: Set<String> = emptySet(),
    val user: User? = null,
    val query: String = "",
    /** Null while browsing; set while showing results from the server. */
    val searchResults: List<FileEntry>? = null,
    val searchTruncated: Boolean = false,
    val sort: Sort = Sort.NAME,
    val grid: Boolean = true,
) {
    enum class Sort { NAME, NEWEST, LARGEST }

    /** What the grid should draw: search results when searching, else the folder. */
    val visible: List<FileEntry>
        get() {
            val base = searchResults ?: entries.filter {
                query.isBlank() || it.name.contains(query, ignoreCase = true)
            }
            return base.sortedWith(
                compareByDescending<FileEntry> { it.isDirectory }.thenComparator { a, b ->
                    when (sort) {
                        Sort.NAME -> a.name.compareTo(b.name, ignoreCase = true)
                        Sort.NEWEST -> b.modified.compareTo(a.modified)
                        Sort.LARGEST -> b.size.compareTo(a.size)
                    }
                }
            )
        }

    val canWrite get() = user?.canWrite == true

    /** True while a filter or a server search is narrowing what is shown. */
    val filtering get() = searchResults != null || query.isNotBlank()

    val loading get() = load == LoadState.LOADING

    /** What the content area should draw. */
    val shown: Shown get() = browserState(
        load = load,
        hasEntries = entries.isNotEmpty(),
        hasVisible = visible.isNotEmpty(),
        filtering = filtering,
    )
}

class FilesViewModel(private val api: CloudHubApi) : ViewModel() {

    private val _state = MutableStateFlow(FilesState())
    val state: StateFlow<FilesState> = _state.asStateFlow()

    fun start() {
        viewModelScope.launch {
            runCatching { api.status() }
                .onSuccess { _state.update { s -> s.copy(user = it.user) } }
            open("/")
        }
    }

    fun open(path: String) {
        viewModelScope.launch {
            // Entries are dropped when moving to a different folder so the
            // skeleton appears; a refresh of the same folder keeps them, and
            // keeps what is on screen.
            val movingOn = path != _state.value.path
            _state.update {
                it.copy(
                    load = LoadState.LOADING, loadError = null, selected = emptySet(),
                    searchResults = null,
                    entries = if (movingOn) emptyList() else it.entries,
                )
            }
            try {
                val entries = api.list(path)
                _state.update { it.copy(path = path, entries = entries, load = LoadState.READY) }
            } catch (e: ApiError) {
                // A folder can be renamed or deleted between visits. Falling
                // back to the root beats an empty screen with no explanation.
                if (path != "/" && e.status == 404) {
                    _state.update { it.copy(message = "That folder is no longer there") }
                    open("/")
                } else {
                    _state.update { it.copy(load = LoadState.FAILED, loadError = e.message) }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(load = LoadState.FAILED, loadError = e.message ?: "Could not reach the server")
                }
            }
        }
    }

    fun refresh() = open(_state.value.path)

    /** After a failure: back to the skeleton, and try the same folder again. */
    fun retry() = open(_state.value.path)

    fun setQuery(query: String) {
        _state.update { it.copy(query = query, searchResults = if (query.isBlank()) null else it.searchResults) }
    }

    /** The explicit all-folders search, mirroring the web app's scope toggle. */
    fun searchEverywhere() {
        val query = _state.value.query.trim()
        if (query.length < 2) {
            _state.update { it.copy(message = "Enter at least two characters") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(load = LoadState.LOADING, loadError = null) }
            try {
                val found = api.search(query, _state.value.path)
                _state.update {
                    it.copy(load = LoadState.READY, searchResults = found.results,
                        searchTruncated = found.truncated, selected = emptySet())
                }
            } catch (e: Exception) {
                _state.update { it.copy(load = LoadState.FAILED, loadError = e.message) }
            }
        }
    }

    fun clearSearch() = _state.update { it.copy(query = "", searchResults = null) }

    fun toggleSelected(path: String) = _state.update {
        it.copy(selected = if (path in it.selected) it.selected - path else it.selected + path)
    }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    fun setSort(sort: FilesState.Sort) = _state.update { it.copy(sort = sort) }

    fun setGrid(grid: Boolean) = _state.update { it.copy(grid = grid) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /* ---- actions -------------------------------------------------------- */

    fun makeFolder(name: String) = act("Folder created") {
        api.makeFolder(joinPath(_state.value.path, name))
    }

    fun rename(entry: FileEntry, newName: String) = act("Renamed") {
        api.rename(entry.path, joinPath(entry.path.substringBeforeLast('/', ""), newName))
    }

    fun delete(paths: List<String>) = act(null) {
        var trashed = false
        for (path in paths) trashed = api.delete(path).trashed || trashed
        object { val message = if (trashed) "Moved to trash" else "Deleted" }
    }

    fun move(paths: List<String>, destination: String) = relocate(paths, destination, moving = true)

    fun copy(paths: List<String>, destination: String) = relocate(paths, destination, moving = false)

    private fun relocate(paths: List<String>, destination: String, moving: Boolean) {
        viewModelScope.launch {
            try {
                val result = if (moving) api.move(paths, destination) else api.copy(paths, destination)
                // Per-item failures are reported, never rounded to "done".
                val note = if (result.failed.isEmpty()) {
                    "${result.completed} item${if (result.completed == 1) "" else "s"} " +
                        if (moving) "moved" else "copied"
                } else {
                    "${result.completed} done, ${result.failed.size} failed: ${result.failed.first().message}"
                }
                _state.update { it.copy(message = note, selected = emptySet()) }
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message) }
            }
        }
    }

    private fun act(success: String?, block: suspend () -> Any) {
        viewModelScope.launch {
            try {
                val result = block()
                val note = success ?: (result as? Any)?.let {
                    runCatching { it.javaClass.getMethod("getMessage").invoke(it) as? String }.getOrNull()
                }
                _state.update { it.copy(message = note, selected = emptySet()) }
                refresh()
            } catch (e: ApiError) {
                _state.update { it.copy(message = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: "That did not work") }
            }
        }
    }

    companion object {
        fun joinPath(parent: String, name: String): String =
            if (parent == "/" || parent.isEmpty()) "/$name" else "$parent/$name"
    }
}
