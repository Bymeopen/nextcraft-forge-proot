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
            priorMessages = emptyList(),
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
    }
}
