package nl.tippie.cloudhub.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /**
     * An All folders search is on: asked for, or showing its results. The
     * results are then always the query in the box -- an edit searches again
     * rather than leaving the last query's answer under the new text.
     */
    val searchMode: Boolean = false,
    /** A request for the search is out; the results so far stay on screen. */
    val searching: Boolean = false,
    /** What the server said it examined, for "No matches in the first N files". */
    val searchScanned: Int = 0,
    /** Why the search failed. A failed search is not a failed listing. */
    val searchError: String? = null,
    val sort: Sort = Sort.NAME,
    val grid: Boolean = true,
    /**
     * The paths this account has starred.
     *
     * A folder listing does not say -- on the server it touches no database --
     * so this is read once and kept in step with every star and unstar. Held
     * here rather than by the Favorites screen so a star changed anywhere, in
     * the viewer or the player, is right everywhere else too.
     */
    val favorites: Set<String> = emptySet(),
    /** The same, as listing rows, most recently starred first. */
    val favoriteEntries: List<FileEntry> = emptyList(),
    val favoritesLoad: LoadState = LoadState.LOADING,
    val favoritesError: String? = null,
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
    val filtering get() = searchMode || searchResults != null || query.isNotBlank()

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
        // A new sign-in, possibly as somebody else: the last account's stars
        // must not decorate this one's files while the real ones load.
        _state.update { it.copy(favorites = emptySet(), favoriteEntries = emptyList()) }
        viewModelScope.launch {
            runCatching { api.status() }
                .onSuccess { _state.update { s -> s.copy(user = it.user) } }
            open("/")
            loadFavorites()
        }
    }

    fun open(path: String) {
        // Leaving for a folder -- a search result's, or any other -- ends the search.
        endSearch()
        viewModelScope.launch {
            // Entries are dropped when moving to a different folder so the
            // skeleton appears; a refresh of the same folder keeps them, and
            // keeps what is on screen.
            val movingOn = path != _state.value.path
            _state.update {
                it.copy(
                    load = LoadState.LOADING, loadError = null, selected = emptySet(),
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

    /**
     * The folder again, and the favorites with it: refresh follows every
     * rename, move and delete, and each of those can change a starred path.
     */
    fun refresh() {
        open(_state.value.path)
        loadFavorites()
    }

    /** After a failure: back to the skeleton, and try the same folder again. */
    fun retry() = open(_state.value.path)

    /** The search running now, so a newer one can cancel it before its answer lands. */
    private var searchJob: Job? = null

    /**
     * Typing filters the folder on screen, until All folders has been asked
     * for. From then on an edit searches all folders again, once typing
     * pauses; it used to leave the last query's results under the new text,
     * with the All folders button gone, so a second search looked broken.
     */
    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        if (!_state.value.searchMode) return
        if (SearchRules.canSearch(query)) runSearch(SearchRules.DEBOUNCE_MS)
        else endSearch()
    }

    /** All folders: the explicit search, from the button or the keyboard. */
    fun searchEverywhere() {
        if (!SearchRules.canSearch(_state.value.query)) {
            _state.update { it.copy(message = "Enter at least two characters") }
            return
        }
        runSearch(0)
    }

    /**
     * Ask the server, slice by slice, while it says there is more.
     *
     * The folder stays on screen until the first answer arrives. A search
     * that is replaced -- by an edit, another tap, leaving the folder -- is
     * cancelled, so a slow older answer can never land on top of a newer one.
     */
    private fun runSearch(debounceMs: Long) {
        searchJob?.cancel()
        _state.update { it.copy(searchMode = true, searching = true, searchError = null) }
        searchJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val query = _state.value.query.trim()
            var scanned = -1
            var slices = 0
            try {
                while (true) {
                    val found = api.search(query, SearchRules.SCOPE, SearchRules.SLICE_MS)
                    slices++
                    val more = SearchRules.shouldContinue(found, scanned, slices)
                    _state.update {
                        it.copy(searchResults = found.results, searchTruncated = found.truncated,
                            searchScanned = found.scanned, searching = more, selected = emptySet())
                    }
                    if (!more) break
                    scanned = found.scanned
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(searching = false, searchError = e.message ?: "Could not reach the server") }
            }
        }
    }

    private fun endSearch() {
        searchJob?.cancel()
        searchJob = null
        _state.update {
            it.copy(searchMode = false, searching = false, searchResults = null,
                searchTruncated = false, searchScanned = 0, searchError = null)
        }
    }

    fun clearSearch() {
        endSearch()
        _state.update { it.copy(query = "") }
    }

    fun toggleSelected(path: String) = _state.update {
        it.copy(selected = if (path in it.selected) it.selected - path else it.selected + path)
    }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    fun setSort(sort: FilesState.Sort) = _state.update { it.copy(sort = sort) }

    fun setGrid(grid: Boolean) = _state.update { it.copy(grid = grid) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /* ---- favorites -------------------------------------------------------- */

    /**
     * One favorites request at a time, in the order they were made.
     *
     * Two taps are two requests, and on separate connections the server could
     * see the unstar before the star. And a listing fetched while a star is on
     * its way describes the server before that star -- painted over the screen
     * it would put back a file just unstarred.
     */
    private val favoriteTraffic = Mutex()

    /** Bumped by every star and unstar, so a listing can tell it is out of date. */
    private var favoriteEdits = 0

    fun loadFavorites() {
        viewModelScope.launch {
            _state.update { it.copy(favoritesLoad = LoadState.LOADING, favoritesError = null) }
            val edits = favoriteEdits
            try {
                val listing = favoriteTraffic.withLock { api.favorites() }
                // A star changed while this was being fetched. Ask again: the
                // next request queues behind that star's, so it will include it.
                if (edits != favoriteEdits) {
                    loadFavorites()
                    return@launch
                }
                _state.update {
                    it.copy(
                        favoriteEntries = listing.favorites,
                        favorites = listing.favorites.map { entry -> entry.path }.toSet(),
                        favoritesLoad = LoadState.READY,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(favoritesLoad = LoadState.FAILED, favoritesError = e.message ?: "Could not reach the server")
                }
            }
        }
    }

    /**
     * Star or unstar a file.
     *
     * The star changes at once -- it is the whole of the feedback, and a star
     * that waits on the network feels broken -- and is put back, with the
     * reason, if the server refuses.
     */
    fun toggleFavorite(entry: FileEntry) {
        if (entry.isDirectory) return
        val starring = entry.path !in _state.value.favorites
        favoriteEdits++
        _state.update { it.withFavorite(entry, starring) }
        viewModelScope.launch {
            try {
                favoriteTraffic.withLock {
                    if (starring) api.addFavorite(entry.path) else api.removeFavorite(entry.path)
                }
            } catch (e: Exception) {
                _state.update { it.withFavorite(entry, !starring).copy(message = e.message ?: "That did not work") }
                // Where it sat in the list is the server's to say again.
                loadFavorites()
            }
        }
    }

    private fun FilesState.withFavorite(entry: FileEntry, starred: Boolean) = copy(
        favorites = if (starred) favorites + entry.path else favorites - entry.path,
        favoriteEntries = FavoriteRules.afterToggle(favoriteEntries, entry, starred),
    )

    /* ---- actions -------------------------------------------------------- */

    fun makeFolder(name: String) = act("Folder created") {
        api.makeFolder(joinPath(_state.value.path, name))
    }

    fun rename(entry: FileEntry, newName: String) {
        // Confirming the prompt unchanged is not a rename, as in the web client.
        if (!isRename(entry.name, newName)) return
        act(null) {
            val result = api.rename(entry.path, joinPath(entry.path.substringBeforeLast('/', ""), newName))
            // The server picks a free name when the one asked for is taken, and
            // says so; a bare "Renamed" hid that the file now has another name.
            object { val message = result.message.ifBlank { "Renamed" } }
        }
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

        /** Whether a rename prompt's answer asks for anything at all. */
        fun isRename(current: String, requested: String): Boolean =
            requested.isNotBlank() && requested != current
    }
}
