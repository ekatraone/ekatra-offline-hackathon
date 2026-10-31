package org.ekatra.alfred.lessons

/** The server-side half of account deletion. */
interface LearnerAccountRemote {
    /**
     * Erase this person's learner record on the Ekatra server. Account deletion is all-or-nothing: the Firebase
     * user and the local data are deleted only if this returns true, because once the Firebase user is gone the
     * server can no longer be told who to erase.
     *
     * @return true if the server holds nothing for this account any more (or never could: no lessons server on
     *   this build, nobody signed in, nothing to erase); false if it could not be confirmed (offline, 429, 5xx).
     */
    suspend fun eraseLearner(): Boolean
}

/** For tests and callers with no lessons server. */
object NoLearnerAccountRemote : LearnerAccountRemote {
    override suspend fun eraseLearner(): Boolean = true
}

/**
 * Erases the learner on the Ekatra server before the Firebase user is deleted, by whichever credential exists:
 *  - the **learner jwt** (`DELETE /api/sync/me`, no body), when this phone exchanged one;
 *  - otherwise the **Firebase ID token** (`POST /api/sync/auth/account/delete`, a POST because Android can refuse
 *    a DELETE with a body), always a freshly fetched one since they expire in an hour. A 401 on the jwt falls back
 *    to this route if the person is still signed in, so their data is not left behind.
 *
 * It returns true only once the server has answered 200 (`deleted:false` counts: nothing left either way). Nothing
 * is queued for later: an ID token cannot be kept, and once the Firebase user is gone there is no way to say who
 * to erase, so a failure stops the deletion instead. The shared debug token is never erased.
 *
 * @param lessonsEnabled false on a persona without Lessons (Asmaa): there is nothing on the server to erase.
 */
class ServerAccountDeletion(
    private val api: LearnerSyncApi,
    private val config: SyncConfig,
    private val connectivity: Connectivity,
    private val firebase: FirebaseSession,
    private val lessonsEnabled: Boolean
) : LearnerAccountRemote {

    override suspend fun eraseLearner(): Boolean {
        // No Lessons or no server on this build: nothing could be there, so never block deleting the account.
        if (!lessonsEnabled || config.baseUrl.isBlank()) return true

        val byJwt = config.hasStoredToken
        val byIdToken = firebase.hasUser()
        if (!byJwt && !byIdToken) return true            // nobody to erase
        if (!connectivity.isOnline()) return false       // something to erase, and no way to reach the server

        if (byJwt) {
            when (api.deleteMe(config.token)) {
                DeleteOutcome.Deleted, DeleteOutcome.NotConfigured -> return true
                is DeleteOutcome.Failed -> return false
                // The jwt no longer works. If they are still signed in, their ID token still can.
                DeleteOutcome.Unauthorized -> if (!byIdToken) return true
            }
        }
        return eraseByIdToken()
    }

    /** 401 is retried once with another fresh ID token; a second 401 means the server will not take this user. */
    private suspend fun eraseByIdToken(): Boolean {
        repeat(2) { attempt ->
            val idToken = firebase.idToken(forceRefresh = true) ?: return false
            when (api.deleteAccountByIdToken(idToken)) {
                DeleteOutcome.Deleted, DeleteOutcome.NotConfigured -> return true
                DeleteOutcome.Unauthorized -> if (attempt == 1) return true
                is DeleteOutcome.Failed -> return false  // 429, 503 and the like
            }
        }
        return true
    }
}
