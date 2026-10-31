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

class FakeConnectivity(initial: Boolean = true) : Connectivity {
    private val state = MutableStateFlow(initial)
    var online: Boolean
        get() = state.value
        set(value) { state.value = value }
    override fun isOnline() = state.value
    override fun onlineFlow(): Flow<Boolean> = state
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

class FakeEngine : org.ekatra.alfred.InferenceEngine {
    var loadOk = true
    var loadCalls = 0
    var tokens: List<String> = listOf("Half of 10 ", "is 5.")
    var failWith: Exception? = null
    var hang = false
    val prompts = mutableListOf<String>()
    var mutexHeldWhileGenerating: Boolean? = null
    lateinit var mutex: kotlinx.coroutines.sync.Mutex

    override fun isReady() = true
    override suspend fun loadModel(modelFile: java.io.File): Boolean { loadCalls++; return loadOk }
    override fun generateRaw(prompt: String): Flow<String> = kotlinx.coroutines.flow.flow {
        prompts += prompt
        mutexHeldWhileGenerating = mutex.isLocked
        failWith?.let { throw it }
        if (hang) kotlinx.coroutines.awaitCancellation()
        tokens.forEach { emit(it) }
    }
    override fun clearContext() {}
    override fun setSystemPrompt(prompt: String) {}
}

class FakeLocator(var model: LocatedModel? = LocatedModel("llama-3.2-1b-instruct", java.io.File("/models/llama.gguf"))) : ModelLocator {
    override fun locate() = model
}

class FakeTrigger : SyncTrigger {
    var count = 0
    override fun enqueue() { count++ }
}
