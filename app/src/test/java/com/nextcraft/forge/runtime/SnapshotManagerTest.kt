package com.nextcraft.forge.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Snapshot create/restore must be symmetric: restore may only delete what a snapshot could have saved. */
class SnapshotManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(root: File, path: String, text: String = path) {
        File(root, path).apply { parentFile.mkdirs() }.writeText(text)
    }

    @Test
    fun restoreKeepsExcludedFoldersAtEveryDepth() {
        val workspace = tmp.newFolder("ws")
        val manager = SnapshotManager(tmp.newFolder("files"))
        write(workspace, "src/Main.kt", "v1")
        write(workspace, "scripts/build/keep.sh", "keep")
        write(workspace, "packages/a/node_modules/dep/index.js", "dep")
        write(workspace, "build/out.txt", "top-level build")
        val snapshot = manager.create("p1", workspace, "first")

        write(workspace, "src/Main.kt", "v2")
        write(workspace, "src/New.kt", "added after the snapshot")

        manager.restore("p1", workspace, snapshot)

        assertEquals("v1", File(workspace, "src/Main.kt").readText())
        assertFalse("files added after the snapshot are removed", File(workspace, "src/New.kt").exists())
        assertEquals("keep", File(workspace, "scripts/build/keep.sh").readText())
        assertTrue(File(workspace, "packages/a/node_modules/dep/index.js").isFile)
        assertTrue(File(workspace, "build/out.txt").isFile)
    }

    @Test
    fun restoreBringsBackDeletedFilesAndRemovesEmptiedFolders() {
        val workspace = tmp.newFolder("ws2")
        val manager = SnapshotManager(tmp.newFolder("files2"))
        write(workspace, "docs/guide.md", "guide")
        val snapshot = manager.create("p2", workspace, "docs")

        File(workspace, "docs/guide.md").delete()
        write(workspace, "extra/only-now.txt", "x")

        manager.restore("p2", workspace, snapshot)

        assertEquals("guide", File(workspace, "docs/guide.md").readText())
        assertFalse("a folder created after the snapshot disappears with its files", File(workspace, "extra").exists())
    }

    @Test
    fun restoreLeavesSymbolicLinksAndTheirTargetsAlone() {
        val workspace = tmp.newFolder("ws3")
        val outside = tmp.newFolder("outside")
        File(outside, "secret.txt").writeText("outside the project")
        val manager = SnapshotManager(tmp.newFolder("files3"))
        write(workspace, "a.txt", "a")
        java.nio.file.Files.createSymbolicLink(File(workspace, "link").toPath(), outside.toPath())

        val snapshot = manager.create("p3", workspace, "with-link")
        manager.restore("p3", workspace, snapshot)

        assertTrue("the target of a link is never touched", File(outside, "secret.txt").isFile)
        assertTrue("the link itself is not part of the snapshot, so it stays", java.nio.file.Files.isSymbolicLink(File(workspace, "link").toPath()))
        assertEquals("a", File(workspace, "a.txt").readText())
    }

    @Test
    fun snapshotDoesNotArchiveThroughASymbolicLinkedFolder() {
        val workspace = tmp.newFolder("ws4")
        val outside = tmp.newFolder("outside4")
        File(outside, "host-file.txt").writeText("not project data")
        val manager = SnapshotManager(tmp.newFolder("files4"))
        write(workspace, "a.txt", "a")
        java.nio.file.Files.createSymbolicLink(File(workspace, "loop").toPath(), outside.toPath())

        val snapshot = manager.create("p4", workspace, "no-follow")
        val names = java.util.zip.ZipInputStream(snapshot.file.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name }.toList()
        }

        assertEquals(listOf("a.txt"), names)
    }
}
