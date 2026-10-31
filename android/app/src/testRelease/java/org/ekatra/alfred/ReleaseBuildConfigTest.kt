package org.ekatra.alfred

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Runs only in the release variant (`testMayaReleaseUnitTest`, `testAsmaaReleaseUnitTest`). A release APK must
 * carry no lessons server address and no learner token: the demo token is a credential and the address is how
 * the app would reach a backend it was never meant to.
 */
class ReleaseBuildConfigTest {
    @Test
    fun `this is the release variant`() {
        assertFalse(BuildConfig.DEBUG)
    }

    @Test
    fun `a release build has a blank lessons server address`() {
        assertEquals("", BuildConfig.SYNC_BASE_URL)
    }

    @Test
    fun `a release build has a blank demo learner token`() {
        assertEquals("", BuildConfig.DEMO_LEARNER_TOKEN)
    }
}
