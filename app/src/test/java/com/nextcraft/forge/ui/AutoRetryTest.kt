package com.nextcraft.forge.ui

import com.nextcraft.forge.model.ActivityItem
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.ToolRequest
import com.nextcraft.forge.runtime.RuntimeBridge
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRetryTest {
    private val dummyBridge = object : RuntimeBridge {
        override val events = emptyFlow<com.nextcraft.forge.model.RuntimeEvent>()
        override suspend fun startSession(
            projectId: String,
            projectSlug: String,
            projectKind: com.nextcraft.forge.model.ProjectKind,
            prompt: String,
            conversationHistory: List<ChatMessage>,
            provider: com.nextcraft.forge.model.ProviderProfile,
        ): String = "test"
        override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) = Unit
        override suspend fun stopSession(sessionId: String) = Unit
        override suspend fun stopActiveSession() = Unit
        override suspend fun undoLastChanges(projectId: String): Boolean = false
        override suspend fun acceptLastChanges(projectId: String) = Unit
        override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = emptyList()
        override suspend fun undoFileChange(projectId: String, path: String): Boolean = false
        override suspend fun acceptFileChange(projectId: String, path: String): Boolean = false
    }

    @Test
    fun passingProviderFailuresAreRetried() {
        assertTrue(isTransientAgentFailure("API error (attempt 1): request failed: stream idle timeout (5m0s): context deadline exceeded"))
        assertTrue(isTransientAgentFailure("Google's Antigravity server returned an error. Wait a moment and try again"))
        assertTrue(isTransientAgentFailure("overloaded_error"))
        assertTrue(isTransientAgentFailure("There was a network issue connecting to the server, please try again."))
    }

    @Test
    fun realProblemsAreNotRetried() {
        assertFalse(isTransientAgentFailure("Antigravity needs Google sign-in. Open Settings → Coding agent."))
        assertFalse(isTransientAgentFailure("Your Antigravity account is out of credits."))
        assertFalse(isTransientAgentFailure("Stopped by user"))
    }

    @Test
    fun completedTurnWithAnswerIsNotAutoRetriedFromZero() {
        val run = ChatRun(
            chatId = "main",
            projectId = "p1",
            bridge = dummyBridge,
            ownsBridge = true,
            lastPrompt = QueuedPrompt(text = "Fix git lock"),
        )
        assertFalse(run.hasSubstantialReplyOrCompletedWork())

        // Once the assistant has streamed a substantial reply or completed tool work, do not auto-retry from 0s.
        run.messages = listOf(
            ChatMessage(fromUser = true, text = "Fix git lock"),
            ChatMessage(
                fromUser = false,
                text = "Saya telah menerapkan antrian tunggu otomatis untuk operasi Git.",
                workItems = listOf(ActivityItem(title = "Edit file", detail = "MainViewModel.kt", isComplete = true)),
            ),
        )
        assertTrue(run.hasSubstantialReplyOrCompletedWork())

        // If a tool is still actively running (incomplete) when the stream drops, it is not complete yet.
        run.messages = listOf(
            ChatMessage(fromUser = true, text = "Fix git lock"),
            ChatMessage(
                fromUser = false,
                text = "Sedang membaca file...",
                workItems = listOf(ActivityItem(title = "Bash", detail = "gradle test", isComplete = false)),
            ),
        )
        assertFalse(run.hasSubstantialReplyOrCompletedWork())
    }
}
