package com.nextcraft.forge.runtime

import com.nextcraft.forge.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedConversationTest {
    private fun chat(vararg texts: String) = texts.mapIndexed { i, t -> ChatMessage(fromUser = i % 2 == 0, text = t) }

    @Test
    fun encodesAndDecodes() {
        val saved = SavedConversation("abc", 4, "CLAUDE:/workspace/app")
        assertEquals(saved, SavedConversation.decode(saved.encode()))
        assertEquals(SavedConversation("legacy-id"), SavedConversation.decode("legacy-id"))
        assertNull(SavedConversation.decode(""))
        assertNull(SavedConversation.decode(null))
    }

    @Test
    fun newConversationGetsEverythingButTheCurrentPrompt() {
        val history = chat("hi", "hello", "next")
        assertEquals(listOf("hi", "hello"), history.unseenBy(null).map { it.text })
    }

    @Test
    fun resumedConversationGetsOnlyMessagesAnsweredByAnotherAgent() {
        // Saved after "a1": the agent saw u1, a1. Another agent answered u2 with a2; u3 is the current prompt.
        val history = chat("u1", "a1", "u2", "a2", "u3")
        assertEquals(listOf("u2", "a2"), history.unseenBy(SavedConversation("id", 2)).map { it.text })
        assertTrue(history.unseenBy(SavedConversation("id", 4)).isEmpty())
        assertTrue(history.unseenBy(SavedConversation("id")).isEmpty()) // unknown count: trust the agent
    }

    @Test
    fun errorBubblesAreLeftOut() {
        val history = chat("u1", "Error: boom", "u2")
        assertEquals(listOf("u1"), history.unseenBy(null).map { it.text })
    }
}
