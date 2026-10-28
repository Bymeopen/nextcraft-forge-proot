package com.nextcraft.forge.runtime

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.ProjectKind
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.ProviderProtocol
import com.nextcraft.forge.model.RuntimeEvent
import com.nextcraft.forge.model.ToolRequest
import kotlinx.coroutines.flow.Flow

data class RuntimeLaunchConfig(
    val executable: String,
    val arguments: List<String>,
    val environment: Map<String, String>,
)

object RuntimeTaskController {
    const val AGENT = "agent"
    const val BUILD = "build"

    private val cancelCallbacks = LinkedHashMap<String, () -> Unit>()

    @Synchronized
    fun register(kind: String, action: () -> Unit) {
        cancelCallbacks[kind] = action
    }

    @Synchronized
    fun release(action: (() -> Unit)?) {
        if (action != null) {
            cancelCallbacks.entries.removeAll { it.value === action }
        }
    }

    @Synchronized
    fun hasActiveTask(): Boolean = cancelCallbacks.isNotEmpty()

    fun stop(kind: String) {
        val toInvoke = synchronized(this) {
            val matchingKeys = cancelCallbacks.keys.filter { k -> k == kind || k.startsWith("$kind:") }
            matchingKeys.mapNotNull { k -> cancelCallbacks.remove(k) }
        }
        for (cb in toInvoke) cb()
    }

    fun requestStop() {
        val toInvoke = synchronized(this) {
            cancelCallbacks.values.toList().also { cancelCallbacks.clear() }
        }
        for (cb in toInvoke) cb()
    }
}

object RuntimeExecutionServiceContract {
    const val SERVICE_CLASS_NAME = "com.nextcraft.forge.runtime.RuntimeExecutionService"
    const val ACTION_START = "com.nextcraft.forge.runtime.action.START"
    const val ACTION_PROGRESS = "com.nextcraft.forge.runtime.action.PROGRESS"
    const val ACTION_COMPLETE = "com.nextcraft.forge.runtime.action.COMPLETE"
    const val ACTION_FAILED = "com.nextcraft.forge.runtime.action.FAILED"
    const val ACTION_CANCELLED = "com.nextcraft.forge.runtime.action.CANCELLED"
    const val EXTRA_PROJECT_NAME = "com.nextcraft.forge.runtime.extra.PROJECT_NAME"
    const val EXTRA_DETAIL = "com.nextcraft.forge.runtime.extra.DETAIL"
    const val EXTRA_DETAIL_RES = "com.nextcraft.forge.runtime.extra.DETAIL_RES"
    const val EXTRA_DETAIL_ARGS = "com.nextcraft.forge.runtime.extra.DETAIL_ARGS"

    private fun lookupServiceClass(): Class<*>? =
        runCatching { Class.forName(SERVICE_CLASS_NAME) }.getOrNull()

    fun startForeground(context: Context, projectName: String) {
        val targetCls = lookupServiceClass() ?: return
        runCatching {
            val startIntent = Intent(context, targetCls)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROJECT_NAME, projectName)
            ContextCompat.startForegroundService(context, startIntent)
        }.onFailure { ErrorLog.swallowed("RuntimeBridge", it) }
    }

    fun pushProgress(context: Context, projectName: String?, detail: String) {
        val targetCls = lookupServiceClass() ?: return
        runCatching {
            val progIntent = Intent(context, targetCls)
                .setAction(ACTION_PROGRESS)
                .putExtra(EXTRA_PROJECT_NAME, projectName)
                .putExtra(EXTRA_DETAIL, detail)
            context.startService(progIntent)
        }.onFailure { ErrorLog.swallowed("RuntimeBridge", it) }
    }

    fun finishForeground(
        context: Context,
        completed: Boolean,
        projectName: String,
        detail: String,
        tag: String = "RuntimeBridge",
    ) {
        val targetCls = lookupServiceClass() ?: return
        val outcomeAction = if (completed) ACTION_COMPLETE else ACTION_FAILED
        runCatching {
            val doneIntent = Intent(context, targetCls)
                .setAction(outcomeAction)
                .putExtra(EXTRA_PROJECT_NAME, projectName)
                .putExtra(EXTRA_DETAIL, detail)
            context.startService(doneIntent)
        }.onFailure { err ->
            Log.w(tag, "Could not post task result notification", err)
            runCatching { context.stopService(Intent(context, targetCls)) }
                .onFailure { ErrorLog.swallowed("RuntimeBridge", it) }
        }
    }

    fun finishForegroundRes(
        context: Context,
        completed: Boolean,
        projectName: String,
        @StringRes detailRes: Int,
        args: Array<String>,
        tag: String = "RuntimeBridge",
    ) {
        val targetCls = lookupServiceClass() ?: return
        val outcomeAction = if (completed) ACTION_COMPLETE else ACTION_FAILED
        runCatching {
            val doneIntent = Intent(context, targetCls)
                .setAction(outcomeAction)
                .putExtra(EXTRA_PROJECT_NAME, projectName)
                .putExtra(EXTRA_DETAIL_RES, detailRes)
                .putExtra(EXTRA_DETAIL_ARGS, args)
            context.startService(doneIntent)
        }.onFailure { err ->
            Log.w(tag, "Could not post task result notification", err)
            runCatching { context.stopService(Intent(context, targetCls)) }
                .onFailure { ErrorLog.swallowed("RuntimeBridge", it) }
        }
    }

    fun cancelForeground(context: Context) {
        val targetCls = lookupServiceClass() ?: return
        runCatching {
            context.startService(Intent(context, targetCls).setAction(ACTION_CANCELLED))
        }.onFailure {
            runCatching { context.stopService(Intent(context, targetCls)) }
                .onFailure { ErrorLog.swallowed("RuntimeBridge", it) }
        }
    }
}

interface RuntimeBridge {
    val events: Flow<RuntimeEvent>

    suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String

    suspend fun respondToApproval(request: ToolRequest, approved: Boolean)
    suspend fun stopSession(sessionId: String)
    suspend fun stopActiveSession()
    suspend fun undoLastChanges(projectId: String): Boolean
    suspend fun acceptLastChanges(projectId: String)
    suspend fun loadPendingChanges(projectId: String): List<ChangeItem>
    suspend fun undoFileChange(projectId: String, path: String): Boolean
    suspend fun acceptFileChange(projectId: String, path: String): Boolean
}

internal fun List<ChatMessage>.recentWithinCharacterBudget(maxCharacters: Int): List<ChatMessage> {
    if (maxCharacters <= 0 || isEmpty()) return emptyList()
    var budgetLeft = maxCharacters
    val window = ArrayDeque<ChatMessage>()
    for (idx in lastIndex downTo 0) {
        if (budgetLeft <= 0) break
        val item = this[idx]
        val clipped = if (item.text.length <= budgetLeft) item.text else item.text.takeLast(budgetLeft)
        window.addFirst(item.copy(text = clipped))
        budgetLeft -= clipped.length
        if (clipped.length < item.text.length) break
    }
    return window.toList()
}

internal fun localServerPlaceholderKey(profile: ProviderProfile): String =
    if (profile.kind == ProviderKind.CUSTOM) "no-key-required" else ""

val CLAUDE_EFFORT_LEVELS: List<String> = listOf("low", "medium", "high", "xhigh", "max")

internal val ANTI_HALLUCINATION_RULES: List<String> = listOf(
    "Strict anti-hallucination & factual accuracy policy (applies to every AI provider and model):",
    "- Never fabricate, guess, or hallucinate file paths, class/function/symbol names, APIs, library versions, command outputs, or test/build results.",
    "- Always read and inspect the actual workspace files before referencing, editing, or explaining them.",
    "- Never claim a build, test, command, or fix succeeded unless you actually executed it with a tool and verified its exit code and output.",
    "- If a file, symbol, dependency, or tool is missing, or if an operation fails, report the exact factual state or error honestly instead of inventing a workaround or pretending it worked.",
    "Universal Claude-Grade Execution Protocol (CORE-EXEC-001..005 — append/upgrade-only, never delete):",
    "- [CORE-EXEC-001] Single-Step Command Chaining: Combine related search, read, and git checks in a single command (`&&` / `;`) to target ~3 tool calls per turn and <10s response time.",
    "- [CORE-EXEC-002] Deterministic Batch Scripting & Stream Filtering: Use inline `python3 -I - <<'EOF'` for multi-file or structural edits and keep code files <= 1000 lines; always bound shell output with `head`/`tail`/`cut` to protect context cache.",
    "- [CORE-EXEC-003] Non-Blocking Daemon & PTY Freeze Guard: Use `keep-running <log> <cmd>` or `timeout --foreground -k 10s ... < /dev/null` with `--offline --configuration-cache` so background PTY tasks never freeze (`SIGTTIN`) or get killed.",
    "- [CORE-EXEC-004] Prompt Cache & Adaptive Low-Effort Thinking: Keep reasoning minimal and direct, avoid repeating unchanged file dumps, and preserve context cache efficiency.",
    "- [CORE-EXEC-005] Self-Healing & Factual Honesty: On any tool/test failure, inspect `git status`/`git diff` and the exact stack trace, apply a surgical fix, verify `exit=0`, and report committed vs uncommitted state honestly.",
)

internal fun buildAgentWorkspacePrompt(
    currentPrompt: String,
    priorMessages: List<ChatMessage>,
    guestWorkspacePath: String,
    projectKind: ProjectKind,
    androidStackInstalled: Boolean,
    maxHistoryChars: Int,
    resumed: Boolean = false,
): String {
    val trimmedHistory = priorMessages.recentWithinCharacterBudget(maxHistoryChars)
    return buildString {
        appendLine("<project_workspace>")
        if (projectKind == ProjectKind.QUICK_PROJECT) {
            appendLine("This is a lightweight project workspace at $guestWorkspacePath.")
            appendLine("Respond conversationally, and use terminal or file tools whenever they are useful for the request.")
            appendLine("Keep every file and command inside this project workspace.")
        } else {
            appendLine("The current working directory $guestWorkspacePath is the project root.")
            appendLine("Create and edit project files directly in this directory. Do not create another outer project folder unless the user explicitly asks for one.")
            appendLine("When giving commands to the user, make them runnable from this project root.")
        }
        if (androidStackInstalled) {
            for (line in AndroidToolchain.AGENT_PROMPT_LINES) appendLine(line)
        } else {
            appendLine("The optional Android build toolchain is not installed in this NextCraft Forge runtime. You may create Android project files, but do not claim that Gradle, the Android SDK, or aapt2 is available and do not present build or install commands as verified. Tell the user to add the Android development stack in NextCraft Forge Settings before building.")
        }
        appendLine("For local servers, give a clear start command and never use a kill command that searches its own command text with pgrep, because it can terminate the terminal itself.")
        for (rule in ANTI_HALLUCINATION_RULES) appendLine(rule)
        appendLine("</project_workspace>")
        appendLine()
        appendConversationHistory(trimmedHistory, guestWorkspacePath, resumed)
        appendLine(currentPrompt)
    }
}

object RuntimeLaunchConfigBuilder {
    private val CLAUDE_STREAM_ARGS = listOf(
        "-p",
        "--input-format",
        "stream-json",
        "--output-format",
        "stream-json",
        "--verbose",
    )

    fun build(
        profile: ProviderProfile,
        authToken: String? = null,
        localGatewayUrl: String? = null,
        useStoredLogin: Boolean = false,
        claudeEffort: String = "",
    ): RuntimeLaunchConfig {
        val secret = authToken?.trim().orEmpty()
        val isOat = secret.startsWith("sk-ant-oat")
        val isAntApi = secret.startsWith("sk-ant-api")
        val oauthMode = isOat || (profile.kind.protocol == ProviderProtocol.CLAUDE_LOGIN && !isAntApi)

        val env = linkedMapOf(
            "DISABLE_AUTOUPDATER" to "1",
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" to "1",
            "DISABLE_TELEMETRY" to "1",
            "DISABLE_ERROR_REPORTING" to "1",
        )

        when (profile.kind.protocol) {
            ProviderProtocol.CLAUDE_LOGIN -> {
                require(secret.isNotBlank() || useStoredLogin) {
                    "Sign in to Claude or enter a subscription token first"
                }
                when {
                    useStoredLogin && secret.isBlank() -> {
                        env["ANTHROPIC_API_KEY"] = ""
                        env["ANTHROPIC_AUTH_TOKEN"] = ""
                        env["CLAUDE_CODE_OAUTH_TOKEN"] = ""
                    }
                    isAntApi -> {
                        env["ANTHROPIC_BASE_URL"] = ProviderKind.ANTHROPIC.defaultBaseUrl
                        val chosenModel = profile.model.takeUnless { it.isBlank() || it == "default" }
                            ?: ProviderKind.ANTHROPIC.defaultModel
                        env["ANTHROPIC_MODEL"] = chosenModel
                    }
                    else -> {
                        env["CLAUDE_CODE_OAUTH_TOKEN"] = secret
                        env["ANTHROPIC_API_KEY"] = ""
                        env["ANTHROPIC_AUTH_TOKEN"] = ""
                    }
                }
            }
            ProviderProtocol.ANTHROPIC,
            ProviderProtocol.ANTHROPIC_GATEWAY,
            -> {
                env["ANTHROPIC_BASE_URL"] = profile.resolvedBaseUrl.trim().trimEnd('/').removeSuffix("/v1")
                env["ANTHROPIC_MODEL"] = profile.model
            }
            ProviderProtocol.OPENROUTER -> {
                val base = (localGatewayUrl ?: profile.resolvedBaseUrl).trim().trimEnd('/').removeSuffix("/v1")
                env["ANTHROPIC_BASE_URL"] = base
                env["ANTHROPIC_MODEL"] = profile.model
            }
            ProviderProtocol.OPENAI_RESPONSES,
            ProviderProtocol.OPENAI_CHAT,
            -> {
                require(!localGatewayUrl.isNullOrBlank()) { "A local format gateway is required for this provider" }
                env["ANTHROPIC_BASE_URL"] = localGatewayUrl.trimEnd('/')
                env["ANTHROPIC_MODEL"] = "claude-sonnet-4-6"
            }
        }

        val normalizedEffort = claudeEffort.trim().lowercase()
        if (normalizedEffort in CLAUDE_EFFORT_LEVELS) {
            env["CLAUDE_CODE_EFFORT_LEVEL"] = normalizedEffort
        }

        val activeModel = env["ANTHROPIC_MODEL"] ?: profile.model
        if (oauthMode) {
            if (secret.isNotBlank()) {
                env["CLAUDE_CODE_OAUTH_TOKEN"] = secret
                env["ANTHROPIC_API_KEY"] = ""
                env["ANTHROPIC_AUTH_TOKEN"] = ""
            }
        } else {
            env["ANTHROPIC_DEFAULT_OPUS_MODEL"] = activeModel
            env["ANTHROPIC_DEFAULT_SONNET_MODEL"] = activeModel
            env["ANTHROPIC_DEFAULT_HAIKU_MODEL"] = activeModel
            env["ANTHROPIC_SMALL_MODEL"] = activeModel
            env["ANTHROPIC_FAST_MODEL"] = activeModel
            env["CLAUDE_CODE_SUBAGENT_MODEL"] = activeModel
            env["CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY"] = "1"
            env["CLAUDE_CODE_DISABLE_TOKEN_COUNTING"] = "1"
            env["DISABLE_TELEMETRY"] = "1"

            if (secret.isNotBlank()) {
                env["CLAUDE_CODE_OAUTH_TOKEN"] = ""
                val targetsFirstPartyAnthropic = profile.kind.protocol == ProviderProtocol.ANTHROPIC ||
                    profile.kind.protocol == ProviderProtocol.CLAUDE_LOGIN ||
                    isAntApi ||
                    env["ANTHROPIC_BASE_URL"].orEmpty().contains("api.anthropic.com", ignoreCase = true)

                when {
                    profile.kind == ProviderKind.LLM_ROUTER || profile.kind.protocol == ProviderProtocol.OPENROUTER -> {
                        env["ANTHROPIC_AUTH_TOKEN"] = secret
                        env["ANTHROPIC_API_KEY"] = ""
                        env["OPENROUTER_API_KEY"] = secret
                    }
                    targetsFirstPartyAnthropic ||
                        profile.kind.protocol == ProviderProtocol.OPENAI_CHAT ||
                        profile.kind.protocol == ProviderProtocol.OPENAI_RESPONSES -> {
                        env["ANTHROPIC_API_KEY"] = secret
                        env["ANTHROPIC_AUTH_TOKEN"] = ""
                    }
                    else -> {
                        env["ANTHROPIC_AUTH_TOKEN"] = secret
                        env["ANTHROPIC_API_KEY"] = secret
                    }
                }
            }
        }

        return RuntimeLaunchConfig(
            executable = "/usr/local/bin/claude",
            arguments = CLAUDE_STREAM_ARGS,
            environment = env,
        )
    }
}

internal data class SavedConversation(
    val id: String,
    val messageCount: Int = -1,
    val tag: String = "",
) {
    fun encode(): String = "$id|$messageCount|$tag"

    companion object {
        fun decode(raw: String?): SavedConversation? {
            if (raw.isNullOrBlank()) return null
            val tokens = raw.split('|', limit = 3)
            val convId = tokens[0]
            if (convId.isBlank()) return null
            val seenCount = tokens.getOrNull(1)?.toIntOrNull() ?: -1
            val tagStr = tokens.getOrNull(2).orEmpty()
            return SavedConversation(convId, seenCount, tagStr)
        }
    }
}

internal fun List<ChatMessage>.usefulForAgent(): List<ChatMessage> = filter { m ->
    val t = m.text
    (m.fromUser || !t.startsWith("Hi! Tell me")) &&
        !t.startsWith("Failed to") &&
        !t.startsWith("Error:") &&
        !t.contains("API Error")
}

internal fun List<ChatMessage>.unseenBy(saved: SavedConversation?): List<ChatMessage> {
    val historyBeforeCurrent = dropLast(1)
    val slice = when {
        saved == null -> historyBeforeCurrent
        saved.messageCount < 0 || saved.messageCount >= historyBeforeCurrent.size -> emptyList()
        else -> historyBeforeCurrent.drop(saved.messageCount)
    }
    return slice.usefulForAgent()
}

internal fun StringBuilder.appendConversationHistory(
    messages: List<ChatMessage>,
    guestWorkspacePath: String,
    resumed: Boolean,
) {
    if (messages.isEmpty()) return
    appendLine("<conversation_history>")
    val headerNote = if (resumed) {
        "These messages were exchanged in this chat with another assistant since your last reply. Take them into account."
    } else {
        "The following is our prior conversation in this project. Continue naturally from where we left off."
    }
    appendLine(headerNote)
    appendLine()
    for (m in messages) {
        val speaker = if (m.fromUser) "User" else "Assistant"
        appendLine("$speaker: ${m.text}")
        if (m.attachments.isNotEmpty()) {
            appendLine("Attached files:")
            for (att in m.attachments) {
                appendLine("- ${att.displayName}: $guestWorkspacePath/${att.relativePath} (${att.mimeType})")
            }
        }
        appendLine()
    }
    appendLine("</conversation_history>")
    appendLine()
    appendLine("Now, respond to this new message from the user:")
}
