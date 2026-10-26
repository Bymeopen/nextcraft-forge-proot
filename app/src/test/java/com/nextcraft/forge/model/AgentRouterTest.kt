package com.nextcraft.forge.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRouterTest {
    private val all = AgentKind.entries.toSet()
    private fun config(mode: RouteMode, agents: List<AgentKind> = AgentKind.entries) = RoutingConfig(mode, agents)

    @Test
    fun tagSelectsAgentAndIsRemoved() {
        val routed = AgentRouter.route("@deepseek: write tests", config(RouteMode.MANUAL), AgentKind.CLAUDE_CODE, all, 0)!!
        assertEquals(AgentKind.DEEPSEEK_HARNESS, routed.agent)
        assertEquals("write tests", routed.text)
        assertTrue(routed.tagged)
    }

    @Test
    fun unknownOrNotReadyTagIsLeftInTheText() {
        val unknown = AgentRouter.route("@bob hi", config(RouteMode.MANUAL), AgentKind.CLAUDE_CODE, all, 0)!!
        assertEquals("@bob hi", unknown.text)
        assertEquals(AgentKind.CLAUDE_CODE, unknown.agent)
        val notReady = AgentRouter.route("@agy hi", config(RouteMode.MANUAL), AgentKind.CLAUDE_CODE, setOf(AgentKind.CLAUDE_CODE), 0)!!
        assertEquals(AgentKind.CLAUDE_CODE, notReady.agent)
        assertFalse(notReady.tagged)
    }

    @Test
    fun singleIgnoresReadinessAndUsesSelected() {
        val routed = AgentRouter.route("hi", config(RouteMode.SINGLE), AgentKind.ANTIGRAVITY, emptySet(), 5)!!
        assertEquals(AgentKind.ANTIGRAVITY, routed.agent)
    }

    @Test
    fun singleNeverLeavesTheSelectedAgentNotEvenWithATagOrARule() {
        val rules = listOf(PromptRule(listOf("unit test"), AgentKind.DEEPSEEK_HARNESS))
        val cfg = RoutingConfig(RouteMode.SINGLE, rules = rules)
        for (text in listOf("@deepseek fix it", "@agy: hello", "write a unit test", "@claude hi")) {
            val routed = AgentRouter.route(text, cfg, AgentKind.ANTIGRAVITY, all, 7)!!
            assertEquals(AgentKind.ANTIGRAVITY, routed.agent)
            assertEquals(text, routed.text)
            assertFalse(routed.tagged)
        }
    }

    @Test
    fun failoverUsesPriorityOrderSkippingTriedAndNotReady() {
        val order = listOf(AgentKind.DEEPSEEK_HARNESS, AgentKind.CLAUDE_CODE, AgentKind.ANTIGRAVITY)
        val cfg = config(RouteMode.FAILOVER, order)
        val ready = setOf(AgentKind.CLAUDE_CODE, AgentKind.ANTIGRAVITY)
        assertEquals(AgentKind.CLAUDE_CODE, AgentRouter.route("x", cfg, AgentKind.ANTIGRAVITY, ready, 0)!!.agent)
        assertEquals(AgentKind.ANTIGRAVITY, AgentRouter.route("x", cfg, AgentKind.ANTIGRAVITY, ready, 0, tried = setOf(AgentKind.CLAUDE_CODE))!!.agent)
        assertNull(AgentRouter.route("x", cfg, AgentKind.ANTIGRAVITY, ready, 0, tried = ready))
    }

    @Test
    fun balancedRotatesThroughReadyAgents() {
        val cfg = config(RouteMode.BALANCED, listOf(AgentKind.CLAUDE_CODE, AgentKind.DEEPSEEK_HARNESS))
        val ready = setOf(AgentKind.CLAUDE_CODE, AgentKind.DEEPSEEK_HARNESS)
        val picks = (0..3).map { AgentRouter.route("x", cfg, AgentKind.CLAUDE_CODE, ready, it)!!.agent }
        assertEquals(listOf(AgentKind.CLAUDE_CODE, AgentKind.DEEPSEEK_HARNESS, AgentKind.CLAUDE_CODE, AgentKind.DEEPSEEK_HARNESS), picks)
    }

    @Test
    fun balancedHonoursWeightsAndInterleaves() {
        val two = listOf(AgentKind.CLAUDE_CODE, AgentKind.DEEPSEEK_HARNESS)
        val ready = two.toSet()
        val cfg = RoutingConfig(RouteMode.BALANCED, two, mapOf(AgentKind.CLAUDE_CODE to 2, AgentKind.DEEPSEEK_HARNESS to 1))
        val picks = (0 until 6).map { AgentRouter.route("x", cfg, AgentKind.CLAUDE_CODE, ready, it)!!.agent }
        assertEquals(4, picks.count { it == AgentKind.CLAUDE_CODE })
        assertEquals(2, picks.count { it == AgentKind.DEEPSEEK_HARNESS })
        // Never three of the same in a row for 2:1.
        assertFalse(picks.windowed(3).any { w -> w.all { it == w[0] } })
    }

    @Test
    fun fiftyFiftyAlternatesAndZeroWeightIsSkipped() {
        val two = listOf(AgentKind.CLAUDE_CODE, AgentKind.DEEPSEEK_HARNESS)
        val ready = two.toSet()
        val equal = RoutingConfig(RouteMode.BALANCED, two, mapOf(AgentKind.CLAUDE_CODE to 5, AgentKind.DEEPSEEK_HARNESS to 5))
        val picks = (0 until 4).map { AgentRouter.route("x", equal, AgentKind.CLAUDE_CODE, ready, it)!!.agent }
        assertEquals(setOf(AgentKind.CLAUDE_CODE, AgentKind.DEEPSEEK_HARNESS), picks.take(2).toSet())
        assertEquals(picks.take(2), picks.drop(2))
        val skipped = RoutingConfig(RouteMode.BALANCED, two, mapOf(AgentKind.CLAUDE_CODE to 0, AgentKind.DEEPSEEK_HARNESS to 3))
        assertTrue((0 until 5).all { AgentRouter.route("x", skipped, AgentKind.CLAUDE_CODE, ready, it)!!.agent == AgentKind.DEEPSEEK_HARNESS })
    }

    @Test
    fun promptRulesRouteByContentBeforeTheMode() {
        val rules = listOf(PromptRule(listOf("unit test", "pytest"), AgentKind.DEEPSEEK_HARNESS))
        val cfg = RoutingConfig(RouteMode.MANUAL, rules = rules)
        assertEquals(AgentKind.DEEPSEEK_HARNESS, AgentRouter.route("Write a Unit Test for login", cfg, AgentKind.CLAUDE_CODE, all, 0)!!.agent)
        assertEquals(AgentKind.CLAUDE_CODE, AgentRouter.route("refactor login", cfg, AgentKind.CLAUDE_CODE, all, 0)!!.agent)
        // A tag still wins, a rule for a not-ready agent is ignored, and Single mode ignores rules.
        assertEquals(AgentKind.ANTIGRAVITY, AgentRouter.route("@agy unit test it", cfg, AgentKind.CLAUDE_CODE, all, 0)!!.agent)
        assertEquals(AgentKind.CLAUDE_CODE, AgentRouter.route("unit test", cfg, AgentKind.CLAUDE_CODE, setOf(AgentKind.CLAUDE_CODE), 0)!!.agent)
        assertEquals(AgentKind.CLAUDE_CODE, AgentRouter.route("unit test", cfg.copy(mode = RouteMode.SINGLE), AgentKind.CLAUDE_CODE, all, 0)!!.agent)
    }

    @Test
    fun keywordsAreSplitOnCommasAndNewlines() {
        assertEquals(listOf("test", "unit tests"), PromptRule.parseKeywords(" test, unit tests,\n TEST ,, "))
    }

    @Test
    fun manualNeverUsesAnAgentThatIsSwitchedOff() {
        // Claude is the selected agent but switched off; only Antigravity is on.
        val cfg = RoutingConfig(RouteMode.MANUAL, agents = listOf(AgentKind.ANTIGRAVITY))
        val plain = AgentRouter.route("hello", cfg, AgentKind.CLAUDE_CODE, all, 0)!!
        assertEquals(AgentKind.ANTIGRAVITY, plain.agent)
        // A tag cannot reach the switched-off agent either: the tag stays in the text and the on agent answers.
        val tagged = AgentRouter.route("@claude hello", cfg, AgentKind.CLAUDE_CODE, all, 0)!!
        assertEquals(AgentKind.ANTIGRAVITY, tagged.agent)
        assertEquals("@claude hello", tagged.text)
    }

    @Test
    fun primaryAgentFollowsTheSwitchedOnList() {
        val onlyAgy = RoutingConfig(RouteMode.MANUAL, agents = listOf(AgentKind.ANTIGRAVITY))
        assertEquals(AgentKind.ANTIGRAVITY, AgentRouter.primaryAgent(onlyAgy, AgentKind.CLAUDE_CODE, all))
        val both = RoutingConfig(RouteMode.MANUAL, agents = listOf(AgentKind.ANTIGRAVITY, AgentKind.CLAUDE_CODE))
        assertEquals(AgentKind.CLAUDE_CODE, AgentRouter.primaryAgent(both, AgentKind.CLAUDE_CODE, all))
        val failover = RoutingConfig(RouteMode.FAILOVER, agents = listOf(AgentKind.DEEPSEEK_HARNESS, AgentKind.CLAUDE_CODE))
        assertEquals(AgentKind.CLAUDE_CODE, AgentRouter.primaryAgent(failover, AgentKind.ANTIGRAVITY, setOf(AgentKind.CLAUDE_CODE)))
        assertEquals(AgentKind.ANTIGRAVITY, AgentRouter.primaryAgent(RoutingConfig(RouteMode.SINGLE), AgentKind.ANTIGRAVITY, all))
    }

    @Test
    fun onlyFailoverAndBalancedFailOver() {
        assertTrue(AgentRouter.failsOver(RouteMode.FAILOVER))
        assertTrue(AgentRouter.failsOver(RouteMode.BALANCED))
        assertFalse(AgentRouter.failsOver(RouteMode.MANUAL))
        assertFalse(AgentRouter.failsOver(RouteMode.SINGLE))
    }
}
