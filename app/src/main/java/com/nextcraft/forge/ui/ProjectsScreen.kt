@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextcraft.forge.model.Project
import com.nextcraft.forge.model.ProjectKind
import com.nextcraft.forge.model.ProjectType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.nextcraft.forge.R
import androidx.annotation.StringRes
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically

/**
 * Linear-style projects home: a dense list of rows (no cards), hairline dividers,
 * a single accent for the primary action and the active project, and one header action.
 */
@Composable
fun ProjectsScreen(
    state: ForgeUiState,
    onOpenProject: (String) -> Unit,
    onNewProject: () -> Unit,
    onQuickProject: () -> Unit,
    onImportZip: (Uri) -> Unit,
    onCloneGitUrl: (String) -> Unit,
    onConnectGitHub: () -> Unit,
    onDeleteProject: (String) -> Unit,
    onRenameProject: (String, String) -> Unit = { _, _ -> },
    onNewQuickProject: () -> Unit = {},
    onOpenUpdate: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val regular = state.projects
        .filter { it.kind == ProjectKind.PROJECT }
        .sortedByDescending { it.updatedAtMillis }
    val quick = state.projects
        .filter { it.kind == ProjectKind.QUICK_PROJECT }
        .sortedByDescending { it.updatedAtMillis }
    var pendingDeleteProject by remember { mutableStateOf<Project?>(null) }
    var pendingRenameProject by remember { mutableStateOf<Project?>(null) }
    var showCloneDialog by rememberSaveable { mutableStateOf(false) }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImportZip)
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        state.updateInfo?.let { update ->
            item(key = "update-banner") {
                UpdateBanner(versionName = update.versionName, onOpen = onOpenUpdate)
            }
        }
        item(key = "hero") {
            ProjectsHero(
                onQuickProject = onQuickProject,
                onNewProject = onNewProject,
            )
        }
        item(key = "import") {
            BringExistingProjectCard(
                githubLogin = state.githubLogin,
                // Any type: file managers often label .zip as application/octet-stream, which would grey it out.
                onPickZip = { zipPicker.launch(arrayOf("*/*")) },
                onGitUrl = { showCloneDialog = true },
                onConnectGitHub = onConnectGitHub,
            )
        }
        item(key = "header-yours") {
            Text(
                text = stringResource(R.string.projects_your_projects),
                fontSize = ForgeType.cardTitle,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 6.dp),
            )
        }
        if (state.projects.isEmpty()) {
            item(key = "empty") { ProjectsEmptyState() }
        } else {
            if (regular.isNotEmpty()) {
                if (quick.isNotEmpty()) item(key = "header-recent") { SectionLabel(stringResource(R.string.projects_recent)) }
                items(regular, key = { it.id }) { project ->
                    val projectBusy = (state.runningProjectChatCounts[project.id] ?: 0) > 0 || project.id in state.buildingProjectIds
                    ProjectRow(
                        project = project,
                        active = project.id == state.activeProjectId,
                        changedCount = if (project.id == state.activeProjectId) state.changedItems.count { it.accepted == null } else 0,
                        running = projectBusy,
                        locked = false,
                        canDelete = !projectBusy,
                        onOpen = { onOpenProject(project.id) },
                        onDelete = { pendingDeleteProject = project },
                        onRename = { pendingRenameProject = project },
                    )
                }
            }
            if (quick.isNotEmpty()) {
                item(key = "header-quick") { SectionLabel(stringResource(R.string.projects_quick)) }
                items(quick, key = { it.id }) { project ->
                    val projectBusy = (state.runningProjectChatCounts[project.id] ?: 0) > 0 || project.id in state.buildingProjectIds
                    ProjectRow(
                        project = project,
                        active = project.id == state.activeProjectId,
                        changedCount = if (project.id == state.activeProjectId) state.changedItems.count { it.accepted == null } else 0,
                        running = projectBusy,
                        locked = false,
                        canDelete = !projectBusy,
                        onOpen = { onOpenProject(project.id) },
                        onDelete = { pendingDeleteProject = project },
                        onRename = { pendingRenameProject = project },
                    )
                }
            }
        }
        item(key = "bottom-space") { Spacer(modifier = Modifier.height(24.dp)) }
    }

    if (showCloneDialog) {
        CloneUrlDialog(
            onDismiss = { showCloneDialog = false },
            onClone = { url ->
                showCloneDialog = false
                onCloneGitUrl(url)
            },
        )
    }
    pendingRenameProject?.let { target ->
        var name by remember(target.id) { mutableStateOf(target.name) }
        ForgeAlertDialog(
            onDismissRequest = { pendingRenameProject = null },
            title = { Text(stringResource(R.string.projects_rename_title)) },
            text = {
                ForgeTextField(
                    kind = ForgeFieldKind.NAME, imeAction = ImeAction.Done, error = if (name.isBlank()) stringResource(R.string.field_error_required) else null,
                    value = name,
                    onValueChange = { name = it.take(MAX_PROJECT_NAME) },
                    label = { Text(stringResource(R.string.projects_rename_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                ForgePrimaryButton(
                    text = stringResource(R.string.projects_rename_save),
                    enabled = name.isNotBlank(),
                    onClick = {
                        onRenameProject(target.id, name)
                        pendingRenameProject = null
                    },
                )
            },
            dismissButton = {
                ForgeTextButton(onClick = { pendingRenameProject = null }) { Text(stringResource(R.string.projects_cancel)) }
            },
        )
    }
    pendingDeleteProject?.let { target ->
        ForgeAlertDialog(
            modifier = Modifier.forgePopIn(),
            onDismissRequest = { pendingDeleteProject = null },
            title = { Text(stringResource(R.string.shell_delete_project_confirm_title)) },
            text = { Text(stringResource(R.string.shell_delete_project_confirm_message, target.name, target.slug)) },
            confirmButton = {
                ForgePrimaryButton(
                    text = stringResource(R.string.shell_confirm_delete),
                    onClick = {
                        onDeleteProject(target.id)
                        pendingDeleteProject = null
                    },
                )
            },
            dismissButton = {
                ForgeTextButton(onClick = { pendingDeleteProject = null }) { Text(stringResource(R.string.projects_cancel)) }
            },
        )
    }
}

/** Title, tagline and the two entry actions (filled Quick project, outlined New project). */
@Composable
private fun ProjectsHero(onQuickProject: () -> Unit, onNewProject: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 10.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_logo),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.shell_nextcraft_forge),
                fontSize = ForgeType.control,
                color = scheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.app_tagline),
            fontSize = ForgeType.screenTitle,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 32.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = stringResource(R.string.projects_tagline),
            fontSize = ForgeType.control,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ForgePrimaryButton(
                text = stringResource(R.string.projects_quick_project),
                onClick = onQuickProject,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = onNewProject,
                shape = RoundedCornerShape(100),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.projects_new_project), fontWeight = FontWeight.SemiBold)
            }
        }
        Text(
            text = stringResource(R.string.projects_buttons_hint),
            fontSize = ForgeType.caption,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Collapsible import card: header toggles; body offers ZIP, Git URL and GitHub. */
@Composable
private fun BringExistingProjectCard(
    githubLogin: String,
    onPickZip: () -> Unit,
    onGitUrl: () -> Unit,
    onConnectGitHub: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    val toggleLabel = stringResource(if (expanded) R.string.projects_collapse else R.string.projects_expand)

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = tokens.cardContainerColor,
        contentColor = scheme.onSurface,
        border = tokens.cardBorderStroke,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 14.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = toggleLabel) { expanded = !expanded }
                    .padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.projects_bring_existing),
                        fontSize = ForgeType.rowTitle,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(
                            if (expanded) R.string.projects_bring_existing_expanded else R.string.projects_bring_existing_collapsed,
                        ),
                        fontSize = ForgeType.control,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            // Height-only animation keeps the LazyColumn item from jumping while the body appears.
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(180)),
                exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(120)),
            ) {
                Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ImportTile(
                            icon = Icons.Default.KeyboardArrowDown,
                            label = stringResource(R.string.projects_zip_file),
                            onClick = onPickZip,
                            modifier = Modifier.weight(1f),
                        )
                        ImportTile(
                            icon = Icons.Default.Edit,
                            label = stringResource(R.string.projects_git_url),
                            onClick = onGitUrl,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(tokens.cardElevatedColor)
                            .clickable(role = Role.Button, onClick = onConnectGitHub)
                            .heightIn(min = 60.dp)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.projects_connect_github),
                                fontSize = ForgeType.body,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = if (githubLogin.isBlank()) {
                                    stringResource(R.string.projects_browse_remote)
                                } else {
                                    stringResource(R.string.projects_github_connected, githubLogin)
                                },
                                fontSize = ForgeType.secondary,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowRight,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }

}

@Composable
private fun ImportTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalForgeThemeTokens.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(tokens.cardElevatedColor)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, fontSize = ForgeType.body, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CloneUrlDialog(onDismiss: () -> Unit, onClone: (String) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    ForgeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.projects_clone_title)) },
        text = {
            ForgeTextField(
                kind = ForgeFieldKind.URL, imeAction = ImeAction.Done, error = url.takeIf { it.isNotBlank() && !FieldValidation.isHttpUrl(it) }?.let { stringResource(R.string.field_error_url) },
                value = url,
                onValueChange = { url = it },
                placeholder = { Text(stringResource(R.string.projects_clone_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { onClone(url) }, enabled = FieldValidation.isHttpUrl(url)) {
                Text(stringResource(R.string.projects_clone))
            }
        },
        dismissButton = {
            ForgeTextButton(onClick = onDismiss) { Text(stringResource(R.string.projects_cancel)) }
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = ForgeType.caption,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp).forgeHeading(),
    )
}

private const val MAX_PROJECT_NAME = 80

/** Project card: folder tile, name, description, guest path, language and age, plus a rename/delete menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProjectRow(
    project: Project,
    active: Boolean,
    locked: Boolean,
    canDelete: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    changedCount: Int = 0,
    running: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurfaceVariant
    // Phones up to ~6.3in report <= 400dp wide: a smaller tile and gaps give the text column 16dp more.
    val narrow = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 400

    ForgeThemedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .alpha(if (locked) 0.5f else 1f),
        border = if (active) androidx.compose.foundation.BorderStroke(1.dp, scheme.primary.copy(alpha = 0.6f)) else LocalForgeThemeTokens.current.cardBorderStroke,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(enabled = !locked, role = Role.Button, onClick = onOpen, onLongClick = { menuOpen = true })
                .padding(start = if (narrow) 12.dp else 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(if (narrow) 44.dp else 52.dp).clip(RoundedCornerShape(14.dp)).background(scheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { ForgeIcon(glyph = ForgeGlyph.FOLDER, tint = scheme.primary, size = if (narrow) 22.dp else 26.dp) }
            Spacer(modifier = Modifier.width(if (narrow) 12.dp else 16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.name,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.2).sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // The stock descriptions ("General coding workspace", "Quick project") say nothing the card does not already
                // show (the list is grouped by kind), so only a locked card or a description someone wrote takes a line.
                val generic = project.description in GENERIC_PROJECT_DESCRIPTIONS
                if (locked || !generic) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (locked) stringResource(R.string.projects_agent_running) else project.localizedDescription().ifBlank { project.subtitle() },
                        fontSize = ForgeType.body,
                        color = muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val guestPath = "/workspace/${project.slug}"
                Text(
                    text = guestPath,
                    fontSize = ForgeType.secondary,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOf(compactLanguages(project.localizedLanguage()), project.relativeUpdatedAt()).filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = ForgeType.secondary,
                    color = muted,
                    // One line: the language list is shortened ("Kotlin +2") so the time always fits, even at 360dp.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Live state for the open project: the agent is working, or edits wait for review.
                if (running || changedCount > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (running) {
                            ProjectStatusPill(stringResource(R.string.projects_status_working), ForgeGlyph.SPARK, LocalForgeThemeTokens.current.warningText)
                        }
                        if (changedCount > 0) {
                            ProjectStatusPill(quantityStringResource(R.plurals.chat_files_changed, changedCount, changedCount), ForgeGlyph.FILE, scheme.primary)
                        }
                    }
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = !locked) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.projects_more_actions), tint = muted)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.projects_rename)) },
                        leadingIcon = { ForgeIcon(ForgeGlyph.EDIT, muted) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.shell_delete_project)) },
                        leadingIcon = { ForgeIcon(ForgeGlyph.TRASH, LocalForgeThemeTokens.current.dangerText) },
                        enabled = canDelete,
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/** Folder drawn from two rounded rectangles; the icon set in use has no folder glyph. */
@Composable
private fun FolderGlyph(tint: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(28.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = tint,
            topLeft = androidx.compose.ui.geometry.Offset(0f, h * 0.12f),
            size = androidx.compose.ui.geometry.Size(w * 0.45f, h * 0.3f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f),
        )
        drawRoundRect(
            color = tint,
            topLeft = androidx.compose.ui.geometry.Offset(0f, h * 0.26f),
            size = androidx.compose.ui.geometry.Size(w, h * 0.62f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.12f),
        )
    }
}

/** Filled accent dot = active project, hollow ring = the rest. Shape (not only colour) carries the state. */
@Composable
private fun StatusDot(active: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.onSurfaceVariant
    val description = stringResource(if (active) R.string.projects_active_project else R.string.projects_project)
    Box(
        modifier = Modifier
            .size(16.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(accent))
        } else {
            Box(modifier = Modifier.size(10.dp).border(1.5.dp, ring, CircleShape))
        }
    }
}

@Composable
private fun ProjectsEmptyState() {
    ForgeThemedCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        ForgeEmptyState(
            glyph = ForgeGlyph.FOLDER,
            title = stringResource(R.string.projects_no_projects_yet),
            message = stringResource(R.string.projects_empty_hint),
        )
    }
}

private val GENERIC_PROJECT_DESCRIPTIONS = setOf(
    "Default workspace inside Ubuntu ARM64 Root Chroot",
    "General coding workspace",
    "Quick project",
)

@Composable
private fun Project.subtitle(): String = when {
    kind == ProjectKind.QUICK_PROJECT -> stringResource(R.string.projects_quick_chat)
    type == ProjectType.ANDROID -> stringResource(R.string.projects_android) + (androidTemplate?.let { " · ${it.label}" } ?: "")
    else -> stringResource(R.string.projects_general)
}

/**
 * Project text is stored in English (the defaults a project is created with) and translated when drawn, so it follows
 * the app language even after the language is changed. Text the user typed is shown as written.
 */
@Composable
private fun Project.localizedDescription(): String = when {
    description == "Default workspace inside Ubuntu ARM64 Root Chroot" -> stringResource(R.string.project_default_sandbox_desc)
    description == "General coding workspace" -> stringResource(R.string.project_general_desc)
    description == "Quick project" -> stringResource(R.string.project_quick_desc)
    else -> {
        val imported = Regex("^Imported from (.+)$").matchEntire(description)
        val android = Regex("^Android \\((.+)\\) project$").matchEntire(description)
        when {
            imported != null -> stringResource(R.string.project_imported_desc, imported.groupValues[1])
            android != null -> stringResource(R.string.project_android_desc, android.groupValues[1])
            else -> description
        }
    }
}

@Composable
private fun Project.localizedLanguage(): String = if (language == "Multi-language") stringResource(R.string.project_lang_multi) else language

/** Small status capsule on a project card: glyph plus words, so colour is never the only signal. */
@Composable
private fun ProjectStatusPill(text: String, glyph: ForgeGlyph, tint: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(100))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ForgeIcon(glyph, tint = tint, size = 12.dp)
        Spacer(modifier = Modifier.width(5.dp))
        Text(text, fontSize = ForgeType.caption, color = tint, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** "Kotlin / TypeScript / Python" becomes "Kotlin +2": up to two languages are listed in full, more collapse to the first plus a count. */
internal fun compactLanguages(languages: String): String {
    val parts = languages.split(" / ").map { it.trim() }.filter { it.isNotEmpty() }
    return if (parts.size <= 2) languages else parts.first() + " +" + (parts.size - 1)
}

/** Shown on top of the project list while a newer build is available; opens Settings, where it is installed. */
@Composable
private fun UpdateBanner(versionName: String, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.primary.copy(alpha = 0.14f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onOpen),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.upd_banner_title, versionName), fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.upd_banner_hint), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
        }
    }
}
