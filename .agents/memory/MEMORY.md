# Long-Term Project & Architecture Memory (`nextcraft-forge`)

## 1. System & Toolchain Snapshot
- **Target Platform**: Android (`arm64-v8a`, `minSdk 26`, `targetSdk 35`, `compileSdk 37` with `minorApiLevel = 2` / `android-37.2`).
- **Build System**: Gradle `9.8.1`, AGP `9.4.1` (built-in Kotlin `2.4.21`, Compose plugin `org.jetbrains.kotlin.plugin.compose`, JDK `21` targeting Java `17` bytecode).
- **Guest Runtime**: Ubuntu 24.04.5 LTS ARM64 Root Chroot & Non-Root PRoot fallback with `ProotAndChrootOptimizer` (kernels 3.18–6.12+ GKI/16KB page size) and `DeviceHardwareOptimizer` (Snapdragon/MediaTek/UNISOC/Exynos/Tensor, 6–16GB RAM + ZRAM/SwapRAM).

## 2. Key Architectural Decisions
- **2026-10-25**: Integrated Hermes-Lite zero-overhead autonomous agent architecture (`AGENTS.md`, `.agents/hooks.json`, `.agents/scripts/security_gate.py`, `.agents/memory/{USER,MEMORY}.md`, and `skill-creator`) both in `/workspace/nextcraft-forge` and globally across all guest workspaces via `AntigravityCustomizations.kt`.
- **2026-10-25**: Enabled automatic app update installation on `silentCheck` (`onResume` + 15-minute `UpdateCheckWorker`) and set `autoSyncGitHub = true` by default.
- **2026-10-25**: Merged open PRs (`#97`, `#102`, `#103`, and Dependabot workflow/Gradle updates `#81..#90`) and aligned `compileSdk` to `release(37) { minorApiLevel = 2 }`.

## 3. Custom Skills Index
- `skill-creator`: `.agents/skills/skill-creator/SKILL.md` — Closed-loop generator for creating and sanitizing reusable skills.
