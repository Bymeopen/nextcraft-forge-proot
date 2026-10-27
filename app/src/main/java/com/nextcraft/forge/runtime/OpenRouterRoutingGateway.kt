package com.nextcraft.forge.runtime

import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.network.LocalNetwork
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
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

internal fun applyOpenRouterRouting(source: JSONObject, profile: ProviderProfile): JSONObject {
    val slugList = profile.openRouterProviders
    if (slugList.isEmpty()) return source
    val providerSpec = JSONObject()
        .put("order", JSONArray(slugList))
        .put("allow_fallbacks", profile.openRouterAllowFallbacks)
    return source.put("provider", providerSpec)
}

internal class OpenRouterRoutingGateway(
    private val profile: ProviderProfile,
    private val apiKey: String,
    val sessionToken: String = "forge-local-${UUID.randomUUID()}",
    private val onRotateApiKey: (() -> String?)? = null,
) : AutoCloseable {
    private val isActive = AtomicBoolean(true)
    private val keySwapMutex = Any()
    @Volatile private var currentUpstreamKey: String = apiKey

    private val listenSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val url: String = "http://127.0.0.1:${listenSocket.localPort}"

    private val activeUpstreamCalls: MutableSet<HttpURLConnection> = ConcurrentHashMap.newKeySet()

    private val requestExecutor = ThreadPoolExecutor(
        0,
        MAX_WORKER_THREADS,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
    ) { runnable ->
        Thread(runnable, "forge-or-worker").apply { isDaemon = true }
    }

    fun start(): OpenRouterRoutingGateway = apply {
        Thread(::runAcceptLoop, "forge-or-acceptor").apply {
            isDaemon = true
            start()
        }
    }

    private fun runAcceptLoop() {
        var consecutiveErrors = 0
        while (isActive.get()) {
            val clientSock = try {
                listenSocket.accept().also { consecutiveErrors = 0 }
            } catch (_: Exception) {
                if (!isActive.get() || listenSocket.isClosed || ++consecutiveErrors > ACCEPT_ERROR_LIMIT) {
                    break
                }
                Thread.sleep(ACCEPT_PAUSE_MS)
                continue
            }
            runCatching {
                clientSock.soTimeout = CLIENT_SO_TIMEOUT_MS
                clientSock.tcpNoDelay = true
            }.onFailure { ErrorLog.swallowed(LOG_TAG, it) }

            try {
                requestExecutor.execute {
                    runCatching { clientSock.use(::serveClientConnection) }
                }
            } catch (_: RejectedExecutionException) {
                runCatching { clientSock.close() }.onFailure { ErrorLog.swallowed(LOG_TAG, it) }
            }
        }
    }

    private fun serveClientConnection(sock: Socket) {
        val reader = BufferedInputStream(sock.getInputStream())
        val reqLine = readCrLfLine(reader) ?: return
        val tokens = reqLine.split(' ')
        val httpMethod = tokens.getOrNull(0).orEmpty()
        val reqPath = tokens.getOrNull(1).orEmpty().substringBefore('?')

        val reqHeaders = HashMap<String, String>()
        while (true) {
            val hdrLine = readCrLfLine(reader) ?: return
            if (hdrLine.isEmpty()) break
            val colonIdx = hdrLine.indexOf(':')
            if (colonIdx > 0) {
                val hdrName = hdrLine.substring(0, colonIdx).lowercase(Locale.US)
                reqHeaders[hdrName] = hdrLine.substring(colonIdx + 1).trim()
            }
        }

        val respOut = GatewayOutput(sock.getOutputStream())
        if (!verifyLoopbackCredentials(reqHeaders)) {
            sendJsonResponse(respOut, 401, buildErrorPayload("Missing or invalid loopback gateway credentials"))
            return
        }

        if (reqHeaders["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            respOut.write("HTTP/1.1 100 Continue\r\n\r\n".encodeToByteArray())
            respOut.flush()
        }

        val rawBody: ByteArray
        val bodyLen: Int
        val isChunked = reqHeaders["transfer-encoding"]?.lowercase(Locale.US)?.contains("chunked") == true
        if (isChunked) {
            val decoded = decodeChunkedStream(reader)
            if (decoded == null) {
                sendJsonResponse(respOut, 400, buildErrorPayload("Malformed or too large chunked request body"))
                return
            }
            rawBody = decoded
            bodyLen = decoded.size
        } else {
            val declaredLen = reqHeaders["content-length"]?.toIntOrNull() ?: 0
            if (declaredLen !in 0..BODY_SIZE_LIMIT_BYTES) {
                sendJsonResponse(respOut, 413, buildErrorPayload("Request is too large"))
                return
            }
            rawBody = ByteArray(declaredLen)
            var cursor = 0
            while (cursor < declaredLen) {
                val n = reader.read(rawBody, cursor, declaredLen - cursor)
                if (n < 0) break
                cursor += n
            }
            if (cursor < declaredLen) {
                sendJsonResponse(respOut, 400, buildErrorPayload("Truncated request body"))
                return
            }
            bodyLen = cursor
        }

        if (reqPath.endsWith("/count_tokens")) {
            val approxTokens = rawBody.decodeToString(0, bodyLen).length / 4 + 1
            sendJsonResponse(respOut, 200, JSONObject().put("input_tokens", approxTokens).toString())
            return
        }

        val targetsCompletions = reqPath == "/chat/completions" || reqPath == "/v1/chat/completions"
        val targetsMessages = reqPath == "/messages" || reqPath == "/v1/messages"
        if (httpMethod != "POST" || (!targetsMessages && !targetsCompletions)) {
            sendJsonResponse(respOut, 404, buildErrorPayload("Unsupported OpenRouter endpoint"))
            return
        }

        runCatching {
            val parsedJson = JSONObject(rawBody.decodeToString(0, bodyLen))
            val enrichedPayload = applyOpenRouterRouting(parsedJson, profile).toString().encodeToByteArray()
            val strippedBase = profile.resolvedBaseUrl.trim().trimEnd('/')
                .removeSuffix("/chat/completions")
                .removeSuffix("/messages")
                .removeSuffix("/v1")
            val targetUri = if (targetsCompletions) "$strippedBase/v1/chat/completions" else "$strippedBase/v1/messages"

            if (!LocalNetwork.isPlainHttpAllowed(targetUri)) {
                sendJsonResponse(respOut, 400, buildErrorPayload("Plain http:// is only allowed for localhost"))
                return
            }
            if (!relayToOpenRouter(targetUri, reqHeaders, enrichedPayload, respOut, canRotateKey = true)) {
                emitStreamOrHttpError(respOut, "OpenRouter upstream request failed")
            }
        }.onFailure { err ->
            emitStreamOrHttpError(respOut, err.message ?: "OpenRouter request failed")
        }
    }

    private fun relayToOpenRouter(
        targetUri: String,
        clientHeaders: Map<String, String>,
        payloadBytes: ByteArray,
        respOut: GatewayOutput,
        canRotateKey: Boolean,
    ): Boolean {
        val keySnapshot = currentUpstreamKey
        val conn = (URL(targetUri).openConnection() as HttpURLConnection).also(activeUpstreamCalls::add)
        conn.instanceFollowRedirects = false

        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 180_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", clientHeaders["accept"] ?: "application/json")
            conn.setRequestProperty("Authorization", "Bearer $keySnapshot")
            conn.setRequestProperty("anthropic-version", clientHeaders["anthropic-version"] ?: "2023-06-01")
            clientHeaders["anthropic-beta"]?.let { conn.setRequestProperty("anthropic-beta", it) }
            conn.outputStream.use { it.write(payloadBytes) }

            val status = conn.responseCode
            if (canRotateKey && (status == 402 || status == 429) && onRotateApiKey != null) {
                val rotatedSuccessfully = synchronized(keySwapMutex) {
                    if (currentUpstreamKey == keySnapshot) {
                        val nextKey = onRotateApiKey.invoke()?.takeIf { it.isNotBlank() && it != keySnapshot }
                        if (nextKey != null) {
                            currentUpstreamKey = nextKey
                            true
                        } else {
                            false
                        }
                    } else {
                        true
                    }
                }
                if (rotatedSuccessfully) {
                    conn.disconnect()
                    return relayToOpenRouter(targetUri, clientHeaders, payloadBytes, respOut, canRotateKey = false)
                }
            }

            val bodyStream = if (status in 200..299) conn.inputStream else conn.errorStream
            val statusPhrase = if (status in 200..299) "OK" else "Error"
            val mimeType = conn.contentType ?: "application/json"
            respOut.eventStream = mimeType.contains("text/event-stream", ignoreCase = true)

            val responseHeader = "HTTP/1.1 $status $statusPhrase\r\n" +
                "Content-Type: $mimeType\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n\r\n"
            respOut.write(responseHeader.encodeToByteArray())
            respOut.flush()

            bodyStream?.use { upstreamIn ->
                val chunkBuf = ByteArray(4096)
                while (true) {
                    val got = upstreamIn.read(chunkBuf)
                    if (got <= 0) break
                    respOut.write(chunkBuf, 0, got)
                    respOut.flush()
                }
            }
            respOut.flush()
            return true
        } finally {
            activeUpstreamCalls.remove(conn)
            conn.disconnect()
        }
    }

    private fun verifyLoopbackCredentials(headers: Map<String, String>): Boolean {
        val tokenHeader = headers["x-api-key"]
            ?: headers["authorization"]?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
            ?: return false
        val candidateBytes = tokenHeader.encodeToByteArray()
        return MessageDigest.isEqual(candidateBytes, sessionToken.encodeToByteArray()) ||
            MessageDigest.isEqual(candidateBytes, currentUpstreamKey.encodeToByteArray()) ||
            MessageDigest.isEqual(candidateBytes, apiKey.encodeToByteArray())
    }

    private fun emitStreamOrHttpError(respOut: GatewayOutput, reason: String) {
        runCatching {
            when {
                !respOut.committed -> sendJsonResponse(respOut, 502, buildErrorPayload(reason))
                respOut.eventStream -> {
                    val errData = buildErrorPayload(reason)
                    respOut.write("event: error\ndata: $errData\n\n".encodeToByteArray())
                    respOut.flush()
                }
            }
        }.onFailure { ErrorLog.swallowed(LOG_TAG, it) }
    }

    private fun decodeChunkedStream(reader: BufferedInputStream): ByteArray? {
        val accumulator = ByteArrayOutputStream()
        while (true) {
            val hexHeader = readCrLfLine(reader) ?: return null
            val chunkBytes = hexHeader.substringBefore(';').trim().toIntOrNull(16) ?: return null
            if (chunkBytes < 0 || accumulator.size() + chunkBytes > BODY_SIZE_LIMIT_BYTES) return null
            if (chunkBytes == 0) {
                while (true) {
                    val trailerLine = readCrLfLine(reader) ?: return null
                    if (trailerLine.isEmpty()) break
                }
                return accumulator.toByteArray()
            }
            val block = ByteArray(chunkBytes)
            var totalRead = 0
            while (totalRead < chunkBytes) {
                val n = reader.read(block, totalRead, chunkBytes - totalRead)
                if (n < 0) return null
                totalRead += n
            }
            accumulator.write(block)
            if (readCrLfLine(reader) != "") return null
        }
    }

    private fun sendJsonResponse(out: BufferedOutputStream, statusCode: Int, jsonBody: String) {
        val bodyBytes = jsonBody.encodeToByteArray()
        val phrase = if (statusCode in 200..299) "OK" else "Error"
        val headerBlock = "HTTP/1.1 $statusCode $phrase\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bodyBytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(headerBlock.encodeToByteArray())
        out.write(bodyBytes)
        out.flush()
    }

    private fun readCrLfLine(reader: BufferedInputStream): String? {
        val lineBuf = ByteArrayOutputStream(128)
        while (lineBuf.size() <= HEADER_LINE_LIMIT_BYTES) {
            val b = reader.read()
            if (b < 0) {
                return if (lineBuf.size() == 0) null else lineBuf.toByteArray().decodeToString()
            }
            if (b == '\n'.code) {
                return lineBuf.toByteArray().decodeToString().trimEnd('\r')
            }
            lineBuf.write(b)
        }
        return null
    }

    private fun buildErrorPayload(reason: String): String = JSONObject()
        .put("type", "error")
        .put("error", JSONObject().put("type", "api_error").put("message", reason))
        .toString()

    override fun close() {
        isActive.set(false)
        runCatching { listenSocket.close() }.onFailure { ErrorLog.swallowed(LOG_TAG, it) }
        requestExecutor.shutdown()
        for (conn in activeUpstreamCalls.toList()) {
            runCatching { conn.disconnect() }
        }
    }

    private companion object {
        const val LOG_TAG = "OpenRouterRoutingGateway"
        const val MAX_WORKER_THREADS = 32
        const val ACCEPT_ERROR_LIMIT = 50
        const val ACCEPT_PAUSE_MS = 100L
        const val BODY_SIZE_LIMIT_BYTES = 16 * 1024 * 1024
        const val HEADER_LINE_LIMIT_BYTES = 16 * 1024
        const val CLIENT_SO_TIMEOUT_MS = 30_000
    }
}
