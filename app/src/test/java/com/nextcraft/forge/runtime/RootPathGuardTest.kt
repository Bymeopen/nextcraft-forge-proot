package com.nextcraft.forge.runtime

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Root commands must stay inside the app's data, and quoting must keep any file name one literal word. */
class RootPathGuardTest {
    private val pkg = "com.nextcraft.forge"

    @Test
    fun appDataChildren_areAllowed() {
        listOf(
            "/data/user/0/$pkg/files/runtime/rootfs",
            "/data/data/$pkg/files/workspaces/p1",
            "/data/user/10/$pkg/files/x",
            "/data/user_de/0/$pkg/cache/x",
            "/storage/emulated/0/Android/data/$pkg/files/x",
        ).forEach { assertTrue(it, RootPathGuard.isInsideAppData(it, pkg)) }
    }

    @Test
    fun everythingElse_isRefused() {
        listOf(
            "/",
            "/data",
            "/data/data",
            "/data/user/0",
            "/data/user/0/$pkg", // the app directory itself is never a target
            "/data/user/0/other.app/files/x",
            "/data/user/0/other.app/$pkg/files", // package name only as a nested folder name
            "/sdcard/Download",
            "/storage/emulated/0",
            "/system/bin",
            "/data/local/tmp/$pkg/x",
            "/tmp/$pkg/x",
        ).forEach { assertFalse(it, RootPathGuard.isInsideAppData(it, pkg)) }
    }

    @Test
    fun rootfsRepair_checksEveryRootPath() {
        val source = File("src/main/java/com/nextcraft/forge/runtime/RootfsRepair.kt").readText()
        // move, chown, delete and write-as-root: each must consult the guard before running a root command.
        assertTrue("RootfsRepair must use RootPathGuard in all four operations", "RootPathGuard.isInsideAppData" in source)
        assertTrue(Regex("""RootPathGuard\.isInsideAppData""").findAll(source).count() >= 5)
    }

    /** Runs the quoted text through a real POSIX shell: it must print back exactly the original string. */
    @Test
    fun shellSingleQuote_roundTripsHostileNames() {
        assumeTrue("needs /bin/sh", File("/bin/sh").canExecute())
        listOf(
            "plain",
            "with space",
            "it's",
            "a'b; echo pwned",
            "\$(echo pwned)",
            "`echo pwned`",
            "line1\nline2",
            "--flag",
            "*?[x]",
            "back\\slash \"quote\"",
        ).forEach { value ->
            val process = ProcessBuilder("/bin/sh", "-c", "printf %s ${shellSingleQuote(value)}").redirectErrorStream(true).start()
            val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(value, out)
        }
    }
}
