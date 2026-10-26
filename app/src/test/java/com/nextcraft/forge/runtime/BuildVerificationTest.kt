package com.nextcraft.forge.runtime

import com.nextcraft.forge.runtime.BuildVerification.FailureKind
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildVerificationTest {
    @Test
    fun classifiesFailures() {
        assertEquals(FailureKind.OUT_OF_MEMORY, BuildVerification.classify("FAILURE: Build failed", 137, 0))
        assertEquals(FailureKind.OUT_OF_MEMORY, BuildVerification.classify("java.lang.OutOfMemoryError: Java heap space", 1, 0))
        assertEquals(FailureKind.DEPENDENCY, BuildVerification.classify("Could not resolve androidx.room:room-runtime:2.8.0", 1, 0))
        assertEquals(FailureKind.CODE, BuildVerification.classify("e: file:///w/app/Main.kt:3:5 Unresolved reference 'foo'", 1, 0))
        assertEquals(FailureKind.CODE, BuildVerification.classify("FAILURE: Build failed", 1, 2))
        assertEquals(FailureKind.OTHER, BuildVerification.classify("FAILURE: Build failed with an exception.", 1, 0))
    }

    @Test
    fun fixPromptIsMarkedAndForbidsSkippingTests() {
        val prompt = BuildVerification.fixPrompt(2, listOf("Please fix this Android build error in `Main.kt:3`"), "")
        assertTrue(BuildVerification.isAutoFixPrompt(prompt))
        assertTrue(prompt.startsWith("[Auto-fix 2/${BuildVerification.MAX_FIX_ROUNDS}]"))
        assertTrue(prompt.contains("Main.kt:3"))
        assertTrue(prompt.contains("Do not delete, skip or weaken tests"))
        assertFalse(BuildVerification.isAutoFixPrompt("make the button blue"))
    }

    @Test
    fun fixPromptFallsBackToTheLogTail() {
        val log = (1..100).joinToString("\n") { "line $it" }
        val prompt = BuildVerification.fixPrompt(1, emptyList(), log)
        assertTrue(prompt.contains("line 100"))
        assertFalse(prompt.contains("line 1\n"))
    }

    @Test
    fun gradleTargetsRunInOneInvocation() {
        val workspace = Files.createTempDirectory("matrix").toFile()
        java.io.File(workspace, "settings.gradle.kts").writeText("")
        java.io.File(workspace, "app/src/main").apply { mkdirs() }.resolve("AndroidManifest.xml").writeText("<manifest/>")
        fun target(id: String) = BuildTarget(id, id, "x", detected = true, enabled = true)
        val custom = BuildTarget("custom:1", "custom", "echo", detected = false, enabled = true)
        val steps = withGradleBatched(workspace, listOf(target("gradle:assembleDebug"), custom, target("gradle:test")))
        assertEquals(2, steps.size)
        assertEquals(listOf("gradle:assembleDebug", "gradle:test"), steps[0].ids)
        assertTrue(steps[0].target.command.contains("assembleDebug test --console=plain"))
        assertEquals(custom, steps[1].target)
        workspace.deleteRecursively()
    }

    @Test
    fun lowMemoryFlagsGoIntoTheCommand() {
        val root = Files.createTempDirectory("gradle").toFile()
        val command = androidGradleCommand(root, BuildVerification.VERIFY_TASKS, BuildVerification.LOW_MEMORY_FLAGS)
        assertTrue(command.contains("--max-workers=1"))
        assertTrue(command.contains("assembleDebug testDebugUnitTest --console=plain"))
        root.deleteRecursively()
    }
}
