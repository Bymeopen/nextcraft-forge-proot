package com.nextcraft.forge.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcStatusTest {
    private val status = "Name:\tcom.nextcraft.forge\nState:\tS (sleeping)\nThreads:\t48\nVmRSS:\t  210332 kB\n"

    @Test
    fun readsAnIntegerValue() {
        assertEquals(48, parseProcStatus(status, "Threads"))
        assertEquals(210332, parseProcStatus(status, "VmRSS"))
    }

    @Test
    fun missingOrNonNumericKeyIsNull() {
        assertNull(parseProcStatus(status, "Nope"))
        assertNull(parseProcStatus(status, "State"))
    }

    @Test
    fun aKeyThatOnlyPrefixesAnotherIsNotMatched() {
        assertNull(parseProcStatus("ThreadsX:\t9\n", "Threads"))
    }
}
