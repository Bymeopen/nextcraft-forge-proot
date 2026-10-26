package com.nextcraft.forge.runtime

import com.nextcraft.forge.model.ProviderProfile
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Adds NextCraft Forge's saved OpenRouter routing policy to Anthropic Messages requests. */
internal fun applyOpenRouterRouting(source: JSONObject, profile: ProviderProfile): JSONObject {
    val providers = profile.openRouterProviders
    if (providers.isEmpty()) return source
    val routing = JSONObject()
        .put("order", JSONArray(providers))
        .put("allow_fallbacks", profile.openRouterAllowFallbacks)
    return source.put("provider", routing)
}

/** Loopback proxy used because coding-agent CLIs do not expose OpenRouter's provider body field. */
internal class OpenRouterRoutingGateway(
    private val profile: ProviderProfile,
    private val apiKey: String,
    val sessionToken: String = "forge-local-${java.util.UUID.randomUUID()}",
    private val onRotateApiKey: (() -> String?)? = null,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val rotationLock = Any()
    @Volatile private var activeApiKey: String = apiKey
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val url: String = "http://127.0.0.1:${server.localPort}"

    fun start(): OpenRouterRoutingGateway = apply {
        Thread({ acceptLoop() }, "forge-openrouter-routing").apply { isDaemon = true; start() }
    }

    // Bounded pool: caps concurrent connections instead of one unbounded thread per accept().
    private val workers = java.util.concurrent.ThreadPoolExecutor(
        0, MAX_CONNECTIONS, 30L, TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue(),
    ) { task -> Thread(task, "forge-openrouter-request").apply { isDaemon = true } }

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
            }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("OpenRouterRoutingGateway", it) }
            try {
                workers.execute { runCatching { socket.use(::handle) } } // client disconnects must not escape as uncaught exceptions (crashes the app)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                runCatching { socket.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("OpenRouterRoutingGateway", it) }
            }
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty().substringBefore('?')
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val split = line.indexOf(':')
            if (split > 0) headers[line.substring(0, split).lowercase(Locale.US)] = line.substring(split + 1).trim()
        }
        val output = GatewayOutput(socket.getOutputStream())
        if (!isAuthorized(headers)) {
            writeJson(output, 401, errorJson("Missing or invalid loopback gateway credentials"))
            return
        }
        if (headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            // Without this the client waits for a go-ahead until the socket timeout before sending the body.
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
            output.flush()
        }
        val body: ByteArray
        var offset: Int
        if (headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true) {
            // No Content-Length: reading it as 0 would turn the request into an empty body.
            body = readChunkedBody(input) ?: run {
                writeJson(output, 400, errorJson("Malformed or too large chunked request body"))
                return
            }
            offset = body.size
        } else {
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length !in 0..MAX_REQUEST_BYTES) {
                writeJson(output, 413, errorJson("Request is too large"))
                return
            }
            body = ByteArray(length)
            offset = 0
            while (offset < length) {
                val count = input.read(body, offset, length - offset)
                if (count < 0) break
                offset += count
            }
            if (offset < length) {
                writeJson(output, 400, errorJson("Truncated request body"))
                return
            }
        }
        if (path.endsWith("/count_tokens")) {
            writeJson(output, 200, JSONObject().put("input_tokens", body.decodeToString(0, offset).length / 4 + 1).toString())
            return
        }
        val isChatCompletions = path == "/chat/completions" || path == "/v1/chat/completions"
        val isMessages = path == "/messages" || path == "/v1/messages"
        if (method != "POST" || (!isMessages && !isChatCompletions)) {
            writeJson(output, 404, errorJson("Unsupported OpenRouter endpoint"))
            return
        }

        runCatching {
            val routedBody = applyOpenRouterRouting(JSONObject(body.decodeToString(0, offset)), profile).toString().toByteArray()
            val normalizedBase = profile.resolvedBaseUrl.trim().trimEnd('/')
                .removeSuffix("/chat/completions")
                .removeSuffix("/messages")
                .removeSuffix("/v1")
            val endpoint = if (isChatCompletions) "$normalizedBase/v1/chat/completions" else "$normalizedBase/v1/messages"
            if (!com.nextcraft.forge.network.LocalNetwork.isPlainHttpAllowed(endpoint)) {
                writeJson(output, 400, errorJson("Plain http:// is only allowed for localhost"))
                return
            }
            if (!forwardUpstream(endpoint, headers, routedBody, output, allowRetry = true)) {
                writeFailure(output, "OpenRouter upstream request failed")
            }
        }.onFailure { error ->
            writeFailure(output, error.message ?: "OpenRouter request failed")
        }
    }

    private fun forwardUpstream(
        endpoint: String,
        headers: Map<String, String>,
        routedBody: ByteArray,
        output: GatewayOutput,
        allowRetry: Boolean,
    ): Boolean {
        val currentKey = activeApiKey
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).also(upstreamConnections::add).apply { instanceFollowRedirects = false }
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 180_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", headers["accept"] ?: "application/json")
            connection.setRequestProperty("Authorization", "Bearer $currentKey")
            connection.setRequestProperty("anthropic-version", headers["anthropic-version"] ?: "2023-06-01")
            headers["anthropic-beta"]?.let { connection.setRequestProperty("anthropic-beta", it) }
            connection.outputStream.use { it.write(routedBody) }

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
                    return forwardUpstream(endpoint, headers, routedBody, output, allowRetry = false)
                }
            }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val reason = if (code in 200..299) "OK" else "Error"
            val contentType = connection.contentType ?: "application/json"
            output.eventStream = contentType.contains("text/event-stream", ignoreCase = true)
            output.write(
                "HTTP/1.1 $code $reason\r\nContent-Type: $contentType\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray(),
            )
            output.flush()
            stream?.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                    output.flush()
                }
            }
            output.flush()
            return true
        } finally {
            upstreamConnections.remove(connection)
            connection.disconnect()
        }
    }

    /** Open upstream calls; close() disconnects them so a stopped session stops spending the user's quota. */
    private val upstreamConnections: MutableSet<HttpURLConnection> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun isAuthorized(headers: Map<String, String>): Boolean {
        val presented = headers["x-api-key"]
            ?: headers["authorization"]?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
            ?: return false
        val presentedBytes = presented.toByteArray()
        return MessageDigest.isEqual(presentedBytes, sessionToken.toByteArray()) ||
            MessageDigest.isEqual(presentedBytes, activeApiKey.toByteArray()) ||
            MessageDigest.isEqual(presentedBytes, apiKey.toByteArray())
    }

    /**
     * Reports a failure on a response that may already be under way: once the status line is out, a second HTTP
     * response would land inside the event stream, so a stream gets an Anthropic `error` event and anything else
     * is just closed.
     */
    private fun writeFailure(output: GatewayOutput, message: String) {
        runCatching {
            when {
                !output.committed -> writeJson(output, 502, errorJson(message))
                output.eventStream -> {
                    val error = JSONObject().put("type", "error")
                        .put("error", JSONObject().put("type", "api_error").put("message", message))
                    output.write("event: error\ndata: $error\n\n".toByteArray())
                    output.flush()
                }
                else -> Unit
            }
        }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("OpenRouterRoutingGateway", it) }
    }

    /** Reads an HTTP/1.1 chunked body, or returns null when it is malformed or larger than [MAX_REQUEST_BYTES]. */
    private fun readChunkedBody(input: BufferedInputStream): ByteArray? {
        val body = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: return null
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: return null
            if (size < 0 || body.size() + size > MAX_REQUEST_BYTES) return null
            if (size == 0) {
                while (true) {
                    val trailer = readLine(input) ?: return null
                    if (trailer.isEmpty()) break
                }
                return body.toByteArray()
            }
            val chunk = ByteArray(size)
            var read = 0
            while (read < size) {
                val count = input.read(chunk, read, size - read)
                if (count < 0) return null
                read += count
            }
            body.write(chunk)
            if (readLine(input) != "") return null
        }
    }

    private fun writeJson(output: BufferedOutputStream, code: Int, body: String) {
        val bytes = body.toByteArray()
        val reason = if (code in 200..299) "OK" else "Error"
        output.write(
            "HTTP/1.1 $code $reason\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(),
        )
        output.write(bytes)
        output.flush()
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

    private fun errorJson(message: String): String = JSONObject()
        .put("type", "error")
        .put("error", JSONObject().put("type", "api_error").put("message", message))
        .toString()

    override fun close() {
        running.set(false)
        runCatching { server.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("OpenRouterRoutingGateway", it) }
        workers.shutdown()
        upstreamConnections.toList().forEach { runCatching { it.disconnect() } }
    }

    private companion object {
        const val MAX_CONNECTIONS = 32
        const val MAX_ACCEPT_FAILURES = 50
        const val ACCEPT_BACKOFF_MS = 100L
        const val MAX_REQUEST_BYTES = 16 * 1024 * 1024
        const val MAX_HEADER_LINE_BYTES = 16 * 1024
        const val SOCKET_TIMEOUT_MS = 30_000
    }
}
