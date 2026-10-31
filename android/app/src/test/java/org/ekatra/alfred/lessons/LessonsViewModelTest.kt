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
    private val firebase = FakeFirebase()
    private var consent = acceptedConsent()
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
        // (A config built with a FakeTokenStore lets a test join, expire and sign out.)
        manager = testManager(api, dao, bundles, progress, config, net).also {
            it.ioDispatcher = Dispatchers.Unconfined
        }
        return LessonsViewModel(manager, bundles, progress, net, engine, locator, trigger, mutex, ActivePersona, firebase, consent)
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
        assertEquals(PendingMessage("Will be created when you're back online", showProgress = false), pendingRequestMessage(vm.uiState.value))
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
    fun `a refused request is kept with its reason instead of vanishing`() = runTest {
        api.lessonResult = { _, _, _ -> ApiResult.Rejected(422, "generation_failed") }
        newVm()

        vm.askForLesson("decimals")

        val failed = vm.uiState.value.failedRequests.single()
        assertEquals("decimals", failed.topic)
        assertEquals("Couldn't create lesson: generation_failed", failedRequestText(failed))
        assertTrue(dao.items.none { it.type == "lesson_request" })
        assertTrue(vm.uiState.value.pendingTopics.isEmpty())
        assertNull("a refused request is not 'pending' any more", pendingRequestMessage(vm.uiState.value))
        assertFalse(vm.uiState.value.requesting)
        // ...and it survives a restart.
        assertEquals("decimals", LessonProgressStore(File(dir, "p.json")).data.failedRequests.single().topic)
    }

    @Test
    fun `a 400 is also kept with its reason`() = runTest {
        api.lessonResult = { _, _, _ -> ApiResult.Rejected(400, "topic must be 2-120 characters") }
        newVm()
        vm.askForLesson("ab")
        assertEquals("Couldn't create lesson: topic must be 2-120 characters", failedRequestText(vm.uiState.value.failedRequests.single()))
    }

    @Test
    fun `try again queues the topic under a new request id and clears the failure`() = runTest {
        api.lessonResult = { _, _, _ -> ApiResult.Rejected(422, "generation_failed") }
        newVm()
        vm.askForLesson("decimals")
        val oldId = api.lessonCalls.single().third
        api.lessonResult = { _, _, _ -> ApiResult.Ok(RequestLessonResult("course-2", sampleBundle("course-2", "lesson-9"))) }

        vm.retryFailedRequest(vm.uiState.value.failedRequests.single().clientRequestId)

        assertEquals(2, api.lessonCalls.size)
        val newId = api.lessonCalls.last().third
        assertTrue("retry must not reuse the failed id", newId != oldId)
        assertEquals("decimals", api.lessonCalls.last().first)
        assertTrue(vm.uiState.value.failedRequests.isEmpty())
        assertNotNull(bundles.get("course-2"))
    }

    @Test
    fun `try again while offline stays queued with the offline message`() = runTest {
        api.lessonResult = { _, _, _ -> ApiResult.Rejected(400, "bad") }
        newVm()
        vm.askForLesson("decimals")
        net.online = false

        vm.retryFailedRequest(vm.uiState.value.failedRequests.single().clientRequestId)

        assertEquals(listOf("decimals"), vm.uiState.value.pendingTopics)
        assertEquals("Will be created when you're back online", pendingRequestMessage(vm.uiState.value)?.text)
        assertTrue(vm.uiState.value.failedRequests.isEmpty())
        assertEquals(1, api.lessonCalls.size)
    }

    @Test
    fun `dismissing a failure removes it`() = runTest {
        api.lessonResult = { _, _, _ -> ApiResult.Rejected(400, "bad") }
        newVm()
        vm.askForLesson("decimals")

        vm.dismissFailedRequest(vm.uiState.value.failedRequests.single().clientRequestId)

        assertTrue(vm.uiState.value.failedRequests.isEmpty())
        assertTrue(progress.data.failedRequests.isEmpty())
    }

    @Test
    fun `retrying an unknown failure does nothing`() = runTest {
        newVm()
        vm.retryFailedRequest("nope")
        assertTrue(dao.items.isEmpty())
        assertTrue(api.lessonCalls.isEmpty())
    }

    // ---- pending message depends on being online ----

    @Test
    fun `online with a request still pending says it is being created, not 'back online'`() = runTest {
        for (failure in listOf<ApiResult<RequestLessonResult>>(
            ApiResult.Rejected(409, "in_progress"), ApiResult.Retryable("HTTP 503"), ApiResult.Rejected(404, "gone")
        )) {
            dao.deleteAll()
            api.lessonResult = { _, _, _ -> failure }
            newVm()

            vm.askForLesson("fractions")

            val ui = vm.uiState.value
            assertTrue(ui.online)
            assertEquals(listOf("fractions"), ui.pendingTopics)
            val msg = pendingRequestMessage(ui)!!
            assertEquals("Creating your lesson with Meta Muse… this can take a minute", msg.text)
            assertTrue("shows a progress indicator", msg.showProgress)
            assertFalse(msg.text.contains("back online"))
            assertTrue("a retryable failure is not shown as a failure", ui.failedRequests.isEmpty())
        }
    }

    @Test
    fun `the message flips with connectivity`() = runTest {
        api.lessonResult = { _, _, _ -> ApiResult.Retryable("HTTP 503") }
        newVm()
        vm.askForLesson("fractions")
        assertTrue(pendingRequestMessage(vm.uiState.value)!!.showProgress)

        net.online = false
        assertEquals("Will be created when you're back online", pendingRequestMessage(vm.uiState.value)!!.text)
        assertFalse(pendingRequestMessage(vm.uiState.value)!!.showProgress)
    }

    @Test
    fun `an expired sign-in is not described as creating`() {
        val ui = LessonsUiState(online = true, pendingTopics = listOf("x"), syncStatus = SyncStatus.UNAUTHORIZED)
        val msg = pendingRequestMessage(ui)!!
        assertFalse(msg.showProgress)
        assertTrue(msg.text.contains("sign-in expired"))
    }

    @Test
    fun `no pending requests means no message`() {
        assertNull(pendingRequestMessage(LessonsUiState(online = true)))
        assertNull(pendingRequestMessage(LessonsUiState(online = false)))
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

    @Test
    fun `each image's stamp reaches the ui state, and only the written one changes`() = runTest {
        newVm()
        val cover = bundles.coverFile(course); val module = bundles.lessonImageFile(course, lesson)
        bundles.writeImage(module, JPEG_BYTES)
        val moduleStamp = vm.uiState.value.imageStamps[module.path]

        bundles.writeImage(cover, JPEG_BYTES)

        assertEquals(1L, vm.uiState.value.imageStamps[cover.path])
        assertEquals("the other image is not re-decoded", moduleStamp, vm.uiState.value.imageStamps[module.path])
        assertEquals(cover, vm.coverFile(course))
        assertEquals(module, vm.lessonImageFile(course, lesson))
    }

    @Test
    fun `pending image paths reach the ui state`() = runTest {
        newVm()
        bundles.setImagesPending(setOf("/x.jpg"))
        assertEquals(setOf("/x.jpg"), vm.uiState.value.pendingImages)
        bundles.imageDownloadFinished("/x.jpg")
        assertTrue(vm.uiState.value.pendingImages.isEmpty())
    }

    @Test
    fun `the course the reader shows its cover for can be looked up`() = runTest {
        bundles.save(sampleBundleWithImages(course, lesson))
        newVm()
        assertEquals(COVER_URL, vm.findCourse(course)?.coverImageUrl)
    }

    // ---- getting a learner token ----

    private fun tokenVm(
        hasUser: Boolean,
        refresher: FakeRefresher,
        store: FakeTokenStore = FakeTokenStore(),
        build: String = "",
        baseUrl: String = "http://x"
    ): LessonsViewModel {
        firebase.user = hasUser
        val config = SyncConfig(baseUrl, build, store)
        manager = testManager(api, dao, bundles, progress, config, net, refresher, consent)
            .also { it.ioDispatcher = Dispatchers.Unconfined }
        return LessonsViewModel(manager, bundles, progress, net, engine, locator, trigger, mutex, ActivePersona, firebase, consent)
            .also { it.ioDispatcher = io; vm = it }
    }

    @Test
    fun `nobody signed in offers Sign in to get lessons`() = runTest {
        val refresher = FakeRefresher(ensure = { error("must not exchange without a user") })
        tokenVm(hasUser = false, refresher = refresher)

        val ui = vm.uiState.value
        assertEquals(LessonsSignIn.NEEDS_SIGN_IN, ui.signIn)
        assertFalse(ui.configured); assertTrue(ui.needsToken)
        assertEquals(0, refresher.ensureCalls)
    }

    @Test
    fun `a signed-in user with no token is exchanged silently and lessons open`() = runTest {
        val store = FakeTokenStore()
        val refresher = FakeRefresher(ensure = { store.token = "exchanged"; true })

        tokenVm(hasUser = true, refresher = refresher, store = store)

        assertTrue("the exchange ran", refresher.ensureCalls >= 1)
        val ui = vm.uiState.value
        assertEquals(LessonsSignIn.READY, ui.signIn)
        assertTrue(ui.configured)
        assertNull(ui.signInMessage)
        assertTrue("a sync follows", api.meCalls >= 1)
    }

    @Test
    fun `while the exchange runs the screen says it is getting lessons ready`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val store = FakeTokenStore()
        val refresher = object : TokenRefresher {
            override suspend fun ensureToken(): Boolean { gate.await(); store.token = "t"; return true }
            override suspend fun refreshAfter401() = false
        }
        firebase.user = true
        val config = SyncConfig("http://x", "", store)
        manager = testManager(api, dao, bundles, progress, config, net, refresher, consent)
            .also { it.ioDispatcher = Dispatchers.Unconfined }
        val v = LessonsViewModel(manager, bundles, progress, net, engine, locator, trigger, mutex, ActivePersona, firebase, consent)
            .also { it.ioDispatcher = io }

        assertEquals(LessonsSignIn.GETTING_READY, v.uiState.value.signIn)
        gate.complete(Unit)
        assertEquals(LessonsSignIn.READY, v.uiState.value.signIn)
    }

    @Test
    fun `offline with no token says to connect once, and does not try`() = runTest {
        net.online = false
        val refresher = FakeRefresher(ensure = { error("no network, no exchange") })

        tokenVm(hasUser = true, refresher = refresher)

        val ui = vm.uiState.value
        assertEquals(LessonsSignIn.FAILED, ui.signIn)
        assertEquals(LessonsViewModel.OFFLINE_SETUP_MESSAGE, ui.signInMessage)
        assertEquals(0, refresher.ensureCalls)
    }

    @Test
    fun `coming back online sets the token up`() = runTest {
        net.online = false
        val store = FakeTokenStore()
        val refresher = FakeRefresher(ensure = { store.token = "t"; true })
        tokenVm(hasUser = true, refresher = refresher, store = store)
        assertEquals(LessonsSignIn.FAILED, vm.uiState.value.signIn)

        net.online = true

        assertEquals(LessonsSignIn.READY, vm.uiState.value.signIn)
    }

    @Test
    fun `a failed exchange can be retried`() = runTest {
        val store = FakeTokenStore()
        var attempts = 0
        val refresher = FakeRefresher(ensure = { if (++attempts == 1) false else { store.token = "t"; true } })
        tokenVm(hasUser = true, refresher = refresher, store = store)
        assertEquals(LessonsSignIn.FAILED, vm.uiState.value.signIn)
        assertEquals(LessonsViewModel.SETUP_FAILED_MESSAGE, vm.uiState.value.signInMessage)

        vm.retrySignIn()

        assertEquals(LessonsSignIn.READY, vm.uiState.value.signIn)
        assertNull(vm.uiState.value.signInMessage)
    }

    @Test
    fun `back from the sign-in screen exchanges the new user`() = runTest {
        val store = FakeTokenStore()
        val refresher = FakeRefresher(ensure = { store.token = "t"; true })
        tokenVm(hasUser = false, refresher = refresher, store = store)
        assertEquals(LessonsSignIn.NEEDS_SIGN_IN, vm.uiState.value.signIn)

        firebase.user = true       // they signed in on the other screen
        vm.onSignedIn()

        assertEquals(LessonsSignIn.READY, vm.uiState.value.signIn)
    }

    @Test
    fun `a build with no lessons server says so, and a build-time token needs no sign-in`() = runTest {
        tokenVm(hasUser = false, refresher = FakeRefresher(), baseUrl = "")
        assertEquals(LessonsSignIn.UNAVAILABLE, vm.uiState.value.signIn)

        tokenVm(hasUser = false, refresher = FakeRefresher(), build = "debug-token")
        assertEquals(LessonsSignIn.READY, vm.uiState.value.signIn)
    }

    @Test
    fun `a stored token keeps lessons working offline`() = runTest {
        net.online = false
        val refresher = FakeRefresher(ensure = { true })
        tokenVm(hasUser = true, refresher = refresher, store = FakeTokenStore(token = fakeJwt(4_000_000_000L)))

        assertEquals(LessonsSignIn.READY, vm.uiState.value.signIn)
        assertEquals(0, refresher.ensureCalls)
    }

    @Test
    fun `the sign-in state table`() {
        fun st(c: Boolean, n: Boolean, u: Boolean, g: Boolean, f: Boolean) = lessonsSignInState(c, n, u, g, f)
        assertEquals(LessonsSignIn.READY, st(true, false, false, false, false))
        assertEquals(LessonsSignIn.UNAVAILABLE, st(false, false, false, false, false))
        assertEquals(LessonsSignIn.NEEDS_SIGN_IN, st(false, true, false, false, false))
        assertEquals(LessonsSignIn.GETTING_READY, st(false, true, true, true, false))
        assertEquals(LessonsSignIn.GETTING_READY, st(false, true, true, false, false))
        assertEquals(LessonsSignIn.FAILED, st(false, true, true, false, true))
        assertEquals("busy beats a stale failure", LessonsSignIn.GETTING_READY, st(false, true, true, true, true))
    }

    @Test
    fun `a failed first exchange is not silently retried by the first connectivity reading`() = runTest {
        var attempts = 0
        val refresher = FakeRefresher(ensure = { attempts++; false })

        tokenVm(hasUser = true, refresher = refresher)

        assertEquals(1, attempts)
        assertEquals(LessonsSignIn.FAILED, vm.uiState.value.signIn)
    }
}
