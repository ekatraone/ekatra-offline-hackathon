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
) : LearnerSyncApi, PhoneAuthApi {

    @Inject constructor(config: SyncConfig) : this(config, ::defaultOpen)

    companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 60_000
        /** Writing a lesson takes 60-240 s and the server gives up at 240 s; wait 300 s so we never quit first. */
        const val LESSON_READ_TIMEOUT_MS = 300_000
        const val EVENT_BATCH_LIMIT = 20
        const val DEFAULT_RESEND_SECONDS = 30
        const val DEFAULT_EXPIRES_SECONDS = 600
        /** Lesson images are about 150-300 KB; refuse anything absurd rather than fill memory. */
        const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
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

    override suspend fun downloadImage(url: String): ApiResult<ByteArray> = withContext(Dispatchers.IO) {
        val parsed = runCatching { URL(url) }.getOrNull()
        if (parsed == null || parsed.protocol !in setOf("http", "https")) {
            return@withContext ApiResult.Rejected(0, "Not an http(s) image URL")
        }
        var conn: HttpURLConnection? = null
        try {
            conn = openConnection(parsed).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "image/jpeg")
            }
            val code = conn.responseCode
            when {
                code in 200..299 -> {
                    val bytes = conn.inputStream.use { readCapped(it, MAX_IMAGE_BYTES) }
                        ?: return@withContext ApiResult.Rejected(413, "Image is larger than $MAX_IMAGE_BYTES bytes")
                    // Anything that is not a JPEG (an HTML error page from a proxy, say) is not an image.
                    if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) {
                        ApiResult.Rejected(415, "Not a JPEG")
                    } else ApiResult.Ok(bytes)
                }
                code == 429 || code == 408 || code >= 500 -> ApiResult.Retryable("HTTP $code")
                else -> ApiResult.Rejected(code, "HTTP $code")
            }
        } catch (e: IOException) {
            ApiResult.Retryable(e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    /** @return the bytes, or null if the stream is longer than [max]. */
    private fun readCapped(input: java.io.InputStream, max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private class Raw(val code: Int, val text: String)

    /** One HTTP exchange. Throws IOException on a network failure. */
    private fun send(method: String, path: String, readTimeoutMs: Int, body: Any?, authenticated: Boolean, token: String? = null): Raw {
        var conn: HttpURLConnection? = null
        try {
            conn = openConnection(URL(config.baseUrl.trimEnd('/') + path)).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = readTimeoutMs
                if (authenticated) {
                    // Never follow a redirect with the bearer token attached: it could be sent to another host.
                    instanceFollowRedirects = false
                    setRequestProperty("Authorization", "Bearer ${token ?: config.token}")
                }
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(gson.toJson(body).toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return Raw(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } finally {
            conn?.disconnect()
        }
    }

    private suspend fun <T> call(
        method: String,
        path: String,
        readTimeoutMs: Int,
        body: Any?,
        type: Class<T>
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) return@withContext ApiResult.Rejected(0, "Sync is not configured")
        try {
            val raw = send(method, path, readTimeoutMs, body, authenticated = true)
            interpret(raw.code, raw.text, type)
        } catch (e: IOException) {
            ApiResult.Retryable(e.message ?: e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            // Malformed JSON or an unexpected envelope; the server may recover, so do not drop the item.
            ApiResult.Retryable("Bad response: ${e.message}")
        }
    }

    // ---- public auth endpoints: no Authorization header ----

    /** Run a public POST and hand the status and body to [map]; a network failure goes to [onError]. */
    private suspend fun <R> publicPost(
        path: String, body: Any, notConfigured: R, onError: (String) -> R, map: (Int, String) -> R
    ): R = withContext(Dispatchers.IO) {
        if (config.baseUrl.isBlank()) return@withContext notConfigured
        try {
            val raw = send("POST", path, READ_TIMEOUT_MS, body, authenticated = false)
            map(raw.code, raw.text)
        } catch (e: IOException) {
            onError(e.message ?: e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            onError("Bad response: ${e.message}")
        }
    }

    private class Envelope(val root: com.google.gson.JsonObject?) {
        /** Only a body with a `success` flag is an answer from our API; a proxy's page is not. */
        val fromApi: Boolean get() = root?.get("success")?.takeIf { it.isJsonPrimitive } != null
        val ok: Boolean get() = root?.get("success")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
        fun error(code: Int): String = root?.get("error")?.takeIf { it.isJsonPrimitive }?.asString ?: "HTTP $code"
        fun <T> data(type: Class<T>, gson: Gson): T? =
            root?.get("data")?.takeIf { it.isJsonObject }?.let { runCatching { gson.fromJson(it, type) }.getOrNull() }
    }

    private fun envelope(text: String) = Envelope(runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull())

    override suspend fun exchange(idToken: String): ExchangeOutcome =
        publicPost("/api/sync/auth/exchange", mapOf("idToken" to idToken), ExchangeOutcome.NotConfigured,
            { ExchangeOutcome.Failed(it) }) { code, text ->
            val env = envelope(text)
            when {
                code in 200..299 -> {
                    val data = env.data(ExchangeResponse::class.java, gson)
                    if (env.ok && !data?.token.isNullOrBlank()) {
                        ExchangeOutcome.Success(data!!.token!!, data.learner?.id, data.learner?.name, data.learner?.phone, data.learner?.email)
                    } else ExchangeOutcome.Failed("Bad response")
                }
                code == 401 -> ExchangeOutcome.Unauthorized
                else -> ExchangeOutcome.Failed(env.error(code))
            }
        }

    override suspend fun deleteMe(token: String): DeleteOutcome = withContext(Dispatchers.IO) {
        if (config.baseUrl.isBlank()) return@withContext DeleteOutcome.NotConfigured
        try {
            val raw = send("DELETE", "/api/sync/me", READ_TIMEOUT_MS, null, authenticated = true, token = token)
            when {
                raw.code in 200..299 -> DeleteOutcome.Deleted
                raw.code == 401 -> DeleteOutcome.Unauthorized
                // A 404 in our own {success:false} envelope says the learner is already gone. A bare or HTML 404
                // (a proxy mid-redeploy) says nothing about the learner, so that one is retried.
                raw.code == 404 && envelope(raw.text).let { it.fromApi && !it.ok } -> DeleteOutcome.Deleted
                else -> DeleteOutcome.Failed(envelope(raw.text).error(raw.code))
            }
        } catch (e: IOException) {
            DeleteOutcome.Failed(e.message ?: e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            DeleteOutcome.Failed("Bad response: ${e.message}")
        }
    }

    override suspend fun deleteAccountByIdToken(idToken: String): DeleteOutcome = withContext(Dispatchers.IO) {
        if (config.baseUrl.isBlank()) return@withContext DeleteOutcome.NotConfigured
        try {
            val raw = send("DELETE", "/api/sync/auth/account", READ_TIMEOUT_MS, mapOf("idToken" to idToken), authenticated = false)
            when {
                raw.code in 200..299 -> DeleteOutcome.Deleted        // { deleted: true } or { deleted: false }: nothing left either way
                raw.code == 401 -> DeleteOutcome.Unauthorized
                raw.code == 404 && envelope(raw.text).let { it.fromApi && !it.ok } -> DeleteOutcome.Deleted
                else -> DeleteOutcome.Failed(envelope(raw.text).error(raw.code))
            }
        } catch (e: IOException) {
            DeleteOutcome.Failed(e.message ?: e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            DeleteOutcome.Failed("Bad response: ${e.message}")
        }
    }

    override suspend fun whatsappStart(phone: String): StartOutcome =
        publicPost("/api/auth/whatsapp/start", mapOf("phone" to phone), StartOutcome.NotConfigured,
            { StartOutcome.NetworkError(it) }) { code, text ->
            val env = envelope(text)
            when {
                code in 200..299 -> {
                    if (!env.ok) StartOutcome.NetworkError("Bad response") else {
                        val d = env.data(StartResponse::class.java, gson)
                        StartOutcome.Sent(d?.resendAfterSeconds ?: DEFAULT_RESEND_SECONDS, d?.expiresInSeconds ?: DEFAULT_EXPIRES_SECONDS)
                    }
                }
                code == 429 -> StartOutcome.RateLimited
                code >= 500 || !env.fromApi -> StartOutcome.NetworkError(env.error(code))
                else -> StartOutcome.BadPhone(env.error(code))
            }
        }

    override suspend fun whatsappVerify(phone: String, code: String): VerifyOutcome =
        publicPost("/api/auth/whatsapp/verify", mapOf("phone" to phone, "code" to code), VerifyOutcome.NotConfigured,
            { VerifyOutcome.NetworkError(it) }) { status, text ->
            val env = envelope(text)
            when {
                status in 200..299 -> {
                    val token = env.data(VerifyResponse::class.java, gson)?.firebaseCustomToken
                    if (env.ok && !token.isNullOrBlank()) VerifyOutcome.Verified(token) else VerifyOutcome.NetworkError("Bad response")
                }
                status == 401 -> VerifyOutcome.WrongCode
                status == 429 -> VerifyOutcome.RateLimited
                status >= 500 || !env.fromApi -> VerifyOutcome.NetworkError(env.error(status))
                else -> VerifyOutcome.MalformedCode
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
            // A 4xx is a real answer from our API only if it carries a {success:false} envelope. A bare
            // 404 or 400 page (a proxy during a redeploy, say) says nothing about the request itself.
            root?.get("success")?.takeIf { it.isJsonPrimitive }?.asBoolean != false -> ApiResult.Retryable("HTTP $code (not an API response)")
            else -> ApiResult.Rejected(code, error)
        }
    }
}

private fun defaultOpen(url: URL): HttpURLConnection = url.openConnection() as HttpURLConnection
