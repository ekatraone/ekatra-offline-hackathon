package org.ekatra.alfred.data.account

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDeleterTest {

    /** Records every call in order. Each step can be set to fail. */
    private class FakeRemote(
        var analyticsError: Exception? = null,
        var profileError: Exception? = null,
        var authErrors: MutableList<Exception> = mutableListOf()
    ) : AccountRemote {
        val calls = mutableListOf<String>()

        override suspend fun deleteAnalytics(uid: String) {
            calls += "analytics:$uid"
            analyticsError?.let { throw it }
        }

        override suspend fun deleteProfile(uid: String) {
            calls += "profile:$uid"
            profileError?.let { throw it }
        }

        override suspend fun deleteAuthUser() {
            calls += "auth"
            if (authErrors.isNotEmpty()) throw authErrors.removeAt(0)
        }
    }

    private class FakeLocal : LocalAccountData {
        var cleared: String? = null
        override suspend fun clear(uid: String) {
            cleared = uid
        }
    }

    private val noReauth: suspend () -> Boolean = { error("reauth should not run") }

    @Test
    fun `deletes analytics, profile, then auth, then local data, in that order`() = runBlocking {
        val remote = FakeRemote()
        val local = FakeLocal()

        val result = AccountDeleter(remote, local).delete("uid1", noReauth)

        assertEquals(DeletionResult.Deleted, result)
        assertEquals(listOf("analytics:uid1", "profile:uid1", "auth"), remote.calls)
        assertEquals("uid1", local.cleared)
    }

    @Test
    fun `analytics failure stops before profile and auth, and keeps the account`() = runBlocking {
        val remote = FakeRemote(analyticsError = RuntimeException("offline"))
        val local = FakeLocal()

        val result = AccountDeleter(remote, local).delete("uid1", noReauth)

        assertEquals(AccountDeleter.Step.ANALYTICS, (result as DeletionResult.Failed).step)
        assertEquals(listOf("analytics:uid1"), remote.calls)
        assertEquals(null, local.cleared)
    }

    @Test
    fun `profile failure stops before auth, and keeps the account`() = runBlocking {
        val remote = FakeRemote(profileError = RuntimeException("permission denied"))
        val local = FakeLocal()

        val result = AccountDeleter(remote, local).delete("uid1", noReauth)

        assertEquals(AccountDeleter.Step.PROFILE, (result as DeletionResult.Failed).step)
        assertTrue(remote.calls.none { it == "auth" })
        assertEquals(null, local.cleared)
    }

    @Test
    fun `auth failure after remote data is gone keeps local data`() = runBlocking {
        val remote = FakeRemote(authErrors = mutableListOf(RuntimeException("network")))
        val local = FakeLocal()

        val result = AccountDeleter(remote, local).delete("uid1", noReauth)

        assertEquals(AccountDeleter.Step.AUTH, (result as DeletionResult.Failed).step)
        assertEquals(null, local.cleared)
    }

    @Test
    fun `recent login is re-authenticated and the auth step retried once`() = runBlocking {
        val remote = FakeRemote(authErrors = mutableListOf(RecentLoginRequiredException()))
        val local = FakeLocal()
        var reauthCalls = 0

        val result = AccountDeleter(remote, local).delete("uid1") {
            reauthCalls++
            true
        }

        assertEquals(DeletionResult.Deleted, result)
        assertEquals(1, reauthCalls)
        // Analytics and profile run once; only the auth step is repeated.
        assertEquals(
            listOf("analytics:uid1", "profile:uid1", "auth", "auth"),
            remote.calls
        )
        assertEquals("uid1", local.cleared)
    }

    @Test
    fun `second failure after re-authentication fails the auth step`() = runBlocking {
        val remote = FakeRemote(
            authErrors = mutableListOf(
                RecentLoginRequiredException(),
                RuntimeException("still failing")
            )
        )
        val local = FakeLocal()

        val result = AccountDeleter(remote, local).delete("uid1") { true }

        assertEquals(AccountDeleter.Step.AUTH, (result as DeletionResult.Failed).step)
        assertEquals(null, local.cleared)
    }

    @Test
    fun `unsupported provider that cannot re-authenticate fails without retry`() = runBlocking {
        val remote = FakeRemote(authErrors = mutableListOf(RecentLoginRequiredException()))
        val local = FakeLocal()

        val result = AccountDeleter(remote, local).delete("uid1") { false }

        assertEquals(AccountDeleter.Step.REAUTHENTICATION, (result as DeletionResult.Failed).step)
        assertEquals(1, remote.calls.count { it == "auth" })
        assertEquals(null, local.cleared)
    }
}
