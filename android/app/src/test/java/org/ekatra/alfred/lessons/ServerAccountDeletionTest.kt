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
import java.io.File
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

    private fun deletion(build: String = "", baseUrl: String = "http://x", lessons: Boolean = true) =
        ServerAccountDeletion(api, SyncConfig(baseUrl, build, store), net, firebase, lessonsEnabled = lessons)

    // ---- by learner jwt ----

    @Test
    fun `it erases the learner with the learner jwt and reports success`() = runTest {
        assertTrue(deletion().eraseLearner())

        assertEquals(listOf("learner-jwt"), api.deleteCalls)
        assertTrue("the jwt route needs no ID token", api.deleteByIdCalls.isEmpty())
    }

    @Test
    fun `a failing jwt erase is a failure, so the deletion can stop`() = runTest {
        for (failure in listOf(DeleteOutcome.Failed("HTTP 503"), DeleteOutcome.Failed("HTTP 429"), DeleteOutcome.Failed("timeout"))) {
            api.deleteResult = { failure }
            assertFalse("$failure", deletion().eraseLearner())
        }
        assertTrue("no fallback on a server error", api.deleteByIdCalls.isEmpty())
    }

    @Test
    fun `a 401 on the jwt falls back to the ID token so a signed-in person's data is not left behind`() = runTest {
        api.deleteResult = { DeleteOutcome.Unauthorized }

        assertTrue(deletion().eraseLearner())

        assertEquals(listOf("id-token"), api.deleteByIdCalls)
    }

    @Test
    fun `a 401 on the jwt falling back to a failing ID route is a failure`() = runTest {
        api.deleteResult = { DeleteOutcome.Unauthorized }
        api.deleteByIdResult = { DeleteOutcome.Failed("503") }
        assertFalse(deletion().eraseLearner())
    }

    @Test
    fun `a 401 on the jwt with nobody signed in is simply done`() = runTest {
        api.deleteResult = { DeleteOutcome.Unauthorized }
        firebase.user = false

        assertTrue(deletion().eraseLearner())
        assertTrue(api.deleteByIdCalls.isEmpty())
    }

    // ---- by Firebase ID token ----

    @Test
    fun `with no learner jwt but a Firebase user it erases by ID token`() = runTest {
        store.token = null

        assertTrue(deletion().eraseLearner())

        assertEquals(listOf("id-token"), api.deleteByIdCalls)
        assertTrue("not the jwt route", api.deleteCalls.isEmpty())
        assertEquals("always a fresh ID token", listOf(true), firebase.idTokenRequests)
    }

    @Test
    fun `the shared debug token is never erased, but the signed-in person's own record is`() = runTest {
        store.token = null

        assertTrue(deletion(build = "shared-demo-token").eraseLearner())

        assertTrue("the demo learner is not theirs", api.deleteCalls.isEmpty())
        assertEquals(listOf("id-token"), api.deleteByIdCalls)
    }

    @Test
    fun `deleted false still counts as success, since nothing is left either way`() = runTest {
        store.token = null
        // The client maps both 200 shapes to Deleted (see the client tests below).
        api.deleteByIdResult = { DeleteOutcome.Deleted }
        assertTrue(deletion().eraseLearner())
    }

    @Test
    fun `a 401 on the ID token is retried once with a fresh one, then the server is taken as not having them`() = runTest {
        store.token = null
        firebase.idTokens = mutableListOf("first", "second")
        api.deleteByIdResult = { DeleteOutcome.Unauthorized }

        assertTrue(deletion().eraseLearner())

        assertEquals(listOf("first", "second"), api.deleteByIdCalls)
        assertEquals(listOf(true, true), firebase.idTokenRequests)
    }

    @Test
    fun `a 401 that the fresh ID token fixes is done`() = runTest {
        store.token = null
        firebase.idTokens = mutableListOf("stale", "fresh")
        api.deleteByIdResult = { id -> if (id == "stale") DeleteOutcome.Unauthorized else DeleteOutcome.Deleted }

        assertTrue(deletion().eraseLearner())
        assertEquals(listOf("stale", "fresh"), api.deleteByIdCalls)
    }

    @Test
    fun `429, 5xx and a missing ID token are failures`() = runTest {
        store.token = null
        for (failure in listOf(DeleteOutcome.Failed("HTTP 429"), DeleteOutcome.Failed("HTTP 503"))) {
            api.deleteByIdResult = { failure }
            assertFalse("$failure", deletion().eraseLearner())
        }
        api.deleteByIdResult = { DeleteOutcome.Deleted }
        firebase.idTokens = mutableListOf(null)
        assertFalse("no ID token to be had", deletion().eraseLearner())
    }

    // ---- offline ----

    @Test
    fun `offline with something to erase is a failure, and nothing is sent`() = runTest {
        net.online = false

        assertFalse(deletion().eraseLearner())

        store.token = null
        assertFalse(deletion().eraseLearner())
        assertTrue(api.deleteCalls.isEmpty() && api.deleteByIdCalls.isEmpty())
    }

    // ---- nothing to erase: never block the deletion ----

    @Test
    fun `with no jwt and nobody signed in there is nothing to erase, even offline`() = runTest {
        store.token = null; firebase.user = false
        net.online = false

        assertTrue(deletion().eraseLearner())
        assertTrue(api.deleteCalls.isEmpty() && api.deleteByIdCalls.isEmpty())
    }

    @Test
    fun `a build with no lessons server never blocks the deletion`() = runTest {
        net.online = false

        assertTrue(deletion(baseUrl = "").eraseLearner())
        assertTrue(api.deleteCalls.isEmpty() && api.deleteByIdCalls.isEmpty())
    }

    @Test
    fun `a persona without Lessons never blocks the deletion, even with a server configured`() = runTest {
        net.online = false

        assertTrue(deletion(lessons = false).eraseLearner())
        assertTrue(api.deleteCalls.isEmpty() && api.deleteByIdCalls.isEmpty())
    }

    @Test
    fun `NotConfigured from the client counts as nothing to erase`() = runTest {
        api.deleteResult = { DeleteOutcome.NotConfigured }
        assertTrue(deletion().eraseLearner())
    }

    // ---- the retry machinery is gone ----

    @Test
    fun `nothing is queued for later, because a retry could not be aimed once the Firebase user is gone`() {
        val store = File("src/main/java/org/ekatra/alfred/lessons/TokenStore.kt").readText()
        val deletion = File("src/main/java/org/ekatra/alfred/lessons/ServerAccountDeletion.kt").readText()
        for (gone in listOf("pendingDelete", "PendingDelete", "retryPending", "hasPending", "pendingIdErase", "PendingIdErase")) {
            assertFalse("TokenStore still has $gone", store.contains(gone))
            assertFalse("ServerAccountDeletion still has $gone", deletion.contains("fun $gone") || deletion.contains("val $gone"))
        }
        val app = File("src/main/java/org/ekatra/alfred/EkatraApp.kt").readText()
        val worker = File("src/main/java/org/ekatra/alfred/data/sync/SyncWorker.kt").readText()
        assertFalse(app.contains("retryPending")); assertFalse(worker.contains("retryPending"))
    }

    @Test
    fun `the app wires the erase with the persona's Lessons flag, so Asmaa is never blocked from deleting an account`() {
        val module = File("src/main/java/org/ekatra/alfred/lessons/LessonsModule.kt").readText()
        assertTrue(module.contains("lessonsEnabled = personaHasLessons(ActivePersona.key)"))
        assertFalse(personaHasLessons("asmaa"))
    }

    @Test
    fun `signing out still leaves the token store empty`() {
        SyncConfig("http://x", "", store).clearAll()
        assertNull(store.token); assertNull(store.learner)
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
    fun `deleteAccountByIdToken sends a POST with the idToken in the body and no Authorization header`() = runBlocking {
        startServer()

        val outcome = client(activeToken = "active-jwt").deleteAccountByIdToken("firebase-id-token")

        assertEquals(DeleteOutcome.Deleted, outcome)
        assertEquals("POST", lastMethod); assertEquals("/api/sync/auth/account/delete", lastPath)
        assertNull("the learner jwt must not ride along", lastAuth)
        assertTrue(lastBody!!.contains(""""idToken":"firebase-id-token""""))
    }

    @Test
    fun `the ID-token erase is never a DELETE with a body, but the jwt erase stays a bodiless DELETE`() = runBlocking {
        startServer()
        client().deleteAccountByIdToken("t")
        assertEquals("a POST, never a DELETE with a body", "POST", lastMethod)

        client().deleteMe("jwt")
        assertEquals("DELETE", lastMethod); assertEquals("/api/sync/me", lastPath)
        assertEquals("no body on the jwt DELETE", "", lastBody)
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

    @Test
    fun `the public OTP calls and the exchange do not follow redirects either`() = runBlocking {
        startServer()
        status = 302
        redirectTo = "http://127.0.0.1:${server.localPort}/elsewhere"
        bodyOverride = ""

        client().whatsappStart("+919876543210")
        client().whatsappVerify("+919876543210", "123456")
        client().exchange("firebase-id-token")
        client().deleteAccountByIdToken("firebase-id-token")

        assertEquals("one request each, no follow-up to the redirect target", 4, requestCount)
        assertEquals("/api/sync/auth/account/delete", lastPath)
    }

    @Test
    fun `a redirect on a public call is a failed try, not an answer`() = runBlocking {
        startServer()
        status = 301
        redirectTo = "http://127.0.0.1:${server.localPort}/elsewhere"
        bodyOverride = ""

        assertTrue(client().whatsappStart("+919876543210") is StartOutcome.NetworkError)
        assertTrue(client().whatsappVerify("+919876543210", "123456") is VerifyOutcome.NetworkError)
        assertTrue(client().exchange("t") is ExchangeOutcome.Failed)
    }

    // ---- bodies only on POST ----

    @Test
    fun `send refuses to write a body on any method but POST, and makes no request`() = runBlocking {
        startServer()
        val c = client()
        for (method in listOf("DELETE", "GET", "PUT", "PATCH", "HEAD")) {
            val before = requestCount
            val failed = runCatching { c.send(method, "/x", 1_000, mapOf("a" to "b"), authenticated = true) }.exceptionOrNull()

            assertTrue("$method with a body should be refused, was $failed", failed is IllegalArgumentException)
            assertEquals("$method must not reach the network", before, requestCount)
        }
    }

    @Test
    fun `send writes a body on POST and none on a bodiless DELETE or GET`() = runBlocking {
        startServer()
        val c = client()

        c.send("POST", "/a", 1_000, mapOf("k" to "v"), authenticated = false)
        assertTrue(lastBody!!.contains(""""k":"v""""))

        c.send("DELETE", "/b", 1_000, null, authenticated = true)
        assertEquals("", lastBody)
        c.send("GET", "/c", 1_000, null, authenticated = true)
        assertEquals("", lastBody)
    }

    @Test
    fun `no endpoint the client exposes sends a body on a non-POST method`() = runBlocking {
        startServer()
        val c = client()
        val seen = mutableListOf<Pair<String?, String?>>()
        fun note() { seen += lastMethod to lastBody }

        c.getMe(); note()
        c.fetchBundle("abc"); note()
        c.deleteMe("jwt"); note()
        c.deleteAccountByIdToken("id"); note()
        c.postEvents(emptyList()); note()
        c.exchange("id"); note()
        c.whatsappStart("+919876543210"); note()
        c.whatsappVerify("+919876543210", "123456"); note()

        for ((method, body) in seen) {
            if (method != "POST") assertEquals("a $method carried a body", "", body)
        }
        assertEquals(listOf("GET", "GET", "DELETE", "POST", "POST", "POST", "POST", "POST"), seen.map { it.first })
    }
}
