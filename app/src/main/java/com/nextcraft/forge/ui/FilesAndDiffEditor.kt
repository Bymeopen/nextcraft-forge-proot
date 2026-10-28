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
internal fun WorkspaceFileEditor(
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
            contentAlignment = Alignment.Center,
        ) {
            when {
                isImageFilePath(selectedPath) -> {
                    ChatImage(
                        alt = selectedPath.substringAfterLast('/'),
                        src = selectedPath,
                        maxHeight = 960.dp,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                    )
                }
                isVideoFilePath(selectedPath) -> {
                    ChatVideo(
                        src = selectedPath,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                else -> {
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
    }
}

@Composable
internal fun WorkspaceDiffReviewList(
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
    val scheme = MaterialTheme.colorScheme
    if (changes.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            ForgeEmptyState(
                glyph = ForgeGlyph.CODE,
                title = stringResource(R.string.files_no_unreviewed_checkpoint_changes),
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.files_modified_file_s_since, changes.size),
                    fontSize = ForgeType.rowTitle,
                    color = scheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                ConfirmedAction(
                    title = stringResource(R.string.files_undo_all),
                    message = stringResource(R.string.files_modified_file_s_since, changes.size),
                    confirmLabel = stringResource(R.string.files_undo_all),
                    onConfirm = onRevertAll,
                ) { onClick ->
                    OutlinedButton(
                        onClick = onClick,
                        enabled = !sessionRunning,
                        shape = RoundedCornerShape(100),
                        modifier = Modifier.heightIn(min = 40.dp),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.files_undo_all), fontWeight = FontWeight.SemiBold)
                    }
                }
                ForgePrimaryButton(
                    text = stringResource(R.string.files_keep_all),
                    onClick = onKeepAll,
                    enabled = !sessionRunning,
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(changes, key = { it.path }) { item ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = tokens.cardContainerColor,
                    contentColor = scheme.onSurface,
                    border = tokens.cardBorderStroke,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
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
                                modifier = Modifier.size(44.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.files_keep_file),
                                    tint = tokens.successText,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            ConfirmedAction(
                                title = stringResource(R.string.files_revert_file),
                                message = item.path,
                                confirmLabel = stringResource(R.string.files_revert_file),
                                onConfirm = { onRevertFile(item.path) },
                            ) { onClick ->
                                IconButton(
                                    onClick = onClick,
                                    enabled = !sessionRunning,
                                    modifier = Modifier.size(44.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.files_revert_file),
                                        tint = tokens.dangerText,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }

                        when {
                            isImageFilePath(item.path) -> {
                                ChatImage(
                                    alt = item.path.substringAfterLast('/'),
                                    src = item.path,
                                    maxHeight = 320.dp,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            isVideoFilePath(item.path) -> {
                                ChatVideo(
                                    src = item.path,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            else -> {
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
    }
}

@Composable
internal fun NewWorkspaceFileDialog(
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
