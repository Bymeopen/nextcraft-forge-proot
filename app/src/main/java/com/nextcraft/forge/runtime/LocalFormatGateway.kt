package com.nextcraft.forge.runtime

import android.util.Log
import com.nextcraft.forge.model.ProviderProtocol
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.stringOrEmpty
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Small loopback-only Anthropic-to-OpenAI compatibility bridge for Claude Code. */
internal class LocalFormatGateway(
    private val profile: ProviderProfile,
    private val apiKey: String,
    val sessionToken: String = "forge-local-${UUID.randomUUID()}",
    private val onRotateApiKey: (() -> String?)? = null,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val rotationLock = Any()
    private val upstreamConnections: MutableSet<HttpURLConnection> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    @Volatile private var activeApiKey: String = apiKey
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val url: String = "http://127.0.0.1:${server.localPort}"
    private val isResponsesProtocol: Boolean
        get() = profile.kind.protocol == ProviderProtocol.OPENAI_RESPONSES ||
            profile.resolvedBaseUrl.trim().trimEnd('/').endsWith("/responses")

    fun start(): LocalFormatGateway = apply {
        Thread({ acceptLoop() }, "forge-format-gateway").apply { isDaemon = true; start() }
    }

    // Bounded pool: caps concurrent connections instead of one unbounded thread per accept().
    private val workers = java.util.concurrent.ThreadPoolExecutor(
        0, MAX_CONNECTIONS, 30L, TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue(),
    ) { task -> Thread(task, "forge-format-request").apply { isDaemon = true } }

    private fun acceptLoop() {
        var failures = 0
        while (running.get()) {
            val socket = try {
                server.accept().also { failures = 0 }
            } catch (_: Exception) {
                // Closed server ends the loop; transient errors (e.g. EMFILE) back off instead of busy-spinning.
                if (!running.get() || server.isClosed || ++failures > MAX_ACCEPT_FAILURES) break
                Thread.sleep(ACCEPT_BACKOFF_MS)
                continue
            }
            runCatching {
                socket.soTimeout = SOCKET_TIMEOUT_MS
                socket.tcpNoDelay = true
            }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("LocalFormatGateway", it) }
            try {
                workers.execute { runCatching { socket.use(::handle) } } // client disconnects must not escape as uncaught exceptions (crashes the app)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                runCatching { socket.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("LocalFormatGateway", it) }
            }
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val split = line.indexOf(':')
            if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
        }
        val output = GatewayOutput(socket.getOutputStream())
        if (!isAuthorized(headers)) {
            writeJson(output, 401, errorJson("authentication_error", "Missing or invalid credentials"))
            return
        }
        if (headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            // Without this the client waits for a go-ahead until the socket timeout before sending the body.
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
            output.flush()
        }
        val chunked = headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true
        val bodyText = if (chunked) {
            // No Content-Length: reading it as 0 turned the request into an empty body and a JSON parse error.
            readChunkedBody(input) ?: run {
                writeJson(output, 400, errorJson("invalid_request_error", "Malformed or too large chunked request body"))
                return
            }
        } else {
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length !in 0..MAX_REQUEST_BYTES) {
                writeJson(output, 413, errorJson("invalid_request_error", "Request body is too large"))
                return
            }
            val bodyBytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(bodyBytes, offset, length - offset)
                if (count < 0) break
                offset += count
            }
            if (offset < length) {
                writeJson(output, 400, errorJson("invalid_request_error", "Truncated request body"))
                return
            }
            bodyBytes.decodeToString(0, offset)
        }
        val path = requestLine.split(' ').getOrNull(1).orEmpty().substringBefore('?')
        if (path.endsWith("/count_tokens")) {
            val approximate = bodyText.length / 4 + 1
            writeJson(output, 200, JSONObject().put("input_tokens", approximate).toString())
            return
        }
        if (!path.endsWith("/messages")) {
            writeJson(output, 404, errorJson("not_found", "Unsupported gateway endpoint"))
            return
        }
        runCatching {
            val anthropic = JSONObject(bodyText)
            val requestedModel = anthropic.stringOrEmpty("model", profile.model)
            val wantsStream = anthropic.optBoolean("stream", false)
            if (wantsStream && !isResponsesProtocol) {
                streamFromProvider(anthropic, requestedModel, output)
                return@runCatching
            }
            val upstream = callProvider(toOpenAi(anthropic))
            if (upstream.first !in 200..299) {
                Log.w("FormatGateway", "Provider returned HTTP ${upstream.first}: ${logSafe(providerError(upstream.second))}")
                writeJson(output, upstream.first, errorJson("api_error", providerError(upstream.second)))
            } else {
                val translated = fromOpenAi(JSONObject(upstream.second), requestedModel)
                if (wantsStream) writeStream(output, translated) else writeJson(output, 200, translated.toString())
            }
        }.onFailure { error ->
            writeFailure(output, error.message ?: "Provider request failed")
        }
    }

    /**
     * Reports a failure on a response that may already be under way. Once the status line is out, a second HTTP
     * response would land inside the event stream and break the client's parser, so an event stream gets an
     * Anthropic `error` event instead, and any other partly written response is just closed.
     */
    internal fun writeFailure(output: GatewayOutput, message: String) {
        runCatching {
            when {
                !output.committed -> writeJson(output, 502, errorJson("api_error", message))
                output.eventStream -> writeStreamError(output, "api_error", message)
                else -> Unit
            }
        }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("LocalFormatGateway", it) }
    }

    private fun writeStreamError(output: BufferedOutputStream, type: String, message: String) {
        val error = JSONObject().put("type", "error").put("error", JSONObject().put("type", type).put("message", message))
        output.write("event: error\ndata: $error\n\n".toByteArray())
        output.flush()
    }

    private fun writeEventStreamHeaders(output: BufferedOutputStream) {
        (output as? GatewayOutput)?.eventStream = true
        output.write(EVENT_STREAM_HEADERS.toByteArray())
    }

    private fun toOpenAi(source: JSONObject): JSONObject {
        if (isResponsesProtocol) return toOpenAiResponses(source)
        val target = JSONObject()
            .put("model", normalizeModel(profile.model))
            .put("stream", false)
            .put(maxTokensField(), source.optInt("max_tokens", 4096))
        if (source.has("temperature")) target.put("temperature", source.get("temperature"))
        val messages = JSONArray()
        source.opt("system")?.let { system ->
            val text = when (system) {
                is JSONArray -> contentText(system)
                else -> system.toString()
            }
            if (text.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", text))
        }
        val sourceMessages = source.optJSONArray("messages") ?: JSONArray()
        for (index in 0 until sourceMessages.length()) {
            val message = sourceMessages.getJSONObject(index)
            val role = message.stringOrEmpty("role")
            val content = message.opt("content")
            if (content !is JSONArray) {
                messages.put(JSONObject().put("role", role).put("content", content ?: ""))
                continue
            }
            val text = contentText(content)
            val toolCalls = JSONArray()
            val toolResults = mutableListOf<JSONObject>()
            for (partIndex in 0 until content.length()) {
                val part = content.optJSONObject(partIndex) ?: continue
                when (part.stringOrEmpty("type")) {
                    "tool_use" -> toolCalls.put(
                        JSONObject().put("id", part.stringOrEmpty("id"))
                            .put("type", "function")
                            .put("function", JSONObject().put("name", part.stringOrEmpty("name")).put("arguments", part.optJSONObject("input")?.toString() ?: "{}")),
                    )
                    "tool_result" -> toolResults += JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", part.stringOrEmpty("tool_use_id"))
                        .put("content", toolResultText(part))
                }
            }
            if (role == "user") {
                toolResults.forEach(messages::put)
            }
            val images = imageUrls(content)
            if (images.isNotEmpty() && role == "user") {
                // Image blocks (screenshots, pasted images) were dropped, so the model never saw them.
                val parts = JSONArray()
                if (text.isNotBlank()) parts.put(JSONObject().put("type", "text").put("text", text))
                images.forEach { url -> parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url))) }
                messages.put(JSONObject().put("role", role).put("content", parts))
            } else if (text.isNotBlank() || toolCalls.length() > 0) {
                val converted = JSONObject().put("role", role).put("content", text.ifBlank { JSONObject.NULL })
                if (toolCalls.length() > 0) converted.put("tool_calls", toolCalls)
                messages.put(converted)
            }
            if (role != "user") {
                toolResults.forEach(messages::put)
            }
        }
        target.put("messages", messages)
        source.optJSONArray("tools")?.let { tools ->
            val converted = JSONArray()
            for (index in 0 until tools.length()) {
                val tool = tools.getJSONObject(index)
                converted.put(
                    JSONObject().put("type", "function").put(
                        "function",
                        JSONObject()
                            .put("name", tool.stringOrEmpty("name"))
                            .put("description", tool.stringOrEmpty("description"))
                            .put("parameters", tool.optJSONObject("input_schema") ?: JSONObject().put("type", "object")),
                    ),
                )
            }
            target.put("tools", converted).put("tool_choice", "auto")
        }
        return target
    }

    private fun toOpenAiResponses(source: JSONObject): JSONObject {
        val target = JSONObject()
            .put("model", normalizeModel(profile.model))
            .put("stream", false)
            .put("max_output_tokens", source.optInt("max_tokens", 4096))
        if (source.has("temperature")) target.put("temperature", source.get("temperature"))
        source.opt("system")?.let { system ->
            val text = when (system) {
                is JSONArray -> contentText(system)
                else -> system.toString()
            }
            if (text.isNotBlank()) target.put("instructions", text)
        }
        val input = JSONArray()
        val sourceMessages = source.optJSONArray("messages") ?: JSONArray()
        for (index in 0 until sourceMessages.length()) {
            val message = sourceMessages.getJSONObject(index)
            val role = message.stringOrEmpty("role")
            val content = message.opt("content")
            if (content !is JSONArray) {
                input.put(JSONObject().put("role", role).put("content", content?.toString().orEmpty()))
                continue
            }
            val text = contentText(content)
            val toolCalls = mutableListOf<JSONObject>()
            val toolResults = mutableListOf<JSONObject>()
            for (partIndex in 0 until content.length()) {
                val part = content.optJSONObject(partIndex) ?: continue
                when (part.stringOrEmpty("type")) {
                    "tool_use" -> toolCalls += JSONObject()
                        .put("type", "function_call")
                        .put("call_id", part.stringOrEmpty("id"))
                        .put("name", part.stringOrEmpty("name"))
                        .put("arguments", part.optJSONObject("input")?.toString() ?: "{}")
                    "tool_result" -> toolResults += JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", part.stringOrEmpty("tool_use_id"))
                        .put("output", toolResultText(part))
                }
            }
            if (role == "user") {
                toolResults.forEach(input::put)
            }
            val images = imageUrls(content)
            if (images.isNotEmpty() && role == "user") {
                val parts = JSONArray()
                if (text.isNotBlank()) parts.put(JSONObject().put("type", "input_text").put("text", text))
                images.forEach { url -> parts.put(JSONObject().put("type", "input_image").put("image_url", url)) }
                input.put(JSONObject().put("role", role).put("content", parts))
            } else if (text.isNotBlank()) {
                input.put(JSONObject().put("role", role).put("content", text))
            }
            toolCalls.forEach(input::put)
            if (role != "user") {
                toolResults.forEach(input::put)
            }
        }
        target.put("input", input)
        source.optJSONArray("tools")?.let { tools ->
            val converted = JSONArray()
            for (index in 0 until tools.length()) {
                val tool = tools.getJSONObject(index)
                converted.put(
                    JSONObject()
                        .put("type", "function")
                        .put("name", tool.stringOrEmpty("name"))
                        .put("description", tool.stringOrEmpty("description"))
                        .put("parameters", tool.optJSONObject("input_schema") ?: JSONObject().put("type", "object")),
                )
            }
            target.put("tools", converted).put("tool_choice", "auto")
        }
        return target
    }

    private fun fromOpenAi(source: JSONObject, model: String): JSONObject {
        if (source.has("output") && !source.has("choices")) {
            return fromOpenAiResponses(source, model)
        }
        val message = source.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: JSONObject()
        val content = JSONArray()
        val text = message.stringOrEmpty("content")
        if (text.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", text))
        val calls = message.optJSONArray("tool_calls") ?: JSONArray()
        val finishReason = source.optJSONArray("choices")?.optJSONObject(0)?.stringOrEmpty("finish_reason").orEmpty()
        val cutOff = finishReason == "length"
        for (index in 0 until calls.length()) {
            val call = calls.getJSONObject(index)
            val function = call.optJSONObject("function") ?: JSONObject()
            val parsedArguments = runCatching { JSONObject(function.stringOrEmpty("arguments", "{}")) }.getOrNull()
            // A call cut off by the token limit has partial arguments; running it would act on invalid input.
            if (parsedArguments == null && cutOff) continue
            val arguments = parsedArguments ?: JSONObject()
            content.put(
                JSONObject().put("type", "tool_use")
                    .put("id", call.stringOrEmpty("id").ifBlank { "tool_${UUID.randomUUID()}" })
                    .put("name", function.stringOrEmpty("name"))
                    .put("input", arguments),
            )
        }
        val usage = source.optJSONObject("usage") ?: JSONObject()
        val stopReason = when {
            cutOff -> "max_tokens"
            calls.length() > 0 -> "tool_use"
            // Reported as a normal end_turn, a filtered answer looked complete; Anthropic calls this a refusal.
            finishReason == "content_filter" -> "refusal"
            else -> "end_turn"
        }
        return JSONObject().put("id", source.stringOrEmpty("id").ifBlank { "msg_${UUID.randomUUID()}" })
            .put("type", "message").put("role", "assistant").put("model", model)
            .put("content", content).put("stop_reason", stopReason)
            .put("stop_sequence", JSONObject.NULL)
            .put("usage", JSONObject().put("input_tokens", usage.optInt("prompt_tokens")).put("output_tokens", usage.optInt("completion_tokens")))
    }

    private fun fromOpenAiResponses(source: JSONObject, model: String): JSONObject {
        val output = source.optJSONArray("output") ?: JSONArray()
        val content = JSONArray()
        var hasToolUse = false
        for (index in 0 until output.length()) {
            val item = output.optJSONObject(index) ?: continue
            when (item.stringOrEmpty("type")) {
                "message" -> {
                    val parts = item.optJSONArray("content")
                    val text = if (parts != null) contentText(parts) else item.stringOrEmpty("content")
                    if (text.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", text))
                }
                "function_call" -> {
                    hasToolUse = true
                    val arguments = runCatching { JSONObject(item.stringOrEmpty("arguments", "{}")) }.getOrDefault(JSONObject())
                    val callId = item.stringOrEmpty("call_id").ifBlank { item.stringOrEmpty("id") }.ifBlank { "tool_${UUID.randomUUID()}" }
                    content.put(
                        JSONObject().put("type", "tool_use")
                            .put("id", callId)
                            .put("name", item.stringOrEmpty("name"))
                            .put("input", arguments),
                    )
                }
            }
        }
        if (content.length() == 0) {
            val fallbackText = source.stringOrEmpty("output_text")
            if (fallbackText.isNotBlank()) {
                content.put(JSONObject().put("type", "text").put("text", fallbackText))
            }
        }
        val usage = source.optJSONObject("usage") ?: JSONObject()
        val inputTokens = usage.optInt("input_tokens", usage.optInt("prompt_tokens"))
        val outputTokens = usage.optInt("output_tokens", usage.optInt("completion_tokens"))
        return JSONObject().put("id", source.stringOrEmpty("id").ifBlank { "msg_${UUID.randomUUID()}" })
            .put("type", "message").put("role", "assistant").put("model", model)
            .put("content", content).put("stop_reason", responsesStopReason(source, hasToolUse))
            .put("stop_sequence", JSONObject.NULL)
            .put("usage", JSONObject().put("input_tokens", inputTokens).put("output_tokens", outputTokens))
    }

    private fun callProvider(body: JSONObject, allowRetry: Boolean = true): Pair<Int, String> {
        val rawBase = profile.resolvedBaseUrl.trim().trimEnd('/')
        val endpoint = if (isResponsesProtocol) {
            "${rawBase.removeSuffix("/responses").removeSuffix("/chat/completions")}/responses"
        } else {
            "${rawBase.removeSuffix("/chat/completions")}/chat/completions"
        }
        if (!com.nextcraft.forge.network.LocalNetwork.isPlainHttpAllowed(endpoint)) {
            return 400 to errorJson("invalid_request_error", "Plain http:// is only allowed for localhost")
        }
        val currentKey = activeApiKey
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).also(upstreamConnections::add).apply { instanceFollowRedirects = false }
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 180_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            if (currentKey.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $currentKey")
            }
            if (endpoint.startsWith("https://opencode.ai/zen/")) {
                connection.setRequestProperty("User-Agent", "NextCraft-Forge-Android/1.0")
                connection.setRequestProperty("x-session-id", "session-${UUID.randomUUID()}")
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            if (allowRetry && code in setOf(402, 429) && onRotateApiKey != null) {
                val shouldRetry = synchronized(rotationLock) {
                    if (activeApiKey == currentKey) {
                        val rotated = onRotateApiKey.invoke()?.takeIf { it.isNotBlank() && it != currentKey }
                        if (rotated != null) {
                            activeApiKey = rotated
                            true
                        } else {
                            false
                        }
                    } else {
                        true
                    }
                }
                if (shouldRetry) {
                    connection.disconnect()
                    return callProvider(body, allowRetry = false)
                }
            }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                buildString {
                    val buffer = CharArray(8 * 1024)
                    while (length < MAX_UPSTREAM_RESPONSE_CHARS) {
                        val count = reader.read(buffer, 0, minOf(buffer.size, MAX_UPSTREAM_RESPONSE_CHARS - length))
                        if (count <= 0) break
                        append(buffer, 0, count)
                    }
                }
            }.orEmpty()
            code to responseText
        } finally {
            upstreamConnections.remove(connection)
            connection.disconnect()
        }
    }

    private fun streamFromProvider(
        anthropic: JSONObject,
        requestedModel: String,
        output: BufferedOutputStream,
        allowRetry: Boolean = true,
    ) {
        val rawBase = profile.resolvedBaseUrl.trim().trimEnd('/')
        val endpoint = "${rawBase.removeSuffix("/chat/completions")}/chat/completions"
        if (!com.nextcraft.forge.network.LocalNetwork.isPlainHttpAllowed(endpoint)) {
            writeJson(output, 400, errorJson("invalid_request_error", "Plain http:// is only allowed for localhost"))
            return
        }
        val body = toOpenAi(anthropic).put("stream", true)
        val currentKey = activeApiKey
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).also(upstreamConnections::add).apply { instanceFollowRedirects = false }
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 180_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "text/event-stream, application/json")
            if (currentKey.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $currentKey")
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            if (allowRetry && code in setOf(402, 429) && onRotateApiKey != null) {
                val shouldRetry = synchronized(rotationLock) {
                    if (activeApiKey == currentKey) {
                        val rotated = onRotateApiKey.invoke()?.takeIf { it.isNotBlank() && it != currentKey }
                        if (rotated != null) {
                            activeApiKey = rotated
                            true
                        } else {
                            false
                        }
                    } else {
                        true
                    }
                }
                if (shouldRetry) {
                    connection.disconnect()
                    streamFromProvider(anthropic, requestedModel, output, allowRetry = false)
                    return
                }
            }
            if (code !in 200..299) {
                val errorText = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(4096) }.orEmpty()
                Log.w("FormatGateway", "Provider returned HTTP $code: ${logSafe(providerError(errorText))}")
                writeJson(output, code, errorJson("api_error", providerError(errorText)))
                return
            }
            val contentType = connection.contentType.orEmpty().lowercase(Locale.ROOT)
            val stream = connection.inputStream ?: run {
                writeJson(output, 502, errorJson("api_error", "Empty response from provider"))
                return
            }
            if (contentType.contains("text/event-stream")) {
                stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    streamOpenAiSseLines(lines, output, requestedModel)
                }
            } else {
                val responseText = stream.bufferedReader(Charsets.UTF_8).use { reader ->
                    buildString {
                        val buffer = CharArray(8 * 1024)
                        while (length < MAX_UPSTREAM_RESPONSE_CHARS) {
                            val count = reader.read(buffer, 0, minOf(buffer.size, MAX_UPSTREAM_RESPONSE_CHARS - length))
                            if (count <= 0) break
                            append(buffer, 0, count)
                        }
                    }
                }
                val trimmed = responseText.trimStart()
                if (trimmed.startsWith("data:") || trimmed.startsWith(":")) {
                    streamOpenAiSseLines(responseText.lineSequence(), output, requestedModel)
                } else {
                    val translated = fromOpenAi(JSONObject(responseText), requestedModel)
                    writeStream(output, translated)
                }
            }
        } finally {
            upstreamConnections.remove(connection)
            connection.disconnect()
        }
    }

    internal fun streamOpenAiSseLines(
        lines: Sequence<String>,
        output: BufferedOutputStream,
        requestedModel: String,
    ) {
        writeEventStreamHeaders(output)
        fun emitEvent(name: String, data: JSONObject) {
            output.write("event: $name\ndata: $data\n\n".toByteArray())
            output.flush()
        }
        val msgId = "msg_${UUID.randomUUID()}"
        val startMessage = JSONObject()
            .put("id", msgId)
            .put("type", "message")
            .put("role", "assistant")
            .put("model", requestedModel)
            .put("content", JSONArray())
            .put("stop_reason", JSONObject.NULL)
            .put("stop_sequence", JSONObject.NULL)
            .put("usage", JSONObject().put("input_tokens", 0).put("output_tokens", 0))
        emitEvent("message_start", JSONObject().put("type", "message_start").put("message", startMessage))

        var nextBlockIndex = 0
        var openBlockType: String? = null
        var openBlockIndex = -1
        val pendingToolCalls = HashMap<Int, PendingToolCall>()
        var sawToolCalls = false
        var truncated = false
        var filtered = false
        // A stream that stops without [DONE] or a finish_reason was cut off (dropped connection, proxy timeout).
        var finished = false
        var inputTokens = 0
        var outputTokens = 0

        fun closeOpenBlock() {
            if (openBlockIndex >= 0) {
                emitEvent("content_block_stop", JSONObject().put("type", "content_block_stop").put("index", openBlockIndex))
                openBlockType = null
                openBlockIndex = -1
            }
        }

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) continue
            if (payload == "[DONE]") {
                finished = true
                break
            }
            val chunk = runCatching { JSONObject(payload) }.getOrNull() ?: continue
            // OpenRouter, DeepSeek and vLLM report failures after the 200 as `data: {"error": {...}}`. Skipping it
            // (no `choices`) turned a rate limit or context-length error into an empty, successful turn.
            chunk.optJSONObject("error")?.let { error ->
                closeOpenBlock()
                val message = error.stringOrEmpty("message").ifEmpty { error.toString().take(500) }
                Log.w("FormatGateway", "Provider reported an error mid-stream: ${logSafe(message)}")
                writeStreamError(output, "api_error", message)
                return
            }
            chunk.optJSONObject("usage")?.let { usage ->
                inputTokens = usage.optInt("prompt_tokens", usage.optInt("input_tokens", inputTokens))
                outputTokens = usage.optInt("completion_tokens", usage.optInt("output_tokens", outputTokens))
            }
            val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: continue
            val finishReason = choice.stringOrEmpty("finish_reason")
            if (finishReason.isNotEmpty()) finished = true
            if (finishReason == "length") truncated = true
            if (finishReason == "content_filter") filtered = true
            val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: continue

            val reasoningDelta = delta.stringOrEmpty("reasoning_content").ifEmpty { delta.stringOrEmpty("reasoning") }
            if (reasoningDelta.isNotEmpty()) {
                if (openBlockType != "thinking") {
                    closeOpenBlock()
                    val idx = nextBlockIndex++
                    openBlockType = "thinking"
                    openBlockIndex = idx
                    emitEvent(
                        "content_block_start",
                        JSONObject()
                            .put("type", "content_block_start")
                            .put("index", idx)
                            .put("content_block", JSONObject().put("type", "thinking").put("thinking", "")),
                    )
                }
                emitEvent(
                    "content_block_delta",
                    JSONObject()
                        .put("type", "content_block_delta")
                        .put("index", openBlockIndex)
                        .put("delta", JSONObject().put("type", "thinking_delta").put("thinking", reasoningDelta)),
                )
            }

            val textDelta = delta.stringOrEmpty("content")
            if (textDelta.isNotEmpty()) {
                if (openBlockType != "text") {
                    closeOpenBlock()
                    val idx = nextBlockIndex++
                    openBlockType = "text"
                    openBlockIndex = idx
                    emitEvent(
                        "content_block_start",
                        JSONObject()
                            .put("type", "content_block_start")
                            .put("index", idx)
                            .put("content_block", JSONObject().put("type", "text").put("text", "")),
                    )
                }
                emitEvent(
                    "content_block_delta",
                    JSONObject()
                        .put("type", "content_block_delta")
                        .put("index", openBlockIndex)
                        .put("delta", JSONObject().put("type", "text_delta").put("text", textDelta)),
                )
            }

            // Tool calls are collected per slot and emitted once the stream is done. Streaming them as they came closed
            // one tool's block when the next slot opened, so providers that interleave the arguments of parallel calls
            // (index 0, 1, 0, …) sent deltas to a block that was already stopped and the tool input broke.
            val toolCalls = delta.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                for (tcIdx in 0 until toolCalls.length()) {
                    val tc = toolCalls.optJSONObject(tcIdx) ?: continue
                    sawToolCalls = true
                    val call = pendingToolCalls.getOrPut(tc.optInt("index", tcIdx)) { PendingToolCall() }
                    val fn = tc.optJSONObject("function")
                    tc.stringOrEmpty("id").takeIf { it.isNotBlank() && call.id.isBlank() }?.let { call.id = it }
                    fn?.stringOrEmpty("name")?.takeIf { it.isNotBlank() && call.name.isBlank() }?.let { call.name = it }
                    fn?.stringOrEmpty("arguments")?.let { call.arguments.append(it) }
                }
            }
        }

        closeOpenBlock()
        if (finished) {
            for ((_, call) in pendingToolCalls.toSortedMap()) {
                // Arguments cut off by the token limit are not valid JSON; the call must not reach the client.
                if (truncated && call.arguments.isNotEmpty() && runCatching { JSONObject(call.arguments.toString()) }.isFailure) continue
                val idx = nextBlockIndex++
                emitEvent(
                    "content_block_start",
                    JSONObject()
                        .put("type", "content_block_start")
                        .put("index", idx)
                        .put(
                            "content_block",
                            JSONObject()
                                .put("type", "tool_use")
                                .put("id", call.id.ifBlank { "tool_${UUID.randomUUID()}" })
                                .put("name", call.name)
                                .put("input", JSONObject()),
                        ),
                )
                if (call.arguments.isNotEmpty()) {
                    emitEvent(
                        "content_block_delta",
                        JSONObject()
                            .put("type", "content_block_delta")
                            .put("index", idx)
                            .put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", call.arguments.toString())),
                    )
                }
                emitEvent("content_block_stop", JSONObject().put("type", "content_block_stop").put("index", idx))
            }
        }
        if (!finished) {
            Log.w("FormatGateway", "Provider stream ended before it finished")
            writeStreamError(output, "api_error", "The provider closed the stream before the response finished")
            return
        }
        emitEvent(
            "message_delta",
            JSONObject()
                .put("type", "message_delta")
                .put(
                    "delta",
                    JSONObject()
                        .put(
                            "stop_reason",
                            when {
                                truncated -> "max_tokens"
                                sawToolCalls -> "tool_use"
                                filtered -> "refusal"
                                else -> "end_turn"
                            },
                        )
                        .put("stop_sequence", JSONObject.NULL),
                )
                .put("usage", JSONObject().put("input_tokens", inputTokens).put("output_tokens", outputTokens)),
        )
        emitEvent("message_stop", JSONObject().put("type", "message_stop"))
    }

    private fun writeStream(output: BufferedOutputStream, message: JSONObject) {
        writeEventStreamHeaders(output)
        fun event(name: String, data: JSONObject) {
            output.write("event: $name\ndata: $data\n\n".toByteArray())
            output.flush()
        }
        val content = message.getJSONArray("content")
        event(
            "message_start",
            JSONObject().put("type", "message_start").put(
                "message",
                JSONObject(message.toString()).put("content", JSONArray()).put("stop_reason", JSONObject.NULL),
            ),
        )
        for (index in 0 until content.length()) {
            val block = content.getJSONObject(index)
            val type = block.getString("type")
            val start = if (type == "text") {
                JSONObject().put("type", "text").put("text", "")
            } else {
                JSONObject().put("type", "tool_use").put("id", block.getString("id")).put("name", block.getString("name")).put("input", JSONObject())
            }
            event("content_block_start", JSONObject().put("type", "content_block_start").put("index", index).put("content_block", start))
            val delta = if (type == "text") {
                JSONObject().put("type", "text_delta").put("text", block.getString("text"))
            } else {
                JSONObject().put("type", "input_json_delta").put("partial_json", block.getJSONObject("input").toString())
            }
            event("content_block_delta", JSONObject().put("type", "content_block_delta").put("index", index).put("delta", delta))
            event("content_block_stop", JSONObject().put("type", "content_block_stop").put("index", index))
        }
        event(
            "message_delta",
            JSONObject().put("type", "message_delta").put(
                "delta",
                JSONObject().put("stop_reason", message.getString("stop_reason")).put("stop_sequence", JSONObject.NULL),
            ).put("usage", message.getJSONObject("usage")),
        )
        event("message_stop", JSONObject().put("type", "message_stop"))
        output.flush()
    }

    private fun writeJson(output: BufferedOutputStream, code: Int, body: String) {
        val bytes = body.toByteArray()
        val reason = if (code in 200..299) "OK" else "Error"
        output.write("HTTP/1.1 $code $reason\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    /**
     * Only forward requests that present the ephemeral loopback session token or active provider key.
     */
    private fun isAuthorized(headers: Map<String, String>): Boolean {
        val presented = headers["x-api-key"]
            ?: headers["authorization"]?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
            ?: return false
        val presentedBytes = presented.toByteArray()
        return MessageDigest.isEqual(presentedBytes, sessionToken.toByteArray()) ||
            MessageDigest.isEqual(presentedBytes, activeApiKey.toByteArray()) ||
            MessageDigest.isEqual(presentedBytes, apiKey.toByteArray())
    }

    /** Reads an HTTP/1.1 chunked body, or returns null when it is malformed or larger than [MAX_REQUEST_BYTES]. */
    private fun readChunkedBody(input: BufferedInputStream): String? {
        val body = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: return null
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: return null
            if (size < 0 || body.size() + size > MAX_REQUEST_BYTES) return null
            if (size == 0) {
                // Trailer section, ended by an empty line.
                while (true) {
                    val trailer = readLine(input) ?: return null
                    if (trailer.isEmpty()) break
                }
                return body.toByteArray().decodeToString()
            }
            val chunk = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = input.read(chunk, offset, size - offset)
                if (count < 0) return null
                offset += count
            }
            body.write(chunk)
            if (readLine(input) != "") return null
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val out = ByteArrayOutputStream(128)
        while (out.size() <= MAX_HEADER_LINE_BYTES) {
            val value = input.read()
            if (value < 0) return if (out.size() == 0) null else out.toByteArray().decodeToString()
            if (value == '\n'.code) return out.toByteArray().decodeToString().trimEnd('\r')
            out.write(value)
        }
        return null
    }

    private fun contentText(array: JSONArray): String = buildString {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.stringOrEmpty("type") in setOf("text", "output_text")) append(item.stringOrEmpty("text"))
        }
    }

    /** Tool output text; a failed tool is marked so the model does not read the error as a normal result. */
    private fun toolResultText(part: JSONObject): String {
        val text = valueText(part.opt("content"))
        return if (part.optBoolean("is_error", false)) "Error: $text" else text
    }

    /** Anthropic image blocks as URLs an OpenAI-compatible API accepts (data: URLs for inline base64). */
    private fun imageUrls(content: JSONArray): List<String> = buildList {
        for (index in 0 until content.length()) {
            val part = content.optJSONObject(index) ?: continue
            if (part.stringOrEmpty("type") != "image") continue
            val source = part.optJSONObject("source") ?: continue
            when (source.stringOrEmpty("type")) {
                "base64" -> {
                    val data = source.stringOrEmpty("data")
                    if (data.isNotEmpty()) add("data:${source.stringOrEmpty("media_type", "image/png")};base64,$data")
                }
                "url" -> source.stringOrEmpty("url").takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun valueText(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> contentText(value).ifBlank { value.toString() }
        null, JSONObject.NULL -> ""
        else -> value.toString()
    }

    private fun providerError(body: String): String = runCatching {
        JSONObject(body).optJSONObject("error")?.stringOrEmpty("message").orEmpty().ifBlank { body.take(500) }
    }.getOrDefault(body.take(500))

    /**
     * api.openai.com rejects `max_tokens` on its reasoning models (o-series, gpt-5) with HTTP 400 and accepts
     * `max_completion_tokens` on every current chat model. Other OpenAI-compatible servers keep the older name.
     */
    private fun maxTokensField(): String {
        val host = runCatching { URL(profile.resolvedBaseUrl.trim()).host }.getOrDefault("")
        return if (host.equals("api.openai.com", ignoreCase = true)) "max_completion_tokens" else "max_tokens"
    }

    private fun normalizeModel(model: String): String = model
        .removePrefix("models/")
        .removePrefix("anthropic/")
        .substringBefore('[')
        .trim()

    private fun errorJson(type: String, message: String) =
        JSONObject().put("type", "error").put("error", JSONObject().put("type", type).put("message", message)).toString()

    override fun close() {
        running.set(false)
        runCatching { server.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("LocalFormatGateway", it) }
        workers.shutdown()
        // shutdown() does not interrupt workers, and HttpURLConnection ignores interrupts anyway: a provider call of
        // up to 180 s would keep using the key and quota after the session ended. Disconnecting aborts it.
        upstreamConnections.toList().forEach { runCatching { it.disconnect() } }
    }

    private companion object {
        const val MAX_CONNECTIONS = 32
        const val MAX_ACCEPT_FAILURES = 50
        const val ACCEPT_BACKOFF_MS = 100L
        const val MAX_REQUEST_BYTES = 8 * 1024 * 1024
        const val MAX_UPSTREAM_RESPONSE_CHARS = 4 * 1024 * 1024
        const val MAX_HEADER_LINE_BYTES = 16 * 1024
        const val SOCKET_TIMEOUT_MS = 30_000
    }
}

/** Socket output that remembers whether a response has started, so a late failure is reported the right way. */
internal class GatewayOutput(out: java.io.OutputStream) : BufferedOutputStream(out) {
    var committed = false
        private set
    var eventStream = false

    override fun write(b: Int) {
        committed = true
        super.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len > 0) committed = true
        super.write(b, off, len)
    }
}

private const val EVENT_STREAM_HEADERS =
    "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"

/** One streamed OpenAI tool call, assembled from its deltas. */
private class PendingToolCall {
    var id = ""
    var name = ""
    val arguments = StringBuilder()
}

/** Anthropic stop_reason for an OpenAI Responses API result. */
private fun responsesStopReason(source: JSONObject, hasToolUse: Boolean): String {
    if (hasToolUse) return "tool_use"
    if (source.stringOrEmpty("status") != "incomplete") return "end_turn"
    return when (source.optJSONObject("incomplete_details")?.stringOrEmpty("reason")) {
        "content_filter" -> "refusal"
        else -> "max_tokens" // max_output_tokens, and any other reason the answer was cut short
    }
}

/**
 * Provider error bodies can echo the request (prompt, file contents) or key material; Logcat is readable by other
 * tools on a debuggable or rooted device, so only a short, redacted excerpt is logged. The client still receives
 * the full provider message in the response.
 */
private fun logSafe(text: String): String = text
    .replace(Regex("(?i)(api[_-]?key|token|secret|password|authorization)(\\s*[=:]\\s*)(bearer\\s+)?[^\\s'\",}]+"), "$1$2••••")
    .replace(Regex("\\b(sk|rk|pk)-[A-Za-z0-9_-]{8,}"), "$1-••••")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(200)
