@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.runtime.ClaudeAuthStatus
import com.nextcraft.forge.runtime.ClaudeLoginMode
import com.nextcraft.forge.runtime.ErrorLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ClaudeAccountCard(
    viewModel: MainViewModel,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val palette = MaterialTheme.colorScheme
    val coroutineScope = rememberCoroutineScope()
    val hostContext = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val authState by viewModel.claudeAuth.state.collectAsState()
    val storedDiskCredential by produceState(initialValue = false, authState.status) {
        value = withContext(Dispatchers.IO) { viewModel.claudeAuth.hasCredential() }
    }
    var accountEmailInput by rememberSaveable { mutableStateOf("") }
    var oneTimeCodeInput by remember { mutableStateOf("") }

    val isAuthenticated = authState.status == ClaudeAuthStatus.SIGNED_IN ||
        (storedDiskCredential && authState.status == ClaudeAuthStatus.SIGNED_OUT)
    val isBusy = authState.status == ClaudeAuthStatus.STARTING || authState.status == ClaudeAuthStatus.COMPLETING
    val isAwaitingCallback = authState.status == ClaudeAuthStatus.AWAITING_BROWSER || authState.status == ClaudeAuthStatus.COMPLETING

    ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(ForgeSpace.cardInner),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_claude_account),
                fontSize = ForgeType.cardTitle,
                fontWeight = FontWeight.SemiBold,
            )
            val statusHeadlineRes = when {
                isAuthenticated -> R.string.settings_claude_signed_in
                isAwaitingCallback || isBusy -> R.string.settings_claude_waiting
                else -> R.string.settings_claude_not_signed_in
            }
            Text(
                text = stringResource(statusHeadlineRes),
                fontSize = ForgeType.cardTitle,
                fontWeight = FontWeight.SemiBold,
            )
            if (isAuthenticated) {
                LaunchedEffect(isAuthenticated, authState.status) {
                    if (authState.accountEmail == null) viewModel.claudeAuth.loadAccountEmail()
                }
                authState.accountEmail?.let { connectedEmail ->
                    Text(
                        text = stringResource(R.string.rt_auth_connected_as, connectedEmail),
                        fontSize = ForgeType.rowTitle,
                        color = palette.primary,
                    )
                }
            }
            authState.message?.takeUnless { isAuthenticated && authState.accountEmail != null }?.let { statusNotice ->
                Text(
                    text = statusNotice.asString(),
                    fontSize = ForgeType.secondary,
                    color = palette.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (isAuthenticated) {
                OutlinedButton(
                    shape = RoundedCornerShape(100),
                    onClick = { coroutineScope.launch { runCatching { viewModel.claudeAuth.logout() } } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp),
                ) {
                    Text(stringResource(R.string.settings_claude_sign_out))
                }
            } else {
                ForgeTextField(
                    kind = ForgeFieldKind.EMAIL,
                    imeAction = ImeAction.Done,
                    value = accountEmailInput,
                    onValueChange = { accountEmailInput = it },
                    label = { Text(stringResource(R.string.settings_claude_email_optional)) },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
                ForgePrimaryButton(
                    text = stringResource(R.string.settings_claude_sign_in),
                    onClick = {
                        coroutineScope.launch {
                            viewModel.claudeAuth.beginLogin(ClaudeLoginMode.CLAUDE_ACCOUNT, accountEmailInput)
                        }
                    },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    shape = RoundedCornerShape(100),
                    onClick = {
                        coroutineScope.launch {
                            viewModel.claudeAuth.beginLogin(ClaudeLoginMode.SSO, accountEmailInput)
                        }
                    },
                    enabled = !isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp),
                ) {
                    Text(stringResource(R.string.settings_claude_sign_in_sso))
                }

                authState.authorizationUrl?.let { authUrl ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ForgePrimaryButton(
                            text = stringResource(R.string.settings_claude_open_page),
                            onClick = {
                                runCatching { hostContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))) }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            shape = RoundedCornerShape(100),
                            onClick = {
                                clipboardManager.setText(AnnotatedString(authState.manualUrl ?: authUrl))
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp),
                        ) {
                            Text(stringResource(R.string.agent_copy_link))
                        }
                    }
                }

                if (isAwaitingCallback) {
                    ForgeTextField(
                        kind = ForgeFieldKind.IDENTIFIER,
                        imeAction = ImeAction.Done,
                        value = oneTimeCodeInput,
                        onValueChange = { oneTimeCodeInput = it },
                        label = { Text(stringResource(R.string.settings_one_time_code)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ForgePrimaryButton(
                            text = stringResource(R.string.settings_complete_sign_in),
                            onClick = {
                                runCatching { viewModel.claudeAuth.submitCode(oneTimeCodeInput.trim()) }
                                    .onFailure { ErrorLog.swallowed("ClaudeAccountCard", it) }
                                oneTimeCodeInput = ""
                            },
                            enabled = oneTimeCodeInput.isNotBlank() && authState.status == ClaudeAuthStatus.AWAITING_BROWSER,
                            modifier = Modifier.weight(1f),
                        )
                        ForgeTextButton(
                            onClick = {
                                oneTimeCodeInput = ""
                                viewModel.claudeAuth.cancel()
                            },
                        ) {
                            Text(stringResource(R.string.agent_cancel))
                        }
                    }
                }
            }
            if (!isAuthenticated || isAwaitingCallback) {
                Text(
                    text = stringResource(R.string.settings_claude_footnote),
                    fontSize = ForgeType.secondary,
                    color = palette.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            extra()
        }
    }
}
