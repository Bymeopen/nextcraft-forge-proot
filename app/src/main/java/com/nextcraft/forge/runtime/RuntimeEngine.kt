package com.nextcraft.forge.runtime

import android.content.Context
import com.nextcraft.forge.data.AppPreferences
import java.io.File

/**
 * How guest commands reach the Ubuntu rootfs.
 *
 * [CHROOT] is the primary engine (ADR-002): `su -mm` + kernel mounts + `chroot-exec.sh`. [PROOT] is the
 * non-root fallback (ADR-007): the static `libproot.so` shipped in the APK translates paths with ptrace, so no
 * mounts, no root, and no `chown` are involved, at the cost of slower syscalls.
 */
enum class RuntimeEngine { CHROOT, PROOT }

/** What the user asked for in Settings; [AUTO] prefers root and falls back to PRoot. */
enum class RuntimeEngineChoice(val id: String) {
    AUTO("auto"), CHROOT("chroot"), PROOT("proot");

    companion object {
        fun fromStored(value: String?): RuntimeEngineChoice = entries.firstOrNull { it.id == value } ?: AUTO
    }
}

/**
 * Engine selection, cached per process: the first `su` call shows the Magisk/KernelSU/APatch dialog, so it must not
 * be repeated on every launch. Code that has no [Context] (RootfsRepair, native spawn) reads [current].
 */
object RuntimeEngines {
    const val PROOT_LIBRARY = "libproot.so"

    @Volatile
    private var detected: RuntimeEngine? = null

    @Volatile
    private var detectionDone = false

    const val PROOT_LOADER_LIBRARY = "libproot_loader.so"

    /** The PRoot binary lives in the APK's native library directory: the only place Android lets an app exec from. */
    fun prootBinary(context: Context): File = File(context.applicationInfo.nativeLibraryDir, PROOT_LIBRARY)

    /** proot's ELF loader, which every guest execve runs through; it must be exec-able too, so it ships beside proot. */
    fun prootLoader(context: Context): File = File(context.applicationInfo.nativeLibraryDir, PROOT_LOADER_LIBRARY)

    fun isProotAvailable(context: Context): Boolean =
        prootBinary(context).let { it.isFile && it.canExecute() } && prootLoader(context).let { it.isFile && it.canExecute() }

    /**
     * Pure decision, kept separate from the probes so it can be unit-tested: which engine a [choice] yields given
     * whether root was granted and whether the PRoot binary exists. `null` means nothing can run.
     */
    fun resolve(choice: RuntimeEngineChoice, rootGranted: Boolean, prootAvailable: Boolean): RuntimeEngine? = when (choice) {
        RuntimeEngineChoice.CHROOT -> RuntimeEngine.CHROOT.takeIf { rootGranted }
        RuntimeEngineChoice.PROOT -> RuntimeEngine.PROOT.takeIf { prootAvailable }
        RuntimeEngineChoice.AUTO -> when {
            rootGranted -> RuntimeEngine.CHROOT
            prootAvailable -> RuntimeEngine.PROOT
            else -> null
        }
    }

    /** Applies the stored choice; a forced PRoot never calls `su`, so no root dialog appears on a rooted phone. */
    fun detect(context: Context, rootTimeoutSeconds: Long = 8L, refresh: Boolean = false): RuntimeEngine? {
        if (detectionDone && !refresh) return detected
        val choice = RuntimeEngineChoice.fromStored(AppPreferences(context).runtimeEngine)
        val rootGranted = choice != RuntimeEngineChoice.PROOT && isRootAccessAvailable(rootTimeoutSeconds)
        val engine = resolve(choice, rootGranted, isProotAvailable(context))
        detected = engine
        detectionDone = true
        return engine
    }

    /** Forgets the cached result, e.g. after the user changes the engine in Settings. */
    fun invalidate() {
        detectionDone = false
        detected = null
    }

    /** Last detected engine; CHROOT until a detection ran, which keeps the root-only paths unchanged for callers without a context. */
    fun current(): RuntimeEngine = detected ?: RuntimeEngine.CHROOT

    fun isRootEngine(): Boolean = current() == RuntimeEngine.CHROOT

    /**
     * Process arguments that run [shellCommand] (the `. env; rm -f env; exec …` line built by
     * [RuntimeInstaller.prepareChrootShellCommand]) under [engine].
     */
    fun launchArgv(shellCommand: String, engine: RuntimeEngine = current()): List<String> = when (engine) {
        RuntimeEngine.CHROOT -> listOf("su", "-mm", "-c", shellCommand)
        RuntimeEngine.PROOT -> listOf("/system/bin/sh", "-c", shellCommand)
    }

    /** Test hook: pretend a detection produced [engine]. */
    internal fun overrideForTests(engine: RuntimeEngine?) {
        detected = engine
        detectionDone = engine != null
    }
}
