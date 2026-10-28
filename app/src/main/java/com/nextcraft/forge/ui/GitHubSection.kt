@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.core.net.toUri
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nextcraft.forge.R
import com.nextcraft.forge.runtime.ErrorLog

private val CLASSIC_GH_TOKEN_PREFIXES = listOf("ghp_", "gho_", "ghu_", "ghs_", "ghr_")

@Composable
fun GitHubSection(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    val hostContext = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val themeTokens = LocalForgeThemeTokens.current
    val palette = MaterialTheme.colorScheme
    var patDraft by remember { mutableStateOf("") }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.resumeGitHubDeviceSignIn()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var repoTitleInput by rememberSaveable { mutableStateOf("") }
    var repoSummaryInput by rememberSaveable { mutableStateOf("") }
    var privateVisibility by rememberSaveable { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_repository_sync),
                    fontSize = ForgeType.cardTitle,
                    fontWeight = FontWeight.SemiBold,
                )
                state.githubStatusMessage?.asString()?.let { bannerText ->
                    Text(
                        text = bannerText,
                        fontSize = ForgeType.secondary,
                        color = palette.onSurfaceVariant,
                    )
                }
                state.githubDeviceCode?.let { deviceFlow ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = themeTokens.cardElevatedColor,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_one_time_device_code, deviceFlow.userCode),
                                fontSize = ForgeType.cardTitle,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            )
                            ForgePrimaryButton(
                                text = stringResource(R.string.settings_copy_code_open_github),
                                onClick = {
                                    clipboard.setText(AnnotatedString(deviceFlow.userCode))
                                    runCatching {
                                        hostContext.startActivity(
                                            Intent(Intent.ACTION_VIEW, deviceFlow.verificationUri.toUri()),
                                        )
                                    }.onFailure { ErrorLog.swallowed("GitHubSection", it) }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ForgeTextButton(onClick = { clipboard.setText(AnnotatedString(deviceFlow.userCode)) }) {
                                Text(stringResource(R.string.settings_copy_code))
                            }
                        }
                    }
                }

                if (state.githubLogin.isBlank()) {
                    ForgePrimaryButton(
                        text = stringResource(R.string.settings_sign_in_with_github),
                        onClick = viewModel::startGitHubDeviceSignIn,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    fun submitPersonalToken() {
                        val trimmed = patDraft.trim()
                        if (trimmed.isNotEmpty()) {
                            viewModel.connectGitHubWithToken(trimmed)
                            patDraft = ""
                        }
                    }
                    ForgeTextField(
                        kind = ForgeFieldKind.SECRET,
                        imeAction = ImeAction.Done,
                        onImeAction = ::submitPersonalToken,
                        value = patDraft,
                        onValueChange = { nextVal ->
                            patDraft = nextVal
                            if (looksLikeGitHubToken(nextVal)) submitPersonalToken()
                        },
                        label = { Text(stringResource(R.string.settings_or_paste_github_personal)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        shape = RoundedCornerShape(100),
                        onClick = ::submitPersonalToken,
                        enabled = patDraft.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp),
                    ) {
                        Text(stringResource(R.string.settings_connect))
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ForgeStatusIcon(ForgeStatus.DONE, iconSize = 18.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.settings_connected_as, state.githubLogin),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        ForgeTextButton(onClick = viewModel::disconnectGitHub) {
                            Text(stringResource(R.string.settings_disconnect))
                        }
                    }
                    ForgeToggleRow(
                        title = stringResource(R.string.settings_auto_commit_push),
                        subtitle = stringResource(R.string.settings_commit_and_push_the),
                        checked = state.autoSyncGitHub,
                        onCheckedChange = viewModel::setAutoSyncGitHub,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ForgePrimaryButton(
                            text = stringResource(R.string.settings_push_workspace),
                            onClick = { viewModel.syncProjectWithGitHub(reportStatus = true) },
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            shape = RoundedCornerShape(100),
                            onClick = viewModel::pullProjectFromGitHub,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp),
                        ) {
                            Text(stringResource(R.string.settings_pull_latest))
                        }
                    }

                    var expandCreateSection by rememberSaveable { mutableStateOf(false) }
                    ThinDivider()
                    SettingsRow(
                        icon = ForgeGlyph.PLUS,
                        title = stringResource(R.string.settings_create_push_new_github),
                        onClick = { expandCreateSection = !expandCreateSection },
                        trailing = { Chevron(expandCreateSection) },
                        horizontalPadding = 0.dp,
                    )
                    AnimatedVisibility(visible = expandCreateSection) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ForgeTextField(
                                kind = ForgeFieldKind.IDENTIFIER,
                                imeAction = ImeAction.Next,
                                value = repoTitleInput,
                                onValueChange = { repoTitleInput = it },
                                label = { Text(stringResource(R.string.settings_repository_name)) },
                                placeholder = { Text(state.activeProject?.slug ?: "my-forge-repo") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ForgeTextField(
                                kind = ForgeFieldKind.TEXT,
                                imeAction = ImeAction.Done,
                                value = repoSummaryInput,
                                onValueChange = { repoSummaryInput = it },
                                label = { Text(stringResource(R.string.settings_description_optional)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ForgeToggleRow(
                                title = stringResource(
                                    if (privateVisibility) R.string.settings_private_repository else R.string.settings_public_repository,
                                ),
                                subtitle = stringResource(R.string.settings_visibility_of_the_new),
                                checked = privateVisibility,
                                onCheckedChange = { privateVisibility = it },
                            )
                            ForgePrimaryButton(
                                text = stringResource(R.string.settings_create_push),
                                onClick = {
                                    val resolvedRepoName = repoTitleInput.ifBlank { state.activeProject?.slug.orEmpty() }
                                    if (resolvedRepoName.isNotBlank()) {
                                        viewModel.createGitHubRepository(
                                            name = resolvedRepoName,
                                            description = repoSummaryInput,
                                            isPrivate = privateVisibility,
                                            pushActiveProject = true,
                                        )
                                        repoTitleInput = ""
                                        repoSummaryInput = ""
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
                            color = palette.onSurfaceVariant,
                        )
                        for (remoteRepo in state.githubRepos.take(10)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = remoteRepo.fullName,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = ForgeType.control,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    if (remoteRepo.description.isNotBlank()) {
                                        Text(
                                            text = remoteRepo.description,
                                            fontSize = ForgeType.secondary,
                                            color = palette.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                ForgeTextButton(onClick = { viewModel.cloneGitHubRepository(remoteRepo) }) {
                                    Text(stringResource(R.string.settings_clone))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun looksLikeGitHubToken(value: String): Boolean {
    val clean = value.trim()
    return (clean.startsWith("github_pat_") && clean.length >= 60) ||
        (CLASSIC_GH_TOKEN_PREFIXES.any(clean::startsWith) && clean.length >= 40)
}

@Composable
fun FigmaSection(
    state: ForgeUiState,
    viewModel: MainViewModel,
    onClose: () -> Unit = {},
) {
    val hostContext = LocalContext.current
    val palette = MaterialTheme.colorScheme
    var tokenDraft by remember { mutableStateOf("") }
    var designUrlDraft by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.projects_figma_description),
            fontSize = ForgeType.secondary,
            color = palette.onSurfaceVariant,
        )
        if (!state.figmaConnected) {
            ForgeTextButton(
                onClick = {
                    runCatching {
                        hostContext.startActivity(
                            Intent(Intent.ACTION_VIEW, "https://www.figma.com/developers/api#access-tokens".toUri()),
                        )
                    }.onFailure { ErrorLog.swallowed("FigmaSection", it) }
                },
            ) {
                Text(stringResource(R.string.projects_figma_open_settings))
            }
            fun submitFigmaToken() {
                val trimmed = tokenDraft.trim()
                if (trimmed.isNotEmpty()) {
                    viewModel.connectFigma(trimmed)
                    tokenDraft = ""
                }
            }
            ForgeTextField(
                kind = ForgeFieldKind.SECRET,
                imeAction = ImeAction.Done,
                onImeAction = ::submitFigmaToken,
                value = tokenDraft,
                onValueChange = { nextVal ->
                    tokenDraft = nextVal
                    val clean = nextVal.trim()
                    if (clean.startsWith("figd_") && clean.length >= 36) {
                        submitFigmaToken()
                    }
                },
                label = { Text(stringResource(R.string.projects_figma_token_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            ForgePrimaryButton(
                text = stringResource(R.string.settings_connect),
                onClick = ::submitFigmaToken,
                enabled = tokenDraft.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
        fun triggerFigmaImport() {
            if (designUrlDraft.isNotBlank()) {
                viewModel.importFigmaDesignUrl(designUrlDraft)
                designUrlDraft = ""
                onClose()
            }
        }
        ForgeTextField(
            kind = ForgeFieldKind.URL,
            imeAction = ImeAction.Done,
            onImeAction = ::triggerFigmaImport,
            value = designUrlDraft,
            onValueChange = { designUrlDraft = it },
            label = { Text(stringResource(R.string.projects_figma_url_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            shape = RoundedCornerShape(100),
            onClick = ::triggerFigmaImport,
            enabled = designUrlDraft.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp),
        ) {
            Text(stringResource(R.string.projects_figma_import_url))
        }
    }
}
