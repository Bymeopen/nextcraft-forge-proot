package com.nextcraft.forge.runtime

import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.ProjectKind
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeEffortLaunchTest {
    private val profile = ProviderProfile(kind = ProviderKind.ANTHROPIC, model = "sonnet")

    @Test
    fun validEffortReachesTheCli() {
        val env = RuntimeLaunchConfigBuilder.build(profile, authToken = "sk-ant-api-test", claudeEffort = " XHigh ").environment
        assertEquals("xhigh", env["CLAUDE_CODE_EFFORT_LEVEL"])
    }

    @Test
    fun blankOrUnknownEffortKeepsTheDefault() {
        listOf("", "turbo").forEach {
            val env = RuntimeLaunchConfigBuilder.build(profile, authToken = "sk-ant-api-test", claudeEffort = it).environment
            assertFalse(env.containsKey("CLAUDE_CODE_EFFORT_LEVEL"))
        }
    }

    @Test
    fun antiHallucinationRulesAreInjectedAcrossAllProvidersAndAgents() {
        val claudeAndDshPrompt = buildAgentWorkspacePrompt(
            currentPrompt = "Fix MainActivity.kt",
            history = listOf(ChatMessage(fromUser = true, text = "Fix MainActivity.kt")),
            guestWorkspacePath = "/workspace/demo",
            projectKind = ProjectKind.PROJECT,
            androidStackInstalled = true,
            maxHistoryChars = 160_000,
        )
        val agyPrompt = antigravityWorkspacePrompt("demo", "Fix MainActivity.kt")

        ANTI_HALLUCINATION_RULES.forEach { rule ->
            assertTrue("Missing rule in Claude/DeepSeek prompt: $rule", claudeAndDshPrompt.contains(rule))
            assertTrue("Missing rule in Antigravity prompt: $rule", agyPrompt.contains(rule))
        }
        assertTrue(AntigravityCustomizations.GLOBAL_GEMINI_MD.contains("Strict Anti-Hallucination & Factual Accuracy Policy"))
        assertTrue(AntigravityCustomizations.GLOBAL_GEMINI_MD.contains("Hermes-Lite Cognitive Architecture"))
        assertTrue(AntigravityCustomizations.SKILLS.containsKey("skill-creator"))
    }

    @Test
    fun hermesLiteWorkspaceProvisioningSeedsAndPreservesMemory() {
        val tempWorkspace = java.nio.file.Files.createTempDirectory("hermes-lite-test").toFile()
        try {
            AntigravityCustomizations.applyWorkspace(tempWorkspace, "/workspace/demo-app")
            val agentsMd = java.io.File(tempWorkspace, "AGENTS.md")
            val hooksJson = java.io.File(tempWorkspace, ".agents/hooks.json")
            val securityGate = java.io.File(tempWorkspace, ".agents/scripts/security_gate.py")
            val userMd = java.io.File(tempWorkspace, ".agents/memory/USER.md")
            val memoryMd = java.io.File(tempWorkspace, ".agents/memory/MEMORY.md")
            val skillCreator = java.io.File(tempWorkspace, ".agents/skills/skill-creator/SKILL.md")

            assertTrue(agentsMd.isFile)
            assertTrue(hooksJson.isFile)
            assertTrue(securityGate.isFile)
            assertTrue(userMd.isFile)
            assertTrue(memoryMd.isFile)
            assertTrue(skillCreator.isFile)
            assertTrue(memoryMd.readText().contains("/workspace/demo-app"))

            // Verify existing user/project memory is never overwritten on subsequent runs
            memoryMd.writeText("# Custom Persisted Memory\n- Verified milestone 1\n")
            AntigravityCustomizations.applyWorkspace(tempWorkspace, "/workspace/demo-app")
            assertEquals("# Custom Persisted Memory\n- Verified milestone 1\n", memoryMd.readText())
        } finally {
            tempWorkspace.deleteRecursively()
        }
    }
}
