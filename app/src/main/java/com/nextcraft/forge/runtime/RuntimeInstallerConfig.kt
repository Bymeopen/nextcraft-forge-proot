package com.nextcraft.forge.runtime

import android.net.ConnectivityManager
import android.os.Process as AndroidProcess
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

// Guest-side settings, hooks, trust and shell configuration for [RuntimeInstaller].
/**
 * Workspaces belong to the app UID while git runs as root, so without this git refuses every project with
 * "dubious ownership" (exit 128). The system config is used so ~/.gitconfig stays the user's.
 */
internal fun RuntimeInstaller.ensureSystemGitTrust() {
    val config = File(rootfs, "etc/gitconfig")
    val existing = if (config.isFile) runCatching { config.readText() }.getOrNull() ?: return else ""
    val merged = mergeGitConfig(existing, listOf(GitConfigEntry("safe", "directory", "*", multiValue = true)))
    if (merged != existing) writeRootfsFileAtomically(config, merged)
}

/** Installs or removes android-shell and friends to match Settings → Android system access. */
fun RuntimeInstaller.applyAndroidHostTools() {
    if (!rootfs.isDirectory) return
    val enabled = com.nextcraft.forge.data.AppPreferences(context).androidHostTools
    AndroidHostTools.apply(rootfs, enabled) { file, body -> writeRootfsFileAtomically(file, body, mode = 0b111101101) }
}

fun RuntimeInstaller.ensureSettingsAndHooks() {
    val hook = File(rootfs, "opt/nextcraft/permission-hook.sh")
    hook.parentFile?.mkdirs()
    // Hands every permission request to the app through the shared bridge directory and
    // waits for the user's/guardrail decision. The app classifies risk, auto-answers
    // SAFE/REVIEW and prompts for HIGH. No answer within the timeout means deny.
    // Written atomically: a Claude session may be executing this script, and truncating it in place makes the hook
    // answer nothing, which Claude Code treats as a refusal.
    writeRootfsFileAtomically(
        hook,
        """#!/bin/sh
BRIDGE="${'$'}{NEXTCRAFT_BRIDGE_DIR:-/nextcraft-bridge}"
RESP="${'$'}{NEXTCRAFT_RESPONSE_DIR:-/nextcraft-bridge-responses}"
# Parallel chats share this directory: the session id (set by the app) tags the request so only its owner answers it.
SID="${'$'}{NEXTCRAFT_SESSION_ID:-}"
ID="${'$'}{SID:+${'$'}{SID}_}${'$'}(cat /proc/sys/kernel/random/uuid 2>/dev/null || echo "${'$'}${'$'}-${'$'}(date +%s)")"
DECISION=deny
if [ -d "${'$'}BRIDGE" ] && cat > "${'$'}BRIDGE/${'$'}ID.request.tmp" && mv "${'$'}BRIDGE/${'$'}ID.request.tmp" "${'$'}BRIDGE/${'$'}ID.request"; then
i=0
while [ "${'$'}i" -lt 600 ]; do
    if [ -f "${'$'}RESP/${'$'}ID.response" ]; then
        [ "${'$'}(cat "${'$'}RESP/${'$'}ID.response")" = "allow" ] && DECISION=allow
        break
    fi
    sleep 0.1
    i=${'$'}((i + 1))
done
rm -f "${'$'}BRIDGE/${'$'}ID.request" "${'$'}BRIDGE/${'$'}ID.request.tmp"
else
cat > /dev/null
fi
printf '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"%s"}}}\n' "${'$'}DECISION"
""",
        mode = 0b111101101,
    )

    // BROWSER handler for `claude auth login`: there is no browser in the chroot, so the sign-in URL is handed to
    // the app through the bridge directory and opened in the Android browser. The browser then redirects to
    // http://localhost:<port>/callback, which the CLI (same network namespace) is listening on.
    val openUrl = File(rootfs, "opt/nextcraft/open-url.sh")
    writeRootfsFileAtomically(
        openUrl,
        """#!/bin/sh
BRIDGE="${'$'}{NEXTCRAFT_BRIDGE_DIR:-/nextcraft-bridge}"
[ -n "${'$'}1" ] && [ -d "${'$'}BRIDGE" ] || exit 0
printf '%s\n' "${'$'}1" > "${'$'}BRIDGE/claude-login-url.tmp" && mv "${'$'}BRIDGE/claude-login-url.tmp" "${'$'}BRIDGE/claude-login-url"
""",
        mode = 0b111101101,
    )
    writeRootfsFileAtomically(
        File(rootfs, "etc/profile.d/nextcraft-browser.sh"),
        "export BROWSER=\"${'$'}{BROWSER:-/opt/nextcraft/open-url.sh}\"\n",
        mode = 0b110100100,
    )
    listOf("usr/bin/xdg-open", "usr/local/bin/xdg-open", "usr/bin/www-browser", "usr/bin/sensible-browser").forEach { path ->
        val link = File(rootfs, path)
        runCatching {
            link.parentFile?.mkdirs()
            if (java.nio.file.Files.isSymbolicLink(link.toPath()) || link.exists()) link.delete()
            Os.symlink("/opt/nextcraft/open-url.sh", link.absolutePath)
        }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
    }

    val settingsContent = JSONObject()
        .put("disableAllHooks", false)
        .put(
            "permissions",
            JSONObject()
                .put("allow", claudeWorkspaceToolRules())
                .put("defaultMode", "acceptEdits"),
        )
        .put(
            "hooks",
            JSONObject().put(
                "PermissionRequest",
                JSONArray().put(
                    JSONObject()
                        .put("matcher", "Bash|Edit|Write|NotebookEdit")
                        .put(
                            "hooks",
                            JSONArray().put(
                                JSONObject().put("type", "command").put("command", "/opt/nextcraft/permission-hook.sh"),
                            ),
                        ),
                ),
            ),
        )
        .toString()

    val settingsPaths = listOf(
        File(rootfs, "root/.claude/nextcraft-settings.json"),
        File(rootfs, "root/.claude/settings.json"),
        File(rootfs, "etc/claude/settings.json"),
    )
    for (target in settingsPaths) {
        writeRootfsFileAtomically(target, settingsContent)
    }
    // Sessions started by an older build (and its settings files) still call /opt/pocket/*: keep identical copies there.
    val legacyScripts = File(rootfs, "opt/pocket")
    listOf(hook, openUrl).forEach { script ->
        runCatching { writeRootfsFileAtomically(File(legacyScripts, script.name), script.readText(), mode = 0b111101101) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
    }
    File(rootfs, "root/.claude/pocket-settings.json").delete()
    // Agents' background jobs get a hangup when the turn ends (the CLI tears down its shells). This starts a command
    // in its own session with hangups ignored, so a dev server or a long build outlives the turn.
    writeRootfsFileAtomically(
        File(rootfs, "opt/nextcraft/bin/keep-running"),
        """#!/bin/sh
# Keeps a command running after the agent's turn ends (dev servers, long builds).
#   keep-running <logfile> <command> [args...]      then read <logfile>; stop it with: kill <pid>
[ ${'$'}# -ge 2 ] || { echo "usage: keep-running <logfile> <command> [args...]" >&2; exit 2; }
log="${'$'}1"; shift
setsid nohup "${'$'}@" > "${'$'}log" 2>&1 < /dev/null &
echo "started pid ${'$'}! (log: ${'$'}log)"
""",
        mode = 0b111101101,
    )
    ensureSystemGitTrust()
    applyAndroidHostTools()
    ForgeMcpServer.apply(rootfs) { file, body -> writeRootfsFileAtomically(file, body, mode = 0b111101101) }
    ProotAndChrootOptimizer.applyGuestWrappers(rootfs) { file, body, mode -> writeRootfsFileAtomically(file, body, mode = mode) }
    val managedMcp = runCatching { ConnectorStore(context).mcpServersJson() }.getOrNull()
    AntigravityCustomizations.apply(rootfs, mcpServers = managedMcp) { file, body -> writeRootfsFileAtomically(file, body, mode = 0b110100100) }
    runCatching {
        val dshPatch = File(rootfs, "root/.dsh/cordis.patch.yml")
        writeRootfsFileAtomically(dshPatch, ForgeMcpServer.toDshCordisPatchYaml(managedMcp), mode = 0b110000000)
    }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
    ensureWorkspaceTrust("/workspace", mcpServers = managedMcp)
    ensureSharedTmpPermissions()
}

internal fun RuntimeInstaller.ensureSharedTmpPermissions() {
    val sharedSticky = listOf("tmp", "var/tmp").map { File(rootfs, it) }
    sharedSticky.forEach { dir ->
        runCatching {
            dir.mkdirs()
            Os.chmod(dir.absolutePath, 0b1_111_111_111) // 01777: sticky + rwxrwxrwx
        }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
    }
    runCatching {
        val run = File(rootfs, "run").apply { mkdirs() }
        Os.chmod(run.absolutePath, 0b111_101_101) // 0755
    }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
}

/** Synchronizes enabled MCP connectors into Antigravity (`mcp_config.json`), DeepSeek Harness (`cordis.patch.yml`), and Claude Code (`~/.claude.json`). */
fun RuntimeInstaller.syncMcpConfigs() {
    runCatching {
        val managedMcp = ConnectorStore(context).mcpServersJson()
        AntigravityCustomizations.apply(rootfs, mcpServers = managedMcp) { file, body -> writeRootfsFileAtomically(file, body, mode = 0b110100100) }
        val dshPatch = File(rootfs, "root/.dsh/cordis.patch.yml")
        writeRootfsFileAtomically(dshPatch, ForgeMcpServer.toDshCordisPatchYaml(managedMcp), mode = 0b110000000)
        ensureWorkspaceTrust("/workspace", mcpServers = managedMcp)
    }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
}

internal fun RuntimeInstaller.ensureWorkspaceTrust(
    workspacePath: String,
    environment: Map<String, String> = emptyMap(),
    mcpServers: org.json.JSONObject? = null,
) = synchronized(CLAUDE_STATE_LOCK) {
    val stateFile = File(rootfs, "root/.claude.json")
    if (stateFile.exists() && !(stateFile.canRead() && stateFile.canWrite())) {
        // Claude runs as root inside the chroot and may have rewritten the file as root-owned 0600.
        RootfsRepair.reclaimOwnership(stateFile, AndroidProcess.myUid())
    }
    val state = if (stateFile.exists()) {
        // Claude may be writing the file right now (another session); never replace a file that does not parse,
        // or the account, API-key approvals and project history in it would be lost.
        readClaudeState(stateFile) ?: run {
            Log.w("RuntimeInstaller", "Leaving ~/.claude.json untouched: it could not be parsed")
            return@synchronized
        }
    } else {
        JSONObject()
    }
    var changed = false
    listOf("disableAllHooks", "permissions", "hooks", "allowedTools", "autoApprove")
        .forEach { if (state.has(it)) { state.remove(it); changed = true } }
    if (!state.optBoolean("hasCompletedOnboarding", false)) {
        state.put("hasCompletedOnboarding", true)
        changed = true
    }
    val projects = state.optJSONObject("projects") ?: JSONObject().also { changed = true }
    val workspace = projects.optJSONObject(workspacePath) ?: JSONObject().also { changed = true }
    if (!workspace.optBoolean("hasTrustDialogAccepted", false)) {
        workspace.put("hasTrustDialogAccepted", true)
        changed = true
    }
    projects.put(workspacePath, workspace)
    state.put("projects", projects)
    val apiKeySuffix = environment["ANTHROPIC_API_KEY"]?.trim()?.takeIf { it.isNotEmpty() }?.takeLast(20)
    if (!apiKeySuffix.isNullOrBlank()) {
        val responses = state.optJSONObject("customApiKeyResponses") ?: JSONObject().also { changed = true }
        val approved = responses.optJSONArray("approved") ?: JSONArray().also { changed = true }
        val existing = (0 until approved.length()).mapNotNull { approved.optString(it).takeIf(String::isNotBlank) }.toSet()
        if (apiKeySuffix !in existing) {
            approved.put(apiKeySuffix)
            changed = true
        }
        responses.put("approved", approved)
        if (responses.optJSONArray("rejected") == null) {
            responses.put("rejected", JSONArray())
            changed = true
        }
        state.put("customApiKeyResponses", responses)
    }
    // Connectors the user added: the entries this app manages (prefix "forge-") follow the saved list, anything
    // else in mcpServers (added with `claude mcp add`) is left alone.
    if (mcpServers != null) {
        val existing = state.optJSONObject("mcpServers") ?: JSONObject()
        fun managed(source: JSONObject): String = source.keys().asSequence()
            .filter { it.startsWith(McpConnector.MANAGED_PREFIX) }.sorted()
            .joinToString("|") { key -> key + "=" + source.get(key) }
        if (managed(existing) != managed(mcpServers)) {
            existing.keys().asSequence().toList()
                .filter { it.startsWith(McpConnector.MANAGED_PREFIX) }
                .forEach { existing.remove(it) }
            mcpServers.keys().forEach { key -> existing.put(key, mcpServers.get(key)) }
            state.put("mcpServers", existing)
            changed = true
        }
    }
    // Every launch used to rewrite (and fsync) the file even when nothing changed; that is slow flash I/O and
    // can overwrite what a running Claude session wrote in the meantime.
    if (!changed && stateFile.exists()) return@synchronized
    writeRootfsFileAtomically(stateFile, state.toString())
}

/** Parses ~/.claude.json, retrying briefly in case Claude is in the middle of rewriting it. */
internal fun RuntimeInstaller.readClaudeState(stateFile: File): JSONObject? {
    repeat(3) { attempt ->
        val parsed = runCatching { JSONObject(stateFile.readText()) }.getOrNull()
        if (parsed != null) return parsed
        if (attempt < 2) Thread.sleep(150)
    }
    return null
}

/**
 * Writes [target] through a temp file in the same directory and an atomic rename, so a crash or a concurrent
 * reader never sees a truncated file. A root-owned target (rewritten by a root process in the chroot) is
 * reclaimed first.
 */
internal fun RuntimeInstaller.writeRootfsFileAtomically(target: File, content: String, mode: Int? = null) {
    val parent = checkNotNull(target.parentFile) { "No parent directory for ${target.name}" }
    parent.mkdirs()
    if (!parent.canWrite() || (target.exists() && !target.canWrite())) {
        RootfsRepair.reclaimOwnership(if (parent.canWrite()) target else parent, AndroidProcess.myUid())
    }
    val temp = File(parent, ".${target.name}.${AndroidProcess.myPid()}.${System.nanoTime()}.tmp")
    try {
        FileOutputStream(temp).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        // Set the mode before the rename so the file is never visible without its execute bit.
        if (mode != null) Os.chmod(temp.absolutePath, mode)
        Os.rename(temp.absolutePath, target.absolutePath)
    } catch (error: java.io.IOException) {
        // EACCES: the folder or file belongs to root (or carries a label the app may not write). Let root write it.
        val written = RootfsRepair.writeAsRoot(
            target, content.toByteArray(Charsets.UTF_8), File(downloads, ".root-write.tmp"), AndroidProcess.myUid(),
            mode ?: 0b110100100,
        )
        if (!written) throw error
    } finally {
        temp.delete()
    }
}

// Only auto-allow read-only tools. Bash/Edit/Write/NotebookEdit go through the
// PermissionRequest hook so the app can classify and gate each command.
internal fun RuntimeInstaller.claudeWorkspaceToolRules() = JSONArray().apply {
    put("Read")
    put("Glob")
    put("Grep")
    // Tools outside the permission hook (web, sub-agents, todos, skills) would otherwise be refused outright in a
    // headless session, since nobody can answer Claude's own prompt. With auto-approve on, nothing may be refused.
    if (runCatching { com.nextcraft.forge.data.AppPreferences(context).autoApproveAllTools }.getOrDefault(true)) {
        listOf("WebFetch", "WebSearch", "Task", "Agent", "TodoWrite", "Skill").forEach(::put)
    }
}

/**
 * dpkg (any `apt install` inside the chroot) re-applies base-files' mode 0700 root:root to /root, and files that
 * root creates there stay root's. The app UID is then locked out of /root and below: the version markers cannot be
 * read (REA shows "not installed"), `.claude` cannot be written, `agy` cannot be replaced. Folders are handed back
 * to the app UID (not recursively: root still reaches everything, and the per-file fallbacks cover the rest).
 */
internal fun RuntimeInstaller.ensureAppAccess() {
    val uid = AndroidProcess.myUid()
    for (relative in listOf("root", "root/.claude", "root/.gemini", "root/.gemini/config", "root/.gemini/antigravity-cli", "root/.local", "root/.local/bin", "etc")) {
        val dir = File(rootfs, relative)
        if (dir.isDirectory && (!dir.canWrite() || !dir.canExecute())) {
            RootfsRepair.reclaimOwnership(dir, uid, recursive = false)
        }
    }
}

/** Keeps the coloured prompt block in /root/.bashrc ([ShellPrompt]); an unreadable file is left alone. */
internal fun RuntimeInstaller.ensureShellPrompt() {
    ensureX86Wrapper()
    val bashrc = File(rootfs, "root/.bashrc")
    val current = runCatching { if (bashrc.isFile) bashrc.readText() else "" }.getOrNull() ?: return
    val updated = ShellPrompt.apply(current)
    if (updated != current) {
        runCatching { writeRootfsFileAtomically(bashrc, updated, mode = 0b110100100) }
            .onFailure { Log.w("RuntimeInstaller", "Could not write the shell prompt", it) }
    }
}

/** Installs `run-x86` (see [X86Wrapper]) in the guest; skipped while it is up to date. */
internal fun RuntimeInstaller.ensureX86Wrapper() {
    val target = File(rootfs, X86Wrapper.GUEST_PATH)
    if (runCatching { target.readText() }.getOrNull() == X86Wrapper.SCRIPT) return
    runCatching {
        target.parentFile?.mkdirs()
        writeRootfsFileAtomically(target, X86Wrapper.SCRIPT, mode = 0b111101101)
    }.onFailure { Log.w("RuntimeInstaller", "Could not write run-x86", it) }
}

internal fun RuntimeInstaller.writeResolver() {
    ensureAppAccess()
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val dns = manager?.getLinkProperties(manager.activeNetwork)?.dnsServers.orEmpty()
    val deviceServers = dns.mapNotNull { it.hostAddress }
    // glibc only reads the first MAXNS=3 "nameserver" lines, so the device's own servers are capped to 1 here --
    // otherwise two DoT-only device entries would push both public fallbacks off the end of the list.
    val fallbacks = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "8.8.4.4", "1.0.0.1")
    val servers = (deviceServers.take(1) + fallbacks).distinct().take(3)
    val resolvFile = File(rootfs, "etc/resolv.conf")
    resolvFile.parentFile?.mkdirs()
    if (java.nio.file.Files.isSymbolicLink(resolvFile.toPath())) {
        resolvFile.delete()
    }
    val resolver = servers.joinToString("\n") { "nameserver $it" } + "\noptions timeout:2 attempts:2 rotate\n"
    try {
        // A link that survived the delete above (not ours to unlink) would send the write into /run, which is root's.
        if (java.nio.file.Files.isSymbolicLink(resolvFile.toPath())) throw java.io.IOException("resolv.conf is still a symlink")
        resolvFile.writeText(resolver)
    } catch (error: java.io.IOException) {
        // /etc/resolv.conf is root-owned after the chroot supervisor (running as root) created it.
        if (!RootfsRepair.writeAsRoot(resolvFile, resolver.toByteArray(Charsets.UTF_8), File(downloads, ".root-write.tmp"), AndroidProcess.myUid())) {
            throw error
        }
    }
    ensureLocalhostInHosts()
}

/**
 * The Core rootfs ships an empty /etc/hosts, so `localhost` was resolved through DNS and failed. Anything that binds
 * or connects to localhost then breaks: the OAuth callback listener of Antigravity ("listen tcp: lookup localhost ...
 * no such host") and Claude Code logins, dev servers, Gradle and preview. Keeps whatever else the file holds.
 */
internal fun RuntimeInstaller.ensureLocalhostInHosts() {
    val hosts = File(rootfs, "etc/hosts")
    val current = runCatching { hosts.takeIf { it.isFile }?.readText().orEmpty() }.getOrDefault("")
    val hasLocalhost = current.lineSequence().any { line ->
        val fields = line.substringBefore('#').trim().split(Regex("\\s+"))
        fields.size >= 2 && fields[0] == "127.0.0.1" && "localhost" in fields.drop(1)
    }
    if (hasLocalhost) return
    val entries = "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n"
    runCatching { writeRootfsFileAtomically(hosts, entries + current.trimStart('\n')) }
        .onFailure { Log.w("RuntimeInstaller", "Could not write /etc/hosts", it) }
}
