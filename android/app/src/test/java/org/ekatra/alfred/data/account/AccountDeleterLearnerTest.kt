package org.ekatra.alfred.data.account

import kotlinx.coroutines.runBlocking
import org.ekatra.alfred.lessons.LearnerAccountRemote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the server-side erase sits in the account deletion sequence. */
class AccountDeleterLearnerTest {

    private val calls = mutableListOf<String>()

    private inner class Remote(
        val analyticsError: Exception? = null,
        val authErrors: MutableList<Exception> = mutableListOf()
    ) : AccountRemote {
        override suspend fun deleteAnalytics(uid: String) { calls += "analytics"; analyticsError?.let { throw it } }
        override suspend fun deleteProfile(uid: String) { calls += "profile" }
        override suspend fun deleteAuthUser() { calls += "auth"; if (authErrors.isNotEmpty()) throw authErrors.removeAt(0) }
    }

    private inner class Local : LocalAccountData {
        override suspend fun clear(uid: String) { calls += "local" }
    }

    private inner class Learner : LearnerAccountRemote {
        override suspend fun eraseLearner() { calls += "learner" }
    }

    @Test
    fun `the learner is erased before the Firebase user, and local data goes last`() = runBlocking {
        val result = AccountDeleter(Remote(), Local(), Learner()).delete("u") { error("no reauth") }

        assertEquals(DeletionResult.Deleted, result)
        assertEquals(listOf("analytics", "profile", "learner", "auth", "local"), calls)
    }

    @Test
    fun `if cloud deletion fails first, the learner is not touched and the account stays intact`() = runBlocking {
        val result = AccountDeleter(Remote(analyticsError = RuntimeException("offline")), Local(), Learner()).delete("u") { false }

        assertTrue(result is DeletionResult.Failed)
        assertEquals(listOf("analytics"), calls)
    }

    @Test
    fun `a reauthentication retry erases the learner only once`() = runBlocking {
        val remote = Remote(authErrors = mutableListOf(RecentLoginRequiredException()))

        val result = AccountDeleter(remote, Local(), Learner()).delete("u") { true }

        assertEquals(DeletionResult.Deleted, result)
        assertEquals(listOf("analytics", "profile", "learner", "auth", "auth", "local"), calls)
    }

    @Test
    fun `the deletion completes even though the erase could not be sent`() = runBlocking {
        // The real implementation records a pending erase and returns normally; the deleter just carries on.
        val quietFailure = object : LearnerAccountRemote { override suspend fun eraseLearner() { calls += "learner(queued for retry)" } }

        val result = AccountDeleter(Remote(), Local(), quietFailure).delete("u") { error("no reauth") }

        assertEquals(DeletionResult.Deleted, result)
        assertEquals("local", calls.last())
    }

    @Test
    fun `the old two-argument form still works for callers with no lessons server`() = runBlocking {
        val result = AccountDeleter(Remote(), Local()).delete("u") { error("no reauth") }
        assertEquals(DeletionResult.Deleted, result)
        assertEquals(listOf("analytics", "profile", "auth", "local"), calls)
    }
}
