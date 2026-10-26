package com.nextcraft.forge.network

import com.nextcraft.forge.model.stringOrEmpty
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

sealed interface GeminiImageResult {
    /** [note] is whatever text the model sent next to the picture (often empty). */
    class Success(val bytes: ByteArray, val mimeType: String, val note: String) : GeminiImageResult
    data class Failure(val message: String) : GeminiImageResult
}

/** Model ids of Google's image generation ("Nano Banana") in the Gemini API. */
object GeminiImageModels {
    const val NANO_BANANA = "gemini-2.5-flash-image"
    const val NANO_BANANA_PRO = "gemini-3-pro-image-preview"
    val all = listOf(NANO_BANANA, NANO_BANANA_PRO)
    /** Vault id under which the Google AI Studio key for image generation is stored. */
    const val KEY_ID = "GEMINI_IMAGE"
}

/** File extension for an image mime type returned by the API. */
internal fun imageExtension(mimeType: String): String = when (mimeType.lowercase().substringBefore(';').trim()) {
    "image/jpeg", "image/jpg" -> "jpg"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    else -> "png"
}

/** Text-to-image and image editing through `generateContent` of the Gemini API (the user's own API key). */
class GeminiImageClient {
    suspend fun generate(
        apiKey: String,
        model: String,
        prompt: String,
        inputImage: ByteArray? = null,
        inputMimeType: String = "image/png",
    ): GeminiImageResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext GeminiImageResult.Failure("No Google API key is saved.")
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/${model.trim()}:generateContent"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = 15_000
            // Image models can take a minute; the pro model with reasoning even longer.
            readTimeout = 180_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey.trim())
        }
        try {
            val body = requestBody(prompt, inputImage, inputMimeType)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            parseResponse(code, text)
        } catch (e: Exception) {
            GeminiImageResult.Failure(e.message ?: e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    internal fun requestBody(prompt: String, inputImage: ByteArray?, inputMimeType: String): String {
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (inputImage != null) {
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", inputMimeType)
                        .put("data", Base64.getEncoder().encodeToString(inputImage)),
                ),
            )
        }
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))
            .toString()
    }

    internal fun parseResponse(code: Int, body: String): GeminiImageResult {
        val root = runCatching { JSONObject(body) }.getOrNull()
        if (code !in 200..299) {
            val message = root?.optJSONObject("error")?.stringOrEmpty("message").orEmpty().ifBlank { "HTTP $code" }
            return GeminiImageResult.Failure(redact(message))
        }
        if (root == null) return GeminiImageResult.Failure("The reply was not valid JSON.")
        root.optJSONObject("promptFeedback")?.stringOrEmpty("blockReason")?.takeIf { it.isNotBlank() }?.let {
            return GeminiImageResult.Failure("The prompt was blocked ($it).")
        }
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
            ?: return GeminiImageResult.Failure("The model returned no result.")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val notes = StringBuilder()
        var image: ByteArray? = null
        var mime = "image/png"
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            // The REST API answers in camelCase; older replies used snake_case.
            val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
            if (inline != null && image == null) {
                val data = inline.stringOrEmpty("data")
                if (data.isNotBlank()) {
                    image = runCatching { Base64.getMimeDecoder().decode(data) }.getOrNull()
                    mime = inline.stringOrEmpty("mimeType").ifBlank { inline.stringOrEmpty("mime_type") }.ifBlank { "image/png" }
                }
            } else {
                part.stringOrEmpty("text").takeIf { it.isNotBlank() }?.let { notes.append(it).append('\n') }
            }
        }
        val bytes = image
        if (bytes == null || bytes.isEmpty()) {
            val reason = candidate.stringOrEmpty("finishReason")
            val detail = notes.toString().trim().take(300)
            return GeminiImageResult.Failure(
                when {
                    detail.isNotEmpty() -> detail
                    reason.isNotBlank() && reason != "STOP" -> "No image was produced ($reason)."
                    else -> "No image was produced."
                },
            )
        }
        return GeminiImageResult.Success(bytes, mime, notes.toString().trim())
    }

    private fun redact(text: String): String = text
        .replace(Regex("AIza[0-9A-Za-z_-]{20,}"), "AIza••••")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(300)
}
