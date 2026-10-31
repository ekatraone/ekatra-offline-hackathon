package org.ekatra.alfred.lessons

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

/**
 * Where the learner API lives and who we are. Read on every call, because the token can change while
 * the app runs: a tester joins, signs out, or has an expired token cleared.
 *
 * Token precedence: the stored token (exchanged after a Firebase sign-in), then the build-time
 * `DEMO_LEARNER_TOKEN`, then none.
 * Both the base URL and the build-time token are blank in release builds.
 */
class SyncConfig(
    val baseUrl: String,
    private val buildToken: String,
    private val store: TokenStore? = null
) {
    private val _changes = MutableStateFlow(0)
    /** Bumps whenever the stored token is set or cleared, so screens re-read the configuration. */
    val changes: StateFlow<Int> = _changes

    private fun storedToken(): String? = store?.token()?.takeIf { it.isNotBlank() }

    val token: String get() = storedToken() ?: buildToken

    /** True when the token in use is the stored, exchanged one (not the build-time one). */
    val hasStoredToken: Boolean get() = storedToken() != null

    val isConfigured: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()

    /** A server is known but there is no token yet. */
    val needsToken: Boolean get() = baseUrl.isNotBlank() && token.isBlank()

    /** The learner the stored token belongs to, kept across an expiry so a different learner can be detected. */
    val learnerId: String? get() = store?.learnerId()

    fun saveToken(token: String, learnerId: String?) {
        store?.save(token, learnerId)
        _changes.value += 1
    }

    /** Forget the token (expiry). The learner id stays so a re-join as someone else is noticed. */
    fun clearToken() {
        store?.clearToken()
        _changes.value += 1
    }

    /** Forget the token and the learner id (sign out). */
    fun clearAll() {
        store?.clearAll()
        _changes.value += 1
    }
}
