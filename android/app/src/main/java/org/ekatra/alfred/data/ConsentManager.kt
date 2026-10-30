package org.ekatra.alfred.data

import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.perf.FirebasePerformance
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of the user's data-collection choice.
 *
 * Analytics, Crashlytics and Performance are disabled in the manifest so nothing is
 * collected before the consent screen; [record] and [applyStored] switch them on
 * only when the user accepted. Cloud profile sync is gated on the same choice.
 */
@Singleton
class ConsentManager @Inject constructor(
    private val settings: AppSettings,
    private val analytics: FirebaseAnalytics
) {
    fun needsPrompt(): Boolean = !settings.isConsentShown()

    fun hasCollectionConsent(): Boolean = settings.hasAnalyticsConsent()

    fun record(collection: Boolean, feedback: Boolean) {
        settings.setAnalyticsConsent(collection)
        settings.setFeedbackConsent(collection && feedback)
        applyToSdks(collection)
    }

    /** Call on app start so SDK state always matches the stored choice. */
    fun applyStored() = applyToSdks(settings.hasAnalyticsConsent())

    private fun applyToSdks(granted: Boolean) {
        analytics.setAnalyticsCollectionEnabled(granted)
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(granted)
        FirebasePerformance.getInstance().isPerformanceCollectionEnabled = granted
    }
}
