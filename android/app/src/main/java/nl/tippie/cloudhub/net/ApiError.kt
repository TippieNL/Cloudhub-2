package nl.tippie.cloudhub.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Response

/**
 * The server's error envelope, kept as one type so every screen reports
 * failures the same way.
 *
 * CloudHub answers every failed request with
 * {"error":{"code":"...","message":"..."},"requestId":"..."} -- see
 * Http::error() in src/Helpers/Http.php. The message is written for a person,
 * so it is shown as-is rather than replaced with something vaguer.
 */
class ApiError(
    val status: Int,
    val code: String,
    message: String,
    val requestId: String? = null,
) : Exception(message) {

    /** True when signing in again is what the caller should do. */
    val isUnauthorized get() = status == 401

    /** True when the account is signed in but not allowed to do this. */
    val isForbidden get() = status == 403

    /** The store or the caller's quota is full; retrying will not help. */
    val isOutOfSpace get() = status == 507

    companion object {
        @Serializable private data class Envelope(val error: Body? = null, val requestId: String? = null)
        @Serializable private data class Body(val code: String? = null, val message: String? = null)

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Build from a failed response, falling back sensibly when the body is
         * not the envelope -- a reverse proxy's own 502 page, for instance.
         */
        fun from(response: Response, body: String?): ApiError {
            val parsed = body?.takeIf { it.isNotBlank() }?.let {
                runCatching { json.decodeFromString<Envelope>(it) }.getOrNull()
            }
            return ApiError(
                status = response.code,
                code = parsed?.error?.code ?: "HTTP_${response.code}",
                message = parsed?.error?.message ?: defaultMessage(response.code),
                requestId = parsed?.requestId ?: response.header("X-Request-ID"),
            )
        }

        private fun defaultMessage(status: Int) = when (status) {
            401 -> "Please sign in again."
            403 -> "You do not have permission to do that."
            404 -> "That file or folder no longer exists."
            409 -> "Something with that name is already there."
            413 -> "That file is too large for this server."
            419 -> "Your session expired. Please sign in again."
            507 -> "There is not enough space left."
            in 500..599 -> "The server had a problem handling that."
            else -> "The request failed (HTTP $status)."
        }
    }
}
