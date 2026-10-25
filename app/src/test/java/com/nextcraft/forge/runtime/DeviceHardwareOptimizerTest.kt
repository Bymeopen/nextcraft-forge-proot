package com.nextcraft.forge.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceHardwareOptimizerTest {

    @Test
    fun detectsSnapdragonMediatekUnisocExynosTensorAndKirin() {
        assertEquals(
            DeviceHardwareOptimizer.SocVendor.SNAPDRAGON,
            DeviceHardwareOptimizer.detectSocVendor(
                socManufacturer = "Qualcomm",
                socModel = "SM8650",
                hardware = "qcom",
            ),
        )
        assertEquals(
            DeviceHardwareOptimizer.SocVendor.MEDIATEK,
            DeviceHardwareOptimizer.detectSocVendor(
                socManufacturer = "MediaTek",
                socModel = "MT6989",
                hardware = "mt6989",
            ),
        )
        assertEquals(
            DeviceHardwareOptimizer.SocVendor.MEDIATEK,
            DeviceHardwareOptimizer.detectSocVendor(
                socManufacturer = "",
                socModel = "Helio G99",
                hardware = "mt6789",
            ),
        )
        assertEquals(
            DeviceHardwareOptimizer.SocVendor.UNISOC,
            DeviceHardwareOptimizer.detectSocVendor(
                socManufacturer = "UNISOC",
                socModel = "UMS9230",
                hardware = "ums9230_t606",
            ),
        )
        assertEquals(
            DeviceHardwareOptimizer.SocVendor.UNISOC,
            DeviceHardwareOptimizer.detectSocVendor(
                socManufacturer = "Spreadtrum",
                socModel = "T820",
                board = "sp9863a",
            ),
        )
        assertEquals(
            DeviceHardwareOptimizer.SocVendor.EXYNOS,
            DeviceHardwareOptimizer.detectSocVendor(
                socManufacturer = "Samsung",
                socModel = "s5e9945",
                hardware = "exynos2400",
            ),
        )
        assertEquals(
            DeviceHardwareOptimizer.SocVendor.TENSOR,
            DeviceHardwareOptimizer.detectSocVendor(
                socManufacturer = "Google",
                socModel = "Tensor G4",
                board = "zuma",
            ),
        )
        assertEquals(
            DeviceHardwareOptimizer.SocVendor.KIRIN,
            DeviceHardwareOptimizer.detectSocVendor(
                socManufacturer = "HiSilicon",
                socModel = "Kirin 9000S",
            ),
        )
    }

    @Test
    fun classifiesAllMarketedRamTiers6_8_10_12_16Gb() {
        assertEquals(DeviceHardwareOptimizer.RamTier.RAM_4GB, DeviceHardwareOptimizer.classifyRamTier(3_800))
        assertEquals(DeviceHardwareOptimizer.RamTier.RAM_6GB, DeviceHardwareOptimizer.classifyRamTier(5_600))
        assertEquals(DeviceHardwareOptimizer.RamTier.RAM_8GB, DeviceHardwareOptimizer.classifyRamTier(7_400))
        assertEquals(DeviceHardwareOptimizer.RamTier.RAM_10GB, DeviceHardwareOptimizer.classifyRamTier(9_400))
        assertEquals(DeviceHardwareOptimizer.RamTier.RAM_12GB, DeviceHardwareOptimizer.classifyRamTier(11_200))
        assertEquals(DeviceHardwareOptimizer.RamTier.RAM_16GB_PLUS, DeviceHardwareOptimizer.classifyRamTier(15_200))
    }

    @Test
    fun detectsBigLittleCoreTopologyAndSocTiers() {
        val entryTopo = DeviceHardwareOptimizer.detectCoreTopology(
            listOf(1_800_000L, 1_800_000L, 1_800_000L, 1_800_000L, 1_800_000L, 1_800_000L, 2_000_000L, 2_000_000L),
        )
        assertEquals(8, entryTopo.totalCores)
        assertEquals(2, entryTopo.performanceCores)
        assertEquals(6, entryTopo.efficiencyCores)
        assertEquals(
            DeviceHardwareOptimizer.SocTier.ENTRY_EFFICIENCY,
            DeviceHardwareOptimizer.classifySocTier(DeviceHardwareOptimizer.SocVendor.UNISOC, "T616", entryTopo),
        )

        val flagshipTopo = DeviceHardwareOptimizer.detectCoreTopology(
            listOf(2_200_000L, 2_200_000L, 2_800_000L, 2_800_000L, 2_800_000L, 3_150_000L, 3_150_000L, 3_300_000L),
        )
        assertEquals(6, flagshipTopo.performanceCores)
        assertEquals(
            DeviceHardwareOptimizer.SocTier.FLAGSHIP_PERFORMANCE,
            DeviceHardwareOptimizer.classifySocTier(DeviceHardwareOptimizer.SocVendor.SNAPDRAGON, "SM8650", flagshipTopo),
        )
    }

    @Test
    fun builtInSwapRamOptimizes6GbAnd16GbDevicesWithoutThrashing() {
        val sixGbNoSwap = DeviceHardwareOptimizer.computeGradleTuning(
            totalMemoryMb = 5_600L,
            swapTotalMb = 0L,
        )
        assertEquals(768L, sixGbNoSwap.heapMb)
        assertEquals(512, sixGbNoSwap.metaspaceMb)
        assertFalse(sixGbNoSwap.daemonEnabled)
        assertEquals(2, sixGbNoSwap.maxWorkers)

        val sixGbWithSwap = DeviceHardwareOptimizer.computeGradleTuning(
            totalMemoryMb = 5_600L,
            swapTotalMb = 4_096L,
            swapFreeMb = 3_500L,
        )
        assertEquals(1536L, sixGbWithSwap.heapMb)
        assertEquals(768, sixGbWithSwap.metaspaceMb)
        assertTrue(sixGbWithSwap.daemonEnabled)
        assertEquals(180_000L, sixGbWithSwap.daemonIdleTimeoutMs)
        assertEquals(3, sixGbWithSwap.maxWorkers)

        val tenGbNoSwap = DeviceHardwareOptimizer.computeGradleTuning(
            totalMemoryMb = 9_400L,
            swapTotalMb = 0L,
        )
        assertEquals(2560L, tenGbNoSwap.heapMb)
        assertEquals(1280, tenGbNoSwap.metaspaceMb)
        assertTrue(tenGbNoSwap.daemonEnabled)
        assertEquals(4, tenGbNoSwap.maxWorkers)

        val sixteenGbFlagshipSwap = DeviceHardwareOptimizer.computeGradleTuning(
            totalMemoryMb = 15_200L,
            swapTotalMb = 8_192L,
            swapFreeMb = 7_500L,
            socVendor = DeviceHardwareOptimizer.SocVendor.SNAPDRAGON,
            socTier = DeviceHardwareOptimizer.SocTier.FLAGSHIP_PERFORMANCE,
            performanceCores = 6,
        )
        assertEquals(4096L, sixteenGbFlagshipSwap.heapMb)
        assertEquals(2048, sixteenGbFlagshipSwap.metaspaceMb)
        assertEquals(6, sixteenGbFlagshipSwap.maxWorkers)
        assertTrue(sixteenGbFlagshipSwap.parallelExecution)
    }

    @Test
    fun entryUnisocAndHelioCappedToProtectAndroidUiResponsiveness() {
        val entryUnisoc8Gb = DeviceHardwareOptimizer.computeGradleTuning(
            totalMemoryMb = 7_400L,
            swapTotalMb = 4_096L,
            swapFreeMb = 3_800L,
            socVendor = DeviceHardwareOptimizer.SocVendor.UNISOC,
            socTier = DeviceHardwareOptimizer.SocTier.ENTRY_EFFICIENCY,
            performanceCores = 2,
        )
        assertEquals(3, entryUnisoc8Gb.maxWorkers)
        assertFalse(entryUnisoc8Gb.parallelExecution)
    }

    @Test
    fun parsesMemInfoAndZramAlgorithmAccurately() {
        val sampleMemInfo = """
            MemTotal:       11428864 kB
            MemFree:          452100 kB
            MemAvailable:    6123456 kB
            Cached:          3912000 kB
            SwapTotal:       8388604 kB
            SwapFree:        7123456 kB
        """.trimIndent()
        val parsed = DeviceHardwareOptimizer.parseMemInfo(sampleMemInfo)
        assertEquals(11_161L, parsed.totalRamMb)
        assertEquals(5_979L, parsed.availRamMb)
        assertEquals(8_191L, parsed.swapTotalMb)
        assertEquals(6_956L, parsed.swapFreeMb)

        assertEquals("zstd", DeviceHardwareOptimizer.parseZramAlgorithm("lzo lzo-rle lz4 [zstd]"))
        assertEquals("lz4", DeviceHardwareOptimizer.parseZramAlgorithm("[lz4] zstd"))
    }
}
