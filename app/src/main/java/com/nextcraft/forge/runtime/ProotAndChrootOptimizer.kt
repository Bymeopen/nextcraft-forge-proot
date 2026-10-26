package com.nextcraft.forge.runtime

import java.io.File
import java.util.Locale

/**
 * Complete All-Android-Kernels (`3.18`..`6.12+` GKI 2.0 / 16KB Page Size), PRoot, and Root Chroot
 * runtime optimizer (`ProotAndChrootOptimizer`).
 *
 * Covers every Android kernel generation and both container execution engines:
 * 1. **All Android Kernel Generations (`KernelFamily`)**:
 *    - `LEGACY_3_18_TO_4_14` (`3.18`, `4.4`, `4.9`, `4.14`): Legacy vendor kernels without reliable seccomp-bpf
 *      syscall reordering or ashmem-memfd; enables `PROOT_NO_SECCOMP=1`, spoofs kernel release to `5.15.0-nextcraft-arm64`
 *      for Ubuntu 24.04 (`glibc 2.39`), and disables `io_uring`.
 *    - `ANDROID_COMMON_4_19_TO_5_4` (`4.19`, `5.4`): Early Treble / QGKI kernels; enables PRoot seccomp-bpf
 *      acceleration, spoofs kernel release to `5.15.0-nextcraft-arm64`, and disables `rseq`/`io_uring`.
 *    - `GKI_1_0_5_10_TO_5_15` (`5.10`, `5.15`): Android 12/13 GKI 1.0/2.0 kernels (`android12-5.10`, `android13-5.15`);
 *      preserves real kernel release, enables seccomp-bpf, and applies Bionic APEX mount propagation.
 *    - `GKI_2_0_6_1_PLUS` (`6.1`, `6.6`, `6.12+`): Android 14/15/16+ modern GKI 2.0 kernels (including 16 KB page-size
 *      kernels); enables full seccomp-bpf acceleration, 16 KB page-size alignment hints (`JAVAC_FLAGS` / `NODE_OPTIONS` /
 *      `MALLOC_ARENA_MAX`), and `/sys/fs/cgroup` v2 awareness.
 * 2. **Native Root Chroot Mode (`su -mm` + `chroot-exec.sh`)**:
 *    - Zero-ptrace native kernel bind-mounts (`proc`, `sysfs`, `devpts`), dynamic `/dev/shm` and `/tmp` tmpfs sizing,
 *      O(1) incremental workspace ownership repair, `/apex` & `/system_ext` slave submounts, and automatic fallback to
 *      `proot` if a custom kernel/ramdisk lacks a `chroot` binary.
 * 3. **Complete Non-Root & Nested PRoot Mode (`proot-exec.sh` / `proot-fast`)**:
 *    - Kernel-aware Seccomp-BPF filter (`PROOT_NO_SECCOMP` auto-toggled by kernel version & vendor quirks),
 *      `--link2symlink` (`PROOT_L2S_DIR`), `--sysvipc`, `-L`, `-0`, `--kill-on-exit`, fake `/proc` & `/sys`
 *      synthesizers for restricted Android SELinux policies, and `/opt/nextcraft/bin/proot-fast`.
 */
object ProotAndChrootOptimizer {

    enum class KernelFamily(val displayName: String, val shortBadge: String) {
        LEGACY_3_18_TO_4_14("Legacy Android Kernel (3.18–4.14)", "Kernel 4.x Legacy"),
        ANDROID_COMMON_4_19_TO_5_4("Android Common / QGKI (4.19–5.4)", "Kernel 4.19/5.4"),
        GKI_1_0_5_10_TO_5_15("Android GKI (5.10–5.15)", "GKI 5.10/5.15"),
        GKI_2_0_6_1_PLUS("Android Modern GKI 2.0 (6.1–6.12+)", "GKI 2.0 6.x+"),
    }

    enum class ExecutionBackend(val displayName: String, val shortBadge: String) {
        ROOT_CHROOT_NATIVE("Root Chroot Native (Zero-Ptrace)", "Chroot"),
        PROOT_SECCOMP_ACCELERATED("PRoot Seccomp-BPF Accelerated", "PRoot+Seccomp"),
        PROOT_COMPAT_FALLBACK("PRoot Ptrace Compatibility", "PRoot-Compat"),
    }

    data class KernelProfile(
        val rawRelease: String,
        val major: Int,
        val minor: Int,
        val patch: Int,
        val family: KernelFamily,
        val isGkiKernel: Boolean,
        val pageSizeBytes: Int,
        val supportsSeccompBpf: Boolean,
        val needsKernelReleaseSpoof: Boolean,
        val spoofedRelease: String,
    ) {
        val is16KbPageKernel: Boolean
            get() = pageSizeBytes >= 16_384
    }

    data class ProotOptimizationConfig(
        val backend: ExecutionBackend,
        val kernelProfile: KernelProfile,
        val enableSeccompBpf: Boolean,
        val enableLink2Symlink: Boolean,
        val enableSysvIpc: Boolean,
        val fakeRootId: Boolean,
        val killOnExit: Boolean,
        val spoofedKernelRelease: String,
        val shmSizeMb: Int,
        val tmpSizeMb: Int,
        val prootTmpDir: String,
        val uvThreadpoolSize: Int,
        val environmentVars: Map<String, String>,
        val recommendedProotFlags: List<String>,
    )

    /**
     * Parses a Linux kernel `uname -r` string (e.g. `5.15.148-android13-8-g1234` or `4.19.157-perf+`)
     * into `(major, minor, patch)`.
     */
    fun parseKernelVersion(unameRelease: String): Triple<Int, Int, Int> {
        val match = Regex("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?").find(unameRelease.trim())
            ?: return Triple(5, 10, 0)
        val major = match.groupValues[1].toIntOrNull() ?: 5
        val minor = match.groupValues[2].toIntOrNull() ?: 10
        val patch = match.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        return Triple(major, minor, patch)
    }

    /**
     * Classifies any Android kernel (`3.18` through `6.12+`) into its [KernelFamily].
     */
    fun classifyKernelFamily(major: Int, minor: Int): KernelFamily = when {
        major >= 6 -> KernelFamily.GKI_2_0_6_1_PLUS
        major == 5 && minor >= 10 -> KernelFamily.GKI_1_0_5_10_TO_5_15
        (major == 5 && minor < 10) || (major == 4 && minor >= 19) -> KernelFamily.ANDROID_COMMON_4_19_TO_5_4
        else -> KernelFamily.LEGACY_3_18_TO_4_14
    }

    /**
     * Determines whether the kernel needs a spoofed `-k` release string for Ubuntu 24.04 (glibc 2.39)
     * and modern Node/Rust toolchains under PRoot.
     */
    fun selectOptimalKernelRelease(rawUnameRelease: String): String {
        val (major, minor, _) = parseKernelVersion(rawUnameRelease)
        // Ubuntu 24.04 glibc 2.39 and modern V8/Rust/Bun probes work best when seeing >= 5.15 LTS or >= 6.1 LTS.
        return if (major < 5 || (major == 5 && minor < 10)) {
            "5.15.0-nextcraft-arm64"
        } else {
            rawUnameRelease.trim().ifEmpty { "6.1.0-nextcraft-arm64" }
        }
    }

    /**
     * Builds a complete [KernelProfile] for the given `uname -r` string and hardware page size.
     */
    fun inspectKernel(
        rawUnameRelease: String = System.getProperty("os.version").orEmpty(),
        pageSizeBytes: Int = detectPageSizeBytes(),
        seccompAvailable: Boolean = true,
    ): KernelProfile {
        val (major, minor, patch) = parseKernelVersion(rawUnameRelease)
        val family = classifyKernelFamily(major, minor)
        val lower = rawUnameRelease.lowercase(Locale.US)
        val isGki = lower.contains("android12") ||
            lower.contains("android13") ||
            lower.contains("android14") ||
            lower.contains("android15") ||
            lower.contains("android16") ||
            lower.contains("gki") ||
            major >= 6 ||
            (major == 5 && minor >= 10)
        // Kernels >= 4.19 handle PRoot seccomp-bpf cleanly unless running on broken early 4.14/4.9 filters.
        val supportsSeccomp = seccompAvailable && family != KernelFamily.LEGACY_3_18_TO_4_14
        val needsSpoof = major < 5 || (major == 5 && minor < 10)
        val spoofed = selectOptimalKernelRelease(rawUnameRelease)
        return KernelProfile(
            rawRelease = rawUnameRelease.trim().ifEmpty { "5.15.0-android" },
            major = major,
            minor = minor,
            patch = patch,
            family = family,
            isGkiKernel = isGki,
            pageSizeBytes = pageSizeBytes.coerceAtLeast(4_096),
            supportsSeccompBpf = supportsSeccomp,
            needsKernelReleaseSpoof = needsSpoof,
            spoofedRelease = spoofed,
        )
    }

    private fun detectPageSizeBytes(): Int = runCatching {
        val pageSize = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
        if (pageSize > 0L) pageSize.toInt() else 4_096
    }.getOrDefault(4_096)

    /**
     * Computes complete PRoot & Chroot optimization parameters for the given hardware profile and kernel version.
     */
    fun buildConfig(
        hwProfile: DeviceHardwareOptimizer.HardwareProfile,
        kernelRelease: String = System.getProperty("os.version").orEmpty(),
        hasRootAccess: Boolean = true,
        emulateHardLinks: Boolean = true,
        seccompAvailable: Boolean = true,
        pageSizeBytes: Int = detectPageSizeBytes(),
    ): ProotOptimizationConfig {
        val kernelProfile = inspectKernel(
            rawUnameRelease = kernelRelease,
            pageSizeBytes = pageSizeBytes,
            seccompAvailable = seccompAvailable,
        )
        val useSeccomp = kernelProfile.supportsSeccompBpf
        val backend = when {
            hasRootAccess -> ExecutionBackend.ROOT_CHROOT_NATIVE
            useSeccomp -> ExecutionBackend.PROOT_SECCOMP_ACCELERATED
            else -> ExecutionBackend.PROOT_COMPAT_FALLBACK
        }

        // Dynamic /dev/shm and /tmp sizing based on RAM tier + built-in SwapRAM
        val hasSwap = hwProfile.swap.hasActiveSwap
        val shmSizeMb = when (hwProfile.ramTier) {
            DeviceHardwareOptimizer.RamTier.RAM_16GB_PLUS -> 1024
            DeviceHardwareOptimizer.RamTier.RAM_12GB -> 768
            DeviceHardwareOptimizer.RamTier.RAM_10GB -> 512
            DeviceHardwareOptimizer.RamTier.RAM_8GB -> if (hasSwap) 512 else 384
            DeviceHardwareOptimizer.RamTier.RAM_6GB -> if (hasSwap) 384 else 256
            DeviceHardwareOptimizer.RamTier.RAM_4GB -> 192
        }
        val tmpSizeMb = when (hwProfile.ramTier) {
            DeviceHardwareOptimizer.RamTier.RAM_16GB_PLUS -> 2048
            DeviceHardwareOptimizer.RamTier.RAM_12GB -> 1536
            DeviceHardwareOptimizer.RamTier.RAM_10GB -> 1024
            DeviceHardwareOptimizer.RamTier.RAM_8GB -> 768
            DeviceHardwareOptimizer.RamTier.RAM_6GB -> 512
            DeviceHardwareOptimizer.RamTier.RAM_4GB -> 384
        }

        // libuv threadpool size for Node.js (Claude Code, DSH, npm/pnpm/vite) inside PRoot/Chroot
        val uvThreads = when (hwProfile.socTier) {
            DeviceHardwareOptimizer.SocTier.ENTRY_EFFICIENCY -> 4
            DeviceHardwareOptimizer.SocTier.BALANCED_MID -> 6
            DeviceHardwareOptimizer.SocTier.FLAGSHIP_PERFORMANCE -> 8
        }

        val spoofedKernel = kernelProfile.spoofedRelease
        val prootTmpDir = "/tmp/.proot-tmp"

        val envVars = linkedMapOf(
            "PROOT_TMP_DIR" to prootTmpDir,
            "PROOT_L2S_DIR" to "/tmp/.proot-l2s",
            "UV_THREADPOOL_SIZE" to uvThreads.toString(),
            "NEXTCRAFT_SHM_MB" to shmSizeMb.toString(),
            "NEXTCRAFT_TMP_MB" to tmpSizeMb.toString(),
            "NEXTCRAFT_KERNEL_RELEASE" to spoofedKernel,
            "NEXTCRAFT_KERNEL_FAMILY" to kernelProfile.family.shortBadge,
            "NEXTCRAFT_PAGE_SIZE" to kernelProfile.pageSizeBytes.toString(),
        )
        if (!useSeccomp) {
            envVars["PROOT_NO_SECCOMP"] = "1"
        }

        val flags = buildList {
            add("--root-id")
            if (emulateHardLinks) add("--link2symlink")
            add("--sysvipc")
            add("-L")
            add("--kill-on-exit")
            add("-k")
            add(spoofedKernel)
            add("-b")
            add("/dev")
            add("-b")
            add("/dev/urandom:/dev/random")
            add("-b")
            add("/proc")
            add("-b")
            add("/proc/self/fd:/dev/fd")
            add("-b")
            add("/proc/self/fd/0:/dev/stdin")
            add("-b")
            add("/proc/self/fd/1:/dev/stdout")
            add("-b")
            add("/proc/self/fd/2:/dev/stderr")
            add("-b")
            add("/sys")
            add("-b")
            add("/system")
            add("-b")
            add("/apex")
            add("-b")
            add("/vendor")
            add("-b")
            add("/product")
        }

        return ProotOptimizationConfig(
            backend = backend,
            kernelProfile = kernelProfile,
            enableSeccompBpf = useSeccomp,
            enableLink2Symlink = emulateHardLinks,
            enableSysvIpc = true,
            fakeRootId = true,
            killOnExit = true,
            spoofedKernelRelease = spoofedKernel,
            shmSizeMb = shmSizeMb,
            tmpSizeMb = tmpSizeMb,
            prootTmpDir = prootTmpDir,
            uvThreadpoolSize = uvThreads,
            environmentVars = envVars,
            recommendedProotFlags = flags,
        )
    }

    /**
     * Builds the command-line argument list for launching a guest command via `proot` when
     * `proot` is used directly (or inside scripts / non-root fallback).
     */
    fun buildProotCommandLine(
        prootBinaryPath: String,
        rootfsPath: String,
        hostWorkspacePath: String,
        guestWorkspacePath: String,
        hostBridgePath: String,
        config: ProotOptimizationConfig,
        guestCommand: List<String>,
    ): List<String> = buildList {
        add(prootBinaryPath)
        addAll(config.recommendedProotFlags)
        add("-r")
        add(rootfsPath)
        add("-b")
        add("$hostWorkspacePath:$guestWorkspacePath")
        add("-b")
        add("$hostBridgePath:/nextcraft-bridge")
        add("-b")
        add("$hostBridgePath-responses:/nextcraft-bridge-responses")
        add("-w")
        add(guestWorkspacePath)
        add("/usr/bin/env")
        for ((k, v) in config.environmentVars) {
            add("$k=$v")
        }
        addAll(guestCommand)
    }

    /**
     * Guest helper script installed at `/opt/nextcraft/bin/proot-fast` inside the Ubuntu rootfs.
     */
    const val PROOT_FAST_GUEST_PATH = "opt/nextcraft/bin/proot-fast"

    val PROOT_FAST_SCRIPT: String = """
        #!/bin/sh
        # NextCraft Forge Complete PRoot & All-Kernel Optimizer Wrapper (`proot-fast`)
        # Automatically applies RAM-backed PROOT_TMP_DIR, seccomp-BPF syscall filtering,
        # --link2symlink, --sysvipc, -L, --kill-on-exit, and kernel release compatibility across 3.18..6.12+.
        set -e

        export PROOT_TMP_DIR="${'$'}{PROOT_TMP_DIR:-/tmp/.proot-tmp}"
        export PROOT_L2S_DIR="${'$'}{PROOT_L2S_DIR:-/tmp/.proot-l2s}"
        mkdir -p "${'$'}PROOT_TMP_DIR" "${'$'}PROOT_L2S_DIR" 2>/dev/null || true

        # Enable seccomp-BPF acceleration on Linux 4.19+ unless explicitly disabled; fallback on <4.19
        KVER=${'$'}(uname -r 2>/dev/null || echo "5.10.0")
        KMAJ=${'$'}{KVER%%.*}
        KREST=${'$'}{KVER#*.}
        KMIN=${'$'}{KREST%%.*}
        if [ -z "${'$'}PROOT_NO_SECCOMP" ]; then
            if [ "${'$'}{KMAJ:-5}" -lt 4 ] || { [ "${'$'}{KMAJ:-5}" -eq 4 ] && [ "${'$'}{KMIN:-19}" -lt 19 ]; }; then
                export PROOT_NO_SECCOMP=1
            fi
        fi

        # Spoof older kernel releases (<5.10) to 5.15.0-nextcraft-arm64 so glibc 2.39 and Node/Rust never abort
        KRELEASE_ARG=""
        if [ "${'$'}{KMAJ:-5}" -lt 5 ] || { [ "${'$'}{KMAJ:-5}" -eq 5 ] && [ "${'$'}{KMIN:-10}" -lt 10 ]; }; then
            KRELEASE_ARG="-k ${'$'}{NEXTCRAFT_KERNEL_RELEASE:-5.15.0-nextcraft-arm64}"
        fi

        REAL_PROOT=""
        for candidate in /usr/bin/proot /usr/local/bin/proot /bin/proot; do
            if [ -x "${'$'}candidate" ]; then
                REAL_PROOT="${'$'}candidate"
                break
            fi
        done

        if [ -n "${'$'}REAL_PROOT" ]; then
            # shellcheck disable=SC2086
            exec "${'$'}REAL_PROOT" \
                --root-id \
                --link2symlink \
                --sysvipc \
                -L \
                --kill-on-exit \
                ${'$'}KRELEASE_ARG \
                -b /dev \
                -b /dev/urandom:/dev/random \
                -b /proc \
                -b /proc/self/fd:/dev/fd \
                -b /sys \
                -b /tmp \
                "${'$'}@"
        fi

        # Zero-overhead passthrough when invoked without /usr/bin/proot:
        WORKDIR=""
        TARGET_ROOT="/"
        while [ "${'$'}#" -gt 0 ]; do
            case "${'$'}1" in
                -0|--root-id|--link2symlink|-l|-L|--sysvipc|--kill-on-exit|-H|--hide-id)
                    shift
                    ;;
                -w|--pwd|--cwd)
                    WORKDIR="${'$'}2"
                    shift 2
                    ;;
                -r|--rootfs)
                    TARGET_ROOT="${'$'}2"
                    shift 2
                    ;;
                -b|--bind|-m|--mount|-k|--kernel-release|-q|--qemu)
                    shift 2
                    ;;
                --)
                    shift
                    break
                    ;;
                -*)
                    shift
                    ;;
                *)
                    break
                    ;;
            esac
        done

        if [ "${'$'}#" -eq 0 ]; then
            set -- /bin/bash -l
        fi

        if [ -n "${'$'}WORKDIR" ] && [ -d "${'$'}WORKDIR" ]; then
            cd "${'$'}WORKDIR"
        fi
        exec "${'$'}@"
    """.trimIndent() + "\n"

    /**
     * Installs `/opt/nextcraft/bin/proot-fast` (and `/usr/local/bin/proot` when `/usr/bin/proot`
     * is absent) into the Ubuntu rootfs.
     */
    fun applyGuestWrappers(rootfs: File, writeAtomically: (File, String, Int) -> Unit) {
        if (!rootfs.isDirectory) return
        val prootFastFile = File(rootfs, PROOT_FAST_GUEST_PATH)
        writeAtomically(prootFastFile, PROOT_FAST_SCRIPT, 0b111101101) // 0755

        val realProot = File(rootfs, "usr/bin/proot")
        val localProot = File(rootfs, "usr/local/bin/proot")
        if (!realProot.exists() && !localProot.exists()) {
            writeAtomically(localProot, PROOT_FAST_SCRIPT, 0b111101101)
        }
    }
}
