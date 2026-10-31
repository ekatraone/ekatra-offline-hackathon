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
    /** Called just before a row is stored, to check ordering against other writes. */
    var onInsert: (SyncQueueItem) -> Unit = {}

    override suspend fun insert(item: SyncQueueItem): Long {
        onInsert(item)
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
    override suspend fun deleteByType(type: String) { rows.value = rows.value.filterNot { it.type == type } }
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

    var deleteResult: (String) -> DeleteOutcome = { DeleteOutcome.Deleted }
    val deleteCalls = mutableListOf<String>()

    var exchangeResult: (String) -> ExchangeOutcome = { ExchangeOutcome.Success("exchanged-jwt", "learner-1", "Asha", "+919876543210", null) }
    val exchangeCalls = mutableListOf<String>()

    var imageResult: (String) -> ApiResult<ByteArray> = { ApiResult.Ok(JPEG_BYTES) }
    val imageCalls = mutableListOf<String>()
    /** When set, requestLesson waits on it: a lesson that is still being generated. */
    var lessonGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    val lessonCalls = mutableListOf<Triple<String, String, String>>()
    val eventBatches = mutableListOf<List<LearnerEvent>>() // one entry per POST
    var meCalls = 0

    override suspend fun requestLesson(topic: String, language: String, clientRequestId: String): ApiResult<RequestLessonResult> {
        lessonCalls += Triple(topic, language, clientRequestId)
        lessonGate?.await()
        return lessonResult(topic, language, clientRequestId)
    }
    override suspend fun fetchBundle(courseId: String) = bundleResult(courseId)
    override suspend fun postEvents(events: List<LearnerEvent>): ApiResult<EventsResponse> {
        eventBatches += events
        return eventsResult(events)
    }
    override suspend fun getMe(): ApiResult<MeResponse> { meCalls++; return meResult() }
    override suspend fun deleteMe(token: String): DeleteOutcome {
        deleteCalls += token
        return deleteResult(token)
    }
    override suspend fun exchange(idToken: String): ExchangeOutcome {
        exchangeCalls += idToken
        return exchangeResult(idToken)
    }
    override suspend fun downloadImage(url: String): ApiResult<ByteArray> { imageCalls += url; return imageResult(url) }
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

class FakeLocator(var model: LocatedModel? = LocatedModel("llama-3.2-1b-instruct", java.io.File("/models/llama.gguf"), "Llama 3.2 1B Instruct")) : ModelLocator {
    override fun locate() = model
}

class FakeTrigger : SyncTrigger {
    var count = 0
    var replaceCount = 0
    override fun enqueue(replace: Boolean) { count++; if (replace) replaceCount++ }
}

/** Counts every block it is asked to run, so a test can prove work went through the IO dispatcher. */
class CountingDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
    var dispatched = 0
    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { dispatched++; block.run() }
}

/** Starts with the JPEG magic bytes, which is all the client checks. */
val JPEG_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)

const val COVER_URL = "https://demo.example/api/sync/media/cover-1"
const val LESSON_IMAGE_URL = "https://demo.example/api/sync/media/lesson-img-1"

/** A bundle with a cover, one module image, and a review lesson (which never has an image). */
fun sampleBundleWithImages(courseId: String = "course-1", lessonId: String = "lesson-1"): LessonBundle {
    val b = sampleBundle(courseId, lessonId)
    val review = b.lessons.single().copy(id = "review-$lessonId", kind = "review", content = "", order = 4, imageUrl = "https://demo.example/never")
    return b.copy(
        course = b.course.copy(coverImageUrl = COVER_URL),
        lessons = b.lessons.map { it.copy(imageUrl = LESSON_IMAGE_URL) } + review
    )
}

class FakeTokenStore(var token: String? = null, var learner: String? = null, var pendingDelete: String? = null) : TokenStore {
    override fun pendingDeleteToken() = pendingDelete
    override fun setPendingDeleteToken(token: String) { pendingDelete = token }
    override fun clearPendingDelete() { pendingDelete = null }
    override fun token() = token
    override fun learnerId() = learner
    override fun save(token: String, learnerId: String?) { this.token = token; if (learnerId != null) learner = learnerId }
    override fun clearToken() { token = null }
    override fun clearAll() { token = null; learner = null }
}

/** An in-memory SharedPreferences, enough for PrefsTokenStore. */
class FakePrefs : android.content.SharedPreferences {
    val data = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = data.toMutableMap()
    override fun getString(key: String, defValue: String?) = data[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String, defValue: Int) = data[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = data[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = data[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = data[key] as? Boolean ?: defValue
    override fun contains(key: String) = data.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clearAll = false
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { clearAll = true }
        override fun commit(): Boolean {
            if (clearAll) data.clear()
            removed.forEach { data.remove(it) }
            data.putAll(pending)
            return true
        }
        override fun apply() { commit() }
    }
}

class FakeFirebase(
    var user: Boolean = true,
    var idTokens: MutableList<String?> = mutableListOf("id-1"),
    var customTokenSignIn: Boolean = true
) : FirebaseSession {
    val idTokenRequests = mutableListOf<Boolean>()   // forceRefresh flags, in order
    val customTokens = mutableListOf<String>()
    var signedOut = false
    override fun hasUser() = user
    override suspend fun idToken(forceRefresh: Boolean): String? {
        idTokenRequests += forceRefresh
        return if (!user) null else if (idTokens.size > 1) idTokens.removeAt(0) else idTokens.firstOrNull()
    }
    override suspend fun signInWithCustomToken(customToken: String): Boolean {
        customTokens += customToken
        if (customTokenSignIn) user = true
        return customTokenSignIn
    }
    override fun signOut() { user = false; signedOut = true }
}

class FakeRefresher(var ensure: () -> Boolean = { false }, var refresh: () -> Boolean = { false }) : TokenRefresher {
    var ensureCalls = 0
    var refreshCalls = 0
    override suspend fun ensureToken(): Boolean { ensureCalls++; return ensure() }
    override suspend fun refreshAfter401(): Boolean { refreshCalls++; return refresh() }
}

class FakePhoneAuth : PhoneAuthApi {
    var startResult: (String) -> StartOutcome = { StartOutcome.Sent(30, 600) }
    var verifyResult: (String, String) -> VerifyOutcome = { _, _ -> VerifyOutcome.Verified("custom-token") }
    val starts = mutableListOf<String>()
    val verifies = mutableListOf<Pair<String, String>>()
    override suspend fun whatsappStart(phone: String): StartOutcome { starts += phone; return startResult(phone) }
    override suspend fun whatsappVerify(phone: String, code: String): VerifyOutcome { verifies += phone to code; return verifyResult(phone, code) }
}

/** A JWT-shaped string with the given `exp` (seconds since the epoch). The signature is junk; the app never checks it. */
fun fakeJwt(expSeconds: Long?): String {
    fun b64(s: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
    val payload = if (expSeconds == null) """{"sub":"x"}""" else """{"sub":"x","exp":$expSeconds}"""
    return b64("""{"alg":"HS256","typ":"JWT"}""") + "." + b64(payload) + ".sig"
}

class FakeConsentStore(var acceptedAt: Int = 0, var declinedAt: Int = 0) : LessonsConsentStore {
    override fun acceptedVersion() = acceptedAt
    override fun declinedVersion() = declinedAt
    override fun setAccepted(version: Int) { acceptedAt = version; declinedAt = 0 }
    override fun setDeclined(version: Int) { declinedAt = version }
    override fun clear() { acceptedAt = 0; declinedAt = 0 }
}

/** Lessons consent that has been accepted at the current wording: what almost every test wants. */
fun acceptedConsent() = LessonsConsent(FakeConsentStore(acceptedAt = LessonsConsent.CURRENT_VERSION))

/** Builds the manager the way Hilt does, with a refresher that has nothing to exchange unless a test says so. */
fun testManager(
    api: LearnerSyncApi, queue: org.ekatra.alfred.data.local.SyncQueueDao, bundles: LessonStore, progress: LessonProgressStore,
    config: SyncConfig, connectivity: Connectivity, refresher: TokenRefresher = FakeRefresher(),
    consent: LessonsConsent = acceptedConsent()
) = LearnerSyncManager(api, queue, bundles, progress, config, connectivity, refresher, LocalLearnerData(queue, bundles, progress), consent)
