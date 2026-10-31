package org.ekatra.alfred.lessons

import kotlinx.coroutines.test.runTest
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

class LearnerTokenProviderTest {

    private val nowMs = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private lateinit var dir: File
    private lateinit var api: FakeApi
    private lateinit var firebase: FakeFirebase
    private lateinit var net: FakeConnectivity
    private lateinit var store: FakeTokenStore
    private lateinit var dao: FakeQueueDao
    private lateinit var bundles: LessonStore
    private lateinit var progress: LessonProgressStore
    private lateinit var provider: LearnerTokenProvider
    private lateinit var config: SyncConfig

    private fun jwtIn(days: Long) = fakeJwt((nowMs + days * day) / 1000)

    @Before
    fun setup() {
        dir = Files.createTempDirectory("token-provider").toFile()
        api = FakeApi(); firebase = FakeFirebase(); net = FakeConnectivity(true); store = FakeTokenStore(); dao = FakeQueueDao()
        bundles = LessonStore(File(dir, "lessons")); progress = LessonProgressStore(File(dir, "p.json"))
        build(buildToken = "")
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun build(buildToken: String) {
        config = SyncConfig("http://x", buildToken, store)
        provider = LearnerTokenProvider(api, firebase, config, net, LocalLearnerData(dao, bundles, progress)).also { it.now = { nowMs } }
    }

    // ---- token order ----

    @Test
    fun `the exchanged token beats the build-time token, and the build-time token is the fallback`() = runTest {
        build(buildToken = "build-token")
        assertEquals("build-token", config.token)

        provider.exchange()

        assertEquals("exchanged-jwt", config.token)
        assertTrue(config.hasStoredToken)

        store.clearAll()
        assertEquals("build-token", config.token)
        store.clearAll(); build(buildToken = "")
        assertEquals("", config.token)
    }

    // ---- exchange on sign-in ----

    @Test
    fun `exchange after sign-in sends the Firebase ID token and stores the learner jwt and id`() = runTest {
        firebase.idTokens = mutableListOf("firebase-id-token")

        val result = provider.exchange()

        assertEquals(ExchangeResult.Done, result)
        assertEquals(listOf("firebase-id-token"), api.exchangeCalls)
        assertEquals(listOf(false), firebase.idTokenRequests)   // a cached ID token first
        assertEquals("exchanged-jwt", store.token)
        assertEquals("learner-1", store.learner)
        assertTrue(config.isConfigured)
    }

    @Test
    fun `a 401 on the first try gets one retry with a force-refreshed ID token`() = runTest {
        firebase.idTokens = mutableListOf("stale", "fresh")
        api.exchangeResult = { id -> if (id == "stale") ExchangeOutcome.Unauthorized else ExchangeOutcome.Success("jwt-ok", "learner-1", null, null, null) }

        assertEquals(ExchangeResult.Done, provider.exchange())

        assertEquals(listOf("stale", "fresh"), api.exchangeCalls)
        assertEquals(listOf(false, true), firebase.idTokenRequests)
        assertEquals("jwt-ok", store.token)
    }

    @Test
    fun `a second 401 means Firebase no longer vouches for the user`() = runTest {
        api.exchangeResult = { ExchangeOutcome.Unauthorized }
        assertEquals(ExchangeResult.Unauthorized, provider.exchange())
        assertEquals(2, api.exchangeCalls.size)
        assertNull(store.token)
    }

    @Test
    fun `no Firebase user and no network are reported, and nothing is stored`() = runTest {
        firebase.user = false
        assertEquals(ExchangeResult.NoFirebaseUser, provider.exchange())

        firebase.user = true; net.online = false
        assertEquals(ExchangeResult.Offline, provider.exchange())
        assertTrue(api.exchangeCalls.isEmpty())
        assertNull(store.token)
    }

    @Test
    fun `a server error is a failure to retry, and keeps what was stored`() = runTest {
        store.token = jwtIn(20)
        api.exchangeResult = { ExchangeOutcome.Failed("HTTP 503") }

        assertEquals(ExchangeResult.Failed("HTTP 503"), provider.exchange())
        assertNotNull(store.token)
    }

    @Test
    fun `a different person signing in wipes the last learner's lessons first`() = runTest {
        store.learner = "someone-else"
        bundles.save(sampleBundle("course-1", "lesson-1"))
        progress.markCompleted("lesson-1")
        dao.insert(org.ekatra.alfred.data.model.SyncQueueItem(type = "learner_event", payload = "{}"))

        provider.exchange() // fake returns learner-1

        assertNull(bundles.get("course-1"))
        assertTrue(progress.data.completedLessonIds.isEmpty())
        assertTrue(dao.items.none { it.type == "learner_event" })
        assertEquals("learner-1", store.learner)
    }

    @Test
    fun `the same person signing in again keeps their lessons`() = runTest {
        store.learner = "learner-1"
        bundles.save(sampleBundle("course-1", "lesson-1"))
        dao.insert(org.ekatra.alfred.data.model.SyncQueueItem(type = "learner_event", payload = "{}"))

        provider.exchange()

        assertNotNull(bundles.get("course-1"))
        assertEquals(1, dao.items.size)
    }

    // ---- ensureToken: when to exchange ----

    @Test
    fun `a Firebase user with no token is exchanged`() = runTest {
        assertTrue(provider.ensureToken())
        assertEquals(1, api.exchangeCalls.size)
    }

    @Test
    fun `a healthy token is left alone`() = runTest {
        store.token = jwtIn(20)

        assertTrue(provider.ensureToken())

        assertTrue(api.exchangeCalls.isEmpty())
        assertEquals(jwtIn(20), store.token)
    }

    @Test
    fun `a token within 3 days of expiry is exchanged again`() = runTest {
        store.token = jwtIn(2)

        provider.ensureToken()

        assertEquals(1, api.exchangeCalls.size)
        assertEquals("exchanged-jwt", store.token)
    }

    @Test
    fun `the refresh window is 3 days`() {
        assertFalse(provider.isNearExpiry(jwtIn(4)))
        assertTrue(provider.isNearExpiry(jwtIn(3)))   // exactly 3 days left counts
        assertTrue(provider.isNearExpiry(jwtIn(1)))
        assertTrue("already expired", provider.isNearExpiry(jwtIn(-1)))
        assertFalse("an opaque token has no readable expiry", provider.isNearExpiry("demo-token"))
        assertFalse("a jwt without exp", provider.isNearExpiry(fakeJwt(null)))
    }

    @Test
    fun `offline, a stored token keeps working and nothing is attempted`() = runTest {
        store.token = jwtIn(1)  // near expiry, but we cannot reach the server
        net.online = false

        assertTrue(provider.ensureToken())

        assertTrue(api.exchangeCalls.isEmpty())
        assertEquals(jwtIn(1), store.token)
    }

    @Test
    fun `offline with no token there is nothing to use`() = runTest {
        net.online = false
        assertFalse(provider.ensureToken())
        assertTrue(api.exchangeCalls.isEmpty())
    }

    @Test
    fun `no Firebase user means no exchange`() = runTest {
        firebase.user = false
        assertFalse(provider.ensureToken())
        assertTrue(api.exchangeCalls.isEmpty())
    }

    @Test
    fun `a near-expiry token survives a failed re-exchange`() = runTest {
        store.token = jwtIn(1)
        api.exchangeResult = { ExchangeOutcome.Failed("down") }

        assertTrue("the old one still works until it expires", provider.ensureToken())
        assertEquals(jwtIn(1), store.token)
    }

    @Test
    fun `with a Firebase user the exchanged token replaces the debug build token`() = runTest {
        build(buildToken = "debug-token")

        provider.ensureToken()

        assertEquals("exchanged-jwt", config.token)
    }

    // ---- 401 ----

    @Test
    fun `after a 401 a fresh ID token is forced and exchanged`() = runTest {
        store.token = jwtIn(20)

        assertTrue(provider.refreshAfter401())

        assertEquals(listOf(true), firebase.idTokenRequests)
        assertEquals("exchanged-jwt", store.token)
    }

    @Test
    fun `a 401 refresh fails cleanly when offline, signed out, or refused`() = runTest {
        net.online = false
        assertFalse(provider.refreshAfter401())
        net.online = true; firebase.user = false
        assertFalse(provider.refreshAfter401())
        firebase.user = true
        api.exchangeResult = { ExchangeOutcome.Unauthorized }
        assertFalse(provider.refreshAfter401())
        api.exchangeResult = { ExchangeOutcome.Failed("503") }
        assertFalse(provider.refreshAfter401())
    }
}
