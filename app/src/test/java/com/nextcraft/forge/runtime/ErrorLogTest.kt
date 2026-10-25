package com.nextcraft.forge.runtime

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ErrorLogTest {
    @Before
    fun reset() = ErrorLog.clear()

    @Test
    fun recordsTheTagAndMessage() {
        ErrorLog.swallowed("Installer", java.io.IOException("disk full"), now = 1_000)
        val e = ErrorLog.recent().single()
        assertEquals("Installer", e.tag)
        assertEquals("IOException: disk full", e.message)
    }

    @Test
    fun theSameErrorInARowIsCountedNotRepeated() {
        repeat(5) { ErrorLog.swallowed("Loop", IllegalStateException("again"), now = 2_000L + it) }
        val e = ErrorLog.recent().single()
        assertEquals(5, e.count)
        assertEquals(2_004L, e.atMillis)
    }

    @Test
    fun cancellationAndInterruptionAreNotErrors() {
        ErrorLog.swallowed("X", CancellationException("scope ended"))
        ErrorLog.swallowed("X", InterruptedException())
        assertTrue(ErrorLog.recent().isEmpty())
    }

    @Test
    fun theLogIsBounded_andNewestComesFirstInText() {
        repeat(200) { ErrorLog.swallowed("T$it", RuntimeException("e$it"), now = it.toLong()) }
        assertEquals(60, ErrorLog.recent().size)
        assertTrue(ErrorLog.asText().lines().first().contains("T199"))
    }

    @Test
    fun aHugeMessageIsTruncated() {
        ErrorLog.swallowed("Big", RuntimeException("x".repeat(5_000)))
        assertTrue(ErrorLog.recent().single().message.length <= 240)
    }
}
