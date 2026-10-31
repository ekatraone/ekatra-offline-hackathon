package org.ekatra.alfred.lessons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenConfigTest {

    // ---- token precedence ----

    @Test
    fun `the stored token wins over the build-time token, which wins over none`() {
        val store = FakeTokenStore()
        val config = SyncConfig("http://x", "build-token", store)
        assertEquals("build-token", config.token)
        assertFalse(config.hasStoredToken)

        store.token = "joined-token"
        assertEquals("joined-token", config.token)
        assertTrue(config.hasStoredToken)

        store.token = null
        assertEquals("build-token", config.token)

        val none = SyncConfig("http://x", "", FakeTokenStore())
        assertEquals("", none.token)
        assertFalse(none.isConfigured)
    }

    @Test
    fun `a blank stored token is ignored`() {
        val config = SyncConfig("http://x", "build-token", FakeTokenStore(token = "  "))
        assertEquals("build-token", config.token)
        assertFalse(config.hasStoredToken)
    }

    @Test
    fun `needing a token means a server and no token at all`() {
        assertTrue(SyncConfig("http://x", "", FakeTokenStore()).needsToken)
        assertFalse("a build token means nothing is needed", SyncConfig("http://x", "build", FakeTokenStore()).needsToken)
        assertFalse("a stored token means nothing is needed", SyncConfig("http://x", "", FakeTokenStore("t")).needsToken)
        assertFalse("no server means nothing to exchange with", SyncConfig("", "", FakeTokenStore()).needsToken)
        assertFalse(SyncConfig("", "", FakeTokenStore()).isConfigured)
    }

    @Test
    fun `a config with no store behaves as before`() {
        val config = SyncConfig("http://x", "tok")
        assertTrue(config.isConfigured); assertFalse(config.hasStoredToken); assertEquals("tok", config.token)
    }

    @Test
    fun `saving, clearing and signing out change the config and announce it`() {
        val store = FakeTokenStore()
        val config = SyncConfig("http://x", "", store)
        val v0 = config.changes.value

        config.saveToken("t1", "learner-1")
        assertTrue(config.isConfigured); assertEquals("learner-1", config.learnerId)
        assertEquals(v0 + 1, config.changes.value)

        config.clearToken()   // expiry: the learner id is kept
        assertFalse(config.isConfigured); assertEquals("learner-1", config.learnerId)
        assertEquals(v0 + 2, config.changes.value)

        config.saveToken("t2", "learner-1")
        config.clearAll()     // sign out: both go
        assertNull(config.learnerId); assertFalse(config.isConfigured)
        assertEquals(v0 + 4, config.changes.value)
    }

    // ---- storage ----

    @Test
    fun `the prefs store saves, reads and clears the token and the learner id`() {
        val prefs = FakePrefs()
        val store = PrefsTokenStore(prefs)
        assertNull(store.token()); assertNull(store.learnerId())

        store.save("tok", "learner-9")
        assertEquals("tok", store.token()); assertEquals("learner-9", store.learnerId())
        assertEquals("a fresh store sees it too", "tok", PrefsTokenStore(prefs).token())

        store.clearToken()
        assertNull(store.token()); assertEquals("learner-9", store.learnerId())

        store.save("tok2", null)
        assertEquals("a save without an id keeps the earlier one", "learner-9", store.learnerId())

        store.clearAll()
        assertNull(store.token()); assertNull(store.learnerId())
    }

    // ---- phone numbers ----

    @Test
    fun `local numbers default to plus 91`() {
        assertEquals("+919876543210", normalizePhone("9876543210"))
        assertEquals("+919876543210", normalizePhone("98765 43210"))
        assertEquals("+919876543210", normalizePhone("09876543210"))
        assertEquals("+919876543210", normalizePhone("98765-43210"))
        assertEquals("+919876543210", normalizePhone("  (98765) 43210 "))
    }

    @Test
    fun `numbers with a country code keep it`() {
        assertEquals("+919876543210", normalizePhone("+91 98765 43210"))
        assertEquals("+919876543210", normalizePhone("919876543210"))
        assertEquals("+447700900123", normalizePhone("+44 7700 900123"))
        assertEquals("+447700900123", normalizePhone("00447700900123"))
        assertEquals("+14155550123", normalizePhone("+1 (415) 555-0123"))
    }

    @Test
    fun `things that are not phone numbers are refused`() {
        assertNull(normalizePhone(""))
        assertNull(normalizePhone("   "))
        assertNull(normalizePhone("abc"))
        assertNull(normalizePhone("98765x43210"))
        assertNull(normalizePhone("+0123456789"))      // country code cannot start with 0
        assertNull(normalizePhone("+1234567"))         // 7 digits is too short
        assertNull(normalizePhone("+1234567890123456")) // 16 digits is too long
        assertNull(normalizePhone("+"))
    }

    @Test
    fun `the length limits are 8 to 15 digits after the plus`() {
        assertEquals("+12345678", normalizePhone("+12345678"))
        assertEquals("+123456789012345", normalizePhone("+123456789012345"))
        assertNull(normalizePhone("+1234567"))
        assertNull(normalizePhone("+1234567890123456"))
    }
}
