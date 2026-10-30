package org.ekatra.alfred.data

import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.perf.FirebasePerformance
import io.mockk.*
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConsentManagerTest {

    private val settings = mockk<AppSettings>(relaxed = true)
    private val analytics = mockk<FirebaseAnalytics>(relaxed = true)
    private val crashlytics = mockk<FirebaseCrashlytics>(relaxed = true)
    private val performance = mockk<FirebasePerformance>(relaxed = true)
    private val consent = ConsentManager(settings, analytics)

    @Before
    fun setup() {
        mockkStatic(FirebaseCrashlytics::class, FirebasePerformance::class)
        every { FirebaseCrashlytics.getInstance() } returns crashlytics
        every { FirebasePerformance.getInstance() } returns performance
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `accept enables every SDK and stores feedback opt-in`() {
        consent.record(collection = true, feedback = true)

        verify { settings.setAnalyticsConsent(true) }
        verify { settings.setFeedbackConsent(true) }
        verify { analytics.setAnalyticsCollectionEnabled(true) }
        verify { crashlytics.setCrashlyticsCollectionEnabled(true) }
        verify { performance.isPerformanceCollectionEnabled = true }
    }

    @Test
    fun `decline disables every SDK and never stores feedback opt-in`() {
        consent.record(collection = false, feedback = true)

        verify { settings.setFeedbackConsent(false) }
        verify { analytics.setAnalyticsCollectionEnabled(false) }
        verify { crashlytics.setCrashlyticsCollectionEnabled(false) }
        verify { performance.isPerformanceCollectionEnabled = false }
    }

    @Test
    fun `applyStored mirrors the stored choice`() {
        every { settings.hasAnalyticsConsent() } returns false
        consent.applyStored()
        verify { analytics.setAnalyticsCollectionEnabled(false) }
    }

    @Test
    fun `needsPrompt is true when consent_shown is absent`() {
        every { settings.isConsentShown() } returns false
        assertTrue(consent.needsPrompt())
    }

    @Test
    fun `needsPrompt is false when consent_shown is present`() {
        every { settings.isConsentShown() } returns true
        assertFalse(consent.needsPrompt())
    }
}
