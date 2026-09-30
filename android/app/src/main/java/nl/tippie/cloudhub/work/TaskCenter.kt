package nl.tippie.cloudhub.work

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import nl.tippie.cloudhub.net.ApiError
import nl.tippie.cloudhub.net.BackgroundTask
import nl.tippie.cloudhub.net.CloudHubApi
import nl.tippie.cloudhub.net.TaskList
import nl.tippie.cloudhub.ui.TaskRules
import java.io.InputStream

/**
 * This account's background tasks on the server, followed for the whole app.
 *
 * Cloudhub-web runs long file operations as tasks: a large copy, a ZIP of a
 * folder, unpacking an archive, emptying a big trash, a duplicate scan. They
 * run on the server and carry on with the app closed; this is how the app
 * starts them, watches them and says when they are done.
 *
 * One instance, held by CloudHubApp, so the badge on the Files screen, the
 * Tasks screen and every action that queues something share one list, and a
 * task started on one screen is followed wherever the user goes next. It
 * looks at the server only while something is queued or running and stops
 * when nothing is -- a phone polling an idle queue pays battery for nothing.
 * In the background it stops too, unless a ZIP the user asked for is still
 * being made: that one is fetched as soon as it is ready.
 *
 * Everything here runs on one thread -- the main one, in the app -- and the
 * network calls move themselves off it, so the sets below need no locking.
 */
class TaskCenter(
    private val api: CloudHubApi,
    /** Where a finished archive is written: the phone's Downloads folder. Called off the main thread. */
    private val save: (name: String, input: InputStream) -> Unit,
    private val scope: CoroutineScope = MainScope(),
) {

    data class State(
        /** Null until the server has been asked; false where it has no task queue at all. */
        val supported: Boolean? = null,
        val list: TaskList = TaskList(),
        /** Whether [list] holds finished tasks too, or only those still queued or running. */
        val complete: Boolean = false,
        /** Why the last look failed. The list stays as it was. */
        val error: String? = null,
    ) {
        /**
         * Tasks can be started. What every task action is offered on: a server
         * without a queue, or with one it cannot use, gets the app as it was.
         */
        val available get() = supported == true && list.available
        val active get() = list.active
        val tasks get() = list.jobs
    }

    sealed interface Event {
        /** Something to say in passing. */
        data class Message(val text: String) : Event
        /** A task that changes the store finished: the folder on screen may be out of date. */
        data object FilesChanged : Event
        /** Checksums asked for on this phone are ready to be read. */
        data class Checksums(val task: BackgroundTask) : Event
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Delivered once each. A channel rather than a shared flow so that what
     * happens while nobody is collecting -- the app in the background -- is
     * said when it comes back rather than dropped.
     */
    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    /** Tasks seen queued or running; one that stops being either has finished. */
    private val watched = mutableSetOf<String>()
    /** Archives to bring to the phone as soon as they are ready. */
    private val autoDownloads = mutableSetOf<String>()
    /** Checksums to show as soon as they are ready. */
    private val showChecksums = mutableSetOf<String>()

    private var loop: Job? = null
    private var signedIn = false
    private var foreground = true
    /** The Tasks screen is showing, so finished tasks are listed too. */
    private var screenOpen = false
    private var lastKick = 0L

    /** A new sign-in: forget the last account's tasks, and look for this one's. */
    fun start() {
        reset()
        signedIn = true
        look()
    }

    /** Signed out, or about to sign in as somebody else. */
    fun reset() {
        loop?.cancel()
        loop = null
        signedIn = false
        watched.clear()
        autoDownloads.clear()
        showChecksums.clear()
        _state.value = State()
    }

    /** Look now, and keep looking while anything is queued or running. */
    fun refresh() = look()

    fun setScreenOpen(open: Boolean) {
        screenOpen = open
        if (open) look()
    }

    /** The app came to the front, or left it. */
    fun setForeground(value: Boolean) {
        foreground = value
        // Tasks may have finished while the app was away.
        if (value) look()
        else if (autoDownloads.isEmpty()) {
            loop?.cancel()
            loop = null
        }
    }

    /**
     * Follow a task the server has queued: one from [queue], or a copy,
     * delete or purge the server chose to do in the background.
     */
    fun follow(task: BackgroundTask, download: Boolean = false, checksums: Boolean = false) {
        watched += task.id
        if (download) autoDownloads += task.id
        if (checksums) showChecksums += task.id
        // On the badge at once, before the next look confirms it.
        _state.update {
            val others = it.list.jobs.filter { job -> job.id != task.id }
            it.copy(list = it.list.copy(jobs = listOf(task) + others, active = others.count(TaskRules::isActive) + 1))
        }
        kick(force = true)
        look()
    }

    /**
     * Queue a task, and follow it. Throws the server's refusal, which says
     * why in words meant for the user: too many tasks already, three ZIPs
     * waiting to be downloaded, a scan already running.
     */
    suspend fun queue(
        type: String,
        params: JsonObject,
        download: Boolean = false,
        checksums: Boolean = false,
    ): BackgroundTask {
        val task = guarded { api.queueTask(type, params) }
        follow(task, download, checksums)
        return task
    }

    /** Stop a task; says what happened, which for a running one is "stopping". */
    suspend fun cancel(id: String): String {
        val reply = guarded { api.cancelTask(id) }
        look()
        return if (reply.status == "cancelled") "Cancelled" else "Stopping…"
    }

    suspend fun retry(id: String) {
        val reply = guarded { api.retryTask(id) }
        reply.job?.let { follow(it) } ?: look()
    }

    suspend fun remove(id: String) {
        guarded { api.removeTask(id) }
        autoDownloads -= id
        look()
    }

    /** Forget every finished task; answers how many went. */
    suspend fun clear(): Int {
        val reply = guarded { api.clearTasks() }
        look()
        return reply.removed
    }

    /**
     * Save a finished archive to the phone's Downloads folder.
     *
     * [removeAfter] is for a ZIP fetched as soon as it was ready: once it is
     * safely on the phone the server's copy is only in the way -- an account
     * may keep three waiting -- so the task goes with it. One downloaded from
     * the Tasks screen by hand stays until it is removed.
     */
    fun download(task: BackgroundTask, removeAfter: Boolean = false) {
        val name = TaskRules.downloadName(task)
        scope.launch {
            say("Downloading $name…")
            val outcome = try {
                withContext(Dispatchers.IO) {
                    api.openTaskDownload(task.id).use { body -> save(name, body.byteStream()) }
                }
                if (removeAfter) runCatching { api.removeTask(task.id) }
                "Saved $name to Downloads"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Could not download $name: ${e.message}"
            }
            say(outcome)
            if (removeAfter) look()
        }
    }

    /** Whether it is following anything now; for the tests. */
    internal val looking get() = loop?.isActive == true

    /* ---- looking ------------------------------------------------------------- */

    private fun look() {
        if (!signedIn) return
        loop?.cancel()
        loop = scope.launch {
            while (true) {
                val next = lookOnce() ?: break
                delay(next)
            }
        }
    }

    /** One look at the server; answers how long until the next, or null for none. */
    private suspend fun lookOnce(): Long? {
        val all = screenOpen
        val list = try {
            api.tasks(activeOnly = !all)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            // Cloudhub-2's own server, which has no queue: nothing to offer,
            // and nothing to ask again.
            if (e.status == 404 && _state.value.supported != true) {
                _state.value = State(supported = false)
                return null
            }
            if (e.isUnauthorized) return null
            _state.update { it.copy(error = e.message) }
            return TaskRules.nextPollMs(null, failed = true, watching = watching())
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Could not reach the server") }
            return TaskRules.nextPollMs(null, failed = true, watching = watching())
        }

        _state.value = State(supported = true, list = list, complete = all)

        // Everything queued or running is watched, whoever queued it and from
        // where, as on the web: a copy started there is announced here too.
        val active = list.jobs.filter(TaskRules::isActive).map { it.id }.toSet()
        watched += active
        // One that is not any more has finished; how, the task itself says.
        // It stays watched until that has been read, so a look that fails
        // halfway loses no announcement and no download.
        var unread = false
        for (id in watched - active) {
            val task = list.jobs.firstOrNull { it.id == id } ?: try {
                api.task(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                // Removed meanwhile, from another device: nothing to say,
                // and nothing to fetch.
                if (e.status == 404) {
                    watched -= id
                    autoDownloads -= id
                    showChecksums -= id
                } else {
                    unread = true
                }
                continue
            } catch (e: Exception) {
                unread = true
                continue
            }
            if (TaskRules.isActive(task)) continue
            watched -= id
            finish(task)
        }
        // A ZIP asked for here that is already done -- it finished while the
        // app was not looking, so it was never seen running.
        for (task in list.jobs) {
            if (task.hasDownload && autoDownloads.remove(task.id)) download(task, removeAfter = true)
        }

        if (TaskRules.shouldKick(list)) kick()
        if (!foreground && autoDownloads.isEmpty()) return null
        return TaskRules.nextPollMs(list, failed = false, watching = watching())
            ?: if (unread) TaskRules.RETRY_POLL_MS else null
    }

    private fun finish(task: BackgroundTask) {
        val fetch = autoDownloads.remove(task.id)
        val sums = showChecksums.remove(task.id)
        when {
            // Each of these says so itself: the download when it lands, the
            // checksums in the dialog they open.
            fetch && task.hasDownload -> download(task, removeAfter = true)
            sums && task.status == TaskRules.COMPLETED -> _events.trySend(Event.Checksums(task))
            else -> TaskRules.finishedMessage(task)?.let(::say)
        }
        if (task.type in TaskRules.CHANGES_FILES) _events.trySend(Event.FilesChanged)
    }

    /**
     * Ask the web server to work through the queue. Only the "inline" runner
     * needs asking; [force] asks whatever the runner, for a task just queued
     * -- the server answers "not running" when it has a runner of its own.
     */
    private fun kick(force: Boolean = false) {
        if (!force && _state.value.list.runner != "inline") return
        val now = System.currentTimeMillis()
        if (!force && now - lastKick < TaskRules.KICK_INTERVAL_MS) return
        lastKick = now
        scope.launch { runCatching { api.runQueue() } }
    }

    private fun watching() = watched.isNotEmpty() || autoDownloads.isNotEmpty()

    private fun say(text: String) {
        _events.trySend(Event.Message(text))
    }

    /**
     * A task call, noting a server that turns out to have no usable queue:
     * it answers 503 QUEUE_UNAVAILABLE, and every task action goes away.
     */
    private suspend fun <T> guarded(call: suspend () -> T): T = try {
        call()
    } catch (e: ApiError) {
        if (e.code == "QUEUE_UNAVAILABLE") {
            _state.update { it.copy(list = it.list.copy(available = false, message = e.message)) }
        }
        throw e
    }
}
