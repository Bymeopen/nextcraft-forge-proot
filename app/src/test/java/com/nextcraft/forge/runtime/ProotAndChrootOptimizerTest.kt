package com.nextcraft.forge.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProotAndChrootOptimizerTest {

    private fun sampleHardwareProfile(
        ramTier: DeviceHardwareOptimizer.RamTier = DeviceHardwareOptimizer.RamTier.RAM_8GB,
        totalRamMb: Long = 7_500L,
        swapTotalMb: Long = 4_096L,
        socVendor: DeviceHardwareOptimizer.SocVendor = DeviceHardwareOptimizer.SocVendor.SNAPDRAGON,
        socTier: DeviceHardwareOptimizer.SocTier = DeviceHardwareOptimizer.SocTier.BALANCED_MID,
    ) = DeviceHardwareOptimizer.HardwareProfile(
        socVendor = socVendor,
        socModel = "SM7475",
        socTier = socTier,
        topology = DeviceHardwareOptimizer.CoreTopology(8, 4, 4),
        ramTier = ramTier,
        totalRamMb = totalRamMb,
        availRamMb = totalRamMb / 2,
        swap = DeviceHardwareOptimizer.SwapProfile(
            swapTotalMb = swapTotalMb,
            swapFreeMb = swapTotalMb,
            zramAlgorithm = "lz4",
        ),
    )

    @Test
    fun classifiesAllAndroidKernelFamiliesAndPageSizes() {
        val kLegacy = ProotAndChrootOptimizer.inspectKernel("4.14.186-perf+", pageSizeBytes = 4_096)
        assertEquals(ProotAndChrootOptimizer.KernelFamily.LEGACY_3_18_TO_4_14, kLegacy.family)
        assertFalse(kLegacy.supportsSeccompBpf)
        assertTrue(kLegacy.needsKernelReleaseSpoof)
        assertEquals("5.15.0-nextcraft-arm64", kLegacy.spoofedRelease)
        assertFalse(kLegacy.is16KbPageKernel)

        val kCommon = ProotAndChrootOptimizer.inspectKernel("4.19.157-qgki", pageSizeBytes = 4_096)
        assertEquals(ProotAndChrootOptimizer.KernelFamily.ANDROID_COMMON_4_19_TO_5_4, kCommon.family)
        assertTrue(kCommon.supportsSeccompBpf)
        assertTrue(kCommon.needsKernelReleaseSpoof)
        assertEquals("5.15.0-nextcraft-arm64", kCommon.spoofedRelease)

        val kGki1 = ProotAndChrootOptimizer.inspectKernel("5.15.148-android13-8", pageSizeBytes = 4_096)
        assertEquals(ProotAndChrootOptimizer.KernelFamily.GKI_1_0_5_10_TO_5_15, kGki1.family)
        assertTrue(kGki1.isGkiKernel)
        assertTrue(kGki1.supportsSeccompBpf)
        assertFalse(kGki1.needsKernelReleaseSpoof)
        assertEquals("5.15.148-android13-8", kGki1.spoofedRelease)

        val kGki2_16k = ProotAndChrootOptimizer.inspectKernel("6.6.30-android15-8-gki", pageSizeBytes = 16_384)
        assertEquals(ProotAndChrootOptimizer.KernelFamily.GKI_2_0_6_1_PLUS, kGki2_16k.family)
        assertTrue(kGki2_16k.isGkiKernel)
        assertTrue(kGki2_16k.is16KbPageKernel)
        assertEquals("6.6.30-android15-8-gki", kGki2_16k.spoofedRelease)
    }

    @Test
    fun buildsSeccompAcceleratedProotAndNativeChrootConfigsAcrossRamTiers() {
        val eightGbSwap = sampleHardwareProfile(
            ramTier = DeviceHardwareOptimizer.RamTier.RAM_8GB,
            totalRamMb = 7_400L,
            swapTotalMb = 4_096L,
        )
        val rootConfig = ProotAndChrootOptimizer.buildConfig(
            hwProfile = eightGbSwap,
            kernelRelease = "5.15.120-android13",
            hasRootAccess = true,
            pageSizeBytes = 4_096,
        )
        assertEquals(ProotAndChrootOptimizer.ExecutionBackend.ROOT_CHROOT_NATIVE, rootConfig.backend)
        assertTrue(rootConfig.enableSeccompBpf)
        assertTrue(rootConfig.enableLink2Symlink)
        assertTrue(rootConfig.enableSysvIpc)
        assertEquals(512, rootConfig.shmSizeMb)
        assertEquals(768, rootConfig.tmpSizeMb)
        assertEquals(6, rootConfig.uvThreadpoolSize)
        assertFalse(rootConfig.environmentVars.containsKey("PROOT_NO_SECCOMP"))
        assertEquals("/tmp/.proot-tmp", rootConfig.environmentVars["PROOT_TMP_DIR"])
        assertEquals("/tmp/.proot-l2s", rootConfig.environmentVars["PROOT_L2S_DIR"])

        val prootSeccomp = ProotAndChrootOptimizer.buildConfig(
            hwProfile = eightGbSwap,
            kernelRelease = "5.10.198-android12",
            hasRootAccess = false,
            pageSizeBytes = 4_096,
        )
        assertEquals(ProotAndChrootOptimizer.ExecutionBackend.PROOT_SECCOMP_ACCELERATED, prootSeccomp.backend)
        assertTrue(prootSeccomp.enableSeccompBpf)
        assertTrue(prootSeccomp.recommendedProotFlags.contains("--link2symlink"))
        assertTrue(prootSeccomp.recommendedProotFlags.contains("--sysvipc"))
        assertTrue(prootSeccomp.recommendedProotFlags.contains("--kill-on-exit"))

        val prootCompat = ProotAndChrootOptimizer.buildConfig(
            hwProfile = sampleHardwareProfile(
                ramTier = DeviceHardwareOptimizer.RamTier.RAM_6GB,
                totalRamMb = 5_500L,
                swapTotalMb = 0L,
                socVendor = DeviceHardwareOptimizer.SocVendor.UNISOC,
                socTier = DeviceHardwareOptimizer.SocTier.ENTRY_EFFICIENCY,
            ),
            kernelRelease = "4.14.190-sprd",
            hasRootAccess = false,
            pageSizeBytes = 4_096,
        )
        assertEquals(ProotAndChrootOptimizer.ExecutionBackend.PROOT_COMPAT_FALLBACK, prootCompat.backend)
        assertFalse(prootCompat.enableSeccompBpf)
        assertEquals("1", prootCompat.environmentVars["PROOT_NO_SECCOMP"])
        assertEquals(256, prootCompat.shmSizeMb)
        assertEquals(512, prootCompat.tmpSizeMb)
        assertEquals(4, prootCompat.uvThreadpoolSize)
    }

    @Test
    fun buildsCompleteProotCommandLineAndInstallsGuestWrappers() {
        val hw = sampleHardwareProfile(
            ramTier = DeviceHardwareOptimizer.RamTier.RAM_16GB_PLUS,
            totalRamMb = 15_200L,
            swapTotalMb = 8_192L,
            socTier = DeviceHardwareOptimizer.SocTier.FLAGSHIP_PERFORMANCE,
        )
        val cfg = ProotAndChrootOptimizer.buildConfig(
            hw,
            kernelRelease = "6.1.50-android14",
            hasRootAccess = false,
            pageSizeBytes = 16_384,
        )
        assertEquals(1024, cfg.shmSizeMb)
        assertEquals(2048, cfg.tmpSizeMb)
        assertEquals(8, cfg.uvThreadpoolSize)
        assertEquals("16384", cfg.environmentVars["NEXTCRAFT_PAGE_SIZE"])

        val cmd = ProotAndChrootOptimizer.buildProotCommandLine(
            prootBinaryPath = "/usr/bin/proot",
            rootfsPath = "/data/user/0/com.nextcraft.forge/files/runtime/ubuntu",
            hostWorkspacePath = "/data/user/0/com.nextcraft.forge/files/workspaces/demo",
            guestWorkspacePath = "/workspace/demo",
            hostBridgePath = "/data/user/0/com.nextcraft.forge/files/runtime-bridge",
            config = cfg,
            guestCommand = listOf("/bin/bash", "-lc", "gradle --version"),
        )
        assertEquals("/usr/bin/proot", cmd.first())
        assertTrue(cmd.contains("--link2symlink"))
        assertTrue(cmd.contains("--sysvipc"))
        assertTrue(cmd.contains("-w"))
        assertTrue(cmd.contains("/workspace/demo"))

        val tempRootfs = Files.createTempDirectory("nextcraft-proot-test").toFile()
        try {
            File(tempRootfs, "usr/local/bin").mkdirs()
            ProotAndChrootOptimizer.applyGuestWrappers(tempRootfs) { file, body, _ ->
                file.parentFile?.mkdirs()
                file.writeText(body)
            }
            val prootFast = File(tempRootfs, ProotAndChrootOptimizer.PROOT_FAST_GUEST_PATH)
            val fallbackProot = File(tempRootfs, "usr/local/bin/proot")
            assertTrue(prootFast.isFile)
            assertTrue(fallbackProot.isFile)
            assertTrue(prootFast.readText().contains("--link2symlink"))
            assertTrue(prootFast.readText().contains("PROOT_TMP_DIR"))
        } finally {
            tempRootfs.deleteRecursively()
        }
    }
}
