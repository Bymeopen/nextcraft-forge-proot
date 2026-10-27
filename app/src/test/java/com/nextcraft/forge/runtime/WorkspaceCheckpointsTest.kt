package com.nextcraft.forge.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceCheckpointsTest {
    @get:Rule val temp = TemporaryFolder()

    private fun workspace(): File = temp.newFolder("ws").also {
        File(it, "src").mkdirs()
        File(it, "src/Main.kt").writeText("fun main() {}")
    }

    @Test
    fun kotlinDaemonStateIsNotTracked() {
        val checkpoints = WorkspaceCheckpoints(temp.newFolder("files"))
        assertTrue(checkpoints.isInternalRuntimePath(".kotlin/sessions/kotlin-compiler-1.salive"))
        assertFalse(checkpoints.isInternalRuntimePath("src/Main.kt"))
    }

    @Test
    fun unreadableFileDoesNotAbortSnapshotOrCheckpoint() {
        val ws = workspace()
        // Same shape as the failing case: a root-owned file the app (another uid) cannot open, outside any ignored folder.
        val secret = File(ws, "src/locked.bin").apply { writeText("x"); setReadable(false, false) }
        // Running as root (some sandboxes) can still read it, so the case cannot be reproduced there.
        assumeFalse(secret.canRead())
        val checkpoints = WorkspaceCheckpoints(temp.newFolder("files"))
        val snapshot = checkpoints.snapshot(ws)
        assertEquals(setOf("src/Main.kt"), snapshot.keys)
        checkpoints.createCheckpoint("p1", ws)
    }

    @Test
    fun buildChangeDetailsPrunesAlreadySyncedPaths() {
        val ws = workspace()
        val checkpoints = WorkspaceCheckpoints(temp.newFolder("files"))
        checkpoints.createCheckpoint("p1", ws)
        // Record a pending path whose live file already matches the checkpoint baseline.
        checkpoints.saveChangedPaths("p1", listOf("src/Main.kt"))
        assertEquals(listOf("src/Main.kt"), checkpoints.readChangedPaths("p1"))
        val details = checkpoints.buildChangeDetails("p1", ws, checkpoints.readChangedPaths("p1"))
        assertTrue(details.isEmpty())
        assertTrue(checkpoints.readChangedPaths("p1").isEmpty())
    }
}
