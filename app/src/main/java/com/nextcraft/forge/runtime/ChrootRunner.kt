package com.nextcraft.forge.runtime

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Result of a chroot command: exit code (-1 when killed or timed out) and the merged stdout/stderr text. */
data class ChrootResult(val exitCode: Int, val output: String, val timedOut: Boolean = false) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * Runs shell commands inside the Ubuntu chroot for the Tools screens (diagnostics, build matrix, SSH, ROM Studio).
 * Each command runs as `bash -lc` under the active engine (`su -mm` chroot, or PRoot) with a workspace at /workspace/<slug>.
 */
class ChrootRunner(context: Context) {
    private val appContext = context.applicationContext
    private val installer = RuntimeInstaller(appContext)

    /** Host directory for a tool workspace; the guest sees it as /workspace/[slug]. */
    fun toolWorkspace(slug: String): File = File(appContext.filesDir, "workspaces/$slug").apply { mkdirs() }

    fun isReady(): Boolean = installer.isInstalled()

    /** Starts [command] and returns the live process (stdout and stderr merged). The caller must read and destroy it. */
    fun start(workspaceDir: File, slug: String, command: String, environment: Map<String, String> = emptyMap()): Process {
        require(Regex("^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$").matches(slug)) { "Invalid workspace name" }
        check(installer.isInstalled()) { "The Linux runtime is not installed yet. Run the runtime setup first." }
        val (envFile, suCommand) = installer.prepareChrootShellCommand(
            workspace = workspaceDir,
            guestWorkspacePath = "/workspace/$slug",
            environment = environment,
            guestCommand = listOf("/bin/bash", "-lc", command),
        )
        val process = try {
            ProcessBuilder(installer.launchArgv(suCommand)).redirectErrorStream(true).start()
        } catch (error: Throwable) {
            envFile.delete()
            throw error
        }
        guestPidFiles[process] = GuestProcess.pidFileFor(envFile)
        return process
    }

    /**
     * Runs [command] to completion. Cancelling the calling coroutine kills the process; [timeoutMillis] kills it too.
     * [onChunk] receives output as it arrives (called on an IO thread).
     */
    suspend fun run(
        workspaceDir: File,
        slug: String,
        command: String,
        environment: Map<String, String> = emptyMap(),
        timeoutMillis: Long = 10 * 60_000L,
        maxChars: Int = MAX_OUTPUT_CHARS,
        onChunk: ((String) -> Unit)? = null,
    ): ChrootResult = withContext(Dispatchers.IO) {
        val process = start(workspaceDir, slug, command, environment)
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val completion = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) stop(process)
        }
        // The read below blocks until the command closes its output, so the timeout needs its own watchdog.
        val watchdog = Thread({
            try {
                if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    timedOut.set(true)
                    stop(process)
                }
            } catch (_: InterruptedException) {
            }
        }, "forge-chroot-watchdog").apply { isDaemon = true; start() }
        val output = StringBuilder()
        try {
            process.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    val text = String(buffer, 0, count)
                    if (output.length < maxChars) output.append(text.take(maxChars - output.length))
                    onChunk?.invoke(text)
                }
            }
            val exit = if (timedOut.get()) -1 else runCatching { process.waitFor() }.getOrDefault(-1)
            ChrootResult(exit, output.toString(), timedOut.get())
        } finally {
            watchdog.interrupt()
            completion?.dispose()
            if (process.isAlive) stop(process)
            guestPidFiles.remove(process)?.delete()
        }
    }

    /** Forgets a process started with [start] once it has exited, removing its pid file. */
    fun release(process: Process) {
        guestPidFiles.remove(process)?.delete()
    }

    /** Ends the su client and, through its pid file, chroot-exec and the command's process group on the root side. */
    fun stop(process: Process) {
        GuestProcess.signal(guestPidFiles[process], 15, forceAfterSeconds = 3)
        process.destroyForcibly()
    }

    private val guestPidFiles: MutableMap<Process, java.io.File> = java.util.Collections.synchronizedMap(java.util.WeakHashMap())

    companion object {
        const val MAX_OUTPUT_CHARS = 200_000

        /** POSIX single-quoting for values interpolated into a shell command. */
        fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
