package com.nextcraft.forge.runtime

import android.util.Log
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.ProviderProtocol
import com.nextcraft.forge.model.stringOrEmpty
import com.nextcraft.forge.network.LocalNetwork
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

internal class LocalFormatGateway(
    private val profile: ProviderProfile,
    private val apiKey: String,
    val sessionToken: String = "forge-local-${UUID.randomUUID()}",
    private val onRotateApiKey: (() -> String?)? = null,
) : AutoCloseable {
    private val isActive = AtomicBoolean(true)
    private val keySwapMonitor = Any()
    private val inflightUpstreams: MutableSet<HttpURLConnection> = ConcurrentHashMap.newKeySet()
    @Volatile private var currentBearerKey: String = apiKey
    private val listenerSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val url: String = "http://127.0.0.1:${listenerSocket.localPort}"

    private val usesResponsesApi: Boolean
        get() = profile.kind.protocol == ProviderProtocol.OPENAI_RESPONSES ||
            profile.resolvedBaseUrl.trim().trimEnd('/').endsWith("/responses")

    private val requestExecutor = ThreadPoolExecutor(
        0, CONCURRENT_WORKER_LIMIT, 30L, TimeUnit.SECONDS, SynchronousQueue(),
    ) { r -> Thread(r, "forge-format-worker").apply { isDaemon = true } }

    fun start(): LocalFormatGateway = apply {
        Thread(::runAcceptLoop, "forge-format-listener").apply {
            isDaemon = true
            start()
        }
    }

    private fun runAcceptLoop() {
        var consecutiveErrors = 0
        while (isActive.get()) {
            val clientSock = try {
                listenerSocket.accept().also { consecutiveErrors = 0 }
            } catch (_: Exception) {
                if (!isActive.get() || listenerSocket.isClosed || ++consecutiveErrors > ACCEPT_FAILURE_CEILING) break
                Thread.sleep(ACCEPT_RETRY_DELAY_MS)
                continue
            }
            runCatching {
                clientSock.soTimeout = CLIENT_SOCKET_TIMEOUT_MS
                clientSock.tcpNoDelay = true
            }.onFailure { ErrorLog.swallowed("LocalFormatGateway", it) }
            try {
                requestExecutor.execute { runCatching { clientSock.use(::dispatchClientConnection) } }
            } catch (_: RejectedExecutionException) {
                runCatching { clientSock.close() }.onFailure { ErrorLog.swallowed("LocalFormatGateway", it) }
            }
        }
    }

    private fun dispatchClientConnection(sock: Socket) {
        val netIn = BufferedInputStream(sock.getInputStream())
        val startLine = readCrLfLine(netIn) ?: return
        val headerMap = HashMap<String, String>()
        while (true) {
            val rawHeader = readCrLfLine(netIn) ?: return
            if (rawHeader.isEmpty()) break
            val colonIdx = rawHeader.indexOf(':')
            if (colonIdx > 0) {
                headerMap[rawHeader.substring(0, colonIdx).lowercase(Locale.ROOT)] = rawHeader.substring(colonIdx + 1).trim()
            }
        }
        val netOut = GatewayOutput(sock.getOutputStream())
        if (!verifyLoopbackCredentials(headerMap)) {
            sendJsonResponse(netOut, 401, buildAnthropicError("authentication_error", "Missing or invalid credentials"))
            return
        }
        if (headerMap["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            netOut.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
            netOut.flush()
        }
        val isChunked = headerMap["transfer-encoding"]?.lowercase(Locale.ROOT)?.contains("chunked") == true
        val rawBody = if (isChunked) {
            decodeChunkedPayload(netIn) ?: run {
                sendJsonResponse(netOut, 400, buildAnthropicError("invalid_request_error", "Malformed or too large chunked request body"))
                return
            }
        } else {
            val declaredLen = headerMap["content-length"]?.toIntOrNull() ?: 0
            if (declaredLen !in 0..REQUEST_BODY_BYTE_CAP) {
                sendJsonResponse(netOut, 413, buildAnthropicError("invalid_request_error", "Request body is too large"))
                return
            }
            val buf = ByteArray(declaredLen)
            var pos = 0
            while (pos < declaredLen) {
                val n = netIn.read(buf, pos, declaredLen - pos)
                if (n < 0) break
                pos += n
            }
            if (pos < declaredLen) {
                sendJsonResponse(netOut, 400, buildAnthropicError("invalid_request_error", "Truncated request body"))
                return
            }
            buf.decodeToString(0, pos)
        }
        val routePath = startLine.split(' ').getOrNull(1).orEmpty().substringBefore('?')
        if (routePath.endsWith("/count_tokens")) {
            val estTokens = rawBody.length / 4 + 1
            sendJsonResponse(netOut, 200, JSONObject().put("input_tokens", estTokens).toString())
            return
        }
        if (!routePath.endsWith("/messages")) {
            sendJsonResponse(netOut, 404, buildAnthropicError("not_found", "Unsupported gateway endpoint"))
            return
        }
        runCatching {
            val anthropicReq = JSONObject(rawBody)
            val targetModel = anthropicReq.stringOrEmpty("model", profile.model)
            val streamingRequested = anthropicReq.optBoolean("stream", false)
            if (streamingRequested && !usesResponsesApi) {
                relayStreamingCompletion(anthropicReq, targetModel, netOut)
                return@runCatching
            }
            val (httpCode, responsePayload) = executeUpstreamCall(convertAnthropicToOpenAi(anthropicReq))
            if (httpCode !in 200..299) {
                val errText = extractUpstreamErrorMessage(responsePayload)
                Log.w("FormatGateway", "Provider returned HTTP $httpCode: ${redactLogExcerpt(errText)}")
                sendJsonResponse(netOut, httpCode, buildAnthropicError("api_error", errText))
            } else {
                val anthropicResp = convertOpenAiToAnthropic(JSONObject(responsePayload), targetModel)
                if (streamingRequested) emitSyntheticSseStream(netOut, anthropicResp)
                else sendJsonResponse(netOut, 200, anthropicResp.toString())
            }
        }.onFailure { err ->
            writeFailure(netOut, err.message ?: "Provider request failed")
        }
    }

    internal fun writeFailure(output: GatewayOutput, message: String) {
        runCatching {
            when {
                !output.committed -> sendJsonResponse(output, 502, buildAnthropicError("api_error", message))
                output.eventStream -> writeSseErrorFrame(output, "api_error", message)
                else -> Unit
            }
        }.onFailure { ErrorLog.swallowed("LocalFormatGateway", it) }
    }

    private fun writeSseErrorFrame(output: BufferedOutputStream, errType: String, errMsg: String) {
        val frame = JSONObject()
            .put("type", "error")
            .put("error", JSONObject().put("type", errType).put("message", errMsg))
        output.write("event: error\ndata: $frame\n\n".toByteArray())
        output.flush()
    }

    private fun beginSseResponse(output: BufferedOutputStream) {
        (output as? GatewayOutput)?.eventStream = true
        output.write(SSE_HTTP_PREAMBLE.toByteArray())
    }

    private fun convertAnthropicToOpenAi(anthropic: JSONObject): JSONObject {
        if (usesResponsesApi) return convertAnthropicToResponsesPayload(anthropic)
        val openAiReq = JSONObject()
            .put("model", stripModelPrefixes(profile.model))
            .put("stream", false)
            .put(resolveTokenLimitFieldName(), anthropic.optInt("max_tokens", 4096))
        if (anthropic.has("temperature")) openAiReq.put("temperature", anthropic.get("temperature"))
        val outMessages = JSONArray()
        anthropic.opt("system")?.let { sysField ->
            val sysText = if (sysField is JSONArray) joinTextBlocks(sysField) else sysField.toString()
            if (sysText.isNotBlank()) outMessages.put(JSONObject().put("role", "system").put("content", sysText))
        }
        val inMessages = anthropic.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until inMessages.length()) {
            val msgObj = inMessages.getJSONObject(i)
            val role = msgObj.stringOrEmpty("role")
            val contentNode = msgObj.opt("content")
            if (contentNode !is JSONArray) {
                outMessages.put(JSONObject().put("role", role).put("content", contentNode ?: ""))
                continue
            }
            val combinedText = joinTextBlocks(contentNode)
            val fnCalls = JSONArray()
            val fnResults = ArrayList<JSONObject>()
            for (pIdx in 0 until contentNode.length()) {
                val block = contentNode.optJSONObject(pIdx) ?: continue
                when (block.stringOrEmpty("type")) {
                    "tool_use" -> fnCalls.put(
                        JSONObject()
                            .put("id", block.stringOrEmpty("id"))
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", block.stringOrEmpty("name"))
                                    .put("arguments", block.optJSONObject("input")?.toString() ?: "{}"),
                            ),
                    )
                    "tool_result" -> fnResults += JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", block.stringOrEmpty("tool_use_id"))
                        .put("content", formatToolResultContent(block))
                }
            }
            if (role == "user") fnResults.forEach(outMessages::put)
            val dataUris = extractImageDataUris(contentNode)
            if (dataUris.isNotEmpty() && role == "user") {
                val multiParts = JSONArray()
                if (combinedText.isNotBlank()) multiParts.put(JSONObject().put("type", "text").put("text", combinedText))
                dataUris.forEach { uri ->
                    multiParts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", uri)))
                }
                outMessages.put(JSONObject().put("role", role).put("content", multiParts))
            } else if (combinedText.isNotBlank() || fnCalls.length() > 0) {
                val msgEntry = JSONObject().put("role", role).put("content", combinedText.ifBlank { JSONObject.NULL })
                if (fnCalls.length() > 0) msgEntry.put("tool_calls", fnCalls)
                outMessages.put(msgEntry)
            }
            if (role != "user") fnResults.forEach(outMessages::put)
        }
        openAiReq.put("messages", outMessages)
        anthropic.optJSONArray("tools")?.let { inTools ->
            val outTools = JSONArray()
            for (i in 0 until inTools.length()) {
                val t = inTools.getJSONObject(i)
                outTools.put(
                    JSONObject().put("type", "function").put(
                        "function",
                        JSONObject()
                            .put("name", t.stringOrEmpty("name"))
                            .put("description", t.stringOrEmpty("description"))
                            .put("parameters", t.optJSONObject("input_schema") ?: JSONObject().put("type", "object")),
                    ),
                )
            }
            openAiReq.put("tools", outTools).put("tool_choice", "auto")
        }
        return openAiReq
    }

    private fun convertAnthropicToResponsesPayload(anthropic: JSONObject): JSONObject {
        val req = JSONObject()
            .put("model", stripModelPrefixes(profile.model))
            .put("stream", false)
            .put("max_output_tokens", anthropic.optInt("max_tokens", 4096))
        if (anthropic.has("temperature")) req.put("temperature", anthropic.get("temperature"))
        anthropic.opt("system")?.let { sysField ->
            val sysText = if (sysField is JSONArray) joinTextBlocks(sysField) else sysField.toString()
            if (sysText.isNotBlank()) req.put("instructions", sysText)
        }
        val inputItems = JSONArray()
        val inMessages = anthropic.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until inMessages.length()) {
            val msgObj = inMessages.getJSONObject(i)
            val role = msgObj.stringOrEmpty("role")
            val contentNode = msgObj.opt("content")
            if (contentNode !is JSONArray) {
                inputItems.put(JSONObject().put("role", role).put("content", contentNode?.toString().orEmpty()))
                continue
            }
            val combinedText = joinTextBlocks(contentNode)
            val fnCalls = ArrayList<JSONObject>()
            val fnOutputs = ArrayList<JSONObject>()
            for (pIdx in 0 until contentNode.length()) {
                val block = contentNode.optJSONObject(pIdx) ?: continue
                when (block.stringOrEmpty("type")) {
                    "tool_use" -> fnCalls += JSONObject()
                        .put("type", "function_call")
                        .put("call_id", block.stringOrEmpty("id"))
                        .put("name", block.stringOrEmpty("name"))
                        .put("arguments", block.optJSONObject("input")?.toString() ?: "{}")
                    "tool_result" -> fnOutputs += JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", block.stringOrEmpty("tool_use_id"))
                        .put("output", formatToolResultContent(block))
                }
            }
            if (role == "user") fnOutputs.forEach(inputItems::put)
            val dataUris = extractImageDataUris(contentNode)
            if (dataUris.isNotEmpty() && role == "user") {
                val parts = JSONArray()
                if (combinedText.isNotBlank()) parts.put(JSONObject().put("type", "input_text").put("text", combinedText))
                dataUris.forEach { uri -> parts.put(JSONObject().put("type", "input_image").put("image_url", uri)) }
                inputItems.put(JSONObject().put("role", role).put("content", parts))
            } else if (combinedText.isNotBlank()) {
                inputItems.put(JSONObject().put("role", role).put("content", combinedText))
            }
            fnCalls.forEach(inputItems::put)
            if (role != "user") fnOutputs.forEach(inputItems::put)
        }
        req.put("input", inputItems)
        anthropic.optJSONArray("tools")?.let { inTools ->
            val outTools = JSONArray()
            for (i in 0 until inTools.length()) {
                val t = inTools.getJSONObject(i)
                outTools.put(
                    JSONObject()
                        .put("type", "function")
                        .put("name", t.stringOrEmpty("name"))
                        .put("description", t.stringOrEmpty("description"))
                        .put("parameters", t.optJSONObject("input_schema") ?: JSONObject().put("type", "object")),
                )
            }
            req.put("tools", outTools).put("tool_choice", "auto")
        }
        return req
    }

    private fun convertOpenAiToAnthropic(openAi: JSONObject, targetModel: String): JSONObject {
        if (openAi.has("output") && !openAi.has("choices")) {
            return convertResponsesPayloadToAnthropic(openAi, targetModel)
        }
        val firstChoice = openAi.optJSONArray("choices")?.optJSONObject(0)
        val msgNode = firstChoice?.optJSONObject("message") ?: JSONObject()
        val blocks = JSONArray()
        val textBody = msgNode.stringOrEmpty("content")
        if (textBody.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", textBody))
        val rawToolCalls = msgNode.optJSONArray("tool_calls") ?: JSONArray()
        val rawFinish = firstChoice?.stringOrEmpty("finish_reason").orEmpty()
        val hitLengthCap = rawFinish == "length"
        for (i in 0 until rawToolCalls.length()) {
            val callNode = rawToolCalls.getJSONObject(i)
            val fnNode = callNode.optJSONObject("function") ?: JSONObject()
            val parsedArgs = runCatching { JSONObject(fnNode.stringOrEmpty("arguments", "{}")) }.getOrNull()
            if (parsedArgs == null && hitLengthCap) continue
            blocks.put(
                JSONObject()
                    .put("type", "tool_use")
                    .put("id", callNode.stringOrEmpty("id").ifBlank { "tool_${UUID.randomUUID()}" })
                    .put("name", fnNode.stringOrEmpty("name"))
                    .put("input", parsedArgs ?: JSONObject()),
            )
        }
        val usageNode = openAi.optJSONObject("usage") ?: JSONObject()
        val mappedStopReason = when {
            hitLengthCap -> "max_tokens"
            rawToolCalls.length() > 0 -> "tool_use"
            rawFinish == "content_filter" -> "refusal"
            else -> "end_turn"
        }
        return JSONObject()
            .put("id", openAi.stringOrEmpty("id").ifBlank { "msg_${UUID.randomUUID()}" })
            .put("type", "message")
            .put("role", "assistant")
            .put("model", targetModel)
            .put("content", blocks)
            .put("stop_reason", mappedStopReason)
            .put("stop_sequence", JSONObject.NULL)
            .put(
                "usage",
                JSONObject()
                    .put("input_tokens", usageNode.optInt("prompt_tokens"))
                    .put("output_tokens", usageNode.optInt("completion_tokens")),
            )
    }

    private fun convertResponsesPayloadToAnthropic(resp: JSONObject, targetModel: String): JSONObject {
        val outputArr = resp.optJSONArray("output") ?: JSONArray()
        val blocks = JSONArray()
        var containsToolUse = false
        for (i in 0 until outputArr.length()) {
            val entry = outputArr.optJSONObject(i) ?: continue
            when (entry.stringOrEmpty("type")) {
                "message" -> {
                    val contentParts = entry.optJSONArray("content")
                    val textVal = if (contentParts != null) joinTextBlocks(contentParts) else entry.stringOrEmpty("content")
                    if (textVal.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", textVal))
                }
                "function_call" -> {
                    containsToolUse = true
                    val argsObj = runCatching { JSONObject(entry.stringOrEmpty("arguments", "{}")) }.getOrDefault(JSONObject())
                    val callId = entry.stringOrEmpty("call_id").ifBlank { entry.stringOrEmpty("id") }.ifBlank { "tool_${UUID.randomUUID()}" }
                    blocks.put(
                        JSONObject()
                            .put("type", "tool_use")
                            .put("id", callId)
                            .put("name", entry.stringOrEmpty("name"))
                            .put("input", argsObj),
                    )
                }
            }
        }
        if (blocks.length() == 0) {
            val fallback = resp.stringOrEmpty("output_text")
            if (fallback.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", fallback))
        }
        val usageNode = resp.optJSONObject("usage") ?: JSONObject()
        val inTok = usageNode.optInt("input_tokens", usageNode.optInt("prompt_tokens"))
        val outTok = usageNode.optInt("output_tokens", usageNode.optInt("completion_tokens"))
        return JSONObject()
            .put("id", resp.stringOrEmpty("id").ifBlank { "msg_${UUID.randomUUID()}" })
            .put("type", "message")
            .put("role", "assistant")
            .put("model", targetModel)
            .put("content", blocks)
            .put("stop_reason", mapResponsesStopReason(resp, containsToolUse))
            .put("stop_sequence", JSONObject.NULL)
            .put("usage", JSONObject().put("input_tokens", inTok).put("output_tokens", outTok))
    }

    private fun executeUpstreamCall(payload: JSONObject, canRetryKey: Boolean = true): Pair<Int, String> {
        val baseClean = profile.resolvedBaseUrl.trim().trimEnd('/')
        val targetUrl = if (usesResponsesApi) {
            "${baseClean.removeSuffix("/responses").removeSuffix("/chat/completions")}/responses"
        } else {
            "${baseClean.removeSuffix("/chat/completions")}/chat/completions"
        }
        if (!LocalNetwork.isPlainHttpAllowed(targetUrl)) {
            return 400 to buildAnthropicError("invalid_request_error", "Plain http:// is only allowed for localhost")
        }
        val keySnapshot = currentBearerKey
        val conn = (URL(targetUrl).openConnection() as HttpURLConnection).also(inflightUpstreams::add).apply {
            instanceFollowRedirects = false
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 180_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (keySnapshot.isNotBlank()) setRequestProperty("Authorization", "Bearer $keySnapshot")
            if (targetUrl.startsWith("https://opencode.ai/zen/")) {
                setRequestProperty("User-Agent", "NextCraft-Forge-Android/1.0")
                setRequestProperty("x-session-id", "session-${UUID.randomUUID()}")
            }
        }
        return try {
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val statusCode = conn.responseCode
            if (canRetryKey && statusCode in setOf(402, 429) && onRotateApiKey != null) {
                if (tryRotateKey(keySnapshot)) {
                    conn.disconnect()
                    return executeUpstreamCall(payload, canRetryKey = false)
                }
            }
            val bodyStream = if (statusCode in 200..299) conn.inputStream else conn.errorStream
            val bodyText = bodyStream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                while (sb.length < UPSTREAM_RESPONSE_CHAR_CAP) {
                    val n = reader.read(buf, 0, minOf(buf.size, UPSTREAM_RESPONSE_CHAR_CAP - sb.length))
                    if (n <= 0) break
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }.orEmpty()
            statusCode to bodyText
        } finally {
            inflightUpstreams.remove(conn)
            conn.disconnect()
        }
    }

    private fun tryRotateKey(failedKey: String): Boolean = synchronized(keySwapMonitor) {
        if (currentBearerKey != failedKey) return@synchronized true
        val nextKey = onRotateApiKey?.invoke()?.takeIf { it.isNotBlank() && it != failedKey }
        if (nextKey != null) {
            currentBearerKey = nextKey
            true
        } else {
            false
        }
    }

    private fun relayStreamingCompletion(
        anthropicReq: JSONObject,
        targetModel: String,
        netOut: BufferedOutputStream,
        canRetryKey: Boolean = true,
    ) {
        val baseClean = profile.resolvedBaseUrl.trim().trimEnd('/')
        val targetUrl = "${baseClean.removeSuffix("/chat/completions")}/chat/completions"
        if (!LocalNetwork.isPlainHttpAllowed(targetUrl)) {
            sendJsonResponse(netOut, 400, buildAnthropicError("invalid_request_error", "Plain http:// is only allowed for localhost"))
            return
        }
        val openAiStreamBody = convertAnthropicToOpenAi(anthropicReq).put("stream", true)
        val keySnapshot = currentBearerKey
        val conn = (URL(targetUrl).openConnection() as HttpURLConnection).also(inflightUpstreams::add).apply {
            instanceFollowRedirects = false
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 180_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream, application/json")
            if (keySnapshot.isNotBlank()) setRequestProperty("Authorization", "Bearer $keySnapshot")
        }
        try {
            conn.outputStream.use { it.write(openAiStreamBody.toString().toByteArray()) }
            val statusCode = conn.responseCode
            if (canRetryKey && statusCode in setOf(402, 429) && onRotateApiKey != null) {
                if (tryRotateKey(keySnapshot)) {
                    conn.disconnect()
                    relayStreamingCompletion(anthropicReq, targetModel, netOut, canRetryKey = false)
                    return
                }
            }
            if (statusCode !in 200..299) {
                val errRaw = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(4096) }.orEmpty()
                val parsedErr = extractUpstreamErrorMessage(errRaw)
                Log.w("FormatGateway", "Provider returned HTTP $statusCode: ${redactLogExcerpt(parsedErr)}")
                sendJsonResponse(netOut, statusCode, buildAnthropicError("api_error", parsedErr))
                return
            }
            val mime = conn.contentType.orEmpty().lowercase(Locale.ROOT)
            val bodyStream = conn.inputStream ?: run {
                sendJsonResponse(netOut, 502, buildAnthropicError("api_error", "Empty response from provider"))
                return
            }
            if (mime.contains("text/event-stream")) {
                bodyStream.bufferedReader(Charsets.UTF_8).useLines { seq ->
                    streamOpenAiSseLines(seq, netOut, targetModel)
                }
            } else {
                val fullText = bodyStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val sb = StringBuilder()
                    val buf = CharArray(8192)
                    while (sb.length < UPSTREAM_RESPONSE_CHAR_CAP) {
                        val n = reader.read(buf, 0, minOf(buf.size, UPSTREAM_RESPONSE_CHAR_CAP - sb.length))
                        if (n <= 0) break
                        sb.append(buf, 0, n)
                    }
                    sb.toString()
                }
                val leading = fullText.trimStart()
                if (leading.startsWith("data:") || leading.startsWith(":")) {
                    streamOpenAiSseLines(fullText.lineSequence(), netOut, targetModel)
                } else {
                    emitSyntheticSseStream(netOut, convertOpenAiToAnthropic(JSONObject(fullText), targetModel))
                }
            }
        } finally {
            inflightUpstreams.remove(conn)
            conn.disconnect()
        }
    }

    internal fun streamOpenAiSseLines(
        lines: Sequence<String>,
        output: BufferedOutputStream,
        requestedModel: String,
    ) {
        beginSseResponse(output)
        fun sendSseFrame(eventName: String, payload: JSONObject) {
            output.write("event: $eventName\ndata: $payload\n\n".toByteArray())
            output.flush()
        }
        val messageEnvelope = JSONObject()
            .put("id", "msg_${UUID.randomUUID()}")
            .put("type", "message")
            .put("role", "assistant")
            .put("model", requestedModel)
            .put("content", JSONArray())
            .put("stop_reason", JSONObject.NULL)
            .put("stop_sequence", JSONObject.NULL)
            .put("usage", JSONObject().put("input_tokens", 0).put("output_tokens", 0))
        sendSseFrame("message_start", JSONObject().put("type", "message_start").put("message", messageEnvelope))

        var blockCursor = 0
        var activeBlockKind: String? = null
        var activeBlockIdx = -1
        val bufferedTools = HashMap<Int, AssembledToolCall>()
        var observedToolCall = false
        var hitTokenLimit = false
        var hitSafetyFilter = false
        var streamTerminatedCleanly = false
        var promptTokens = 0
        var completionTokens = 0

        fun finishCurrentBlock() {
            if (activeBlockIdx >= 0) {
                sendSseFrame("content_block_stop", JSONObject().put("type", "content_block_stop").put("index", activeBlockIdx))
                activeBlockKind = null
                activeBlockIdx = -1
            }
        }

        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (!trimmed.startsWith("data:")) continue
            val dataStr = trimmed.removePrefix("data:").trim()
            if (dataStr.isEmpty()) continue
            if (dataStr == "[DONE]") {
                streamTerminatedCleanly = true
                break
            }
            val eventJson = runCatching { JSONObject(dataStr) }.getOrNull() ?: continue
            eventJson.optJSONObject("error")?.let { errObj ->
                finishCurrentBlock()
                val msg = errObj.stringOrEmpty("message").ifEmpty { errObj.toString().take(500) }
                Log.w("FormatGateway", "Provider reported an error mid-stream: ${redactLogExcerpt(msg)}")
                writeSseErrorFrame(output, "api_error", msg)
                return
            }
            eventJson.optJSONObject("usage")?.let { u ->
                promptTokens = u.optInt("prompt_tokens", u.optInt("input_tokens", promptTokens))
                completionTokens = u.optInt("completion_tokens", u.optInt("output_tokens", completionTokens))
            }
            val choiceObj = eventJson.optJSONArray("choices")?.optJSONObject(0) ?: continue
            val finishReason = choiceObj.stringOrEmpty("finish_reason")
            if (finishReason.isNotEmpty()) streamTerminatedCleanly = true
            if (finishReason == "length") hitTokenLimit = true
            if (finishReason == "content_filter") hitSafetyFilter = true
            val deltaObj = choiceObj.optJSONObject("delta") ?: choiceObj.optJSONObject("message") ?: continue

            val thoughtFragment = deltaObj.stringOrEmpty("reasoning_content").ifEmpty { deltaObj.stringOrEmpty("reasoning") }
            if (thoughtFragment.isNotEmpty()) {
                if (activeBlockKind != "thinking") {
                    finishCurrentBlock()
                    val idx = blockCursor++
                    activeBlockKind = "thinking"
                    activeBlockIdx = idx
                    sendSseFrame(
                        "content_block_start",
                        JSONObject()
                            .put("type", "content_block_start")
                            .put("index", idx)
                            .put("content_block", JSONObject().put("type", "thinking").put("thinking", "")),
                    )
                }
                sendSseFrame(
                    "content_block_delta",
                    JSONObject()
                        .put("type", "content_block_delta")
                        .put("index", activeBlockIdx)
                        .put("delta", JSONObject().put("type", "thinking_delta").put("thinking", thoughtFragment)),
                )
            }

            val textFragment = deltaObj.stringOrEmpty("content")
            if (textFragment.isNotEmpty()) {
                if (activeBlockKind != "text") {
                    finishCurrentBlock()
                    val idx = blockCursor++
                    activeBlockKind = "text"
                    activeBlockIdx = idx
                    sendSseFrame(
                        "content_block_start",
                        JSONObject()
                            .put("type", "content_block_start")
                            .put("index", idx)
                            .put("content_block", JSONObject().put("type", "text").put("text", "")),
                    )
                }
                sendSseFrame(
                    "content_block_delta",
                    JSONObject()
                        .put("type", "content_block_delta")
                        .put("index", activeBlockIdx)
                        .put("delta", JSONObject().put("type", "text_delta").put("text", textFragment)),
                )
            }

            val deltaTools = deltaObj.optJSONArray("tool_calls")
            if (deltaTools != null && deltaTools.length() > 0) {
                for (tIdx in 0 until deltaTools.length()) {
                    val tcNode = deltaTools.optJSONObject(tIdx) ?: continue
                    observedToolCall = true
                    val slot = bufferedTools.getOrPut(tcNode.optInt("index", tIdx)) { AssembledToolCall() }
                    val fnNode = tcNode.optJSONObject("function")
                    tcNode.stringOrEmpty("id").takeIf { it.isNotBlank() && slot.callId.isBlank() }?.let { slot.callId = it }
                    fnNode?.stringOrEmpty("name")?.takeIf { it.isNotBlank() && slot.fnName.isBlank() }?.let { slot.fnName = it }
                    fnNode?.stringOrEmpty("arguments")?.let { slot.rawArgs.append(it) }
                }
            }
        }

        finishCurrentBlock()
        if (!streamTerminatedCleanly) {
            Log.w("FormatGateway", "Provider stream ended before it finished")
            writeSseErrorFrame(output, "api_error", "The provider closed the stream before the response finished")
            return
        }
        for ((_, toolSlot) in bufferedTools.toSortedMap()) {
            if (hitTokenLimit && toolSlot.rawArgs.isNotEmpty() && runCatching { JSONObject(toolSlot.rawArgs.toString()) }.isFailure) continue
            val idx = blockCursor++
            sendSseFrame(
                "content_block_start",
                JSONObject()
                    .put("type", "content_block_start")
                    .put("index", idx)
                    .put(
                        "content_block",
                        JSONObject()
                            .put("type", "tool_use")
                            .put("id", toolSlot.callId.ifBlank { "tool_${UUID.randomUUID()}" })
                            .put("name", toolSlot.fnName)
                            .put("input", JSONObject()),
                    ),
            )
            if (toolSlot.rawArgs.isNotEmpty()) {
                sendSseFrame(
                    "content_block_delta",
                    JSONObject()
                        .put("type", "content_block_delta")
                        .put("index", idx)
                        .put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", toolSlot.rawArgs.toString())),
                )
            }
            sendSseFrame("content_block_stop", JSONObject().put("type", "content_block_stop").put("index", idx))
        }
        val finalStopReason = when {
            hitTokenLimit -> "max_tokens"
            observedToolCall -> "tool_use"
            hitSafetyFilter -> "refusal"
            else -> "end_turn"
        }
        sendSseFrame(
            "message_delta",
            JSONObject()
                .put("type", "message_delta")
                .put("delta", JSONObject().put("stop_reason", finalStopReason).put("stop_sequence", JSONObject.NULL))
                .put("usage", JSONObject().put("input_tokens", promptTokens).put("output_tokens", completionTokens)),
        )
        sendSseFrame("message_stop", JSONObject().put("type", "message_stop"))
    }

    private fun emitSyntheticSseStream(output: BufferedOutputStream, anthropicMsg: JSONObject) {
        beginSseResponse(output)
        fun sendSseFrame(eventName: String, payload: JSONObject) {
            output.write("event: $eventName\ndata: $payload\n\n".toByteArray())
            output.flush()
        }
        val blocks = anthropicMsg.getJSONArray("content")
        val initHeader = JSONObject(anthropicMsg.toString())
            .put("content", JSONArray())
            .put("stop_reason", JSONObject.NULL)
        sendSseFrame("message_start", JSONObject().put("type", "message_start").put("message", initHeader))
        for (idx in 0 until blocks.length()) {
            val block = blocks.getJSONObject(idx)
            val kind = block.getString("type")
            val startBlock = if (kind == "text") {
                JSONObject().put("type", "text").put("text", "")
            } else {
                JSONObject()
                    .put("type", "tool_use")
                    .put("id", block.getString("id"))
                    .put("name", block.getString("name"))
                    .put("input", JSONObject())
            }
            sendSseFrame("content_block_start", JSONObject().put("type", "content_block_start").put("index", idx).put("content_block", startBlock))
            val deltaBlock = if (kind == "text") {
                JSONObject().put("type", "text_delta").put("text", block.getString("text"))
            } else {
                JSONObject().put("type", "input_json_delta").put("partial_json", block.getJSONObject("input").toString())
            }
            sendSseFrame("content_block_delta", JSONObject().put("type", "content_block_delta").put("index", idx).put("delta", deltaBlock))
            sendSseFrame("content_block_stop", JSONObject().put("type", "content_block_stop").put("index", idx))
        }
        sendSseFrame(
            "message_delta",
            JSONObject()
                .put("type", "message_delta")
                .put("delta", JSONObject().put("stop_reason", anthropicMsg.getString("stop_reason")).put("stop_sequence", JSONObject.NULL))
                .put("usage", anthropicMsg.getJSONObject("usage")),
        )
        sendSseFrame("message_stop", JSONObject().put("type", "message_stop"))
        output.flush()
    }

    private fun sendJsonResponse(output: BufferedOutputStream, statusCode: Int, jsonBody: String) {
        val payloadBytes = jsonBody.toByteArray()
        val phrase = if (statusCode in 200..299) "OK" else "Error"
        output.write(
            "HTTP/1.1 $statusCode $phrase\r\nContent-Type: application/json\r\nContent-Length: ${payloadBytes.size}\r\nConnection: close\r\n\r\n"
                .toByteArray(),
        )
        output.write(payloadBytes)
        output.flush()
    }

    private fun verifyLoopbackCredentials(headers: Map<String, String>): Boolean {
        val candidate = headers["x-api-key"]
            ?: headers["authorization"]?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
            ?: return false
        val candidateBytes = candidate.toByteArray()
        return MessageDigest.isEqual(candidateBytes, sessionToken.toByteArray()) ||
            MessageDigest.isEqual(candidateBytes, currentBearerKey.toByteArray()) ||
            MessageDigest.isEqual(candidateBytes, apiKey.toByteArray())
    }

    private fun decodeChunkedPayload(input: BufferedInputStream): String? {
        val accumulator = ByteArrayOutputStream()
        while (true) {
            val hexHeader = readCrLfLine(input) ?: return null
            val chunkLen = hexHeader.substringBefore(';').trim().toIntOrNull(16) ?: return null
            if (chunkLen < 0 || accumulator.size() + chunkLen > REQUEST_BODY_BYTE_CAP) return null
            if (chunkLen == 0) {
                while (true) {
                    val trailer = readCrLfLine(input) ?: return null
                    if (trailer.isEmpty()) break
                }
                return accumulator.toByteArray().decodeToString()
            }
            val buf = ByteArray(chunkLen)
            var pos = 0
            while (pos < chunkLen) {
                val n = input.read(buf, pos, chunkLen - pos)
                if (n < 0) return null
                pos += n
            }
            accumulator.write(buf)
            if (readCrLfLine(input) != "") return null
        }
    }

    private fun readCrLfLine(input: BufferedInputStream): String? {
        val lineBuf = ByteArrayOutputStream(128)
        while (lineBuf.size() <= HEADER_LINE_BYTE_CAP) {
            val b = input.read()
            if (b < 0) return if (lineBuf.size() == 0) null else lineBuf.toByteArray().decodeToString()
            if (b == '\n'.code) return lineBuf.toByteArray().decodeToString().trimEnd('\r')
            lineBuf.write(b)
        }
        return null
    }

    private fun joinTextBlocks(arr: JSONArray): String = buildString {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.stringOrEmpty("type") in setOf("text", "output_text")) {
                append(obj.stringOrEmpty("text"))
            }
        }
    }

    private fun formatToolResultContent(block: JSONObject): String {
        val rawText = coerceNodeToText(block.opt("content"))
        return if (block.optBoolean("is_error", false)) "Error: $rawText" else rawText
    }

    private fun extractImageDataUris(arr: JSONArray): List<String> = buildList {
        for (i in 0 until arr.length()) {
            val block = arr.optJSONObject(i) ?: continue
            if (block.stringOrEmpty("type") != "image") continue
            val src = block.optJSONObject("source") ?: continue
            when (src.stringOrEmpty("type")) {
                "base64" -> {
                    val b64 = src.stringOrEmpty("data")
                    if (b64.isNotEmpty()) add("data:${src.stringOrEmpty("media_type", "image/png")};base64,$b64")
                }
                "url" -> src.stringOrEmpty("url").takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private fun coerceNodeToText(node: Any?): String = when (node) {
        is String -> node
        is JSONArray -> joinTextBlocks(node).ifBlank { node.toString() }
        null, JSONObject.NULL -> ""
        else -> node.toString()
    }

    private fun extractUpstreamErrorMessage(raw: String): String = runCatching {
        JSONObject(raw).optJSONObject("error")?.stringOrEmpty("message").orEmpty().ifBlank { raw.take(500) }
    }.getOrDefault(raw.take(500))

    private fun resolveTokenLimitFieldName(): String {
        val hostname = runCatching { URL(profile.resolvedBaseUrl.trim()).host }.getOrDefault("")
        return if (hostname.equals("api.openai.com", ignoreCase = true)) "max_completion_tokens" else "max_tokens"
    }

    private fun stripModelPrefixes(rawModel: String): String = rawModel
        .removePrefix("models/")
        .removePrefix("anthropic/")
        .substringBefore('[')
        .trim()

    private fun buildAnthropicError(errType: String, errMsg: String): String =
        JSONObject()
            .put("type", "error")
            .put("error", JSONObject().put("type", errType).put("message", errMsg))
            .toString()

    override fun close() {
        isActive.set(false)
        runCatching { listenerSocket.close() }.onFailure { ErrorLog.swallowed("LocalFormatGateway", it) }
        requestExecutor.shutdown()
        inflightUpstreams.toList().forEach { runCatching { it.disconnect() } }
    }

    private companion object {
        const val CONCURRENT_WORKER_LIMIT = 32
        const val ACCEPT_FAILURE_CEILING = 50
        const val ACCEPT_RETRY_DELAY_MS = 100L
        const val REQUEST_BODY_BYTE_CAP = 8 * 1024 * 1024
        const val UPSTREAM_RESPONSE_CHAR_CAP = 4 * 1024 * 1024
        const val HEADER_LINE_BYTE_CAP = 16 * 1024
        const val CLIENT_SOCKET_TIMEOUT_MS = 30_000
    }
}

internal class GatewayOutput(out: OutputStream) : BufferedOutputStream(out) {
    var committed: Boolean = false
        private set
    var eventStream: Boolean = false

    override fun write(b: Int) {
        committed = true
        super.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len > 0) committed = true
        super.write(b, off, len)
    }
}

private const val SSE_HTTP_PREAMBLE =
    "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"

private class AssembledToolCall {
    var callId: String = ""
    var fnName: String = ""
    val rawArgs: StringBuilder = StringBuilder()
}

private fun mapResponsesStopReason(resp: JSONObject, hasToolCall: Boolean): String {
    if (hasToolCall) return "tool_use"
    if (resp.stringOrEmpty("status") != "incomplete") return "end_turn"
    return when (resp.optJSONObject("incomplete_details")?.stringOrEmpty("reason")) {
        "content_filter" -> "refusal"
        else -> "max_tokens"
    }
}

private fun redactLogExcerpt(raw: String): String = raw
    .replace(Regex("(?i)(api[_-]?key|token|secret|password|authorization)(\\s*[=:]\\s*)(bearer\\s+)?[^\\s'\",}]+"), "$1$2••••")
    .replace(Regex("\\b(sk|rk|pk)-[A-Za-z0-9_-]{8,}"), "$1-••••")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(200)
