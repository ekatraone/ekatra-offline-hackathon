package org.ekatra.alfred.lessons

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

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
