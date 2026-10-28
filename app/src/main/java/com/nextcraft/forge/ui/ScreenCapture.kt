package com.nextcraft.forge.ui

import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** One screen of the app to photograph: where to navigate, and the file name it is saved under. */
/** [extraSettleMs] is added to the usual wait for screens that take longer to draw (the terminal starts a shell first). */
internal data class CaptureStep(val name: String, val tab: ForgeTab, val pane: ProjectPane? = null, val extraSettleMs: Long = 0L)

/**
 * The main screens, in the order they are captured: the four panes of the open project, then the global screens.
 * File names are plain English so an agent reading the folder knows what each picture shows.
 */
internal fun appCaptureSteps(): List<CaptureStep> = listOf(
    CaptureStep("chat", ForgeTab.PROJECTS, ProjectPane.CHAT),
    CaptureStep("files", ForgeTab.PROJECTS, ProjectPane.FILES),
    CaptureStep("terminal", ForgeTab.PROJECTS, ProjectPane.TERMINAL, extraSettleMs = 2500L),
    CaptureStep("preview", ForgeTab.PROJECTS, ProjectPane.PREVIEW),
    CaptureStep("projects", ForgeTab.PROJECTS),
    CaptureStep("ai-settings", ForgeTab.AGENT),
    CaptureStep("tools", ForgeTab.TOOLS),
    CaptureStep("diagnostics", ForgeTab.DIAGNOSTICS),
    CaptureStep("code-viewer", ForgeTab.CODE_VIEWER),
    CaptureStep("file-browser", ForgeTab.FILE_BROWSER),
    CaptureStep("settings", ForgeTab.SETTINGS),
)

/** The activity window behind a Compose [Context], or null when there is none (previews, tests). */
internal fun Context.findWindow(): Window? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current.window
        current = current.baseContext
    }
    return null
}

/**
 * Copies what [window] currently shows (the GPU-composited frame, including the terminal and web views) into a
 * bitmap no wider than [maxWidth] px, or null when the copy fails. Must be called from the main thread.
 */
internal suspend fun captureWindow(window: Window, maxWidth: Int = 720): Bitmap? = suspendCancellableCoroutine { continuation ->
    val view = window.decorView
    if (view.width <= 0 || view.height <= 0) {
        continuation.resume(null)
        return@suspendCancellableCoroutine
    }
    val full = createBitmap(view.width, view.height)
    PixelCopy.request(
        window,
        full,
        { result ->
            if (!continuation.isActive) {
                full.recycle()
                return@request
            }
            if (result != PixelCopy.SUCCESS) {
                full.recycle()
                continuation.resume(null)
            } else if (full.width <= maxWidth) {
                continuation.resume(full)
            } else {
                val height = (full.height.toLong() * maxWidth / full.width).toInt().coerceAtLeast(1)
                val scaled = full.scale(maxWidth, height)
                if (scaled !== full) full.recycle()
                continuation.resume(scaled)
            }
        },
        Handler(Looper.getMainLooper()),
    )
}
