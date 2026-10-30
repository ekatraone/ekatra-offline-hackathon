package org.ekatra.alfred.persona

import org.junit.Assert.assertEquals
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
}
