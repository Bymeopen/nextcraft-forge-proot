package com.nextcraft.forge.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeleteRecursivelyNoFollowOrRootTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun removesTreeWithoutTouchingTheTargetOfASymlink() {
        val outside = tmp.newFolder("other-project").also { File(it, "keep.txt").writeText("keep") }
        val project = tmp.newFolder("project")
        File(project, "app/build").mkdirs()
        File(project, "app/build/out.apk").writeText("x")
        Files.createSymbolicLink(File(project, "link").toPath(), outside.toPath())

        // Everything is deletable by this uid, so the root fallback (which needs Android) is never reached.
        assertTrue(project.deleteRecursivelyNoFollowOrRoot())

        assertFalse(project.exists())
        assertEquals("keep", File(outside, "keep.txt").readText())
    }

    @Test
    fun missingDirectoryCountsAsDeleted() {
        assertTrue(File(tmp.root, "never-existed").deleteRecursivelyNoFollowOrRoot())
    }
}
