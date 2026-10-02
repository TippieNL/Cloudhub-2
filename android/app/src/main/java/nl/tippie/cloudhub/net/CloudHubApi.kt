package nl.tippie.cloudhub.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * One function per endpoint the app uses.
 *
 * Every call is addressed in the portable front-controller form the web client
 * uses -- {base}/?route=%2Fapi%2Ffiles%2Flist&path=... -- which
 * Http::requestPath() prefers over a rewritten path. That way the app works
 * whether or not the deployment has URL rewriting configured, which is exactly
 * the sort of difference a self-hosted install varies on.
 */
class CloudHubApi(
    @Volatile var baseUrl: String,
    private val client: CloudHubClient,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /**
     * Public so Coil and the media player can build authenticated URLs.
     *
     * The trailing slash is not cosmetic. Requests go to the front controller,
     * which is a directory -- the web client addresses it the same way, as
     * $frontController in public/index.php. Asking for the directory without
     * the slash makes a web server answer with a 301 to add one, and OkHttp
     * follows that by re-sending the request as a GET with the body dropped
     * (HttpMethod.redirectsToGet, the same rule browsers use). The query
     * survives, so route=/api/auth/login arrives -- as a GET, which matches no
     * route, and the reply is "API endpoint not found" naming an endpoint that
     * plainly exists. Every write broke this way on a subdirectory install:
     * login, upload (PUT) and delete (DELETE) alike.
     *
     * Concatenated rather than addPathSegment(""), which would turn a bare
     * origin -- whose path is already "/" -- into "//".
     */
    fun url(route: String, vararg query: Pair<String, String?>): HttpUrl {
        val front = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val builder = front.toHttpUrl().newBuilder()
            .addQueryParameter("route", route)
        for ((key, value) in query) if (value != null) builder.addQueryParameter(key, value)
        return builder.build()
    }

    /**
     * The thumbnail for a listing row. [large] asks for the 640px one, which
     * the biggest grid sizes need to stay sharp; a server that predates it
     * ignores the parameter and sends the 300px one, which still works.
     */
    fun thumbnailUrl(entry: FileEntry, large: Boolean = false): HttpUrl =
        url("/api/thumbnail", "path" to entry.path, "v" to entry.modified, "size" to if (large) "large" else null)

    /**
     * A thumbnail for a path with no listing row behind it -- the duplicates
     * screen has paths, not entries. No version marker, so the browser cache
     * may serve a stale frame for a replaced file; a 44dp preview beside a
     * name and a folder is not where that matters.
     */
    fun thumbnailUrlFor(path: String): HttpUrl = url("/api/thumbnail", "path" to path)

    fun streamUrl(path: String): HttpUrl = url("/api/files/stream", "path" to path)

    fun downloadUrl(path: String): HttpUrl = url("/api/files/download", "path" to path)

    fun previewUrl(path: String): HttpUrl = url("/api/files/preview", "path" to path)

    fun subtitleUrl(path: String): HttpUrl = url("/api/files/subtitle", "path" to path)

    /**
     * The subtitle files sitting beside a video.
     *
     * Empty for most files, so the caller starts playback first and adds what
     * comes back -- a film must never wait on its subtitles, let alone fail
     * for want of them.
     */
    suspend fun subtitles(path: String): List<SubtitleTrack> =
        get("/api/files/subtitles", "path" to path) { decode<SubtitleListing>(it).tracks }

    /* ---- auth ----------------------------------------------------------- */

    suspend fun status(): AuthStatus = get("/api/auth/status") {
        decode<AuthStatus>(it).also { s -> client.csrfToken = s.csrfToken }
    }

    suspend fun login(username: String, password: String): LoginResult =
        post("/api/auth/login", mapOf("username" to username, "password" to password)) {
            decode<LoginResult>(it).also { r -> client.csrfToken = r.csrfToken }
        }

    suspend fun logout(): SimpleResult = post("/api/auth/logout", emptyMap()) { decode(it) }

    suspend fun config(): ServerConfigInfo = get("/api/files/config") { decode(it) }

    /* ---- storage --------------------------------------------------------- */

    /** This account's own usage. Any signed-in account may read it. */
    suspend fun myStorage(): MyStorage = get("/api/storage/me") { decode(it) }

    /**
     * The whole-server report. Admins only, and the only place a fresh
     * measurement can be asked for -- it walks the entire store.
     */
    suspend fun serverStorage(refresh: Boolean = false): ServerStorage =
        if (refresh) get("/api/storage/usage", "refresh" to "1") { decode(it) }
        else get("/api/storage/usage") { decode(it) }

    /* ---- duplicates -------------------------------------------------------
     *
     * A poll loop, because hashing a media library does not finish inside one
     * request: start a scan, ask it to carry on until it says it is done, and
     * read the last result whenever the screen is opened again.
     *
     * Reading needs only a signed-in account; starting one needs write access,
     * because a scan walks the whole store and reads files.
     */

    /** Start again from the beginning, and do the first slice of the work. */
    suspend fun startDuplicateScan(path: String = "/"): DuplicateScan =
        postJson("/api/duplicates/scan", """{"path":${str(path)},"restart":true}""") { decode(it) }

    /** Carry on. Repeat while the reply says `done` is false. */
    suspend fun continueDuplicateScan(path: String = "/"): DuplicateScan =
        postJson("/api/duplicates/scan", """{"path":${str(path)}}""") { decode(it) }

    /** What the last scan found, without doing any work. */
    suspend fun lastDuplicateScan(): DuplicateScan = get("/api/duplicates/scan") { decode(it) }

    /** Throw the saved scan away. */
    suspend fun forgetDuplicateScan(): SimpleResult =
        request("/api/duplicates/scan", "DELETE", "{}") { decode(it) }

    /**
     * Change the signed-in account's own password.
     *
     * Exempt from the write capability on the server, deliberately: a viewer
     * must be able to rotate their own credentials.
     */
    suspend fun changePassword(current: String, replacement: String): SimpleResult =
        post("/api/users/me/password",
            mapOf("currentPassword" to current, "newPassword" to replacement)) { decode(it) }

    /* ---- browsing -------------------------------------------------------- */

    suspend fun list(path: String): List<FileEntry> =
        get("/api/files/list", "path" to path) { decode(it) }

    /**
     * [budgetMs] asks the server to answer with what it has found after that
     * long; see [SearchResult.incomplete]. A server without the parameter
     * ignores it.
     */
    suspend fun search(query: String, under: String = "/", budgetMs: Int? = null): SearchResult =
        get("/api/files/search", "q" to query, "path" to under, "budget" to budgetMs?.toString()) { decode(it) }

    /* ---- changing things -------------------------------------------------- */

    suspend fun makeFolder(path: String): SimpleResult =
        post("/api/files/mkdir", mapOf("path" to path)) { decode(it) }

    suspend fun rename(from: String, to: String): SimpleResult =
        post("/api/files/rename", mapOf("oldPath" to from, "newPath" to to)) { decode(it) }

    suspend fun move(paths: List<String>, destination: String): RelocateResult =
        postJson("/api/files/move", buildRelocateBody(paths, destination)) { decode(it) }

    /**
     * With [background], a server with a task queue may copy a large selection
     * in the background instead, answering at once with
     * [RelocateResult.queued] and the task -- the same offer the web app
     * makes. Moves are renames, quick whatever their size, so they are never
     * offered.
     */
    suspend fun copy(paths: List<String>, destination: String, background: Boolean = false): RelocateResult =
        postJson("/api/files/copy", buildRelocateBody(paths, destination, offer(background))) { decode(it) }

    /**
     * Goes to the trash unless the server has it disabled; the reply says
     * which. With [background], a permanent delete of a large folder may be
     * queued instead; see [SimpleResult.queued].
     */
    suspend fun delete(path: String, background: Boolean = false): SimpleResult =
        request("/api/files/delete", "DELETE", """{"path":${str(path)}${offer(background)}}""") { decode(it) }

    /* ---- trash ------------------------------------------------------------ */

    suspend fun trash(): TrashListing = get("/api/trash") { decode(it) }

    suspend fun restore(id: String): SimpleResult =
        post("/api/trash/restore", mapOf("id" to id)) { decode(it) }

    suspend fun purge(id: String, background: Boolean = false): SimpleResult =
        postJson("/api/trash/purge", """{"id":${str(id)}${offer(background)}}""") { decode(it) }

    /** With [background], emptying a large trash may be queued as a task; see [SimpleResult.queued]. */
    suspend fun emptyTrash(background: Boolean = false): SimpleResult =
        postJson("/api/trash/purge", """{"all":true${offer(background)}}""") { decode(it) }

    /* ---- background tasks -----------------------------------------------------
     *
     * Cloudhub-web runs long file operations as tasks on the server: they
     * carry on with the app closed, and this account's tasks can be listed,
     * stopped, retried and removed. Cloudhub-2's own server has none of these
     * routes and answers 404, which is how the app knows to offer none of it.
     */

    /** This account's tasks, newest first; [activeOnly] leaves out finished ones. */
    suspend fun tasks(activeOnly: Boolean = false): TaskList =
        get("/api/jobs", "active" to if (activeOnly) "1" else null) { decode(it) }

    suspend fun task(id: String): BackgroundTask = get("/api/jobs/$id") { taskOf(it) }

    /**
     * Queue a task: copy, archive, extract, checksum, thumbnails or
     * duplicates, with the parameters that type takes.
     */
    suspend fun queueTask(type: String, params: JsonObject): BackgroundTask =
        postJson("/api/jobs", buildJsonObject {
            put("type", type)
            put("params", params)
        }.toString()) { taskOf(it) }

    /** Stop a task: at once if it is still queued, at its next checkpoint if it runs. */
    suspend fun cancelTask(id: String): TaskReply = postJson("/api/jobs/$id/cancel", "{}") { decode(it) }

    /** Queue a failed or cancelled task again; a copy carries on where it stopped. */
    suspend fun retryTask(id: String): TaskReply = postJson("/api/jobs/$id/retry", "{}") { decode(it) }

    /** Forget a finished task, and the archive it kept for download. */
    suspend fun removeTask(id: String): SimpleResult = request("/api/jobs/$id", "DELETE", "{}") { decode(it) }

    /** Forget every finished task. */
    suspend fun clearTasks(): TaskReply = postJson("/api/jobs/clear", "{}") { decode(it) }

    /**
     * Ask the web server to work through the queue, where no worker process
     * does it ([TaskList.runner] is "inline"): KSWEB on a phone, typically.
     *
     * The server answers at once and carries on after answering, so this
     * returns in a moment. It goes through a client with a short read timeout
     * all the same: a server that cannot hand its answer back early only sends
     * it once the queue is empty, and the connection would be held for all of
     * that. Giving up on the answer stops nothing -- the server ignores the
     * client going away.
     */
    suspend fun runQueue(): TaskReply = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url("/api/jobs/run"))
            .post("{}".toRequestBody(jsonType))
            .header("X-CSRF-Token", client.csrfToken)
            .build()
        execute(request, kickClient) { decode<TaskReply>(it) }
    }

    /** A finished archive, for streaming to disk; the caller closes the body. */
    suspend fun openTaskDownload(id: String): ResponseBody = open(url("/api/jobs/$id/download"))

    /* ---- favorites ---------------------------------------------------------
     *
     * The account's own stars. Not gated on the write capability -- a viewer
     * keeps favorites too -- but both writes still carry the CSRF token, which
     * request() adds. Starring twice and unstarring what is not starred are
     * both answered with success, so a double tap is never an error.
     */

    suspend fun favorites(): FavoritesListing = get("/api/favorites") { decode(it) }

    suspend fun addFavorite(path: String): FavoriteResult =
        postJson("/api/favorites", """{"path":${str(path)}}""") { decode(it) }

    suspend fun removeFavorite(path: String): FavoriteResult =
        request("/api/favorites", "DELETE", """{"path":${str(path)}}""") { decode(it) }

    /* ---- share links ------------------------------------------------------ */

    suspend fun createShare(path: String, expiresInHours: Int?): ShareLink =
        postJson("/api/shares/create", buildString {
            append("""{"filePath":${json.encodeToString(String.serializer(), path)}""")
            if (expiresInHours != null) append(""","expiresInHours":$expiresInHours""")
            append("}")
        }) { decode(it) }

    /**
     * Revoke by token, not by path.
     *
     * A link outlives the file it points at -- the file can be renamed or
     * moved -- so the token is what identifies it. The route is a DELETE.
     */
    suspend fun revokeShare(token: String): SimpleResult =
        request("/api/shares/revoke", "DELETE", """{"token":${str(token)}}""") { decode(it) }

    /* ---- uploads ---------------------------------------------------------- */

    suspend fun uploadInit(id: String, targetPath: String, name: String, size: Long): UploadStatus =
        postJson("/api/uploads/init", """
            {"uploadId":${str(id)},"targetPath":${str(targetPath)},
             "name":${str(name)},"size":$size,"conflict":"rename"}
        """.trimIndent()) { decode(it) }

    suspend fun uploadStatus(id: String): UploadStatus =
        get("/api/uploads/status", "id" to id) { decode(it) }

    /**
     * Send one chunk from the given offset.
     *
     * The body is streamed from the file rather than read into memory: a phone
     * uploading a 4 GB video must not need 4 GB of heap.
     */
    suspend fun uploadChunk(id: String, offset: Long, body: RequestBody): UploadStatus =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url("/api/uploads/chunk", "id" to id))
                .put(body)
                .header("X-Upload-Offset", offset.toString())
                .header("X-CSRF-Token", client.csrfToken)
                .build()
            execute(request) { decode<UploadStatus>(it) }
        }

    suspend fun uploadComplete(id: String): UploadComplete =
        post("/api/uploads/complete", mapOf("id" to id)) { decode(it) }

    suspend fun uploadCancel(id: String): SimpleResult =
        request("/api/uploads/cancel", "DELETE", """{"id":${str(id)}}""") { decode(it) }

    /* ---- raw bytes --------------------------------------------------------- */

    /** Opens a download for streaming to disk; the caller closes the body. */
    suspend fun openDownload(path: String): ResponseBody = open(downloadUrl(path))

    private suspend fun open(url: HttpUrl): ResponseBody = withContext(Dispatchers.IO) {
        val response = client.okHttp.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) {
            val body = response.body?.string()
            response.close()
            throw ApiError.from(response, body)
        }
        response.body ?: throw ApiError(response.code, "EMPTY", "The server sent no content.")
    }

    /** Hand a locally decoded video frame back, so the web app benefits too. */
    suspend fun contributeVideoThumbnail(path: String, webpBase64: String): SimpleResult =
        postJson("/api/thumbnail/video",
            """{"path":${str(path)},"image":${str(webpBase64)}}""") { decode(it) }

    /* ---- plumbing ---------------------------------------------------------- */

    private fun str(value: String) = json.encodeToString(String.serializer(), value)

    private fun buildRelocateBody(paths: List<String>, destination: String, extra: String = "") = buildString {
        append("""{"destination":${str(destination)},"paths":[""")
        append(paths.joinToString(",") { str(it) })
        append("]")
        append(extra)
        append("}")
    }

    /** A task's reply is {"job": {...}}; a reply without one is not a task. */
    private fun taskOf(body: String): BackgroundTask =
        decode<TaskReply>(body).job ?: throw ApiError(500, "NO_TASK", "The server did not return the task.")

    /** The same connections and cookies as every other call; see [runQueue] for the timeout. */
    private val kickClient by lazy {
        client.okHttp.newBuilder().readTimeout(15, TimeUnit.SECONDS).build()
    }

    private inline fun <reified T> decode(body: String): T =
        json.decodeFromString(body)

    private suspend fun <T> get(
        route: String,
        vararg query: Pair<String, String?>,
        parse: (String) -> T,
    ): T = withContext(Dispatchers.IO) {
        execute(Request.Builder().url(url(route, *query)).get().build(), parse = parse)
    }

    private suspend fun <T> post(route: String, body: Map<String, String>, parse: (String) -> T): T =
        postJson(route, json.encodeToString(MapSerializer(String.serializer(), String.serializer()), body), parse)

    private suspend fun <T> postJson(route: String, body: String, parse: (String) -> T): T =
        request(route, "POST", body, parse)

    private suspend fun <T> request(
        route: String,
        method: String,
        body: String,
        parse: (String) -> T,
    ): T = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url(route))
            .method(method, body.toRequestBody(jsonType))
            // Every mutating route verifies this; see the guard in
            // public/index.php that calls Auth::verifyCsrf().
            .header("X-CSRF-Token", client.csrfToken)
            .build()
        execute(request, parse = parse)
    }

    private fun <T> execute(
        request: Request,
        http: okhttp3.OkHttpClient = client.okHttp,
        parse: (String) -> T,
    ): T {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // A redirect that quietly turned a write into a GET surfaces as
                // a 404 for an endpoint that exists, which tells the user
                // nothing they can act on. Name the real problem instead.
                if (response.priorResponse != null &&
                    request.method != "GET" && response.request.method == "GET"
                ) {
                    throw ApiError(
                        status = response.code,
                        code = "REDIRECTED",
                        message = "The server redirected this request, which drops it. " +
                            "Check the server address is exactly right.",
                        requestId = response.header("X-Request-ID"),
                    )
                }
                throw ApiError.from(response, body)
            }
            return parse(body)
        }
    }

    /**
     * "Do this in the background if it is large", for a copy, delete or
     * purge. The server decides -- it knows how big the work is and whether
     * anything runs its queue.
     *
     * Only offered where the server has shown it has a queue it can use
     * (TaskCenter.State.available). A Cloudhub-web that took the queue
     * without its jobs table fails the offer with a 500 on anything large,
     * where without the key it does the work in the request as it always
     * did; a server with no queue at all ignores the key either way.
     */
    private fun offer(background: Boolean) = if (background) ",\"background\":\"auto\"" else ""
}
