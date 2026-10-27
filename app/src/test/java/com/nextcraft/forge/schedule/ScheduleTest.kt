package com.nextcraft.forge.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTest {
    @Test fun intervals() {
        assertEquals(30, ScheduleRules.parseInterval("30m"))
        assertEquals(120, ScheduleRules.parseInterval("2h"))
        assertEquals(1440, ScheduleRules.parseInterval("1d"))
        assertEquals(45, ScheduleRules.parseInterval("45"))
        assertNull(ScheduleRules.parseInterval("1m"))
        assertNull(ScheduleRules.parseInterval("99d"))
        assertNull(ScheduleRules.parseInterval("abc"))
        assertEquals("2h", ScheduleRules.label(120))
        assertEquals("45m", ScheduleRules.label(45))
    }

    @Test fun parsesDirectives() {
        val reply = "Done.\n[[schedule: 30m | Check CI and report changes]]\n[[Schedule: 2h|Again]]\n[[schedule: 1m | too fast]]\n[[schedule: 10m | ]]"
        val parsed = ScheduleDirectives.parse(reply)
        assertEquals(
            listOf(ScheduleDirective.Schedule(30, "Check CI and report changes"), ScheduleDirective.Schedule(120, "Again")),
            parsed,
        )
        assertEquals(listOf(ScheduleDirective.Unschedule), ScheduleDirectives.parse("ok [[unschedule]]"))
        assertTrue(ScheduleDirectives.parse("no directive here").isEmpty())
    }

    @Test fun noteOnlyWhenRelevant() {
        assertTrue(ScheduleDirectives.augment("pantau build tiap 30 menit", true).contains("[[schedule"))
        assertEquals("fix the bug", ScheduleDirectives.augment("fix the bug", true))
        assertEquals("every day", ScheduleDirectives.augment("every day", false))
    }

    @Test fun dueTasksMoveForward() {
        val t = ScheduledTask(projectId = "p", chatId = "c", prompt = "x", intervalMinutes = 10, nextRunAtMillis = 1_000)
        val (list, started) = ScheduleLogic.takeDue(listOf(t), now = 5_000_000) { true }
        assertEquals(1, started.size)
        assertEquals(5_000_000 + 600_000, list.single().nextRunAtMillis)
        assertEquals(1, list.single().runCount)
        val (same, none) = ScheduleLogic.takeDue(listOf(t), now = 5_000_000) { false }
        assertTrue(none.isEmpty())
        assertEquals(t, same.single())
        assertFalse(t.copy(enabled = false).isDue(Long.MAX_VALUE))
    }

    @Test fun agentDirectivesAreLimited() {
        var tasks = emptyList<ScheduledTask>()
        repeat(5) { i ->
            tasks = ScheduleLogic.applyDirectives(tasks, listOf(ScheduleDirective.Schedule(10, "p$i")), "p", "c", 0)
        }
        assertEquals(ScheduleRules.MAX_AGENT_TASKS_PER_CHAT, tasks.size)
        // Agent-scheduled tasks start enabled so background wakeups run automatically while foreground chat stops.
        assertTrue(tasks.all { it.enabled })
        // Same prompt replaces its timer.
        tasks = ScheduleLogic.applyDirectives(tasks, listOf(ScheduleDirective.Schedule(60, "p0")), "p", "c", 0)
        assertEquals(60, tasks.first { it.prompt == "p0" }.intervalMinutes)
        assertTrue(tasks.first { it.prompt == "p0" }.enabled)
        assertEquals(ScheduleRules.MAX_AGENT_TASKS_PER_CHAT, tasks.size)
        // A timer the user switched off stays off when the agent re-issues it.
        val disabledByUser = tasks.map { if (it.prompt == "p1") it.copy(enabled = false) else it }
        val reissued = ScheduleLogic.applyDirectives(disabledByUser, listOf(ScheduleDirective.Schedule(15, "p1")), "p", "c", 0)
        assertFalse(reissued.first { it.prompt == "p1" }.enabled)
        // Unschedule removes only the agent's tasks in that chat and project (even when another project shares chatId="main").
        val user = ScheduledTask(projectId = "p", chatId = "c", prompt = "mine", intervalMinutes = 10, nextRunAtMillis = 0)
        val other = ScheduledTask(projectId = "p", chatId = "d", prompt = "other", intervalMinutes = 10, nextRunAtMillis = 0, byAgent = true)
        val otherProjectSameChat = ScheduledTask(projectId = "p2", chatId = "c", prompt = "otherProject", intervalMinutes = 10, nextRunAtMillis = 0, byAgent = true)
        val after = ScheduleLogic.applyDirectives(tasks + user + other + otherProjectSameChat, listOf(ScheduleDirective.Unschedule), "p", "c", 0)
        assertEquals(setOf("mine", "other", "otherProject"), after.map { it.prompt }.toSet())
    }
}
