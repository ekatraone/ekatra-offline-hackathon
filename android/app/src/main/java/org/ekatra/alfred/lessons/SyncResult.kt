package org.ekatra.alfred.lessons

/** Outcome of one call to the learner sync API. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    /** 401: the token is missing, invalid or expired. Retrying will not help. */
    data class Unauthorized(val message: String) : ApiResult<Nothing>

    /** Network error, timeout, 5xx or 429: keep the queue item and try again later. */
    data class Retryable(val message: String) : ApiResult<Nothing>

    /** 400/404/422 and other 4xx: the server will never accept this payload. */
    data class Rejected(val code: Int, val message: String) : ApiResult<Nothing>
}

/** Where the learner API lives and who we are. Both are blank in release builds. */
data class SyncConfig(val baseUrl: String, val token: String) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()
}
