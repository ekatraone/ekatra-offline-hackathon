package org.ekatra.alfred.lessons

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.ekatra.alfred.InferenceEngine
import org.ekatra.alfred.persona.Persona
import org.ekatra.alfred.tools.PromptFormatter
import javax.inject.Inject
import javax.inject.Named

enum class Stage { READ, QUIZ, DONE }

sealed interface LessonsScreen {
    data object Home : LessonsScreen
    data class Lesson(val courseId: String, val lessonId: String, val stage: Stage) : LessonsScreen
}

/** State of the "Maya explains" panel under a wrong answer. */
sealed interface ExplainState {
    data object Idle : ExplainState
    /** [modelName] is the model that is actually answering, shown to the learner. */
    data class Loading(val modelName: String) : ExplainState
    data class Streaming(val text: String, val modelName: String) : ExplainState
    data class Done(val text: String, val modelName: String) : ExplainState
    /**
     * The on-device model did not answer; [text] is the explanation that shipped with the lesson and
     * [reason] says why, so the UI never pretends the model wrote it.
     */
    data class Fallback(val text: String, val reason: String) : ExplainState
}

data class QuizState(
    val index: Int = 0,
    val selected: String? = null,
    val correct: Boolean? = null,
    val correctCount: Int = 0
)

data class LessonsUiState(
    val configured: Boolean = false,
    /** A server is known but there is no token yet. */
    val needsToken: Boolean = false,
    val online: Boolean = true,
    val bundles: List<LessonBundle> = emptyList(),
    val completed: Set<String> = emptySet(),
    val pendingTopics: List<String> = emptyList(),
    /** Requests the server refused for good; shown with the reason and a retry button. */
    val failedRequests: List<FailedLessonRequest> = emptyList(),
    val pendingCount: Int = 0,
    val syncStatus: SyncStatus = SyncStatus.IDLE,
    /** True from tapping "Create lesson" until the sync pass that handles it ends. */
    val requesting: Boolean = false,
    val notice: String? = null,
    val profile: ServerProfile? = null,
    val lessonTitles: Map<String, String> = emptyMap(),
    /** Lessons this learner has answered a question in on this device. */
    val answeredLessonIds: Set<String> = emptySet(),
    /** Per image file, changes when that file is written; each image decodes again only on its own change. */
    val imageStamps: Map<String, Long> = emptyMap(),
    /** Image files with a download queued or running: only these draw a placeholder. */
    val pendingImages: Set<String> = emptySet(),
    val screen: LessonsScreen = LessonsScreen.Home,
    val quiz: QuizState = QuizState(),
    val explain: ExplainState = ExplainState.Idle
)

@HiltViewModel
class LessonsViewModel @Inject constructor(
    private val manager: LearnerSyncManager,
    private val bundleStore: LessonStore,
    private val progress: LessonProgressStore,
    private val connectivity: Connectivity,
    private val engine: InferenceEngine,
    private val locator: ModelLocator,
    private val trigger: SyncTrigger,
    @Named("inference") private val inferenceMutex: Mutex,
    private val persona: Persona
) : ViewModel() {

    companion object {
        private const val TAG = "LessonsViewModel"
        const val MIN_TOPIC = 2
        const val MAX_TOPIC = 120
        const val LANGUAGE = "en"
        /** Module text handed to the on-device model; the context is only 2048 tokens. */
        const val LESSON_CONTEXT_CHARS = 1200

        /** Chat keeps the persona's 120 s; a quiz explanation that takes longer than this is not worth waiting for. */
        const val EXPLAIN_FIRST_TOKEN_MS = 25_000L
        /** If chat or host mode is generating, wait this long for the shared inference Mutex, then fall back. */
        const val EXPLAIN_MUTEX_WAIT_MS = 10_000L
    }

    private val formatter = PromptFormatter()
    private val _ui = MutableStateFlow(LessonsUiState(configured = manager.isConfigured, needsToken = manager.needsToken))
    val uiState: StateFlow<LessonsUiState> = _ui.asStateFlow()

    /** Test seams. */
    internal var firstTokenTimeoutMs: Long = EXPLAIN_FIRST_TOKEN_MS
    internal var mutexWaitMs: Long = EXPLAIN_MUTEX_WAIT_MS

    /** Queue and progress files are written off the main thread. Tests swap it. */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private var explainJob: Job? = null

    init {
        viewModelScope.launch { bundleStore.bundles.collect { b -> _ui.update { it.copy(bundles = b) } } }
        viewModelScope.launch { bundleStore.imageStamps.collect { m -> _ui.update { it.copy(imageStamps = m) } } }
        viewModelScope.launch { bundleStore.imagesPending.collect { p -> _ui.update { it.copy(pendingImages = p) } } }
        viewModelScope.launch {
            progress.state.collect { p ->
                _ui.update {
                    it.copy(
                        completed = p.completedLessonIds,
                        failedRequests = p.failedRequests,
                        answeredLessonIds = p.answers.values.map { a -> a.lessonId }.toSet(),
                        profile = p.profile,
                        lessonTitles = p.me?.lessonTitles.orEmpty()
                    )
                }
            }
        }
        viewModelScope.launch {
            manager.pendingEventCount.collect { n ->
                _ui.update { it.copy(pendingCount = n) }
                refreshPendingTopics()
            }
        }
        viewModelScope.launch { manager.status.collect { s -> _ui.update { it.copy(syncStatus = s) } } }
        // The token can change under us: a join, a sign-out, or a 401 clearing an expired one.
        viewModelScope.launch { manager.configChanges.collect { refreshConfig() } }
        viewModelScope.launch {
            // Fires on every reconnect, so queued work goes out within seconds of coming back online.
            var wasOnline: Boolean? = null
            connectivity.onlineFlow().collect { online ->
                _ui.update { it.copy(online = online) }
                if (online && manager.isConfigured) {
                    // A real offline -> online change also replaces any WorkManager run that is sitting
                    // in retry backoff, in case this screen is closed before the sync below finishes.
                    if (wasOnline == false) trigger.enqueue(replace = true)
                    syncNow()
                }
                wasOnline = online
            }
        }
    }

    // ---- sync ----

    fun syncNow() {
        viewModelScope.launch { runSync() }
    }

    private suspend fun runSync() {
        val report = manager.sync()
        if (report.status == SyncStatus.UNAUTHORIZED) {
            _ui.update { it.copy(notice = "Lessons sign-in expired. Ask for a new token.") }
        }
        refreshConfig()
        _ui.update { it.copy(requesting = false) }
        refreshPendingTopics()
    }

    private suspend fun refreshPendingTopics() {
        val topics = manager.pendingRequestTopics()
        _ui.update { it.copy(pendingTopics = topics) }
    }

    private fun refreshConfig() {
        _ui.update { it.copy(configured = manager.isConfigured, needsToken = manager.needsToken) }
    }

    fun dismissNotice() = _ui.update { it.copy(notice = null) }

    /** Try a refused request again. It is queued under a NEW clientRequestId. */
    fun retryFailedRequest(clientRequestId: String) {
        viewModelScope.launch(ioDispatcher) {
            if (!manager.retryFailedRequest(clientRequestId)) return@launch
            trigger.enqueue()
            refreshPendingTopics()
            if (connectivity.isOnline()) {
                _ui.update { it.copy(requesting = true, notice = null) }
                runSync()
            }
        }
    }

    fun dismissFailedRequest(clientRequestId: String) {
        viewModelScope.launch(ioDispatcher) { manager.dismissFailedRequest(clientRequestId) }
    }

    // ---- ask for a lesson ----

    /** @return false when the topic is rejected locally. */
    fun askForLesson(rawTopic: String): Boolean {
        val topic = rawTopic.trim()
        if (!manager.isConfigured) {
            _ui.update { it.copy(notice = "Lessons aren't set up on this build.") }
            return false
        }
        if (topic.length < MIN_TOPIC || topic.length > MAX_TOPIC) {
            _ui.update { it.copy(notice = "Topic must be $MIN_TOPIC to $MAX_TOPIC characters.") }
            return false
        }
        viewModelScope.launch {
            // Queue first: if the app is killed during the 60-240 s generation, the same
            // clientRequestId is retried on the next sync and the server returns the same course.
            manager.enqueueLessonRequest(topic, LANGUAGE)
            trigger.enqueue()
            if (connectivity.isOnline()) {
                _ui.update { it.copy(requesting = true, notice = null) }
                runSync()
            } else {
                // The pending card says "Will be created when you're back online".
                refreshPendingTopics()
            }
        }
        return true
    }

    // ---- reading and quiz ----

    fun coverFile(courseId: String) = bundleStore.coverFile(courseId)

    fun lessonImageFile(courseId: String, lessonId: String) = bundleStore.lessonImageFile(courseId, lessonId)

    fun findCourse(courseId: String): BundleCourse? = bundleStore.get(courseId)?.course

    fun findLesson(courseId: String, lessonId: String): BundleLesson? =
        bundleStore.get(courseId)?.lessons?.firstOrNull { it.id == lessonId }

    fun openLesson(courseId: String, lessonId: String) {
        val lesson = findLesson(courseId, lessonId) ?: return
        explainJob?.cancel()
        // Review lessons have no text to read, so they open straight on the quiz.
        val stage = if (lesson.isReview || lesson.content.isNullOrBlank()) Stage.QUIZ else Stage.READ
        _ui.update { it.copy(screen = LessonsScreen.Lesson(courseId, lessonId, stage), quiz = QuizState(), explain = ExplainState.Idle) }
        if (stage == Stage.QUIZ) startQuiz()
    }

    fun startQuiz() {
        val s = _ui.value.screen as? LessonsScreen.Lesson ?: return
        val lesson = findLesson(s.courseId, s.lessonId) ?: return
        if (lesson.questions.isEmpty()) { finishLesson(); return }
        val (quiz, explain) = enterQuestion(lesson, 0, 0)
        _ui.update { it.copy(screen = s.copy(stage = Stage.QUIZ), quiz = quiz, explain = explain) }
    }

    /**
     * A question is answered once. If this learner already answered it (an earlier visit), show that
     * answer locked instead of letting them answer again, so retaking a lesson never sends a second
     * answer for the same question.
     */
    private fun enterQuestion(lesson: BundleLesson, index: Int, correctSoFar: Int): Pair<QuizState, ExplainState> {
        val q = lesson.questions[index]
        val prev = progress.answerFor(q.id) ?: return QuizState(index, null, null, correctSoFar) to ExplainState.Idle
        val quiz = QuizState(index, prev.answer, prev.correct, correctSoFar + if (prev.correct) 1 else 0)
        return quiz to if (prev.correct) ExplainState.Idle else fallbackFor(q, "you answered this earlier")
    }

    private fun fallbackFor(q: BundleQuestion, reason: String) = ExplainState.Fallback(
        q.explanation?.takeIf { it.isNotBlank() } ?: "The correct answer is ${q.correctAnswer}.",
        reason
    )

    /** Grade locally, queue the event, record it, and ask Maya to explain a miss. */
    fun answer(letter: String) {
        val s = _ui.value.screen as? LessonsScreen.Lesson ?: return
        val quiz = _ui.value.quiz
        if (s.stage != Stage.QUIZ || quiz.selected != null) return
        val lesson = findLesson(s.courseId, s.lessonId) ?: return
        val question = lesson.questions.getOrNull(quiz.index) ?: return
        if (progress.answerFor(question.id) != null) return // locked after the first answer

        val correct = LessonGrading.isCorrect(question, letter)
        _ui.update {
            it.copy(quiz = quiz.copy(selected = letter, correct = correct, correctCount = quiz.correctCount + if (correct) 1 else 0))
        }
        viewModelScope.launch(ioDispatcher) {
            // Queue row first, progress file second: if the process dies in between, the answer is
            // still on its way to the server (and the learner just sees the question unanswered).
            manager.enqueueQuizAnswer(s.courseId, s.lessonId, question.id, letter, correct)
            progress.recordAnswer(s.courseId, s.lessonId, question.id, letter, correct, LearnerSyncManager.nowIso())
            trigger.enqueue()
        }
        if (!correct) explainWrongAnswer(lesson, question, letter)
    }

    fun next() {
        val s = _ui.value.screen as? LessonsScreen.Lesson ?: return
        val quiz = _ui.value.quiz
        val lesson = findLesson(s.courseId, s.lessonId) ?: return
        if (quiz.selected == null) return
        explainJob?.cancel()
        if (quiz.index + 1 < lesson.questions.size) {
            val (nextQuiz, explain) = enterQuestion(lesson, quiz.index + 1, quiz.correctCount)
            _ui.update { it.copy(quiz = nextQuiz, explain = explain) }
        } else {
            finishLesson()
        }
    }

    private fun finishLesson() {
        val s = _ui.value.screen as? LessonsScreen.Lesson ?: return
        explainJob?.cancel()
        _ui.update { it.copy(screen = s.copy(stage = Stage.DONE), explain = ExplainState.Idle) }
        viewModelScope.launch(ioDispatcher) {
            manager.enqueueLessonCompleted(s.courseId, s.lessonId)
            progress.markCompleted(s.lessonId)
            trigger.enqueue()
            if (connectivity.isOnline() && manager.isConfigured) runSync()
        }
    }

    /** @return true if the back press was consumed inside the screen stack. */
    fun back(): Boolean {
        if (_ui.value.screen is LessonsScreen.Home) return false
        explainJob?.cancel()
        _ui.update { it.copy(screen = LessonsScreen.Home, quiz = QuizState(), explain = ExplainState.Idle) }
        return true
    }

    // ---- Maya explains (on-device Llama) ----

    private fun explainWrongAnswer(lesson: BundleLesson, question: BundleQuestion, chosen: String) {
        explainJob?.cancel()
        val model = locator.locate()
        if (model == null) {
            _ui.update { it.copy(explain = fallbackFor(question, "Maya's on-device model isn't downloaded")) }
            return
        }
        val prompt = buildExplainPrompt(model.id, lesson, question, chosen)
        val out = StringBuilder()
        var timedOut = false

        explainJob = viewModelScope.launch {
            _ui.update { it.copy(explain = ExplainState.Loading(model.name)) }

            // Chat and host mode share this Mutex. Wait a little for it, not for a whole reply.
            val gotLock = withTimeoutOrNull(mutexWaitMs) { inferenceMutex.lock(); true } ?: false
            if (!gotLock) {
                Log.w(TAG, "Inference busy; showing the lesson's explanation")
                _ui.update { it.copy(explain = fallbackFor(question, "Maya was busy with another answer")) }
                return@launch
            }
            try {
                check(engine.loadModel(model.file)) { "Model failed to load" } // no-op if already loaded
                val genJob = currentCoroutineContext()[Job]
                val watchdog = launch {
                    delay(firstTokenTimeoutMs)
                    if (out.isEmpty()) {
                        Log.w(TAG, "First-token timeout, cancelling explanation")
                        timedOut = true
                        genJob?.cancel(CancellationException("First token timeout"))
                    }
                }
                try {
                    engine.generateRaw(prompt).collect { token ->
                        if (out.isEmpty()) watchdog.cancel()
                        out.append(token)
                        _ui.update { it.copy(explain = ExplainState.Streaming(out.toString(), model.name)) }
                    }
                } finally {
                    watchdog.cancel()
                }
            } catch (e: CancellationException) {
                if (timedOut && out.isEmpty()) {
                    _ui.update { it.copy(explain = fallbackFor(question, "Maya took too long to answer")) }
                    return@launch
                }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Explanation failed", e)
                out.clear()
            } finally {
                inferenceMutex.unlock()
            }
            val text = out.toString().trim()
            _ui.update {
                it.copy(explain = if (text.isNotBlank()) ExplainState.Done(text, model.name)
                else fallbackFor(question, "Maya couldn't answer this time"))
            }
        }
    }

    internal fun buildExplainPrompt(modelId: String, lesson: BundleLesson, question: BundleQuestion, chosen: String): String {
        val lessonText = (lesson.content ?: "").trim().let {
            if (it.length > LESSON_CONTEXT_CHARS) it.take(LESSON_CONTEXT_CHARS).trimEnd() + "…" else it
        }
        val chosenText = LessonGrading.optionText(question, chosen) ?: chosen
        val correctText = LessonGrading.optionText(question, question.correctAnswer) ?: question.correctAnswer
        val system = "You are ${persona.assistantName}, a friendly tutor. A student got a quiz question wrong. " +
            "In 2 to 4 short, simple sentences, explain why ${question.correctAnswer} is right and why their answer is not. " +
            "Use only the lesson text. Be encouraging. No LaTeX."
        val user = buildString {
            if (lessonText.isNotEmpty()) append("Lesson:\n").append(lessonText).append("\n\n")
            append("Question: ").append(question.question).append("\n")
            append("Student chose ").append(chosen).append(": ").append(chosenText).append("\n")
            append("Correct answer ").append(question.correctAnswer).append(": ").append(correctText)
        }
        return formatter.format(modelId, system, emptyList(), user)
    }
}
