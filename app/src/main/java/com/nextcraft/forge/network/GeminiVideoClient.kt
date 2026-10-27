package com.nextcraft.forge.network

import com.nextcraft.forge.model.stringOrEmpty
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

sealed interface GeminiVideoResult {
    data object Success : GeminiVideoResult
    data class Failure(val message: String) : GeminiVideoResult
}

/** Gemini Omni video generation (it replaces the Veo 3.1 previews, which Google shuts down on 22 October 2026). */
object GeminiVideoModels {
    const val OMNI_FLASH = "gemini-omni-1.1-flash"
}

/** The file id inside a Gemini Files API address (`.../v1beta/files/<id>:download?alt=media`), or null. */
internal fun geminiFileId(uri: String): String? = Regex("/files/([A-Za-z0-9_-]+)").find(uri)?.groupValues?.get(1)

/** Only Google's own hosts may receive the API key while following a redirect. */
internal fun isGoogleHost(host: String?): Boolean {
    val h = host?.lowercase() ?: return false
    return h == "googleapis.com" || h.endsWith(".googleapis.com") || h.endsWith(".googleusercontent.com")
}

/** Text-to-video and image-to-video through the Interactions API (the user's own Google AI Studio key). */
class GeminiVideoClient {
    /** Generates a clip and writes the MP4 to [output]. [aspectRatio] is "16:9" or "9:16"; [resolution] "720p" or "1080p". */
    suspend fun generate(
        apiKey: String,
        prompt: String,
        output: File,
        aspectRatio: String = "16:9",
        resolution: String = "720p",
        inputImage: ByteArray? = null,
        inputMimeType: String = "image/png",
    ): GeminiVideoResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext GeminiVideoResult.Failure("No Google API key is saved.")
        try {
            val body = requestBody(prompt, aspectRatio, resolution, inputImage, inputMimeType)
            val (code, text) = postJson("https://generativelanguage.googleapis.com/v1beta/interactions", apiKey, body)
            val root = runCatching { JSONObject(text) }.getOrNull()
            if (code !in 200..299 || root == null) {
                val message = root?.optJSONObject("error")?.stringOrEmpty("message").orEmpty().ifBlank { "HTTP $code" }
                return@withContext GeminiVideoResult.Failure(redact(message))
            }
            val video = findVideo(root)
                ?: return@withContext GeminiVideoResult.Failure(
                    root.optJSONObject("error")?.stringOrEmpty("message")?.let(::redact)
                        ?: "The model returned no video (status ${root.stringOrEmpty("status").ifBlank { "unknown" }}).",
                )
            output.parentFile?.mkdirs()
            val data = video.stringOrEmpty("data")
            if (data.isNotBlank()) {
                output.writeBytes(Base64.getMimeDecoder().decode(data))
                return@withContext GeminiVideoResult.Success
            }
            val uri = video.stringOrEmpty("uri")
            if (uri.isBlank()) return@withContext GeminiVideoResult.Failure("The video had neither data nor an address.")
            waitUntilActive(apiKey, uri)?.let { return@withContext GeminiVideoResult.Failure(it) }
            download(apiKey, uri, output)?.let { return@withContext GeminiVideoResult.Failure(it) }
            GeminiVideoResult.Success
        } catch (e: Exception) {
            runCatching { output.delete() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("GeminiVideoClient", it) }
            GeminiVideoResult.Failure(redact(e.message ?: e.javaClass.simpleName))
        }
    }

    internal fun requestBody(prompt: String, aspectRatio: String, resolution: String, image: ByteArray?, mime: String): String {
        val root = JSONObject().put("model", GeminiVideoModels.OMNI_FLASH)
        if (image == null) {
            root.put("input", prompt)
        } else {
            root.put(
                "input",
                JSONArray()
                    .put(JSONObject().put("type", "image").put("data", Base64.getEncoder().encodeToString(image)).put("mime_type", mime))
                    .put(JSONObject().put("type", "text").put("text", prompt)),
            )
            root.put("generation_config", JSONObject().put("video_config", JSONObject().put("task", "image_to_video")))
        }
        // "uri" delivery keeps large clips out of the JSON reply.
        root.put(
            "response_format",
            JSONObject().put("type", "video").put("aspect_ratio", aspectRatio).put("resolution", resolution).put("delivery", "uri"),
        )
        return root.toString()
    }

    /** The `video` item of the first `model_output` step, or null. */
    internal fun findVideo(root: JSONObject): JSONObject? {
        val steps = root.optJSONArray("steps") ?: return null
        for (s in 0 until steps.length()) {
            val step = steps.optJSONObject(s) ?: continue
            if (step.stringOrEmpty("type") != "model_output") continue
            val content = step.optJSONArray("content") ?: continue
            for (c in 0 until content.length()) {
                val item = content.optJSONObject(c) ?: continue
                if (item.stringOrEmpty("type") == "video") return item
            }
        }
        return null
    }

    private fun postJson(url: String, apiKey: String, body: String): Pair<Int, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = 15_000
            // Generating a clip takes minutes and the call is synchronous.
            readTimeout = 600_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey.trim())
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            code to stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(4 * 1024)
                val maxChars = 4 * 1024 * 1024
                while (sb.length < maxChars) {
                    val n = reader.read(buf, 0, minOf(buf.size, maxChars - sb.length))
                    if (n <= 0) break
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }.orEmpty()
        } finally {
            connection.disconnect()
        }
    }

    /** Polls the file until it is ACTIVE; returns an error message, or null when it is ready. */
    private suspend fun waitUntilActive(apiKey: String, uri: String): String? {
        val id = geminiFileId(uri) ?: return null
        repeat(100) {
            val connection = (URL("https://generativelanguage.googleapis.com/v1beta/files/$id").openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("x-goog-api-key", apiKey.trim())
            }
            val state = try {
                if (connection.responseCode in 200..299) {
                    runCatching { JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).stringOrEmpty("state") }.getOrDefault("")
                } else {
                    ""
                }
            } finally {
                connection.disconnect()
            }
            when (state) {
                "ACTIVE" -> return null
                "FAILED" -> return "The video could not be generated."
            }
            delay(3_000)
        }
        return "The video was not ready in time."
    }

    /** Streams the clip to [output], following at most three redirects and only to Google hosts. */
    private fun download(apiKey: String, uri: String, output: File): String? {
        var url = uri
        repeat(4) {
            val parsed = runCatching { URI(url) }.getOrNull() ?: return "Invalid video address."
            if (parsed.scheme != "https" || !isGoogleHost(parsed.host)) return "The video address is not a Google address."
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 120_000
                setRequestProperty("x-goog-api-key", apiKey.trim())
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    url = connection.getHeaderField("Location") ?: return "The video download was redirected without an address."
                    return@repeat
                }
                if (code !in 200..299) return "The video download failed (HTTP $code)."
                var total = 0L
                connection.inputStream.use { input ->
                    output.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_VIDEO_BYTES) return "The video is larger than ${MAX_VIDEO_BYTES / (1024 * 1024)} MB."
                            out.write(buffer, 0, count)
                        }
                    }
                }
                return if (total == 0L) "The downloaded video was empty." else null
            } finally {
                connection.disconnect()
            }
        }
        return "Too many redirects while downloading the video."
    }

    private fun redact(text: String): String = text
        .replace(Regex("AIza[0-9A-Za-z_-]{20,}"), "AIza••••")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(300)

    private companion object {
        const val MAX_VIDEO_BYTES = 200L * 1024 * 1024
    }
}
