package com.nextcraft.forge.network

import android.content.Context
import com.nextcraft.forge.model.ProviderProtocol
import com.nextcraft.forge.model.stringOrEmpty
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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
        val effectiveBaseUrl = if (protocol == ProviderProtocol.CLAUDE_LOGIN && baseUrl.isBlank()) {
            "https://api.anthropic.com"
        } else {
            baseUrl.trim()
        }
        if (effectiveBaseUrl.isBlank()) {
            return@withContext ModelDiscoveryResult.Failure("Enter a base URL first.")
        }

        var authError = false
        var lastMessage = "This provider did not expose a model list. You can enter a custom model name."
        var lastProviderMessage: String? = null
        for (endpoint in modelEndpoints(effectiveBaseUrl, protocol)) {
            // OpenRouter's complete catalog is public. Fetch it anonymously even when
            // OpenRouter is configured through Custom API so an account-scoped key does
            // not reduce discovery to the models allowed by that key's preferences.
            val discoveryKey = if (isOpenRouterCatalogEndpoint(endpoint)) "" else apiKey
            val response = request(endpoint, "GET", discoveryKey, protocol = protocol)
            when {
                response.code == 401 || response.code == 403 -> {
                    authError = true
                    lastProviderMessage = providerErrorMessage(response.body)
                }
                response.code in 200..299 -> {
                    val models = ModelResponseParser.parse(response.body)
                    if (models.isNotEmpty()) return@withContext ModelDiscoveryResult.Success(models, endpoint)
                    lastMessage = "The provider replied, but its model list was empty or unsupported."
                }
                response.code > 0 && response.code != 404 -> {
                    lastMessage = friendlyHttpError(response.code)
                    lastProviderMessage = providerErrorMessage(response.body)
                }
                response.error != null -> lastMessage = response.error
            }
        }
        ModelDiscoveryResult.Failure(
            if (authError) {
                "Check the saved API key, then try refreshing again."
            } else {
                lastMessage
            },
            lastProviderMessage,
        )
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
        val effectiveBaseUrl = if (protocol == ProviderProtocol.CLAUDE_LOGIN && baseUrl.isBlank()) {
            "https://api.anthropic.com"
        } else {
            baseUrl.trim()
        }
        val effectiveModel = if (
            (protocol == ProviderProtocol.CLAUDE_LOGIN || protocol == ProviderProtocol.ANTHROPIC) &&
            (model.isBlank() || model.trim().equals("default", ignoreCase = true))
        ) {
            "claude-sonnet-4-6"
        } else {
            model.trim()
        }
        if (effectiveBaseUrl.isBlank() || effectiveModel.isBlank()) {
            return@withContext ConnectionValidation.Failure("Base URL, model, and API key are required.")
        }
        val endpoint = messagesEndpoint(effectiveBaseUrl, protocol)
        val body = validationBody(effectiveModel, protocol, openRouterProviderOrder, openRouterAllowFallbacks)
        val response = request(
            endpoint = endpoint,
            method = "POST",
            apiKey = apiKey,
            body = body,
            protocol = protocol,
            connectTimeoutMs = 12_000,
            readTimeoutMs = 45_000,
        )
        when {
            response.code in 200..299 -> ConnectionValidation.Success("Connection verified.")
            response.code == 401 || response.code == 403 -> ConnectionValidation.Failure(
                "Check this API key or select another saved key.",
                providerErrorMessage(response.body),
                "Rejected",
            )
            response.code == 404 -> ConnectionValidation.Failure(
                "Check the Base URL and selected gateway protocol.",
                providerErrorMessage(response.body),
                "Endpoint error",
            )
            response.code == 400 && response.body.contains("model", ignoreCase = true) ->
                ConnectionValidation.Failure(
                    "Refresh the model list or select a different model.",
                    providerErrorMessage(response.body),
                    "Model error",
                )
            response.code == 429 -> ConnectionValidation.Failure(
                "Wait a moment, then retry or use another API key.",
                providerErrorMessage(response.body),
                "Rate limited",
            )
            response.code in 500..599 -> ConnectionValidation.Failure(
                "The provider is temporarily unavailable. Try again shortly.",
                providerErrorMessage(response.body),
                "Provider error",
            )
            response.code > 0 -> ConnectionValidation.Failure(
                "Review the model, protocol, and endpoint settings.",
                providerErrorMessage(response.body),
                "Request failed",
            )
            response.error?.contains("timeout", ignoreCase = true) == true ||
                response.error?.contains("timed out", ignoreCase = true) == true ->
                ConnectionValidation.Failure(
                    "Check your connection and try again.",
                    response.error,
                    "Timed out",
                )
            else -> ConnectionValidation.Failure(
                "Check your internet connection and provider settings.",
                response.error,
                "Network error",
            )
        }
    }

    private fun request(
        endpoint: String,
        method: String,
        apiKey: String,
        body: String? = null,
        protocol: ProviderProtocol,
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 20_000,
    ): HttpResult {
        if (!LocalNetwork.isPlainHttpAllowed(endpoint)) {
            return HttpResult(
                0,
                "",
                "Plain http:// is only allowed for localhost. Use https:// for this server.",
            )
        }
        return runCatching {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                // Redirects are not followed: HttpURLConnection drops Authorization on a cross-host redirect but not
                // custom headers such as x-api-key, so a redirecting base URL could receive the key elsewhere.
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                val cleanKey = apiKey.trim()
                val isClaudeOAuthToken = cleanKey.startsWith("sk-ant-oat")
                val isAnthropicApiKey = cleanKey.startsWith("sk-ant-api")
                val isAnthropicHost = runCatching {
                    URL(endpoint).host.equals("api.anthropic.com", ignoreCase = true)
                }.getOrDefault(endpoint.contains("api.anthropic.com", ignoreCase = true))
                val isAnthropicProtocol = protocol == ProviderProtocol.ANTHROPIC ||
                    protocol == ProviderProtocol.CLAUDE_LOGIN ||
                    protocol == ProviderProtocol.ANTHROPIC_GATEWAY

                if (cleanKey.isNotEmpty()) {
                    val useOAuthBearer = isClaudeOAuthToken ||
                        (protocol == ProviderProtocol.CLAUDE_LOGIN && !isAnthropicApiKey)
                    when {
                        useOAuthBearer -> {
                            setRequestProperty("Authorization", "Bearer $cleanKey")
                            setRequestProperty("anthropic-version", "2023-06-01")
                            setRequestProperty("anthropic-beta", "claude-code-20250219,oauth-2025-04-20")
                        }
                        isAnthropicHost || protocol == ProviderProtocol.ANTHROPIC || isAnthropicApiKey -> {
                            // First-party api.anthropic.com rejects Authorization: Bearer <sk-ant-api...>
                            // with HTTP 401 "Invalid bearer token". Send x-api-key only.
                            setRequestProperty("x-api-key", cleanKey)
                            setRequestProperty("anthropic-version", "2023-06-01")
                        }
                        isAnthropicProtocol -> {
                            setRequestProperty("Authorization", "Bearer $cleanKey")
                            setRequestProperty("x-api-key", cleanKey)
                            setRequestProperty("anthropic-version", "2023-06-01")
                        }
                        else -> {
                            setRequestProperty("Authorization", "Bearer $cleanKey")
                        }
                    }
                } else if (isAnthropicProtocol) {
                    setRequestProperty("anthropic-version", "2023-06-01")
                }
                if (endpoint.startsWith("https://opencode.ai/zen/")) {
                    setRequestProperty("User-Agent", "NextCraft-Forge-Android/1.0")
                    setRequestProperty("x-session-id", "session-${UUID.randomUUID()}")
                }
                if (body != null) doOutput = true
            }
            try {
                if (body != null) {
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    buildString {
                        val buffer = CharArray(8 * 1024)
                        while (length < MAX_PROVIDER_RESPONSE_CHARS) {
                            val count = reader.read(buffer, 0, minOf(buffer.size, MAX_PROVIDER_RESPONSE_CHARS - length))
                            if (count <= 0) break
                            append(buffer, 0, count)
                        }
                    }
                }.orEmpty()
                HttpResult(code, responseBody)
            } finally {
                connection.disconnect()
            }
        }.getOrElse { HttpResult(0, "", it.message ?: "Network connection failed") }
    }

    private fun modelEndpoints(baseUrl: String, protocol: ProviderProtocol): List<String> {
        val base = baseUrl.trim().trimEnd('/')
        val withoutV1 = base.removeSuffix("/v1")
        val withoutAnthropic = base.removeSuffix("/anthropic")
        val candidates = when (protocol) {
            ProviderProtocol.OPENROUTER -> listOf("$withoutV1/v1/models", "$base/models")
            ProviderProtocol.OPENAI_CHAT, ProviderProtocol.OPENAI_RESPONSES -> listOf("$base/models", "$withoutV1/v1/models")
            else -> listOf("$withoutV1/v1/models", "$base/models", "$withoutAnthropic/models", "$withoutAnthropic/v1/models")
        }
        return candidates.distinct()
    }

    private fun messagesEndpoint(baseUrl: String, protocol: ProviderProtocol): String {
        val base = baseUrl.trim().trimEnd('/')
        val withoutV1 = base.removeSuffix("/v1")
        return when (protocol) {
            ProviderProtocol.OPENROUTER -> "$withoutV1/v1/messages"
            ProviderProtocol.OPENAI_CHAT -> "${base.removeSuffix("/chat/completions")}/chat/completions"
            ProviderProtocol.OPENAI_RESPONSES -> "${base.removeSuffix("/responses")}/responses"
            else -> "$withoutV1/v1/messages"
        }
    }

    private fun isOpenRouterCatalogEndpoint(endpoint: String): Boolean = runCatching {
        val url = URL(endpoint)
        url.host.equals("openrouter.ai", ignoreCase = true) &&
            url.path.trimEnd('/').endsWith("/models")
    }.getOrDefault(false)

    internal fun validationBody(
        model: String,
        protocol: ProviderProtocol,
        openRouterProviderOrder: String = "",
        openRouterAllowFallbacks: Boolean = true,
    ): String = when (protocol) {
        ProviderProtocol.OPENAI_RESPONSES -> JSONObject()
            .put("model", model)
            .put(
                "input",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "input_text")
                                    .put("text", "Hello, reply with 1 word."),
                            ),
                        ),
                ),
            )
            .toString()
        ProviderProtocol.OPENAI_CHAT -> JSONObject()
            .put("model", model)
            .put("max_tokens", VALIDATION_MAX_TOKENS)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK")))
            .toString()
        else -> JSONObject()
            .put("model", model)
            .put("max_tokens", VALIDATION_MAX_TOKENS)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK")))
            .also { body ->
                val providers = openRouterProviderOrder.split(',')
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
                if (protocol == ProviderProtocol.OPENROUTER && providers.isNotEmpty()) {
                    body.put(
                        "provider",
                        JSONObject()
                            .put("order", JSONArray(providers))
                            .put("allow_fallbacks", openRouterAllowFallbacks),
                    )
                }
            }
            .toString()
    }

    private fun friendlyHttpError(code: Int): String = when (code) {
        429 -> "The provider rate limit was reached. Wait a moment and try again."
        in 500..599 -> "The provider is temporarily unavailable (HTTP $code)."
        else -> "The provider returned HTTP $code. Check the URL and account access."
    }

    private fun providerErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        val extracted = runCatching {
            val root = JSONObject(body)
            when (val error = root.opt("error")) {
                is JSONObject -> error.stringOrEmpty("message").ifBlank { error.stringOrEmpty("detail") }
                is String -> error
                else -> root.stringOrEmpty("message").ifBlank { root.stringOrEmpty("detail") }
            }
        }.getOrNull().orEmpty()
        if (extracted.isBlank()) return null
        return extracted
            .replace(Regex("(?i)bearer\\s+\\S+"), "Bearer ••••")
            .replace(Regex("(?i)sk-[a-z0-9_-]{8,}"), "sk-••••")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(280)
    }

    private data class HttpResult(val code: Int, val body: String, val error: String? = null)

    private companion object {
        const val VALIDATION_MAX_TOKENS = 8
        const val MAX_PROVIDER_RESPONSE_CHARS = 2 * 1024 * 1024
    }
}

object ModelResponseParser {
    fun parse(json: String): List<DiscoveredModel> = runCatching {
        val trimmed = json.trim()
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> {
                val root = JSONObject(trimmed)
                root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
            }
        }
        buildList {
            for (index in 0 until array.length()) {
                when (val item = array.opt(index)) {
                    is String -> if (item.isNotBlank()) add(DiscoveredModel(item))
                    is JSONObject -> {
                        val id = item.stringOrEmpty("id")
                            .ifBlank { item.stringOrEmpty("name") }
                            .ifBlank { item.stringOrEmpty("model") }
                        if (id.isNotBlank()) {
                            val label = item.stringOrEmpty("display_name")
                                .ifBlank { item.stringOrEmpty("displayName") }
                                .ifBlank { id }
                            val pricing = item.optJSONObject("pricing")
                            val free = id.endsWith(":free", ignoreCase = true) || pricing?.let {
                                listOf("prompt", "completion", "request").all { field ->
                                    it.stringOrEmpty(field, "0").toDoubleOrNull() == 0.0
                                }
                            } == true
                            add(DiscoveredModel(id, label, free))
                        }
                    }
                }
            }
        }.distinctBy { it.id }.sortedWith(
            compareByDescending<DiscoveredModel> { it.isFree }.thenBy { it.displayName.lowercase() },
        )
    }.getOrDefault(emptyList())
}
