@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.material3.RadioButton
import androidx.compose.ui.res.pluralStringResource
import com.nextcraft.forge.model.WorkspaceDirListing
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Checkbox
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.DiffLineType
import com.nextcraft.forge.model.WorkspaceEntry
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.ui.res.stringResource
import com.nextcraft.forge.R
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults

@Composable
fun FilesAndDiffScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
    /** Set when this is one workspace opened from the global file manager: Back then returns to the workspace list. */
    onBackToWorkspaces: (() -> Unit)? = null,
) {
    var mode by rememberSaveable(state.activeProjectId) { mutableStateOf(if (state.selectedFilePath != null) "editor" else "files") } // "files" | "editor" | "diff"
    var showNewFileDialog by rememberSaveable { mutableStateOf(false) }
    var newFileBaseDir by rememberSaveable { mutableStateOf("") }
    var newFolderBaseDir by rememberSaveable { mutableStateOf<String?>(null) }
    var renameTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeletePath by rememberSaveable { mutableStateOf<String?>(null) }
    // Not saveable, like selectedPaths: restoring `selecting` without its list made "export selection" export everything.
    var selecting by remember { mutableStateOf(false) }
    val selectedPaths = remember { mutableStateListOf<String>() }
    var uploadDir by rememberSaveable { mutableStateOf("") }
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.importFilesToWorkspace(uris, uploadDir)
    }
    val zipSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        // Cancelling the save dialog keeps the selection, so the user can pick another place instead of starting over.
        if (uri != null) {
            viewModel.exportWorkspaceZip(uri, selectedPaths.toList())
            selecting = false
            selectedPaths.clear()
        }
    }
    var saveCopyPath by rememberSaveable { mutableStateOf<String?>(null) }
    val fileSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val path = saveCopyPath
        if (uri != null && path != null) viewModel.exportWorkspaceFile(uri, path)
        saveCopyPath = null
    }
    val inProject = state.activeTab == ForgeTab.PROJECTS
    val workspaceName = state.activeProject?.name ?: stringResource(R.string.files_files)

    // The folder being browsed, relative to the workspace root ("" is the workspace folder itself). Reset per project.
    var browseDir by rememberSaveable(state.activeProjectId) { mutableStateOf("") }
    var sortName by rememberSaveable { mutableStateOf(FileSort.NAME.name) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val sort = FileSort.entries.firstOrNull { it.name == sortName } ?: FileSort.NAME
    // Listed from disk one folder at a time, so a folder's item count is its real one. state.workspaceFiles changes
    // whenever the agent, terminal or a revert touches the workspace, which re-lists the open folder.
    val dirLoad by produceState<DirLoad>(DirLoad.Loading, state.activeProjectId, browseDir, state.workspaceFiles, refreshTick) {
        val dir = browseDir
        value = viewModel.listWorkspaceDirectory(dir)?.let { DirLoad.Ready(dir, it) } ?: DirLoad.Missing(dir)
    }
    // A folder that was deleted or renamed (by the agent, the terminal or from here): step up to one that still exists.
    LaunchedEffect(dirLoad, browseDir) {
        val load = dirLoad
        if (load is DirLoad.Missing && load.dir == browseDir && browseDir.isNotEmpty()) {
            browseDir = browseDir.substringBeforeLast('/', "")
        }
    }
    val loaded = (dirLoad as? DirLoad.Ready)?.takeIf { it.dir == browseDir }
    val shownEntries = remember(loaded, sort, showHidden) {
        sortWorkspaceEntries(loaded?.listing?.entries.orEmpty(), sort, showHidden)
    }
    // Back climbs the folder tree first, then leaves the explorer: editor/changes -> explorer, workspace root -> list.
    BackHandler(enabled = mode != "files" || browseDir.isNotEmpty() || (!inProject && onBackToWorkspaces != null)) {
        when {
            mode != "files" -> mode = "files"
            browseDir.isNotEmpty() -> browseDir = browseDir.substringBeforeLast('/', "")
            else -> onBackToWorkspaces?.invoke()
        }
    }

    androidx.compose.runtime.LaunchedEffect(state.selectedFilePath) {
        if (state.selectedFilePath != null) {
            mode = "editor"
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (inProject) {
            ForgeProjectHeader(
                title = workspaceName,
                subtitle = stringResource(R.string.files_files),
                onBack = { viewModel.projectBack() },
            )
        } else {
            // Opened from the Tools file manager: the workspace is the title, Back returns to the workspace list.
            ForgeProjectHeader(
                title = workspaceName,
                subtitle = stringResource(R.string.fm_title),
                onBack = onBackToWorkspaces ?: { viewModel.selectTab(ForgeTab.TOOLS) },
            )
        }
        FilesHeaderCard(
            // The screen header and the breadcrumb already name the workspace and folder; this says how much is in it.
            title = pluralStringResource(R.plurals.fm_items_count, shownEntries.size, shownEntries.size),
            selecting = selecting,
            selectedCount = selectedPaths.size,
            enabled = !state.activeProjectAgentRunning,
            sort = sort,
            showHidden = showHidden,
            onSort = { sortName = it.name },
            onToggleHidden = { showHidden = !showHidden },
            onNewFile = { newFileBaseDir = browseDir; showNewFileDialog = true },
            onNewFolder = { newFolderBaseDir = browseDir },
            onUpload = { uploadDir = browseDir; uploadPicker.launch(arrayOf("*/*")) },
            onToggleSelect = {
                selecting = !selecting
                if (!selecting) selectedPaths.clear()
            },
            onRefresh = {
                refreshTick++
                state.activeProject?.let { viewModel.refreshWorkspaceFilesAndChanges(it.id) }
            },
            searching = searching,
            onToggleSearch = {
                searching = !searching
                if (!searching) searchQuery = ""
                mode = "files"
            },
        )
        val modes = listOf("files", "editor", "diff")
        ForgePillTabs(
            items = listOf(
                stringResource(R.string.files_tab_explorer, shownEntries.size),
                state.selectedFilePath?.substringAfterLast('/') ?: stringResource(R.string.files_tab_editor),
                stringResource(R.string.files_tab_changes, state.changedItems.size),
            ),
            selectedIndex = modes.indexOf(mode).coerceAtLeast(0),
            onSelect = { mode = modes[it] },
        )

        when (mode) {
            "files" -> Column(modifier = Modifier.weight(1f)) {
                // Search covers the whole workspace (not just the open folder) and lists only the matches, flat.
                val search = remember(state.workspaceFiles, searchQuery, searching) {
                    if (searching && searchQuery.isNotBlank()) searchWorkspaceEntries(state.workspaceFiles, searchQuery) else null
                }
                if (searching) {
                    FileSearchBar(query = searchQuery, onQueryChange = { searchQuery = it }, resultCount = search?.matches?.size)
                } else {
                    FileBreadcrumbs(rootName = workspaceName, dir = browseDir, onNavigate = { browseDir = it })
                }
                val searchHits = search?.entries?.filter { it.path in search.matches }
                if (searchHits != null && searchHits.isEmpty()) {
                    ForgeEmptyState(
                        glyph = ForgeGlyph.SEARCH,
                        title = stringResource(R.string.files_search_empty_title),
                        message = stringResource(R.string.files_search_empty_message, searchQuery.trim()),
                        modifier = Modifier.weight(1f),
                    )
                } else WorkspaceFolderList(
                    entries = searchHits ?: shownEntries,
                    loading = searchHits == null && loaded == null,
                    atRoot = browseDir.isEmpty(),
                    truncated = searchHits == null && loaded?.listing?.truncated == true,
                    selectedPath = state.selectedFilePath,
                    selecting = selecting,
                    selectedPaths = selectedPaths,
                    enabled = !state.activeProjectAgentRunning,
                    onOpenFolder = { dir ->
                        // Opening a folder from a search result leaves the search and goes there.
                        searching = false
                        searchQuery = ""
                        browseDir = dir
                    },
                    onSelectFile = { path ->
                        viewModel.openWorkspaceFile(path)
                        mode = "editor"
                    },
                    onNewFileIn = { dir -> newFileBaseDir = dir; showNewFileDialog = true },
                    onNewFolderIn = { dir -> newFolderBaseDir = dir },
                    onUploadIn = { dir -> uploadDir = dir; uploadPicker.launch(arrayOf("*/*")) },
                    onRename = { path -> renameTarget = path },
                    onSaveCopy = { path -> saveCopyPath = path; fileSaver.launch(path.substringAfterLast('/')) },
                    onEditInTerminal = viewModel::editInTerminal,
                    onInstallApk = viewModel::installWorkspaceApk,
                    onAnalyzeWithRea = viewModel::analyzeWithRea,
                    onDelete = { path -> pendingDeletePath = path },
                    modifier = Modifier.weight(1f),
                )
                if (selecting) {
                    ForgePrimaryButton(
                        text = stringResource(R.string.files_download_zip),
                        onClick = { zipSaver.launch("${state.activeProject?.slug ?: "workspace"}.zip") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
            "editor" -> WorkspaceFileEditor(
                selectedPath = state.selectedFilePath,
                content = state.selectedFileContent,
                editable = state.selectedFileEditable,
                dirty = state.selectedFileDirty,
                sessionRunning = state.activeProjectAgentRunning,
                savedBanner = state.fileSavedBanner,
                onContentChange = viewModel::updateSelectedFileContent,
                onSave = viewModel::saveSelectedFile,
                onRequestDelete = { path -> pendingDeletePath = path },
                onBackToExplorer = { mode = "files" },
                modifier = Modifier.weight(1f),
            )
            else -> WorkspaceDiffReviewList(
                changes = state.changedItems,
                sessionRunning = state.activeProjectAgentRunning,
                onOpenFile = { path ->
                    viewModel.openWorkspaceFile(path)
                    mode = "editor"
                },
                onRevertFile = viewModel::revertSingleFile,
                onRevertHunk = viewModel::revertDiffHunk,
                onRevertAll = viewModel::revertAllChanges,
                onKeepFile = viewModel::keepSingleFile,
                onKeepAll = viewModel::keepAllChanges,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (showNewFileDialog) {
        NewWorkspaceFileDialog(
            baseDir = newFileBaseDir,
            onDismiss = { showNewFileDialog = false },
            onCreate = { relPath ->
                viewModel.createNewWorkspaceFile(relPath)
                showNewFileDialog = false
                mode = "editor"
            },
        )
    }
    newFolderBaseDir?.let { base ->
        NameDialog(
            title = stringResource(R.string.files_new_folder),
            label = stringResource(R.string.files_folder_name),
            initial = "",
            onDismiss = { newFolderBaseDir = null },
            onConfirm = { name ->
                viewModel.createWorkspaceFolder(if (base.isEmpty()) name else "$base/$name")
                newFolderBaseDir = null
            },
        )
    }
    renameTarget?.let { path ->
        NameDialog(
            title = stringResource(R.string.files_rename),
            label = stringResource(R.string.files_new_name),
            initial = path.substringAfterLast('/'),
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                viewModel.renameWorkspaceEntry(path, name)
                renameTarget = null
            },
        )
    }

    pendingDeletePath?.let { targetPath ->
        ForgeAlertDialog(
            modifier = Modifier.forgePopIn(),
            onDismissRequest = { pendingDeletePath = null },
            title = { Text(stringResource(R.string.files_delete_file_confirm_title)) },
            text = { Text(stringResource(R.string.files_delete_file_confirm_message, targetPath)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteWorkspaceFile(targetPath)
                        pendingDeletePath = null
                        mode = "files"
                    },
                ) {
                    Text(stringResource(R.string.shell_confirm_delete))
                }
            },
            dismissButton = {
                ForgeTextButton(onClick = { pendingDeletePath = null }) {
                    Text(stringResource(R.string.agent_cancel))
                }
            },
        )
    }
}

/** Card header for the explorer: title, "+" menu (new file / folder / upload), select-for-download, refresh. */
@Composable
internal fun FilesHeaderCard(
    title: String,
    selecting: Boolean,
    selectedCount: Int,
    enabled: Boolean,
    sort: FileSort,
    showHidden: Boolean,
    onSort: (FileSort) -> Unit,
    onToggleHidden: () -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onUpload: () -> Unit,
    onToggleSelect: () -> Unit,
    onRefresh: () -> Unit,
    searching: Boolean = false,
    onToggleSearch: () -> Unit = {},
) {
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    var addOpen by remember { mutableStateOf(false) }
    var viewOpen by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = tokens.cardContainerColor,
        contentColor = scheme.onSurface,
        border = tokens.cardBorderStroke,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (selecting) stringResource(R.string.files_selected_count, selectedCount) else title,
                fontSize = ForgeType.cardTitle,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ForgeGlyphPillIcon(
                glyph = ForgeGlyph.SEARCH,
                description = stringResource(R.string.files_search),
                onClick = onToggleSearch,
                tint = if (searching) scheme.primary else scheme.onSurface,
            )
            Box {
                ForgePillIcon(
                    icon = Icons.Default.List,
                    description = stringResource(R.string.fm_sort),
                    onClick = { viewOpen = true },
                )
                DropdownMenu(expanded = viewOpen, onDismissRequest = { viewOpen = false }) {
                    listOf(
                        FileSort.NAME to R.string.fm_sort_name,
                        FileSort.MODIFIED to R.string.fm_sort_modified,
                        FileSort.SIZE to R.string.fm_sort_size,
                    ).forEach { (option, label) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) },
                            leadingIcon = { RadioButton(selected = sort == option, onClick = null) },
                            onClick = { viewOpen = false; onSort(option) },
                        )
                    }
                    Divider(color = scheme.outlineVariant, thickness = 0.5.dp)
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.fm_show_hidden)) },
                        leadingIcon = { Checkbox(checked = showHidden, onCheckedChange = null) },
                        onClick = { viewOpen = false; onToggleHidden() },
                    )
                }
            }
            Box {
                ForgePillIcon(
                    icon = Icons.Default.Add,
                    description = stringResource(R.string.files_add_menu),
                    enabled = enabled,
                    onClick = { addOpen = true },
                )
                DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_new_file)) },
                        leadingIcon = { ForgeIcon(ForgeGlyph.FILE, scheme.onSurfaceVariant) },
                        onClick = { addOpen = false; onNewFile() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_new_folder)) },
                        leadingIcon = { ForgeIcon(ForgeGlyph.FOLDER, scheme.onSurfaceVariant) },
                        onClick = { addOpen = false; onNewFolder() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_upload_here)) },
                        leadingIcon = { ForgeIcon(ForgeGlyph.UPLOAD, scheme.onSurfaceVariant) },
                        onClick = { addOpen = false; onUpload() },
                    )
                }
            }
            if (selecting) {
                ForgePillIcon(
                    icon = Icons.Default.Close,
                    description = stringResource(R.string.agent_cancel),
                    onClick = onToggleSelect,
                    tint = scheme.primary,
                )
            } else {
                // Refresh and "download as zip" were two more bare icons; with text labels in a menu they are clearer
                // and the row keeps three well-spaced buttons.
                var moreOpen by remember { mutableStateOf(false) }
                Box {
                    ForgePillIcon(
                        icon = Icons.Default.MoreVert,
                        description = stringResource(R.string.files_more),
                        onClick = { moreOpen = true },
                    )
                    DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_refresh)) },
                            leadingIcon = { ForgeIcon(ForgeGlyph.REFRESH, scheme.onSurfaceVariant) },
                            onClick = { moreOpen = false; onRefresh() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_download_zip)) },
                            leadingIcon = { ForgeIcon(ForgeGlyph.DOWNLOAD, scheme.onSurfaceVariant) },
                            onClick = { moreOpen = false; onToggleSelect() },
                        )
                    }
                }
            }
        }
    }
}

/** How the folder being browsed is ordered; folders always come first. */
internal enum class FileSort { NAME, MODIFIED, SIZE }

/** Result of listing the open folder: the folder it was listed for is kept so a slower answer is never shown for another one. */
private sealed interface DirLoad {
    data object Loading : DirLoad
    data class Ready(val dir: String, val listing: WorkspaceDirListing) : DirLoad
    data class Missing(val dir: String) : DirLoad
}

internal fun sortWorkspaceEntries(entries: List<WorkspaceEntry>, sort: FileSort, showHidden: Boolean): List<WorkspaceEntry> {
    val visible = if (showHidden) entries else entries.filterNot { it.name.startsWith('.') }
    val byName = compareBy<WorkspaceEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
    val within: Comparator<WorkspaceEntry> = when (sort) {
        FileSort.NAME -> byName
        FileSort.MODIFIED -> compareByDescending<WorkspaceEntry> { it.modifiedMillis }.then(byName)
        FileSort.SIZE -> compareByDescending<WorkspaceEntry> { it.sizeBytes }.then(byName)
    }
    // Folders first, like every file manager; the chosen order applies inside each group.
    return visible.sortedWith(compareByDescending<WorkspaceEntry> { it.isDirectory }.then(within))
}

/** "Workspace > folder > subfolder": the workspace folder is always the first crumb, every crumb jumps back to that level. */
@Composable
internal fun FileBreadcrumbs(rootName: String, dir: String, onNavigate: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    val segments = if (dir.isEmpty()) emptyList() else dir.split('/')
    // Keep the current (deepest) crumb in view when the path is longer than the screen.
    LaunchedEffect(dir) { scroll.animateScrollTo(scroll.maxValue) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crumb(label = rootName, isRoot = true, current = segments.isEmpty()) { onNavigate("") }
        segments.forEachIndexed { index, name ->
            Text(text = "›", fontSize = ForgeType.body, color = scheme.onSurfaceVariant)
            val path = segments.take(index + 1).joinToString("/")
            Crumb(label = name, isRoot = false, current = index == segments.lastIndex) { onNavigate(path) }
        }
    }
}

@Composable
private fun Crumb(label: String, isRoot: Boolean, current: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = !current, role = Role.Button) { onClick() }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isRoot) {
            ForgeIcon(glyph = ForgeGlyph.FOLDER, tint = scheme.primary, size = 18.dp)
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = label,
            fontSize = ForgeType.body,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = if (current) scheme.onSurface else scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The direct children of the open folder: tap a folder to go in, tap a file to open it, ⋮ for the actions. */
@Composable
internal fun WorkspaceFolderList(
    entries: List<WorkspaceEntry>,
    loading: Boolean,
    atRoot: Boolean,
    truncated: Boolean,
    selectedPath: String?,
    selecting: Boolean,
    selectedPaths: MutableList<String>,
    enabled: Boolean,
    onOpenFolder: (String) -> Unit,
    onSelectFile: (String) -> Unit,
    onNewFileIn: (String) -> Unit,
    onNewFolderIn: (String) -> Unit,
    onUploadIn: (String) -> Unit,
    onRename: (String) -> Unit,
    onSaveCopy: (String) -> Unit,
    onEditInTerminal: (String) -> Unit,
    onInstallApk: (String) -> Unit,
    onAnalyzeWithRea: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    if (entries.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (!loading) {
                ForgeEmptyState(
                    glyph = ForgeGlyph.FOLDER,
                    title = stringResource(if (atRoot) R.string.files_empty_title else R.string.fm_folder_empty),
                    message = if (atRoot) stringResource(R.string.files_workspace_is_empty_use) else null,
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        items(entries, key = { it.path }) { entry ->
            val isSelected = entry.path == selectedPath
            var menuOpen by remember { mutableStateOf(false) }
            val checked = entry.path in selectedPaths
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) scheme.onSurface.copy(alpha = 0.08f) else Color.Transparent)
                    .clickable(role = Role.Button) {
                        when {
                            selecting -> if (checked) selectedPaths.remove(entry.path) else selectedPaths.add(entry.path)
                            entry.isDirectory -> onOpenFolder(entry.path)
                            else -> onSelectFile(entry.path)
                        }
                    }
                    .heightIn(min = 56.dp)
                    .padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selecting) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Spacer(modifier = Modifier.width(4.dp))
                }
                // Stroke glyphs at the same weight as the nav bar; the accent only marks folders.
                ForgeIcon(
                    glyph = if (entry.isDirectory) ForgeGlyph.FOLDER else ForgeGlyph.FILE,
                    tint = if (entry.isDirectory) scheme.primary else scheme.onSurfaceVariant,
                    size = 22.dp,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.name,
                        fontSize = ForgeType.body,
                        color = if (entry.isDirectory) scheme.onSurface else tokens.accentText,
                        fontWeight = if (entry.isDirectory || isSelected) FontWeight.Medium else FontWeight.Normal,
                        // One line with the middle cut out, so a long name still shows its extension.
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    val detail = if (entry.isDirectory) {
                        val count = entry.childCount ?: 0
                        pluralStringResource(R.plurals.fm_items_count, count, count)
                    } else {
                        formatBytes(entry.sizeBytes)
                    }
                    Text(
                        text = if (entry.modifiedMillis > 0L) {
                            stringResource(R.string.fm_meta, detail, formatRelativeTime(entry.modifiedMillis))
                        } else {
                            detail
                        },
                        fontSize = ForgeType.caption,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    // Always open: installing, analysing or saving a copy reads the file and is safe while an agent works.
                    IconButton(onClick = { menuOpen = true }, enabled = !selecting) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.files_row_menu), tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        // The button sits at the right edge; shift the menu left so it keeps a margin to the screen edge.
                        offset = androidx.compose.ui.unit.DpOffset((-12).dp, 0.dp),
                    ) {
                        if (entry.isDirectory) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.files_new_file)) }, leadingIcon = { ForgeIcon(ForgeGlyph.FILE, scheme.onSurfaceVariant) }, onClick = { menuOpen = false; onNewFileIn(entry.path) }, enabled = enabled)
                            DropdownMenuItem(text = { Text(stringResource(R.string.files_new_folder)) }, leadingIcon = { ForgeIcon(ForgeGlyph.FOLDER, scheme.onSurfaceVariant) }, onClick = { menuOpen = false; onNewFolderIn(entry.path) }, enabled = enabled)
                            DropdownMenuItem(text = { Text(stringResource(R.string.files_upload_here)) }, leadingIcon = { ForgeIcon(ForgeGlyph.UPLOAD, scheme.onSurfaceVariant) }, onClick = { menuOpen = false; onUploadIn(entry.path) }, enabled = enabled)
                            Divider(color = scheme.outlineVariant, thickness = 0.5.dp)
                        }
                        if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.files_install_apk)) }, leadingIcon = { ForgeIcon(ForgeGlyph.BUILD, scheme.onSurfaceVariant) }, onClick = { menuOpen = false; onInstallApk(entry.path) })
                        }
                        if (!entry.isDirectory) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.files_edit_in_terminal)) }, leadingIcon = { ForgeIcon(ForgeGlyph.TERMINAL, scheme.onSurfaceVariant) }, onClick = { menuOpen = false; onEditInTerminal(entry.path) }, enabled = enabled)
                            DropdownMenuItem(text = { Text(stringResource(R.string.files_analyze_rea)) }, leadingIcon = { ForgeIcon(ForgeGlyph.SEARCH, scheme.onSurfaceVariant) }, onClick = { menuOpen = false; onAnalyzeWithRea(entry.path) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.files_save_copy)) }, leadingIcon = { ForgeIcon(ForgeGlyph.DOWNLOAD, scheme.onSurfaceVariant) }, onClick = { menuOpen = false; onSaveCopy(entry.path) })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.files_rename)) }, leadingIcon = { ForgeIcon(ForgeGlyph.EDIT, scheme.onSurfaceVariant) }, onClick = { menuOpen = false; onRename(entry.path) }, enabled = enabled)
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_delete), color = tokens.dangerText) },
                            leadingIcon = { ForgeIcon(ForgeGlyph.TRASH, tokens.dangerText) },
                            onClick = { menuOpen = false; onDelete(entry.path) },
                            enabled = enabled,
                        )
                    }
                }
            }
        }
        if (truncated) {
            item(key = "truncated") {
                Text(
                    text = stringResource(R.string.fm_truncated, entries.size),
                    fontSize = ForgeType.caption,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
}

/** Single-field dialog used for New folder and Rename. */
@Composable
private fun NameDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    ForgeAlertDialog(
        modifier = Modifier.forgePopIn(),
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = {
            ForgeTextField(
                kind = ForgeFieldKind.IDENTIFIER, imeAction = ImeAction.Done,
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { if (value.isNotBlank()) onConfirm(value.trim()) },
                enabled = value.isNotBlank() && !value.contains('/'),
                shape = RoundedCornerShape(100),
            ) { Text(stringResource(R.string.files_ok), fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { ForgeTextButton(onClick = onDismiss) { Text(stringResource(R.string.agent_cancel)) } },
    )
}

@Composable
private fun WorkspaceFileEditor(
    selectedPath: String?,
    content: String,
    editable: Boolean,
    dirty: Boolean,
    sessionRunning: Boolean,
    savedBanner: String?,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onRequestDelete: (String) -> Unit,
    onBackToExplorer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    if (selectedPath == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.files_select_a_file_from),
                fontSize = ForgeType.control,
                color = scheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedButton(
                onClick = onBackToExplorer,
                shape = RoundedCornerShape(100),
                modifier = Modifier.heightIn(min = 44.dp),
            ) { Text(stringResource(R.string.files_open_file_explorer)) }
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 14.dp, top = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (dirty) "$selectedPath *" else selectedPath,
                fontFamily = FontFamily.Monospace,
                fontSize = ForgeType.secondary,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!editable) {
                Text(
                    text = stringResource(R.string.files_read_only_badge),
                    fontSize = ForgeType.caption,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.warningText,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            if (savedBanner != null) {
                // Saved confirmation: a status glyph instead of green text, so it reads in every theme.
                ForgeStatusIcon(ForgeStatus.DONE, iconSize = 18.dp)
                Spacer(modifier = Modifier.width(10.dp))
            }
            ForgePillIcon(
                icon = Icons.Default.Delete,
                description = stringResource(R.string.files_delete_file),
                onClick = { onRequestDelete(selectedPath) },
                enabled = !sessionRunning,
            )
            Spacer(modifier = Modifier.width(6.dp))
            if (editable) {
                ForgePrimaryButton(
                    text = stringResource(R.string.files_save),
                    onClick = onSave,
                    enabled = dirty && !sessionRunning,
                )
            }
        }
        // Code surface: deepest theme surface, rounded, no outline; the field is transparent on top of it.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(tokens.terminalBackground),
        ) {
            TextField(
                value = content,
                onValueChange = { if (editable) onContentChange(it) },
                readOnly = !editable,
                keyboardOptions = ForgeFieldKind.IDENTIFIER.keyboardOptions(ImeAction.Default),
                textStyle = ForgeCodeTextStyle.copy(color = tokens.terminalText),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    errorContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = scheme.primary,
                ),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun WorkspaceDiffReviewList(
    changes: List<ChangeItem>,
    sessionRunning: Boolean,
    onOpenFile: (String) -> Unit,
    onRevertFile: (String) -> Unit,
    onRevertHunk: (String, Int) -> Unit,
    onRevertAll: () -> Unit,
    onKeepFile: (String) -> Unit,
    onKeepAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalForgeThemeTokens.current
    if (changes.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.files_no_unreviewed_checkpoint_changes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.files_modified_file_s_since, changes.size),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onKeepAll, enabled = !sessionRunning) { Text(stringResource(R.string.files_keep_all)) }
                OutlinedButton(onClick = onRevertAll, enabled = !sessionRunning) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.files_undo_all))
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(changes, key = { it.path }) { item ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = item.path,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(role = Role.Button) { onOpenFile(item.path) },
                            )
                            Text(
                                text = "+${item.additions}",
                                color = tokens.successText,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = ForgeType.caption,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "-${item.deletions}",
                                color = tokens.dangerText,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = ForgeType.caption,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = { onKeepFile(item.path) },
                                enabled = !sessionRunning,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.files_keep_file),
                                    tint = tokens.successText,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            IconButton(
                                onClick = { onRevertFile(item.path) },
                                enabled = !sessionRunning,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.files_revert_file),
                                    tint = tokens.dangerText,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }

                        DiffCodeView(
                            lines = item.diffLines,
                            path = item.path,
                            previewLines = 200,
                            onRevertHunk = if (!sessionRunning) ({ idx -> onRevertHunk(item.path, idx) }) else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NewWorkspaceFileDialog(
    baseDir: String,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var path by rememberSaveable(baseDir) { mutableStateOf(if (baseDir.isEmpty()) "" else "$baseDir/") }
    ForgeAlertDialog(
        modifier = Modifier.forgePopIn(),
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.files_create_new_file), fontWeight = FontWeight.SemiBold) },
        text = {
            ForgeTextField(
                kind = ForgeFieldKind.IDENTIFIER, imeAction = ImeAction.Done,
                value = path,
                onValueChange = { path = it },
                label = { Text(stringResource(R.string.files_relative_file_path)) },
                placeholder = { Text("src/main.py or index.html") },
                textStyle = ForgeCodeTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { if (path.isNotBlank()) onCreate(path.trim()) },
                enabled = path.isNotBlank(),
                shape = RoundedCornerShape(100),
            ) { Text(stringResource(R.string.files_create), fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            ForgeTextButton(onClick = onDismiss) { Text(stringResource(R.string.agent_cancel)) }
        },
    )
}
