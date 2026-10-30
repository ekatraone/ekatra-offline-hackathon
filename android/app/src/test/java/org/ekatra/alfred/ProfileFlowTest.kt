package org.ekatra.alfred

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileFlowTest {

    @Test
    fun `first launch, not signed in - consent first, then the sign-in screen`() {
        assertEquals(ProfileAction.ASK_CONSENT, profileAction(needsConsent = true, isAuthenticated = false, isEditMode = false))
        assertEquals(ProfileAction.SHOW_SIGN_IN, profileAction(needsConsent = false, isAuthenticated = false, isEditMode = false))
    }

    @Test
    fun `signed-in upgrader with consent absent sees consent before model download`() {
        assertEquals(ProfileAction.ASK_CONSENT, profileAction(needsConsent = true, isAuthenticated = true, isEditMode = false))
        assertEquals(ProfileAction.GO_ONWARD, profileAction(needsConsent = false, isAuthenticated = true, isEditMode = false))
    }

    @Test
    fun `signed in with consent present goes straight on`() {
        assertEquals(ProfileAction.GO_ONWARD, profileAction(needsConsent = false, isAuthenticated = true, isEditMode = false))
    }

    @Test
    fun `edit mode has no consent gate and no navigation`() {
        for (needsConsent in listOf(true, false)) {
            for (authenticated in listOf(true, false)) {
                assertEquals(ProfileAction.STAY, profileAction(needsConsent, authenticated, isEditMode = true))
            }
        }
    }
}
