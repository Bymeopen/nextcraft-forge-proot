package com.nextcraft.forge.ui

import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.ToolRequest
import com.nextcraft.forge.runtime.RuntimeBridge
import kotlinx.coroutines.Job

/** The effort part of an Antigravity model id such as gemini-4-argon-high. */
internal val AGY_EFFORT_SUFFIX = Regex("-(low|medium|high)$")

/** Characters of a tool's output kept in the chat: the start (the command and its first lines) and the end (the result). */
private const val TOOL_DETAIL_HEAD = 1_500
private const val TOOL_DETAIL_TAIL = 2_500

/**
 * Shortens a tool's output for the chat. A build or test run can print megabytes; keeping all of it in every message
 * made long chats slow to draw, scroll, save and reopen. The full output stays in the agent's own context and logs.
 */
internal fun clipToolDetail(detail: String): String {
    if (detail.length <= TOOL_DETAIL_HEAD + TOOL_DETAIL_TAIL + 200) return detail
    val head = detail.substring(0, TOOL_DETAIL_HEAD)
    val tail = detail.substring(detail.length - TOOL_DETAIL_TAIL)
    val omittedLines = detail.substring(TOOL_DETAIL_HEAD, detail.length - TOOL_DETAIL_TAIL).count { it == '\n' }
    return "$head\n… ${omittedLines + 1} lines omitted …\n$tail"
}

/** Failures that a plain resend usually fixes: the provider's stream stalled, the connection dropped, or a 5xx. */
internal fun isTransientAgentFailure(reason: String): Boolean {
    val text = reason.lowercase()
    return listOf(
        "stream idle timeout", "context deadline exceeded", "sent nothing for 5 minutes", "stream was interrupted",
        // agy's own wording for a dropped request, and our message for an unreachable Google.
        "network issue connecting to the server", "could not reach google",
        "could not fetch the model catalog", "invalid model selection", "not recognized as a known model",
        "dropped mid-answer", "connection reset", "server returned an error", "overloaded", "code 500", "code 502",
        "code 503", "internal error encountered",
        // The agent's saved session is gone; the retry starts a new one with the chat history.
        "saved agent session was not found",
    ).any { it in text }
}

/** One agent turn in progress for a chat: its bridge, session, and the live copy of the chat it streams into. */
internal class ChatRun(
    val chatId: String,
    val projectId: String,
    val projectName: String = "",
    val chatTitle: String = "Main chat",
    val bridge: RuntimeBridge,
    /** True when [bridge] was created for this run (Claude Code); its events are collected by the run itself. */
    val ownsBridge: Boolean,
    val lastPrompt: QueuedPrompt,
    /** True when the run works in the chat's own branch (a clone of the project), not in the project folder. */
    val isolated: Boolean = false,
    val agent: AgentKind = AgentKind.CLAUDE_CODE,
    /** Agents whose turn already failed for this message (Failover and Balanced). */
    val tried: Set<AgentKind> = emptySet(),
) {
    var job: Job? = null
    var eventsJob: Job? = null
    var sessionId: String? = null
    var finishedSessionId: String? = null
    var messages: List<ChatMessage> = emptyList()
    /** Streamed text not yet folded into [messages]; keeps one string copy per publish instead of one per delta. */
    val pendingText = StringBuilder()
    var lastPublishAt = 0L
    var publishJob: Job? = null
    var statusLine: UiText? = null
    var pendingTool: ToolRequest? = null
    var finished: Boolean = false

    /** True while the run is being stopped so that a queued message can go out at once (see steerQueuedPrompt). */
    var steering: Boolean = false
    /** The agent changed project files this turn: the local build check has something to verify. */
    var changedFiles: Boolean = false
    /** True once the assistant has streamed reply text in this turn. */
    var assistantReplied: Boolean = false

    /**
     * Whether this turn already produced a substantial reply or completed all tool work without any tool left hanging,
     * so an end-of-turn disconnect must not auto-retry the entire prompt from 0s.
     */
    fun hasSubstantialReplyOrCompletedWork(): Boolean {
        val lastAssistant = messages.lastOrNull { !it.fromUser }
        val textLen = (lastAssistant?.text?.length ?: 0) + pendingText.length
        val items = lastAssistant?.workItems.orEmpty()
        val hasRunningTool = pendingTool != null || items.any { !it.isComplete }
        return !hasRunningTool && (textLen >= 160 || (textLen > 0 && items.isNotEmpty()) || changedFiles)
    }
}
