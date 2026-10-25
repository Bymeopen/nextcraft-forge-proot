package com.nextcraft.forge.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.nextcraft.forge.R

/**
 * Fixed English sentences produced below the UI layer (provider checks, build diagnostics, diff notes, validation) that
 * reach the screen as plain strings. They are translated when shown, so they follow the app language, including after
 * it is changed. Text that is not recognised (tool output, server replies) is shown as it came.
 *
 * MessageTextTest keeps this table honest: every entry must equal its English resource and differ in Indonesian.
 */
internal object MessageText {
    internal val exact: Map<String, Int> = mapOf(
        "Enter a base URL first." to R.string.mt_enter_base_url,
        "This provider did not expose a model list. You can enter a custom model name." to R.string.mt_no_model_list,
        "The provider replied, but its model list was empty or unsupported." to R.string.mt_model_list_empty,
        "Check the saved API key, then try refreshing again." to R.string.mt_check_saved_key,
        "Base URL, model, and API key are required." to R.string.mt_fields_required,
        "Check this API key or select another saved key." to R.string.mt_check_this_key,
        "Check the Base URL and selected gateway protocol." to R.string.mt_check_base_url,
        "Refresh the model list or select a different model." to R.string.mt_refresh_models,
        "Wait a moment, then retry or use another API key." to R.string.mt_wait_retry_key,
        "The provider is temporarily unavailable. Try again shortly." to R.string.mt_provider_unavailable,
        "Review the model, protocol, and endpoint settings." to R.string.mt_review_settings,
        "Check your connection and try again." to R.string.mt_check_connection,
        "Check your internet connection and provider settings." to R.string.mt_check_internet,
        "Network connection failed" to R.string.mt_network_failed,
        "The provider rate limit was reached. Wait a moment and try again." to R.string.mt_rate_limit,
        "Connection verified." to R.string.mt_connection_verified,
        "Rejected" to R.string.mt_label_rejected,
        "Endpoint error" to R.string.mt_label_endpoint,
        "Model error" to R.string.mt_label_model,
        "Rate limited" to R.string.mt_label_rate,
        "Provider error" to R.string.mt_label_provider,
        "Request failed" to R.string.mt_label_request,
        "Timed out" to R.string.mt_label_timeout,
        "Network error" to R.string.mt_label_network,
        "Repository name must not be empty" to R.string.mt_repo_name_empty,
        "Source error" to R.string.mt_build_source_error,
        "Build failed" to R.string.mt_build_failed_title,
        "Open the file and correct the reported line." to R.string.mt_build_fix_line,
        "Free device storage, then retry the build." to R.string.mt_build_free_storage,
        "Check the network connection or use a cached dependency version." to R.string.mt_build_network,
        "Review duplicate or conflicting manifest declarations." to R.string.mt_build_manifest_hint,
        "Review resource names and XML diagnostics in the build output." to R.string.mt_build_resources_hint,
        "Close other apps and retry." to R.string.mt_build_close_apps,
        "Open the build details for the complete Gradle output." to R.string.mt_build_details,
        "The device does not have enough free storage for this build." to R.string.mt_build_no_storage,
        "Gradle ran out of memory. Close other apps and retry." to R.string.mt_build_oom,
        "A project dependency is unavailable. Connect to the internet or use an offline-cached version." to R.string.mt_build_dependency,
        "Android manifest merge failed. Open the build log for the conflicting declaration." to R.string.mt_build_manifest,
        "Android resources could not be compiled. Open the build log for the file and line." to R.string.mt_build_resources,
        "Kotlin or Java compilation failed. Open the build log for source errors." to R.string.mt_build_compile,
        "This project's Gradle and Android plugin versions are incompatible." to R.string.mt_build_incompatible,
        "Binary file changed" to R.string.mt_diff_binary,
        "File is too large to preview safely. Undo and Keep still work." to R.string.mt_diff_too_large_file,
        "Give the host a name." to R.string.mt_ssh_name,
        "Checking the SSH client…" to R.string.mt_ssh_checking,
        "OpenSSH client is not installed." to R.string.mt_ssh_missing,
        "User may only contain letters, digits, . _ -" to R.string.mt_ssh_user,
        "Host may only contain letters, digits, . _ -" to R.string.mt_ssh_host,
        "Port must be 1-65535." to R.string.mt_ssh_port,
        "Forward must look like 8080:localhost:80." to R.string.mt_ssh_forward,
        "Root access and the installed Linux runtime are required to run this." to R.string.mt_root_required,
        "Invalid project id" to R.string.mt_snap_invalid_id,
        "Project folder not found" to R.string.mt_snap_no_folder,
        "Could not save the snapshot" to R.string.mt_snap_save_failed,
        "Could not read the snapshot back" to R.string.mt_snap_read_back,
        "Snapshot file is missing" to R.string.mt_snap_missing,
        "Snapshot contains an unsafe path" to R.string.mt_snap_unsafe,
        "Opened system package installer." to R.string.mt_installer_opened,
        "APK installation failed." to R.string.mt_install_failed_dot,
        "APK installation failed" to R.string.mt_install_failed,
        "PRoot engine and launcher script verified." to R.string.mt_repair_ok,
        "PRoot binary missing from the APK; reinstall the app." to R.string.mt_repair_incomplete,
        "No app release has been published yet." to R.string.mt_upd_no_release,
        "Update download exceeds the allowed size." to R.string.mt_upd_too_big,
        "Update manifest is missing SHA-256 checksum — refusing install." to R.string.mt_upd_no_checksum,
        "Downloaded update failed SHA-256 checksum verification." to R.string.mt_upd_bad_checksum,
        "Downloaded file is not a valid Android APK archive." to R.string.mt_upd_not_apk,
        "Downloaded APK signing certificate does not match the installed app." to R.string.mt_upd_cert,
        "Archive has too many files" to R.string.mt_zip_too_many,
        "Not a regular file" to R.string.mt_not_regular,
    )

    internal class Rule(val regex: Regex, @androidx.annotation.StringRes val id: Int)

    internal val rules = listOf(
        Rule(Regex("^The provider is temporarily unavailable \\(HTTP (.+)\\)\\.\$"), R.string.mt_provider_unavailable_http),
        Rule(Regex("^The provider returned HTTP (.+)\\. Check the URL and account access\\.\$"), R.string.mt_provider_http),
        Rule(Regex("^Unexpected GitHub (.+) response\$"), R.string.mt_github_unexpected),
        Rule(Regex("^Project is larger than (.+) MB; remove build output first\\.\$"), R.string.mt_snap_too_large),
        Rule(Regex("^Could not clear (.+); a build or terminal may still be using it\\.\$"), R.string.mt_snap_clear_failed),
        Rule(Regex("^Installed & launched (.+)\$"), R.string.mt_installed_launched),
        Rule(Regex("^Reading logcat needs READ_LOGS: run `adb shell pm grant (.+) android\\.permission\\.READ_LOGS` once\\.\$"), R.string.mt_logcat_read_logs),
        Rule(Regex("^Installed (.+)\$"), R.string.mt_installed),
        Rule(Regex("^Gradle build failed \\(exit code (.+)\\)\\.\$"), R.string.mt_build_exit),
        Rule(Regex("^… (.+) unchanged lines …\$"), R.string.mt_diff_unchanged),
        Rule(Regex("^Diff is too large to display \\((.+) → (.+) lines\\)\\. Undo and Keep still work\\.\$"), R.string.mt_diff_too_large),
        Rule(Regex("^Unsafe zip entry: (.+)\$"), R.string.mt_zip_unsafe),
        Rule(Regex("^Update download failed \\(HTTP (.+)\\)\\.\$"), R.string.mt_upd_http),
        Rule(Regex("^Update manifest request failed \\(HTTP (.+)\\)\$"), R.string.mt_upd_manifest_http),
    )

    /** The string resource and its arguments for [raw], or null when it is not a known sentence. */
    fun match(raw: String): Pair<Int, List<String>>? {
        val text = raw.trim()
        exact[text]?.let { return it to emptyList() }
        rules.forEach { rule ->
            val found = rule.regex.matchEntire(text) ?: return@forEach
            return rule.id to found.groupValues.drop(1)
        }
        return null
    }

    fun localize(context: Context, raw: String): String =
        match(raw)?.let { (id, args) -> context.getString(id, *args.toTypedArray()) } ?: raw
}

/** [raw] in the app language when it is a known sentence, else unchanged. */
@Composable
internal fun messageText(raw: String): String {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current // re-read when the language changes
    return remember(raw, configuration) { MessageText.localize(context, raw) }
}

/** A multi-line log in the app language: known sentences are translated line by line, tool output stays as it is. */
@Composable
internal fun messageLines(raw: String): String {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(raw, configuration) { raw.lines().joinToString("\n") { MessageText.localize(context, it) } }
}
