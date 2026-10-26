package com.nextcraft.forge.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the parts of the terminal setup that fail only at runtime or in release builds. */
class TerminalWiringTest {
    private fun read(path: String) = File(path).also { assertTrue("missing ${it.absolutePath}", it.isFile) }.readText()

    @Test
    fun jitpack_isRestrictedToTheTermuxGroup() {
        val settings = read("../settings.gradle.kts")
        val block = settings.substringAfter("https://jitpack.io", missingDelimiterValue = "")
        assertTrue("JitPack must be declared", block.isNotEmpty())
        assertTrue(
            "JitPack must be limited with includeGroup so no other dependency resolves from it",
            Regex("""includeGroup\("com\.github\.termux\.termux-app"\)""").containsMatchIn(block),
        )
    }

    @Test
    fun termuxLibraries_arePinnedToAnExactVersion() {
        val build = read("build.gradle.kts")
        val deps = Regex("""com\.github\.termux\.termux-app:[a-z-]+:([^"]+)""").findAll(build).map { it.groupValues[1] }.toList()
        assertTrue("terminal-emulator and terminal-view expected", deps.size == 2)
        deps.forEach { assertTrue("pin an exact tag, not '$it'", Regex("""v\d+\.\d+\.\d+""").matches(it)) }
    }

    @Test
    fun releaseBuild_keepsTheTermuxJniClasses() {
        assertTrue(
            "libtermux.so resolves com.termux.terminal.JNI by name; R8 must keep it",
            "-keep class com.termux.** { *; }" in read("proguard-rules.pro"),
        )
    }

    @Test
    fun terminalKeys_haveSpokenLabels() {
        val screen = read("src/main/java/com/nextcraft/forge/ui/TerminalScreen.kt")
        val keys = Regex("""TerminalKey\("([^"]+)", stringResource""").findAll(screen).count()
        assertTrue("every named key must pass a spoken description (found $keys)", keys >= 13)
    }
}
