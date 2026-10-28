@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.core.net.toUri
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.BuildConfig
import com.nextcraft.forge.R
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.providersForAgent
import com.nextcraft.forge.runtime.AntigravityAuthStatus
import com.nextcraft.forge.runtime.RuntimeEngineChoice
import com.nextcraft.forge.runtime.RuntimeSetupStatus
import kotlinx.coroutines.launch


/**
 * Settings, grouped the way the reference layout does: General (appearance, language, safety),
 * AI agent (agent tabs, access mode, endpoint, model & keys), GitHub, Developer tools, Linux
 * runtime, Danger zone and About. Colours and type stay Linear; only the layout follows the reference.
 */
@Composable
fun SettingsScreenModern(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme


    val isProvisioning = state.setupSnapshot.status == RuntimeSetupStatus.RUNNING
    val runtimeBusy = !state.runtimeInstalled || isProvisioning

    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .forgeFadingVertical(scroll, MaterialTheme.colorScheme.background)
            .verticalScroll(scroll)
            .padding(start = 14.dp, end = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ForgeLargeTitle(
            title = stringResource(R.string.settings_settings),
            subtitle = stringResource(R.string.settings_preferences_configuration),
            horizontalPadding = 6.dp,
        )

        // ───────────────────────── General ─────────────────────────
        ForgeSectionLabel(stringResource(R.string.settings_general), horizontalPadding = 6.dp, topPadding = 8.dp)
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            val activeThemeMode = ForgeThemeMode.fromStored(state.themeMode)
            var appearanceOpen by rememberSaveable { mutableStateOf(false) }
            SettingsRow(
                icon = ForgeGlyph.SLIDERS,
                title = stringResource(R.string.settings_appearance),
                subtitle = stringResource(activeThemeMode.title),
                onClick = { appearanceOpen = !appearanceOpen },
                trailing = { Chevron(appearanceOpen) },
            )
            val reduceMotion = rememberReduceMotion()
            AnimatedVisibility(
                visible = appearanceOpen,
                enter = ForgeMotion.expandEnter(reduceMotion),
                exit = ForgeMotion.shrinkExit(reduceMotion),
            ) {
                Column {
                    ThemeModeGrid(active = activeThemeMode, onSelect = viewModel::setThemeMode)
                    if (activeThemeMode == ForgeThemeMode.GLASS) {
                        SettingsRow(
                            icon = ForgeGlyph.SLIDERS,
                            title = stringResource(R.string.settings_glass_solid),
                            subtitle = stringResource(R.string.settings_glass_solid_desc),
                            onClick = { viewModel.setGlassSolid(!state.glassSolid) },
                            trailing = { androidx.compose.material3.Switch(checked = state.glassSolid, onCheckedChange = null) },
                        )
                    }
                }
            }
            ThinDivider()

            val languages = AppLanguage.selectable
            val activeLanguage = languages.firstOrNull { it.code.equals(state.languageCode, ignoreCase = true) } ?: languages.first()
            var languageOpen by rememberSaveable { mutableStateOf(false) }
            SettingsRow(
                icon = ForgeGlyph.GLOBE,
                title = stringResource(R.string.settings_language),
                subtitle = if (activeLanguage == AppLanguage.SYSTEM) stringResource(R.string.settings_language_system) else activeLanguage.nativeName,
                onClick = { languageOpen = !languageOpen },
                trailing = { Chevron(languageOpen) },
            )
            AnimatedVisibility(
                visible = languageOpen,
                enter = ForgeMotion.expandEnter(reduceMotion),
                exit = ForgeMotion.shrinkExit(reduceMotion),
            ) {
                Column(modifier = Modifier.padding(bottom = 6.dp)) {
                    languages.forEach { lang ->
                        RadioRow(
                            title = if (lang == AppLanguage.SYSTEM) stringResource(R.string.settings_language_system) else lang.nativeName,
                            subtitle = lang.displayName,
                            selected = lang == activeLanguage,
                            onClick = { viewModel.setLanguageCode(lang.code) },
                        )
                    }
                }
            }
            ThinDivider()

            // Runtime engine (ADR-007): auto prefers root chroot and falls back to PRoot; the others force one.
            val engineChoices = RuntimeEngineChoice.entries
            val activeEngine = RuntimeEngineChoice.fromStored(state.runtimeEngineChoice)
            var engineOpen by rememberSaveable { mutableStateOf(false) }
            SettingsRow(
                icon = ForgeGlyph.TERMINAL,
                title = stringResource(R.string.settings_runtime_engine),
                subtitle = stringResource(activeEngine.titleRes()),
                onClick = { engineOpen = !engineOpen },
                trailing = { Chevron(engineOpen) },
            )
            AnimatedVisibility(
                visible = engineOpen,
                enter = ForgeMotion.expandEnter(reduceMotion),
                exit = ForgeMotion.shrinkExit(reduceMotion),
            ) {
                Column(modifier = Modifier.padding(bottom = 6.dp)) {
                    engineChoices.forEach { choice ->
                        RadioRow(
                            title = stringResource(choice.titleRes()),
                            subtitle = stringResource(choice.subtitleRes()),
                            selected = choice == activeEngine,
                            onClick = { viewModel.setRuntimeEngineChoice(choice.id) },
                        )
                    }
                }
            }
            ThinDivider()

            ForgeToggleRow(
                title = stringResource(R.string.settings_auto_keep_changes),
                subtitle = stringResource(R.string.settings_auto_keep_changes_subtitle),
                checked = state.autoKeepChanges,
                onCheckedChange = viewModel::setAutoKeepChanges,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            ForgeToggleRow(
                title = stringResource(R.string.settings_android_host_tools),
                subtitle = stringResource(R.string.settings_android_host_tools_subtitle),
                checked = state.androidHostTools,
                onCheckedChange = viewModel::setAndroidHostTools,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            ForgeToggleRow(
                title = stringResource(R.string.settings_game_templates),
                subtitle = stringResource(R.string.settings_game_templates_subtitle),
                checked = state.gameTemplatesEnabled,
                onCheckedChange = viewModel::setGameTemplatesEnabled,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
        }

        // ───────────────────────── Agent ─────────────────────────
        ForgeSectionLabel(stringResource(R.string.settings_section_agent), horizontalPadding = 6.dp, topPadding = 8.dp)
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            ForgeToggleRow(
                title = stringResource(R.string.settings_rea_chat_hint),
                subtitle = stringResource(R.string.settings_rea_chat_hint_subtitle),
                checked = state.reaChatHint,
                onCheckedChange = viewModel::setReaChatHint,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            ForgeToggleRow(
                title = stringResource(R.string.settings_schedule_agent),
                subtitle = stringResource(R.string.settings_schedule_agent_subtitle),
                checked = state.scheduleEnabled,
                onCheckedChange = viewModel::setScheduleEnabled,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
        }

        // ───────────────────────── Tool approval ─────────────────────────
        // One choice instead of two switches that excluded each other: the app already keeps them mutually exclusive.
        ForgeSectionLabel(stringResource(R.string.settings_approval_title), horizontalPadding = 6.dp, topPadding = 8.dp)
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            RadioRow(
                title = stringResource(R.string.settings_approval_ask),
                subtitle = stringResource(R.string.settings_prompt_before_executing_destructive),
                selected = !state.autoApproveAllTools && state.interactiveHighRiskApprovals,
                onClick = { viewModel.setInteractiveHighRiskApprovals(true) },
            )
            RadioRow(
                title = stringResource(R.string.settings_approval_block),
                subtitle = stringResource(R.string.settings_approval_block_subtitle),
                selected = !state.autoApproveAllTools && !state.interactiveHighRiskApprovals,
                onClick = {
                    viewModel.setAutoApproveAllTools(false)
                    viewModel.setInteractiveHighRiskApprovals(false)
                },
            )
            RadioRow(
                title = stringResource(R.string.settings_auto_approve_all_tools),
                subtitle = stringResource(R.string.settings_auto_approve_all_tools_subtitle),
                selected = state.autoApproveAllTools,
                onClick = { viewModel.setAutoApproveAllTools(true) },
            )
            if (state.autoApproveAllTools) {
                // Approving everything means nothing is ever confirmed: say so where the user is looking.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    ForgeIcon(ForgeGlyph.WARNING, tint = tokens.warningText, size = 16.dp, modifier = Modifier.padding(top = 2.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_auto_approve_warning),
                        fontSize = ForgeType.secondary,
                        color = tokens.warningText,
                    )
                }
            }
        }

        // The toolchain manager (dev stacks + agents) lives in Tools > Toolchains.
        ForgeSectionLabel(stringResource(R.string.settings_runtime), horizontalPadding = 6.dp, topPadding = 8.dp)

        // ───────────────────────── Linux runtime ─────────────────────────
        var runtimeOpen by rememberSaveable { mutableStateOf(false) }
        DisclosureCard(
            icon = ForgeGlyph.TERMINAL,
            title = stringResource(R.string.settings_linux_runtime),
            subtitle = stringResource(R.string.onb_subtitle),
            expanded = runtimeOpen,
            onToggle = { runtimeOpen = !runtimeOpen },
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                KeyValueRow(stringResource(R.string.settings_architecture), if (state.arm64Supported) "ARM64 (aarch64)" else "—")
                KeyValueRow(stringResource(R.string.settings_environment), "Ubuntu 24.04 · Root chroot")
                KeyValueRow(stringResource(R.string.settings_active_agent), state.agentKind.displayTitle())
                if (state.installedAgents.isNotEmpty()) {
                    ThinDivider()
                    Text(stringResource(R.string.settings_installed_agents), fontSize = ForgeType.control, fontWeight = FontWeight.SemiBold, color = scheme.onSurfaceVariant)
                    state.installedAgents.forEach { kind ->
                        KeyValueRow(kind.displayTitle(), state.agentUpdates[kind]?.latestVersion?.let { stringResource(R.string.settings_agent_update, it) } ?: stringResource(R.string.settings_agent_installed))
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                    shape = RoundedCornerShape(100),
                    onClick = viewModel::clearTerminal,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                ) {
                    ForgeIcon(glyph = ForgeGlyph.TRASH, tint = MaterialTheme.colorScheme.onSurface, size = 16.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_clear_terminal_history))
                }
                var cachesOpen by remember { mutableStateOf(false) }
                OutlinedButton(
                    shape = RoundedCornerShape(100),
                    onClick = { cachesOpen = true; viewModel.loadRuntimeCacheSizes() },
                    enabled = !state.sessionRunning && state.buildingProjectIds.isEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                ) {
                    ForgeIcon(glyph = ForgeGlyph.TRASH, tint = MaterialTheme.colorScheme.onSurface, size = 16.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_clear_caches))
                }
                if (cachesOpen) {
                    val sizes = state.runtimeCacheSizes
                    ForgeAlertDialog(
                        modifier = Modifier.forgePopIn(),
                        onDismissRequest = { cachesOpen = false },
                        shape = RoundedCornerShape(24.dp),
                        title = { Text(stringResource(R.string.settings_clear_caches), fontWeight = FontWeight.SemiBold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(stringResource(R.string.settings_clear_caches_message))
                                com.nextcraft.forge.runtime.RuntimeCaches.Kind.entries.forEach { kind ->
                                    KeyValueRow(kind.label, sizes?.get(kind.name)?.let(::cacheSizeLabel) ?: "…")
                                }
                                if (sizes != null) {
                                    KeyValueRow(stringResource(R.string.settings_clear_caches_total), cacheSizeLabel(sizes.values.sum()))
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = { cachesOpen = false; viewModel.clearRuntimeCaches() },
                                enabled = sizes != null && sizes.values.sum() > 0 && !state.sessionRunning && state.buildingProjectIds.isEmpty(),
                            ) { Text(stringResource(R.string.settings_clear_caches_confirm), color = tokens.dangerText) }
                        },
                        dismissButton = {
                            TextButton(onClick = { cachesOpen = false }) { Text(stringResource(R.string.agent_cancel)) }
                        },
                    )
                }
            }
        }

        // ───────────────────────── Danger zone ─────────────────────────
        ForgeSectionLabel(stringResource(R.string.settings_danger_zone), horizontalPadding = 6.dp, topPadding = 8.dp)
        ForgeThemedCard(
            modifier = Modifier.fillMaxWidth(),
            border = androidx.compose.foundation.BorderStroke(1.dp, tokens.dangerText.copy(alpha = 0.45f)),
        ) {
            SettingsRow(
                icon = ForgeGlyph.WARNING,
                iconTint = tokens.dangerText,
                title = stringResource(R.string.settings_repair_rootfs),
                subtitle = stringResource(R.string.settings_repair_rootfs_sub),
                onClick = { if (!isProvisioning) viewModel.repairRootfs() },
                trailing = { Text("›", color = scheme.onSurfaceVariant) },
            )
        }

        // ───────────────────────── About ─────────────────────────
        ForgeSectionLabel(stringResource(R.string.settings_about), horizontalPadding = 6.dp, topPadding = 8.dp)
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            SettingsRow(
                icon = ForgeGlyph.INFO,
                title = stringResource(R.string.settings_version),
                trailing = { Text("v${BuildConfig.VERSION_NAME}", color = scheme.onSurfaceVariant, fontSize = ForgeType.control) },
            )
            ThinDivider()
            // What changed in this build: read here instead of a phone notification after each update.
            var whatsNewOpen by rememberSaveable { mutableStateOf(false) }
            val dynamicNotes = remember(state.updateInfo, whatsNewOpen) {
                state.updateInfo?.notes?.takeIf { it.isNotBlank() }
                    ?: runCatching { com.nextcraft.forge.data.AppPreferences(context).latestReleaseNotes.takeIf { it.isNotBlank() } }.getOrNull()
            }
            SettingsRow(
                icon = ForgeGlyph.INFO,
                title = stringResource(R.string.chg_notif_channel),
                onClick = { whatsNewOpen = !whatsNewOpen },
                trailing = { Text(if (whatsNewOpen) "⌄" else "›", color = scheme.onSurfaceVariant) },
            )
            if (whatsNewOpen) {
                Text(
                    dynamicNotes ?: stringResource(R.string.chg_notif_text),
                    fontSize = ForgeType.secondary,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                )
            }
            ThinDivider()
            SettingsRow(
                icon = ForgeGlyph.REFRESH,
                title = stringResource(R.string.settings_check_for_app_updates),
                subtitle = state.updateStatusMessage ?: stringResource(R.string.settings_check_updates_sub),
                onClick = { viewModel.checkForAppUpdates() },
                trailing = { Text("›", color = scheme.onSurfaceVariant) },
            )
            ForgeToggleRow(
                title = stringResource(R.string.settings_update_notify),
                subtitle = stringResource(R.string.settings_update_notify_sub),
                checked = state.updateNotifications,
                onCheckedChange = viewModel::setUpdateNotifications,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            ForgeToggleRow(
                title = stringResource(R.string.settings_auto_install_updates),
                subtitle = stringResource(R.string.settings_auto_install_updates_sub),
                checked = state.autoInstallUpdates,
                onCheckedChange = viewModel::setAutoInstallUpdates,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            state.updateProgress?.let { fraction ->
                LinearProgressIndicator(
                    progress = fraction,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            state.updateInfo?.let { info ->
                Box(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                    ForgePrimaryButton(
                        text = stringResource(R.string.settings_download_install_update, info.versionName),
                        onClick = viewModel::downloadAndInstallAppUpdate,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Google account card for Antigravity, plus its model and effort. */
@Composable
internal fun AntigravityAccountCard(
    state: ForgeUiState,
    viewModel: MainViewModel,
    agyAuth: com.nextcraft.forge.runtime.AntigravityAuthState,
    agyModelInput: String,
    onAgyModelChange: (String) -> Unit,
    agyEffortInput: String,
    onAgyEffortChange: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var manualCode by rememberSaveable { mutableStateOf(false) }
    var agyVerificationCode by rememberSaveable { mutableStateOf("") }
    val hasAgyCredential by produceState(initialValue = false, agyAuth.status) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { viewModel.antigravityAuth.hasOfficialCredential() }
    }
    val signedIn = agyAuth.status == AntigravityAuthStatus.SIGNED_IN || hasAgyCredential
    val inFlight = agyAuth.status == AntigravityAuthStatus.STARTING || agyAuth.status == AntigravityAuthStatus.COMPLETING

    ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_google_account), fontSize = ForgeType.cardTitle, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(50), color = scheme.primary.copy(alpha = 0.16f), modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        ForgeIcon(glyph = ForgeGlyph.SHIELD, tint = scheme.primary, size = 24.dp)
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            when {
                                signedIn -> R.string.settings_google_signed_in
                                inFlight -> R.string.settings_google_waiting
                                else -> R.string.settings_google_not_signed_in
                            },
                        ),
                        fontSize = ForgeType.cardTitle,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = agyAuth.message?.asString() ?: stringResource(R.string.settings_google_required),
                        fontSize = ForgeType.secondary,
                        color = scheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (inFlight || state.apiBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            if (signedIn) {
                OutlinedButton(
                    shape = RoundedCornerShape(100),
                    onClick = { scope.launch { runCatching { viewModel.antigravityAuth.logout() } } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                ) { Text(stringResource(R.string.settings_sign_out_google_oauth)) }
            } else {
                ForgePrimaryButton(
                    text = stringResource(if (inFlight || agyAuth.authorizationUrl != null) R.string.settings_restart_google_sign_in else R.string.settings_sign_in_with_google),
                    onClick = { scope.launch { viewModel.antigravityAuth.beginLogin() } },
                    enabled = !inFlight,
                    modifier = Modifier.fillMaxWidth(),
                )
                agyAuth.authorizationUrl?.let { url ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            shape = RoundedCornerShape(100),
                            onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } },
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        ) { Text(stringResource(R.string.agent_open_google_sign_in)) }
                        OutlinedButton(
                            shape = RoundedCornerShape(100),
                            onClick = { clipboard.setText(AnnotatedString(url)) },
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        ) { Text(stringResource(R.string.agent_copy_link)) }
                    }
                }
                if (!manualCode) {
                    ForgeTextButton(onClick = { manualCode = true }, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.settings_enter_code_manually))
                    }
                } else {
                    ForgeTextField(
                        kind = ForgeFieldKind.IDENTIFIER, imeAction = ImeAction.Done,
                        value = agyVerificationCode,
                        onValueChange = { agyVerificationCode = it },
                        label = { Text(stringResource(R.string.settings_one_time_code)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ForgePrimaryButton(
                            text = stringResource(R.string.settings_complete_sign_in),
                            onClick = {
                                if (agyVerificationCode.isNotBlank()) {
                                    runCatching { viewModel.antigravityAuth.submitCode(agyVerificationCode.trim()) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("SettingsScreenModern", it) }
                                    agyVerificationCode = ""
                                }
                            },
                            enabled = agyVerificationCode.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        )
                        ForgeTextButton(onClick = {
                            manualCode = false
                            agyVerificationCode = ""
                            viewModel.antigravityAuth.cancel()
                        }) { Text(stringResource(R.string.agent_cancel)) }
                    }
                }
            }
            if (!signedIn) state.apiStatusMessage?.let { msg ->
                Text(text = msg.asString(), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
            }
            Text(
                text = stringResource(R.string.settings_antigravity_footnote),
                fontSize = ForgeType.secondary,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    // Models come from the signed-in account (`agy models`); load them once as soon as the account is connected.
    androidx.compose.runtime.LaunchedEffect(signedIn) {
        if (signedIn && state.antigravityModels.isEmpty()) viewModel.refreshAntigravityModels()
    }
    if (showModelPicker) {
        ModelPickerDialog(
            title = stringResource(R.string.settings_antigravity_model),
            options = antigravityModelOptions(state.antigravityModels, state.antigravityModel),
            selectedId = state.antigravityModel,
            loading = state.antigravityModelsLoading,
            onRefresh = viewModel::refreshAntigravityModels,
            onSelect = { id ->
                viewModel.selectAntigravityModel(id)
                onAgyModelChange(id)
                AGY_EFFORT_SUFFIX.find(id)?.groupValues?.get(1)?.let(onAgyEffortChange)
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false },
        )
    }
    if (signedIn) {
        val tokens = LocalForgeThemeTokens.current
        val efforts = listOf("low", "medium", "high")
        val effortIndex = efforts.indexOf(agyEffortInput).coerceAtLeast(0)
        val currentModel = agyModelInput.ifBlank { state.antigravityModel }
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(ForgeSpace.cardInner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.agent_active_model),
                        fontSize = ForgeType.secondary,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    ForgeTextButton(onClick = viewModel::refreshAntigravityModels, enabled = !state.antigravityModelsLoading) {
                        Text(stringResource(R.string.agent_sync))
                    }
                }
                // Model tile: tap to open the bottom sheet with the models of the signed-in account.
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = tokens.cardElevatedColor,
                    border = tokens.cardBorderStroke,
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { showModelPicker = true },
                ) {
                    Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(10.dp), color = tokens.cardContainerColor, modifier = Modifier.size(36.dp)) {
                            Box(contentAlignment = Alignment.Center) { ForgeIcon(glyph = ForgeGlyph.SPARK, tint = scheme.primary, size = 20.dp) }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (currentModel.isBlank()) "—" else antigravityModelLabel(currentModel),
                                fontSize = ForgeType.cardTitle,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (currentModel.isNotBlank()) {
                                Text(currentModel, fontSize = ForgeType.secondary, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant)
                            }
                        }
                        Chevron(expanded = false)
                    }
                }
                // Stacked, not side by side: the long hint used to squeeze the label into two ragged lines.
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.agent_reasoning_depth),
                        fontSize = ForgeType.secondary,
                        color = scheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            when (effortIndex) {
                                0 -> R.string.agent_reasoning_low_hint
                                1 -> R.string.agent_reasoning_medium_hint
                                else -> R.string.agent_reasoning_high_hint
                            },
                        ),
                        fontSize = ForgeType.secondary,
                        color = scheme.primary,
                    )
                }
                ForgePillTabs(
                    items = listOf(stringResource(R.string.agent_effort_low), stringResource(R.string.agent_effort_medium), stringResource(R.string.agent_effort_high)),
                    equalWidth = true,
                    selectedIndex = effortIndex,
                    onSelect = {
                        onAgyEffortChange(efforts[it])
                        // Saved right away: there is no separate Save button in this layout.
                        viewModel.setAntigravityConfig(currentModel, efforts[it])
                    },
                )
                OutlinedButton(
                    shape = RoundedCornerShape(100),
                    onClick = viewModel::validateAntigravityConnection,
                    enabled = !state.apiBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                ) {
                    ForgeIcon(glyph = ForgeGlyph.REFRESH, tint = scheme.primary, size = 18.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.agent_test_connection))
                }
                state.apiStatusMessage?.let { msg ->
                    val text = msg.asString()
                    Text(
                        text = text,
                        fontSize = ForgeType.secondary,
                        color = when {
                            text.startsWith("✓") -> tokens.successText
                            text.startsWith("✕") -> tokens.dangerText
                            else -> scheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/* ───────────────────────── Building blocks ───────────────────────── */

/** 56dp list row: leading icon in a tinted square, title/subtitle, custom trailing. */
@Composable
internal fun SettingsRow(
    icon: ForgeGlyph,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    horizontalPadding: androidx.compose.ui.unit.Dp = 14.dp,
    trailing: @Composable () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier
                        .forgePressScale(interaction, pressedScale = 0.985f)
                        .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick)
                } else Modifier,
            )
            .heightIn(min = 60.dp)
            .padding(horizontal = horizontalPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = RoundedCornerShape(10.dp), color = tokens.cardElevatedColor, modifier = Modifier.size(36.dp)) {
            Box(contentAlignment = Alignment.Center) {
                ForgeIcon(glyph = icon, tint = iconTint, size = 20.dp)
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = ForgeType.rowTitle, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        trailing()
    }
}

/** Card with a SettingsRow header that reveals its body. */
@Composable
internal fun DisclosureCard(
    icon: ForgeGlyph,
    title: String,
    subtitle: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
        SettingsRow(icon = icon, title = title, subtitle = subtitle, onClick = onToggle, trailing = { Chevron(expanded) })
        AnimatedVisibility(
            visible = expanded,
            enter = ForgeMotion.expandEnter(reduceMotion),
            exit = ForgeMotion.shrinkExit(reduceMotion),
        ) {
            Column {
                ThinDivider()
                content()
            }
        }
    }
}

@Composable
internal fun Chevron(expanded: Boolean) {
    val reduceMotion = rememberReduceMotion()
    val rotation by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (reduceMotion) androidx.compose.animation.core.snap() else ForgeMotion.springSnappy(),
        label = "chevronRotation",
    )
    Icon(
        imageVector = Icons.Default.KeyboardArrowDown,
        contentDescription = stringResource(if (expanded) R.string.projects_collapse else R.string.projects_expand),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.graphicsLayer { rotationZ = rotation },
    )
}

@Composable
internal fun ThinDivider() {
    Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
}

/** Radio row: title + subtitle left, Material radio right; whole row is the target. */
@Composable
internal fun RadioRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    mono: Boolean = false,
    horizontalPadding: androidx.compose.ui.unit.Dp = 14.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .forgePressScale(interaction, pressedScale = 0.985f, haptic = true)
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), role = Role.RadioButton, onClick = onClick)
            .forgeSelected(selected)
            .heightIn(min = 56.dp)
            .padding(horizontal = horizontalPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = ForgeType.rowTitle, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = ForgeType.secondary,
                    color = scheme.onSurfaceVariant,
                    fontFamily = if (mono) FontFamily.Monospace else null,
                )
            }
        }
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
internal fun KeyValueRow(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key, fontSize = ForgeType.body, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 140.dp).padding(end = 12.dp))
        // A long value (installed stacks, agent versions) wraps and ends at the right edge; it must not push the label out.
        Text(
            value,
            fontSize = ForgeType.body,
            fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
    }
}

/** Progress and message of the runtime setup service while it runs, errors in the danger colour. */
@Composable
internal fun SetupProgress(state: ForgeUiState, scope: SetupScope? = null) {
    // Agent installs show under their agent tab, tool/stack installs under Settings: a screen passes its scope.
    if (scope != null && state.setupScope != scope) return
    val status = state.setupSnapshot.status
    // Step, percent, MB transferred and the latest terminal lines while an install runs or after it failed.
    if (status == RuntimeSetupStatus.RUNNING || status == RuntimeSetupStatus.ERROR) {
        RuntimeProgressPanel(state.setupSnapshot)
    }
}

/** 2 × 2 theme tiles: icon-less by design, the title carries the name; selection by accent tint + ring. Tiles in a row share one height. */
@Composable
internal fun ThemeModeGrid(active: ForgeThemeMode, onSelect: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    val reduceMotion = rememberReduceMotion()
    Column(
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ForgeThemeMode.entries.chunked(2).forEach { pair ->
            // IntrinsicSize.Min + fillMaxHeight: both tiles in a row are as tall as the taller one.
            Row(modifier = Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { mode ->
                    val selected = mode == active
                    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val tileBg = androidx.compose.animation.animateColorAsState(
                        targetValue = if (selected) scheme.primary.copy(alpha = 0.12f) else tokens.cardElevatedColor,
                        animationSpec = if (reduceMotion) androidx.compose.animation.core.snap() else ForgeMotion.springSnappy(),
                        label = "themeTileBg",
                    ).value
                    val titleColor = androidx.compose.animation.animateColorAsState(
                        targetValue = if (selected) scheme.primary else scheme.onSurface,
                        animationSpec = if (reduceMotion) androidx.compose.animation.core.snap() else ForgeMotion.springSnappy(),
                        label = "themeTileTitle",
                    ).value
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = tileBg,
                        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, scheme.primary.copy(alpha = 0.6f)) else null,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .heightIn(min = 76.dp)
                            .forgePressScale(interaction, pressedScale = 0.96f, haptic = true)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), role = Role.RadioButton) { onSelect(mode.id) }
                            .forgeSelected(selected),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.Top,
                        ) {
                            Text(
                                text = stringResource(mode.title),
                                fontSize = ForgeType.body,
                                fontWeight = FontWeight.SemiBold,
                                color = titleColor,
                            )
                            Text(
                                text = stringResource(mode.subtitle),
                                fontSize = ForgeType.caption,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

/** "412 MB" style size for the cache list. */
private fun cacheSizeLabel(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "${bytes shr 20} MB"
    bytes > 0 -> "${maxOf(1, bytes shr 10)} KB"
    else -> "0"
}
