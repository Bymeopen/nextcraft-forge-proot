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

    // Replaces a tracked file by a symlink to a file outside the workspace, the way a build or `ln -s` would.
    private fun replaceByOutsideSymlink(ws: File): File {
        val outside = temp.newFile("outside.txt").apply { writeText("outside") }
        File(ws, "src/Main.kt").delete()
        java.nio.file.Files.createSymbolicLink(File(ws, "src/Main.kt").toPath(), outside.toPath())
        return outside
    }

    @Test
    fun symlinkReplacedPathDoesNotAbortChangeDetailsOrUndo() {
        val ws = workspace()
        File(ws, "src/Other.kt").writeText("val a = 1")
        val checkpoints = WorkspaceCheckpoints(temp.newFolder("files"))
        checkpoints.createCheckpoint("p1", ws)
        val outside = replaceByOutsideSymlink(ws)
        File(ws, "src/Other.kt").writeText("val a = 2")
        checkpoints.saveChangedPaths("p1", listOf("src/Main.kt", "src/Other.kt"))

        // One unsafe path used to make the whole list throw, which failed a finished agent turn.
        val details = checkpoints.buildChangeDetails("p1", ws, checkpoints.readChangedPaths("p1"))
        assertEquals(setOf("src/Main.kt", "src/Other.kt"), details.map { it.path }.toSet())

        // Undo restores the safe path, skips the symlinked one and never writes through the link.
        val restored = checkpoints.revertLatestTurn("p1", ws)
        assertEquals(listOf("src/Other.kt"), restored)
        assertEquals("val a = 1", File(ws, "src/Other.kt").readText())
        assertEquals("outside", outside.readText())
    }

    @Test
    fun revertAndAcceptOfASymlinkedPathReturnInsteadOfThrowing() {
        val ws = workspace()
        val checkpoints = WorkspaceCheckpoints(temp.newFolder("files"))
        checkpoints.createCheckpoint("p1", ws)
        val outside = replaceByOutsideSymlink(ws)
        checkpoints.saveChangedPaths("p1", listOf("src/Main.kt"))

        assertFalse(checkpoints.revertFile("p1", ws, "src/Main.kt"))
        assertEquals("outside", outside.readText())

        checkpoints.saveChangedPaths("p1", listOf("src/Main.kt"))
        assertTrue(checkpoints.acceptFile("p1", ws, "src/Main.kt"))
        assertTrue(checkpoints.readChangedPaths("p1").isEmpty())
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
