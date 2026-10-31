package org.ekatra.alfred.lessons

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Only Maya has Lessons, so only Maya's consent screen carries the Lessons wording. One check for both uses. */
fun personaHasLessons(personaKey: String): Boolean = personaKey == "maya"

/**
 * What the main consent screen's ACCEPT button does for Lessons. On Maya that screen carries the same Lessons
 * wording as the Lessons notice (including that the topic goes to Meta's AI and that data is stored on Ekatra's
 * servers), so accepting it records the current Lessons version and a fresh install is not asked twice.
 *
 * "Continue without analytics" does NOT call this. A decline button must not count as agreeing to server
 * storage or to Meta processing: after declining, Lessons and the WhatsApp sign-in ask on first use. People who
 * saw an older screen, which had no Lessons line, have no Lessons version and are asked once too.
 */
fun LessonsConsent.recordFromMainConsentScreen(personaKey: String) {
    if (personaHasLessons(personaKey)) accept()
}

/** Where the Lessons consent choice is kept. */
interface LessonsConsentStore {
    fun acceptedVersion(): Int
    fun declinedVersion(): Int
    fun setAccepted(version: Int)
    fun setDeclined(version: Int)
    fun clear()
}

class PrefsLessonsConsentStore(private val prefs: SharedPreferences) : LessonsConsentStore {
    override fun acceptedVersion(): Int = prefs.getInt(KEY_ACCEPTED, 0)
    override fun declinedVersion(): Int = prefs.getInt(KEY_DECLINED, 0)
    override fun setAccepted(version: Int) { prefs.edit().putInt(KEY_ACCEPTED, version).remove(KEY_DECLINED).commit() }
    override fun setDeclined(version: Int) { prefs.edit().putInt(KEY_DECLINED, version).commit() }
    override fun clear() { prefs.edit().clear().commit() }

    companion object {
        const val FILE = "lessons_consent"
        private const val KEY_ACCEPTED = "accepted_version"
        private const val KEY_DECLINED = "declined_version"
    }
}

enum class ConsentState {
    /** The current wording has been accepted. */
    ACCEPTED,
    /** Never asked, or asked about an older wording: ask now. */
    NEEDED,
    /** They said no to the current wording. */
    DECLINED
}

/**
 * Consent to Lessons storing a learner record (name, contact, progress) on Ekatra's servers.
 *
 * This is the product's own notice, not analytics: it is kept apart from the analytics toggle in
 * [org.ekatra.alfred.data.ConsentManager], and declining it leaves chat and analytics untouched.
 * It is versioned, so a change to the wording ([CURRENT_VERSION]) asks existing users again. Until it is
 * accepted nothing leaves the phone for Lessons: no token exchange, no sync, no lesson requests.
 */
@Singleton
class LessonsConsent @Inject constructor(private val store: LessonsConsentStore) {

    companion object {
        /** Bump when the wording changes. 1 was the original, which never mentioned server storage. */
        const val CURRENT_VERSION = 2
    }

    private val _state = MutableStateFlow(read())
    val state: StateFlow<ConsentState> = _state

    fun isAccepted(): Boolean = _state.value == ConsentState.ACCEPTED

    fun accept() {
        store.setAccepted(CURRENT_VERSION)
        _state.value = read()
    }

    fun decline() {
        store.setDeclined(CURRENT_VERSION)
        _state.value = read()
    }

    /** They declined, then asked to look again. */
    fun reconsider() {
        store.setDeclined(0)
        _state.value = read()
    }

    /** Forget the choice (account deletion), so the next person on this phone is asked. */
    fun clear() {
        store.clear()
        _state.value = read()
    }

    private fun read(): ConsentState = when {
        store.acceptedVersion() >= CURRENT_VERSION -> ConsentState.ACCEPTED
        store.declinedVersion() >= CURRENT_VERSION -> ConsentState.DECLINED
        else -> ConsentState.NEEDED
    }
}
