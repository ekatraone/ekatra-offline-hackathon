package org.ekatra.alfred.data

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {

    private val prefs = InMemoryPrefs()
    private val context = mockk<Context>().also {
        every { it.applicationContext } returns it
        every { it.getSharedPreferences("ekatra_settings", any()) } returns prefs
    }
    private val settings = AppSettings(context)

    @Test
    fun `clearAllExceptConsent removes cached settings but keeps consent_shown`() {
        settings.setAnalyticsConsent(true)
        settings.setFeedbackConsent(true)
        settings.setOnboardingComplete()
        settings.setSelectedModel("some-model")
        val anonId = settings.getAnonId()

        settings.clearAllExceptConsent()

        assertTrue(settings.isConsentShown())
        assertFalse(settings.hasAnalyticsConsent())
        assertFalse(settings.hasFeedbackConsent())
        assertFalse(settings.isOnboardingComplete())
        assertEquals(setOf("consent_shown"), prefs.all.keys)
        assertFalse(settings.getAnonId() == anonId)
    }

    @Test
    fun `clearAllExceptConsent does not create consent_shown when it was never set`() {
        settings.setOnboardingComplete()

        settings.clearAllExceptConsent()

        assertFalse(settings.isConsentShown())
        assertTrue(prefs.all.isEmpty())
    }
}

/** Minimal in-memory SharedPreferences: enough for AppSettings' reads and edits. */
private class InMemoryPrefs : SharedPreferences {
    private val data = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = data.toMutableMap()
    override fun getString(key: String, defValue: String?) = data[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String, defValue: Int) = data[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = data[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = data[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = data[key] as? Boolean ?: defValue
    override fun contains(key: String) = data.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clear = false

        private fun put(key: String, value: Any?) = apply { pending[key] = value; removals.remove(key) }

        override fun putString(key: String, value: String?) = put(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values)
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun remove(key: String) = apply { removals.add(key); pending.remove(key) }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) data.clear()
            removals.forEach { data.remove(it) }
            data.putAll(pending)
        }
    }
}
