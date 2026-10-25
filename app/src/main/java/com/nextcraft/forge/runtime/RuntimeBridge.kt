package com.nextcraft.forge.runtime

import android.content.Context
import android.content.Intent
import android.util.Log
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

/**
 * Shared cancellation hook so the foreground notification or UI can stop an active
 * coding agent session in ClaudeRuntimeBridge, AntigravityRuntimeBridge, or DshRuntimeBridge.
 */
object RuntimeTaskController {
    const val AGENT = "agent"
    const val BUILD = "build"

    /**
     * Stop hooks by task kind. The agent and the Android build can run at the same time; a single slot let one
     * overwrite the other's hook, and stopping the agent also killed the build.
     */
    private val hooks = LinkedHashMap<String, () -> Unit>()

    @Synchronized
    fun register(kind: String, action: () -> Unit) {
        hooks[kind] = action
    }

    /** Clears [action] only if it is still registered, so a finishing task cannot drop another task's stop hook. */
    @Synchronized
    fun release(action: (() -> Unit)?) {
        if (action != null) hooks.entries.removeAll { it.value === action }
    }

    /** True while any agent session or build holds a stop hook, i.e. while work is running. */
    @Synchronized
    fun hasActiveTask(): Boolean = hooks.isNotEmpty()

    /** Stops only the task of [kind]. */
    fun stop(kind: String) {
        // Parallel chats register as "agent:<sessionId>"; stopping the kind stops all of them.
        val actions = synchronized(this) {
            val keys = hooks.keys.filter { it == kind || it.startsWith("$kind:") }
            keys.mapNotNull { hooks.remove(it) }
        }
        actions.forEach { it() }
    }

    /** Stops every running task (the shared notification's Stop action, app teardown). */
    fun requestStop() {
        val actions = synchronized(this) { hooks.values.toList().also { hooks.clear() } }
        actions.forEach { it() }
    }
}

/**
 * Contract and helper dispatcher for RuntimeExecutionService so bridges in Block 3
 * can post foreground notifications without circular compile-time dependencies.
 */
object RuntimeExecutionServiceContract {
    const val SERVICE_CLASS_NAME = "com.nextcraft.forge.runtime.RuntimeExecutionService"
    const val ACTION_START = "com.nextcraft.forge.runtime.action.START"
    const val ACTION_PROGRESS = "com.nextcraft.forge.runtime.action.PROGRESS"
    const val ACTION_COMPLETE = "com.nextcraft.forge.runtime.action.COMPLETE"
    const val ACTION_FAILED = "com.nextcraft.forge.runtime.action.FAILED"
    const val ACTION_CANCELLED = "com.nextcraft.forge.runtime.action.CANCELLED"
    const val EXTRA_PROJECT_NAME = "com.nextcraft.forge.runtime.extra.PROJECT_NAME"
    const val EXTRA_DETAIL = "com.nextcraft.forge.runtime.extra.DETAIL"
    /** Fixed phrases travel as a string resource id plus format args, so the service words them in the app language. */
    const val EXTRA_DETAIL_RES = "com.nextcraft.forge.runtime.extra.DETAIL_RES"
    const val EXTRA_DETAIL_ARGS = "com.nextcraft.forge.runtime.extra.DETAIL_ARGS"

    private fun resolveServiceClass(): Class<*>? =
        runCatching { Class.forName(SERVICE_CLASS_NAME) }.getOrNull()

    fun startForeground(context: Context, projectName: String) {
        val cls = resolveServiceClass() ?: return
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, cls)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_PROJECT_NAME, projectName),
            )
        }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeBridge", it) }
    }

    fun pushProgress(context: Context, projectName: String?, detail: String) {
        val cls = resolveServiceClass() ?: return
        runCatching {
            context.startService(
                Intent(context, cls)
                    .setAction(ACTION_PROGRESS)
                    .putExtra(EXTRA_PROJECT_NAME, projectName)
                    .putExtra(EXTRA_DETAIL, detail),
            )
        }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeBridge", it) }
    }

    fun finishForeground(context: Context, completed: Boolean, projectName: String, detail: String, tag: String = "RuntimeBridge") {
        val cls = resolveServiceClass() ?: return
        runCatching {
            context.startService(
                Intent(context, cls)
                    .setAction(if (completed) ACTION_COMPLETE else ACTION_FAILED)
                    .putExtra(EXTRA_PROJECT_NAME, projectName)
                    .putExtra(EXTRA_DETAIL, detail),
            )
        }.onFailure { error ->
            Log.w(tag, "Could not post task result notification", error)
            runCatching { context.stopService(Intent(context, cls)) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeBridge", it) }
        }
    }

    fun finishForegroundRes(context: Context, completed: Boolean, projectName: String, @androidx.annotation.StringRes detailRes: Int, args: Array<String>, tag: String = "RuntimeBridge") {
        val cls = resolveServiceClass() ?: return
        runCatching {
            context.startService(
                Intent(context, cls)
                    .setAction(if (completed) ACTION_COMPLETE else ACTION_FAILED)
                    .putExtra(EXTRA_PROJECT_NAME, projectName)
                    .putExtra(EXTRA_DETAIL_RES, detailRes)
                    .putExtra(EXTRA_DETAIL_ARGS, args),
            )
        }.onFailure { error ->
            Log.w(tag, "Could not post task result notification", error)
            runCatching { context.stopService(Intent(context, cls)) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeBridge", it) }
        }
    }

    fun cancelForeground(context: Context) {
        val cls = resolveServiceClass() ?: return
        runCatching {
            context.startService(Intent(context, cls).setAction(ACTION_CANCELLED))
        }.onFailure {
            runCatching { context.stopService(Intent(context, cls)) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeBridge", it) }
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

/** Keeps recent context while preventing an old chat from becoming an unbounded prompt allocation. */
internal fun List<ChatMessage>.recentWithinCharacterBudget(maxCharacters: Int): List<ChatMessage> {
    if (maxCharacters <= 0 || isEmpty()) return emptyList()
    var remaining = maxCharacters
    val selected = ArrayDeque<ChatMessage>()
    for (message in asReversed()) {
        if (remaining <= 0) break
        val text = if (message.text.length <= remaining) message.text else message.text.takeLast(remaining)
        selected.addFirst(message.copy(text = text))
        remaining -= text.length
        if (text.length < message.text.length) break
    }
    return selected.toList()
}

/**
 * Self-hosted Custom API servers (LM Studio, vLLM, Ollama, Local Gateway…) often need no key,
 * but the agent CLIs refuse to start without one. Send a harmless placeholder that such servers ignore.
 */
internal fun localServerPlaceholderKey(profile: ProviderProfile): String =
    if (profile.kind == ProviderKind.CUSTOM) "no-key-required" else ""

/** Effort levels Claude Code accepts in CLAUDE_CODE_EFFORT_LEVEL. */
val CLAUDE_EFFORT_LEVELS = listOf("low", "medium", "high", "xhigh", "max")

/**
 * Shared factual-accuracy and anti-hallucination rules injected into every coding agent and AI provider prompt
 * (Claude Code, Antigravity CLI, DeepSeek Harness, Anthropic API, OpenRouter, Kimi, OpenCode Zen, NVIDIA NIM, Custom API).
 */
internal val ANTI_HALLUCINATION_RULES: List<String> = listOf(
    "Strict anti-hallucination & factual accuracy policy (applies to every AI provider and model):",
    "- Never fabricate, guess, or hallucinate file paths, class/function/symbol names, APIs, library versions, command outputs, or test/build results.",
    "- Always read and inspect the actual workspace files before referencing, editing, or explaining them.",
    "- Never claim a build, test, command, or fix succeeded unless you actually executed it with a tool and verified its exit code and output.",
    "- If a file, symbol, dependency, or tool is missing, or if an operation fails, report the exact factual state or error honestly instead of inventing a workaround or pretending it worked.",
)

internal fun buildAgentWorkspacePrompt(
    currentPrompt: String,
    history: List<ChatMessage>,
    guestWorkspacePath: String,
    projectKind: ProjectKind,
    androidStackInstalled: Boolean,
    maxHistoryChars: Int,
): String {
    val priorMessages = history
        .filter { msg ->
            (msg.fromUser || !msg.text.startsWith("Hi! Tell me")) &&
                !msg.text.startsWith("Failed to") &&
                !msg.text.startsWith("Error:") &&
                !msg.text.contains("API Error")
        }
        .dropLast(1)
        .recentWithinCharacterBudget(maxHistoryChars)

    val sb = StringBuilder()
    sb.appendLine("<project_workspace>")
    if (projectKind == ProjectKind.QUICK_PROJECT) {
        sb.appendLine("This is a lightweight project workspace at $guestWorkspacePath.")
        sb.appendLine("Respond conversationally, and use terminal or file tools whenever they are useful for the request.")
        sb.appendLine("Keep every file and command inside this project workspace.")
    } else {
        sb.appendLine("The current working directory $guestWorkspacePath is the project root.")
        sb.appendLine("Create and edit project files directly in this directory. Do not create another outer project folder unless the user explicitly asks for one.")
        sb.appendLine("When giving commands to the user, make them runnable from this project root.")
    }
    if (androidStackInstalled) {
        AndroidToolchain.AGENT_PROMPT_LINES.forEach(sb::appendLine)
    } else {
        sb.appendLine("The optional Android build toolchain is not installed in this NextCraft Forge runtime. You may create Android project files, but do not claim that Gradle, the Android SDK, or aapt2 is available and do not present build or install commands as verified. Tell the user to add the Android development stack in NextCraft Forge Settings before building.")
    }
    sb.appendLine("For local servers, give a clear start command and never use a kill command that searches its own command text with pgrep, because it can terminate the terminal itself.")
    ANTI_HALLUCINATION_RULES.forEach(sb::appendLine)
    sb.appendLine("</project_workspace>")
    sb.appendLine()
    if (priorMessages.isEmpty()) {
        sb.appendLine(currentPrompt)
        return sb.toString()
    }
    sb.appendLine("<conversation_history>")
    sb.appendLine("The following is our prior conversation in this project. Continue naturally from where we left off.")
    sb.appendLine()
    for (msg in priorMessages) {
        val role = if (msg.fromUser) "User" else "Assistant"
        sb.appendLine("$role: ${msg.text}")
        if (msg.attachments.isNotEmpty()) {
            sb.appendLine("Attached files:")
            msg.attachments.forEach { attachment ->
                sb.appendLine("- ${attachment.displayName}: $guestWorkspacePath/${attachment.relativePath} (${attachment.mimeType})")
            }
        }
        sb.appendLine()
    }
    sb.appendLine("</conversation_history>")
    sb.appendLine()
    sb.appendLine("Now, respond to this new message from the user:")
    sb.appendLine(currentPrompt)
    return sb.toString()
}

object RuntimeLaunchConfigBuilder {
    fun build(
        profile: ProviderProfile,
        authToken: String? = null,
        localGatewayUrl: String? = null,
        /**
         * Claude subscription signed in with `claude auth login`: the CLI reads and refreshes its own credentials file,
         * so no token is passed. (A token in CLAUDE_CODE_OAUTH_TOKEN is never refreshed and expires within hours.)
         */
        useStoredLogin: Boolean = false,
        /** Claude Code effort level; blank or unknown values leave the CLI's default. */
        claudeEffort: String = "",
    ): RuntimeLaunchConfig {
        val cleanToken = authToken?.trim().orEmpty()
        val isClaudeOAuthToken = cleanToken.startsWith("sk-ant-oat")
        val isAnthropicApiKey = cleanToken.startsWith("sk-ant-api")
        val useClaudeOAuth = isClaudeOAuthToken ||
            (profile.kind.protocol == ProviderProtocol.CLAUDE_LOGIN && !isAnthropicApiKey)

        val environment = linkedMapOf(
            "DISABLE_AUTOUPDATER" to "1",
            // Skip telemetry, error reporting and other background network calls inside Chroot.
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" to "1",
            "DISABLE_TELEMETRY" to "1",
            "DISABLE_ERROR_REPORTING" to "1",
        )
        when (profile.kind.protocol) {
            ProviderProtocol.CLAUDE_LOGIN -> {
                require(cleanToken.isNotBlank() || useStoredLogin) { "Sign in to Claude or enter a subscription token first" }
                if (useStoredLogin && cleanToken.isBlank()) {
                    // Claude Code gives API-key variables precedence over the stored login; make sure none leaks in.
                    environment["ANTHROPIC_API_KEY"] = ""
                    environment["ANTHROPIC_AUTH_TOKEN"] = ""
                    environment["CLAUDE_CODE_OAUTH_TOKEN"] = ""
                } else if (isAnthropicApiKey) {
                    environment["ANTHROPIC_BASE_URL"] = ProviderKind.ANTHROPIC.defaultBaseUrl
                    environment["ANTHROPIC_MODEL"] = profile.model
                        .takeUnless { it.isBlank() || it == "default" }
                        ?: ProviderKind.ANTHROPIC.defaultModel
                } else {
                    environment["CLAUDE_CODE_OAUTH_TOKEN"] = cleanToken
                    // Claude Code gives API-key variables precedence over OAuth. Explicitly
                    // clear them so a previous API provider can never shadow this token.
                    environment["ANTHROPIC_API_KEY"] = ""
                    environment["ANTHROPIC_AUTH_TOKEN"] = ""
                }
            }
            ProviderProtocol.ANTHROPIC -> {
                // Claude Code appends /v1/messages itself; a base URL saved with /v1 would hit /v1/v1/messages.
                environment["ANTHROPIC_BASE_URL"] = profile.resolvedBaseUrl.trim().trimEnd('/').removeSuffix("/v1")
                environment["ANTHROPIC_MODEL"] = profile.model
            }
            ProviderProtocol.ANTHROPIC_GATEWAY -> {
                // Claude Code appends /v1/messages itself; accept base URLs entered with or
                // without /v1 so the agent hits the same endpoint as Test connection.
                environment["ANTHROPIC_BASE_URL"] = profile.resolvedBaseUrl.trim().trimEnd('/').removeSuffix("/v1")
                environment["ANTHROPIC_MODEL"] = profile.model
            }
            ProviderProtocol.OPENROUTER -> {
                environment["ANTHROPIC_BASE_URL"] = (localGatewayUrl ?: profile.resolvedBaseUrl).trim().trimEnd('/').removeSuffix("/v1")
                environment["ANTHROPIC_MODEL"] = profile.model
            }
            ProviderProtocol.OPENAI_RESPONSES,
            ProviderProtocol.OPENAI_CHAT,
            -> {
                require(!localGatewayUrl.isNullOrBlank()) { "A local format gateway is required for this provider" }
                environment["ANTHROPIC_BASE_URL"] = localGatewayUrl.trimEnd('/')
                environment["ANTHROPIC_MODEL"] = "claude-sonnet-4-6"
            }
        }
        claudeEffort.trim().lowercase().takeIf { it in CLAUDE_EFFORT_LEVELS }?.let { environment["CLAUDE_CODE_EFFORT_LEVEL"] = it }
        val runtimeModel = environment["ANTHROPIC_MODEL"] ?: profile.model
        if (useClaudeOAuth) {
            if (cleanToken.isNotBlank()) {
                environment["CLAUDE_CODE_OAUTH_TOKEN"] = cleanToken
                environment["ANTHROPIC_API_KEY"] = ""
                environment["ANTHROPIC_AUTH_TOKEN"] = ""
            }
        } else {
            environment["ANTHROPIC_DEFAULT_OPUS_MODEL"] = runtimeModel
            environment["ANTHROPIC_DEFAULT_SONNET_MODEL"] = runtimeModel
            environment["ANTHROPIC_DEFAULT_HAIKU_MODEL"] = runtimeModel
            environment["ANTHROPIC_SMALL_MODEL"] = runtimeModel
            environment["ANTHROPIC_FAST_MODEL"] = runtimeModel
            environment["CLAUDE_CODE_SUBAGENT_MODEL"] = runtimeModel
            environment["CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY"] = "1"
            environment["CLAUDE_CODE_DISABLE_TOKEN_COUNTING"] = "1"
            environment["DISABLE_TELEMETRY"] = "1"
            if (cleanToken.isNotBlank()) {
                environment["CLAUDE_CODE_OAUTH_TOKEN"] = ""
                val isFirstPartyAnthropic = profile.kind.protocol == ProviderProtocol.ANTHROPIC ||
                    profile.kind.protocol == ProviderProtocol.CLAUDE_LOGIN ||
                    isAnthropicApiKey ||
                    environment["ANTHROPIC_BASE_URL"].orEmpty().contains("api.anthropic.com", ignoreCase = true)
                when {
                    profile.kind == ProviderKind.LLM_ROUTER || profile.kind.protocol == ProviderProtocol.OPENROUTER -> {
                        environment["ANTHROPIC_AUTH_TOKEN"] = cleanToken
                        environment["ANTHROPIC_API_KEY"] = ""
                        environment["OPENROUTER_API_KEY"] = cleanToken
                    }
                    isFirstPartyAnthropic ||
                        profile.kind.protocol == ProviderProtocol.OPENAI_CHAT ||
                        profile.kind.protocol == ProviderProtocol.OPENAI_RESPONSES -> {
                        // First-party Anthropic API (and LocalFormatGateway) must use x-api-key only;
                        // setting ANTHROPIC_AUTH_TOKEN sends Authorization: Bearer <api-key>, which
                        // api.anthropic.com rejects with HTTP 401 "Invalid bearer token".
                        environment["ANTHROPIC_API_KEY"] = cleanToken
                        environment["ANTHROPIC_AUTH_TOKEN"] = ""
                    }
                    else -> {
                        environment["ANTHROPIC_AUTH_TOKEN"] = cleanToken
                        environment["ANTHROPIC_API_KEY"] = cleanToken
                    }
                }
            }
        }
        return RuntimeLaunchConfig(
            executable = "/usr/local/bin/claude",
            arguments = listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose"),
            environment = environment,
        )
    }
}
