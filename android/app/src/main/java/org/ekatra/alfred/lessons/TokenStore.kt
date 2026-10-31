package org.ekatra.alfred.lessons

import android.content.SharedPreferences

/** Where the learner JWT obtained from the server is kept. */
interface TokenStore {
    fun token(): String?
    fun learnerId(): String?
    fun save(token: String, learnerId: String?)
    /** Drop the token but remember which learner it was for. */
    fun clearToken()
    fun clearAll()
}

/**
 * App-private SharedPreferences (not readable by other apps). The token is a 30-day learner JWT that
 * only reaches lesson progress, which is why plain private storage was chosen over adding the
 * security-crypto dependency.
 */
class PrefsTokenStore(private val prefs: SharedPreferences) : TokenStore {
    override fun token(): String? = prefs.getString(KEY_TOKEN, null)
    override fun learnerId(): String? = prefs.getString(KEY_LEARNER, null)

    override fun save(token: String, learnerId: String?) {
        val edit = prefs.edit()
        edit.putString(KEY_TOKEN, token)
        if (learnerId != null) edit.putString(KEY_LEARNER, learnerId)
        edit.commit()
    }

    override fun clearToken() { prefs.edit().remove(KEY_TOKEN).commit() }
    override fun clearAll() { prefs.edit().remove(KEY_TOKEN).remove(KEY_LEARNER).commit() }

    companion object {
        const val FILE = "lessons_auth"
        private const val KEY_TOKEN = "joined_token"
        private const val KEY_LEARNER = "joined_learner_id"
    }
}
