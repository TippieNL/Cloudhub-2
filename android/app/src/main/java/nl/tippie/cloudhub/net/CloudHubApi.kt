package nl.tippie.cloudhub.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.InputStream

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

    /** Public so Coil and the media player can build authenticated URLs. */
    fun url(route: String, vararg query: Pair<String, String?>): HttpUrl {
        val builder = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("route", route)
        for ((key, value) in query) if (value != null) builder.addQueryParameter(key, value)
        return builder.build()
    }

    fun thumbnailUrl(entry: FileEntry): HttpUrl =
        url("/api/thumbnail", "path" to entry.path, "v" to entry.modified)

    fun streamUrl(path: String): HttpUrl = url("/api/files/stream", "path" to path)

    fun downloadUrl(path: String): HttpUrl = url("/api/files/download", "path" to path)

    fun previewUrl(path: String): HttpUrl = url("/api/files/preview", "path" to path)

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

    /* ---- browsing -------------------------------------------------------- */

    suspend fun list(path: String): List<FileEntry> =
        get("/api/files/list", "path" to path) { decode(it) }

    suspend fun search(query: String, under: String = "/"): SearchResult =
        get("/api/files/search", "q" to query, "path" to under) { decode(it) }

    /* ---- changing things -------------------------------------------------- */

    suspend fun makeFolder(path: String): SimpleResult =
        post("/api/files/mkdir", mapOf("path" to path)) { decode(it) }

    suspend fun rename(from: String, to: String): SimpleResult =
        post("/api/files/rename", mapOf("oldPath" to from, "newPath" to to)) { decode(it) }

    suspend fun move(paths: List<String>, destination: String): RelocateResult =
        postJson("/api/files/move", buildRelocateBody(paths, destination)) { decode(it) }

    suspend fun copy(paths: List<String>, destination: String): RelocateResult =
        postJson("/api/files/copy", buildRelocateBody(paths, destination)) { decode(it) }

    /** Goes to the trash unless the server has it disabled; the reply says which. */
    suspend fun delete(path: String): SimpleResult =
        request("/api/files/delete", "DELETE", """{"path":${str(path)}}""") { decode(it) }

    /* ---- trash ------------------------------------------------------------ */

    suspend fun trash(): TrashListing = get("/api/trash") { decode(it) }

    suspend fun restore(id: String): SimpleResult =
        post("/api/trash/restore", mapOf("id" to id)) { decode(it) }

    suspend fun purge(id: String): SimpleResult =
        post("/api/trash/purge", mapOf("id" to id)) { decode(it) }

    suspend fun emptyTrash(): SimpleResult =
        postJson("/api/trash/purge", """{"all":true}""") { decode(it) }

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
    suspend fun openDownload(path: String): ResponseBody = withContext(Dispatchers.IO) {
        val response = client.okHttp.newCall(Request.Builder().url(downloadUrl(path)).build()).execute()
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

    private fun buildRelocateBody(paths: List<String>, destination: String) = buildString {
        append("""{"destination":${str(destination)},"paths":[""")
        append(paths.joinToString(",") { str(it) })
        append("]}")
    }

    private inline fun <reified T> decode(body: String): T =
        json.decodeFromString(body)

    private suspend fun <T> get(
        route: String,
        vararg query: Pair<String, String?>,
        parse: (String) -> T,
    ): T = withContext(Dispatchers.IO) {
        execute(Request.Builder().url(url(route, *query)).get().build(), parse)
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
        execute(request, parse)
    }

    private fun <T> execute(request: Request, parse: (String) -> T): T {
        client.okHttp.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiError.from(response, body)
            return parse(body)
        }
    }
}
