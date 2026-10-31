package org.ekatra.alfred.lessons

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What the sync manager needs from whoever holds the learner token. */
interface TokenRefresher {
    /**
     * Make sure a usable token is stored if one can be had: exchange after a Firebase sign-in, or again when
     * the stored JWT is near expiry. Only ever talks to the network when online.
     * @return true if a token is available afterwards.
     */
    suspend fun ensureToken(): Boolean

    /** The server just answered 401. Get a fresh token from Firebase. @return true if a new one was stored. */
    suspend fun refreshAfter401(): Boolean
}

sealed interface ExchangeResult {
    data object Done : ExchangeResult
    data object NoFirebaseUser : ExchangeResult
    /** The Lessons notice has not been accepted, so nothing is sent. */
    data object ConsentNeeded : ExchangeResult
    data object Offline : ExchangeResult
    /** Firebase no longer vouches for this user. */
    data object Unauthorized : ExchangeResult
    data class Failed(val message: String) : ExchangeResult
}

/**
 * Turns a Firebase sign-in into the learner JWT the lessons API wants, and keeps it fresh.
 *
 * Preference order (see [SyncConfig]): the exchanged token stored here, then the build-time debug token.
 * A stored JWT keeps lessons working offline, so nothing here is attempted without a connection.
 */
@Singleton
class LearnerTokenProvider @Inject constructor(
    private val api: LearnerSyncApi,
    private val firebase: FirebaseSession,
    private val config: SyncConfig,
    private val connectivity: Connectivity,
    private val localData: LocalLearnerData,
    private val consent: LessonsConsent
) : TokenRefresher {

    companion object {
        /** Exchange again when the JWT has this long or less to live. */
        const val REFRESH_WINDOW_MS = 3L * 24 * 60 * 60 * 1000
    }

    internal var now: () -> Long = { System.currentTimeMillis() }
    private val lock = Mutex()

    fun isNearExpiry(token: String): Boolean {
        val exp = jwtExpiryMillis(token) ?: return false // opaque tokens carry no expiry we can read
        return exp - now() <= REFRESH_WINDOW_MS
    }

    override suspend fun ensureToken(): Boolean {
        // No exchange (and so no server learner record) before the Lessons notice is accepted.
        if (!consent.isAccepted()) return config.isConfigured
        val stored = if (config.hasStoredToken) config.token else null
        if (stored != null && !isNearExpiry(stored)) return config.isConfigured
        // Offline, or nobody signed in: keep whatever we have (a near-expiry JWT still works until it expires).
        if (!connectivity.isOnline() || !firebase.hasUser()) return config.isConfigured
        exchange()
        return config.isConfigured
    }

    /** Exchange the current Firebase user for a learner JWT and store it. Call after any Firebase sign-in. */
    suspend fun exchange(): ExchangeResult = lock.withLock {
        if (!consent.isAccepted()) return ExchangeResult.ConsentNeeded
        if (!firebase.hasUser()) return ExchangeResult.NoFirebaseUser
        if (!connectivity.isOnline()) return ExchangeResult.Offline

        var idToken = firebase.idToken(forceRefresh = false) ?: return ExchangeResult.NoFirebaseUser
        var outcome = api.exchange(idToken)
        if (outcome is ExchangeOutcome.Unauthorized) {
            // The cached ID token may just have expired: ask Firebase for a fresh one, once.
            idToken = firebase.idToken(forceRefresh = true) ?: return ExchangeResult.NoFirebaseUser
            outcome = api.exchange(idToken)
        }
        when (outcome) {
            is ExchangeOutcome.Success -> { store(outcome); ExchangeResult.Done }
            ExchangeOutcome.Unauthorized -> ExchangeResult.Unauthorized
            is ExchangeOutcome.Failed -> ExchangeResult.Failed(outcome.message)
            ExchangeOutcome.NotConfigured -> ExchangeResult.Failed("Lessons are not set up on this build")
        }
    }

    override suspend fun refreshAfter401(): Boolean = lock.withLock {
        if (!consent.isAccepted() || !firebase.hasUser() || !connectivity.isOnline()) return false
        // The token the server refused is no use, and the cached ID token may be stale too: force a new one.
        val idToken = firebase.idToken(forceRefresh = true) ?: return false
        val outcome = api.exchange(idToken)
        if (outcome is ExchangeOutcome.Success) { store(outcome); true } else false
    }

    private suspend fun store(outcome: ExchangeOutcome.Success) {
        val previous = config.learnerId
        // A different person on this phone: the last learner's lessons and unsynced answers must not
        // be shown to, or synced into, the new profile.
        if (previous != null && outcome.learnerId != null && previous != outcome.learnerId) localData.clear()
        // SharedPreferences.commit() is a blocking disk write.
        withContext(Dispatchers.IO) { config.saveToken(outcome.token, outcome.learnerId) }
    }
}

/** What the sign-in screens call after Firebase signs a user in or out. */
interface LearnerSessionHooks {
    /** Exchange for a learner token in the background. Never throws. */
    suspend fun afterSignIn()

    /** Clear the learner token and the local lesson data. */
    suspend fun afterSignOut()
}

@Singleton
class LearnerSession @Inject constructor(
    private val provider: LearnerTokenProvider,
    private val manager: LearnerSyncManager
) : LearnerSessionHooks {
    override suspend fun afterSignIn() {
        runCatching { provider.exchange() }
    }

    override suspend fun afterSignOut() {
        runCatching { manager.signOut() }
    }
}
