package com.nextcraft.forge.runtime

import android.content.ComponentCallbacks2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("DEPRECATION")
class GradleDaemonReaperTest {
    @Test
    fun onlyUnderRealMemoryPressure() {
        assertFalse(GradleDaemonReaper.shouldStop(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, lowMemory = false))
        assertTrue(GradleDaemonReaper.shouldStop(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, lowMemory = false))
        assertTrue(GradleDaemonReaper.shouldStop(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, lowMemory = false))
        assertTrue(GradleDaemonReaper.shouldStop(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, lowMemory = true))
    }

    @Test
    fun scriptOnlyKillsDaemonsWithoutCpuProgress() {
        assertTrue("GradleDaemon" in GradleDaemonReaper.STOP_IDLE_SCRIPT)
        assertTrue("[ \"\$a\" = \"\$b\" ] && kill" in GradleDaemonReaper.STOP_IDLE_SCRIPT)
    }
}
