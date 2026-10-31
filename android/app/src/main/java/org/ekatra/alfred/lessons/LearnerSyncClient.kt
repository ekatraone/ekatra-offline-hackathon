package org.ekatra.alfred.lessons

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HttpURLConnection client for the sync API in CONTRACT.md. Every call sends
 * `Authorization: Bearer <learner JWT>` and unwraps the `{success, data | error}` envelope.
 */
@Singleton
class LearnerSyncClient internal constructor(
    private val config: SyncConfig,
    // Seam for unit tests: the Firebase Performance plugin rewrites URL.openConnection() in app
    // classes at build time, which cannot run on the JVM. Tests pass their own factory.
    private val openConnection: (URL) -> HttpURLConnection
) : LearnerSyncApi {

    @Inject constructor(config: SyncConfig) : this(config, ::defaultOpen)

    companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 60_000
        /** Muse Spark takes 60-240 s to write a lesson and the server gives up at 240 s; wait 300 s so we never quit first. */
        const val LESSON_READ_TIMEOUT_MS = 300_000
        const val EVENT_BATCH_LIMIT = 20
    }

    private val gson = Gson()

    override suspend fun requestLesson(topic: String, language: String, clientRequestId: String) =
        call("POST", "/api/sync/lessons/request", LESSON_READ_TIMEOUT_MS,
            mapOf("topic" to topic, "language" to language, "clientRequestId" to clientRequestId),
            RequestLessonResult::class.java)

    override suspend fun fetchBundle(courseId: String) =
        call("GET", "/api/sync/courses/${courseId.filter { it.isLetterOrDigit() || it == '-' }}/bundle",
            READ_TIMEOUT_MS, null, LessonBundle::class.java)

    override suspend fun postEvents(events: List<LearnerEvent>) =
        call("POST", "/api/sync/events", READ_TIMEOUT_MS, mapOf("events" to events), EventsResponse::class.java)

    override suspend fun getMe() =
        call("GET", "/api/sync/me", READ_TIMEOUT_MS, null, MeResponse::class.java)

    private suspend fun <T> call(
        method: String,
        path: String,
        readTimeoutMs: Int,
        body: Any?,
        type: Class<T>
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) return@withContext ApiResult.Rejected(0, "Sync is not configured")
        var conn: HttpURLConnection? = null
        try {
            conn = openConnection(URL(config.baseUrl.trimEnd('/') + path)).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = readTimeoutMs
                setRequestProperty("Authorization", "Bearer ${config.token}")
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(gson.toJson(body).toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            interpret(code, text, type)
        } catch (e: IOException) {
            ApiResult.Retryable(e.message ?: e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            // Malformed JSON or an unexpected envelope; the server may recover, so do not drop the item.
            ApiResult.Retryable("Bad response: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    private fun <T> interpret(code: Int, text: String, type: Class<T>): ApiResult<T> {
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
        val error = root?.get("error")?.takeIf { it.isJsonPrimitive }?.asString ?: "HTTP $code"
        return when {
            code in 200..299 -> {
                val data = root?.get("data")
                if (root?.get("success")?.asBoolean == true && data != null && !data.isJsonNull) {
                    ApiResult.Ok(gson.fromJson(data, type))
                } else {
                    ApiResult.Retryable("Bad response envelope")
                }
            }
            code == 401 -> ApiResult.Unauthorized(error)
            code == 429 || code >= 500 -> ApiResult.Retryable(error)
            else -> ApiResult.Rejected(code, error)
        }
    }
}

private fun defaultOpen(url: URL): HttpURLConnection = url.openConnection() as HttpURLConnection
