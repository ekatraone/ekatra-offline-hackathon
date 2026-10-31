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

    private inner class Learner(private val result: Boolean = true) : LearnerAccountRemote {
        override suspend fun eraseLearner(): Boolean { calls += "learner"; return result }
    }

    @Test
    fun `the learner is erased first, and the Firebase user and local data go only after it succeeds`() = runBlocking {
        val result = AccountDeleter(Remote(), Local(), Learner()).delete("u") { error("no reauth") }

        assertEquals(DeletionResult.Deleted, result)
        assertEquals(listOf("learner", "analytics", "profile", "auth", "local"), calls)
    }

    @Test
    fun `if the server erase cannot be confirmed nothing else is deleted`() = runBlocking {
        val result = AccountDeleter(Remote(), Local(), Learner(result = false)).delete("u") { error("no reauth") }

        assertTrue(result is DeletionResult.Failed)
        assertEquals(AccountDeleter.Step.SERVER, (result as DeletionResult.Failed).step)
        assertEquals("no analytics, profile, Firebase user or local data was touched", listOf("learner"), calls)
    }

    @Test
    fun `after a failed server erase the whole deletion can simply be tried again`() = runBlocking {
        var up = false
        val flaky = object : LearnerAccountRemote { override suspend fun eraseLearner(): Boolean { calls += "learner"; return up } }
        val deleter = AccountDeleter(Remote(), Local(), flaky)

        assertTrue(deleter.delete("u") { false } is DeletionResult.Failed)
        up = true
        assertEquals(DeletionResult.Deleted, deleter.delete("u") { false })
        assertEquals(listOf("learner", "learner", "analytics", "profile", "auth", "local"), calls)
    }

    @Test
    fun `a reauthentication retry erases the learner only once`() = runBlocking {
        val remote = Remote(authErrors = mutableListOf(RecentLoginRequiredException()))

        val result = AccountDeleter(remote, Local(), Learner()).delete("u") { true }

        assertEquals(DeletionResult.Deleted, result)
        assertEquals(listOf("learner", "analytics", "profile", "auth", "auth", "local"), calls)
    }

    @Test
    fun `cloud data failing after the server erase stops there and keeps the account`() = runBlocking {
        val result = AccountDeleter(Remote(analyticsError = RuntimeException("offline")), Local(), Learner()).delete("u") { false }

        assertEquals(AccountDeleter.Step.ANALYTICS, (result as DeletionResult.Failed).step)
        assertEquals(listOf("learner", "analytics"), calls)
    }

    @Test
    fun `the old two-argument form still works for callers with no lessons server`() = runBlocking {
        val result = AccountDeleter(Remote(), Local()).delete("u") { error("no reauth") }
        assertEquals(DeletionResult.Deleted, result)
        assertEquals(listOf("analytics", "profile", "auth", "local"), calls)
    }

    @Test
    fun `the settings dialog has its own message for the server step`() {
        val settings = java.io.File("src/main/java/org/ekatra/alfred/SettingsActivity.kt").readText()
        assertTrue(settings.contains("AccountDeleter.Step.SERVER -> R.string.delete_account_failed_server"))
        val strings = java.io.File("src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("""<string name="delete_account_failed_server">Couldn\'t reach Ekatra to delete your lessons — check your connection and try again.</string>"""))
    }
}
