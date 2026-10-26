package com.nextcraft.forge.runtime

import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

internal class NativeSpawnProcess private constructor(
    internal val pid: Int,
    internal val outputFile: File,
    private val stdin: OutputStream,
    private val ptyMasterFd: Int = -1,
    private val ptyFdLock: Any = Any(),
    private val isPtyFdOpen: () -> Boolean = { false },
    private val outputPump: Thread? = null,
    private val delegateProcess: Process? = null,
) : Process() {
    private val waitLock = Any()
    @Volatile private var result: Int? = null

    override fun getOutputStream(): OutputStream = stdin
    override fun getInputStream(): InputStream = FileInputStream(outputFile)
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    /**
     * Non-blocking exit check. The lock is held only for a WNOHANG waitpid, so isAlive/destroy
     * called from another thread are never stuck behind a thread that is waiting for exit.
     */
    private fun pollExit(): Int? {
        result?.let { return it }
        synchronized(waitLock) {
            result?.let { return it }
            val status = NativeSpawn.waitFor(pid, true)
            if (status == NativeSpawn.STILL_RUNNING) return null
            result = status
            return status
        }
    }

    override fun waitFor(): Int {
        result?.let { return it }
        val exitCode = if (delegateProcess != null) {
            delegateProcess.waitFor().also { result = it }
        } else {
            var polled = pollExit()
            while (polled == null) {
                Thread.sleep(EXIT_POLL_INTERVAL_MS)
                polled = pollExit()
            }
            polled
        }
        runCatching { stdin.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("NativeSpawnProcess", it) }
        outputPump?.join(1_000)
        return exitCode
    }

    override fun exitValue(): Int {
        result?.let { return it }
        if (delegateProcess != null) {
            val code = delegateProcess.exitValue()
            result = code
            runCatching { stdin.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("NativeSpawnProcess", it) }
            return code
        }
        val status = pollExit() ?: throw IllegalThreadStateException("Process is still running")
        runCatching { stdin.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("NativeSpawnProcess", it) }
        return status
    }

    /**
     * True once the child has exited (and been reaped). Signalling after that point could hit an unrelated process
     * group that reused the pid, and [NativeSpawn.kill] escalates to a root `kill` on EPERM, so every kill path checks.
     */
    private fun hasExited(): Boolean =
        if (delegateProcess != null) !delegateProcess.isAlive else pollExit() != null

    /** chroot-exec's pid on the root side (see [GuestProcess]); signalled together with the su client. */
    @Volatile internal var guestPidFile: File? = null

    override fun destroy() {
        if (hasExited()) return
        GuestProcess.signal(guestPidFile, 15)
        if (delegateProcess != null) {
            delegateProcess.destroy()
            killViaRootIfNeeded(pid, 15)
            return
        }
        // NativeSpawn.kill in forge_spawn.c automatically escalates via `su -c` if kill(-pid, 15) returns EPERM
        NativeSpawn.kill(pid, 15)
    }

    /** Send the same interrupt signal produced by Ctrl+C in a real terminal. */
    internal fun interrupt() {
        if (hasExited()) return
        GuestProcess.signal(guestPidFile, 2)
        if (delegateProcess != null) {
            killViaRootIfNeeded(pid, 2)
            return
        }
        NativeSpawn.kill(pid, 2)
    }

    override fun destroyForcibly(): Process {
        if (hasExited()) {
            runCatching { stdin.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("NativeSpawnProcess", it) }
            return this
        }
        GuestProcess.signal(guestPidFile, 15, forceAfterSeconds = 3)
        if (delegateProcess != null) {
            delegateProcess.destroyForcibly()
            killViaRootIfNeeded(pid, 9)
            runCatching { stdin.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("NativeSpawnProcess", it) }
            return this
        }
        NativeSpawn.kill(pid, 9)
        runCatching { stdin.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("NativeSpawnProcess", it) }
        return this
    }

    private fun killViaRootIfNeeded(targetPid: Int, sig: Int) {
        // Guard against -1 (all processes), 0 (current process group), and 1 (init).
        if (targetPid <= 1) return
        runCatching {
            val proc = ProcessBuilder("su", "-c", "kill -s $sig -$targetPid $targetPid 2>/dev/null || true").start()
            if (!proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly()
            }
        }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("NativeSpawnProcess", it) }
    }

    /** Dynamically resize the pseudo-terminal window when soft-keyboard or orientation changes. */
    internal fun resizePty(rows: Int, columns: Int): Int {
        if (delegateProcess != null || ptyMasterFd < 0) return -1
        synchronized(ptyFdLock) {
            if (!isPtyFdOpen()) return -1
            return NativeSpawn.resizePty(ptyMasterFd, rows, columns)
        }
    }

    override fun isAlive(): Boolean =
        delegateProcess?.isAlive ?: runCatching { exitValue(); false }.getOrDefault(true)

    companion object {
        private const val EXIT_POLL_INTERVAL_MS = 20L

        private fun extractProcessPid(process: Process): Int = runCatching {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(process)
        }.getOrDefault(-1)

        fun start(
            argv: List<String>,
            environment: Map<String, String>,
            cwd: String,
            outputFile: File,
            pseudoTerminal: Boolean = false,
            ptyRows: Int = 40,
            ptyColumns: Int = 120,
        ): NativeSpawnProcess {
            outputFile.parentFile?.mkdirs()
            if (pseudoTerminal || !outputFile.exists()) {
                outputFile.delete()
                outputFile.createNewFile()
            }
            if (!NativeSpawn.isAvailable) {
                val pb = ProcessBuilder(argv)
                val cwdFile = File(cwd)
                if (cwdFile.isDirectory) pb.directory(cwdFile)
                if (environment.isNotEmpty()) pb.environment().putAll(environment)
                pb.redirectErrorStream(true)
                pb.redirectOutput(ProcessBuilder.Redirect.to(outputFile))
                val fallback = pb.start()
                return NativeSpawnProcess(
                    pid = extractProcessPid(fallback),
                    outputFile = outputFile,
                    stdin = fallback.outputStream,
                    delegateProcess = fallback,
                )
            }
            val spawned = NativeSpawn.spawn(
                argv.toTypedArray(),
                environment.map { "${it.key}=${it.value}" }.toTypedArray(),
                cwd,
                outputFile.absolutePath,
                pseudoTerminal,
                ptyRows,
                ptyColumns,
            )
            check(spawned != null && spawned.size == 3 && spawned[0] > 0) { "Native Chroot runtime launch failed" }
            val input = ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(spawned[1]))
            val masterFd = spawned[2]
            val ptyLock = Any()
            var ptyOpen = masterFd >= 0
            val pump = masterFd.takeIf { it >= 0 }?.let { outputFd ->
                val pfd = ParcelFileDescriptor.adoptFd(outputFd)
                Thread({
                    try {
                        FileInputStream(pfd.fileDescriptor).use { source ->
                            FileOutputStream(outputFile, true).use { destination ->
                                val buffer = ByteArray(8 * 1024)
                                while (true) {
                                    val count = source.read(buffer)
                                    if (count <= 0) break
                                    destination.write(buffer, 0, count)
                                    destination.flush()
                                }
                            }
                        }
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } catch (e: OutOfMemoryError) {
                        throw e
                    } catch (_: java.io.IOException) {
                        // Normal EOF when child closes PTY slave FD
                    } finally {
                        synchronized(ptyLock) {
                            ptyOpen = false
                            runCatching { pfd.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("NativeSpawnProcess", it) }
                        }
                    }
                }, "nextcraft-pty-output").apply {
                    isDaemon = true
                    start()
                }
            }
            return NativeSpawnProcess(
                pid = spawned[0],
                outputFile = outputFile,
                stdin = input,
                ptyMasterFd = masterFd,
                ptyFdLock = ptyLock,
                isPtyFdOpen = { ptyOpen },
                outputPump = pump,
            )
        }
    }
}

private object NativeSpawn {
    const val STILL_RUNNING = -2

    val isAvailable: Boolean = runCatching {
        System.loadLibrary("forgespawn")
        true
    }.getOrDefault(false)

    external fun spawn(
        argv: Array<String>,
        environment: Array<String>,
        cwd: String,
        outputFile: String,
        pseudoTerminal: Boolean,
        ptyRows: Int,
        ptyColumns: Int,
    ): IntArray?

    external fun waitFor(pid: Int, noHang: Boolean): Int
    external fun kill(pid: Int, signal: Int): Int
    external fun resizePty(masterFd: Int, rows: Int, columns: Int): Int
}
