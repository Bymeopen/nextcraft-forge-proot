package com.nextcraft.forge.model

import java.time.Instant
import java.util.UUID

enum class RiskLevel {
    SAFE,
    REVIEW,
    HIGH,
}

data class ToolRequest(
    val approvalId: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val toolName: String,
    val explanation: String,
    val affectedPaths: List<String> = emptyList(),
    val commandPreview: String? = null,
    val risk: RiskLevel,
)

sealed interface RuntimeEvent {
    val sessionId: String

    data class SessionStarted(
        override val sessionId: String,
    ) : RuntimeEvent

    data class AssistantDelta(
        override val sessionId: String,
        val text: String,
    ) : RuntimeEvent

    data class ReasoningProgress(
        override val sessionId: String,
        val estimatedTokens: Int,
    ) : RuntimeEvent

    data class ReasoningSummary(
        override val sessionId: String,
        val summary: String,
        val blockId: Long,
        val startsNewBlock: Boolean = false,
        val isFinal: Boolean = false,
    ) : RuntimeEvent

    data class ToolStarted(
        override val sessionId: String,
        val toolName: String,
        val detail: String,
    ) : RuntimeEvent

    data class ToolProgress(
        override val sessionId: String,
        val toolName: String,
        val detail: String,
    ) : RuntimeEvent

    data class RuntimeLog(
        override val sessionId: String,
        val title: String,
        val detail: String,
    ) : RuntimeEvent

    data class ToolRequested(
        override val sessionId: String,
        val request: ToolRequest,
    ) : RuntimeEvent

    data class ToolApproved(
        override val sessionId: String,
        val approvalId: String,
    ) : RuntimeEvent

    data class ToolRejected(
        override val sessionId: String,
        val approvalId: String,
    ) : RuntimeEvent

    data class PermissionDenied(
        override val sessionId: String,
        val toolName: String,
        val reason: String,
    ) : RuntimeEvent

    data class ToolCompleted(
        override val sessionId: String,
        val toolName: String,
        val summary: String,
    ) : RuntimeEvent

    data class FilesChanged(
        override val sessionId: String,
        val changes: List<ChangeItem>,
    ) : RuntimeEvent {
        val paths: List<String>
            get() = changes.map { item -> item.path }
    }

    data class PreviewStarted(
        override val sessionId: String,
        val url: String,
    ) : RuntimeEvent

    data class SessionCompleted(
        override val sessionId: String,
    ) : RuntimeEvent

    data class SessionFailed(
        override val sessionId: String,
        val reason: String,
    ) : RuntimeEvent
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val fromUser: Boolean,
    val text: String,
    val createdAt: Instant = Instant.now(),
    val attachments: List<ChatAttachment> = emptyList(),
    val workItems: List<ActivityItem> = emptyList(),
    val workedMillis: Long = 0L,
    val agent: String? = null,
)

data class ChatAttachment(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
)

data class ProjectChat(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val isolated: Boolean = false,
)

enum class DiffLineType {
    CONTEXT,
    ADDITION,
    DELETION,
    INFO,
}

data class DiffLine(
    val type: DiffLineType,
    val text: String,
    val oldLine: Int? = null,
    val newLine: Int? = null,
    val hunkIndex: Int = 0,
)

data class ChangeItem(
    val path: String,
    val additions: Int,
    val deletions: Int,
    val diffLines: List<DiffLine> = emptyList(),
    val binary: Boolean = false,
    val accepted: Boolean? = null,
)

data class ActivityItem(
    val title: String,
    val detail: String,
    val isComplete: Boolean = true,
    val isCommand: Boolean = false,
)
