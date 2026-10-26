package com.nextcraft.forge.runtime

import android.app.ActivityManager
import android.content.Context
import com.nextcraft.forge.R
import com.nextcraft.forge.ui.UiText
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The integer after `key:` in /proc/<pid>/status text ("Threads:\t48" gives 48), or null when absent. */
internal fun parseProcStatus(text: String, key: String): Int? =
    text.lineSequence().firstOrNull { it.startsWith("$key:") }
        ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toIntOrNull()

data class DiagnosticsState(
    val device: List<Pair<UiText, UiText>> = emptyList(),
    val runtime: List<Pair<UiText, UiText>> = emptyList(),
    val healthOutput: String = "",
    val healthRunning: Boolean = false,
    val healthSummary: String? = null,
    /** Errors the app caught and carried on from, newest first (see [ErrorLog]). */
    val swallowedErrors: String = "",
)

/** System diagnostics: device resources, runtime state and the chroot health check (chroot-health.sh). */
class DiagnosticsController(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val appContext = context.applicationContext
    private val runner = ChrootRunner(appContext)
    private val installer = RuntimeInstaller(appContext)
    private val mutable = MutableStateFlow(DiagnosticsState())
    val state: StateFlow<DiagnosticsState> = mutable.asStateFlow()
    private var healthJob: Job? = null

    fun refresh() {
        scope.launch {
            val device = withContext(Dispatchers.IO) { collectDevice() }
            val runtime = withContext(Dispatchers.IO) { collectRuntime() }
            mutable.update { it.copy(device = device, runtime = runtime, swallowedErrors = ErrorLog.asText()) }
        }
    }

    fun runHealthCheck() {
        if (mutable.value.healthRunning) return
        mutable.update { it.copy(healthOutput = "", healthRunning = true, healthSummary = null) }
        healthJob = scope.launch {
            val result = runCatching {
                val workspace = runner.toolWorkspace(WORKSPACE_SLUG)
                File(workspace, "chroot-health.sh").writeText(
                    appContext.assets.open("chroot-health.sh").bufferedReader().use { it.readText() },
                )
                runner.run(
                    workspaceDir = workspace,
                    slug = WORKSPACE_SLUG,
                    command = "bash /workspace/$WORKSPACE_SLUG/chroot-health.sh 2>&1",
                    timeoutMillis = 8 * 60_000L,
                    onChunk = { chunk -> mutable.update { it.copy(healthOutput = (it.healthOutput + chunk).takeLast(MAX_LOG_CHARS)) } },
                )
            }
            mutable.update { current ->
                result.fold(
                    onSuccess = { current.copy(healthRunning = false, healthSummary = current.healthOutput.lineSequence().lastOrNull { it.startsWith("Selesai") }) },
                    onFailure = { current.copy(healthRunning = false, healthOutput = current.healthOutput + "\n" + (it.message ?: appContext.getString(R.string.diag_health_failed))) },
                )
            }
        }
    }

    fun cancelHealthCheck() {
        healthJob?.cancel()
        mutable.update { it.copy(healthRunning = false) }
    }

    private fun collectDevice(): List<Pair<UiText, UiText>> {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
        val stat = runCatching { StatFs(appContext.filesDir.absolutePath) }.getOrNull()
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val charging = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            ?.let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL } == true
        val hwProfile = runCatching { DeviceHardwareOptimizer.inspect(appContext, forceRefresh = true) }.getOrNull()
        return buildList {
            add(row(R.string.diag_device, UiText.Raw("${Build.MANUFACTURER} ${Build.MODEL}")))
            add(row(R.string.diag_android, UiText.Raw("${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")))
            if (hwProfile != null) {
                add(UiText.Raw("SoC / Profile") to UiText.Raw("${hwProfile.socVendor.displayName} (${hwProfile.socModel}) · ${hwProfile.socTier.label}"))
                add(
                    row(
                        R.string.diag_cpu,
                        UiText.Raw(
                            "${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()} · ${hwProfile.topology.totalCores} cores " +
                                "(${hwProfile.topology.performanceCores}P+${hwProfile.topology.efficiencyCores}E)",
                        ),
                    ),
                )
            } else {
                add(row(R.string.diag_cpu, UiText.res(R.string.diag_cores, Build.SUPPORTED_ABIS.firstOrNull().orEmpty(), Runtime.getRuntime().availableProcessors())))
            }
            add(row(R.string.diag_ram, UiText.res(R.string.diag_free_of, formatBytes(memory.availMem), formatBytes(memory.totalMem))))
            if (hwProfile != null && hwProfile.swap.hasActiveSwap) {
                val algSuffix = hwProfile.swap.zramAlgorithm?.let { " ($it)" }.orEmpty()
                add(
                    UiText.Raw("SwapRAM / ZRAM") to UiText.Raw(
                        "${formatBytes(hwProfile.swap.swapFreeMb * 1024L * 1024L)} free of ${formatBytes(hwProfile.swap.swapTotalMb * 1024L * 1024L)}$algSuffix",
                    ),
                )
            }
            // What this app itself costs right now: the numbers to compare before and after an optimisation.
            appMemoryMb()?.let { (pss, heap) -> add(row(R.string.diag_app_memory, UiText.res(R.string.diag_app_memory_value, pss, heap))) }
            procStatusValue("Threads")?.let { add(row(R.string.diag_app_threads, UiText.Raw(it.toString()))) }
            if (stat != null) add(row(R.string.diag_storage, UiText.res(R.string.diag_free_of, formatBytes(stat.availableBytes), formatBytes(stat.totalBytes))))
            if (level >= 0 && scale > 0) {
                val percent = UiText.Raw("${level * 100 / scale}%")
                add(row(R.string.diag_battery, if (charging) UiText.res(R.string.diag_charging_value, "${level * 100 / scale}%") else percent))
            }
        }
    }

    private fun collectRuntime(): List<Pair<UiText, UiText>> {
        val rootfs = RuntimeInstaller.rootfsDir(appContext)
        val installed = runCatching { installer.isInstalled() }.getOrDefault(false)
        return buildList {
            val root = runCatching { isRootAccessAvailable() }.getOrDefault(false)
            add(row(R.string.diag_root, UiText.res(if (root) R.string.diag_granted else R.string.diag_unavailable)))
            add(UiText.Raw("Runtime engine") to UiText.Raw(runCatching { installer.runtimeEngine() }.getOrNull()?.name?.lowercase() ?: "none"))
            if (root) {
                runCatching { detectRomInfo() }.getOrNull()?.let { info ->
                    add(UiText.Raw("ROM") to UiText.Raw(info.rom))
                    add(UiText.Raw("Root manager") to UiText.Raw(info.rootManager))
                    add(UiText.Raw("SELinux") to UiText.Raw(info.selinux))
                }
            }
            runCatching {
                val unrestricted = BatteryOptimization.isUnrestricted(appContext)
                add(UiText.Raw("Battery optimization") to UiText.Raw(if (unrestricted) "Unrestricted" else "Optimized (some ROMs stop background builds)"))
            }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("DiagnosticsController", it) }
            add(row(R.string.diag_linux, UiText.res(if (installed) R.string.diag_installed else R.string.diag_not_installed)))
            runCatching { RuntimeMarkers.read(File(rootfs, ".nextcraft-rootfs-version"))?.trim() }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { add(row(R.string.diag_rootfs, UiText.Raw(it))) }
            runCatching {
                val hw = DeviceHardwareOptimizer.inspect(appContext)
                val prootCfg = ProotAndChrootOptimizer.buildConfig(hw, hasRootAccess = root)
                val pageKb = prootCfg.kernelProfile.pageSizeBytes / 1024
                add(
                    UiText.Raw("Android Kernel profile") to UiText.Raw(
                        "${prootCfg.kernelProfile.rawRelease} · ${prootCfg.kernelProfile.family.shortBadge} · ${pageKb}KB pages",
                    ),
                )
                add(
                    UiText.Raw("Container / PRoot optimization") to UiText.Raw(
                        "${prootCfg.backend.displayName} · /dev/shm ${prootCfg.shmSizeMb}MB · /tmp ${prootCfg.tmpSizeMb}MB · UV threads ${prootCfg.uvThreadpoolSize}",
                    ),
                )
            }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("DiagnosticsController", it) }
            if (installed) {
                // The marker REA's installer writes; the version says whether the Learn/firmware/plugin features are there.
                val rea = runCatching { RuntimeMarkers.read(File(rootfs, "root/.nextcraft-rea-version"))?.trim() }.getOrNull()?.takeIf { it.isNotBlank() }
                add(row(R.string.diag_rea, rea?.let { UiText.Raw("v$it") } ?: UiText.res(R.string.diag_not_installed)))
                val stacks = runCatching { installer.installedStacks().joinToString { it.label } }.getOrDefault("")
                add(row(R.string.diag_stacks, stacks.ifBlank { null }?.let(UiText::Raw) ?: UiText.res(R.string.diag_none)))
                val agents = runCatching { installer.installedAgentVersions().entries.joinToString { "${it.key.title} ${it.value}" } }.getOrDefault("")
                add(row(R.string.diag_agents, agents.ifBlank { null }?.let(UiText::Raw) ?: UiText.res(R.string.diag_none)))
            }
        }
    }

    /** (proportional memory in MB as Android counts it, Java heap in use in MB) for this process. */
    private fun appMemoryMb(): Pair<Long, Long>? = runCatching {
        val info = context.getSystemService(ActivityManager::class.java)
            ?.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))?.firstOrNull() ?: return null
        val heap = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) shr 20 }
        (info.totalPss / 1024L) to heap
    }.getOrNull()

    private fun procStatusValue(key: String): Int? =
        runCatching { parseProcStatus(File("/proc/self/status").readText(), key) }.getOrNull()

    private fun row(@androidx.annotation.StringRes label: Int, value: UiText): Pair<UiText, UiText> = UiText.res(label) to value

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        else -> "%d MB".format(bytes / (1L shl 20))
    }

    private companion object {
        const val WORKSPACE_SLUG = "diagnostics"
        const val MAX_LOG_CHARS = 60_000
    }
}
