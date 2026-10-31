package org.ekatra.alfred.lessons

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.ekatra.alfred.persona.ActivePersona
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class LessonsViewModelTest {

    private lateinit var dir: File
    private lateinit var dao: FakeQueueDao
    private lateinit var api: FakeApi
    private lateinit var net: FakeConnectivity
    private lateinit var bundles: LessonStore
    private lateinit var progress: LessonProgressStore
    private lateinit var engine: FakeEngine
    private lateinit var locator: FakeLocator
    private lateinit var trigger: FakeTrigger
    private val mutex = Mutex()
    private val io = CountingDispatcher()
    private lateinit var manager: LearnerSyncManager
    private lateinit var vm: LessonsViewModel

    private val course = "course-1"
    private val lesson = "lesson-1"

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        dir = Files.createTempDirectory("lessons-vm").toFile()
        dao = FakeQueueDao(); api = FakeApi(); net = FakeConnectivity(true)
        bundles = LessonStore(File(dir, "lessons")); progress = LessonProgressStore(File(dir, "p.json"))
        engine = FakeEngine().also { it.mutex = mutex }
        locator = FakeLocator(); trigger = FakeTrigger()
        bundles.save(sampleBundle(course, lesson))
    }

    @After
    fun tearDown() { Dispatchers.resetMain(); dir.deleteRecursively() }

    private fun newVm(config: SyncConfig = SyncConfig("http://x", "tok")): LessonsViewModel {
        manager = LearnerSyncManager(api, dao, bundles, progress, config, net).also {
            it.ioDispatcher = Dispatchers.Unconfined
        }
        return LessonsViewModel(manager, bundles, progress, net, engine, locator, trigger, mutex, ActivePersona)
            .also { it.ioDispatcher = io; vm = it }
    }

    private fun queuedEvents() = dao.items.filter { it.type == "learner_event" }
        .map { com.google.gson.Gson().fromJson(it.payload, LearnerEvent::class.java) }

    private fun assertFallback(text: String, reasonContains: String? = null) {
        val e = vm.uiState.value.explain as? ExplainState.Fallback
            ?: error("expected Fallback but was ${vm.uiState.value.explain}")
        assertEquals(text, e.text)
        if (reasonContains != null) assertTrue("reason '${e.reason}' should mention '$reasonContains'", e.reason.contains(reasonContains))
    }

    private fun openQuiz() {
        vm.openLesson(course, lesson)
        vm.startQuiz()
    }

    // ---- grading and events ----

    @Test
    fun `a correct answer is graded locally, recorded and queued without waking the model`() = runTest {
        newVm().also { net.online = false }
        openQuiz()

        vm.answer("B") // 1/2 of 10 is 5

        val ui = vm.uiState.value
        assertEquals(true, ui.quiz.correct)
        assertEquals(1, ui.quiz.correctCount)
        assertEquals(ExplainState.Idle, ui.explain)
        assertEquals(0, engine.prompts.size)
        assertEquals(true, progress.answerFor("q1")?.correct)
        val ev = queuedEvents().single()
        assertEquals("quiz_answered", ev.type)
        assertEquals("q1", ev.questionId); assertEquals("B", ev.answer); assertEquals(true, ev.clientCorrect)
        assertEquals(course, ev.courseId); assertEquals(lesson, ev.lessonId)
        assertTrue(trigger.count >= 1)
    }

    @Test
    fun `a second tap on the same question is ignored`() = runTest {
        newVm().also { net.online = false }
        openQuiz()
        vm.answer("A"); vm.answer("B")

        assertEquals(1, queuedEvents().size)
        assertEquals("A", vm.uiState.value.quiz.selected)
    }

    @Test
    fun `answers only count while the quiz is open`() = runTest {
        newVm().also { net.online = false }
        vm.openLesson(course, lesson) // READ stage
        vm.answer("B")
        assertTrue(queuedEvents().isEmpty())
    }

    @Test
    fun `finishing a lesson marks it complete, queues lesson_completed and reaches the done screen`() = runTest {
        newVm().also { net.online = false }
        openQuiz()
        vm.answer("B"); vm.next()
        assertEquals(1, vm.uiState.value.quiz.index)
        vm.answer("C"); vm.next() // q2 correct answer is A, so this one is wrong

        val ui = vm.uiState.value
        assertEquals(Stage.DONE, (ui.screen as LessonsScreen.Lesson).stage)
        assertEquals(1, ui.quiz.correctCount)
        assertTrue(lesson in ui.completed)
        assertEquals(listOf("quiz_answered", "quiz_answered", "lesson_completed"), queuedEvents().map { it.type })
    }

    // ---- Maya explains ----

    @Test
    fun `a wrong answer streams an explanation from the on-device model under the shared mutex`() = runTest {
        newVm()
        net.online = false
        openQuiz()

        vm.answer("A") // wrong

        val explain = vm.uiState.value.explain
        assertEquals(ExplainState.Done("Half of 10 is 5.", "Llama 3.2 1B Instruct"), explain)
        assertEquals(1, engine.loadCalls)
        assertEquals(true, engine.mutexHeldWhileGenerating)
        assertFalse("mutex must be released afterwards", mutex.isLocked)

        val prompt = engine.prompts.single()
        assertTrue(prompt.startsWith("<|start_header_id|>system<|end_header_id|>"))
        assertFalse(prompt.contains("<|begin_of_text|>"))
        assertTrue("lesson text in prompt", prompt.contains("A half is *one of two* equal parts."))
        assertTrue("question in prompt", prompt.contains("What is 1/2 of 10?"))
        assertTrue("chosen answer in prompt", prompt.contains("Student chose A: 2"))
        assertTrue("correct answer in prompt", prompt.contains("Correct answer B: 5"))
        assertTrue(prompt.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n"))
    }

    @Test
    fun `lesson text in the explain prompt is trimmed to about 1200 characters`() = runTest {
        val long = sampleBundle(course, lesson).let { b ->
            b.copy(lessons = b.lessons.map { it.copy(content = "x".repeat(5000)) })
        }
        bundles.save(long)
        newVm(); net.online = false
        openQuiz()
        vm.answer("A")

        val prompt = engine.prompts.single()
        assertTrue(prompt.contains("x".repeat(1200)))
        assertFalse(prompt.contains("x".repeat(1201)))
    }

    @Test
    fun `without a downloaded model the lesson's own explanation is shown`() = runTest {
        locator.model = null
        newVm(); net.online = false
        openQuiz()

        vm.answer("A")

        assertFallback("Half of ten is five.")
        assertEquals(0, engine.prompts.size)
    }

    @Test
    fun `an engine failure falls back to the lesson's explanation`() = runTest {
        engine.failWith = IllegalStateException("prompt too long")
        newVm(); net.online = false
        openQuiz()

        vm.answer("A")

        assertFallback("Half of ten is five.")
        assertFalse(mutex.isLocked)
    }

    @Test
    fun `a model that fails to load falls back`() = runTest {
        engine.loadOk = false
        newVm(); net.online = false
        openQuiz()
        vm.answer("A")
        assertTrue(vm.uiState.value.explain is ExplainState.Fallback)
    }

    @Test
    fun `a model that never produces a token is cut off by the first-token watchdog`() = runTest {
        engine.hang = true
        newVm(); net.online = false
        vm.firstTokenTimeoutMs = 1_000
        openQuiz()

        vm.answer("A")
        assertEquals(ExplainState.Loading("Llama 3.2 1B Instruct"), vm.uiState.value.explain)
        advanceTimeBy(1_001)

        assertFallback("Half of ten is five.")
        assertFalse("mutex must be released after a timeout", mutex.isLocked)
    }

    @Test
    fun `moving to the next question clears the explanation`() = runTest {
        newVm(); net.online = false
        openQuiz()
        vm.answer("A")
        vm.next()
        assertEquals(ExplainState.Idle, vm.uiState.value.explain)
        assertNull(vm.uiState.value.quiz.selected)
    }

    // ---- asking for lessons ----

    @Test
    fun `asking offline queues the request for later`() = runTest {
        newVm(); net.online = false

        assertTrue(vm.askForLesson("  photosynthesis "))

        assertEquals(listOf("photosynthesis"), vm.uiState.value.pendingTopics)
        assertEquals("Will be created when you're back online", vm.uiState.value.notice)
        assertTrue(api.lessonCalls.isEmpty())
        assertTrue(trigger.count >= 1)
        assertEquals(1, dao.items.count { it.type == "lesson_request" })
    }

    @Test
    fun `asking online generates now and the bundle lands in the list`() = runTest {
        api.lessonResult = { _, _, _ -> ApiResult.Ok(RequestLessonResult("course-2", sampleBundle("course-2", "lesson-9"))) }
        newVm()

        assertTrue(vm.askForLesson("decimals"))

        assertEquals("decimals", api.lessonCalls.single().first)
        assertEquals("en", api.lessonCalls.single().second)
        assertFalse(vm.uiState.value.requesting)
        assertNotNull(bundles.get("course-2"))
        assertTrue(vm.uiState.value.bundles.any { it.course.id == "course-2" })
        assertTrue(dao.items.none { it.type == "lesson_request" })
    }

    @Test
    fun `a request that dies mid-call stays queued under the same id`() = runTest {
        api.lessonResult = { _, _, _ -> ApiResult.Retryable("connection reset") }
        newVm()

        vm.askForLesson("decimals")

        val sent = api.lessonCalls.single().third
        val queued = dao.items.single { it.type == "lesson_request" }
        assertTrue(queued.payload.contains(sent))
        assertEquals(listOf("decimals"), vm.uiState.value.pendingTopics)
    }

    @Test
    fun `a server rejection is shown to the learner`() = runTest {
        api.lessonResult = { _, _, _ -> ApiResult.Rejected(400, "topic must be 2-120 characters") }
        newVm()

        vm.askForLesson("ab")

        assertEquals("Couldn't create lesson: topic must be 2-120 characters", vm.uiState.value.notice)
        assertTrue(dao.items.none { it.type == "lesson_request" })
        assertFalse(vm.uiState.value.requesting)
    }

    @Test
    fun `bad topics are refused locally`() = runTest {
        newVm()
        assertFalse(vm.askForLesson("a"))
        assertFalse(vm.askForLesson("   "))
        assertFalse(vm.askForLesson("x".repeat(121)))
        assertTrue(dao.items.isEmpty())
        assertTrue(api.lessonCalls.isEmpty())
    }

    @Test
    fun `an unconfigured build refuses and says so`() = runTest {
        newVm(SyncConfig("", ""))
        assertFalse(vm.uiState.value.configured)
        assertFalse(vm.askForLesson("fractions"))
        assertEquals("Lessons aren't set up on this build.", vm.uiState.value.notice)
    }

    // ---- sync on reconnect, chip, navigation ----

    @Test
    fun `coming back online syncs the queue within the same collector`() = runTest {
        newVm(); net.online = false
        openQuiz()
        vm.answer("B")
        assertEquals(1, queuedEvents().size)
        assertTrue(api.eventBatches.isEmpty())

        net.online = true

        assertEquals(1, api.eventBatches.size)
        assertTrue(dao.items.isEmpty())
    }

    @Test
    fun `the sync chip counts waiting updates and flips to synced`() = runTest {
        newVm(); net.online = false
        openQuiz()
        vm.answer("B")
        assertEquals("1 update waiting", syncChipText(vm.uiState.value))

        net.online = true

        assertEquals(0, vm.uiState.value.pendingCount)
        assertEquals("Synced ✓", syncChipText(vm.uiState.value))
    }

    @Test
    fun `the chip reports an unconfigured build and an expired sign-in`() {
        assertEquals("Not configured", syncChipText(LessonsUiState(configured = false)))
        assertEquals("Sign-in expired", syncChipText(LessonsUiState(configured = true, syncStatus = SyncStatus.UNAUTHORIZED)))
        assertEquals("3 updates waiting", syncChipText(LessonsUiState(configured = true, pendingCount = 3)))
    }

    @Test
    fun `the completion screen gets the server profile after sync`() = runTest {
        api.eventsResult = { evs ->
            ApiResult.Ok(EventsResponse(
                accepted = evs.map { it.clientEventId },
                profile = ServerProfile(mastery = mapOf(lesson to 0.75), struggles = emptyList())
            ))
        }
        newVm()
        openQuiz()
        vm.answer("B"); vm.next(); vm.answer("A"); vm.next()

        assertEquals(0.75, vm.uiState.value.profile!!.mastery[lesson]!!, 0.0)
        assertEquals(Stage.DONE, (vm.uiState.value.screen as LessonsScreen.Lesson).stage)
    }

    @Test
    fun `back steps out of a lesson, then leaves the activity`() = runTest {
        newVm(); net.online = false
        openQuiz()
        assertTrue(vm.back())
        assertEquals(LessonsScreen.Home, vm.uiState.value.screen)
        assertFalse(vm.back())
    }

    @Test
    fun `a review lesson with no text opens straight on its quiz`() = runTest {
        val b = sampleBundle(course, lesson)
        val review = b.lessons.single().copy(id = "review-1", kind = "review", content = "", order = 4)
        bundles.save(b.copy(lessons = b.lessons + review))
        newVm(); net.online = false

        vm.openLesson(course, "review-1")

        assertEquals(Stage.QUIZ, (vm.uiState.value.screen as LessonsScreen.Lesson).stage)
    }

    @Test
    fun `lesson markup renders bold and drops the asterisks`() {
        val t = lessonText("A half is *one of two* parts.\n\nNext **bold** too.")
        assertEquals("A half is one of two parts.\n\nNext bold too.", t.text)
        assertEquals(2, t.spanStyles.size)
    }

    // ---- fix 4: reconnect ----

    @Test
    fun `a real reconnect replaces the WorkManager run and syncs, the first online reading does not`() = runTest {
        newVm()
        assertEquals("opening the screen while online is not a reconnect", 0, trigger.replaceCount)

        net.online = false
        assertEquals(0, trigger.replaceCount)
        net.online = true

        assertEquals(1, trigger.replaceCount)
        assertEquals(2, api.meCalls) // once on open, once on reconnect
    }

    // ---- fix 7: locking, ordering, threading ----

    @Test
    fun `a question already answered on an earlier visit is shown locked and sends nothing new`() = runTest {
        newVm(); net.online = false
        openQuiz()
        vm.answer("A") // wrong
        val first = queuedEvents().single()
        vm.back()

        openQuiz() // second visit

        val quiz = vm.uiState.value.quiz
        assertEquals("A", quiz.selected)
        assertEquals(false, quiz.correct)
        assertFallback("Half of ten is five.")
        vm.answer("B")
        assertEquals("tapping again must not change or resend the answer", listOf(first), queuedEvents())
        assertEquals("A", progress.answerFor("q1")?.answer)
        assertEquals("no model call on a revisit", 1, engine.prompts.size)
    }

    @Test
    fun `revisiting counts earlier correct answers toward the score`() = runTest {
        newVm(); net.online = false
        openQuiz(); vm.answer("B"); vm.back() // q1 right, then leave

        openQuiz()
        assertEquals(1, vm.uiState.value.quiz.correctCount)
        vm.next()
        vm.answer("A") // q2 right
        vm.next()

        assertEquals(2, vm.uiState.value.quiz.correctCount)
        assertEquals(1, queuedEvents().count { it.questionId == "q1" })
    }

    @Test
    fun `the queue row is written before the progress file`() = runTest {
        newVm(); net.online = false
        var progressWhenQueued: StoredAnswer? = StoredAnswer("x", "x", "x", false, "x")
        dao.onInsert = { if (it.type == "learner_event") progressWhenQueued = progress.answerFor("q1") }
        openQuiz()

        vm.answer("B")

        assertNull("progress must not exist yet when the queue row is stored", progressWhenQueued)
        assertNotNull(progress.answerFor("q1"))
    }

    @Test
    fun `answer and completion disk writes run on the IO dispatcher`() = runTest {
        newVm(); net.online = false
        openQuiz()
        val before = io.dispatched

        vm.answer("B")
        assertTrue(io.dispatched > before)

        val afterAnswer = io.dispatched
        vm.next(); vm.answer("A"); vm.next() // finishes the lesson
        assertTrue(io.dispatched > afterAnswer)
    }

    @Test
    fun `re-answering after a crash between the queue write and the progress write is a duplicate id`() = runTest {
        newVm(); net.online = false
        // Simulate the crash: the queue row exists but progress was never recorded.
        manager.enqueueQuizAnswer(course, lesson, "q1", "B", true)
        openQuiz()

        vm.answer("B")

        val ids = queuedEvents().map { it.clientEventId }
        assertEquals(2, ids.size)
        assertEquals("the server will count the second as a duplicate", ids[0], ids[1])
    }

    // ---- model naming and explainer timeouts ----

    @Test
    fun `the explain card names the model that was actually used`() = runTest {
        locator.model = LocatedModel("lfm2.5-1.2b-instruct", File("/models/lfm.gguf"), "LFM2.5 1.2B Instruct")
        newVm(); net.online = false
        openQuiz()

        vm.answer("A")

        val done = vm.uiState.value.explain as ExplainState.Done
        assertEquals("LFM2.5 1.2B Instruct", done.modelName)
        assertEquals("Explained by LFM2.5 1.2B Instruct on this phone", explainCredit(done))
        // The prompt follows the model that is really loaded, not Llama.
        assertTrue(engine.prompts.single().startsWith("<|im_start|>system"))
    }

    @Test
    fun `a fallback is labelled as the lesson's text with the reason`() {
        val credit = explainCredit(ExplainState.Fallback("x", "Maya was busy with another answer"))!!
        assertTrue(credit.contains("not written by the model"))
        assertTrue(credit.contains("Maya was busy with another answer"))
        assertEquals("Llama 3.2 1B Instruct is thinking on this phone…", explainCredit(ExplainState.Loading("Llama 3.2 1B Instruct")))
    }

    @Test
    fun `the explainer waits 25 seconds for a first token while chat keeps 120`() = runTest {
        assertEquals(25_000L, LessonsViewModel.EXPLAIN_FIRST_TOKEN_MS)
        assertEquals(10_000L, LessonsViewModel.EXPLAIN_MUTEX_WAIT_MS)
        // Chat keeps its own, longer, persona timeout (120 s for Maya); the explainer never uses it.
        assertTrue(ActivePersona.firstTokenTimeoutMs > LessonsViewModel.EXPLAIN_FIRST_TOKEN_MS)
        engine.hang = true
        newVm(); net.online = false
        openQuiz()

        vm.answer("A")
        advanceTimeBy(24_000)
        assertTrue("still waiting at 24 s", vm.uiState.value.explain is ExplainState.Loading)
        advanceTimeBy(1_500)

        assertFallback("Half of ten is five.", "too long")
    }

    @Test
    fun `when chat holds the inference mutex the explainer waits about 10 seconds then falls back and says so`() = runTest {
        newVm(); net.online = false
        openQuiz()
        mutex.lock() // chat or host mode is generating

        vm.answer("A")
        advanceTimeBy(9_000)
        assertTrue("still waiting at 9 s", vm.uiState.value.explain is ExplainState.Loading)
        advanceTimeBy(1_500)

        assertFallback("Half of ten is five.", "busy")
        assertEquals("the model is never called while the mutex is held elsewhere", 0, engine.prompts.size)
        assertTrue("the other holder keeps its lock", mutex.isLocked)
        mutex.unlock()
    }

    @Test
    fun `a mutex that frees up in time lets the explainer run`() = runTest {
        newVm(); net.online = false
        openQuiz()
        mutex.lock()

        vm.answer("A")
        advanceTimeBy(5_000)
        mutex.unlock()
        advanceTimeBy(100)

        assertTrue(vm.uiState.value.explain is ExplainState.Done)
        assertFalse(mutex.isLocked)
    }

    // ---- completion screen honesty ----

    @Test
    fun `remote activity is claimed only when the profile has lessons this device never touched`() {
        val profile = ServerProfile(mastery = mapOf("local" to 0.5, "whatsapp-lesson" to 1.0))
        assertTrue(profileHasRemoteActivity(profile, answeredLessonIds = setOf("local"), completed = emptySet()))
        assertFalse(profileHasRemoteActivity(ServerProfile(mastery = mapOf("local" to 0.5)), setOf("local"), emptySet()))
        assertFalse("a lesson completed here counts as local", profileHasRemoteActivity(ServerProfile(mastery = mapOf("l" to 1.0)), emptySet(), setOf("l")))
        assertFalse(profileHasRemoteActivity(ServerProfile(), emptySet(), emptySet()))
    }

    @Test
    fun `the profile note mentions WhatsApp only with evidence and flags waiting updates`() {
        val onlyLocal = LessonsUiState(
            profile = ServerProfile(mastery = mapOf("l1" to 0.5)),
            answeredLessonIds = setOf("l1")
        )
        assertEquals("From the server, as of the last sync", profileSourceNote(onlyLocal))
        assertFalse(profileSourceNote(onlyLocal).contains("WhatsApp"))

        val withRemote = onlyLocal.copy(profile = ServerProfile(mastery = mapOf("l1" to 0.5, "w1" to 0.9)), pendingCount = 2)
        val note = profileSourceNote(withRemote)
        assertTrue(note.contains("such as on WhatsApp"))
        assertTrue(note.contains("doesn't include your 2 waiting updates yet"))
        assertEquals("", profileSourceNote(LessonsUiState(profile = null)))
    }

    @Test
    fun `answered lesson ids reach the ui state from the progress store`() = runTest {
        newVm(); net.online = false
        openQuiz()
        vm.answer("B")
        assertEquals(setOf(lesson), vm.uiState.value.answeredLessonIds)
    }
}
