@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.AndroidTemplate
import com.nextcraft.forge.model.StarterTemplate
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.runtime.RuntimeSetupStatus
import androidx.compose.ui.res.stringResource
import com.nextcraft.forge.R
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding

@Composable
internal fun NewProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String, isAndroid: Boolean, template: AndroidTemplate, starter: StarterTemplate) -> Unit,
    gameTemplatesEnabled: Boolean = false,
) {
    // The game starter only shows once it is switched on in Settings.
    val starters = StarterTemplate.entries.filter { gameTemplatesEnabled || it != StarterTemplate.GAME_LIBGDX }
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var isAndroid by rememberSaveable { mutableStateOf(false) }
    var template by rememberSaveable { mutableStateOf(AndroidTemplate.COMPOSE) }
    var starter by rememberSaveable { mutableStateOf(StarterTemplate.EMPTY) }
    val resolvedStarter = if (starter in starters) starter else StarterTemplate.EMPTY

    ForgeAlertDialog(
        modifier = Modifier.forgePopIn(),
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.np_title), fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ForgeTextField(
                    kind = ForgeFieldKind.NAME, imeAction = ImeAction.Next, error = if (name.isBlank()) stringResource(R.string.field_error_required) else null,
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.shell_project_name)) },
                    placeholder = { Text(stringResource(R.string.shell_my_awesome_app)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                ForgeTextField(
                    kind = ForgeFieldKind.TEXT, imeAction = ImeAction.Done,
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.shell_description)) },
                    placeholder = { Text(stringResource(R.string.shell_optional_project_summary)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                ForgeToggleRow(
                    title = stringResource(R.string.np_android_template),
                    subtitle = stringResource(R.string.np_android_template_sub),
                    checked = isAndroid,
                    onCheckedChange = { isAndroid = it },
                )
                if (isAndroid) {
                    ForgePillTabs(
                        items = AndroidTemplate.entries.map { it.label },
                        selectedIndex = AndroidTemplate.entries.indexOf(template),
                        onSelect = { template = AndroidTemplate.entries[it] },
                        modifier = Modifier.padding(horizontal = 0.dp),
                    )
                } else {
                    // Non-Android starter: an empty workspace, or a Go app that builds for Windows and Linux.
                    ForgePillTabs(
                        items = starters.map { it.label },
                        selectedIndex = starters.indexOf(resolvedStarter).coerceAtLeast(0),
                        onSelect = { starter = starters[it] },
                        modifier = Modifier.padding(horizontal = 0.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, description, isAndroid, template, resolvedStarter) },
                enabled = name.isNotBlank(),
                shape = RoundedCornerShape(100),
            ) { Text(stringResource(R.string.files_create), fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            ForgeTextButton(onClick = onDismiss) { Text(stringResource(R.string.agent_cancel)) }
        },
    )
}
