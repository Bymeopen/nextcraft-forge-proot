package com.nextcraft.forge.runtime

import androidx.core.content.edit
import android.content.Context
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class SshHost(
    val id: String,
    val alias: String,
    val user: String,
    val host: String,
    val port: Int,
    /** Optional local forward "localPort:remoteHost:remotePort" used by the tunnel. */
    val forward: String,
)

data class SshState(
    val hosts: List<SshHost> = emptyList(),
    val tunnels: Set<String> = emptySet(),
    val clientInstalled: Boolean? = null,
    val publicKey: String? = null,
    val log: String = "",
    val busy: Boolean = false,
)

// No leading "-": a user or host like "-oProxyCommand=..." would be parsed by ssh as an option (command injection).
internal val SSH_NAME = Regex("[A-Za-z0-9_][A-Za-z0-9._-]{0,99}")
internal val SSH_FORWARD = Regex("\\d{1,5}:[A-Za-z0-9._-]{1,100}:\\d{1,5}")

/**
 * SSH manager. The terminal in this app is not interactive, so sessions are key-based and non-interactive:
 * test a connection, run a remote command, or hold a port-forward tunnel open. The key pair lives in the chroot.
 */
class SshController(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val appContext = context.applicationContext
    private val runner = ChrootRunner(appContext)
    private val prefs = appContext.getSharedPreferences("forge_tools", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(SshState(hosts = readHosts()))
    val state: StateFlow<SshState> = mutable.asStateFlow()
    private val tunnelProcesses = java.util.concurrent.ConcurrentHashMap<String, Process>()
    /** Hosts whose tunnel is being started: a second tap before the process exists must not start another one. */
    private val tunnelStarting = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var job: Job? = null

    private fun workspace() = runner.toolWorkspace(SLUG)

    fun saveHost(alias: String, user: String, host: String, port: Int, forward: String): String? {
        val cleanForward = forward.trim()
        when {
            alias.isBlank() -> return "Give the host a name."
            !SSH_NAME.matches(user.trim()) -> return "User may only contain letters, digits, . _ -"
            !SSH_NAME.matches(host.trim()) -> return "Host may only contain letters, digits, . _ -"
            port !in 1..65535 -> return "Port must be 1-65535."
            cleanForward.isNotEmpty() && !SSH_FORWARD.matches(cleanForward) -> return "Forward must look like 8080:localhost:80."
        }
        val entry = SshHost(UUID.randomUUID().toString(), alias.trim().take(40), user.trim(), host.trim(), port, cleanForward)
        val hosts = mutable.value.hosts + entry
        writeHosts(hosts)
        mutable.update { it.copy(hosts = hosts) }
        return null
    }

    fun removeHost(id: String) {
        stopTunnel(id)
        val hosts = mutable.value.hosts.filterNot { it.id == id }
        writeHosts(hosts)
        mutable.update { it.copy(hosts = hosts) }
    }

    fun checkClient() = launchTask("Checking the SSH client…") {
        val result = exec("command -v ssh >/dev/null 2>&1 && ssh -V 2>&1", quiet = true)
        mutable.update { it.copy(clientInstalled = result.ok, log = append(it.log, if (result.ok) result.output else "OpenSSH client is not installed.\n")) }
    }

    fun installClient() = launchTask("Installing the OpenSSH client…") {
        // The log goes through a file so the exit status is apt's, not tail's; the package lists are refreshed first
        // because the runtime removes them after every install.
        val result = exec(
            "{ DEBIAN_FRONTEND=noninteractive apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y openssh-client; } " +
                ">/tmp/forge-apt.log 2>&1; rc=\$?; tail -n 15 /tmp/forge-apt.log; exit \$rc",
            timeoutMillis = 15 * 60_000L,
        )
        mutable.update { it.copy(clientInstalled = result.ok) }
        checkClientInline()
    }

    private suspend fun checkClientInline() {
        val result = exec("command -v ssh >/dev/null 2>&1", quiet = true)
        mutable.update { it.copy(clientInstalled = result.ok) }
    }

    fun loadOrCreateKey() = launchTask("Preparing the SSH key…") {
        val result = exec(
            "mkdir -p ~/.ssh && chmod 700 ~/.ssh && { [ -f ~/.ssh/id_ed25519 ] || ssh-keygen -q -t ed25519 -N '' -C forge@android -f ~/.ssh/id_ed25519; } && cat ~/.ssh/id_ed25519.pub",
            quiet = true,
        )
        val key = result.output.lineSequence().firstOrNull { it.startsWith("ssh-") }?.trim()
        mutable.update { it.copy(publicKey = key, log = append(it.log, if (key != null) "Public key ready; add it to ~/.ssh/authorized_keys on the server.\n" else result.output)) }
    }

    fun testConnection(host: SshHost) = launchTask("Connecting to ${host.alias}…") {
        exec("${sshBase(host)} ${target(host)} 'echo connected; uname -a'")
    }

    fun runRemote(host: SshHost, command: String) {
        val clean = command.trim().take(1_000)
        if (clean.isEmpty()) return
        launchTask("Running on ${host.alias}…") { exec("${sshBase(host)} ${target(host)} ${ChrootRunner.quote(clean)}") }
    }

    fun startTunnel(host: SshHost) {
        if (host.forward.isEmpty() || tunnelProcesses.containsKey(host.id) || !tunnelStarting.add(host.id)) return
        scope.launch {
            val command = "${sshBase(host)} -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -L ${ChrootRunner.quote(host.forward)} ${target(host)}"
            val process = runCatching { runner.start(workspace(), SLUG, command) }.getOrElse { error ->
                tunnelStarting.remove(host.id)
                mutable.update { it.copy(log = append(it.log, "✕ ${error.message}\n")) }
                return@launch
            }
            tunnelProcesses[host.id] = process
            tunnelStarting.remove(host.id)
            mutable.update { it.copy(tunnels = it.tunnels + host.id, log = append(it.log, "▶ Tunnel ${host.forward} via ${host.alias}\n")) }
            runCatching {
                process.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(2048)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        val text = String(buffer, 0, count)
                        mutable.update { it.copy(log = append(it.log, text)) }
                    }
                }
            }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("SshController", it) }
            runner.release(process)
            // Only this process's own entry: after Stop and a quick Start the map already holds the new tunnel.
            tunnelProcesses.remove(host.id, process)
            if (!tunnelProcesses.containsKey(host.id)) {
                mutable.update { it.copy(tunnels = it.tunnels - host.id, log = append(it.log, "■ Tunnel ${host.alias} closed\n")) }
            }
        }
    }

    fun stopTunnel(id: String) {
        val process = tunnelProcesses.remove(id) ?: return
        runner.stop(process)
        // Killing the su wrapper does not always reach the ssh client inside the chroot; stop it there as well.
        val forward = mutable.value.hosts.firstOrNull { it.id == id }?.forward.orEmpty()
        if (forward.isNotEmpty()) {
            scope.launch {
                // "[s]sh" keeps pkill from matching this very shell command line.
                // pkill takes a POSIX ERE: Regex.escape's \Q..\E is Java-only and never matched. The forward only
                // holds [A-Za-z0-9._:-] (SSH_FORWARD), so escaping the dot is enough.
                val escapedForward = forward.replace(".", "\\.")
                runCatching { runner.run(workspace(), SLUG, "pkill -f ${ChrootRunner.quote("[s]sh .* -N .*-L $escapedForward ")} || true", timeoutMillis = 15_000L) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("SshController", it) }
            }
        }
    }

    private fun sshBase(host: SshHost) =
        "ssh -o BatchMode=yes -o ConnectTimeout=12 -o StrictHostKeyChecking=accept-new -p ${host.port}"

    private fun target(host: SshHost) = "${host.user}@${host.host}"

    private suspend fun exec(command: String, quiet: Boolean = false, timeoutMillis: Long = 60_000L): ChrootResult {
        val result = runCatching {
            runner.run(workspace(), SLUG, command, timeoutMillis = timeoutMillis) { chunk ->
                if (!quiet) mutable.update { it.copy(log = append(it.log, chunk)) }
            }
        }.getOrElse { error ->
            // Cancel (user pressed Cancel) must stop the task, not be reported as an ordinary failure.
            if (error is CancellationException) throw error
            mutable.update { it.copy(log = append(it.log, "✕ ${error.message}\n")) }
            ChrootResult(-1, error.message.orEmpty())
        }
        if (!quiet) mutable.update { it.copy(log = append(it.log, if (result.ok) "✓ done\n" else "✕ exit ${result.exitCode}${if (result.timedOut) " (timed out)" else ""}\n")) }
        return result
    }

    private fun launchTask(title: String, block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, log = append(it.log, "\n$title\n")) }
        job = scope.launch {
            try {
                block()
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun stopAllTunnels() {
        job?.cancel()
        tunnelStarting.clear()
        tunnelProcesses.keys.toList().forEach { id ->
            tunnelProcesses.remove(id)?.let { runner.stop(it) }
        }
        mutable.update { it.copy(tunnels = emptySet(), busy = false) }
    }

    private fun append(current: String, text: String) = (current + text).takeLast(MAX_LOG_CHARS)

    private fun readHosts(): List<SshHost> = runCatching {
        val array = JSONArray(prefs.getString("ssh_hosts", "[]") ?: "[]")
        (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let {
                SshHost(it.optString("id"), it.optString("alias"), it.optString("user"), it.optString("host"), it.optInt("port", 22), it.optString("forward"))
            }?.takeIf { h -> h.id.isNotBlank() && SSH_NAME.matches(h.user) && SSH_NAME.matches(h.host) && h.port in 1..65535 }
        }
    }.getOrDefault(emptyList())

    private fun writeHosts(hosts: List<SshHost>) {
        val array = JSONArray(hosts.map { JSONObject().put("id", it.id).put("alias", it.alias).put("user", it.user).put("host", it.host).put("port", it.port).put("forward", it.forward) })
        prefs.edit {putString("ssh_hosts", array.toString())}
    }

    private companion object {
        const val SLUG = "ssh"
        const val MAX_LOG_CHARS = 60_000
    }
}
