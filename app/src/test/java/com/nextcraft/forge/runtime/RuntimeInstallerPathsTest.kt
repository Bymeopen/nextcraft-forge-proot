package com.nextcraft.forge.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Archive entries and their symlinks must never resolve outside the runtime root. */
class RuntimeInstallerPathsTest {
    private fun <T> expectFailure(block: () -> T) {
        try {
            block()
            fail("expected the path to be rejected")
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun safeChildAcceptsPlainRelativePathsOnly() {
        val root = Files.createTempDirectory("forge-root").toFile()
        try {
            val child = RuntimeInstaller.safeChild(root, "usr/bin/env")
            assertEquals(File(root.canonicalFile, "usr/bin/env"), child)
            assertEquals(File(root.canonicalFile, "etc/hosts"), RuntimeInstaller.safeChild(root, "etc\\hosts/"))
            for (bad in listOf("", "/etc/passwd", "../x", "a/../../x", "a//b", "./a", "a/./b", "a\u0000b")) {
                expectFailure { RuntimeInstaller.safeChild(root, bad) }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun safeChildFollowsSymlinksOnlyInsideTheRoot() {
        val root = Files.createTempDirectory("forge-root").toFile().canonicalFile
        val outside = Files.createTempDirectory("forge-outside").toFile().canonicalFile
        try {
            File(root, "target").mkdirs()
            // Link targets are read as the guest sees them: relative ones resolve from the link's directory.
            Files.createSymbolicLink(File(root, "inside").toPath(), Paths.get("target"))
            assertEquals(File(root, "target/file"), RuntimeInstaller.safeChild(root, "inside/file"))
            Files.createSymbolicLink(File(root, "escape").toPath(), Paths.get("../${outside.name}"))
            expectFailure { RuntimeInstaller.safeChild(root, "escape/file") }
            // An absolute target is a guest path, so it lands inside the rootfs rather than on the host.
            Files.createSymbolicLink(File(root, "abs").toPath(), Paths.get("/target"))
            assertEquals(File(root, "target/file"), RuntimeInstaller.safeChild(root, "abs/file"))
            Files.createSymbolicLink(File(root, "loop").toPath(), Paths.get("loop"))
            expectFailure { RuntimeInstaller.safeChild(root, "loop/file") }
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun symlinkTargetsAreResolvedAgainstTheRuntimeRoot() {
        val root = File("/tmp/forge-rootfs").toPath()
        val parent = root.resolve("usr/bin")
        // Absolute link targets are the guest's view: they resolve inside the rootfs, not on the host.
        assertEquals(root.resolve("bin/bash"), RuntimeInstaller.resolveSafeSymlinkTarget(root, parent, "/bin/bash"))
        assertEquals(root.resolve("usr/lib/x"), RuntimeInstaller.resolveSafeSymlinkTarget(root, parent, "../lib/x"))
        assertEquals(root, RuntimeInstaller.resolveSafeSymlinkTarget(root, parent, "/"))
        assertTrue(RuntimeInstaller.resolveSafeSymlinkTarget(root, parent, "..\\lib\\y").startsWith(root))
        for (bad in listOf("../../../etc/passwd", "", "a\u0000")) {
            expectFailure { RuntimeInstaller.resolveSafeSymlinkTarget(root, parent, bad) }
        }
    }
}
