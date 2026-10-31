package org.ekatra.alfred.lessons

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class LessonsConsentTest {

    private lateinit var dir: File
    private lateinit var api: FakeApi
    private lateinit var firebase: FakeFirebase
    private lateinit var net: FakeConnectivity
    private lateinit var dao: FakeQueueDao
    private lateinit var bundles: LessonStore
    private lateinit var progress: LessonProgressStore
    private val trigger = FakeTrigger()
    private val io = CountingDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        dir = Files.createTempDirectory("consent").toFile()
        api = FakeApi(); firebase = FakeFirebase(); net = FakeConnectivity(true); dao = FakeQueueDao()
        bundles = LessonStore(File(dir, "lessons")); progress = LessonProgressStore(File(dir, "p.json"))
    }

    @After fun tearDown() { Dispatchers.resetMain(); dir.deleteRecursively() }

    private fun consentAt(accepted: Int = 0, declined: Int = 0) = LessonsConsent(FakeConsentStore(accepted, declined))

    // ---- versions ----

    @Test
    fun `the current version is 2, the wording that mentions server storage`() {
        assertEquals(2, LessonsConsent.CURRENT_VERSION)
    }

    @Test
    fun `never asked and an older version both need the notice`() {
        assertEquals(ConsentState.NEEDED, consentAt(accepted = 0).state.value)
        assertEquals("version 1 never mentioned server storage", ConsentState.NEEDED, consentAt(accepted = 1).state.value)
        assertFalse(consentAt(accepted = 1).isAccepted())
    }

    @Test
    fun `the current version and any later one are accepted`() {
        assertTrue(consentAt(accepted = 2).isAccepted())
        assertTrue(consentAt(accepted = 3).isAccepted())
    }

    @Test
    fun `accepting records the current version and survives a restart`() {
        val store = FakeConsentStore()
        val c = LessonsConsent(store)

        c.accept()

        assertEquals(ConsentState.ACCEPTED, c.state.value)
        assertEquals(LessonsConsent.CURRENT_VERSION, store.acceptedAt)
        assertTrue(LessonsConsent(store).isAccepted())
    }

    @Test
    fun `declining is remembered, can be reviewed, and a later accept clears it`() {
        val store = FakeConsentStore()
        val c = LessonsConsent(store)

        c.decline()
        assertEquals(ConsentState.DECLINED, c.state.value)
        assertEquals(ConsentState.DECLINED, LessonsConsent(store).state.value)

        c.reconsider()
        assertEquals(ConsentState.NEEDED, c.state.value)

        c.decline(); c.accept()
        assertEquals(ConsentState.ACCEPTED, c.state.value)
        assertEquals(0, store.declinedAt)
    }

    @Test
    fun `a decline of an older wording does not count against the new one`() {
        assertEquals(ConsentState.NEEDED, consentAt(declined = 1).state.value)
    }

    @Test
    fun `clearing forgets the choice`() {
        val c = consentAt(accepted = 2)
        c.clear()
        assertEquals(ConsentState.NEEDED, c.state.value)
    }

    @Test
    fun `the prefs store round-trips both versions`() {
        val prefs = FakePrefs()
        val store = PrefsLessonsConsentStore(prefs)
        assertEquals(0, store.acceptedVersion()); assertEquals(0, store.declinedVersion())

        store.setDeclined(2)
        assertEquals(2, store.declinedVersion())
        store.setAccepted(2)
        assertEquals(2, PrefsLessonsConsentStore(prefs).acceptedVersion())
        assertEquals("accepting drops the decline", 0, store.declinedVersion())

        store.clear()
        assertEquals(0, store.acceptedVersion())
    }

    @Test
    fun `it is separate from the analytics consent`() {
        // The Lessons notice has its own file; analytics lives in AppSettings and is not consulted here.
        assertEquals("lessons_consent", PrefsLessonsConsentStore.FILE)
        assertFalse(consentAt().isAccepted())   // nothing about analytics being on or off changes this
    }

    // ---- the main consent screen ----

    @Test
    fun `accepting the main consent screen on Maya records the current Lessons version, so a fresh install is not asked twice`() {
        val store = FakeConsentStore()
        val c = LessonsConsent(store)
        assertEquals(ConsentState.NEEDED, c.state.value)

        c.recordFromMainConsentScreen(accepted = true, personaKey = "maya")

        assertEquals(ConsentState.ACCEPTED, c.state.value)
        assertEquals(LessonsConsent.CURRENT_VERSION, store.acceptedAt)
        assertTrue(LessonsConsent(store).isAccepted())
    }

    @Test
    fun `continuing without analytics records nothing for Lessons, which still asks once`() {
        val c = consentAt()

        c.recordFromMainConsentScreen(accepted = false, personaKey = "maya")

        assertEquals(ConsentState.NEEDED, c.state.value)
    }

    @Test
    fun `Asmaa has no Lessons, so its consent screen never records Lessons consent`() {
        val c = consentAt()
        c.recordFromMainConsentScreen(accepted = true, personaKey = "asmaa")
        assertEquals(ConsentState.NEEDED, c.state.value)
        assertFalse(personaHasLessons("asmaa"))
        assertTrue(personaHasLessons("maya"))
    }

    @Test
    fun `someone who accepted the old screen has no Lessons version and is still asked once`() {
        // The old screen (v1) had no Lessons line and recorded nothing for Lessons.
        val c = consentAt(accepted = 0)
        assertEquals(ConsentState.NEEDED, c.state.value)
        assertFalse(c.isAccepted())
        // Accepting the new Lessons notice is what records it.
        c.accept()
        assertTrue(c.isAccepted())
    }

    @Test
    fun `a decline of the Lessons notice is not undone by a later main-screen decline`() {
        val c = consentAt()
        c.decline()
        c.recordFromMainConsentScreen(accepted = false, personaKey = "maya")
        assertEquals(ConsentState.DECLINED, c.state.value)
    }

    @Test
    fun `the consent screen wires accept to the Lessons record and decline to a no-op, using the same Maya check as the bullet`() {
        val screen = res("main/java/org/ekatra/alfred/PrivacyConsentActivity.kt")
        assertTrue(screen.contains("lessonsConsent.recordFromMainConsentScreen(accepted = true, personaKey = ActivePersona.key)"))
        assertTrue(screen.contains("lessonsConsent.recordFromMainConsentScreen(accepted = false, personaKey = ActivePersona.key)"))
        assertTrue(screen.contains("if (personaHasLessons(ActivePersona.key)) BulletPoint(stringResource(R.string.privacy_collect_lessons))"))
    }

    // ---- an old version blocks the exchange; accepting allows it ----

    private fun provider(consent: LessonsConsent, store: FakeTokenStore = FakeTokenStore()): LearnerTokenProvider {
        val config = SyncConfig("http://x", "", store)
        return LearnerTokenProvider(api, firebase, config, net, LocalLearnerData(dao, bundles, progress), consent)
    }

    @Test
    fun `an old consent version blocks the token exchange completely`() = runTest {
        val p = provider(consentAt(accepted = 1))

        assertEquals(ExchangeResult.ConsentNeeded, p.exchange())
        assertFalse(p.ensureToken())
        assertFalse(p.refreshAfter401())

        assertTrue("no exchange call", api.exchangeCalls.isEmpty())
        assertTrue("not even a look at Firebase", firebase.idTokenRequests.isEmpty())
    }

    @Test
    fun `accepting lets the exchange through`() = runTest {
        val consent = consentAt(accepted = 1)
        val p = provider(consent)
        assertEquals(ExchangeResult.ConsentNeeded, p.exchange())

        consent.accept()

        assertEquals(ExchangeResult.Done, p.exchange())
        assertEquals(1, api.exchangeCalls.size)
    }

    @Test
    fun `declining makes no exchange calls`() = runTest {
        val p = provider(consentAt().also { it.decline() })
        p.exchange(); p.ensureToken(); p.refreshAfter401()
        assertTrue(api.exchangeCalls.isEmpty())
    }

    // ---- the manager ----

    private fun manager(consent: LessonsConsent, store: FakeTokenStore = FakeTokenStore(token = "tok", learner = "L")) =
        testManager(api, dao, bundles, progress, SyncConfig("http://x", "", store), net, FakeRefresher(ensure = { true }), consent)

    @Test
    fun `declining or an old version makes no network calls at all, and queued data stays`() = runTest {
        for (consent in listOf(consentAt(), consentAt(accepted = 1), consentAt().also { it.decline() })) {
            val m = manager(consent)
            bundles.save(sampleBundleWithImages())
            m.enqueueQuizAnswer("course-1", "lesson-1", "q1", "B", true)
            m.enqueueLessonRequest("fractions", "en")

            val report = m.sync()

            assertEquals(SyncStatus.CONSENT_NEEDED, report.status)
            assertEquals(SyncStatus.CONSENT_NEEDED, m.status.value)
            assertTrue("events", api.eventBatches.isEmpty())
            assertTrue("lesson requests", api.lessonCalls.isEmpty())
            assertEquals("me", 0, api.meCalls)
            assertTrue("images", api.imageCalls.isEmpty())
            assertTrue("exchange", api.exchangeCalls.isEmpty())
            assertEquals("the answer and the request are kept for later", 2, dao.items.size)
            dao.deleteAll()
        }
    }

    @Test
    fun `the manager does not even try to get a token before consent`() = runTest {
        val refresher = FakeRefresher(ensure = { true })
        val m = testManager(api, dao, bundles, progress, SyncConfig("http://x", "", FakeTokenStore()), net, refresher, consentAt(accepted = 1))

        assertFalse(m.ensureToken())
        m.sync()

        assertEquals(0, refresher.ensureCalls)
    }

    @Test
    fun `after accepting, the same data syncs`() = runTest {
        val consent = consentAt(accepted = 1)
        val m = manager(consent)
        m.enqueueQuizAnswer("course-1", "lesson-1", "q1", "B", true)
        assertEquals(SyncStatus.CONSENT_NEEDED, m.sync().status)

        consent.accept()
        val report = m.sync()

        assertEquals(SyncStatus.SYNCED, report.status)
        assertEquals(1, api.eventBatches.size)
        assertTrue(dao.items.isEmpty())
    }

    // ---- the Lessons screen ----

    private fun lessonsVm(consent: LessonsConsent, refresher: FakeRefresher, store: FakeTokenStore = FakeTokenStore()): LessonsViewModel {
        val config = SyncConfig("http://x", "", store)
        val m = testManager(api, dao, bundles, progress, config, net, refresher, consent).also { it.ioDispatcher = Dispatchers.Unconfined }
        return LessonsViewModel(m, bundles, progress, net, FakeEngine(), FakeLocator(), trigger, kotlinx.coroutines.sync.Mutex(),
            org.ekatra.alfred.persona.ActivePersona, firebase, consent).also { it.ioDispatcher = io }
    }

    @Test
    fun `an old version blocks exchange and sync from the Lessons screen`() = runTest {
        val refresher = FakeRefresher(ensure = { error("must not exchange before consent") })

        val vm = lessonsVm(consentAt(accepted = 1), refresher)

        assertEquals(ConsentState.NEEDED, vm.uiState.value.consent)
        assertEquals(0, refresher.ensureCalls)
        assertTrue(api.exchangeCalls.isEmpty() && api.meCalls == 0 && api.eventBatches.isEmpty())
        assertEquals("Needs your OK", syncChipText(vm.uiState.value))
        assertFalse("no lesson can be requested", vm.askForLesson("fractions"))
        assertTrue(dao.items.isEmpty())
    }

    @Test
    fun `accepting from the Lessons screen exchanges and then syncs`() = runTest {
        val store = FakeTokenStore()
        val refresher = FakeRefresher(ensure = { store.token = "t"; true })
        val vm = lessonsVm(consentAt(accepted = 1), refresher, store)
        assertEquals(0, refresher.ensureCalls)

        vm.acceptConsent()

        assertEquals(ConsentState.ACCEPTED, vm.uiState.value.consent)
        assertTrue("exchange ran after accepting", refresher.ensureCalls >= 1)
        assertEquals(LessonsSignIn.READY, vm.uiState.value.signIn)
        assertTrue("and a sync followed", api.meCalls >= 1)
    }

    @Test
    fun `declining from the Lessons screen makes no calls and shows it needs this to sync`() = runTest {
        val refresher = FakeRefresher(ensure = { error("no") })
        val vm = lessonsVm(consentAt(), refresher)

        vm.declineConsent()

        assertEquals(ConsentState.DECLINED, vm.uiState.value.consent)
        assertEquals(0, refresher.ensureCalls)
        assertTrue(api.exchangeCalls.isEmpty() && api.meCalls == 0 && api.eventBatches.isEmpty() && api.lessonCalls.isEmpty())
        // Coming back online changes nothing either.
        net.online = false; net.online = true
        assertEquals(0, api.meCalls)
        assertEquals(0, refresher.ensureCalls)
    }

    @Test
    fun `a declined notice can be reviewed again`() = runTest {
        val vm = lessonsVm(consentAt().also { it.decline() }, FakeRefresher())
        assertEquals(ConsentState.DECLINED, vm.uiState.value.consent)

        vm.reviewConsent()

        assertEquals(ConsentState.NEEDED, vm.uiState.value.consent)
    }

    @Test
    fun `an accepted notice leaves the existing behaviour alone`() = runTest {
        val store = FakeTokenStore(token = fakeJwt(4_000_000_000L))
        val vm = lessonsVm(acceptedConsent(), FakeRefresher(ensure = { true }), store)
        assertEquals(ConsentState.ACCEPTED, vm.uiState.value.consent)
        assertEquals(LessonsSignIn.READY, vm.uiState.value.signIn)
        assertNull(vm.uiState.value.signInMessage)
    }

    // ---- the words ----

    private fun res(path: String) = File("src/$path").readText()

    @Test
    fun `the Lessons notice says what is stored, where, why, and that it is deleted with the account`() {
        val strings = res("main/res/values/strings.xml")
        for (name in listOf("lessons_consent_body", "privacy_collect_lessons")) {
            val text = Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(strings)?.groupValues?.get(1)
            assertTrue("$name exists", text != null)
            for (phrase in listOf("name", "contact (phone or email)", "lesson progress", "Ekatra", "servers", "sync across", "WhatsApp", "deleted when you delete your account")) {
                assertTrue("$name should mention '$phrase'", text!!.contains(phrase))
            }
        }
    }

    @Test
    fun `Asmaa's text is untouched`() {
        val asmaa = res("asmaa/res/values/strings.xml")
        assertFalse(asmaa.contains("privacy_collect_lessons"))
        assertFalse(asmaa.contains("lessons_consent"))
    }

    @Test
    fun `the consent screen shows the Lessons line only on Maya`() {
        val screen = res("main/java/org/ekatra/alfred/PrivacyConsentActivity.kt")
        assertTrue(screen.contains("if (personaHasLessons(ActivePersona.key)) BulletPoint(stringResource(R.string.privacy_collect_lessons))"))
    }

    @Test
    fun `the declined message is short and plain`() {
        val strings = res("main/res/values/strings.xml")
        assertTrue(strings.contains("""<string name="lessons_consent_declined_title">Lessons needs this to sync</string>"""))
    }

    @Test
    fun `the Maya deletion dialog says lessons, progress and WhatsApp history go, in one plain sentence`() {
        val strings = res("main/res/values/strings.xml")
        val message = Regex("""<string name="delete_account_message">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(strings)?.groupValues?.get(1) ?: error("delete_account_message missing")

        assertTrue(message.contains("This also deletes your lessons and progress on Ekatra\\'s servers, including your WhatsApp lesson history."))
        // The existing promises are all still there.
        assertTrue(message.contains("permanently deletes your account"))
        assertTrue(message.contains("Chats stored on this device are also removed"))
        assertTrue(message.contains("This cannot be undone"))
    }

    @Test
    fun `Asmaa's deletion text keeps its own wording`() {
        val asmaa = res("asmaa/res/values/strings.xml")
        val message = Regex("""<string name="delete_account_message">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(asmaa)?.groupValues?.get(1)
        assertTrue("Asmaa still overrides it", message != null)
        assertFalse(message!!.contains("WhatsApp"))
        assertFalse(message.contains("Ekatra"))
    }
}
