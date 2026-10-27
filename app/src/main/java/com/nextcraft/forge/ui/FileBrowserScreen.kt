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
import com.nextcraft.forge.runtime.BrowserListing
import com.nextcraft.forge.runtime.BrowserRead
import com.nextcraft.forge.runtime.FileBrowser
import com.nextcraft.forge.runtime.RuntimeInstaller
import com.nextcraft.forge.runtime.shellSingleQuote
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val DIRECTORY_PAGE_CHUNK = 300

private enum class MountAnchor(val titleRes: Int) {
    PROJECTS(R.string.fb_root_projects),
    HOME(R.string.fb_root_home),
    LINUX(R.string.fb_root_linux),
    APP(R.string.fb_root_app),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(viewModel: MainViewModel) {
    val palette = MaterialTheme.colorScheme
    val appContext = LocalContext.current
    val clipboardMgr = LocalClipboardManager.current
    val guestRootDir = remember { RuntimeInstaller.rootfsDir(appContext) }
    val mountDirectories = remember {
        mapOf(
            MountAnchor.PROJECTS to File(appContext.filesDir, "workspaces"),
            MountAnchor.HOME to File(guestRootDir, "root"),
            MountAnchor.LINUX to guestRootDir,
            MountAnchor.APP to appContext.filesDir,
        )
    }
    var selectedAnchorKey by rememberSaveable { mutableStateOf(MountAnchor.HOME.name) }
    val activeAnchor = MountAnchor.valueOf(selectedAnchorKey)
    val anchorBaseDir = mountDirectories.getValue(activeAnchor)
    var activeDirPath by rememberSaveable { mutableStateOf<String?>(null) }
    var inspectedFilePath by rememberSaveable { mutableStateOf<String?>(null) }
    var visibleLimit by rememberSaveable { mutableStateOf(DIRECTORY_PAGE_CHUNK) }

    val currentDir = File(activeDirPath ?: anchorBaseDir.path)
    val isAtAnchorBase = currentDir.path == anchorBaseDir.path

    fun resolveGuestUnixPath(hostFile: File): String? {
        val rawPath = when (activeAnchor) {
            MountAnchor.HOME, MountAnchor.LINUX -> "/" + hostFile.relativeTo(guestRootDir).invariantSeparatorsPath.trimStart('/')
            MountAnchor.PROJECTS -> "/workspace/" + hostFile.relativeTo(mountDirectories.getValue(MountAnchor.PROJECTS)).invariantSeparatorsPath
            MountAnchor.APP -> null
        }
        return rawPath?.trimEnd('/')?.ifEmpty { "/" }
    }

    fun navigateUpOneLevel() {
        if (isAtAnchorBase) {
            viewModel.selectTab(ForgeTab.TOOLS)
        } else {
            activeDirPath = currentDir.parentFile?.path
        }
        visibleLimit = DIRECTORY_PAGE_CHUNK
    }

    BackHandler(enabled = inspectedFilePath != null || !isAtAnchorBase) {
        if (inspectedFilePath != null) inspectedFilePath = null else navigateUpOneLevel()
    }

    ToolScaffold(
        title = stringResource(R.string.tools_file_browser),
        subtitle = stringResource(R.string.tools_file_browser_desc),
        onBack = { if (inspectedFilePath != null) inspectedFilePath = null else navigateUpOneLevel() },
    ) {
        val previewTarget = inspectedFilePath
        if (previewTarget != null) {
            val targetFile = File(previewTarget)
            ReadOnlyFileInspector(
                targetFile = targetFile,
                guestUnixPath = resolveGuestUnixPath(targetFile),
                onCopyPath = { copied -> clipboardMgr.setText(AnnotatedString(copied)) },
            )
            return@ToolScaffold
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MountAnchor.entries.forEach { anchorOption ->
                val isSelected = anchorOption == activeAnchor
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        selectedAnchorKey = anchorOption.name
                        activeDirPath = null
                        visibleLimit = DIRECTORY_PAGE_CHUNK
                    },
                    label = { Text(text = stringResource(anchorOption.titleRes)) },
                    modifier = Modifier.heightIn(min = ForgeSpace.minTouch).forgeSelected(isSelected),
                )
            }
        }

        val effectiveDisplayPath = resolveGuestUnixPath(currentDir) ?: currentDir.path
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = effectiveDisplayPath,
                fontFamily = FontFamily.Monospace,
                fontSize = ForgeType.control,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { clipboardMgr.setText(AnnotatedString(effectiveDisplayPath)) },
                modifier = Modifier.heightIn(min = ForgeSpace.minTouch),
            ) {
                Text(text = stringResource(R.string.fb_copy_path))
            }
        }
        resolveGuestUnixPath(currentDir)?.let { unixDir ->
            TextButton(
                onClick = {
                    viewModel.runTerminalCommand("cd ${shellSingleQuote(unixDir)}")
                    viewModel.selectTab(ForgeTab.TERMINAL)
                },
                modifier = Modifier.heightIn(min = ForgeSpace.minTouch),
            ) {
                Text(text = stringResource(R.string.fb_open_terminal))
            }
        }

        val dirListingState by produceState<BrowserListing?>(initialValue = null, key1 = currentDir.path) {
            value = null
            value = withContext(Dispatchers.IO) { FileBrowser.list(currentDir) }
        }
        when (val snapshot = dirListingState) {
            null -> Text(text = stringResource(R.string.fb_loading), color = palette.onSurfaceVariant, fontSize = ForgeType.control)
            is BrowserListing.Denied -> Text(text = resolveDenialMessage(snapshot.reason), color = palette.onSurfaceVariant, fontSize = ForgeType.control)
            is BrowserListing.Entries -> {
                if (snapshot.viaRoot) {
                    Text(text = stringResource(R.string.fb_via_root), color = palette.onSurfaceVariant, fontSize = ForgeType.caption)
                }
                if (!isAtAnchorBase) {
                    DirectoryEntryItem(
                        entryName = "..",
                        isFolder = true,
                        isSymlink = false,
                        byteLength = 0L,
                        accessibilityHint = stringResource(R.string.fb_up),
                        onActivate = ::navigateUpOneLevel,
                    )
                }
                if (snapshot.items.isEmpty()) {
                    ForgeEmptyState(glyph = ForgeGlyph.FOLDER, title = stringResource(R.string.fb_empty))
                } else {
                    Text(
                        text = stringResource(R.string.fb_items, snapshot.items.size),
                        color = palette.onSurfaceVariant,
                        fontSize = ForgeType.caption,
                    )
                }
                snapshot.items.take(visibleLimit).forEach { node ->
                    DirectoryEntryItem(
                        entryName = node.name,
                        isFolder = node.isDirectory,
                        isSymlink = node.isLink,
                        byteLength = node.sizeBytes,
                    ) {
                        val childTarget = File(currentDir, node.name)
                        if (node.isDirectory) {
                            activeDirPath = childTarget.path
                            visibleLimit = DIRECTORY_PAGE_CHUNK
                        } else if (!node.isLink) {
                            inspectedFilePath = childTarget.path
                        }
                    }
                }
                if (snapshot.items.size > visibleLimit) {
                    TextButton(
                        onClick = { visibleLimit += DIRECTORY_PAGE_CHUNK },
                        modifier = Modifier.heightIn(min = ForgeSpace.minTouch),
                    ) {
                        Text(text = stringResource(R.string.fb_show_more, snapshot.items.size - visibleLimit))
                    }
                }
            }
        }
    }
}

@Composable
private fun resolveDenialMessage(reason: BrowserDenial): String {
    val stringId = when (reason) {
        BrowserDenial.OUTSIDE_APP_DATA -> R.string.fb_denied_outside
        BrowserDenial.MISSING -> R.string.fb_denied_missing
        BrowserDenial.NO_ACCESS -> R.string.fb_denied_access
    }
    return stringResource(stringId)
}

@Composable
private fun DirectoryEntryItem(
    entryName: String,
    isFolder: Boolean,
    isSymlink: Boolean,
    byteLength: Long,
    accessibilityHint: String? = null,
    onActivate: () -> Unit,
) {
    val palette = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, enabled = isFolder || !isSymlink, onClick = onActivate)
            .heightIn(min = ForgeSpace.minTouch)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ForgeIcon(glyph = if (isFolder) ForgeGlyph.FOLDER else ForgeGlyph.FILE, tint = palette.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        val renderedTitle = accessibilityHint?.let { hint -> "$entryName  ($hint)" } ?: entryName
        Text(
            text = renderedTitle,
            fontFamily = FontFamily.Monospace,
            fontSize = ForgeType.control,
            fontWeight = if (isFolder) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            isSymlink -> Text(text = stringResource(R.string.fb_link), fontSize = ForgeType.caption, color = palette.onSurfaceVariant)
            !isFolder -> Text(text = formatEntryByteCount(byteLength), fontSize = ForgeType.caption, color = palette.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReadOnlyFileInspector(
    targetFile: File,
    guestUnixPath: String?,
    onCopyPath: (String) -> Unit,
) {
    val palette = MaterialTheme.colorScheme
    val displayPath = guestUnixPath ?: targetFile.path
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = displayPath,
            fontFamily = FontFamily.Monospace,
            fontSize = ForgeType.control,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onCopyPath(displayPath) }, modifier = Modifier.heightIn(min = ForgeSpace.minTouch)) {
            Text(text = stringResource(R.string.fb_copy_path))
        }
    }
    val readOutcome by produceState<BrowserRead?>(initialValue = null, key1 = targetFile.path) {
        value = null
        value = withContext(Dispatchers.IO) { FileBrowser.read(targetFile) }
    }
    when (val outcome = readOutcome) {
        null -> Text(text = stringResource(R.string.fb_loading), color = palette.onSurfaceVariant, fontSize = ForgeType.control)
        is BrowserRead.Denied -> Text(text = resolveDenialMessage(outcome.reason), color = palette.onSurfaceVariant, fontSize = ForgeType.control)
        is BrowserRead.Binary -> Text(
            text = stringResource(R.string.fb_binary, formatEntryByteCount(outcome.sizeBytes)),
            color = palette.onSurfaceVariant,
            fontSize = ForgeType.control,
        )
        is BrowserRead.Text -> {
            if (outcome.truncated) {
                Text(
                    text = stringResource(R.string.fb_truncated, formatEntryByteCount(outcome.sizeBytes)),
                    color = palette.onSurfaceVariant,
                    fontSize = ForgeType.caption,
                )
            }
            DiffCodeView(
                lines = remember(outcome.text) { fileAsViewerLines(outcome.text) },
                path = targetFile.name,
                previewLines = 400,
            )
        }
    }
}

private fun formatEntryByteCount(bytes: Long): String = when {
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}
