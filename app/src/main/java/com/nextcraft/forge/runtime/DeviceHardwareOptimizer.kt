package com.nextcraft.forge.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.util.Locale

/**
 * Hardware-aware optimizer for all Android SoC families (Qualcomm Snapdragon, MediaTek Dimensity/Helio,
 * UNISOC Tiger/T-Series, Samsung Exynos, Google Tensor, HiSilicon Kirin, and generic ARM64),
 * all marketed RAM tiers (4 GB, 6 GB, 8 GB, 10 GB, 12 GB, 16 GB+), and built-in phone SwapRAM / ZRAM / Extended RAM.
 */
object DeviceHardwareOptimizer {

    enum class SocVendor(val displayName: String, val shortBadge: String) {
        SNAPDRAGON("Qualcomm Snapdragon", "Snapdragon"),
        MEDIATEK("MediaTek Dimensity / Helio", "MediaTek"),
        UNISOC("UNISOC Tiger / T-Series", "UNISOC"),
        EXYNOS("Samsung Exynos", "Exynos"),
        TENSOR("Google Tensor", "Tensor"),
        KIRIN("HiSilicon Kirin", "Kirin"),
        GENERIC_ARM64("ARM64 Multi-Core", "ARM64"),
    }

    enum class SocTier(val label: String) {
        ENTRY_EFFICIENCY("Entry / 2P+6E Efficiency"),
        BALANCED_MID("Balanced Mid-Range"),
        FLAGSHIP_PERFORMANCE("Flagship Performance"),
    }

    enum class RamTier(val marketedGb: Int, val label: String) {
        RAM_4GB(4, "4 GB"),
        RAM_6GB(6, "6 GB"),
        RAM_8GB(8, "8 GB"),
        RAM_10GB(10, "10 GB"),
        RAM_12GB(12, "12 GB"),
        RAM_16GB_PLUS(16, "16 GB+"),
    }

    data class CoreTopology(
        val totalCores: Int,
        val performanceCores: Int,
        val efficiencyCores: Int,
    )

    data class SwapProfile(
        val swapTotalMb: Long,
        val swapFreeMb: Long,
        val zramAlgorithm: String? = null,
    ) {
        val swapUsedMb: Long
            get() = (swapTotalMb - swapFreeMb).coerceAtLeast(0L)

        val hasActiveSwap: Boolean
            get() = swapTotalMb >= 256L
    }

    data class ParsedMemInfo(
        val totalRamMb: Long,
        val availRamMb: Long,
        val swapTotalMb: Long,
        val swapFreeMb: Long,
    )

    data class GradleTuning(
        val heapMb: Long,
        val metaspaceMb: Int,
        val daemonEnabled: Boolean,
        val daemonIdleTimeoutMs: Long,
        val maxWorkers: Int,
        val parallelExecution: Boolean,
    )

    data class HardwareProfile(
        val socVendor: SocVendor,
        val socModel: String,
        val socTier: SocTier,
        val topology: CoreTopology,
        val ramTier: RamTier,
        val totalRamMb: Long,
        val availRamMb: Long,
        val swap: SwapProfile,
    )

    @Volatile
    private var cachedProfile: HardwareProfile? = null

    @Volatile
    private var lastInspectMs: Long = 0L

    /**
     * Classifies marketed RAM tier from Android-reported `MemTotal` / `ActivityManager.MemoryInfo.totalMem` in MB.
     * Accounts for hardware carveouts (modem, GPU, camera, trustzone) where:
     * - 4 GB phone reports ~3,400–4,200 MB
     * - 6 GB phone reports ~5,100–6,200 MB
     * - 8 GB phone reports ~7,000–8,200 MB
     * - 10 GB phone reports ~8,800–9,900 MB
     * - 12 GB phone reports ~10,500–12,500 MB
     * - 16 GB+ phone reports ~13,500+ MB
     */
    fun classifyRamTier(totalMemoryMb: Long): RamTier = when {
        totalMemoryMb >= 13_500L -> RamTier.RAM_16GB_PLUS
        totalMemoryMb >= 10_000L -> RamTier.RAM_12GB
        totalMemoryMb >= 8_500L -> RamTier.RAM_10GB
        totalMemoryMb >= 6_800L -> RamTier.RAM_8GB
        totalMemoryMb >= 4_500L -> RamTier.RAM_6GB
        else -> RamTier.RAM_4GB
    }

    /**
     * Detects SoC vendor across Qualcomm Snapdragon, MediaTek (Dimensity / Helio), UNISOC (Tiger / Spreadtrum),
     * Samsung Exynos, Google Tensor, and HiSilicon Kirin from build properties and `/proc/cpuinfo`.
     */
    fun detectSocVendor(
        socManufacturer: String = "",
        socModel: String = "",
        hardware: String = "",
        board: String = "",
        cpuInfoText: String = "",
    ): SocVendor {
        val combined = listOf(socManufacturer, socModel, hardware, board, cpuInfoText)
            .joinToString(" ")
            .lowercase(Locale.US)

        return when {
            combined.contains("unisoc") ||
                combined.contains("spreadtrum") ||
                Regex("\\b(ums\\d+|sp9\\d+|sc9\\d+|t606|t610|t612|t616|t618|t620|t700|t760|t820)\\b").containsMatchIn(combined) ->
                SocVendor.UNISOC

            combined.contains("mediatek") ||
                combined.contains("dimensity") ||
                combined.contains("helio") ||
                Regex("\\b(mt6\\d{3}|mt8\\d{3}|mt68\\d{2}|mt69\\d{2}|mt67\\d{2})\\b").containsMatchIn(combined) ->
                SocVendor.MEDIATEK

            combined.contains("qualcomm") ||
                combined.contains("snapdragon") ||
                combined.contains("qcom") ||
                Regex("\\b(sm8\\d{3}|sm7\\d{3}|sm6\\d{3}|sm4\\d{3}|sdm\\d{3}|msm\\d{4}|lahaina|taro|kalama|pineapple|sun)\\b").containsMatchIn(combined) ->
                SocVendor.SNAPDRAGON

            combined.contains("tensor") ||
                Regex("\\b(gs101|gs201|gs301|zuma|zumapro|laguna)\\b").containsMatchIn(combined) ->
                SocVendor.TENSOR

            combined.contains("samsung") ||
                combined.contains("exynos") ||
                Regex("\\b(exynos\\d+|s5e\\d{4}|universal\\d{4})\\b").containsMatchIn(combined) ->
                SocVendor.EXYNOS

            combined.contains("hisilicon") ||
                combined.contains("kirin") ||
                Regex("\\b(kirin\\d+|hi36\\d{2}|hi62\\d{2})\\b").containsMatchIn(combined) ->
                SocVendor.KIRIN

            else -> SocVendor.GENERIC_ARM64
        }
    }

    /**
     * Analyzes CPU core max frequencies (in kHz) from `/sys/devices/system/cpu/cpuX/cpufreq/cpuinfo_max_freq`
     * to distinguish performance/prime cores from little/efficiency cores on big.LITTLE / DynamIQ SoCs.
     */
    fun detectCoreTopology(maxFreqKhzList: List<Long>, fallbackCores: Int = 8): CoreTopology {
        val total = if (maxFreqKhzList.isNotEmpty()) maxFreqKhzList.size else fallbackCores.coerceAtLeast(1)
        if (maxFreqKhzList.size < 2) {
            val perf = (total / 2).coerceAtLeast(1)
            return CoreTopology(total, perf, (total - perf).coerceAtLeast(0))
        }
        val minFreq = maxFreqKhzList.minOrNull() ?: 0L
        val maxFreq = maxFreqKhzList.maxOrNull() ?: 0L
        if (minFreq <= 0L || maxFreq - minFreq < (maxFreq * 8L / 100L)) {
            val perf = (total / 2).coerceAtLeast(2).coerceAtMost(total)
            return CoreTopology(total, perf, (total - perf).coerceAtLeast(0))
        }
        val threshold = minFreq + (minFreq * 8L / 100L)
        val perfCores = maxFreqKhzList.count { it >= threshold }.coerceIn(1, total)
        val effCores = (total - perfCores).coerceAtLeast(0)
        return CoreTopology(total, perfCores, effCores)
    }

    fun classifySocTier(
        vendor: SocVendor,
        socModel: String,
        topology: CoreTopology,
    ): SocTier {
        val modelLower = socModel.lowercase(Locale.US)
        if (vendor == SocVendor.UNISOC ||
            modelLower.contains("helio") ||
            Regex("\\b(mt67\\d{2}|sm6225|sm6115|sdm660|sc9863a|t606|t612|t616|t618)\\b").containsMatchIn(modelLower) ||
            (topology.totalCores >= 6 && topology.performanceCores <= 2)
        ) {
            return SocTier.ENTRY_EFFICIENCY
        }
        if (Regex("\\b(sm8\\d{3}|mt69\\d{2}|dimensity\\s*9\\d{3}|exynos\\s*2[245]00|s5e99\\d{2}|tensor|zuma|gs[23]01|kirin\\s*90\\d{2})\\b").containsMatchIn(modelLower) ||
            topology.performanceCores >= 5
        ) {
            return SocTier.FLAGSHIP_PERFORMANCE
        }
        return SocTier.BALANCED_MID
    }

    fun parseMemInfo(
        memInfoText: String,
        fallbackTotalRamMb: Long = 4_096L,
        fallbackAvailRamMb: Long = 1_536L,
    ): ParsedMemInfo {
        var memTotalKb = 0L
        var memAvailKb = 0L
        var memFreeKb = 0L
        var cachedKb = 0L
        var swapTotalKb = 0L
        var swapFreeKb = 0L

        for (rawLine in memInfoText.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val key = line.substringBefore(':').trim()
            val valueKb = line.substringAfter(':', "").trim().substringBefore(' ').trim().toLongOrNull() ?: continue
            when (key) {
                "MemTotal" -> memTotalKb = valueKb
                "MemAvailable" -> memAvailKb = valueKb
                "MemFree" -> memFreeKb = valueKb
                "Cached" -> cachedKb = valueKb
                "SwapTotal" -> swapTotalKb = valueKb
                "SwapFree" -> swapFreeKb = valueKb
            }
        }

        val totalMb = if (memTotalKb > 0L) memTotalKb / 1024L else fallbackTotalRamMb.coerceAtLeast(1_024L)
        val availMb = when {
            memAvailKb > 0L -> memAvailKb / 1024L
            memFreeKb + cachedKb > 0L -> (memFreeKb + cachedKb) / 1024L
            else -> fallbackAvailRamMb.coerceAtLeast(256L)
        }
        val swapTotalMb = (swapTotalKb / 1024L).coerceAtLeast(0L)
        val swapFreeMb = (swapFreeKb / 1024L).coerceIn(0L, swapTotalMb)

        return ParsedMemInfo(
            totalRamMb = totalMb,
            availRamMb = availMb,
            swapTotalMb = swapTotalMb,
            swapFreeMb = swapFreeMb,
        )
    }

    fun parseZramAlgorithm(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val bracketed = Regex("\\[([^\\]]+)]").find(raw)?.groupValues?.getOrNull(1)?.trim()
        if (!bracketed.isNullOrEmpty()) return bracketed
        return raw.trim().substringBefore(' ').trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Computes Gradle JVM heap, Metaspace, worker count, daemon retention, and parallel execution across:
     * - 4 GB / 6 GB / 8 GB / 10 GB / 12 GB / 16 GB+ RAM tiers
     * - Built-in phone SwapRAM / ZRAM / Extended RAM (`swapTotalMb`, `swapFreeMb`)
     * - SoC vendor & core topology (`socVendor`, `socTier`, `performanceCores`)
     */
    fun computeGradleTuning(
        totalMemoryMb: Long,
        swapTotalMb: Long = 0L,
        swapFreeMb: Long = swapTotalMb,
        socVendor: SocVendor = SocVendor.GENERIC_ARM64,
        socTier: SocTier = SocTier.BALANCED_MID,
        performanceCores: Int = 4,
    ): GradleTuning {
        val ramTier = classifyRamTier(totalMemoryMb)
        val hasGenerousSwap = swapTotalMb >= 2_048L && swapFreeMb >= 1_024L
        val hasSubstantialSwap = swapTotalMb >= 3_500L && swapFreeMb >= 1_500L
        val isEntryChip = socTier == SocTier.ENTRY_EFFICIENCY ||
            socVendor == SocVendor.UNISOC ||
            performanceCores <= 2

        val (heapMb, metaspaceMb) = when (ramTier) {
            RamTier.RAM_16GB_PLUS -> {
                if (swapTotalMb > 0L || socTier == SocTier.FLAGSHIP_PERFORMANCE) {
                    4096L to 2048
                } else {
                    3072L to 1536
                }
            }
            RamTier.RAM_12GB -> 3072L to 1536
            RamTier.RAM_10GB -> {
                val heap = if (hasSubstantialSwap) 3072L else 2560L
                val meta = if (hasSubstantialSwap) 1536 else 1280
                heap to meta
            }
            RamTier.RAM_8GB -> {
                val heap = if (hasSubstantialSwap && !isEntryChip) 2304L else 2048L
                val meta = if (hasSubstantialSwap && !isEntryChip) 1280 else 1024
                heap to meta
            }
            RamTier.RAM_6GB -> {
                val heap = when {
                    hasSubstantialSwap -> 1536L
                    hasGenerousSwap -> 1280L
                    else -> ((totalMemoryMb / 8L).coerceIn(768L, 1024L) / 128L) * 128L
                }
                val meta = if (hasGenerousSwap) 768 else 512
                heap to meta
            }
            RamTier.RAM_4GB -> {
                val heap = if (hasGenerousSwap) 1024L else 768L
                heap to 512
            }
        }

        val rawWorkers = when (ramTier) {
            RamTier.RAM_16GB_PLUS -> {
                if ((swapTotalMb > 0L || socTier == SocTier.FLAGSHIP_PERFORMANCE) && performanceCores >= 5) 6 else 4
            }
            RamTier.RAM_12GB -> 4
            RamTier.RAM_10GB -> 4
            RamTier.RAM_8GB -> if (hasSubstantialSwap && !isEntryChip) 4 else 3
            RamTier.RAM_6GB -> if (hasGenerousSwap && !isEntryChip) 3 else 2
            RamTier.RAM_4GB -> 2
        }

        val maxWorkers = if (isEntryChip && (swapTotalMb > 0L || socVendor == SocVendor.UNISOC)) {
            rawWorkers.coerceAtMost((performanceCores + 1).coerceIn(2, 3))
        } else {
            rawWorkers
        }

        val daemonEnabled = when (ramTier) {
            RamTier.RAM_16GB_PLUS,
            RamTier.RAM_12GB,
            RamTier.RAM_10GB,
            RamTier.RAM_8GB -> true
            RamTier.RAM_6GB -> hasSubstantialSwap
            RamTier.RAM_4GB -> false
        }

        val daemonIdleTimeoutMs = when (ramTier) {
            RamTier.RAM_16GB_PLUS,
            RamTier.RAM_12GB -> 600_000L
            RamTier.RAM_10GB,
            RamTier.RAM_8GB -> if (swapTotalMb == 0L || hasGenerousSwap) 600_000L else 300_000L
            RamTier.RAM_6GB -> if (hasSubstantialSwap) 180_000L else 600_000L
            RamTier.RAM_4GB -> 600_000L
        }

        val parallelExecution = when (ramTier) {
            RamTier.RAM_16GB_PLUS,
            RamTier.RAM_12GB,
            RamTier.RAM_10GB -> !isEntryChip
            RamTier.RAM_8GB -> hasSubstantialSwap && !isEntryChip
            RamTier.RAM_6GB,
            RamTier.RAM_4GB -> false
        }

        return GradleTuning(
            heapMb = heapMb,
            metaspaceMb = metaspaceMb,
            daemonEnabled = daemonEnabled,
            daemonIdleTimeoutMs = daemonIdleTimeoutMs,
            maxWorkers = maxWorkers,
            parallelExecution = parallelExecution,
        )
    }

    /**
     * Maximum concurrent agent chat runs for this device's RAM tier, built-in SwapRAM, and SoC tier.
     */
    fun maxParallelChatsFor(profile: HardwareProfile): Int {
        val hasGenerousSwap = profile.swap.swapTotalMb >= 2_048L && profile.swap.swapFreeMb >= 1_024L
        val base = when (profile.ramTier) {
            RamTier.RAM_16GB_PLUS -> 12
            RamTier.RAM_12GB -> 12
            RamTier.RAM_10GB -> 10
            RamTier.RAM_8GB -> if (hasGenerousSwap) 8 else 6
            RamTier.RAM_6GB -> if (hasGenerousSwap) 5 else 3
            RamTier.RAM_4GB -> 2
        }
        return if (profile.socTier == SocTier.ENTRY_EFFICIENCY) base.coerceAtMost(5) else base
    }

    /**
     * Minimum free bytes required before admitting an additional parallel agent run.
     * Built-in SwapRAM with >= 1.5 GB free allows safe relief on 6 GB / 8 GB phones because the kernel
     * can page out idle anonymous pages to ZRAM/SwapRAM without invoking LMKD.
     */
    fun minFreeBytesForRun(profile: HardwareProfile, isHeavyNodeAgent: Boolean): Long {
        val swapReliefMb = if (profile.swap.swapFreeMb >= 1_536L) 120L else 0L
        val minMb = if (isHeavyNodeAgent) {
            (650L - swapReliefMb).coerceAtLeast(480L)
        } else {
            (450L - swapReliefMb).coerceAtLeast(320L)
        }
        return minMb * 1024L * 1024L
    }

    /**
     * Inspects the current Android device's SoC, core topology, RAM tier, and built-in SwapRAM / ZRAM.
     */
    fun inspect(context: Context? = null, forceRefresh: Boolean = false): HardwareProfile {
        val now = System.currentTimeMillis()
        val existing = cachedProfile
        if (!forceRefresh && existing != null && now - lastInspectMs < 4_000L) {
            return existing
        }

        val socMan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER.orEmpty() else ""
        val socMod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.orEmpty() else ""
        val hardware = Build.HARDWARE.orEmpty()
        val board = Build.BOARD.orEmpty()
        val cpuInfo = runCatching { File("/proc/cpuinfo").readText() }.getOrDefault("")

        val vendor = detectSocVendor(
            socManufacturer = socMan,
            socModel = socMod,
            hardware = hardware,
            board = board,
            cpuInfoText = cpuInfo,
        )
        val modelLabel = listOf(socMod, hardware, board).firstOrNull {
            it.isNotBlank() && !it.equals(Build.UNKNOWN, ignoreCase = true)
        } ?: vendor.shortBadge

        val runtimeCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val freqs = (0 until runtimeCores).mapNotNull { idx ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$idx/cpufreq/cpuinfo_max_freq").readText().trim().toLongOrNull()
            }.getOrNull()
        }
        val topology = detectCoreTopology(freqs, runtimeCores)
        val socTier = classifySocTier(vendor, modelLabel, topology)

        val amInfo = ActivityManager.MemoryInfo()
        runCatching {
            context?.getSystemService(ActivityManager::class.java)?.getMemoryInfo(amInfo)
        }
        val fallbackTotalMb = if (amInfo.totalMem > 0L) amInfo.totalMem / (1024L * 1024L) else 4_096L
        val fallbackAvailMb = if (amInfo.availMem > 0L) amInfo.availMem / (1024L * 1024L) else 1_536L

        val memInfoText = runCatching { File("/proc/meminfo").readText() }.getOrDefault("")
        val parsedMem = parseMemInfo(memInfoText, fallbackTotalMb, fallbackAvailMb)
        val zramAlg = parseZramAlgorithm(runCatching { File("/sys/block/zram0/comp_algorithm").readText() }.getOrNull())

        val totalRamMb = if (amInfo.totalMem > 0L) amInfo.totalMem / (1024L * 1024L) else parsedMem.totalRamMb
        val availRamMb = if (amInfo.availMem > 0L) amInfo.availMem / (1024L * 1024L) else parsedMem.availRamMb

        val profile = HardwareProfile(
            socVendor = vendor,
            socModel = modelLabel,
            socTier = socTier,
            topology = topology,
            ramTier = classifyRamTier(totalRamMb),
            totalRamMb = totalRamMb,
            availRamMb = availRamMb,
            swap = SwapProfile(
                swapTotalMb = parsedMem.swapTotalMb,
                swapFreeMb = parsedMem.swapFreeMb,
                zramAlgorithm = zramAlg,
            ),
        )
        cachedProfile = profile
        lastInspectMs = now
        return profile
    }
}
