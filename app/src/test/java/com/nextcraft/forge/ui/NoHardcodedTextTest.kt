package com.nextcraft.forge.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ratchet against new hard-coded UI text. User-visible strings belong in res/values (and values-xx) strings.xml
 * (stringResource), so every screen can be translated. The remaining literals are technical
 * (paths, ports, placeholders with nested expressions); lower [BASELINE] when you remove one.
 */
class NoHardcodedTextTest {
    private val skipFiles = setOf(
        "Theme.kt", "MainViewModel.kt", "ModelLabels.kt",
        "AppLanguage.kt", "LocaleHelper.kt",
    )

    // Call sites that take user-visible text, followed by a quoted literal: Text("x"), contentDescription = "x", ...
    private val callSite =
        """(?:\bText\(\s*(?:text\s*=\s*)?|contentDescription\s*=\s*|onClickLabel\s*=\s*|placeholder\s*=\s*\{\s*Text\(\s*|label\s*=\s*\{\s*Text\(\s*)"""
    private val quoted = "\"((?:[^\"\\\\]|\\\\.)*)\""
    private val literal = Regex(callSite + quoted)

    @Test
    fun uiScreens_doNotGainHardcodedText() {
        val dir = File("src/main/java/com/nextcraft/forge/ui")
        assertTrue("UI source dir not found: ${dir.absolutePath}", dir.isDirectory)
        val offenders = dir.listFiles { f -> f.extension == "kt" && f.name !in skipFiles && !f.name.startsWith("MainViewModel") }!!
            .sortedBy { it.name }
            .flatMap { file ->
                literal.findAll(file.readText())
                    .map { it.groupValues[1] }
                    .filter { Regex("[A-Za-z]{3}").containsMatchIn(it) }
                    .map { "${file.name}: \"$it\"" }
            }
        assertTrue(
            "Hard-coded UI text count ${offenders.size} > $BASELINE. Move new strings to strings.xml:\n" +
                offenders.joinToString("\n"),
            offenders.size <= BASELINE,
        )
    }

    private companion object {
        const val BASELINE = 9
    }
}
