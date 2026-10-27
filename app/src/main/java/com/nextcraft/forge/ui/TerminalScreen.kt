@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.MoreVert
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nextcraft.forge.R
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView

/** General-purpose shortcuts: the terminal is not tied to a project. "claude" opens the Claude Code interface. */
private val quickCommands = listOf(
    "claude",
    "ls -la",
    "uname -a",
    "node -v && npm -v",
    "python3 --version",
    "gradle -v",
    "df -h",
)

/** The TerminalView currently on screen, shared with the key row (plain holder: it is never read during composition). */
internal class TerminalViewHolder {
    var view: TerminalView? = null
}

/**
 * Interactive terminal: a persistent shell per project (root chroot when available) drawn by a full VT100/xterm
 * emulator, so editors, pagers and TUIs (vim, htop, tmux…) work. Typing goes straight to the shell; the key row
 * adds the keys a phone keyboard lacks.
 */
@Composable
fun TerminalScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
    /** Null = the general terminal of the Terminal tab; otherwise the terminal of that chat, started in the chat's folder. */
    chatId: String? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(chatId, state.activeProjectId) { viewModel.ensureTerminalSession(chatId) }
    val session = remember(state.terminalRevision, chatId, state.activeProjectId) { viewModel.currentTerminalSession(chatId) }
    val finished = remember(state.terminalRevision, chatId, state.activeProjectId) { viewModel.isTerminalFinished(chatId) }
    val modifiers = remember { TerminalModifierKeys() }
    val holder = remember { TerminalViewHolder() }
    val prefs = remember { context.getSharedPreferences("forge_terminal", android.content.Context.MODE_PRIVATE) }
    // Starts hidden; the stored flag is read off the main thread below (SharedPreferences blocks on first access).
    var hintSeen by remember { mutableStateOf(true) }
    val dismissHint = { if (!hintSeen) { hintSeen = true; prefs.edit().putBoolean(HINT_SEEN_KEY, true).apply() } }
    val viewClient = remember {
        ForgeTerminalViewClient(
            modifiers = modifiers,
            initialTextPx = with(density) { 14.sp.roundToPx() },
            minTextPx = with(density) { 8.sp.roundToPx() },
            maxTextPx = with(density) { 28.sp.roundToPx() },
            stepPx = with(density) { 1.sp.roundToPx() }.coerceAtLeast(1),
            onTap = {
                holder.view?.let { terminal ->
                    terminal.requestFocus()
                    context.getSystemService(InputMethodManager::class.java)?.showSoftInput(terminal, 0)
                }
            },
            onEmulatorReady = { viewModel.flushTerminalInput(chatId) },
        ).also { client -> client.onTextSizeChanged = { px -> prefs.edit().putInt(TEXT_SIZE_KEY, px).apply() } }
    }
    LaunchedEffect(prefs) {
        val defaultPx = viewClient.textSizePx
        val (seen, px) = withContext(Dispatchers.IO) { prefs.getBoolean(HINT_SEEN_KEY, false) to prefs.getInt(TEXT_SIZE_KEY, defaultPx) }
        hintSeen = seen
        viewClient.applyTextSize(px)
    }
    val terminalBackground = tokens.terminalBackground.compositeOver(scheme.background)
    val ready = session != null && !finished

    Column(modifier = Modifier.fillMaxSize()) {
        val inProject = state.activeTab == ForgeTab.PROJECTS
        val activeProject = state.activeProject
        val headerSubtitle = if (chatId != null && activeProject != null) {
            viewModel.terminalProjectDir(chatId) ?: "/workspace/${activeProject.slug}"
        } else {
            stringResource(R.string.terminal_general_subtitle)
        }
        ForgeProjectHeader(
            title = stringResource(R.string.terminal_project_terminal),
            subtitle = headerSubtitle,
            onBack = if (inProject) ({ viewModel.projectBack() }) else null,
            actions = {
                ForgePillIcon(
                    icon = Icons.Default.Share,
                    description = stringResource(R.string.terminal_copy_terminal_output),
                    onClick = {
                        clipboard.setText(AnnotatedString(session?.emulator?.screen?.transcriptText.orEmpty()))
                    },
                    enabled = session != null,
                )
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    ForgePillIcon(
                        icon = Icons.Default.MoreVert,
                        description = stringResource(R.string.files_more),
                        onClick = { menuOpen = true },
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.terminal_text_smaller)) },
                            onClick = { menuOpen = false; viewClient.changeTextSize(-1) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.terminal_text_larger)) },
                            onClick = { menuOpen = false; viewClient.changeTextSize(1) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.terminal_clear_terminal)) },
                            enabled = session != null,
                            onClick = {
                                menuOpen = false
                                viewModel.clearTerminal(chatId)
                                // reset() only edits the emulator buffer; repaint now instead of waiting for the next key.
                                holder.view?.onScreenUpdated()
                            },
                        )
                    }
                }
            },
        )

        LiveResourceMiniBar(onCleanIdleDaemons = viewModel::cleanIdleDaemons)

        // The emulator fills the space; the shell is resized to it (and to the keyboard) automatically.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(terminalBackground),
        ) {
            if (session == null) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.terminal_preparing),
                        color = tokens.terminalText,
                        fontSize = ForgeType.secondary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            } else {
                key(session) {
                    TerminalEmulatorView(
                        session = session,
                        sessionClient = viewModel.terminalSessionClient,
                        viewClient = viewClient,
                        holder = holder,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (session != null && !finished && !hintSeen) {
                // Shown until the first command is sent; it does not take touches, the terminal below stays usable.
                Text(
                    text = stringResource(R.string.terminal_empty_hint),
                    color = tokens.terminalText.copy(alpha = 0.6f),
                    fontSize = ForgeType.secondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                )
            }
            if (session != null && finished) {
                Surface(
                    color = scheme.surface,
                    contentColor = scheme.onSurface,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                        .forgeLive(),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.terminal_session_ended, session.exitStatus),
                            fontSize = ForgeType.secondary,
                        )
                        ForgeTextButton(onClick = { viewModel.restartTerminalSession(chatId) }) {
                            Text(stringResource(R.string.terminal_restart))
                        }
                    }
                }
            }
        }

        val chipScroll = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .forgeFadingEdges(chipScroll, MaterialTheme.colorScheme.background)
                .horizontalScroll(chipScroll)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // The shell starts in the home folder; this jumps into the open project's folder.
            // A chat's terminal already starts in the chat's folder, so only the general terminal needs the jump.
            state.activeProject?.takeIf { chatId == null }?.let { project ->
                AssistChip(
                    onClick = { dismissHint(); viewModel.terminalProjectDir()?.let { dir -> viewModel.runTerminalCommand("cd " + com.nextcraft.forge.runtime.shellSingleQuote(dir)) } },
                    enabled = ready,
                    shape = RoundedCornerShape(100),
                    label = { Text(stringResource(R.string.terminal_go_to_project, project.name), fontSize = ForgeType.caption, maxLines = 1) },
                )
            }
            quickCommands.forEach { cmd ->
                AssistChip(
                    onClick = { dismissHint(); viewModel.runTerminalCommand(cmd, chatId) },
                    enabled = ready,
                    shape = RoundedCornerShape(100),
                    label = {
                        Text(
                            text = cmd,
                            fontFamily = FontFamily.Monospace,
                            fontSize = ForgeType.caption,
                        )
                    },
                )
            }
        }

        TerminalKeyRow(modifiers = modifiers, holder = holder, enabled = ready)

        // Command line pinned last, so it floats right above the keyboard (the layout is lifted by
        // imePadding). Text goes to the running program plus Enter.
        TerminalCommandBar(enabled = session != null, onSend = { dismissHint(); viewModel.runTerminalCommand(it, chatId) })
    }
}

@Composable
internal fun TerminalCommandBar(enabled: Boolean, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = {
        if (text.isNotBlank()) {
            onSend(text)
            text = ""
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ForgeTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("$ " + stringResource(R.string.terminal_input_hint), fontFamily = FontFamily.Monospace, fontSize = ForgeType.secondary) },
            kind = ForgeFieldKind.IDENTIFIER,
            imeAction = ImeAction.Send,
            onImeAction = { send() },
            enabled = enabled,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = ForgeType.secondary, color = MaterialTheme.colorScheme.onSurface),
        )
        IconButton(onClick = send, enabled = enabled && text.isNotBlank()) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.terminal_send))
        }
    }
}

@Composable
private fun TerminalEmulatorView(
    session: TerminalSession,
    sessionClient: ForgeTerminalSessionClient,
    viewClient: ForgeTerminalViewClient,
    holder: TerminalViewHolder,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    val background = tokens.terminalBackground.compositeOver(scheme.background)
    val label = stringResource(R.string.terminal_project_terminal)

    DisposableEffect(session) {
        onDispose {
            if (holder.view?.currentSession === session) {
                holder.view = null
                viewClient.view = null
                sessionClient.view = null
            }
        }
    }

    AndroidView(
        modifier = modifier.semantics { contentDescription = label },
        factory = { ctx ->
            TerminalView(ctx, null).apply {
                setTerminalViewClient(viewClient)
                setTextSize(viewClient.textSizePx)
                setTypeface(Typeface.MONOSPACE)
                isFocusable = true
                isFocusableInTouchMode = true
                viewClient.view = this
                sessionClient.view = this
                holder.view = this
                // Colours first: the emulator copies the defaults when it is created on attach.
                applyTerminalColors(session, this, background, tokens.terminalText, scheme.primary)
                attachSession(session)
                requestFocus()
            }
        },
        update = { view -> applyTerminalColors(session, view, background, tokens.terminalText, scheme.primary) },
    )
}

/** Esc, Tab, Ctrl/Alt (armed for the next key), arrows, paging and the symbols that are buried on a phone keyboard. */
@Composable
internal fun TerminalKeyRow(
    modifiers: TerminalModifierKeys,
    holder: TerminalViewHolder,
    enabled: Boolean,
) {
    // TerminalView dereferences the emulator without a null check, so keys wait until it exists.
    fun liveView(): TerminalView? = holder.view?.takeIf { it.currentSession?.emulator != null }

    fun press(keyCode: Int) {
        val view = liveView() ?: return
        // An armed Ctrl/Alt belongs to the next key, whether it is typed or tapped here (Ctrl+arrow, Alt+Esc).
        var mod = 0
        if (modifiers.consumeCtrl()) mod = mod or KeyHandler.KEYMOD_CTRL
        if (modifiers.consumeAlt()) mod = mod or KeyHandler.KEYMOD_ALT
        if (modifiers.consumeShift()) mod = mod or KeyHandler.KEYMOD_SHIFT
        view.handleKeyCode(keyCode, mod)
    }

    fun type(char: Char) {
        val view = liveView() ?: return
        // An armed Shift gives the shifted character: a capital letter, or "_" / "?" for the "-" and "/" keys.
        val shifted = if (modifiers.consumeShift()) {
            when (char) { '-' -> '_'; '/' -> '?'; else -> char.uppercaseChar() }
        } else char
        view.inputCodePoint(shifted.code, modifiers.consumeCtrl(), modifiers.consumeAlt())
    }

    val keyScroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .forgeFadingEdges(keyScroll, MaterialTheme.colorScheme.background)
            .horizontalScroll(keyScroll)
            .padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TerminalKey("Esc", stringResource(R.string.terminal_key_esc), enabled) { press(KeyEvent.KEYCODE_ESCAPE) }
        TerminalKey("Tab", stringResource(R.string.terminal_key_tab), enabled) { press(KeyEvent.KEYCODE_TAB) }
        // Enter sits third so it is on screen without scrolling the row, even at 360dp.
        TerminalKey("Enter", stringResource(R.string.terminal_key_enter), enabled) { press(KeyEvent.KEYCODE_ENTER) }
        TerminalKey("Shift", stringResource(R.string.terminal_key_shift), enabled, active = modifiers.shift) { modifiers.shift = !modifiers.shift }
        TerminalKey("Ctrl", stringResource(R.string.terminal_key_ctrl), enabled, active = modifiers.ctrl) { modifiers.ctrl = !modifiers.ctrl }
        TerminalKey("Alt", stringResource(R.string.terminal_key_alt), enabled, active = modifiers.alt) { modifiers.alt = !modifiers.alt }
        TerminalKey("^C", stringResource(R.string.terminal_key_interrupt), enabled) {
            liveView()?.inputCodePoint('c'.code, true, false)
        }
        TerminalKey("↑", stringResource(R.string.terminal_key_up), enabled) { press(KeyEvent.KEYCODE_DPAD_UP) }
        TerminalKey("↓", stringResource(R.string.terminal_key_down), enabled) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        TerminalKey("←", stringResource(R.string.terminal_key_left), enabled) { press(KeyEvent.KEYCODE_DPAD_LEFT) }
        TerminalKey("→", stringResource(R.string.terminal_key_right), enabled) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        TerminalKey("Home", stringResource(R.string.terminal_key_home), enabled) { press(KeyEvent.KEYCODE_MOVE_HOME) }
        TerminalKey("End", stringResource(R.string.terminal_key_end), enabled) { press(KeyEvent.KEYCODE_MOVE_END) }
        TerminalKey("PgUp", stringResource(R.string.terminal_key_pgup), enabled) { press(KeyEvent.KEYCODE_PAGE_UP) }
        TerminalKey("PgDn", stringResource(R.string.terminal_key_pgdn), enabled) { press(KeyEvent.KEYCODE_PAGE_DOWN) }
        listOf('-', '/', '|', '~').forEach { symbol ->
            TerminalKey(symbol.toString(), symbol.toString(), enabled) { type(symbol) }
        }
    }
}

/** Capsule key for the terminal key row; [active] marks an armed modifier (Ctrl/Alt) for the next key. */
@Composable
private fun TerminalKey(
    label: String,
    description: String,
    enabled: Boolean,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(100),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (active) scheme.primary.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent,
        ),
        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp),
        modifier = Modifier
            .heightIn(min = ForgeSpace.minTouch)
            .semantics { contentDescription = description }
            .forgeSelected(active),
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = ForgeType.secondary,
            color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant,
        )
    }
}

private const val TEXT_SIZE_KEY = "text_size_px"
private const val HINT_SEEN_KEY = "hint_seen"
