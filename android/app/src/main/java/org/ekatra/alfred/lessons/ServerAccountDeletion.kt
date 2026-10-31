package org.ekatra.alfred.lessons

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/** The server-side half of account deletion. */
interface LearnerAccountRemote {
    /**
     * Erase this learner on the server before the Firebase user goes. Never throws and never blocks the
     * deletion: if it cannot be done now it is recorded and retried later.
     */
    suspend fun eraseLearner()
}

/** For tests and callers with no lessons server. */
object NoLearnerAccountRemote : LearnerAccountRemote {
    override suspend fun eraseLearner() = Unit
}

/**
 * Erases the learner on the Ekatra server when an account is deleted, and retries if that could not be sent.
 *
 * Two routes, because the server needs to know who to erase:
 *  - **Learner jwt** (`DELETE /api/sync/me`), when this phone exchanged one. The token is kept in a pending
 *    SET until the server answers 200 (erased) or 401 (no longer valid), so a second deletion can never drop
 *    the first one's token, and a retry always aims at that account's token, not at whoever signs in later.
 *  - **Firebase ID token** (`POST /api/sync/auth/account/delete`, a POST because Android can refuse a DELETE with a body), when there is no jwt but a Firebase user (they
 *    never opened Lessons here). An ID token lasts an hour, so it is never stored: a retry fetches a fresh
 *    one, and only while that same user still exists. Once the user is gone it cannot be retried.
 *
 * The shared debug token is never erased.
 */
@Singleton
class ServerAccountDeletion @Inject constructor(
    private val api: LearnerSyncApi,
    private val config: SyncConfig,
    private val store: TokenStore,
    private val connectivity: Connectivity,
    private val firebase: FirebaseSession
) : LearnerAccountRemote {

    private companion object { const val TAG = "ServerAccountDeletion" }

    private enum class Attempt { DONE, TOKEN_REJECTED, LATER }

    override suspend fun eraseLearner() {
        if (config.hasStoredToken) {
            val token = config.token
            when (attemptWithToken(token)) {
                Attempt.DONE -> Unit
                Attempt.LATER -> store.addPendingDeleteToken(token)
                // The jwt is no good any more, so it cannot erase anything. If the person is still signed in
                // to Firebase, their ID token can: do not leave their server data behind.
                Attempt.TOKEN_REJECTED -> if (firebase.hasUser()) eraseByIdToken()
            }
        } else if (firebase.hasUser()) {
            eraseByIdToken()
        }
    }

    /** Called on app start and from background sync. A no-op unless an erase is waiting. */
    suspend fun retryPending() {
        for (token in store.pendingDeleteTokens()) {
            if (attemptWithToken(token) != Attempt.LATER) store.removePendingDeleteToken(token)
        }
        store.pendingIdEraseUid()?.let { retryIdErase(it) }
    }

    val hasPending: Boolean get() = store.pendingDeleteTokens().isNotEmpty() || store.pendingIdEraseUid() != null

    private suspend fun attemptWithToken(token: String): Attempt {
        if (!connectivity.isOnline()) return Attempt.LATER
        return when (api.deleteMe(token)) {
            DeleteOutcome.Deleted -> Attempt.DONE
            DeleteOutcome.Unauthorized -> Attempt.TOKEN_REJECTED
            is DeleteOutcome.Failed, DeleteOutcome.NotConfigured -> Attempt.LATER
        }
    }

    private suspend fun eraseByIdToken() {
        val uid = firebase.uid()
        if (!sendIdErase()) {
            if (uid != null) store.setPendingIdEraseUid(uid)
        }
    }

    private suspend fun retryIdErase(pendingUid: String) {
        // Only for the same Firebase user: an ID token can only ever be for whoever is signed in right now.
        if (!firebase.hasUser() || firebase.uid() != pendingUid) {
            Log.w(TAG, "Server erase by ID token abandoned: that Firebase user is no longer signed in")
            store.setPendingIdEraseUid(null)
            return
        }
        if (sendIdErase()) store.setPendingIdEraseUid(null)
    }

    /**
     * @return true if the server has dealt with it: 200 (`deleted` true or false, both mean nothing is left),
     * or a 401 that persisted after a second, freshly fetched ID token (it will not take this user's token, so
     * there is nothing more to try).
     */
    private suspend fun sendIdErase(): Boolean {
        if (!connectivity.isOnline()) return false
        repeat(2) { attempt ->
            val idToken = firebase.idToken(forceRefresh = true) ?: return false  // always fresh: they expire in an hour
            when (api.deleteAccountByIdToken(idToken)) {
                DeleteOutcome.Deleted -> return true
                DeleteOutcome.Unauthorized -> if (attempt == 1) return true       // still 401 on a fresh token: give up
                is DeleteOutcome.Failed, DeleteOutcome.NotConfigured -> return false // 429, 503 and the like: later
            }
        }
        return true
    }
}
