@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.BuildConfig
import com.nextcraft.forge.R
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.providersForAgent
import com.nextcraft.forge.runtime.AntigravityAuthStatus
import com.nextcraft.forge.runtime.RuntimeSetupStatus
import kotlinx.coroutines.launch

/** GitHub sign-in, repository sync, create and clone; opened from the Projects screen instead of Settings. */
@Composable
fun GitHubSection(state: ForgeUiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    var githubTokenInput by remember { mutableStateOf("") }
    // Coming back from the GitHub tab in the browser: poll at once so the connection lands without waiting.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.resumeGitHubDeviceSignIn()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var newRepoName by rememberSaveable { mutableStateOf("") }
    var newRepoDesc by rememberSaveable { mutableStateOf("") }
    var newRepoPrivate by rememberSaveable { mutableStateOf(true) }
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        // The dialog is the card: no second bordered card inside it.
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.settings_repository_sync), fontSize = ForgeType.cardTitle, fontWeight = FontWeight.SemiBold)
                state.githubStatusMessage?.asString()?.let { status ->
                    Text(text = status, fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
                }
                state.githubDeviceCode?.let { dc ->
                    Surface(shape = RoundedCornerShape(14.dp), color = tokens.cardElevatedColor, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.settings_one_time_device_code, dc.userCode),
                                fontSize = ForgeType.cardTitle,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            )
                            ForgePrimaryButton(
                                text = stringResource(R.string.settings_copy_code_open_github),
                                onClick = {
                                    clipboard.setText(AnnotatedString(dc.userCode))
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dc.verificationUri))) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("GitHubSection", it) }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ForgeTextButton(onClick = { clipboard.setText(AnnotatedString(dc.userCode)) }) {
                                Text(stringResource(R.string.settings_copy_code))
                            }
                        }
                    }
                }
                if (state.githubLogin.isBlank()) {
                    ForgePrimaryButton(
                        text = stringResource(R.string.settings_sign_in_with_github),
                        onClick = { viewModel.startGitHubDeviceSignIn() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    fun connectToken() {
                        val token = githubTokenInput.trim()
                        if (token.isNotEmpty()) {
                            viewModel.connectGitHubWithToken(token)
                            githubTokenInput = ""
                        }
                    }
                    ForgeTextField(
                        kind = ForgeFieldKind.SECRET, imeAction = ImeAction.Done,
                        onImeAction = { connectToken() },
                        value = githubTokenInput,
                        onValueChange = { value ->
                            githubTokenInput = value
                            // A pasted token that already has GitHub's shape connects by itself; no second tap needed.
                            if (looksLikeGitHubToken(value)) connectToken()
                        },
                        label = { Text(stringResource(R.string.settings_or_paste_github_personal)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        shape = RoundedCornerShape(100),
                        onClick = { connectToken() },
                        enabled = githubTokenInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    ) { Text(stringResource(R.string.settings_connect)) }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ForgeStatusIcon(ForgeStatus.DONE, iconSize = 18.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.settings_connected_as, state.githubLogin),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        ForgeTextButton(onClick = viewModel::disconnectGitHub) { Text(stringResource(R.string.settings_disconnect)) }
                    }
                    ForgeToggleRow(
                        title = stringResource(R.string.settings_auto_commit_push),
                        subtitle = stringResource(R.string.settings_commit_and_push_the),
                        checked = state.autoSyncGitHub,
                        onCheckedChange = viewModel::setAutoSyncGitHub,
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ForgePrimaryButton(
                            text = stringResource(R.string.settings_push_workspace),
                            onClick = { viewModel.syncProjectWithGitHub(reportStatus = true) },
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            shape = RoundedCornerShape(100),
                            onClick = { viewModel.pullProjectFromGitHub() },
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        ) { Text(stringResource(R.string.settings_pull_latest)) }
                    }

                    var createOpen by rememberSaveable { mutableStateOf(false) }
                    ThinDivider()
                    SettingsRow(
                        icon = ForgeGlyph.PLUS,
                        title = stringResource(R.string.settings_create_push_new_github),
                        onClick = { createOpen = !createOpen },
                        trailing = { Chevron(createOpen) },
                        horizontalPadding = 0.dp,
                    )
                    AnimatedVisibility(visible = createOpen) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ForgeTextField(
                                kind = ForgeFieldKind.IDENTIFIER, imeAction = ImeAction.Next,
                                value = newRepoName,
                                onValueChange = { newRepoName = it },
                                label = { Text(stringResource(R.string.settings_repository_name)) },
                                placeholder = { Text(state.activeProject?.slug ?: "my-forge-repo") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ForgeTextField(
                                kind = ForgeFieldKind.TEXT, imeAction = ImeAction.Done,
                                value = newRepoDesc,
                                onValueChange = { newRepoDesc = it },
                                label = { Text(stringResource(R.string.settings_description_optional)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ForgeToggleRow(
                                title = stringResource(if (newRepoPrivate) R.string.settings_private_repository else R.string.settings_public_repository),
                                subtitle = stringResource(R.string.settings_visibility_of_the_new),
                                checked = newRepoPrivate,
                                onCheckedChange = { newRepoPrivate = it },
                            )
                            ForgePrimaryButton(
                                text = stringResource(R.string.settings_create_push),
                                onClick = {
                                    val targetName = newRepoName.ifBlank { state.activeProject?.slug.orEmpty() }
                                    if (targetName.isNotBlank()) {
                                        viewModel.createGitHubRepository(name = targetName, description = newRepoDesc, isPrivate = newRepoPrivate, pushActiveProject = true)
                                        newRepoName = ""
                                        newRepoDesc = ""
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    if (state.githubRepos.isNotEmpty()) {
                        ThinDivider()
                        Text(
                            text = stringResource(R.string.settings_your_repositories_tap_clone),
                            fontSize = ForgeType.secondary,
                            fontWeight = FontWeight.Medium,
                            color = scheme.onSurfaceVariant,
                        )
                        state.githubRepos.take(10).forEach { repo ->
                            Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(repo.fullName, fontFamily = FontFamily.Monospace, fontSize = ForgeType.control, fontWeight = FontWeight.SemiBold)
                                    if (repo.description.isNotBlank()) {
                                        Text(repo.description, fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                ForgeTextButton(onClick = { viewModel.cloneGitHubRepository(repo) }) { Text(stringResource(R.string.settings_clone)) }
                            }
                        }
                    }
                }
            }
        }

    }
}

/** GitHub token prefixes (classic, fine-grained, OAuth, app) with enough length to be a complete paste. */
internal fun looksLikeGitHubToken(value: String): Boolean {
    val v = value.trim()
    return (v.startsWith("github_pat_") && v.length >= 60) ||
        (listOf("ghp_", "gho_", "ghu_", "ghs_", "ghr_").any(v::startsWith) && v.length >= 40)
}

/** Figma Personal Access Token (`figd_...`) configuration and Figma design URL import dialog. */
@Composable
fun FigmaSection(state: ForgeUiState, viewModel: MainViewModel, onClose: () -> Unit = {}) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var figmaTokenInput by remember { mutableStateOf("") }
    var figmaUrlInput by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.projects_figma_description),
            fontSize = ForgeType.secondary,
            color = scheme.onSurfaceVariant,
        )
        if (!state.figmaConnected) {
            ForgeTextButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.figma.com/developers/api#access-tokens")))
                    }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("FigmaSection", it) }
                },
            ) {
                Text(stringResource(R.string.projects_figma_open_settings))
            }
            fun connectFigmaToken() {
                val token = figmaTokenInput.trim()
                if (token.isNotEmpty()) {
                    viewModel.connectFigma(token)
                    figmaTokenInput = ""
                }
            }
            ForgeTextField(
                kind = ForgeFieldKind.SECRET,
                imeAction = ImeAction.Done,
                onImeAction = { connectFigmaToken() },
                value = figmaTokenInput,
                onValueChange = { value ->
                    figmaTokenInput = value
                    if (value.trim().startsWith("figd_") && value.trim().length >= 36) {
                        connectFigmaToken()
                    }
                },
                label = { Text(stringResource(R.string.projects_figma_token_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            ForgePrimaryButton(
                text = stringResource(R.string.settings_connect),
                onClick = { connectFigmaToken() },
                enabled = figmaTokenInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ForgeStatusIcon(ForgeStatus.DONE, iconSize = 18.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.projects_figma_connected),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                ForgeTextButton(onClick = viewModel::disconnectFigma) {
                    Text(stringResource(R.string.settings_disconnect))
                }
            }
        }

        ThinDivider()
        ForgeTextField(
            kind = ForgeFieldKind.URL,
            imeAction = ImeAction.Done,
            onImeAction = {
                if (figmaUrlInput.isNotBlank()) {
                    viewModel.importFigmaDesignUrl(figmaUrlInput)
                    figmaUrlInput = ""
                    onClose()
                }
            },
            value = figmaUrlInput,
            onValueChange = { figmaUrlInput = it },
            label = { Text(stringResource(R.string.projects_figma_url_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            shape = RoundedCornerShape(100),
            onClick = {
                viewModel.importFigmaDesignUrl(figmaUrlInput)
                figmaUrlInput = ""
                onClose()
            },
            enabled = figmaUrlInput.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        ) {
            Text(stringResource(R.string.projects_figma_import_url))
        }
    }
}
