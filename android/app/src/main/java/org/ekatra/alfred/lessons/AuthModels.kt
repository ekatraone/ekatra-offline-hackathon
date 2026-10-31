package org.ekatra.alfred.lessons

/** `POST /api/sync/auth/exchange`: a Firebase ID token in, a learner JWT out. */
sealed interface ExchangeOutcome {
    data class Success(val token: String, val learnerId: String?, val name: String?, val phone: String?, val email: String?) : ExchangeOutcome
    /** 401: the ID token is not valid (expired, revoked, or the user is gone). */
    data object Unauthorized : ExchangeOutcome
    /** Network error, 5xx, 429 or a reply that was not from our API: try again later. */
    data class Failed(val message: String) : ExchangeOutcome
    data object NotConfigured : ExchangeOutcome
}

/** `POST /api/auth/whatsapp/start`. */
sealed interface StartOutcome {
    data class Sent(val resendAfterSeconds: Int, val expiresInSeconds: Int) : StartOutcome
    /** 400: not a phone number the server accepts. */
    data class BadPhone(val message: String) : StartOutcome
    /** 429. */
    data object RateLimited : StartOutcome
    data class NetworkError(val message: String) : StartOutcome
    data object NotConfigured : StartOutcome
}

/** `POST /api/auth/whatsapp/verify`. */
sealed interface VerifyOutcome {
    data class Verified(val firebaseCustomToken: String) : VerifyOutcome
    /** 400: the code is malformed. */
    data object MalformedCode : VerifyOutcome
    /** 401: wrong or expired code. */
    data object WrongCode : VerifyOutcome
    /** 429: too many wrong attempts on this code. */
    data object RateLimited : VerifyOutcome
    data class NetworkError(val message: String) : VerifyOutcome
    data object NotConfigured : VerifyOutcome
}

data class ExchangeResponse(val token: String? = null, val learner: MeLearner? = null)
data class StartResponse(val resendAfterSeconds: Int? = null, val expiresInSeconds: Int? = null)
data class VerifyResponse(val firebaseCustomToken: String? = null)
