package com.nextcraft.forge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.runtime.BrowserDenial
import com.nextcraft.forge.runtime.BrowserEntry
import com.nextcraft.forge.runtime.BrowserListing
import com.nextcraft.forge.runtime.BrowserRead
import com.nextcraft.forge.runtime.FileBrowser
import com.nextcraft.forge.runtime.RuntimeInstaller
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PAGE_SIZE = 300

private enum class BrowserRoot(val labelRes: Int) {
    PROJECTS(R.string.fb_root_projects),
    HOME(R.string.fb_root_home),
    LINUX(R.string.fb_root_linux),
    APP(R.string.fb_root_app),
}

/**
 * File browser (Tools): every folder the app owns, not just the open project: all projects, the Ubuntu home
 * (`/root`), the whole Ubuntu root and the app's data. Hidden files are shown. Read-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(viewModel: MainViewModel) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val rootfs = remember { RuntimeInstaller.rootfsDir(context) }
    val roots = remember {
        mapOf(
            BrowserRoot.PROJECTS to File(context.filesDir, "workspaces"),
            BrowserRoot.HOME to File(rootfs, "root"),
            BrowserRoot.LINUX to rootfs,
            BrowserRoot.APP to context.filesDir,
        )
    }
    var rootName by rememberSaveable { mutableStateOf(BrowserRoot.HOME.name) }
    val root = BrowserRoot.valueOf(rootName)
    val rootDir = roots.getValue(root)
    var currentPath by rememberSaveable { mutableStateOf<String?>(null) }
    var openFilePath by rememberSaveable { mutableStateOf<String?>(null) }
    var shown by rememberSaveable { mutableStateOf(PAGE_SIZE) }
    val current = File(currentPath ?: rootDir.path)
    val atRoot = current.path == rootDir.path

    /** The path as the Ubuntu shell sees it, or null for folders outside it. */
    fun guestPath(file: File): String? = when (root) {
        BrowserRoot.HOME, BrowserRoot.LINUX -> "/" + file.relativeTo(rootfs).invariantSeparatorsPath.trimStart('/')
        BrowserRoot.PROJECTS -> "/workspace/" + file.relativeTo(roots.getValue(BrowserRoot.PROJECTS)).invariantSeparatorsPath
        BrowserRoot.APP -> null
    }.let { it?.trimEnd('/')?.ifEmpty { "/" } }

    fun goUp() {
        if (atRoot) viewModel.selectTab(ForgeTab.TOOLS) else currentPath = current.parentFile?.path
        shown = PAGE_SIZE
    }

    BackHandler(enabled = openFilePath != null || !atRoot) {
        if (openFilePath != null) openFilePath = null else goUp()
    }

    ToolScaffold(
        title = stringResource(R.string.tools_file_browser),
        subtitle = stringResource(R.string.tools_file_browser_desc),
        onBack = { if (openFilePath != null) openFilePath = null else goUp() },
    ) {
        val filePath = openFilePath
        if (filePath != null) {
            FileContent(File(filePath), guest = guestPath(File(filePath)), onCopy = { clipboard.setText(AnnotatedString(it)) })
            return@ToolScaffold
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            BrowserRoot.entries.forEach { entry ->
                FilterChip(
                    selected = entry == root,
                    onClick = {
                        rootName = entry.name
                        currentPath = null
                        shown = PAGE_SIZE
                    },
                    label = { Text(stringResource(entry.labelRes)) },
                    modifier = Modifier.heightIn(min = ForgeSpace.minTouch).forgeSelected(entry == root),
                )
            }
        }

        val shownPath = guestPath(current) ?: current.path
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                shownPath,
                fontFamily = FontFamily.Monospace,
                fontSize = ForgeType.control,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { clipboard.setText(AnnotatedString(shownPath)) }, modifier = Modifier.heightIn(min = ForgeSpace.minTouch)) {
                Text(stringResource(R.string.fb_copy_path))
            }
        }
        guestPath(current)?.let { guest ->
            TextButton(
                onClick = {
                    viewModel.runTerminalCommand("cd " + com.nextcraft.forge.runtime.shellSingleQuote(guest))
                    viewModel.selectTab(ForgeTab.TERMINAL)
                },
                modifier = Modifier.heightIn(min = ForgeSpace.minTouch),
            ) { Text(stringResource(R.string.fb_open_terminal)) }
        }

        val listing by produceState<BrowserListing?>(null, current.path) {
            value = null
            value = withContext(Dispatchers.IO) { FileBrowser.list(current) }
        }
        when (val result = listing) {
            null -> Text(stringResource(R.string.fb_loading), color = scheme.onSurfaceVariant, fontSize = ForgeType.control)
            is BrowserListing.Denied -> Text(denialText(result.reason), color = scheme.onSurfaceVariant, fontSize = ForgeType.control)
            is BrowserListing.Entries -> {
                if (result.viaRoot) Text(stringResource(R.string.fb_via_root), color = scheme.onSurfaceVariant, fontSize = ForgeType.caption)
                if (!atRoot) {
                    BrowserRow(name = "..", isDirectory = true, isLink = false, sizeBytes = 0L, contentDescription = stringResource(R.string.fb_up)) { goUp() }
                }
                if (result.items.isEmpty()) {
                    ForgeEmptyState(glyph = ForgeGlyph.FOLDER, title = stringResource(R.string.fb_empty))
                } else {
                    Text(stringResource(R.string.fb_items, result.items.size), color = scheme.onSurfaceVariant, fontSize = ForgeType.caption)
                }
                result.items.take(shown).forEach { entry ->
                    BrowserRow(entry.name, entry.isDirectory, entry.isLink, entry.sizeBytes) {
                        val target = File(current, entry.name)
                        if (entry.isDirectory) {
                            currentPath = target.path
                            shown = PAGE_SIZE
                        } else if (!entry.isLink) {
                            openFilePath = target.path
                        }
                    }
                }
                if (result.items.size > shown) {
                    TextButton(onClick = { shown += PAGE_SIZE }, modifier = Modifier.heightIn(min = ForgeSpace.minTouch)) {
                        Text(stringResource(R.string.fb_show_more, result.items.size - shown))
                    }
                }
            }
        }
    }
}

@Composable
private fun denialText(reason: BrowserDenial): String = stringResource(
    when (reason) {
        BrowserDenial.OUTSIDE_APP_DATA -> R.string.fb_denied_outside
        BrowserDenial.MISSING -> R.string.fb_denied_missing
        BrowserDenial.NO_ACCESS -> R.string.fb_denied_access
    },
)

@Composable
private fun BrowserRow(
    name: String,
    isDirectory: Boolean,
    isLink: Boolean,
    sizeBytes: Long,
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, enabled = isDirectory || !isLink, onClick = onClick)
            .heightIn(min = ForgeSpace.minTouch)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ForgeIcon(if (isDirectory) ForgeGlyph.FOLDER else ForgeGlyph.FILE, scheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(
            text = contentDescription?.let { "$name  ($it)" } ?: name,
            fontFamily = FontFamily.Monospace,
            fontSize = ForgeType.control,
            fontWeight = if (isDirectory) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            isLink -> Text(stringResource(R.string.fb_link), fontSize = ForgeType.caption, color = scheme.onSurfaceVariant)
            !isDirectory -> Text(browserSize(sizeBytes), fontSize = ForgeType.caption, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FileContent(file: File, guest: String?, onCopy: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            guest ?: file.path,
            fontFamily = FontFamily.Monospace,
            fontSize = ForgeType.control,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onCopy(guest ?: file.path) }, modifier = Modifier.heightIn(min = ForgeSpace.minTouch)) {
            Text(stringResource(R.string.fb_copy_path))
        }
    }
    val content by produceState<BrowserRead?>(null, file.path) {
        value = null
        value = withContext(Dispatchers.IO) { FileBrowser.read(file) }
    }
    when (val result = content) {
        null -> Text(stringResource(R.string.fb_loading), color = scheme.onSurfaceVariant, fontSize = ForgeType.control)
        is BrowserRead.Denied -> Text(denialText(result.reason), color = scheme.onSurfaceVariant, fontSize = ForgeType.control)
        is BrowserRead.Binary -> Text(stringResource(R.string.fb_binary, browserSize(result.sizeBytes)), color = scheme.onSurfaceVariant, fontSize = ForgeType.control)
        is BrowserRead.Text -> {
            if (result.truncated) {
                Text(stringResource(R.string.fb_truncated, browserSize(result.sizeBytes)), color = scheme.onSurfaceVariant, fontSize = ForgeType.caption)
            }
            DiffCodeView(lines = remember(result.text) { fileAsViewerLines(result.text) }, path = file.name, previewLines = 400)
        }
    }
}

private fun browserSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
