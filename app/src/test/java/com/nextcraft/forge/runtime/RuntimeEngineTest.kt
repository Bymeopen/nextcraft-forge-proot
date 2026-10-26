package com.nextcraft.forge.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Engine selection (ADR-007) is pure logic over two probes; the probes themselves need a device. */
class RuntimeEngineTest {
    @After
    fun reset() = RuntimeEngines.overrideForTests(null)

    @Test
    fun auto_prefersRootAndFallsBackToProot() {
        assertEquals(RuntimeEngine.CHROOT, RuntimeEngines.resolve(RuntimeEngineChoice.AUTO, rootGranted = true, prootAvailable = true))
        assertEquals(RuntimeEngine.PROOT, RuntimeEngines.resolve(RuntimeEngineChoice.AUTO, rootGranted = false, prootAvailable = true))
        assertNull(RuntimeEngines.resolve(RuntimeEngineChoice.AUTO, rootGranted = false, prootAvailable = false))
    }

    @Test
    fun forcedChoices_neverSubstituteTheOtherEngine() {
        assertNull(RuntimeEngines.resolve(RuntimeEngineChoice.CHROOT, rootGranted = false, prootAvailable = true))
        assertEquals(RuntimeEngine.CHROOT, RuntimeEngines.resolve(RuntimeEngineChoice.CHROOT, rootGranted = true, prootAvailable = true))
        assertEquals(RuntimeEngine.PROOT, RuntimeEngines.resolve(RuntimeEngineChoice.PROOT, rootGranted = true, prootAvailable = true))
        assertNull(RuntimeEngines.resolve(RuntimeEngineChoice.PROOT, rootGranted = true, prootAvailable = false))
    }

    @Test
    fun launchArgv_usesSuForChrootAndPlainShellForProot() {
        val command = ". '/x/.env'; rm -f '/x/.env'; exec '/x/chroot-exec.sh'"
        assertEquals(listOf("su", "-mm", "-c", command), RuntimeEngines.launchArgv(command, RuntimeEngine.CHROOT))
        assertEquals(listOf("/system/bin/sh", "-c", command), RuntimeEngines.launchArgv(command, RuntimeEngine.PROOT))
    }

    @Test
    fun current_defaultsToChrootUntilDetected_andFollowsOverride() {
        assertEquals(RuntimeEngine.CHROOT, RuntimeEngines.current())
        assertTrue(RuntimeEngines.isRootEngine())
        RuntimeEngines.overrideForTests(RuntimeEngine.PROOT)
        assertEquals(RuntimeEngine.PROOT, RuntimeEngines.current())
        assertEquals("/system/bin/sh", RuntimeEngines.launchArgv("true").first())
    }

    @Test
    fun storedChoice_fallsBackToAuto() {
        assertEquals(RuntimeEngineChoice.AUTO, RuntimeEngineChoice.fromStored(null))
        assertEquals(RuntimeEngineChoice.AUTO, RuntimeEngineChoice.fromStored("bogus"))
        assertEquals(RuntimeEngineChoice.PROOT, RuntimeEngineChoice.fromStored("proot"))
    }
}
