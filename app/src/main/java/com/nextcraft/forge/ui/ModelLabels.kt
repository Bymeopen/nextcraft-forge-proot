@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.nextcraft.forge.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.nextcraft.forge.R
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.model.Project
import com.nextcraft.forge.model.ProjectChat
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.runtime.AndroidToolchain
import com.nextcraft.forge.runtime.RuntimeEngineChoice

// Product and brand names stay as written; only descriptive text goes through resources.

fun ProviderKind.displayTitle(): String = when (this) {
    ProviderKind.CLAUDE -> "Claude Subscription (OAuth)"
    ProviderKind.ANTHROPIC -> "Anthropic API"
    ProviderKind.LLM_ROUTER -> "OpenRouter"
    ProviderKind.DEEPSEEK -> "DeepSeek API"
    ProviderKind.KIMI -> "Moonshot Kimi"
    ProviderKind.OPENCODE_ZEN -> "OpenCode Zen"
    ProviderKind.NVIDIA_NIM -> "NVIDIA NIM"
    ProviderKind.CUSTOM -> "Custom Endpoint"
}

@StringRes
fun ProviderKind.subtitleRes(): Int = when (this) {
    ProviderKind.CLAUDE -> R.string.provider_claude_sub
    ProviderKind.ANTHROPIC -> R.string.provider_anthropic_sub
    ProviderKind.LLM_ROUTER -> R.string.provider_router_sub
    ProviderKind.DEEPSEEK -> R.string.provider_deepseek_sub
    ProviderKind.KIMI -> R.string.provider_kimi_sub
    ProviderKind.OPENCODE_ZEN -> R.string.provider_zen_sub
    ProviderKind.NVIDIA_NIM -> R.string.provider_nim_sub
    ProviderKind.CUSTOM -> R.string.provider_custom_sub
}

@Composable
fun ProviderKind.displaySubtitle(): String = stringResource(subtitleRes())

fun AgentKind.displayTitle(): String = when (this) {
    AgentKind.CLAUDE_CODE -> "Claude Code"
    AgentKind.DEEPSEEK_HARNESS -> "DeepSeek"
    AgentKind.ANTIGRAVITY -> "Antigravity CLI"
}

/** Short name for segmented controls, where three labels share one row and "Antigravity CLI" does not fit. */
fun AgentKind.shortTitle(): String = when (this) {
    AgentKind.CLAUDE_CODE -> "Claude"
    AgentKind.DEEPSEEK_HARNESS -> "DeepSeek"
    AgentKind.ANTIGRAVITY -> "Antigravity"
}

@StringRes
fun AgentKind.subtitleRes(): Int = when (this) {
    AgentKind.CLAUDE_CODE -> R.string.agent_claude_sub
    AgentKind.DEEPSEEK_HARNESS -> R.string.agent_deepseek_sub
    AgentKind.ANTIGRAVITY -> R.string.agent_antigravity_sub
}

@Composable
fun AgentKind.displaySubtitle(): String = stringResource(subtitleRes())

@StringRes
fun DevStack.labelRes(): Int = when (this) {
    DevStack.WEB -> R.string.stack_web
    DevStack.PYTHON -> R.string.stack_python
    DevStack.ANDROID -> R.string.stack_android
    DevStack.CPP -> R.string.stack_cpp
    DevStack.PHP -> R.string.stack_php
}

@StringRes
fun DevStack.descriptionRes(): Int = when (this) {
    DevStack.WEB -> R.string.stack_web_desc
    DevStack.PYTHON -> R.string.stack_python_desc
    DevStack.ANDROID -> R.string.stack_android_desc
    DevStack.CPP -> R.string.stack_cpp_desc
    DevStack.PHP -> R.string.stack_php_desc
}

@Composable
fun DevStack.displayLabel(): String = stringResource(labelRes())

@Composable
fun DevStack.displayDescription(): String = when (this) {
    // Versions come from the toolchain constants so the label never drifts from the bundle.
    DevStack.ANDROID -> stringResource(
        descriptionRes(),
        AndroidToolchain.JDK_MAJOR,
        AndroidToolchain.GRADLE_VERSION,
        AndroidToolchain.COMPILE_SDK,
    )
    else -> stringResource(descriptionRes())
}

@StringRes
fun RuntimeEngineChoice.titleRes(): Int = when (this) {
    RuntimeEngineChoice.AUTO -> R.string.engine_auto
    RuntimeEngineChoice.CHROOT -> R.string.engine_chroot
    RuntimeEngineChoice.PROOT -> R.string.engine_proot
}

@StringRes
fun RuntimeEngineChoice.subtitleRes(): Int = when (this) {
    RuntimeEngineChoice.AUTO -> R.string.engine_auto_sub
    RuntimeEngineChoice.CHROOT -> R.string.engine_chroot_sub
    RuntimeEngineChoice.PROOT -> R.string.engine_proot_sub
}

/** "3m ago" style text for list rows; plurals come from resources so each locale decides the form. */
@Composable
fun Project.relativeUpdatedAt(nowMillis: Long = System.currentTimeMillis()): String =
    relativeTime(updatedAtMillis, nowMillis, formattedUpdatedAt)

/** Same wording for any timestamp (chat rows); past a week it shows the date. */
@Composable
fun formatRelativeTime(atMillis: Long, nowMillis: Long = System.currentTimeMillis()): String =
    relativeTime(atMillis, nowMillis, java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(atMillis)))

@Composable
private fun relativeTime(atMillis: Long, nowMillis: Long, fallback: String): String {
    val diff = (nowMillis - atMillis).coerceAtLeast(0L)
    val minutes = (diff / 60_000L).toInt()
    val hours = minutes / 60
    val days = hours / 24
    return when {
        diff < 60_000L -> stringResource(R.string.time_just_now)
        minutes < 60 -> pluralStringResource(R.plurals.time_minutes_ago, minutes, minutes)
        hours < 24 -> pluralStringResource(R.plurals.time_hours_ago, hours, hours)
        days == 1 -> stringResource(R.string.time_yesterday)
        days < 7 -> pluralStringResource(R.plurals.time_days_ago, days, days)
        else -> fallback
    }
}

@Composable
fun ProjectChat.displayTitle(): String =
    // "Main chat" is the English default stored in older data; show it in the app language.
    if (title.isBlank() || title == "Main chat") stringResource(R.string.chat_main) else title
