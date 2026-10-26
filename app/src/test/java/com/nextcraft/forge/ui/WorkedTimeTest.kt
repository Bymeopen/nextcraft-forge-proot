package com.nextcraft.forge.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkedTimeTest {
    @Test
    fun formatsSecondsAndMinutesForTheLiveIndicator() {
        assertEquals("0s", formatWorked(0))
        assertEquals("59s", formatWorked(59_999))
        assertEquals("1m 4s", formatWorked(64_000))
        assertEquals("12m 0s", formatWorked(720_000))
    }
}
