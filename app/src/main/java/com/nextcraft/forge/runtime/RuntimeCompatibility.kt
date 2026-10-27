package com.nextcraft.forge.runtime

import java.util.concurrent.TimeUnit

internal fun supportsArm64Runtime(supportedAbis: Array<String>, osArchitecture: String?): Boolean {
    val kernelIsArm64 = osArchitecture.equals("aarch64", ignoreCase = true) ||
        osArchitecture.equals("arm64", ignoreCase = true)
    return kernelIsArm64 && supportedAbis.any { it.equals("arm64-v8a", ignoreCase = true) }
}

/**
 * Verifies that the device has working root access (`Magisk`, `KernelSU`, or `APatch`)
 * required by NextCraft Forge's Pure Root Chroot engine.
 */
internal fun isRootAccessAvailable(timeoutSeconds: Long = 8L): Boolean = runCatching {
    val proc = ProcessBuilder("su", "-c", "id -u")
        .redirectErrorStream(true)
        .start()
    val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!finished) {
        proc.destroyForcibly()
        return@runCatching false
    }
    val output = proc.inputStream.bufferedReader().use { it.readText().trim() }
    proc.exitValue() == 0 && output.lines().lastOrNull()?.trim() == "0"
}.getOrDefault(false)

/**
 * Disables Android 12–15+ Phantom Process Killer (`max_phantom_processes`) via `su`
 * so background Chroot daemon processes (`Gradle`, `Kotlin`, `Node`, `Claude Code`)
 * are not killed by `ActivityManager` under heavy compilation load.
 */
internal fun disablePhantomProcessKillerViaRoot() {
    if (!RuntimeEngines.isRootEngine()) return
    runCatching {
        val cmd = """
            /system/bin/device_config set_sync_disabled_for_tests persistent 2>/dev/null || true
            /system/bin/device_config put activity_manager max_phantom_processes 2147483647 2>/dev/null || true
            settings put global settings_enable_monitor_phantom_procs false 2>/dev/null || true
        """.trimIndent()
        val proc = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        if (!proc.waitFor(5L, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
        } else {
            runCatching { proc.inputStream.bufferedReader().use { it.readText() } }
        }
    }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeCompatibility", it) }
}

/**
 * Root managers differ in what `su` accepts. Magisk, KernelSU and APatch understand `-mm` (run in the global/master
 * mount namespace, so chroot mounts are shared and can be unmounted later); older SuperSU/ROM-integrated `su`
 * builds reject it and would fail every chroot launch. The result is probed once per process.
 */
internal object SuCompat {
    @Volatile private var masterMount: Boolean? = null

    /** `su -mm -c <command>` when supported, otherwise plain `su -c <command>`. */
    fun argv(command: String): List<String> =
        if (supportsMasterMount()) listOf("su", "-mm", "-c", command) else listOf("su", "-c", command)

    fun supportsMasterMount(): Boolean {
        masterMount?.let { return it }
        val supported = runCatching {
            val proc = ProcessBuilder("su", "-mm", "-c", "true").redirectErrorStream(true).start()
            if (!proc.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                // Most likely the grant dialog is still open: not evidence either way, so do not cache.
                proc.destroyForcibly()
                return true
            }
            runCatching { proc.inputStream.bufferedReader().use { it.readText() } }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeCompatibility", it) }
            proc.exitValue() == 0
        }.getOrElse { return true }
        masterMount = supported
        return supported
    }

    private const val PROBE_TIMEOUT_SECONDS = 10L
}

/** Facts about the ROM and root manager that explain most "works on one phone, not on another" reports. */
internal data class RomInfo(val rom: String, val rootManager: String, val selinux: String)

internal fun detectRomInfo(): RomInfo {
    val props = runCatching {
        val proc = ProcessBuilder("getprop").redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor(5L, TimeUnit.SECONDS)
        Regex("""\[([^\]]+)]: \[([^\]]*)]""").findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    }.getOrDefault(emptyMap())
    fun prop(key: String) = props[key].orEmpty()
    val rom = when {
        prop("ro.mi.os.version.name").isNotBlank() -> "HyperOS ${prop("ro.mi.os.version.name")}"
        prop("ro.miui.ui.version.name").isNotBlank() -> "MIUI ${prop("ro.miui.ui.version.name")}"
        prop("ro.build.version.oneui").isNotBlank() -> "One UI ${prop("ro.build.version.oneui")}"
        prop("ro.build.version.opporom").isNotBlank() -> "ColorOS/OxygenOS ${prop("ro.build.version.opporom")}"
        prop("ro.build.version.emui").isNotBlank() -> prop("ro.build.version.emui")
        prop("ro.lineage.version").isNotBlank() -> "LineageOS ${prop("ro.lineage.version")}"
        prop("ro.crdroid.version").isNotBlank() -> "crDroid ${prop("ro.crdroid.version")}"
        prop("ro.pixelexperience.version").isNotBlank() -> "PixelExperience ${prop("ro.pixelexperience.version")}"
        else -> prop("ro.build.display.id").ifBlank { "AOSP/unknown" }
    }
    if (!RuntimeEngines.isRootEngine()) {
        return RomInfo(rom, "PRoot (non-root engine active)", "unprobed")
    }
    val rootManager = runCatching {
        val proc = ProcessBuilder("su", "-c", "su -v; getenforce").redirectErrorStream(true).start()
        if (!proc.waitFor(8L, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return@runCatching "su unresponsive" to "unknown"
        }
        val lines = proc.inputStream.bufferedReader().use { it.readText() }.lines().map(String::trim).filter(String::isNotEmpty)
        (lines.getOrNull(0) ?: "unknown") to (lines.getOrNull(1) ?: "unknown")
    }.getOrDefault("unavailable" to "unknown")
    val masterMount = if (SuCompat.supportsMasterMount()) "su -mm: yes" else "su -mm: NO (fallback to plain su)"
    return RomInfo(rom, "${rootManager.first} ($masterMount)", rootManager.second)
}
