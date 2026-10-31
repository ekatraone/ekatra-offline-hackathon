package org.ekatra.alfred.lessons

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
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
    val message: String? = null
)

sealed interface LessonRequestOutcome {
    data class Downloaded(val bundle: LessonBundle) : LessonRequestOutcome
    /** Saved to the queue; it is generated on the next sync. */
    data class Queued(val clientRequestId: String) : LessonRequestOutcome
    data class Failed(val message: String) : LessonRequestOutcome
}

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
        private const val MAX_BUNDLE_FETCHES = 5

        fun nowIso(millis: Long = System.currentTimeMillis()): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(millis))
    }

    private val gson = Gson()
    private val runLock = Mutex()
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
            clientEventId = UUID.randomUUID().toString(),
            type = LearnerEvent.QUIZ_ANSWERED,
            courseId = courseId, lessonId = lessonId, questionId = questionId,
            answer = answer, clientCorrect = correct, occurredAt = nowIso()
        ))
    }

    suspend fun enqueueLessonCompleted(courseId: String, lessonId: String) {
        enqueueEvent(LearnerEvent(
            clientEventId = UUID.randomUUID().toString(),
            type = LearnerEvent.LESSON_COMPLETED,
            courseId = courseId, lessonId = lessonId, occurredAt = nowIso()
        ))
    }

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
     * Ask for a lesson. Online: generate now. Offline, or if the network drops mid-call: queue the
     * same clientRequestId, so the server returns the same course instead of generating twice.
     */
    suspend fun requestLesson(topic: String, language: String): LessonRequestOutcome {
        if (!config.isConfigured) return LessonRequestOutcome.Failed("Lessons sync is not configured")
        val id = UUID.randomUUID().toString()
        if (!connectivity.isOnline()) {
            enqueueLessonRequest(topic, language, id)
            return LessonRequestOutcome.Queued(id)
        }
        return when (val r = api.requestLesson(topic, language, id)) {
            is ApiResult.Ok -> {
                bundles.save(r.value.bundle)
                LessonRequestOutcome.Downloaded(r.value.bundle)
            }
            is ApiResult.Retryable -> {
                enqueueLessonRequest(topic, language, id)
                LessonRequestOutcome.Queued(id)
            }
            is ApiResult.Unauthorized -> {
                _status.value = SyncStatus.UNAUTHORIZED
                LessonRequestOutcome.Failed("Not signed in to the lessons service (${r.message})")
            }
            is ApiResult.Rejected -> LessonRequestOutcome.Failed(r.message)
        }
    }

    // ---- sync ----

    /** One full pass: lesson requests, then events in batches, then refresh /me. Never overlaps itself. */
    suspend fun sync(): SyncReport {
        if (!config.isConfigured) return SyncReport(SyncStatus.NOT_CONFIGURED).also { _status.value = it.status }
        if (!connectivity.isOnline()) return SyncReport(SyncStatus.OFFLINE).also { _status.value = it.status }
        if (!runLock.tryLock()) return SyncReport(SyncStatus.RUNNING)
        try {
            _status.value = SyncStatus.RUNNING
            val report = runPass()
            _status.value = report.status
            return report
        } finally {
            runLock.unlock()
        }
    }

    private suspend fun runPass(): SyncReport {
        var downloaded = 0
        var resolved = 0
        var dropped = 0
        var error: String? = null

        // 1. Lesson requests first, so the bundle exists before its events are posted.
        for (item in queue.getPendingByType(TYPE_LESSON_REQUEST, MAX_REQUESTS_PER_RUN)) {
            val req = parseRequest(item)
            if (req == null) { queue.delete(item); dropped++; continue }
            when (val r = api.requestLesson(req.topic, req.language, req.clientRequestId)) {
                is ApiResult.Ok -> { bundles.save(r.value.bundle); queue.delete(item); downloaded++ }
                is ApiResult.Unauthorized -> return SyncReport(SyncStatus.UNAUTHORIZED, downloaded, resolved, dropped, r.message)
                is ApiResult.Retryable -> { queue.incrementRetry(item.id); error = r.message; break }
                is ApiResult.Rejected -> { queue.delete(item); dropped++; error = r.message }
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
                is ApiResult.Unauthorized -> return SyncReport(SyncStatus.UNAUTHORIZED, downloaded, resolved, dropped, r.message)
                is ApiResult.Retryable -> { valid.forEach { queue.incrementRetry(it.first.id) }; error = r.message; break }
                // A whole-batch 4xx will never succeed; keep going rather than wedge the queue behind it.
                is ApiResult.Rejected -> { valid.forEach { queue.delete(it.first) }; dropped += valid.size; error = r.message }
            }
        }

        // 3. Refresh the server view and pick up courses made elsewhere (e.g. on WhatsApp).
        when (val me = api.getMe()) {
            is ApiResult.Ok -> {
                progress.saveMe(me.value)
                downloaded += fetchMissingBundles(me.value)
            }
            is ApiResult.Unauthorized -> return SyncReport(SyncStatus.UNAUTHORIZED, downloaded, resolved, dropped, me.message)
            is ApiResult.Retryable -> error = error ?: me.message
            is ApiResult.Rejected -> Log.w(TAG, "getMe rejected: ${me.message}")
        }

        val status = if (error != null && downloaded == 0 && resolved == 0) SyncStatus.ERROR else SyncStatus.SYNCED
        return SyncReport(status, downloaded, resolved, dropped, error)
    }

    private suspend fun fetchMissingBundles(me: MeResponse): Int {
        var n = 0
        for (course in me.courses.filter { !bundles.has(it.courseId) }.take(MAX_BUNDLE_FETCHES)) {
            when (val r = api.fetchBundle(course.courseId)) {
                is ApiResult.Ok -> { bundles.save(r.value); n++ }
                // 404/422 (not enrolled, authored course) are expected and simply skipped.
                else -> Log.d(TAG, "Skipping bundle ${course.courseId}: $r")
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
