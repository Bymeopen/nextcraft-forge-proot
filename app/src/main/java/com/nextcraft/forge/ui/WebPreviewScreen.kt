@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.core.net.toUri
import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nextcraft.forge.runtime.AndroidBuildIssue
import com.nextcraft.forge.runtime.AndroidBuildPhase
import com.nextcraft.forge.runtime.AndroidLogLevel
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.LaunchedEffect
import com.nextcraft.forge.runtime.AndroidBuildStage
import com.nextcraft.forge.runtime.AndroidIssueSeverity
import androidx.compose.foundation.clickable
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import com.nextcraft.forge.R
import androidx.annotation.StringRes

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPreviewScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    var urlInput by rememberSaveable(state.previewUrl) { mutableStateOf(state.previewUrl) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val tokens = LocalForgeThemeTokens.current
    val colors = MaterialTheme.colorScheme
    val webViewBgArgb = tokens.terminalBackground.toArgb()

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            // Detach before destroy(): destroying a WebView that is still attached throws on some WebView builds.
            webViewRef?.let { destroyWebViewOnce(it) }
            webViewRef = null
        }
    }

    // Keyed on the view too: the first load happens here once the factory has created it, not in the factory itself.
    LaunchedEffect(state.previewUrl, webViewRef) {
        val target = state.previewUrl.trim()
        if (target.isNotEmpty() && webViewRef?.url != target) {
            webViewRef?.loadUrl(target)
        }
    }

    fun openUrl(raw: String) {
        val trimmed = raw.trim()
        val normalized = when {
            trimmed.startsWith(":") && trimmed.drop(1).all { it.isDigit() } ->
                "http://127.0.0.1$trimmed"
            trimmed.isNotEmpty() && trimmed.all { it.isDigit() } ->
                "http://127.0.0.1:$trimmed"
            trimmed.startsWith("localhost", ignoreCase = true) || trimmed.startsWith("127.0.0.1") ->
                "http://$trimmed"
            else -> trimmed
        }
        val uriScheme = normalized.toUri().scheme?.lowercase()
        if (normalized.isNotEmpty() && (uriScheme == "http" || uriScheme == "https")) {
            urlInput = normalized
            viewModel.updatePreviewUrl(normalized)
            webViewRef?.loadUrl(normalized)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (state.activeTab == ForgeTab.PROJECTS) {
            ForgeProjectHeader(
                title = state.activeProject?.name ?: stringResource(R.string.launch_preview),
                accentSeed = state.activeProject?.name,
                subtitle = stringResource(R.string.launch_preview) + " · " + stringResource(R.string.launch_local_dev_server),
                onBack = { viewModel.projectBack() },
            )
        } else {
            // Opened from Tools: same header and Back as every other tool screen.
            ForgeProjectHeader(
                title = stringResource(R.string.launch_preview),
                subtitle = stringResource(R.string.launch_local_dev_server),
                onBack = { viewModel.selectTab(ForgeTab.TOOLS) },
            )
        }

        var previewMode by rememberSaveable { mutableStateOf(0) } // 0 = Web Preview, 1 = UI Inspector
        val reduceMotion = rememberReduceMotion()
        ForgePillTabs(
            items = listOf(
                stringResource(R.string.inspector_tab_web),
                stringResource(R.string.inspector_tab_ui),
            ),
            selectedIndex = previewMode,
            onSelect = { previewMode = it },
        )

        androidx.compose.animation.AnimatedContent(
            targetState = previewMode,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = { ForgeMotion.tabTransition(forward = targetState >= initialState, reduceMotion = reduceMotion) },
            label = "previewModeSwitch",
        ) { mode ->
        if (mode == 1) {
            UiInspectorPanel(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
        Column(modifier = Modifier.fillMaxSize()) {
        // Address bar: status dot, URL, go arrow; refresh sits outside the bar.
        var opened by rememberSaveable { mutableStateOf(false) }
        val serverUp = state.activeLocalPorts.isNotEmpty() || opened
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = tokens.cardContainerColor,
                contentColor = colors.onSurface,
                border = tokens.cardBorderStroke,
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (serverUp) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.5f)),
                    )
                    TextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        singleLine = true,
                        keyboardOptions = ForgeFieldKind.URL.keyboardOptions(ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { opened = true; openUrl(urlInput) }),
                        placeholder = { Text(stringResource(R.string.launch_preview_url), color = colors.onSurfaceVariant.copy(alpha = 0.6f)) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            errorContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    ForgePillIcon(
                        icon = Icons.Default.ArrowForward,
                        description = stringResource(R.string.launch_open),
                        onClick = {
                            opened = true
                            openUrl(urlInput)
                        },
                    )
                }
            }
            ForgePillIcon(
                icon = Icons.Default.Refresh,
                description = stringResource(R.string.launch_scan_ports_and_reload),
                onClick = {
                    viewModel.scanLocalPreviewPorts()
                    webViewRef?.reload()
                },
            )
        }

        if (state.activeLocalPorts.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.activeLocalPorts.forEach { port ->
                    val portUrl = "http://127.0.0.1:$port"
                    AssistChip(
                        onClick = {
                            urlInput = portUrl
                            openUrl(portUrl)
                        },
                        shape = RoundedCornerShape(100),
                        label = { Text(":$port", fontFamily = FontFamily.Monospace) },
                    )
                }
            }
        }

        if (!serverUp) {
            // Nothing is listening yet: say so instead of showing a blank WebView. (if/else, not an early return: a
            // `return@Column` inside this composable lambda is a suspected cause of the Compose Stack.pop crash.)
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                ForgeEmptyState(
                    glyph = ForgeGlyph.GLOBE,
                    title = stringResource(R.string.launch_preview_not_running),
                    message = stringResource(R.string.launch_preview_not_running_hint),
                    actionLabel = stringResource(R.string.preview_open_terminal),
                    onAction = {
                        // Inside a project the terminal is a pane of that project; elsewhere it is the Terminal tab.
                        if (state.activeTab == ForgeTab.PROJECTS) viewModel.selectProjectPane(ProjectPane.TERMINAL) else viewModel.selectTab(ForgeTab.TERMINAL)
                    },
                )
            }
        } else {
        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(16.dp)),
            factory = { context ->
                // Devices without a usable WebView provider throw here; show an empty view instead of force-closing.
                val created = runCatching { WebView(context) }.getOrNull()
                created?.apply {
                    setBackgroundColor(webViewBgArgb)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                        ): Boolean = request.url.scheme?.lowercase() !in setOf("http", "https")

                        // A page that sets no background of its own must look like it does in a browser (white). With the app's dark
                        // surface behind it, plain HTML (directory listings, quick tests) rendered black text on dark and was unreadable.
                        // The dark colour stays only until the first paint, so there is no white flash on open.
                        override fun onPageCommitVisible(view: WebView, url: String) {
                            view.setBackgroundColor(android.graphics.Color.WHITE)
                        }

                        // Without this the app process is killed when the renderer dies (e.g. low memory).
                        override fun onRenderProcessGone(
                            view: WebView,
                            detail: android.webkit.RenderProcessGoneDetail,
                        ): Boolean {
                            if (webViewRef === view) webViewRef = null
                            destroyWebViewOnce(view)
                            return true
                        }
                    }
                    webViewRef = this
                } ?: android.view.View(context)
            },
            update = { view ->
                (view as? WebView)?.let { webView ->
                    webViewRef = webView
                }
            },
            // The view leaves composition when the server stops; destroy it there, not only when the screen closes.
            onRelease = { view ->
                if (webViewRef === view) webViewRef = null
                (view as? WebView)?.let { destroyWebViewOnce(it) }
            },
        )
        }
        }
        }
        }
    }
}

private const val WEBVIEW_DESTROYED_TAG = "forge-webview-destroyed"

/** Detaches and destroys once; onRenderProcessGone, onRelease and onDispose may all reach the same view. */
private fun destroyWebViewOnce(view: WebView) {
    if (view.tag == WEBVIEW_DESTROYED_TAG) return
    view.tag = WEBVIEW_DESTROYED_TAG
    (view.parent as? android.view.ViewGroup)?.removeView(view)
    runCatching { view.stopLoading(); view.destroy() }
        .onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("AndroidAndPreviewScreen", it) }
}

@Composable
private fun UiInspectorPanel(
    state: ForgeUiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalForgeThemeTokens.current
    var selectedNode by remember(state.uiInspectorNodes) { mutableStateOf<UiInspectorNode?>(null) }
    var instruction by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.inspector_tab_ui),
                fontSize = ForgeType.cardTitle,
                fontWeight = FontWeight.SemiBold,
            )
            ForgePrimaryButton(
                text = stringResource(R.string.inspector_scan_ui),
                enabled = !state.uiInspectorBusy,
                onClick = viewModel::captureUiInspectorHierarchy,
            )
        }

        selectedNode?.let { node ->
            ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = buildString {
                            append(node.className)
                            if (node.id.isNotBlank()) append(" #").append(node.id)
                            append(" ").append(node.bounds)
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = ForgeType.caption,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.accentText,
                    )
                    if (node.text.isNotBlank()) {
                        Text(
                            text = node.text,
                            fontSize = ForgeType.secondary,
                            maxLines = 2,
                        )
                    }
                    ForgeTextField(
                        kind = ForgeFieldKind.TEXT,
                        imeAction = ImeAction.Done,
                        value = instruction,
                        onValueChange = { instruction = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.inspector_instruction_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ForgePrimaryButton(
                        text = stringResource(R.string.inspector_edit_with_agent),
                        enabled = instruction.isNotBlank(),
                        onClick = {
                            viewModel.editUiElementWithAgent(node, instruction)
                            instruction = ""
                            selectedNode = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (state.uiInspectorNodes.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.inspector_empty_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = ForgeType.secondary,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(state.uiInspectorNodes, key = { index, node -> "$index:${node.id}" }) { _, node ->
                    val isSelected = selectedNode == node
                    ForgeThemedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { selectedNode = node },
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = buildString {
                                        append(node.className)
                                        if (node.id.isNotBlank()) append(" · #").append(node.id)
                                    },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = ForgeType.caption,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                    color = if (isSelected) tokens.accentText else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = node.bounds,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = ForgeType.caption,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (node.text.isNotBlank()) {
                                Text(
                                    text = node.text,
                                    fontSize = ForgeType.secondary,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
