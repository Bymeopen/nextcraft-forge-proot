package com.nextcraft.forge.model

import java.util.Locale

enum class ProviderProtocol {
    CLAUDE_LOGIN,
    ANTHROPIC,
    ANTHROPIC_GATEWAY,
    OPENROUTER,
    OPENAI_RESPONSES,
    OPENAI_CHAT,
}

enum class ProviderKind(
    val title: String,
    val subtitle: String,
    val protocol: ProviderProtocol,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val experimental: Boolean,
    val fixedBaseUrl: Boolean,
    val fixedProtocol: Boolean,
) {
    CLAUDE(
        "Claude subscription", "Pro, Max, Team or Enterprise",
        ProviderProtocol.CLAUDE_LOGIN, "", "default",
        false, false, false,
    ),
    ANTHROPIC(
        "Anthropic API", "Usage billed through Console",
        ProviderProtocol.ANTHROPIC, "https://api.anthropic.com", "claude-sonnet-4-6",
        false, false, false,
    ),
    LLM_ROUTER(
        "OpenRouter", "Use your OpenRouter API key",
        ProviderProtocol.OPENROUTER, "https://openrouter.ai/api", "~anthropic/claude-sonnet-latest",
        false, false, false,
    ),
    DEEPSEEK(
        "DeepSeek", "Use your DeepSeek API key",
        ProviderProtocol.ANTHROPIC_GATEWAY, "https://api.deepseek.com/anthropic", "deepseek-v4-flash",
        false, false, false,
    ),
    KIMI(
        "Kimi", "Anthropic-compatible endpoint",
        ProviderProtocol.ANTHROPIC_GATEWAY, "https://api.moonshot.ai/anthropic", "kimi-k2.6",
        true, false, false,
    ),
    OPENCODE_ZEN(
        "OpenCode Zen", "Models through the OpenCode Zen gateway",
        ProviderProtocol.OPENAI_RESPONSES, "https://opencode.ai/zen/v1", "deepseek-v4-flash",
        false, true, true,
    ),
    NVIDIA_NIM(
        "NVIDIA NIM", "OpenAI-compatible models hosted by NVIDIA",
        ProviderProtocol.OPENAI_CHAT, "https://integrate.api.nvidia.com/v1", "qwen/qwen2.5-coder-32b-instruct",
        false, true, true,
    ),
    CUSTOM(
        "Custom API", "Anthropic-compatible endpoint",
        ProviderProtocol.ANTHROPIC_GATEWAY, "", "",
        true, false, false,
    ),
}

enum class AgentKind(
    val stableId: String,
    val title: String,
    val subtitle: String,
    val downloadNote: String,
) {
    CLAUDE_CODE("claude-code", "Claude Code", "Anthropic's coding agent · broad provider support", "71.9 MB"),
    DEEPSEEK_HARNESS("deepseek-harness", "DeepSeek", "Official DeepSeek coding agent · API-key providers", "26.8 MB"),
    ANTIGRAVITY("antigravity", "Antigravity CLI", "Google's official coding agent · Google account", "40.1 MB"),
    ;

    companion object {
        private val indexByToken: Map<String, AgentKind> = buildMap {
            for (agent in AgentKind.entries) {
                put(agent.stableId, agent)
                put(agent.name, agent)
            }
        }

        fun fromStoredOrNull(raw: String?): AgentKind? = raw?.let(indexByToken::get)

        fun fromStored(raw: String?): AgentKind = fromStoredOrNull(raw) ?: CLAUDE_CODE
    }
}

val DEEPSEEK_HARNESS_PROVIDERS: Set<ProviderKind> =
    enumValues<ProviderKind>().filterTo(linkedSetOf()) { it != ProviderKind.CLAUDE }

val DSH_PROTOCOL_PROVIDERS: Set<ProviderKind> =
    setOf(ProviderKind.KIMI, ProviderKind.OPENCODE_ZEN, ProviderKind.NVIDIA_NIM, ProviderKind.CUSTOM)

private const val WIRE_RESPONSES = "openai-responses"
private const val WIRE_COMPLETIONS = "openai-completions"
private const val WIRE_ANTHROPIC = "anthropic-messages"

fun defaultDshApiForProvider(provider: ProviderKind): String =
    when (provider) {
        ProviderKind.OPENCODE_ZEN -> WIRE_RESPONSES
        ProviderKind.NVIDIA_NIM -> WIRE_COMPLETIONS
        else -> WIRE_ANTHROPIC
    }

fun inferredDshApiForUrl(endpointUrl: String): String {
    val clean = endpointUrl.trim().trimEnd('/').lowercase(Locale.ROOT)
    return when {
        clean.endsWith("/responses") -> WIRE_RESPONSES
        clean.contains("/anthropic") || clean.contains("api.anthropic.com") -> WIRE_ANTHROPIC
        clean.endsWith("/v1") -> WIRE_COMPLETIONS
        else -> WIRE_ANTHROPIC
    }
}

fun providerProtocolForAgent(cfg: ProviderProfile, harness: AgentKind): ProviderProtocol {
    val customWire = harness == AgentKind.DEEPSEEK_HARNESS && DSH_PROTOCOL_PROVIDERS.contains(cfg.kind)
    if (!customWire) return cfg.kind.protocol
    val activeWire = if (cfg.kind.fixedProtocol) defaultDshApiForProvider(cfg.kind) else cfg.dshApi
    return when (activeWire) {
        WIRE_COMPLETIONS -> ProviderProtocol.OPENAI_CHAT
        WIRE_RESPONSES -> ProviderProtocol.OPENAI_RESPONSES
        else -> ProviderProtocol.ANTHROPIC_GATEWAY
    }
}

fun providersForAgent(harness: AgentKind): List<ProviderKind> =
    when (harness) {
        AgentKind.DEEPSEEK_HARNESS -> DEEPSEEK_HARNESS_PROVIDERS.toList()
        AgentKind.CLAUDE_CODE -> ProviderKind.entries.filter { it != ProviderKind.OPENCODE_ZEN }
        AgentKind.ANTIGRAVITY -> emptyList()
    }

data class ProviderProfile(
    val kind: ProviderKind,
    val baseUrl: String = kind.defaultBaseUrl,
    val model: String = kind.defaultModel,
    val hasSecret: Boolean = false,
    val dshApi: String = defaultDshApiForProvider(kind),
    val openRouterProviderOrder: String = "",
    val openRouterAllowFallbacks: Boolean = true,
) {
    val resolvedBaseUrl: String
        get() = if (kind.fixedBaseUrl) kind.defaultBaseUrl else baseUrl.trim().ifEmpty { kind.defaultBaseUrl }

    val openRouterProviders: List<String>
        get() = openRouterProviderOrder
            .splitToSequence(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
}
