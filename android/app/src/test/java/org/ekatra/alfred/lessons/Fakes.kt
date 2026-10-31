package org.ekatra.alfred.lessons

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.ekatra.alfred.data.local.SyncQueueDao
import org.ekatra.alfred.data.model.SyncQueueItem

/** In-memory SyncQueueDao that filters by type like the real @Query methods. */
class FakeQueueDao : SyncQueueDao {
    private val rows = MutableStateFlow<List<SyncQueueItem>>(emptyList())
    private var nextId = 1L
    val items: List<SyncQueueItem> get() = rows.value

    override suspend fun insert(item: SyncQueueItem): Long {
        val id = nextId++
        rows.value = rows.value + item.copy(id = id)
        return id
    }
    override suspend fun getPending(limit: Int) = rows.value.take(limit)
    override suspend fun getPendingByType(type: String, limit: Int) = rows.value.filter { it.type == type }.take(limit)
    override suspend fun getPendingByTypes(types: List<String>, limit: Int) = rows.value.filter { it.type in types }.take(limit)
    override fun getPendingCount(): Flow<Int> = rows.map { it.size }
    override fun countByType(type: String): Flow<Int> = rows.map { r -> r.count { it.type == type } }
    override fun countByTypes(types: List<String>): Flow<Int> = rows.map { r -> r.count { it.type in types } }
    override suspend fun delete(item: SyncQueueItem) { rows.value = rows.value.filterNot { it.id == item.id } }
    override suspend fun deleteById(id: Long) { rows.value = rows.value.filterNot { it.id == id } }
    override suspend fun deleteAll() { rows.value = emptyList() }
    override suspend fun incrementRetry(id: Long) {
        rows.value = rows.value.map { if (it.id == id) it.copy(retryCount = it.retryCount + 1) else it }
    }
}

class FakeConnectivity(var online: Boolean = true) : Connectivity {
    override fun isOnline() = online
    override fun onlineFlow() = MutableStateFlow(online)
}

class FakeApi : LearnerSyncApi {
    var lessonResult: (String, String, String) -> ApiResult<RequestLessonResult> = { _, _, _ -> error("unset") }
    var bundleResult: (String) -> ApiResult<LessonBundle> = { ApiResult.Rejected(422, "authored") }
    var eventsResult: (List<LearnerEvent>) -> ApiResult<EventsResponse> = { evs ->
        ApiResult.Ok(EventsResponse(accepted = evs.map { it.clientEventId }))
    }
    var meResult: () -> ApiResult<MeResponse> = { ApiResult.Ok(MeResponse()) }

    val lessonCalls = mutableListOf<Triple<String, String, String>>()
    val eventBatches = mutableListOf<List<LearnerEvent>>() // one entry per POST
    var meCalls = 0

    override suspend fun requestLesson(topic: String, language: String, clientRequestId: String): ApiResult<RequestLessonResult> {
        lessonCalls += Triple(topic, language, clientRequestId)
        return lessonResult(topic, language, clientRequestId)
    }
    override suspend fun fetchBundle(courseId: String) = bundleResult(courseId)
    override suspend fun postEvents(events: List<LearnerEvent>): ApiResult<EventsResponse> {
        eventBatches += events
        return eventsResult(events)
    }
    override suspend fun getMe(): ApiResult<MeResponse> { meCalls++; return meResult() }
}

fun sampleBundle(courseId: String = "course-1", lessonId: String = "lesson-1") = LessonBundle(
    version = "v1",
    course = BundleCourse(courseId, "Fractions", "About fractions", "en", Generator("meta", "muse-spark-1.3")),
    lessons = listOf(
        BundleLesson(
            id = lessonId, kind = "module", title = "Halves", content = "A half is *one of two* equal parts.",
            day = 1, order = 1,
            questions = listOf(
                BundleQuestion("q1", "What is 1/2 of 10?", listOf("2", "5", "10", "1"), "B", "Half of ten is five."),
                BundleQuestion("q2", "1/2 is the same as?", listOf("2/4", "1/3", "3/4", "2/3"), "A", "2/4 simplifies to 1/2.")
            )
        )
    )
)
