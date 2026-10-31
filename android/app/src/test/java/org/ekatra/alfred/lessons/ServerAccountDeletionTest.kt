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
    private lateinit var firebase: FakeFirebase

    @Before
    fun setup() {
        api = FakeApi(); net = FakeConnectivity(true)
        store = FakeTokenStore(token = "learner-jwt", learner = "L1")
        firebase = FakeFirebase(user = true, currentUid = "uid-1", idTokens = mutableListOf("id-token"))
    }

    private fun deletion(build: String = "") = ServerAccountDeletion(api, SyncConfig("http://x", build, store), store, net, firebase)

    // ---- by learner jwt ----

    @Test
    fun `it erases the learner with the learner jwt`() = runTest {
        deletion().eraseLearner()

        assertEquals(listOf("learner-jwt"), api.deleteCalls)
        assertTrue("done, so nothing to retry", store.pendingDeletes.isEmpty())
        assertTrue("the jwt route needs no ID token", api.deleteByIdCalls.isEmpty())
    }

    @Test
    fun `a 401 on the jwt falls back to the ID token so a signed-in person's data is not left behind`() = runTest {
        api.deleteResult = { DeleteOutcome.Unauthorized }

        deletion().eraseLearner()

        assertEquals(listOf("id-token"), api.deleteByIdCalls)
        assertTrue(store.pendingDeletes.isEmpty())
        assertNull(store.pendingIdUid)
    }

    @Test
    fun `a 401 on the jwt with nobody signed in is simply done`() = runTest {
        api.deleteResult = { DeleteOutcome.Unauthorized }
        firebase.user = false

        deletion().eraseLearner()

        assertTrue(api.deleteByIdCalls.isEmpty())
        assertTrue(store.pendingDeletes.isEmpty())
    }

    @Test
    fun `offline it records a pending erase instead of failing, and sends nothing`() = runTest {
        net.online = false

        deletion().eraseLearner()

        assertTrue(api.deleteCalls.isEmpty())
        assertEquals(setOf("learner-jwt"), store.pendingDeletes)
    }

    @Test
    fun `a failing server records a pending erase`() = runTest {
        for (failure in listOf(DeleteOutcome.Failed("HTTP 503"), DeleteOutcome.Failed("HTTP 404"), DeleteOutcome.NotConfigured)) {
            store.pendingDeletes.clear()
            api.deleteResult = { failure }
            deletion().eraseLearner()
            assertEquals(setOf("learner-jwt"), store.pendingDeletes)
        }
    }

    @Test
    fun `it never throws, whatever the server does`() = runTest {
        api.deleteResult = { DeleteOutcome.Failed("boom") }
        deletion().eraseLearner() // returns normally
    }

    // ---- by Firebase ID token: no learner jwt ----

    @Test
    fun `with no learner jwt but a Firebase user it erases by ID token, before the user is deleted`() = runTest {
        store.token = null

        deletion().eraseLearner()

        assertEquals(listOf("id-token"), api.deleteByIdCalls)
        assertTrue("not the jwt route", api.deleteCalls.isEmpty())
        assertEquals("always a fresh ID token", listOf(true), firebase.idTokenRequests)
        assertNull(store.pendingIdUid)
    }

    @Test
    fun `the shared debug token is never erased, but the signed-in person's own record is`() = runTest {
        store.token = null

        deletion(build = "shared-demo-token").eraseLearner()

        assertTrue("the demo learner is not theirs", api.deleteCalls.isEmpty())
        assertEquals("their own server record goes by ID token", listOf("id-token"), api.deleteByIdCalls)
    }

    @Test
    fun `with no jwt and nobody signed in there is nothing to erase`() = runTest {
        store.token = null; firebase.user = false

        deletion().eraseLearner()

        assertTrue(api.deleteCalls.isEmpty() && api.deleteByIdCalls.isEmpty())
        assertNull(store.pendingIdUid)
    }

    @Test
    fun `a 401 on the ID token is retried once with a fresh one, then given up on`() = runTest {
        store.token = null
        firebase.idTokens = mutableListOf("first", "second")
        api.deleteByIdResult = { DeleteOutcome.Unauthorized }

        deletion().eraseLearner()

        assertEquals(listOf("first", "second"), api.deleteByIdCalls)
        assertEquals(listOf(true, true), firebase.idTokenRequests)
        assertNull("nothing more can be tried, so nothing stays pending", store.pendingIdUid)
    }

    @Test
    fun `a 401 that the fresh ID token fixes is done`() = runTest {
        store.token = null
        firebase.idTokens = mutableListOf("stale", "fresh")
        api.deleteByIdResult = { id -> if (id == "stale") DeleteOutcome.Unauthorized else DeleteOutcome.Deleted }

        deletion().eraseLearner()

        assertEquals(listOf("stale", "fresh"), api.deleteByIdCalls)
        assertNull(store.pendingIdUid)
    }

    @Test
    fun `offline or a failing server leaves a pending ID erase tied to this Firebase user`() = runTest {
        store.token = null
        net.online = false
        deletion().eraseLearner()
        assertEquals("uid-1", store.pendingIdUid)
        assertTrue(api.deleteByIdCalls.isEmpty())

        store.pendingIdUid = null; net.online = true
        api.deleteByIdResult = { DeleteOutcome.Failed("HTTP 503") }
        deletion().eraseLearner()
        assertEquals("uid-1", store.pendingIdUid)
    }

    @Test
    fun `no ID token to be had is a pending erase, not a crash`() = runTest {
        store.token = null
        firebase.idTokens = mutableListOf(null)

        deletion().eraseLearner()

        assertEquals("uid-1", store.pendingIdUid)
        assertTrue(api.deleteByIdCalls.isEmpty())
    }

    // ---- retrying a pending ID erase ----

    @Test
    fun `the retry runs while that same Firebase user still exists, with a fresh ID token`() = runTest {
        store.token = null; store.pendingIdUid = "uid-1"

        deletion().retryPending()

        assertEquals(listOf("id-token"), api.deleteByIdCalls)
        assertEquals(listOf(true), firebase.idTokenRequests)
        assertNull(store.pendingIdUid)
    }

    @Test
    fun `once the Firebase user is gone the retry gives up and forgets it`() = runTest {
        store.pendingIdUid = "uid-1"
        firebase.user = false

        deletion().retryPending()

        assertTrue("no call without a user to vouch for it", api.deleteByIdCalls.isEmpty())
        assertNull(store.pendingIdUid)
    }

    @Test
    fun `a different person signed in now is never erased by someone else's pending erase`() = runTest {
        store.pendingIdUid = "uid-of-the-deleted-account"
        firebase.currentUid = "uid-of-the-new-person"

        deletion().retryPending()

        assertTrue("the new person's data is untouched", api.deleteByIdCalls.isEmpty())
        assertNull(store.pendingIdUid)
    }

    @Test
    fun `a pending ID erase waits while offline or while the server is failing`() = runTest {
        store.pendingIdUid = "uid-1"
        net.online = false
        deletion().retryPending()
        assertEquals("uid-1", store.pendingIdUid)
        assertTrue(api.deleteByIdCalls.isEmpty())

        net.online = true
        api.deleteByIdResult = { DeleteOutcome.Failed("429") }
        deletion().retryPending()
        assertEquals("uid-1", store.pendingIdUid)
    }

    // ---- a pending SET of jwts ----

    @Test
    fun `a second deletion cannot drop the first one's pending token`() = runTest {
        net.online = false
        store.token = "jwt-of-first-account"
        deletion().eraseLearner()
        store.token = "jwt-of-second-account"
        deletion().eraseLearner()

        assertEquals(setOf("jwt-of-first-account", "jwt-of-second-account"), store.pendingDeletes)
    }

    @Test
    fun `every pending token is retried, and only the ones the server dealt with are forgotten`() = runTest {
        store.token = null
        store.pendingDeletes += setOf("done-a", "later-b", "expired-c")
        api.deleteResult = { t ->
            when (t) { "later-b" -> DeleteOutcome.Failed("503"); "expired-c" -> DeleteOutcome.Unauthorized; else -> DeleteOutcome.Deleted }
        }

        deletion().retryPending()

        assertEquals(setOf("later-b"), store.pendingDeletes)
        assertEquals(3, api.deleteCalls.size)
    }

    @Test
    fun `a pending erase is retried and forgotten only after a 200`() = runTest {
        store.token = null; store.pendingDeletes += "old-jwt"
        api.deleteResult = { DeleteOutcome.Failed("503") }
        val d = deletion()

        d.retryPending()
        assertEquals("kept while the server is failing", setOf("old-jwt"), store.pendingDeletes)

        api.deleteResult = { DeleteOutcome.Deleted }
        d.retryPending()
        assertTrue(store.pendingDeletes.isEmpty())
        assertEquals(listOf("old-jwt", "old-jwt"), api.deleteCalls)
    }

    @Test
    fun `offline the retry waits and keeps the tokens`() = runTest {
        store.pendingDeletes += setOf("a", "b")
        net.online = false

        deletion().retryPending()

        assertTrue(api.deleteCalls.isEmpty())
        assertEquals(setOf("a", "b"), store.pendingDeletes)
    }

    @Test
    fun `with nothing pending the retry sends nothing`() = runTest {
        deletion().retryPending()
        assertTrue(api.deleteCalls.isEmpty() && api.deleteByIdCalls.isEmpty())
    }

    @Test
    fun `the retry aims at the deleted account's token, not whoever is signed in now`() = runTest {
        store.pendingDeletes += "deleted-accounts-jwt"
        store.token = "new-persons-jwt"

        deletion().retryPending()

        assertEquals(listOf("deleted-accounts-jwt"), api.deleteCalls)
        assertEquals("the new person's token is untouched", "new-persons-jwt", store.token)
    }

    @Test
    fun `signing out does not cancel a pending erase`() {
        store.pendingDeletes += "old-jwt"
        store.pendingIdUid = "uid-1"
        SyncConfig("http://x", "", store).clearAll()
        assertEquals(setOf("old-jwt"), store.pendingDeletes)
        assertEquals("uid-1", store.pendingIdUid)
    }

    @Test
    fun `the real store keeps a set of pending tokens and the pending uid across clearToken and clearAll`() {
        val prefs = FakePrefs()
        val real = PrefsTokenStore(prefs)
        real.save("jwt", "L")
        real.addPendingDeleteToken("pending-1")
        real.addPendingDeleteToken("pending-2")
        real.addPendingDeleteToken("pending-1")   // adding twice is one entry
        real.setPendingIdEraseUid("uid-9")

        real.clearToken(); real.clearAll()

        val again = PrefsTokenStore(prefs)
        assertEquals(setOf("pending-1", "pending-2"), again.pendingDeleteTokens())
        assertEquals("uid-9", again.pendingIdEraseUid())

        again.removePendingDeleteToken("pending-1")
        assertEquals(setOf("pending-2"), again.pendingDeleteTokens())
        again.setPendingIdEraseUid(null)
        assertNull(again.pendingIdEraseUid())
    }

    @Test
    fun `hasPending reflects both kinds`() {
        val d = deletion()
        assertFalse(d.hasPending)
        store.pendingDeletes += "x"
        assertTrue(d.hasPending)
        store.pendingDeletes.clear(); store.pendingIdUid = "u"
        assertTrue(d.hasPending)
    }

    // ---- the client call ----

    private lateinit var server: ServerSocket
    private var status = 200
    private var bodyOverride: String? = null
    private var lastMethod: String? = null
    private var lastPath: String? = null
    private var lastAuth: String? = null
    private var lastBody: String? = null
    private var requestCount = 0
    private var redirectTo: String? = null

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
                    var length = 0
                    while (true) {
                        val h = line(); if (h.isEmpty()) break
                        if (h.lowercase().startsWith("authorization:")) lastAuth = h.substringAfter(":").trim()
                        if (h.lowercase().startsWith("content-length:")) length = h.substringAfter(":").trim().toInt()
                    }
                    val bodyBytes = ByteArray(length)
                    var read = 0
                    while (read < length) { val n = input.read(bodyBytes, read, length - read); if (n < 0) break; read += n }
                    lastBody = bodyBytes.toString(Charsets.UTF_8)
                    requestCount++
                    val body = bodyOverride ?: if (status in 200..299) """{"success":true,"data":{}}""" else """{"success":false,"error":"x"}"""
                    val location = redirectTo?.let { loc -> "Location: $loc\r\n" }.orEmpty()
                    it.getOutputStream().apply {
                        write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\n${location}Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray()); flush()
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

    // ---- delete by ID token ----

    @Test
    fun `deleteAccountByIdToken sends DELETE with the idToken in the body and no Authorization header`() = runBlocking {
        startServer()

        val outcome = client(activeToken = "active-jwt").deleteAccountByIdToken("firebase-id-token")

        assertEquals(DeleteOutcome.Deleted, outcome)
        assertEquals("DELETE", lastMethod); assertEquals("/api/sync/auth/account", lastPath)
        assertNull("the learner jwt must not ride along", lastAuth)
        assertTrue(lastBody!!.contains(""""idToken":"firebase-id-token""""))
    }

    @Test
    fun `deleteAccountByIdToken treats both 200 shapes as done`() = runBlocking {
        startServer()
        for (body in listOf("""{"success":true,"deleted":true,"data":{"deleted":true}}""", """{"success":true,"deleted":false,"data":{"deleted":false}}""")) {
            bodyOverride = body
            assertEquals(body, DeleteOutcome.Deleted, client().deleteAccountByIdToken("t"))
        }
    }

    @Test
    fun `deleteAccountByIdToken status mapping`() = runBlocking {
        startServer()
        status = 401; bodyOverride = """{"success":false,"error":"invalid"}"""
        assertEquals(DeleteOutcome.Unauthorized, client().deleteAccountByIdToken("t"))
        for (code in listOf(400, 429, 500, 503)) {
            status = code
            assertTrue("HTTP $code", client().deleteAccountByIdToken("t") is DeleteOutcome.Failed)
        }
        status = 404; bodyOverride = "<html>Not Found</html>"
        assertTrue("a bare 404 is retried", client().deleteAccountByIdToken("t") is DeleteOutcome.Failed)
        assertEquals(DeleteOutcome.NotConfigured, LearnerSyncClient(SyncConfig("", "")) { it.openConnection() as java.net.HttpURLConnection }.deleteAccountByIdToken("t"))
    }

    // ---- redirects ----

    @Test
    fun `an authenticated call does not follow a redirect, so the bearer token cannot be sent elsewhere`() = runBlocking {
        startServer()
        status = 302
        redirectTo = "http://127.0.0.1:${server.localPort}/elsewhere"
        bodyOverride = ""

        val result = client().getMe()

        assertEquals("only the one request was made", 1, requestCount)
        assertEquals("/api/sync/me", lastPath)
        assertTrue("a redirect is not a success", result !is ApiResult.Ok)
    }

    @Test
    fun `no authenticated call follows a redirect`() = runBlocking {
        startServer()
        status = 307
        redirectTo = "http://127.0.0.1:${server.localPort}/elsewhere"
        bodyOverride = ""

        client().postEvents(emptyList())
        client().fetchBundle("abc")
        client().deleteMe("jwt")
        client().requestLesson("topic", "en", "id")

        assertEquals(4, requestCount)
    }
}
