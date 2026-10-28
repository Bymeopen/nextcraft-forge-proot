package com.nextcraft.forge.runtime

import android.content.Context
import android.util.Log
import com.nextcraft.forge.model.stringOrEmpty
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.model.ProjectKind
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.ProviderProtocol
import com.nextcraft.forge.model.RiskLevel
import com.nextcraft.forge.model.RuntimeEvent
import com.nextcraft.forge.model.ToolRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

internal object ProviderRuntimeErrorDetector {
    fun detect(line: String): String? {
        val trimmed = line.trim()
        val json = runCatching { JSONObject(trimmed) }.getOrNull()
        // Structured stream-json lines carry assistant text and tool output. Scanning those
        // would abort the session whenever a file or answer merely mentions "quota" or
        // "rate limit". Only error-bearing fields are inspected; raw (non-JSON) lines are
        // runtime stderr and are scanned whole, except truncated stream-json lines.
        if (json == null && trimmed.startsWith("{")) {
            return null
        }
        val combined = if (json == null) {
            trimmed.lowercase()
        } else buildString {
            append(json.stringOrEmpty("error"))
            if (json.stringOrEmpty("type") == "error") {
                append(' ')
                append(json.stringOrEmpty("message"))
                json.optJSONObject("error")?.let { errObj ->
                    append(' ')
                    append(errObj.stringOrEmpty("type"))
                    append(' ')
                    append(errObj.stringOrEmpty("message"))
                }
            }
            if (json.stringOrEmpty("type") == "result" && json.optBoolean("is_error")) {
                append(' ')
                append(json.stringOrEmpty("result"))
            }
        }.lowercase()
        return when {
            "user not found" in combined -> "User not found. Check the API key and provider account."
            "authentication_failed" in combined ||
                "authentication failed" in combined ||
                "authentication_error" in combined ||
                "invalid bearer token" in combined ||
                "oauth access token is invalid" in combined ||
                "oauth token has been revoked" in combined ||
                "invalid api key" in combined ||
                "invalid_api_key" in combined ||
                "invalid x-api-key" in combined ||
                "oauth token has expired" in combined ||
                "token has expired" in combined ||
                "api key expired" in combined ||
                "not logged in" in combined ||
                "api error: 401" in combined ||
                "http 401" in combined ||
                "status 401" in combined ||
                "401 unauthorized" in combined ||
                (json?.stringOrEmpty("subtype") == "api_retry" && json.optInt("error_status") == 401) ->
                "The provider rejected the saved API key."
            else -> null
        }
    }
}
