package org.ekatra.alfred.lessons

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What keeps a lessons server address and a demo token out of release builds, checked from the debug unit
 * tests too. The release variant itself is asserted by `ReleaseBuildConfigTest` (src/testRelease), which runs
 * in `testMayaReleaseUnitTest` / `testAsmaaReleaseUnitTest`.
 */
class ReleaseHardeningTest {

    private val gradle = File("build.gradle.kts").readText()

    private fun block(name: String): String {
        val start = gradle.indexOf("        $name {")
        require(start >= 0) { "no $name build type" }
        return gradle.substring(start, gradle.indexOf("\n        }", start))
    }

    // ---- the 📚 icon ----

    @Test
    fun `a release build with no lessons server hides the Lessons icon`() {
        assertFalse(lessonsEntryVisible("maya", isDebug = false, syncBaseUrl = ""))
        assertFalse(lessonsEntryVisible("maya", isDebug = false, syncBaseUrl = "   "))
    }

    @Test
    fun `a release build with a lessons server shows it, and debug always does on Maya`() {
        assertTrue(lessonsEntryVisible("maya", isDebug = false, syncBaseUrl = "https://x"))
        assertTrue(lessonsEntryVisible("maya", isDebug = true, syncBaseUrl = ""))
        assertTrue(lessonsEntryVisible("maya", isDebug = true, syncBaseUrl = "http://127.0.0.1:3001"))
    }

    @Test
    fun `Asmaa never shows it`() {
        for (debug in listOf(true, false)) for (url in listOf("", "https://x")) {
            assertFalse(lessonsEntryVisible("asmaa", isDebug = debug, syncBaseUrl = url))
        }
    }

    @Test
    fun `the chat screen uses that rule`() {
        val chat = File("src/main/java/org/ekatra/alfred/NativeChatActivity.kt").readText()
        assertTrue(chat.contains("lessonsEntryVisible(ActivePersona.key, BuildConfig.DEBUG, BuildConfig.SYNC_BASE_URL)"))
    }

    // ---- the build configuration ----

    @Test
    fun `the release build type hard-codes a blank server address and token`() {
        val release = block("release")
        assertTrue(release.contains("""buildConfigField("String", "SYNC_BASE_URL", "\"\"")"""))
        assertTrue(release.contains("""buildConfigField("String", "DEMO_LEARNER_TOKEN", "\"\"")"""))
        assertFalse("release must not read local.properties", release.contains("localProps"))
    }

    @Test
    fun `only the debug build type reads local properties`() {
        val debug = block("debug")
        assertTrue(debug.contains("""localProps.getProperty("SYNC_BASE_URL")"""))
        assertTrue(debug.contains("""localProps.getProperty("DEMO_LEARNER_TOKEN")"""))
    }

    @Test
    fun `local properties is not tracked, so a token cannot be committed with it`() {
        val ignore = File("../.gitignore").takeIf { it.exists() }?.readText().orEmpty()
        assertTrue(ignore.lines().any { it.trim() == "local.properties" || it.trim() == "/local.properties" })
    }
}
