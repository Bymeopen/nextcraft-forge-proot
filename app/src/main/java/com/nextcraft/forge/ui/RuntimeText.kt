package com.nextcraft.forge.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.nextcraft.forge.R

/**
* The runtime installer reports progress as English sentences. This maps the known ones to string resources so
* the install panel follows the app language; anything unrecognised (tool output, log lines) is shown as it came.
*/
internal object RuntimeText {
    private val exact: Map<String, Int> = mapOf(
        "Unpacking Core Linux rootfs…" to R.string.rt_msg_unpack_rootfs,
        "Root Chroot runtime ready" to R.string.rt_msg_chroot_ready_short,
        "Preparing runtime IPC bridge…" to R.string.rt_msg_ipc_bridge,
        "NextCraft Forge Root Chroot setup complete" to R.string.rt_msg_setup_complete,
        "Preparing Ubuntu Root Chroot runtime…" to R.string.rt_msg_prepare_ubuntu,
        "Checking Root Chroot runtime files…" to R.string.rt_msg_check_files,
        "Checking Node.js and npm…" to R.string.rt_msg_check_node,
        "Installing Git and base SSL certificates…" to R.string.rt_msg_install_git,
        "Installing PHP and extensions…" to R.string.rt_msg_install_php,
        "Updating Ubuntu packages inside Root Chroot…" to R.string.rt_msg_update_ubuntu,
        "Linux chroot runtime is ready" to R.string.rt_msg_chroot_ready,
        "Runtime setup paused" to R.string.rt_msg_setup_paused,
        "Preparing your private coding workspace" to R.string.rt_msg_preparing_default,
        "Runtime setup could not complete." to R.string.rt_msg_setup_failed,
        "Your Ubuntu ARM64 chroot environment and coding tools are ready." to R.string.rt_msg_env_ready,
        "✓ Setup completed successfully" to R.string.rt_msg_log_done,
        "— Resuming NextCraft Forge setup —" to R.string.rt_msg_log_resume,
        "• Setup paused safely" to R.string.rt_msg_log_paused,
    )

    private class Rule(val regex: Regex, @StringRes val id: Int)

    private val rules = listOf(
        Rule(Regex("^Downloading: (.+)$"), R.string.rt_msg_downloading_amount),
        Rule(Regex("^Downloading (.+) bundle…$"), R.string.rt_msg_downloading_bundle),
        Rule(Regex("^Loading (.+) bundle…$"), R.string.rt_msg_loading_bundle),
        Rule(Regex("^Downloading (.+)…$"), R.string.rt_msg_downloading),
        Rule(Regex("^Installing (.+)…$"), R.string.rt_msg_installing),
        Rule(Regex("^Removing (.+)…$"), R.string.rt_msg_removing),
        Rule(Regex("^Installed (.+) runtime bundle$"), R.string.rt_msg_installed_bundle),
        Rule(Regex("^Installed (.+)$"), R.string.rt_msg_installed),
        Rule(Regex("^Removed (.+)$"), R.string.rt_msg_removed),
        Rule(Regex("^(.+) stack is ready$"), R.string.rt_msg_stack_ready),
        Rule(Regex("^(.+) is ready$"), R.string.rt_msg_is_ready),
    )

    /** The string resource and its arguments for [raw], or null when it is not a known installer sentence. */
    fun match(raw: String): Pair<Int, List<String>>? {
        val text = raw.trim()
        exact[text]?.let { return it to emptyList() }
        rules.forEach { rule ->
            val found = rule.regex.matchEntire(text) ?: return@forEach
            return rule.id to found.groupValues.drop(1)
        }
        return null
    }
}

/** [raw] in the app language when it is a known installer sentence, else unchanged. */
@Composable
@Suppress("LocalContextGetResourceValueCall") // remember() is keyed on the configuration below
internal fun runtimeText(raw: String): String {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current // re-read when the language changes
    return remember(raw, configuration) {
        RuntimeText.match(raw)?.let { (id, args) -> context.getString(id, *args.toTypedArray()) } ?: raw
    }
}
