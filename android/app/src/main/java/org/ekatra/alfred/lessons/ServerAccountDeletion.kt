package org.ekatra.alfred.lessons

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
 * `DELETE /api/sync/me` with the learner JWT, plus the retry for when it could not be sent.
 *
 * The token that was used is kept, in its own slot, until the server answers 200 (erased) or 401 (the token
 * is no longer good); only then is it forgotten. It is kept apart from the active token so that signing the
 * account out, or a different person signing in later, can never aim a delete at the wrong learner.
 */
@Singleton
class ServerAccountDeletion @Inject constructor(
    private val api: LearnerSyncApi,
    private val config: SyncConfig,
    private val store: TokenStore,
    private val connectivity: Connectivity
) : LearnerAccountRemote {

    override suspend fun eraseLearner() {
        // Only a token this phone exchanged for the signed-in user belongs to them. The build-time debug
        // token is a shared demo learner; deleting an account must never erase that.
        if (!config.hasStoredToken) return
        val token = config.token
        if (!tryDelete(token)) store.setPendingDeleteToken(token)
    }

    /** Called on app start and from background sync. A no-op unless an erase is waiting. */
    suspend fun retryPending() {
        val token = store.pendingDeleteToken() ?: return
        if (tryDelete(token)) store.clearPendingDelete()
    }

    val hasPending: Boolean get() = store.pendingDeleteToken() != null

    /** @return true if the server has dealt with this token: erased (200) or no longer valid (401). */
    private suspend fun tryDelete(token: String): Boolean {
        if (!connectivity.isOnline()) return false
        return when (api.deleteMe(token)) {
            DeleteOutcome.Deleted, DeleteOutcome.Unauthorized -> true
            is DeleteOutcome.Failed, DeleteOutcome.NotConfigured -> false
        }
    }
}
