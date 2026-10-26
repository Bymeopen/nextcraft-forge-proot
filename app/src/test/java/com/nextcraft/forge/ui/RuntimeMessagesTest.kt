package com.nextcraft.forge.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** The controllers whose messages reach the screen must hand out UiText, never an English literal. */
class RuntimeMessagesTest {
    private val controllers = listOf("ClaudeAuthController", "AntigravityAuthController", "SnapshotsController", "CiCdController")
    private val literal = Regex("""message\s*=\s*"|\.message(\?\.take\(\d+\))?\s*\?:\s*"|\{\s*"[A-Z][^"]*"\s*\}""")

    @Test
    fun screenFacingControllers_haveNoEnglishLiterals() {
        controllers.forEach { name ->
            val file = File("src/main/java/com/nextcraft/forge/runtime/$name.kt")
            assertTrue("missing ${file.absolutePath}", file.isFile)
            val hits = file.readLines().withIndex().filter { literal.containsMatchIn(it.value) }
            assertTrue("$name: use UiText.res(R.string...) at " + hits.joinToString { "line ${it.index + 1}" }, hits.isEmpty())
        }
    }
}
