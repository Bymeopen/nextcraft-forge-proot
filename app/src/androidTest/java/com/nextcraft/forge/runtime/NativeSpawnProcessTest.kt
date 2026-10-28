package com.nextcraft.forge.runtime

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * On-device check of the spawn path (libforgespawn.so, or the ProcessBuilder fallback when the library is missing).
 * Needs a device or emulator: `gradle :app:connectedDebugAndroidTest`.
 */
class NativeSpawnProcessTest {
    private lateinit var workDir: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        workDir = File(context.cacheDir, "spawn-test").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
    }

    private fun spawn(script: String, pty: Boolean): Pair<NativeSpawnProcess, File> {
        val out = File(workDir, if (pty) "pty.out" else "pipe.out")
        val process = NativeSpawnProcess.start(
            argv = listOf("/system/bin/sh", "-c", script),
            environment = emptyMap(),
            cwd = workDir.absolutePath,
            outputFile = out,
            pseudoTerminal = pty,
        )
        return process to out
    }

    @Test
    fun capturesOutputAndExitCode() {
        val (process, out) = spawn("echo forge-ok; exit 3", pty = false)
        assertEquals(3, process.waitFor())
        assertTrue(out.readText().contains("forge-ok"))
    }

    @Test
    fun ptyModeCapturesOutput() {
        val (process, out) = spawn("echo pty-ok", pty = true)
        assertEquals(0, process.waitFor())
        assertTrue(out.readText().contains("pty-ok"))
    }

    @Test
    fun destroyStopsLongRunningProcess() {
        val (process, _) = spawn("sleep 60", pty = false)
        assertTrue(process.isAlive)
        process.destroyForcibly()
        process.waitFor()
        assertFalse(process.isAlive)
    }
}
