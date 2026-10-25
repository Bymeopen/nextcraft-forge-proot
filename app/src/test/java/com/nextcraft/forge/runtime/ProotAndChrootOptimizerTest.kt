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
    fun parsesKernelVersionAndSelectsOptimalSpoofedRelease() {
        assertEquals(Triple(5, 15, 148), ProotAndChrootOptimizer.parseKernelVersion("5.15.148-android13-8"))
        assertEquals(Triple(4, 14, 186), ProotAndChrootOptimizer.parseKernelVersion("4.14.186-perf+"))
        assertEquals(Triple(6, 1, 75), ProotAndChrootOptimizer.parseKernelVersion("6.1.75-android14-11"))

        // Legacy 4.14 / 4.19 / 5.4 kernels get spoofed to 5.15.0-nextcraft-arm64 for glibc 2.39 compatibility
        assertEquals("5.15.0-nextcraft-arm64", ProotAndChrootOptimizer.selectOptimalKernelRelease("4.14.186-perf+"))
        assertEquals("5.15.0-nextcraft-arm64", ProotAndChrootOptimizer.selectOptimalKernelRelease("5.4.210-qgki"))
        // Modern 5.15 / 6.1 kernels preserve their real release string
        assertEquals("6.1.75-android14", ProotAndChrootOptimizer.selectOptimalKernelRelease("6.1.75-android14"))
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

        // Non-root on modern kernel -> PRoot Seccomp-BPF Accelerated
        val prootSeccomp = ProotAndChrootOptimizer.buildConfig(
            hwProfile = eightGbSwap,
            kernelRelease = "5.10.198-android12",
            hasRootAccess = false,
        )
        assertEquals(ProotAndChrootOptimizer.ExecutionBackend.PROOT_SECCOMP_ACCELERATED, prootSeccomp.backend)
        assertTrue(prootSeccomp.enableSeccompBpf)
        assertTrue(prootSeccomp.recommendedProotFlags.contains("--link2symlink"))
        assertTrue(prootSeccomp.recommendedProotFlags.contains("--sysvipc"))
        assertTrue(prootSeccomp.recommendedProotFlags.contains("--kill-on-exit"))

        // Legacy 4.14 kernel -> PRoot Ptrace Compatibility Fallback with PROOT_NO_SECCOMP=1
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
        val cfg = ProotAndChrootOptimizer.buildConfig(hw, kernelRelease = "6.1.50-android14", hasRootAccess = false)
        assertEquals(1024, cfg.shmSizeMb)
        assertEquals(2048, cfg.tmpSizeMb)
        assertEquals(8, cfg.uvThreadpoolSize)

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
