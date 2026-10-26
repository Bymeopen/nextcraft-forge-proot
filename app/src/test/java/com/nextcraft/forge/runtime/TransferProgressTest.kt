package com.nextcraft.forge.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferProgressTest {
    @Test
    fun parsesStepLines() {
        val step = InstallProgress.parseStep("##STEP 2/4 Memasang capstone dan pyelftools")!!
        assertEquals(2, step.n)
        assertEquals(4, step.total)
        assertEquals("Memasang capstone dan pyelftools", step.label)
        assertEquals(25, step.percentBefore)
        assertNull(InstallProgress.parseStep("Get:1 http://archive.ubuntu.com"))
    }

    @Test
    fun consumeHidesStepLinesAndCarriesPartialLines() {
        val (shown, step, rest) = InstallProgress.consume("", "hello\n##STEP 1/4 Satu\nworld\n##STEP 2/")
        assertEquals("hello\nworld\n", shown)
        assertEquals(1, step!!.n)
        assertEquals("##STEP 2/", rest)
        val (shown2, step2, rest2) = InstallProgress.consume(rest, "4 Dua\nlagi")
        assertEquals("", shown2)
        assertEquals(2, step2!!.n)
        assertEquals("lagi", rest2)
    }

    @Test
    fun speedIsBytesOverTheWindow() {
        val smoother = SpeedSmoother(window = 2)
        assertEquals(0.0, smoother.add(0, 0), 0.0)
        assertEquals(1000.0, smoother.add(1000, 1000), 0.001)
        assertEquals(1500.0, smoother.add(3000, 2000), 0.001)
        // Window of 2: the oldest sample (t=0) is dropped after the next one.
        assertEquals(3500.0, smoother.add(8000, 3000), 0.001)
    }
}
