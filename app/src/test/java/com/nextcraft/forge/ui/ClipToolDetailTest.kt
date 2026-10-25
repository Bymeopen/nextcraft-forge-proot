package com.nextcraft.forge.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipToolDetailTest {
    @Test
    fun shortOutputIsKept() {
        val text = "BUILD SUCCESSFUL in 4s"
        assertEquals(text, clipToolDetail(text))
    }

    @Test
    fun longOutputKeepsStartAndEnd() {
        val text = (1..20_000).joinToString("\n") { "line $it" }
        val clipped = clipToolDetail(text)
        assertTrue(clipped.length < 4_200)
        assertTrue(clipped.startsWith("line 1\n"))
        assertTrue(clipped.endsWith("line 20000"))
        assertTrue(clipped.contains("lines omitted"))
    }
}
