package com.nextcraft.forge.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShellPromptTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun anEmptyFileGetsJustTheBlock() {
        assertEquals(ShellPrompt.block + "\n", ShellPrompt.apply(""))
    }

    @Test
    fun appliesAtTheEndAndKeepsTheUsersOwnLines() {
        val result = ShellPrompt.apply("alias ll='ls -l'\nexport EDITOR=nano")
        assertTrue(result.startsWith("alias ll='ls -l'\nexport EDITOR=nano\n\n"))
        assertTrue(result.endsWith(ShellPrompt.block + "\n"))
    }

    @Test
    fun applyingTwiceChangesNothing() {
        val once = ShellPrompt.apply("export A=1\n")
        assertEquals(once, ShellPrompt.apply(once))
    }

    @Test
    fun anOldBlockIsReplacedAndTextAroundItSurvives() {
        val old = "before\n# >>> nextcraft prompt >>>\nPS1='old'\n# <<< nextcraft prompt <<<\nafter\n"
        val result = ShellPrompt.apply(old)
        assertFalse(result.contains("PS1='old'"))
        assertTrue(result.contains("before"))
        assertTrue(result.contains("after"))
        assertEquals(1, Regex("# >>> nextcraft prompt >>>").findAll(result).count())
        assertTrue(result.trimEnd().endsWith("# <<< nextcraft prompt <<<"))
    }

    private fun promptOf(term: String): String? {
        assumeTrue(File("/bin/bash").exists() || File("/usr/bin/bash").exists())
        val rc = tmp.newFile().apply { writeText(ShellPrompt.apply("PS1='plain> '\n")) }
        val process = ProcessBuilder("bash", "--rcfile", rc.absolutePath, "-i", "-c", "printf %s \"\$PS1\"")
            .redirectErrorStream(false)
            .also { it.environment()["TERM"] = term }
            .start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return out
    }

    @Test
    fun anInteractiveShellOnAColourTerminalGetsTheColouredPrompt() {
        val prompt = promptOf("xterm-256color")!!
        assertTrue(prompt, prompt.contains("\\[\\e[1;91m\\]\\u"))
        assertTrue(prompt, prompt.contains("\\[\\e[1;94m\\]\\w"))
        assertFalse(prompt.contains("plain>"))
    }

    @Test
    fun aDumbTerminalKeepsItsPlainPrompt() {
        assertEquals("plain> ", promptOf("dumb"))
    }
}
