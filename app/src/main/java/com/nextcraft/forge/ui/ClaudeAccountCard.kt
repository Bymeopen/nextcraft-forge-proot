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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.ImeAction
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.runtime.ClaudeAuthStatus
import com.nextcraft.forge.runtime.ClaudeLoginMode
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Sign-in for the Claude subscription: the browser (account or SSO) opens through Claude Code's own login, and the
 * redirect back to the CLI finishes it; the pasted code is only a fallback.
 */
@Composable
fun ClaudeAccountCard(viewModel: MainViewModel, extra: @Composable ColumnScope.() -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val auth by viewModel.claudeAuth.state.collectAsStateWithLifecycle()
    // A login left on disk by an earlier session (or from the terminal) counts as signed in.
    val hasCredential by produceState(initialValue = false, auth.status) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { viewModel.claudeAuth.hasCredential() }
    }
    var email by rememberSaveable { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val signedIn = auth.status == ClaudeAuthStatus.SIGNED_IN ||
        (hasCredential && auth.status == ClaudeAuthStatus.SIGNED_OUT)
    val inFlight = auth.status == ClaudeAuthStatus.STARTING || auth.status == ClaudeAuthStatus.COMPLETING
    val waiting = auth.status == ClaudeAuthStatus.AWAITING_BROWSER || auth.status == ClaudeAuthStatus.COMPLETING

    ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(ForgeSpace.cardInner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_claude_account), fontSize = ForgeType.cardTitle, fontWeight = FontWeight.SemiBold)
            Text(
                text = stringResource(
                    when {
                        signedIn -> R.string.settings_claude_signed_in
                        waiting || inFlight -> R.string.settings_claude_waiting
                        else -> R.string.settings_claude_not_signed_in
                    },
                ),
                fontSize = ForgeType.cardTitle,
                fontWeight = FontWeight.SemiBold,
            )
            // The signed-in account: shown whenever it is known, not only right after the sign-in finished.
            if (signedIn) {
                androidx.compose.runtime.LaunchedEffect(signedIn, auth.status) {
                    if (auth.accountEmail == null) viewModel.claudeAuth.loadAccountEmail()
                }
                auth.accountEmail?.let {
                    Text(stringResource(R.string.rt_auth_connected_as, it), fontSize = ForgeType.rowTitle, color = scheme.primary)
                }
            }
            auth.message?.takeUnless { signedIn && auth.accountEmail != null }?.let {
                Text(it.asString(), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (inFlight) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            if (signedIn) {
                OutlinedButton(
                    shape = RoundedCornerShape(100),
                    onClick = { scope.launch { runCatching { viewModel.claudeAuth.logout() } } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                ) { Text(stringResource(R.string.settings_claude_sign_out)) }
            } else {
                ForgeTextField(
                    kind = ForgeFieldKind.EMAIL, imeAction = ImeAction.Done,
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.settings_claude_email_optional)) },
                    enabled = !inFlight,
                    modifier = Modifier.fillMaxWidth(),
                )
                ForgePrimaryButton(
                    text = stringResource(R.string.settings_claude_sign_in),
                    onClick = { scope.launch { viewModel.claudeAuth.beginLogin(ClaudeLoginMode.CLAUDE_ACCOUNT, email) } },
                    enabled = !inFlight,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    shape = RoundedCornerShape(100),
                    // Allowed while a browser login is waiting: the controller restarts the flow in SSO mode.
                    onClick = { scope.launch { viewModel.claudeAuth.beginLogin(ClaudeLoginMode.SSO, email) } },
                    enabled = !inFlight,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                ) { Text(stringResource(R.string.settings_claude_sign_in_sso)) }

                auth.authorizationUrl?.let { url ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ForgePrimaryButton(
                            text = stringResource(R.string.settings_claude_open_page),
                            onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            shape = RoundedCornerShape(100),
                            onClick = { clipboard.setText(AnnotatedString(auth.manualUrl ?: url)) },
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        ) { Text(stringResource(R.string.agent_copy_link)) }
                    }
                }
                if (waiting) {
                    ForgeTextField(
                        kind = ForgeFieldKind.IDENTIFIER, imeAction = ImeAction.Done,
                        value = code,
                        onValueChange = { code = it },
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
                                runCatching { viewModel.claudeAuth.submitCode(code.trim()) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeAccountCard", it) }
                                code = ""
                            },
                            enabled = code.isNotBlank() && auth.status == ClaudeAuthStatus.AWAITING_BROWSER,
                            modifier = Modifier.weight(1f),
                        )
                        ForgeTextButton(onClick = {
                            code = ""
                            viewModel.claudeAuth.cancel()
                        }) { Text(stringResource(R.string.agent_cancel)) }
                    }
                }
            }
            // How sign-in works is only useful before it is done; once connected it is five lines of noise.
            if (!signedIn || waiting) {
                Text(
                    text = stringResource(R.string.settings_claude_footnote),
                    fontSize = ForgeType.secondary,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            extra()
        }
    }
}
