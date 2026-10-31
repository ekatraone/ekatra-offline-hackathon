package org.ekatra.alfred.data.account

import org.ekatra.alfred.lessons.LearnerAccountRemote
import org.ekatra.alfred.lessons.NoLearnerAccountRemote
import javax.inject.Inject

/**
 * Runs the account deletion sequence (milestone 2, criteria 1, 2 and 4).
 *
 * The learner record on the Ekatra server is erased first, and the deletion stops there if that cannot be
 * confirmed. Remote data is removed before the Firebase Auth user, so a failure part-way leaves the account
 * intact and retryable. Local data is cleared last, after the Auth user is gone.
 *
 * FCM tokens are not written to Firestore (see EkatraMessagingService), so there is nothing
 * to delete for them and no step covers them. Feedback is anonymous and is not deleted here.
 */
class AccountDeleter @Inject constructor(
    private val remote: AccountRemote,
    private val local: LocalAccountData,
    private val learner: LearnerAccountRemote
) {
    /** For callers with no lessons server to erase from. */
    constructor(remote: AccountRemote, local: LocalAccountData) : this(remote, local, NoLearnerAccountRemote)

    suspend fun delete(uid: String, reauthenticate: suspend () -> Boolean): DeletionResult {
        // All-or-nothing: the learner record on the Ekatra server is erased FIRST, and if that cannot be
        // confirmed nothing else is touched. Once the Firebase user is gone the server can no longer be told
        // who to erase, so it can never be done "later". Retrying the whole deletion is safe.
        if (!learner.eraseLearner()) {
            return DeletionResult.Failed(Step.SERVER, IllegalStateException("The Ekatra server could not be reached to erase the learner"))
        }
        try {
            remote.deleteAnalytics(uid)
        } catch (e: Exception) {
            return DeletionResult.Failed(Step.ANALYTICS, e)
        }
        try {
            remote.deleteProfile(uid)
        } catch (e: Exception) {
            return DeletionResult.Failed(Step.PROFILE, e)
        }

        try {
            remote.deleteAuthUser()
        } catch (e: RecentLoginRequiredException) {
            // Re-authenticate once and retry only the Auth step. Steps above already succeeded.
            if (!reauthenticate()) return DeletionResult.Failed(Step.REAUTHENTICATION, e)
            try {
                remote.deleteAuthUser()
            } catch (retry: Exception) {
                return DeletionResult.Failed(Step.AUTH, retry)
            }
        } catch (e: Exception) {
            return DeletionResult.Failed(Step.AUTH, e)
        }

        local.clear(uid)
        return DeletionResult.Deleted
    }

    enum class Step { SERVER, ANALYTICS, PROFILE, REAUTHENTICATION, AUTH }
}

sealed interface DeletionResult {
    data object Deleted : DeletionResult
    data class Failed(val step: AccountDeleter.Step, val cause: Throwable) : DeletionResult
}

/** Thrown by [AccountRemote.deleteAuthUser] when Firebase requires a recent sign-in. */
class RecentLoginRequiredException(cause: Throwable? = null) : Exception(cause)

interface AccountRemote {
    suspend fun deleteAnalytics(uid: String)
    suspend fun deleteProfile(uid: String)

    /** @throws RecentLoginRequiredException when a fresh sign-in is needed. */
    suspend fun deleteAuthUser()
}

interface LocalAccountData {
    /** Clears chat history, the cached profile and settings, keeping the consent choice. */
    suspend fun clear(uid: String)
}
