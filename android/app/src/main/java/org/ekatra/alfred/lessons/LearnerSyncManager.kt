package org.ekatra.alfred.lessons

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ekatra.alfred.data.local.SyncQueueDao
import org.ekatra.alfred.data.model.SyncQueueItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class SyncStatus { IDLE, RUNNING, SYNCED, OFFLINE, NOT_CONFIGURED, UNAUTHORIZED, ERROR }

data class SyncReport(
    val status: SyncStatus,
    val lessonsDownloaded: Int = 0,
    val eventsResolved: Int = 0,
    val requestsDropped: Int = 0,
    val message: String? = null,
    /** One entry per lesson request the server refused, e.g. "topic must be 2-120 characters". */
    val requestErrors: List<String> = emptyList()
)

/**
 * Syncs the learner's own learning data with the platform. Deliberately separate from [org.ekatra.alfred.data.sync.SyncManager]:
 * this is product data, not analytics, so it is not gated on analytics consent or Firebase.
 *
 * Queue rows live in the existing `sync_queue` table with types [TYPE_LEARNER_EVENT] and
 * [TYPE_LESSON_REQUEST]; each payload carries a UUID so the server can de-duplicate a retry.
 */
@Singleton
class LearnerSyncManager @Inject constructor(
    private val api: LearnerSyncApi,
    private val queue: SyncQueueDao,
    private val bundles: LessonStore,
    private val progress: LessonProgressStore,
    private val config: SyncConfig,
    private val connectivity: Connectivity
) {
    companion object {
        private const val TAG = "LearnerSync"
        const val TYPE_LEARNER_EVENT = "learner_event"
        const val TYPE_LESSON_REQUEST = "lesson_request"
        val QUEUE_TYPES = listOf(TYPE_LEARNER_EVENT, TYPE_LESSON_REQUEST)
        private const val MAX_REQUESTS_PER_RUN = 10
        /** Same give-up point as the analytics SyncManager. */
        const val MAX_RETRIES = 5
        /** Lesson-request statuses that mean the request itself is bad; everything else is retried. */
        private val DROP_REQUEST_CODES = setOf(400, 422)
        private const val MAX_BUNDLE_FETCHES = 5

        fun nowIso(millis: Long = System.currentTimeMillis()): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(millis))
    }

    private val gson = Gson()
    private val runLock = Mutex()

    /** Bundle and progress files are written during a pass, so a pass never runs on Main. Tests swap it. */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val _status = MutableStateFlow(if (config.isConfigured) SyncStatus.IDLE else SyncStatus.NOT_CONFIGURED)
    val status: StateFlow<SyncStatus> = _status

    val isConfigured: Boolean get() = config.isConfigured

    /** Rows waiting to go to the server (events + lesson requests). */
    val pendingCount: Flow<Int> = queue.countByTypes(QUEUE_TYPES)

    suspend fun pendingRequestTopics(): List<String> =
        queue.getPendingByType(TYPE_LESSON_REQUEST, 50).mapNotNull { parseRequest(it)?.topic }

    // ---- enqueue ----

    suspend fun enqueueQuizAnswer(courseId: String, lessonId: String, questionId: String, answer: String, correct: Boolean) {
        enqueueEvent(LearnerEvent(
            clientEventId = eventId("quiz", courseId, lessonId, questionId),
            type = LearnerEvent.QUIZ_ANSWERED,
            courseId = courseId, lessonId = lessonId, questionId = questionId,
            answer = answer, clientCorrect = correct, occurredAt = nowIso()
        ))
    }

    suspend fun enqueueLessonCompleted(courseId: String, lessonId: String) {
        enqueueEvent(LearnerEvent(
            clientEventId = eventId("done", courseId, lessonId),
            type = LearnerEvent.LESSON_COMPLETED,
            courseId = courseId, lessonId = lessonId, occurredAt = nowIso()
        ))
    }

    /**
     * One id per question (and per lesson completion), not per tap. If the app dies between writing
     * the queue row and the progress file, the learner answers again and re-queues the same id, which
     * the server counts as a duplicate instead of a second answer.
     */
    private fun eventId(vararg parts: String): String =
        UUID.nameUUIDFromBytes(parts.joinToString("|").toByteArray(Charsets.UTF_8)).toString()

    private suspend fun enqueueEvent(event: LearnerEvent) {
        queue.insert(SyncQueueItem(type = TYPE_LEARNER_EVENT, payload = gson.toJson(event)))
    }

    suspend fun enqueueLessonRequest(topic: String, language: String, clientRequestId: String = UUID.randomUUID().toString()): String {
        queue.insert(SyncQueueItem(
            type = TYPE_LESSON_REQUEST,
            payload = gson.toJson(LessonRequestPayload(clientRequestId, topic, language, nowIso()))
        ))
        return clientRequestId
    }

    /**
     * Try a refused request again as a brand new one: a new clientRequestId, because the server
     * remembers the old id as failed. @return false if [clientRequestId] is not a failed request.
     */
    suspend fun retryFailedRequest(clientRequestId: String): Boolean {
        val failed = progress.data.failedRequests.firstOrNull { it.clientRequestId == clientRequestId } ?: return false
        enqueueLessonRequest(failed.topic, failed.language) // queue first, then forget the failure
        progress.removeFailedRequest(clientRequestId)
        return true
    }

    fun dismissFailedRequest(clientRequestId: String) = progress.removeFailedRequest(clientRequestId)

    // ---- sync ----

    /** One full pass: lesson requests, then events in batches, then refresh /me. Never overlaps itself. */
    suspend fun sync(): SyncReport {
        if (!config.isConfigured) return SyncReport(SyncStatus.NOT_CONFIGURED).also { _status.value = it.status }
        if (!connectivity.isOnline()) return SyncReport(SyncStatus.OFFLINE).also { _status.value = it.status }
        // Queue behind a running pass rather than skipping: rows enqueued after that pass read the
        // queue would otherwise wait for the next trigger. Passes never overlap.
        return runLock.withLock {
            _status.value = SyncStatus.RUNNING
            val report = withContext(ioDispatcher) { runPass() }
            _status.value = report.status
            report
        }
    }

    private suspend fun runPass(): SyncReport {
        var downloaded = 0
        var resolved = 0
        var dropped = 0
        var error: String? = null
        val requestErrors = mutableListOf<String>()

        // 1. Lesson requests first, so the bundle exists before its events are posted. One failing
        // request never stops the others: keep going.
        for (item in queue.getPendingByType(TYPE_LESSON_REQUEST, MAX_REQUESTS_PER_RUN)) {
            val req = parseRequest(item)
            if (req == null) { queue.delete(item); dropped++; continue }
            when (val r = api.requestLesson(req.topic, req.language, req.clientRequestId)) {
                is ApiResult.Ok -> { bundles.save(r.value.bundle); queue.delete(item); downloaded++ }
                is ApiResult.Unauthorized -> return SyncReport(SyncStatus.UNAUTHORIZED, downloaded, resolved, dropped, r.message, requestErrors)
                is ApiResult.Retryable -> { retryRequest(item); error = r.message }
                is ApiResult.Rejected -> {
                    if (r.code in DROP_REQUEST_CODES) {
                        // Only 400/422 mean the topic itself is unacceptable. Tell the learner why.
                        Log.w(TAG, "Lesson request dropped (${r.code}): ${r.message}")
                        // Keep the reason (the queue row is about to go) so the UI can show it and offer a retry.
                        progress.addFailedRequest(FailedLessonRequest(req.clientRequestId, req.topic, req.language, r.message))
                        queue.delete(item); dropped++; requestErrors += r.message
                    } else {
                        // 404/408/409/413 and anything else can come from a redeploying service or a
                        // proxy, or from the server still generating this same id (409 in_progress).
                        Log.w(TAG, "Lesson request will be retried (${r.code}): ${r.message}")
                        retryRequest(item)
                    }
                    error = r.message
                }
            }
        }

        // 2. Events, 20 at a time.
        while (true) {
            val items = queue.getPendingByType(TYPE_LEARNER_EVENT, LearnerSyncClient.EVENT_BATCH_LIMIT)
            if (items.isEmpty()) break
            val parsed = items.map { it to parseEvent(it) }
            parsed.filter { it.second == null }.forEach { queue.delete(it.first); dropped++ }
            val valid = parsed.mapNotNull { (item, ev) -> ev?.let { item to it } }
            if (valid.isEmpty()) continue

            when (val r = api.postEvents(valid.map { it.second })) {
                is ApiResult.Ok -> {
                    val done = r.value.resolvedIds()
                    val handled = valid.filter { it.second.clientEventId in done }
                    handled.forEach { queue.delete(it.first) }
                    resolved += handled.size
                    r.value.profile?.let { progress.saveProfile(it) }
                    // The server named none of them: stop rather than spin on the same batch.
                    if (handled.isEmpty()) { valid.forEach { queue.incrementRetry(it.first.id) }; error = "Server resolved no events"; break }
                }
                is ApiResult.Unauthorized -> return SyncReport(SyncStatus.UNAUTHORIZED, downloaded, resolved, dropped, r.message, requestErrors)
                is ApiResult.Retryable -> { valid.forEach { queue.incrementRetry(it.first.id) }; error = r.message; break }
                // Per-event rejections arrive inside a 200 (data.rejected). Any whole-request error
                // other than 401 means the request was wrong, not the events: keep every row, count a
                // retry and log it. Rows are only ever deleted per event, from inside a 200.
                is ApiResult.Rejected -> {
                    Log.w(TAG, "Events batch rejected (${r.code}): ${r.message}")
                    valid.forEach { queue.incrementRetry(it.first.id) }
                    error = r.message
                    break
                }
            }
        }

        // 3. Refresh the server view and pick up courses made elsewhere (e.g. on WhatsApp).
        when (val me = api.getMe()) {
            is ApiResult.Ok -> {
                progress.saveMe(me.value)
                downloaded += fetchMissingBundles(me.value)
            }
            is ApiResult.Unauthorized -> return SyncReport(SyncStatus.UNAUTHORIZED, downloaded, resolved, dropped, me.message, requestErrors)
            is ApiResult.Retryable -> error = error ?: me.message
            is ApiResult.Rejected -> Log.w(TAG, "getMe rejected: ${me.message}")
        }

        val status = if (error != null && downloaded == 0 && resolved == 0) SyncStatus.ERROR else SyncStatus.SYNCED
        return SyncReport(status, downloaded, resolved, dropped, error, requestErrors)
    }

    /**
     * Count a failed attempt. After [MAX_RETRIES] the request goes to the back of the queue (same
     * clientRequestId, fresh createdAt) so one stubborn topic cannot keep the others waiting.
     */
    private suspend fun retryRequest(item: SyncQueueItem) {
        if (item.retryCount + 1 >= MAX_RETRIES) {
            queue.delete(item)
            queue.insert(SyncQueueItem(type = item.type, payload = item.payload))
        } else {
            queue.incrementRetry(item.id)
        }
    }

    private suspend fun fetchMissingBundles(me: MeResponse): Int {
        var n = 0
        val unavailable = progress.data.unavailableCourseIds
        // Courses without a generator are authored ones, which the bundle endpoint refuses (422).
        val wanted = me.courses
            .filter { it.generator != null && it.courseId !in unavailable && !bundles.has(it.courseId) }
            .take(MAX_BUNDLE_FETCHES)
        for (course in wanted) {
            when (val r = api.fetchBundle(course.courseId)) {
                is ApiResult.Ok -> { bundles.save(r.value); n++ }
                // Not enrolled (404) or authored (422): remember, so we do not ask again every pass.
                is ApiResult.Rejected -> if (r.code == 404 || r.code == 422) progress.markCourseUnavailable(course.courseId)
                else -> Log.d(TAG, "Bundle ${course.courseId} will be retried: $r")
            }
        }
        return n
    }

    private fun parseRequest(item: SyncQueueItem): LessonRequestPayload? =
        runCatching { gson.fromJson(item.payload, LessonRequestPayload::class.java) }.getOrNull()
            ?.takeIf { !it.clientRequestId.isNullOrBlank() && !it.topic.isNullOrBlank() }

    private fun parseEvent(item: SyncQueueItem): LearnerEvent? =
        runCatching { gson.fromJson(item.payload, LearnerEvent::class.java) }.getOrNull()
            ?.takeIf { !it.clientEventId.isNullOrBlank() && !it.type.isNullOrBlank() }
}
