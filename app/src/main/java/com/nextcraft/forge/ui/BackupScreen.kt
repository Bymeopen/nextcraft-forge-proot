package com.nextcraft.forge.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.data.BackupCrypto
import com.nextcraft.forge.data.BackupManager
import java.io.BufferedInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BackupUiState(
    val busy: Boolean = false,
    val message: UiText? = null,
    /** Set after a restore: the app has to restart so the restored settings are the ones in memory. */
    val restartNeeded: Boolean = false,
)

/** Runs backups and restores off the main thread and reports their outcome to [BackupScreen]. */
class BackupController(private val app: Application, private val scope: CoroutineScope) {
    private val manager = BackupManager(app)
    private val mutable = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = mutable.asStateFlow()

    fun backup(uri: Uri, options: BackupManager.Options, password: String) {
        if (mutable.value.busy) return
        mutable.value = BackupUiState(busy = true)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val out = app.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot write the backup file")
                    manager.create(out, options, password.toCharArray().takeIf { it.isNotEmpty() })
                }
            }
            mutable.value = result.fold(
                onSuccess = { BackupUiState(message = UiText.res(R.string.backup_done, it.files, formatSize(it.bytes)) ) },
                onFailure = {
                    // A half-written file would look like a valid backup in the file picker.
                    runCatching { android.provider.DocumentsContract.deleteDocument(app.contentResolver, uri) }
                    BackupUiState(message = UiText.res(R.string.backup_failed, it.message ?: it.javaClass.simpleName))
                },
            )
        }
    }

    /** Reads only the first bytes: whether [uri] is a password-protected backup. */
    suspend fun isEncrypted(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            app.contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(4)
                input.read(header) == 4 && BackupCrypto.isEncrypted(header)
            } ?: false
        }.getOrDefault(false)
    }

    fun restore(uri: Uri, password: String) {
        if (mutable.value.busy) return
        mutable.value = BackupUiState(busy = true)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val input = app.contentResolver.openInputStream(uri) ?: error("Cannot read the backup file")
                    BufferedInputStream(input).use { manager.restore(it, password.toCharArray().takeIf { p -> p.isNotEmpty() }) }
                }
            }
            mutable.value = result.fold(
                onSuccess = { BackupUiState(message = UiText.res(R.string.restore_done, it.files), restartNeeded = true) },
                onFailure = {
                    val text = when (it) {
                        is BackupCrypto.WrongPasswordException -> UiText.res(R.string.restore_wrong_password)
                        is BackupManager.NeedsPasswordException -> UiText.res(R.string.restore_needs_password)
                        else -> UiText.res(R.string.restore_failed, it.message ?: it.javaClass.simpleName)
                    }
                    BackupUiState(message = text)
                },
            )
        }
    }

    /** Relaunches the app in a fresh process so nothing in memory overwrites the restored settings. */
    fun restartApp() {
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        app.startActivity(launch)
        Runtime.getRuntime().exit(0)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "${bytes shr 20} MB"
        else -> "${maxOf(1, bytes shr 10)} KB"
    }
}

/** Tools → Backup & Restore: projects, chats, settings and (encrypted only) API keys to a file the user picks. */
@Composable
fun BackupScreen(state: ForgeUiState, viewModel: MainViewModel) {
    val scheme = MaterialTheme.colorScheme
    val backup by viewModel.backup.state.collectAsState()
    var projects by rememberSaveable { mutableStateOf(true) }
    var chats by rememberSaveable { mutableStateOf(true) }
    var settings by rememberSaveable { mutableStateOf(true) }
    var apiKeys by rememberSaveable { mutableStateOf(false) }
    // Passwords live only in this composition: never saved, never restored after process death.
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    var restoreEncrypted by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    val agentBusy = state.sessionRunning || state.terminalRunning || state.buildingProjectIds.isNotEmpty()
    val encrypted = password.isNotEmpty()
    val passwordsMatch = password == confirm
    val canBackup = !backup.busy && (projects || chats || settings || apiKeys) && passwordsMatch && (!apiKeys || encrypted)

    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.backup.backup(uri, BackupManager.Options(projects, chats, settings, apiKeys && encrypted), password)
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingRestore = uri
            restorePassword = ""
            confirmRestore = true
        }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    if (pendingRestore != null) {
        androidx.compose.runtime.LaunchedEffect(pendingRestore) {
            restoreEncrypted = viewModel.backup.isEncrypted(pendingRestore!!)
        }
    }

    ToolScaffold(
        title = stringResource(R.string.tools_backup),
        subtitle = stringResource(R.string.tools_backup_desc),
        onBack = { viewModel.selectTab(ForgeTab.TOOLS) },
    ) {
        ToolSectionLabel(stringResource(R.string.backup_section_create))
        ToolCard {
            ForgeToggleRow(title = stringResource(R.string.backup_projects), checked = projects, onCheckedChange = { projects = it })
            ForgeToggleRow(title = stringResource(R.string.backup_chats), checked = chats, onCheckedChange = { chats = it })
            ForgeToggleRow(title = stringResource(R.string.backup_settings), checked = settings, onCheckedChange = { settings = it })
            ForgeToggleRow(
                title = stringResource(R.string.backup_api_keys),
                subtitle = stringResource(R.string.backup_api_keys_sub),
                checked = apiKeys,
                onCheckedChange = { apiKeys = it },
            )
            ForgeTextField(
                kind = ForgeFieldKind.SECRET, imeAction = ImeAction.Next,
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.backup_password)) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (encrypted) {
                ForgeTextField(
                    kind = ForgeFieldKind.SECRET, imeAction = ImeAction.Done,
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text(stringResource(R.string.backup_password_confirm)) },
                    error = if (confirm.isNotEmpty() && !passwordsMatch) stringResource(R.string.backup_password_mismatch) else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                stringResource(if (encrypted) R.string.backup_encrypted_note else R.string.backup_plain_note),
                fontSize = ForgeType.secondary,
                color = scheme.onSurfaceVariant,
            )
            ForgePrimaryButton(
                text = stringResource(R.string.backup_create),
                onClick = {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    createFile.launch("nextcraft-backup-$stamp.ncbak")
                },
                enabled = canBackup && !agentBusy,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ToolSectionLabel(stringResource(R.string.backup_section_restore))
        ToolCard {
            Text(stringResource(R.string.restore_note), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
            OutlinedButton(
                shape = RoundedCornerShape(100),
                onClick = { openFile.launch(arrayOf("*/*")) },
                enabled = !backup.busy && !agentBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.restore_choose)) }
        }

        if (agentBusy) {
            Text(stringResource(R.string.backup_busy), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
        }
        if (backup.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        backup.message?.let { Text(it.asString(), fontSize = ForgeType.control) }
        if (backup.restartNeeded) {
            ForgePrimaryButton(
                text = stringResource(R.string.restore_restart),
                onClick = viewModel.backup::restartApp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (confirmRestore && pendingRestore != null) {
        val tokens = LocalForgeThemeTokens.current
        ForgeAlertDialog(
            modifier = Modifier.forgePopIn(),
            onDismissRequest = { confirmRestore = false; pendingRestore = null },
            shape = RoundedCornerShape(24.dp),
            title = { Text(stringResource(R.string.restore_confirm_title)) },
            text = {
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.restore_confirm_message))
                    if (restoreEncrypted) {
                        ForgeTextField(
                            kind = ForgeFieldKind.SECRET, imeAction = ImeAction.Done,
                            value = restorePassword,
                            onValueChange = { restorePassword = it },
                            label = { Text(stringResource(R.string.backup_password)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val uri = pendingRestore ?: return@TextButton
                        confirmRestore = false
                        pendingRestore = null
                        scope.launch { viewModel.backup.restore(uri, restorePassword) }
                    },
                    enabled = !restoreEncrypted || restorePassword.isNotEmpty(),
                ) { Text(stringResource(R.string.restore_confirm), color = tokens.dangerText) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmRestore = false; pendingRestore = null }) {
                    Text(stringResource(R.string.agent_cancel))
                }
            },
        )
    }
}
