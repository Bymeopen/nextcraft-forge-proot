package com.nextcraft.forge.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestProcessTest {
    @Test
    fun pidFileSharesTheLaunchId() {
        val env = File("/data/user/0/app/files/runtime/env/.env-1234-abcd.sh")
        assertEquals(File("/data/user/0/app/files/runtime/pids/1234-abcd.pid"), GuestProcess.pidFileFor(env))
    }

    @Test
    fun recordsTheShellPidBeforeExec() {
        val cmd = GuestProcess.recordPidCommand(File("/x/pids/a.pid"))
        assertTrue(cmd.startsWith("echo \$\$ > '/x/pids/a.pid'"))
    }
}
