package com.nextcraft.forge.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/** One-shot Ctrl/Alt toggles of the key row: armed by a tap, spent by the next key that is typed. */
class TerminalModifierKeys {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    var shift by mutableStateOf(false)

    fun consumeCtrl(): Boolean = ctrl.also { ctrl = false }
    fun consumeShift(): Boolean = shift.also { shift = false }
    fun consumeAlt(): Boolean = alt.also { alt = false }
}

/**
 * Receives the emulator's callbacks (screen changed, clipboard, session over). Callbacks arrive on the main thread.
 * [view] is whatever TerminalView currently shows a session, set by the Compose layer.
 */
class ForgeTerminalSessionClient(
    private val context: Context,
    private val onSessionEnded: (TerminalSession) -> Unit,
) : TerminalSessionClient {
    @Volatile
    var view: TerminalView? = null

    private val clipboard: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override fun onTextChanged(changedSession: TerminalSession?) {
        view?.onScreenUpdated()
    }

    override fun onTitleChanged(updatedSession: TerminalSession?) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession?) {
        finishedSession?.let(onSessionEnded)
    }

    override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) {
        if (text.isNullOrEmpty()) return
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = clipboard.primaryClip?.takeIf { it.itemCount > 0 } ?: return
        val text = clip.getItemAt(0).coerceToText(context)?.toString()
        if (!text.isNullOrEmpty()) session?.emulator?.paste(text)
    }

    override fun onBell(session: TerminalSession?) = Unit

    override fun onColorsChanged(session: TerminalSession?) {
        view?.onScreenUpdated()
    }

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    /** null = the emulator's default block cursor. */
    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message.orEmpty()) }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message.orEmpty()) }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message.orEmpty()) }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message.orEmpty()) }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message.orEmpty()) }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: TAG, message.orEmpty(), e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "terminal", e) }

    private companion object {
        const val TAG = "ForgeTerminal"
    }
}

/**
 * Input side of the terminal view: soft-keyboard modifiers come from [modifiers] (the key row), pinch zooms the
 * font, a tap opens the keyboard, and [onEmulatorReady] flushes commands queued before the shell existed.
 */
class ForgeTerminalViewClient(
    private val modifiers: TerminalModifierKeys,
    initialTextPx: Int,
    private val minTextPx: Int,
    private val maxTextPx: Int,
    private val stepPx: Int,
    private val onTap: () -> Unit,
    private val onEmulatorReady: () -> Unit,
) : TerminalViewClient {
    @Volatile
    var view: TerminalView? = null

    var textSizePx: Int = initialTextPx
        private set

    /** Called whenever the size changes (pinch or buttons) so it can be remembered. */
    var onTextSizeChanged: (Int) -> Unit = {}

    /** Applies a stored size (loaded after construction) to the field and, if already created, the view. */
    fun applyTextSize(px: Int) {
        val next = px.coerceIn(minTextPx, maxTextPx)
        if (next == textSizePx) return
        textSizePx = next
        view?.setTextSize(next)
    }

    /** Grows (+1) or shrinks (-1) the text by one step, within the allowed range. */
    fun changeTextSize(direction: Int) {
        val next = (textSizePx + direction * stepPx).coerceIn(minTextPx, maxTextPx)
        if (next == textSizePx) return
        textSizePx = next
        view?.setTextSize(next)
        onTextSizeChanged(next)
    }

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            changeTextSize(if (scale > 1f) 1 else -1)
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent?) = onTap()
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false
    override fun readControlKey(): Boolean = modifiers.consumeCtrl()
    override fun readAltKey(): Boolean = modifiers.consumeAlt()
    override fun readShiftKey(): Boolean = modifiers.consumeShift()
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
    override fun onEmulatorSet() = onEmulatorReady()

    override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message.orEmpty()) }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message.orEmpty()) }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message.orEmpty()) }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message.orEmpty()) }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message.orEmpty()) }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: TAG, message.orEmpty(), e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "terminal", e) }

    private companion object {
        const val TAG = "ForgeTerminal"
    }
}

/**
 * Paints the emulator in the app theme: default foreground, background and cursor only (the 16 ANSI colours stay
 * the xterm palette, which programs expect). Colours are opaque: the glass theme's translucent terminal surface
 * would otherwise be drawn over black.
 */
internal fun applyTerminalColors(session: TerminalSession?, view: TerminalView?, background: Color, foreground: Color, cursor: Color) {
    val defaults = TerminalColors.COLOR_SCHEME.mDefaultColors
    val wanted = mapOf(
        TextStyle.COLOR_INDEX_BACKGROUND to background.toArgb(),
        TextStyle.COLOR_INDEX_FOREGROUND to foreground.toArgb(),
        TextStyle.COLOR_INDEX_CURSOR to cursor.toArgb(),
    )
    if (wanted.all { (index, argb) -> defaults[index] == argb }) return
    wanted.forEach { (index, argb) ->
        defaults[index] = argb
        session?.emulator?.mColors?.reset(index)
    }
    view?.onScreenUpdated()
}
