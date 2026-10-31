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
import java.io.File
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
    val requestErrors: List<String> = emptyList(),
    val imagesDownloaded: Int = 0
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
        /** Bounds one pass: a course is a cover plus about six module images. */
        private const val MAX_IMAGES_PER_PASS = 16
        /** Stop the image step after this many failures in a row (probably no real connection). */
        private const val MAX_CONSECUTIVE_IMAGE_FAILURES = 3

        fun nowIso(millis: Long = System.currentTimeMillis()): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(millis))
    }

    private val gson = Gson()
    private val eventsLock = Mutex()
    private val generationLock = Mutex()
    /** Image URLs the server said do not exist (404, not a JPEG, ...). Not asked again until the app restarts. */
    private val missingImageUrls = mutableSetOf<String>()
    /** courseId -> the bundleVersion we already refetched for, so a server that never converges is not hammered. */
    private val refetchedVersion = mutableMapOf<String, String>()

    /** Bundle and progress files are written during a pass, so a pass never runs on Main. Tests swap it. */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val _status = MutableStateFlow(if (config.isConfigured) SyncStatus.IDLE else SyncStatus.NOT_CONFIGURED)
    val status: StateFlow<SyncStatus> = _status

    val isConfigured: Boolean get() = config.isConfigured

    /** Rows waiting to go to the server (events + lesson requests). */
    val pendingCount: Flow<Int> = queue.countByTypes(QUEUE_TYPES)

    /**
     * Quiz answers and completions waiting to sync: what the "N updates waiting" chip counts. Lesson
     * requests are not in it; they have their own "Creating your lesson" card and take minutes.
     */
    val pendingEventCount: Flow<Int> = queue.countByType(TYPE_LEARNER_EVENT)

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

    /** What the events step found. [stop] is set on a 401, which ends the whole sync. */
    private class EventsOutcome(val resolved: Int, val dropped: Int, val error: String?, val stop: SyncReport? = null)

    /**
     * One sync, in two steps with separate locks:
     *  1. **Events**: quick, and what the "Synced" chip is about.
     *  2. **Lesson requests, /me, bundles and images**: can wait on Muse for minutes.
     *
     * Events go first and under their own lock, so finishing a lesson and reconnecting shows "Synced"
     * within seconds even while a queued topic is still being generated by an earlier sync. Events only
     * refer to bundles that are already on the phone, so they never need to wait for the second step.
     * Neither step overlaps itself.
     */
    suspend fun sync(): SyncReport {
        if (!config.isConfigured) return SyncReport(SyncStatus.NOT_CONFIGURED).also { _status.value = it.status }
        if (!connectivity.isOnline()) return SyncReport(SyncStatus.OFFLINE).also { _status.value = it.status }

        val events = eventsLock.withLock {
            _status.value = SyncStatus.RUNNING
            withContext(ioDispatcher) { syncEvents() }
        }
        events.stop?.let { _status.value = it.status; return it }
        // Events are done: the chip can say Synced now, whatever generation below takes.
        _status.value = if (events.error != null && events.resolved == 0) SyncStatus.ERROR else SyncStatus.SYNCED

        val report = generationLock.withLock { withContext(ioDispatcher) { syncLessons(events) } }
        if (report.status == SyncStatus.UNAUTHORIZED || report.status == SyncStatus.ERROR) _status.value = report.status
        return report
    }

    private suspend fun syncEvents(): EventsOutcome {
        var resolved = 0
        var dropped = 0
        var error: String? = null
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
                is ApiResult.Unauthorized ->
                    return EventsOutcome(resolved, dropped, r.message, SyncReport(SyncStatus.UNAUTHORIZED, 0, resolved, dropped, r.message))
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
        return EventsOutcome(resolved, dropped, error)
    }

    private suspend fun syncLessons(events: EventsOutcome): SyncReport {
        var downloaded = 0
        var dropped = events.dropped
        var error: String? = events.error
        val resolved = events.resolved
        val requestErrors = mutableListOf<String>()

        // 2. Lesson requests. One failing request never stops the others: keep going.
        for (item in queue.getPendingByType(TYPE_LESSON_REQUEST, MAX_REQUESTS_PER_RUN)) {
            val req = parseRequest(item)
            if (req == null) { queue.delete(item); dropped++; continue }
            when (val r = api.requestLesson(req.topic, req.language, req.clientRequestId)) {
                is ApiResult.Ok -> { bundles.save(r.value.bundle); queue.delete(item); downloaded++ }
                is ApiResult.Unauthorized -> return SyncReport(SyncStatus.UNAUTHORIZED, downloaded, resolved, dropped, r.message, requestErrors)
                is ApiResult.Retryable -> { retryRequest(item); error = r.message }
                is ApiResult.Rejected -> {
                    if (r.code in DROP_REQUEST_CODES) {
                        // Only 400/422 mean the topic itself is unacceptable. Keep the reason (the queue
                        // row is about to go) so the UI can show it and offer a retry.
                        Log.w(TAG, "Lesson request dropped (${r.code}): ${r.message}")
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

        // 4. Illustrations for every stored bundle that is missing some. This runs on every pass, so it
        // covers new bundles (from a request or /me) and retries earlier failures. An image can never
        // fail or hold back a lesson: nothing above depends on it and nothing here throws.
        val images = fetchMissingImages()

        val status = if (error != null && downloaded == 0 && resolved == 0) SyncStatus.ERROR else SyncStatus.SYNCED
        return SyncReport(status, downloaded, resolved, dropped, error, requestErrors, images)
    }

    private suspend fun fetchMissingImages(): Int {
        // Everything this pass will try, published up front so the UI shows a placeholder for exactly
        // these images and for nothing that has already failed or will not be tried.
        val todo = mutableListOf<Pair<File, String>>()
        for (bundle in bundles.bundles.value) {
            for ((file, url) in bundles.imageTargets(bundle)) {
                if (!url.isNullOrBlank() && !file.exists() && url !in missingImageUrls) todo += file to url
            }
        }
        bundles.setImagesPending(todo.map { it.first.path }.toSet())
        var saved = 0
        var attempts = 0
        var failuresInARow = 0
        try {
            for ((file, url) in todo) {
                if (attempts >= MAX_IMAGES_PER_PASS || failuresInARow >= MAX_CONSECUTIVE_IMAGE_FAILURES) break
                attempts++
                when (val r = api.downloadImage(url)) {
                    is ApiResult.Ok -> {
                        val ok = runCatching { bundles.writeImage(file, r.value) }
                            .onFailure { Log.w(TAG, "Could not save ${file.name}", it) }.isSuccess
                        if (ok) { saved++; failuresInARow = 0 } else failuresInARow++
                    }
                    // The server will never serve this one; skip it and carry on with the rest.
                    is ApiResult.Rejected -> { Log.w(TAG, "Image ${file.name} unavailable (${r.code})"); missingImageUrls += url }
                    // Network trouble or a 5xx: leave it for the next pass.
                    else -> failuresInARow++
                }
                bundles.imageDownloadFinished(file.path) // done either way: a failure collapses its placeholder
            }
        } finally {
            bundles.setImagesPending(emptySet()) // whatever was not attempted is not "pending" any more
        }
        return saved
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

    /**
     * Download bundles we do not have, and refetch ones whose server `bundleVersion` moved on. The
     * version hash covers image ids, so it changes when images that missed the server's time budget
     * attach later; refetching is how they reach the phone. Once per course per version.
     */
    private suspend fun fetchMissingBundles(me: MeResponse): Int {
        var n = 0
        val unavailable = progress.data.unavailableCourseIds
        // No generator filter: WhatsApp-made courses can come back without one. /me already leaves
        // authored courses out, and the bundle endpoint's 422 covers any that slip through.
        val wanted = me.courses
            .filter { it.courseId !in unavailable && needsBundle(it) }
            .take(MAX_BUNDLE_FETCHES)
        for (course in wanted) {
            when (val r = api.fetchBundle(course.courseId)) {
                is ApiResult.Ok -> {
                    course.bundleVersion?.let { refetchedVersion[course.courseId] = it }
                    dropReplacedImages(bundles.get(course.courseId), r.value)
                    bundles.save(r.value); n++
                }
                // Not enrolled (404) or authored (422): remember, so we do not ask again every pass.
                is ApiResult.Rejected -> if (r.code == 404 || r.code == 422) progress.markCourseUnavailable(course.courseId)
                else -> Log.d(TAG, "Bundle ${course.courseId} will be retried: $r")
            }
        }
        return n
    }

    private fun needsBundle(course: MeCourse): Boolean {
        val stored = bundles.get(course.courseId) ?: return true
        val serverVersion = course.bundleVersion ?: return false
        return stored.version != serverVersion && refetchedVersion[course.courseId] != serverVersion
    }

    /** If the server now names a different image for a slot we already have a file for, the old file is stale. */
    private fun dropReplacedImages(old: LessonBundle?, new: LessonBundle) {
        if (old == null) return
        val before = bundles.imageTargets(old).associate { it.first.path to it.second }
        for ((file, url) in bundles.imageTargets(new)) {
            val was = before[file.path]
            if (url != null && was != null && was != url && file.exists()) bundles.deleteImage(file)
        }
    }

    private fun parseRequest(item: SyncQueueItem): LessonRequestPayload? =
        runCatching { gson.fromJson(item.payload, LessonRequestPayload::class.java) }.getOrNull()
            ?.takeIf { !it.clientRequestId.isNullOrBlank() && !it.topic.isNullOrBlank() }

    private fun parseEvent(item: SyncQueueItem): LearnerEvent? =
        runCatching { gson.fromJson(item.payload, LearnerEvent::class.java) }.getOrNull()
            ?.takeIf { !it.clientEventId.isNullOrBlank() && !it.type.isNullOrBlank() }
}
