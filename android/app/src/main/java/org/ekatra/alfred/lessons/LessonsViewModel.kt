package org.ekatra.alfred.lessons

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    data object Loading : ExplainState
    data class Streaming(val text: String) : ExplainState
    data class Done(val text: String) : ExplainState
    /** The on-device model could not answer; [text] is the explanation that shipped with the lesson. */
    data class Fallback(val text: String) : ExplainState
}

data class QuizState(
    val index: Int = 0,
    val selected: String? = null,
    val correct: Boolean? = null,
    val correctCount: Int = 0
)

data class LessonsUiState(
    val configured: Boolean = false,
    val online: Boolean = true,
    val bundles: List<LessonBundle> = emptyList(),
    val completed: Set<String> = emptySet(),
    val pendingTopics: List<String> = emptyList(),
    val pendingCount: Int = 0,
    val syncStatus: SyncStatus = SyncStatus.IDLE,
    /** True from tapping "Create lesson" until the sync pass that handles it ends. */
    val requesting: Boolean = false,
    val notice: String? = null,
    val profile: ServerProfile? = null,
    val lessonTitles: Map<String, String> = emptyMap(),
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
    }

    private val formatter = PromptFormatter()
    private val _ui = MutableStateFlow(LessonsUiState(configured = manager.isConfigured))
    val uiState: StateFlow<LessonsUiState> = _ui.asStateFlow()

    /** Test seam; production uses the persona's value. */
    internal var firstTokenTimeoutMs: Long = persona.firstTokenTimeoutMs

    private var explainJob: Job? = null

    init {
        viewModelScope.launch { bundleStore.bundles.collect { b -> _ui.update { it.copy(bundles = b) } } }
        viewModelScope.launch {
            progress.state.collect { p ->
                _ui.update {
                    it.copy(
                        completed = p.completedLessonIds,
                        profile = p.profile,
                        lessonTitles = p.me?.lessonTitles.orEmpty()
                    )
                }
            }
        }
        viewModelScope.launch {
            manager.pendingCount.collect { n ->
                _ui.update { it.copy(pendingCount = n) }
                refreshPendingTopics()
            }
        }
        viewModelScope.launch { manager.status.collect { s -> _ui.update { it.copy(syncStatus = s) } } }
        viewModelScope.launch {
            // Fires on every reconnect, so queued work goes out within seconds of coming back online.
            connectivity.onlineFlow().collect { online ->
                _ui.update { it.copy(online = online) }
                if (online && manager.isConfigured) syncNow()
            }
        }
    }

    // ---- sync ----

    fun syncNow() {
        viewModelScope.launch { runSync() }
    }

    private suspend fun runSync() {
        val report = manager.sync()
        if (report.requestErrors.isNotEmpty()) {
            _ui.update { it.copy(notice = "Couldn't create lesson: ${report.requestErrors.first()}") }
        } else if (report.status == SyncStatus.UNAUTHORIZED) {
            _ui.update { it.copy(notice = "Lessons sign-in expired. Ask for a new token.") }
        }
        _ui.update { it.copy(requesting = false) }
        refreshPendingTopics()
    }

    private suspend fun refreshPendingTopics() {
        val topics = manager.pendingRequestTopics()
        _ui.update { it.copy(pendingTopics = topics) }
    }

    fun dismissNotice() = _ui.update { it.copy(notice = null) }

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
                _ui.update { it.copy(notice = "Will be created when you're back online") }
                refreshPendingTopics()
            }
        }
        return true
    }

    // ---- reading and quiz ----

    fun findLesson(courseId: String, lessonId: String): BundleLesson? =
        bundleStore.get(courseId)?.lessons?.firstOrNull { it.id == lessonId }

    fun openLesson(courseId: String, lessonId: String) {
        val lesson = findLesson(courseId, lessonId) ?: return
        explainJob?.cancel()
        // Review lessons have no text to read, so they open straight on the quiz.
        val stage = if (lesson.isReview || lesson.content.isNullOrBlank()) Stage.QUIZ else Stage.READ
        _ui.update { it.copy(screen = LessonsScreen.Lesson(courseId, lessonId, stage), quiz = QuizState(), explain = ExplainState.Idle) }
        if (stage == Stage.QUIZ && lesson.questions.isEmpty()) finishLesson()
    }

    fun startQuiz() {
        val s = _ui.value.screen as? LessonsScreen.Lesson ?: return
        val lesson = findLesson(s.courseId, s.lessonId) ?: return
        if (lesson.questions.isEmpty()) { finishLesson(); return }
        _ui.update { it.copy(screen = s.copy(stage = Stage.QUIZ), quiz = QuizState()) }
    }

    /** Grade locally, record it, queue the event, and ask Maya to explain a miss. */
    fun answer(letter: String) {
        val s = _ui.value.screen as? LessonsScreen.Lesson ?: return
        val quiz = _ui.value.quiz
        if (s.stage != Stage.QUIZ || quiz.selected != null) return
        val lesson = findLesson(s.courseId, s.lessonId) ?: return
        val question = lesson.questions.getOrNull(quiz.index) ?: return

        val correct = LessonGrading.isCorrect(question, letter)
        progress.recordAnswer(s.courseId, s.lessonId, question.id, letter, correct, LearnerSyncManager.nowIso())
        _ui.update {
            it.copy(quiz = quiz.copy(selected = letter, correct = correct, correctCount = quiz.correctCount + if (correct) 1 else 0))
        }
        viewModelScope.launch {
            manager.enqueueQuizAnswer(s.courseId, s.lessonId, question.id, letter, correct)
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
            _ui.update { it.copy(quiz = quiz.copy(index = quiz.index + 1, selected = null, correct = null), explain = ExplainState.Idle) }
        } else {
            finishLesson()
        }
    }

    private fun finishLesson() {
        val s = _ui.value.screen as? LessonsScreen.Lesson ?: return
        explainJob?.cancel()
        progress.markCompleted(s.lessonId)
        _ui.update { it.copy(screen = s.copy(stage = Stage.DONE), explain = ExplainState.Idle) }
        viewModelScope.launch {
            manager.enqueueLessonCompleted(s.courseId, s.lessonId)
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
        val fallback = ExplainState.Fallback(question.explanation?.takeIf { it.isNotBlank() }
            ?: "The correct answer is ${question.correctAnswer}.")
        val model = locator.locate()
        if (model == null) {
            _ui.update { it.copy(explain = fallback) }
            return
        }
        val prompt = buildExplainPrompt(model.id, lesson, question, chosen)
        val out = StringBuilder()
        var timedOut = false

        explainJob = viewModelScope.launch {
            _ui.update { it.copy(explain = ExplainState.Loading) }
            try {
                // One inference at a time: chat and the host server share this mutex.
                inferenceMutex.withLock {
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
                            _ui.update { it.copy(explain = ExplainState.Streaming(out.toString())) }
                        }
                    } finally {
                        watchdog.cancel()
                    }
                }
            } catch (e: CancellationException) {
                if (timedOut && out.isEmpty()) {
                    _ui.update { it.copy(explain = fallback) }
                    return@launch
                }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Explanation failed", e)
                out.clear()
            }
            val text = out.toString().trim()
            _ui.update { it.copy(explain = if (text.isNotBlank()) ExplainState.Done(text) else fallback) }
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
