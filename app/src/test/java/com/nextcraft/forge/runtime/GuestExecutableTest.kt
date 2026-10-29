package com.nextcraft.forge.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuestExecutableTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun rootfs(): File = tmp.newFolder("rootfs").also {
        File(it, "usr/local/bin").mkdirs()
        File(it, "usr/local/lib/claude-code/node_modules/.bin").mkdirs()
    }

    private fun script(root: File, rel: String, executable: Boolean = true): File =
        File(root, rel).apply {
            parentFile?.mkdirs()
            writeText("#!/bin/sh\n")
            setExecutable(executable)
        }

    private fun link(root: File, rel: String, target: String) {
        val path = File(root, rel).toPath()
        path.parent?.let { Files.createDirectories(it) }
        Files.createSymbolicLink(path, java.nio.file.Paths.get(target))
    }

    @Test
    fun absoluteGuestSymlinkResolvesInsideTheRootfsNotOnTheHost() {
        val root = rootfs()
        script(root, "usr/local/lib/claude-code/node_modules/.bin/claude")
        link(root, "usr/local/bin/claude", "/usr/local/lib/claude-code/node_modules/.bin/claude")

        // The host view of the same link dangles: that is what made an installed agent look missing.
        assertFalse(File(root, "usr/local/bin/claude").canExecute())
        assertTrue(isGuestExecutable(root, "/usr/local/bin/claude"))
    }

    @Test
    fun relativeSymlinkAndChainOfLinksResolve() {
        val root = rootfs()
        script(root, "usr/local/lib/claude-code/node_modules/@anthropic-ai/claude-code/cli.js")
        link(root, "usr/local/lib/claude-code/node_modules/.bin/claude", "../@anthropic-ai/claude-code/cli.js")
        link(root, "usr/local/bin/claude", "../lib/claude-code/node_modules/.bin/claude")

        assertTrue(isGuestExecutable(root, "/usr/local/bin/claude"))
    }

    @Test
    fun plainExecutableFileCounts() {
        val root = rootfs()
        script(root, "usr/local/bin/claude")
        assertTrue(isGuestExecutable(root, "/usr/local/bin/claude"))
    }

    @Test
    fun missingNonExecutableOrDanglingTargetDoesNotCount() {
        val root = rootfs()
        assertFalse(isGuestExecutable(root, "/usr/local/bin/claude"))

        script(root, "usr/local/lib/claude-code/node_modules/.bin/claude", executable = false)
        link(root, "usr/local/bin/claude", "/usr/local/lib/claude-code/node_modules/.bin/claude")
        assertFalse(isGuestExecutable(root, "/usr/local/bin/claude"))

        link(root, "usr/local/bin/gone", "/usr/local/lib/does-not-exist")
        assertFalse(isGuestExecutable(root, "/usr/local/bin/gone"))
    }

    @Test
    fun symlinkLoopOrOneThatLeavesTheRootfsDoesNotCount() {
        val root = rootfs()
        link(root, "usr/local/bin/a", "b")
        link(root, "usr/local/bin/b", "a")
        assertFalse(isGuestExecutable(root, "/usr/local/bin/a"))

        // An executable that exists on the host but outside the rootfs must not make the guest path count.
        val outside = script(tmp.root, "outside-tool")
        link(root, "usr/local/bin/escape", "../../../../${outside.name}")
        assertFalse(isGuestExecutable(root, "/usr/local/bin/escape"))
    }
}
