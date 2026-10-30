package org.ekatra.alfred.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsmaaPersonaTest {

    @Test
    fun `blank remote prompt uses the default`() {
        assertEquals(ActivePersona.defaultSystemPrompt, ActivePersona.resolveSystemPrompt(""))
    }

    @Test
    fun `remote prompt written for Maya is rejected`() {
        assertEquals(ActivePersona.defaultSystemPrompt, ActivePersona.resolveSystemPrompt("You are Maya, a tutor."))
    }

    @Test
    fun `remote prompt for Asmaa keeps it and appends the guards`() {
        val remote = "أنت أسماء، معلمة لغة عربية."
        val resolved = ActivePersona.resolveSystemPrompt(remote)
        assertTrue(resolved.startsWith(remote))
        assertTrue(resolved.length > remote.length)
    }
}
