package org.ekatra.alfred.lessons

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class ServerAccountDeletionTest {

    private lateinit var api: FakeApi
    private lateinit var net: FakeConnectivity
    private lateinit var store: FakeTokenStore

    @Before
    fun setup() { api = FakeApi(); net = FakeConnectivity(true); store = FakeTokenStore(token = "learner-jwt", learner = "L1") }

    private fun deletion(build: String = "") = ServerAccountDeletion(api, SyncConfig("http://x", build, store), store, net)

    // ---- before the Firebase user goes ----

    @Test
    fun `it erases the learner with the learner jwt`() = runTest {
        deletion().eraseLearner()

        assertEquals(listOf("learner-jwt"), api.deleteCalls)
        assertNull("done, so nothing to retry", store.pendingDelete)
    }

    @Test
    fun `a 401 counts as done, since the token no longer reaches anything`() = runTest {
        api.deleteResult = { DeleteOutcome.Unauthorized }
        deletion().eraseLearner()
        assertNull(store.pendingDelete)
    }

    @Test
    fun `offline it records a pending erase instead of failing, and sends nothing`() = runTest {
        net.online = false

        deletion().eraseLearner()

        assertTrue(api.deleteCalls.isEmpty())
        assertEquals("learner-jwt", store.pendingDelete)
    }

    @Test
    fun `a failing server records a pending erase`() = runTest {
        for (failure in listOf(DeleteOutcome.Failed("HTTP 503"), DeleteOutcome.Failed("HTTP 404"), DeleteOutcome.NotConfigured)) {
            store.pendingDelete = null
            api.deleteResult = { failure }
            deletion().eraseLearner()
            assertEquals("learner-jwt", store.pendingDelete)
        }
    }

    @Test
    fun `it never throws, whatever the server does`() = runTest {
        api.deleteResult = { DeleteOutcome.Failed("boom") }
        deletion().eraseLearner() // returns normally
    }

    @Test
    fun `the shared debug token is never erased`() = runTest {
        store.token = null
        deletion(build = "shared-demo-token").eraseLearner()

        assertTrue(api.deleteCalls.isEmpty())
        assertNull(store.pendingDelete)
    }

    @Test
    fun `with no token at all there is nothing to erase`() = runTest {
        store.token = null
        deletion().eraseLearner()
        assertTrue(api.deleteCalls.isEmpty())
        assertNull(store.pendingDelete)
    }

    // ---- the retry ----

    @Test
    fun `a pending erase is retried and forgotten only after a 200`() = runTest {
        store.token = null; store.pendingDelete = "old-jwt"
        api.deleteResult = { DeleteOutcome.Failed("503") }
        val d = deletion()

        d.retryPending()
        assertEquals("kept while the server is failing", "old-jwt", store.pendingDelete)

        api.deleteResult = { DeleteOutcome.Deleted }
        d.retryPending()
        assertNull(store.pendingDelete)
        assertEquals(listOf("old-jwt", "old-jwt"), api.deleteCalls)
    }

    @Test
    fun `a pending erase is also forgotten after a 401`() = runTest {
        store.pendingDelete = "expired-jwt"
        api.deleteResult = { DeleteOutcome.Unauthorized }
        deletion().retryPending()
        assertNull(store.pendingDelete)
    }

    @Test
    fun `offline the retry waits and keeps the token`() = runTest {
        store.pendingDelete = "old-jwt"
        net.online = false

        deletion().retryPending()

        assertTrue(api.deleteCalls.isEmpty())
        assertEquals("old-jwt", store.pendingDelete)
    }

    @Test
    fun `with nothing pending the retry sends nothing`() = runTest {
        deletion().retryPending()
        assertTrue(api.deleteCalls.isEmpty())
    }

    @Test
    fun `the retry aims at the deleted account's token, not whoever is signed in now`() = runTest {
        // The account was deleted offline. Later someone else signs in and gets their own token.
        store.pendingDelete = "deleted-accounts-jwt"
        store.token = "new-persons-jwt"

        deletion().retryPending()

        assertEquals(listOf("deleted-accounts-jwt"), api.deleteCalls)
        assertEquals("the new person's token is untouched", "new-persons-jwt", store.token)
    }

    @Test
    fun `signing out does not cancel a pending erase`() {
        store.pendingDelete = "old-jwt"
        SyncConfig("http://x", "", store).clearAll()
        assertEquals("old-jwt", store.pendingDelete)
    }

    @Test
    fun `the pending slot survives clearToken and clearAll in the real store`() {
        val prefs = FakePrefs()
        val real = PrefsTokenStore(prefs)
        real.save("jwt", "L")
        real.setPendingDeleteToken("pending")

        real.clearToken(); real.clearAll()

        assertEquals("pending", PrefsTokenStore(prefs).pendingDeleteToken())
        real.clearPendingDelete()
        assertNull(real.pendingDeleteToken())
    }

    @Test
    fun `hasPending reflects the slot`() {
        val d = deletion()
        assertFalse(d.hasPending)
        store.pendingDelete = "x"
        assertTrue(d.hasPending)
    }

    // ---- the client call ----

    private lateinit var server: ServerSocket
    private var status = 200
    private var bodyOverride: String? = null
    private var lastMethod: String? = null
    private var lastPath: String? = null
    private var lastAuth: String? = null

    private fun startServer() {
        server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = try { server.accept() } catch (e: Exception) { return@thread }
                sock.use {
                    val input = it.getInputStream().buffered()
                    fun line(): String {
                        val sb = StringBuilder()
                        while (true) { val b = input.read(); if (b < 0 || b == '\n'.code) break; if (b != '\r'.code) sb.append(b.toChar()) }
                        return sb.toString()
                    }
                    val first = line().split(" ")
                    lastMethod = first[0]; lastPath = first[1]
                    lastAuth = null
                    while (true) { val h = line(); if (h.isEmpty()) break; if (h.lowercase().startsWith("authorization:")) lastAuth = h.substringAfter(":").trim() }
                    val body = bodyOverride ?: if (status in 200..299) """{"success":true,"data":{}}""" else """{"success":false,"error":"x"}"""
                    it.getOutputStream().apply {
                        write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray()); flush()
                    }
                }
            }
        }
    }

    @After fun stopServer() { if (this::server.isInitialized) server.close() }

    private fun client(activeToken: String = "active-token") = LearnerSyncClient(
        SyncConfig("http://127.0.0.1:${server.localPort}/", activeToken)
    ) { url -> url.openConnection() as java.net.HttpURLConnection }

    @Test
    fun `deleteMe sends DELETE to the me endpoint with the token it is given, not the active one`() = runBlocking {
        startServer()

        val outcome = client(activeToken = "active-token").deleteMe("the-token-to-erase")

        assertEquals(DeleteOutcome.Deleted, outcome)
        assertEquals("DELETE", lastMethod); assertEquals("/api/sync/me", lastPath)
        assertEquals("Bearer the-token-to-erase", lastAuth)
    }

    @Test
    fun `deleteMe maps 401 to unauthorized and every other failure to failed`() = runBlocking {
        startServer()
        status = 401
        assertEquals(DeleteOutcome.Unauthorized, client().deleteMe("t"))
        for (code in listOf(400, 403, 429, 500, 503)) {
            status = code
            assertTrue("HTTP $code", client().deleteMe("t") is DeleteOutcome.Failed)
        }
    }

    @Test
    fun `a 404 in our API envelope means the learner is already gone, so it is done`() = runBlocking {
        startServer()
        status = 404
        bodyOverride = """{"success":false,"error":"not_found"}"""

        assertEquals(DeleteOutcome.Deleted, client().deleteMe("t"))
    }

    @Test
    fun `a bare or html 404 is not an answer from our API and is retried`() = runBlocking {
        startServer()
        status = 404
        for (body in listOf("", "<html><body>Not Found</body></html>", """{"message":"gateway"}""", "null")) {
            bodyOverride = body
            assertTrue("body '$body'", client().deleteMe("t") is DeleteOutcome.Failed)
        }
    }

    @Test
    fun `only a 404 gets that treatment, other API errors still fail`() = runBlocking {
        startServer()
        bodyOverride = """{"success":false,"error":"nope"}"""
        for (code in listOf(400, 403, 409, 422, 500)) {
            status = code
            assertTrue("HTTP $code", client().deleteMe("t") is DeleteOutcome.Failed)
        }
    }

    @Test
    fun `deleteMe with a dead server or no address`() = runBlocking {
        assertEquals(DeleteOutcome.NotConfigured, LearnerSyncClient(SyncConfig("", "")) { it.openConnection() as java.net.HttpURLConnection }.deleteMe("t"))
        startServer()
        val c = client()
        server.close()
        assertTrue(c.deleteMe("t") is DeleteOutcome.Failed)
    }
}
