package com.nextcraft.forge.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTextTest {
    @Test
    fun knownInstallerSentences_matchWithTheirArguments() {
        assertEquals(listOf("DeepSeek"), RuntimeText.match("Downloading DeepSeek bundle…")!!.second)
        assertEquals(listOf("Node.js 24.1.0"), RuntimeText.match("Downloading Node.js 24.1.0…")!!.second)
        assertEquals(listOf("3,6 / 26,8 MB"), RuntimeText.match("Downloading: 3,6 / 26,8 MB")!!.second)
        assertEquals(listOf("Python"), RuntimeText.match("Python stack is ready")!!.second)
        assertEquals(listOf("Claude Code"), RuntimeText.match("Claude Code is ready")!!.second)
        assertEquals(listOf("PHP"), RuntimeText.match("Installed PHP")!!.second)
        assertNotNull(RuntimeText.match("✓ Setup completed successfully"))
    }

    @Test
    fun specificRulesWinOverGeneralOnes() {
        val bundle = RuntimeText.match("Downloading X bundle…")!!.first
        val plain = RuntimeText.match("Downloading X…")!!.first
        val stack = RuntimeText.match("Python stack is ready")!!.first
        val ready = RuntimeText.match("Python is ready")!!.first
        assertTrue(bundle != plain)
        assertTrue(stack != ready)
        assertTrue(RuntimeText.match("Installed X runtime bundle")!!.first != RuntimeText.match("Installed X")!!.first)
    }

    @Test
    fun unknownTextIsLeftAlone() {
        assertNull(RuntimeText.match("apt: 12 packages upgraded"))
        assertNull(RuntimeText.match(""))
    }
}
