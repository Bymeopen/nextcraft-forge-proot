package com.nextcraft.forge.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** The code viewer's colours must stay readable (>= 4.5:1) on the terminal surface and on added/removed row tints. */
class CodeContrastTest {
    private fun ratio(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble() + 0.05
        val l2 = b.luminance().toDouble() + 0.05
        return if (l1 > l2) l1 / l2 else l2 / l1
    }

    @Test
    fun codeColours_areReadableOnEveryRowBackground() {
        val modes = listOf(
            "dark" to resolveThemeTokens("dark", systemDark = false),
            "glass" to resolveThemeTokens("glass", systemDark = false),
            "white" to resolveThemeTokens("white", systemDark = false),
            "system-dark" to resolveThemeTokens("system", systemDark = true),
            "system-light" to resolveThemeTokens("system", systemDark = false),
        )
        val failures = mutableListOf<String>()
        modes.forEach { (mode, pair) ->
            val tokens = pair.second
            val base = tokens.terminalBackground
            val rows = mapOf(
                "context" to base,
                "added" to tokens.diffAddedBackground.compositeOver(base),
                "removed" to tokens.diffRemovedBackground.compositeOver(base),
            )
            // Everything drawn as code text: plain, each token kind, and the +/- signs.
            val kinds = CodeTokenKind.values().associate { it.name to (codeSpanStyle(it, tokens).color) }
            val text = kinds + mapOf(
                "plain" to tokens.terminalText,
                "addedSign" to tokens.diffAddedText,
                "removedSign" to tokens.diffRemovedText,
            )
            rows.forEach { (rowName, background) ->
                text.forEach { (name, color) ->
                    val effective = color.compositeOver(background)
                    val r = ratio(effective, background)
                    if (r < 4.5) failures += "$mode: $name on $rowName row = ${"%.2f".format(r)}"
                }
            }
        }
        assertTrue("code text below 4.5:1:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
