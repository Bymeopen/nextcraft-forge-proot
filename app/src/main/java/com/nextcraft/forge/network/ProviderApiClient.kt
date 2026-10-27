package com.nextcraft.forge.network

import android.content.Context
import com.nextcraft.forge.model.ProviderProtocol
import com.nextcraft.forge.model.stringOrEmpty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class DiscoveredModel(
    val id: String,
    val displayName: String = id,
    val isFree: Boolean = false,
)

sealed interface ModelDiscoveryResult {
    data class Success(val models: List<DiscoveredModel>, val endpoint: String) : ModelDiscoveryResult
    data class Failure(val message: String, val providerMessage: String? = null) : ModelDiscoveryResult
}

sealed interface ConnectionValidation {
    data class Success(val message: String) : ConnectionValidation
    data class Failure(
        val message: String,
        val providerMessage: String? = null,
        val label: String = "Failed",
    ) : ConnectionValidation
}

class ProviderApiClient(@Suppress("UNUSED_PARAMETER") private val context: Context? = null) {

    suspend fun discoverModels(
        baseUrl: String,
        apiKey: String,
        protocol: ProviderProtocol,
    ): ModelDiscoveryResult = withContext(Dispatchers.IO) {
        val targetBase = resolveDefaultBase(baseUrl, protocol)
        if (targetBase.isBlank()) {
            return@withContext ModelDiscoveryResult.Failure("Enter a base URL first.")
        }

        var rejectedAuth = false
        var fallbackError = "This provider did not expose a model list. You can enter a custom model name."
        var upstreamDetail: String? = null

        for (candidateUri in buildDiscoveryUris(targetBase, protocol)) {
            val tokenToUse = if (isPublicOpenRouterCatalog(candidateUri)) "" else apiKey
            val resp = executeHttpCall(
                uri = candidateUri,
                verb = "GET",
                token = tokenToUse,
                wireProtocol = protocol,
            )
            when {
                resp.statusCode == 401 || resp.statusCode == 403 -> {
                    rejectedAuth = true
                    upstreamDetail = extractRedactedError(resp.payload)
                }
                resp.statusCode in 200..299 -> {
                    val catalog = ModelResponseParser.parse(resp.payload)
                    if (catalog.isNotEmpty()) {
                        return@withContext ModelDiscoveryResult.Success(catalog, candidateUri)
                    }
                    fallbackError = "The provider replied, but its model list was empty or unsupported."
                }
                resp.statusCode > 0 && resp.statusCode != 404 -> {
                    fallbackError = describeStatusFailure(resp.statusCode)
                    upstreamDetail = extractRedactedError(resp.payload)
                }
                resp.transportError != null -> fallbackError = resp.transportError
            }
        }

        val summary = if (rejectedAuth) {
            "Check the saved API key, then try refreshing again."
        } else {
            fallbackError
        }
        ModelDiscoveryResult.Failure(summary, upstreamDetail)
    }

    suspend fun validate(
        baseUrl: String,
        model: String,
        apiKey: String,
        protocol: ProviderProtocol,
        @Suppress("UNUSED_PARAMETER") discoveredModels: List<DiscoveredModel> = emptyList(),
        openRouterProviderOrder: String = "",
        openRouterAllowFallbacks: Boolean = true,
    ): ConnectionValidation = withContext(Dispatchers.IO) {
        val targetBase = resolveDefaultBase(baseUrl, protocol)
        val trimmedModel = model.trim()
        val usesAnthropicDefault = (protocol == ProviderProtocol.CLAUDE_LOGIN || protocol == ProviderProtocol.ANTHROPIC) &&
            (trimmedModel.isEmpty() || trimmedModel.equals("default", ignoreCase = true))
        val targetModel = if (usesAnthropicDefault) "claude-sonnet-4-6" else trimmedModel

        if (targetBase.isBlank() || targetModel.isBlank()) {
            return@withContext ConnectionValidation.Failure("Base URL, model, and API key are required.")
        }

        val probeUri = buildInferenceUri(targetBase, protocol)
        val probeJson = validationBody(targetModel, protocol, openRouterProviderOrder, openRouterAllowFallbacks)
        val resp = executeHttpCall(
            uri = probeUri,
            verb = "POST",
            token = apiKey,
            jsonBody = probeJson,
            wireProtocol = protocol,
            connectTimeoutMs = 12_000,
            readTimeoutMs = 45_000,
        )

        val errDetail = extractRedactedError(resp.payload)
        when {
            resp.statusCode in 200..299 -> ConnectionValidation.Success("Connection verified.")
            resp.statusCode == 401 || resp.statusCode == 403 -> ConnectionValidation.Failure(
                "Check this API key or select another saved key.",
                errDetail,
                "Rejected",
            )
            resp.statusCode == 404 -> ConnectionValidation.Failure(
                "Check the Base URL and selected gateway protocol.",
                errDetail,
                "Endpoint error",
            )
            resp.statusCode == 400 && resp.payload.contains("model", ignoreCase = true) -> ConnectionValidation.Failure(
                "Refresh the model list or select a different model.",
                errDetail,
                "Model error",
            )
            resp.statusCode == 429 -> ConnectionValidation.Failure(
                "Wait a moment, then retry or use another API key.",
                errDetail,
                "Rate limited",
            )
            resp.statusCode in 500..599 -> ConnectionValidation.Failure(
                "The provider is temporarily unavailable. Try again shortly.",
                errDetail,
                "Provider error",
            )
            resp.statusCode > 0 -> ConnectionValidation.Failure(
                "Review the model, protocol, and endpoint settings.",
                errDetail,
                "Request failed",
            )
            resp.transportError?.contains("timeout", ignoreCase = true) == true ||
                resp.transportError?.contains("timed out", ignoreCase = true) == true -> ConnectionValidation.Failure(
                "Check your connection and try again.",
                resp.transportError,
                "Timed out",
            )
            else -> ConnectionValidation.Failure(
                "Check your internet connection and provider settings.",
                resp.transportError,
                "Network error",
            )
        }
    }

    private fun resolveDefaultBase(rawUrl: String, protocol: ProviderProtocol): String {
        val clean = rawUrl.trim()
        return if (protocol == ProviderProtocol.CLAUDE_LOGIN && clean.isEmpty()) {
            "https://api.anthropic.com"
        } else {
            clean
        }
    }

    private fun executeHttpCall(
        uri: String,
        verb: String,
        token: String,
        jsonBody: String? = null,
        wireProtocol: ProviderProtocol,
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 20_000,
    ): TransportResponse {
        if (!LocalNetwork.isPlainHttpAllowed(uri)) {
            return TransportResponse(
                statusCode = 0,
                payload = "",
                transportError = "Plain http:// is only allowed for localhost. Use https:// for this server.",
            )
        }
        return runCatching {
            val parsedUrl = URL(uri)
            val conn = (parsedUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = verb
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                attachAuthHeaders(this, parsedUrl, uri, token.trim(), wireProtocol)
                if (uri.startsWith("https://opencode.ai/zen/")) {
                    setRequestProperty("User-Agent", "NextCraft-Forge-Android/1.0")
                    setRequestProperty("x-session-id", "session-${UUID.randomUUID()}")
                }
                if (jsonBody != null) doOutput = true
            }
            try {
                if (jsonBody != null) {
                    conn.outputStream.use { out -> out.write(jsonBody.encodeToByteArray()) }
                }
                val code = conn.responseCode
                val activeStream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = activeStream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val sb = StringBuilder()
                    val chunk = CharArray(8 * 1024)
                    while (sb.length < RESPONSE_CHAR_CAP) {
                        val maxRead = minOf(chunk.size, RESPONSE_CHAR_CAP - sb.length)
                        val n = reader.read(chunk, 0, maxRead)
                        if (n <= 0) break
                        sb.append(chunk, 0, n)
                    }
                    sb.toString()
                }.orEmpty()
                TransportResponse(code, text)
            } finally {
                conn.disconnect()
            }
        }.getOrElse { err ->
            TransportResponse(0, "", err.message ?: "Network connection failed")
        }
    }

    private fun attachAuthHeaders(
        conn: HttpURLConnection,
        parsedUrl: URL,
        rawUri: String,
        trimmedToken: String,
        protocol: ProviderProtocol,
    ) {
        val isOatToken = trimmedToken.startsWith("sk-ant-oat")
        val isAntApiKey = trimmedToken.startsWith("sk-ant-api")
        val targetsOfficialAnthropic = runCatching {
            parsedUrl.host.equals("api.anthropic.com", ignoreCase = true)
        }.getOrDefault(rawUri.contains("api.anthropic.com", ignoreCase = true))

        val isAnthropicFamily = protocol == ProviderProtocol.ANTHROPIC ||
            protocol == ProviderProtocol.CLAUDE_LOGIN ||
            protocol == ProviderProtocol.ANTHROPIC_GATEWAY

        if (trimmedToken.isEmpty()) {
            if (isAnthropicFamily) {
                conn.setRequestProperty("anthropic-version", ANTHROPIC_API_VERSION)
            }
            return
        }

        val sendOAuthHeaders = isOatToken || (protocol == ProviderProtocol.CLAUDE_LOGIN && !isAntApiKey)
        when {
            sendOAuthHeaders -> {
                conn.setRequestProperty("Authorization", "Bearer $trimmedToken")
                conn.setRequestProperty("anthropic-version", ANTHROPIC_API_VERSION)
                conn.setRequestProperty("anthropic-beta", "claude-code-20250219,oauth-2025-04-20")
            }
            targetsOfficialAnthropic || protocol == ProviderProtocol.ANTHROPIC || isAntApiKey -> {
                conn.setRequestProperty("x-api-key", trimmedToken)
                conn.setRequestProperty("anthropic-version", ANTHROPIC_API_VERSION)
            }
            isAnthropicFamily -> {
                conn.setRequestProperty("Authorization", "Bearer $trimmedToken")
                conn.setRequestProperty("x-api-key", trimmedToken)
                conn.setRequestProperty("anthropic-version", ANTHROPIC_API_VERSION)
            }
            else -> {
                conn.setRequestProperty("Authorization", "Bearer $trimmedToken")
            }
        }
    }

    private fun buildDiscoveryUris(baseUrl: String, protocol: ProviderProtocol): List<String> {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val strippedV1 = cleanBase.removeSuffix("/v1")
        val strippedAnthropic = cleanBase.removeSuffix("/anthropic")
        val rawList = when (protocol) {
            ProviderProtocol.OPENROUTER -> listOf("$strippedV1/v1/models", "$cleanBase/models")
            ProviderProtocol.OPENAI_CHAT, ProviderProtocol.OPENAI_RESPONSES -> listOf("$cleanBase/models", "$strippedV1/v1/models")
            else -> listOf(
                "$strippedV1/v1/models",
                "$cleanBase/models",
                "$strippedAnthropic/models",
                "$strippedAnthropic/v1/models",
            )
        }
        return rawList.distinct()
    }

    private fun buildInferenceUri(baseUrl: String, protocol: ProviderProtocol): String {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val strippedV1 = cleanBase.removeSuffix("/v1")
        return when (protocol) {
            ProviderProtocol.OPENROUTER -> "$strippedV1/v1/messages"
            ProviderProtocol.OPENAI_CHAT -> "${cleanBase.removeSuffix("/chat/completions")}/chat/completions"
            ProviderProtocol.OPENAI_RESPONSES -> "${cleanBase.removeSuffix("/responses")}/responses"
            else -> "$strippedV1/v1/messages"
        }
    }

    private fun isPublicOpenRouterCatalog(uri: String): Boolean = runCatching {
        val parsed = URL(uri)
        parsed.host.equals("openrouter.ai", ignoreCase = true) &&
            parsed.path.trimEnd('/').endsWith("/models")
    }.getOrDefault(false)

    internal fun validationBody(
        model: String,
        protocol: ProviderProtocol,
        openRouterProviderOrder: String = "",
        openRouterAllowFallbacks: Boolean = true,
    ): String {
        if (protocol == ProviderProtocol.OPENAI_RESPONSES) {
            val textPart = JSONObject().put("type", "input_text").put("text", "Hello, reply with 1 word.")
            val userMsg = JSONObject().put("role", "user").put("content", JSONArray().put(textPart))
            return JSONObject()
                .put("model", model)
                .put("input", JSONArray().put(userMsg))
                .toString()
        }

        val chatMsg = JSONObject().put("role", "user").put("content", "Reply OK")
        val rootObj = JSONObject()
            .put("model", model)
            .put("max_tokens", PROBE_MAX_TOKENS)
            .put("messages", JSONArray().put(chatMsg))

        if (protocol == ProviderProtocol.OPENROUTER) {
            val slugs = openRouterProviderOrder
                .splitToSequence(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()
            if (slugs.isNotEmpty()) {
                val routingObj = JSONObject()
                    .put("order", JSONArray(slugs))
                    .put("allow_fallbacks", openRouterAllowFallbacks)
                rootObj.put("provider", routingObj)
            }
        }
        return rootObj.toString()
    }

    private fun describeStatusFailure(statusCode: Int): String = when (statusCode) {
        429 -> "The provider rate limit was reached. Wait a moment and try again."
        in 500..599 -> "The provider is temporarily unavailable (HTTP $statusCode)."
        else -> "The provider returned HTTP $statusCode. Check the URL and account access."
    }

    private fun extractRedactedError(rawBody: String): String? {
        if (rawBody.isBlank()) return null
        val message = runCatching {
            val json = JSONObject(rawBody)
            when (val errNode = json.opt("error")) {
                is JSONObject -> errNode.stringOrEmpty("message").ifBlank { errNode.stringOrEmpty("detail") }
                is String -> errNode
                else -> json.stringOrEmpty("message").ifBlank { json.stringOrEmpty("detail") }
            }
        }.getOrNull().orEmpty()
        if (message.isBlank()) return null
        return message
            .replace(BEARER_REDACT_REGEX, "Bearer ••••")
            .replace(SK_REDACT_REGEX, "sk-••••")
            .replace(WS_COLLAPSE_REGEX, " ")
            .trim()
            .take(280)
    }

    private data class TransportResponse(
        val statusCode: Int,
        val payload: String,
        val transportError: String? = null,
    )

    private companion object {
        const val PROBE_MAX_TOKENS = 8
        const val RESPONSE_CHAR_CAP = 2 * 1024 * 1024
        const val ANTHROPIC_API_VERSION = "2023-06-01"
        val BEARER_REDACT_REGEX = Regex("(?i)bearer\\s+\\S+")
        val SK_REDACT_REGEX = Regex("(?i)sk-[a-z0-9_-]{8,}")
        val WS_COLLAPSE_REGEX = Regex("\\s+")
    }
}

object ModelResponseParser {
    private val ZERO_PRICING_FIELDS = listOf("prompt", "completion", "request")

    fun parse(json: String): List<DiscoveredModel> = runCatching {
        val clean = json.trim()
        val itemsArray = if (clean.startsWith("[")) {
            JSONArray(clean)
        } else {
            val obj = JSONObject(clean)
            obj.optJSONArray("data") ?: obj.optJSONArray("models") ?: JSONArray()
        }

        val parsed = ArrayList<DiscoveredModel>(itemsArray.length())
        for (i in 0 until itemsArray.length()) {
            when (val entry = itemsArray.opt(i)) {
                is String -> if (entry.isNotBlank()) parsed.add(DiscoveredModel(entry))
                is JSONObject -> parseModelObject(entry)?.let(parsed::add)
            }
        }
        parsed.distinctBy { it.id }
            .sortedWith(compareByDescending<DiscoveredModel> { it.isFree }.thenBy { it.displayName.lowercase() })
    }.getOrDefault(emptyList())

    private fun parseModelObject(node: JSONObject): DiscoveredModel? {
        val modelId = node.stringOrEmpty("id")
            .ifBlank { node.stringOrEmpty("name") }
            .ifBlank { node.stringOrEmpty("model") }
        if (modelId.isBlank()) return null

        val title = node.stringOrEmpty("display_name")
            .ifBlank { node.stringOrEmpty("displayName") }
            .ifBlank { modelId }

        val pricingNode = node.optJSONObject("pricing")
        val hasZeroPrice = pricingNode != null && ZERO_PRICING_FIELDS.all { key ->
            pricingNode.stringOrEmpty(key, "0").toDoubleOrNull() == 0.0
        }
        val isFreeTier = modelId.endsWith(":free", ignoreCase = true) || hasZeroPrice
        return DiscoveredModel(id = modelId, displayName = title, isFree = isFreeTier)
    }
}
