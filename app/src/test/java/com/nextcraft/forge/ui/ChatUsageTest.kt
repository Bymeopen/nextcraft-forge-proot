package com.nextcraft.forge.ui

import com.nextcraft.forge.model.ActivityItem
import com.nextcraft.forge.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatUsageTest {
    @Test
    fun tokenEstimateCountsTextAndToolDetailAtFourCharsPerToken() {
        val messages = listOf(
            ChatMessage(fromUser = true, text = "a".repeat(400)),
            ChatMessage(fromUser = false, text = "b".repeat(396), workItems = listOf(ActivityItem(title = "Bash", detail = "xx", isComplete = true))),
        )
        // 400 + 396 + 4 = 800 characters
        assertEquals(200, estimateContextTokens(messages))
    }

    @Test
    fun contextWindowIsOneMillionOnlyForTheLongVariants() {
        assertEquals(1_000_000, contextWindowTokens("sonnet[1m]"))
        assertEquals(1_000_000, contextWindowTokens("opus[1M]"))
        assertEquals(200_000, contextWindowTokens("claude-opus-5-5"))
    }

    @Test
    fun tokenCountsAreCompact() {
        assertEquals("1M", formatTokenCount(1_000_000))
        assertEquals("200k", formatTokenCount(200_000))
        assertEquals("442.8k", formatTokenCount(442_800))
        assertEquals("950", formatTokenCount(950))
    }

    @Test
    fun claudeModelLabelsCarryTheVersion() {
        assertEquals("Claude Opus 5.5", claudeModelLabel("claude-opus-5-5"))
        assertEquals("Claude Sonnet (latest) · 1M", claudeModelLabel("sonnet[1m]"))
        assertEquals("Default", claudeModelLabel("default"))
    }
}
