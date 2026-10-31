package org.ekatra.alfred.lessons

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Talks to a real loopback HTTP server, so URL building, headers, bodies and status mapping are exercised. */
class LearnerSyncClientTest {

    private lateinit var server: ServerSocket
    private var status = 200
    private var responseBody = """{"success":true,"data":{}}"""
    private var lastAuth: String? = null
    private var lastMethod: String? = null
    private var lastPath: String? = null
    private var lastBody: String? = null

    private fun client(token: String = "tok") =
        testClient(SyncConfig("http://127.0.0.1:${server.localPort}/", token))

    // java.net.URL.openConnection() directly, bypassing the Firebase Perf-instrumented default.
    private fun testClient(config: SyncConfig) =
        LearnerSyncClient(config) { url -> url.openConnection() as java.net.HttpURLConnection }

    /** Minimal HTTP/1.1 server: one request per connection, Connection: close. */
    @Before
    fun start() {
        server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = try { server.accept() } catch (e: Exception) { return@thread }
                sock.use {
                    val input = it.getInputStream().buffered()
                    fun line(): String {
                        val sb = StringBuilder()
                        while (true) {
                            val b = input.read()
                            if (b < 0 || b == '\n'.code) break
                            if (b != '\r'.code) sb.append(b.toChar())
                        }
                        return sb.toString()
                    }
                    val (method, target) = line().split(" ").let { p -> p[0] to p[1] }
                    var length = 0
                    var auth: String? = null
                    while (true) {
                        val h = line()
                        if (h.isEmpty()) break
                        val name = h.substringBefore(":").lowercase()
                        val value = h.substringAfter(":").trim()
                        if (name == "content-length") length = value.toInt()
                        if (name == "authorization") auth = value
                    }
                    val body = ByteArray(length).also { buf ->
                        var read = 0
                        while (read < length) { val n = input.read(buf, read, length - read); if (n < 0) break; read += n }
                    }
                    lastMethod = method; lastPath = target.substringBefore("?"); lastAuth = auth
                    lastBody = body.toString(Charsets.UTF_8)
                    val bytes = responseBody.toByteArray()
                    it.getOutputStream().apply {
                        write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            }
        }
    }

    @After fun stop() { server.close() }

    @Test
    fun `read timeout for lesson generation is at least 240 seconds`() {
        assertTrue(LearnerSyncClient.LESSON_READ_TIMEOUT_MS >= 240_000)
        assertTrue(LearnerSyncClient.READ_TIMEOUT_MS < LearnerSyncClient.LESSON_READ_TIMEOUT_MS)
    }

    @Test
    fun `requestLesson posts the contract body with a bearer token and unwraps the bundle`() = runBlocking {
        responseBody = """{"success":true,"data":{"courseId":"course-1","bundle":{"version":"abc",
            "course":{"id":"course-1","title":"Fractions","description":"d","language":"en","generator":{"provider":"meta","model":"muse-spark-1.3"}},
            "lessons":[{"id":"l1","kind":"module","title":"Halves","content":"c","day":1,"order":1,
              "questions":[{"id":"q1","question":"?","options":["a","b","c","d"],"correctAnswer":"B","explanation":"e"}]}]}}}"""

        val r = client().requestLesson("fractions", "en", "req-1") as ApiResult.Ok

        assertEquals("Bearer tok", lastAuth)
        assertEquals("POST", lastMethod)
        assertEquals("/api/sync/lessons/request", lastPath)
        assertTrue(lastBody!!.contains(""""clientRequestId":"req-1""""))
        assertTrue(lastBody!!.contains(""""topic":"fractions""""))
        assertEquals("course-1", r.value.courseId)
        assertEquals("B", r.value.bundle.lessons.single().questions.single().correctAnswer)
        assertEquals("muse-spark-1.3", r.value.bundle.course.generator?.model)
    }

    @Test
    fun `postEvents sends the events array and parses accepted, duplicates, rejected and profile`() = runBlocking {
        responseBody = """{"success":true,"data":{"accepted":["a"],"duplicates":["b"],
            "rejected":[{"clientEventId":"c","reason":"bad"}],
            "profile":{"mastery":{"l1":0.5},"struggles":["l2"],"preferredHours":[9],"velocity":1,
              "totals":{"events":3,"quizAnswered":2,"correct":1,"wrong":1,"lessonsCompleted":0}}}}"""
        val ev = LearnerEvent("a", "quiz_answered", "c1", "l1", "q1", "B", true, "2026-11-01T00:00:00.000Z")

        val r = client().postEvents(listOf(ev)) as ApiResult.Ok

        assertEquals("/api/sync/events", lastPath)
        assertTrue(lastBody!!.startsWith("""{"events":[{"clientEventId":"a""""))
        assertEquals(setOf("a", "b", "c"), r.value.resolvedIds())
        assertEquals(0.5, r.value.profile!!.mastery["l1"]!!, 0.0)
        assertEquals(1, r.value.profile!!.totals.wrong)
    }

    @Test
    fun `lesson_completed events omit the quiz-only fields`() = runBlocking {
        client().postEvents(listOf(LearnerEvent("a", "lesson_completed", "c1", "l1", occurredAt = "t")))
        assertTrue(!lastBody!!.contains("questionId") && !lastBody!!.contains("clientCorrect"))
    }

    @Test
    fun `getMe and fetchBundle use GET`() = runBlocking {
        client().getMe()
        assertEquals("GET" to "/api/sync/me", lastMethod to lastPath)
        client().fetchBundle("abc-123")
        assertEquals("GET" to "/api/sync/courses/abc-123/bundle", lastMethod to lastPath)
    }

    @Test
    fun `status codes map to unauthorized, retryable and rejected`() = runBlocking {
        responseBody = """{"success":false,"error":"nope"}"""
        status = 401
        assertEquals(ApiResult.Unauthorized("nope"), client().getMe())
        status = 429
        assertEquals(ApiResult.Retryable("nope"), client().getMe())
        status = 503
        assertEquals(ApiResult.Retryable("nope"), client().getMe())
        status = 422
        assertEquals(ApiResult.Rejected(422, "nope"), client().fetchBundle("x"))
        status = 404
        assertEquals(ApiResult.Rejected(404, "nope"), client().fetchBundle("x"))
    }

    @Test
    fun `a dead server is retryable, not a crash`() = runBlocking {
        val c = client()
        server.close()
        assertTrue(c.getMe() is ApiResult.Retryable)
    }

    @Test
    fun `a 200 with a non-json body is retryable`() = runBlocking {
        responseBody = "<html>gateway</html>"
        assertTrue(client().getMe() is ApiResult.Retryable)
    }

    @Test
    fun `an unconfigured client never opens a connection`() = runBlocking {
        val r = testClient(SyncConfig("", "")).getMe()
        assertTrue(r is ApiResult.Rejected)
    }
}
