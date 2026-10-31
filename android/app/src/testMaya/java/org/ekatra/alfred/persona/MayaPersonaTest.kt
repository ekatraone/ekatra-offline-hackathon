package org.ekatra.alfred.persona

import org.ekatra.alfred.data.AvailableModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MayaPersonaTest {

    @Test
    fun `blank remote prompt uses the default`() {
        assertEquals(ActivePersona.defaultSystemPrompt, ActivePersona.resolveSystemPrompt(""))
    }

    @Test
    fun `remote prompt for Maya is used as is`() {
        val remote = "You are Maya, a friendly tutor."
        assertEquals(remote, ActivePersona.resolveSystemPrompt(remote))
    }

    @Test
    fun `remote prompt written for Asmaa is rejected`() {
        assertEquals(ActivePersona.defaultSystemPrompt, ActivePersona.resolveSystemPrompt("You are Asmaa."))
        assertEquals(ActivePersona.defaultSystemPrompt, ActivePersona.resolveSystemPrompt("أنت أسماء، معلمة لغة عربية."))
    }

    @Test
    fun `llama 3_2 1b is the first preference with a pinned url and hash`() {
        assertEquals("llama-3.2-1b-instruct", ActivePersona.modelPreference.first())
        val m = AvailableModels.getModelById("llama-3.2-1b-instruct")
        assertNotNull(m)
        assertEquals("6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83", m!!.expectedSha256)
        assertTrue("revision must be pinned, not main", "/resolve/main/" !in m.url)
        assertTrue(m.url.contains("/resolve/067b946cf014b7c697f3654f621d577a3e3afd1c/"))
    }
}
