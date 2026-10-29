package com.nextcraft.forge.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nextcraft.forge.R
import com.nextcraft.forge.network.LocalNetwork
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Loads the image behind a chat `src` (workspace path, data URI or an https URL); null when it cannot be shown. */
internal val LocalChatImageLoader = staticCompositionLocalOf<suspend (String) -> ImageBitmap?> { { null } }

/** An image the chat should draw: markdown `![alt](src)` or a line that is only the path of an image file. */
internal data class MarkdownImageRef(val alt: String, val src: String)

private val IMAGE_LINE = Regex("""^!\[([^\]]*)]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)$""")
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "heic", "heif", "avif")

/** True when [path] has a supported raster image file extension. */
internal fun isImageFilePath(path: String): Boolean {
    val ext = path.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
    return ext in IMAGE_EXTENSIONS
}

/** The image a single markdown line stands for, or null when the line is ordinary text. */
internal fun imageRefOfLine(line: String): MarkdownImageRef? {
    val trimmed = line.trim()
    IMAGE_LINE.matchEntire(trimmed)?.let { return MarkdownImageRef(it.groupValues[1], it.groupValues[2]) }
    val bare = trimmed.removeSurrounding("`")
    if (bare.isEmpty() || bare.any { it.isWhitespace() || it == '(' || it == ')' || it == '[' || it == ']' }) return null
    return if (isImageFilePath(bare)) MarkdownImageRef("", bare) else null
}

private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "mov")

/** True when [path] has a supported video file extension. */
internal fun isVideoFilePath(path: String): Boolean {
    val ext = path.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
    return ext in VIDEO_EXTENSIONS
}

/** The workspace path of a video when the line is only that path (optionally in backticks), else null. */
internal fun videoPathOfLine(line: String): String? {
    val bare = line.trim().removeSurrounding("`")
    if (bare.isEmpty() || bare.any { it.isWhitespace() || it == '(' || it == ')' || it == '[' || it == ']' }) return null
    if (bare.startsWith("http", ignoreCase = true)) return null
    return bare.takeIf { isVideoFilePath(it) }
}

/** Shows a still frame of a chat video and opens it in the system player. */
internal interface ChatVideoHandler {
    suspend fun thumbnail(src: String): ImageBitmap?
    fun open(src: String)
}

internal val LocalChatVideoHandler = staticCompositionLocalOf<ChatVideoHandler?> { null }

/** A video file in a message: its first frame with a play badge; a tap plays it. */
@Composable
internal fun ChatVideo(src: String, modifier: Modifier = Modifier) {
    val handler = LocalChatVideoHandler.current
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val thumb by produceState<ImageBitmap?>(null, src) { value = handler?.thumbnail(src) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 360.dp)
            .clip(shape)
            .background(scheme.onSurface.copy(alpha = 0.08f))
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.chat_video_play)) { handler?.open(src) },
        contentAlignment = Alignment.Center,
    ) {
        thumb?.let {
            Image(bitmap = it, contentDescription = stringResource(R.string.chat_video_play), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
        }
        Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.55f)) {
            Text(
                text = "▶  " + stringResource(R.string.chat_video_play),
                color = Color.White,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** Where an image `src` lives. */
internal sealed interface ChatImageSource {
    data class Remote(val url: String) : ChatImageSource
    data class Data(val base64: String) : ChatImageSource
    data class Workspace(val relativePath: String) : ChatImageSource
}

internal fun classifyImageSource(src: String, projectSlug: String): ChatImageSource? {
    val value = src.trim()
    return when {
        value.startsWith("data:image/", ignoreCase = true) -> {
            val comma = value.indexOf(',')
            // Same 10 MB ceiling as files and downloads (base64 is 4/3 of the bytes): a data URI loads without a tap.
            if (comma < 0 || !value.substring(0, comma).contains(";base64", ignoreCase = true) ||
                value.length - comma - 1 > ChatImageDecoder.MAX_BYTES / 3 * 4 + 4
            ) null
            else ChatImageSource.Data(value.substring(comma + 1))
        }
        value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true) ->
            ChatImageSource.Remote(value)
        else -> {
            var path = value.removePrefix("file://")
            val workspaceRoot = "/workspace/$projectSlug/"
            if (path.startsWith(workspaceRoot)) path = path.removePrefix(workspaceRoot)
            path = path.removePrefix("./")
            // Any other absolute path is outside the project and is never read.
            if (path.isEmpty() || path.startsWith("/")) null else ChatImageSource.Workspace(path)
        }
    }
}

internal object ChatImageDecoder {
    private const val MAX_EDGE = 1600
    const val MAX_BYTES = 10 * 1024 * 1024

    /** Decodes [bytes], scaled down so that neither side is much larger than [MAX_EDGE]; null for non-images. */
    fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_EDGE || bounds.outHeight / (sample * 2) >= MAX_EDGE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

    /** Downloads an image over https (plain http only for localhost); null on any failure or when it is too large. */
    fun fetch(url: String): ByteArray? {
        if (!LocalNetwork.isPlainHttpAllowed(url)) return null
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            connection.inputStream.use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                    if (out.size() > MAX_BYTES) return null
                }
            }
            out.toByteArray()
        } finally {
            connection.disconnect()
        }
    }
}

private sealed interface ImageState {
    data object Loading : ImageState
    data object Failed : ImageState
    data class Loaded(val bitmap: ImageBitmap) : ImageState
}

/**
 * An image inside a chat message. Workspace files and data URIs load by themselves; a remote image waits for a tap,
 * because an address written by the model could otherwise be used to leak data or track the reader.
 */
@Composable
internal fun ChatImage(alt: String, src: String, modifier: Modifier = Modifier, maxHeight: Dp = 360.dp) {
    val scheme = MaterialTheme.colorScheme
    val loader = LocalChatImageLoader.current
    val isRemote = src.trim().startsWith("http", ignoreCase = true)
    var allowRemote by remember(src) { mutableStateOf(false) }
    var zoomed by remember(src) { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    if (isRemote && !allowRemote) {
        val host = runCatching { URI(src.trim()).host }.getOrNull().orEmpty()
        Surface(
            shape = shape,
            color = scheme.onSurface.copy(alpha = 0.06f),
            modifier = modifier.clip(shape).clickable(role = Role.Button) { allowRemote = true },
        ) {
            Text(
                text = stringResource(R.string.chat_image_tap_remote, host),
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        }
        return
    }

    val state by produceState<ImageState>(ImageState.Loading, src, allowRemote) {
        value = loader(src)?.let { ImageState.Loaded(it) } ?: ImageState.Failed
    }
    val description = alt.ifBlank { stringResource(R.string.chat_image_default_alt) }
    when (val current = state) {
        ImageState.Loading -> Box(modifier = modifier.fillMaxWidth().heightIn(min = 72.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        ImageState.Failed -> Text(
            text = stringResource(R.string.chat_image_failed, alt.ifBlank { src }),
            color = scheme.onSurfaceVariant,
            modifier = modifier,
        )
        is ImageState.Loaded -> {
            Image(
                bitmap = current.bitmap,
                contentDescription = description,
                contentScale = ContentScale.Fit,
                modifier = modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .clip(shape)
                    .clickable(role = Role.Button) { zoomed = true },
            )
            if (zoomed) ZoomableImageDialog(current.bitmap, description) { zoomed = false }
        }
    }
}

/** Full-screen view: pinch to zoom, drag to move, tap anywhere outside the gesture to close. */
@Composable
internal fun ZoomableImageDialog(bitmap: ImageBitmap, description: String, onClose: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(onClickLabel = stringResource(R.string.chat_image_close), onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = description,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            if (scale == 1f) {
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                offsetX += pan.x
                                offsetY += pan.y
                            }
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    },
            )
        }
    }
}
