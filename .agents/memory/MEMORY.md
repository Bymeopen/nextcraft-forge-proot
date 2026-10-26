# Long-Term Project & Architecture Memory (`nextcraft-forge`)

## 1. System & Toolchain Snapshot
- **Target Platform**: Android (`arm64-v8a`, `minSdk 26`, `targetSdk 35`, `compileSdk 37` with `minorApiLevel = 2` / `android-37.2`).
- **Build System**: Gradle `9.8.1`, AGP `9.4.1` (built-in Kotlin `2.4.21`, Compose plugin `org.jetbrains.kotlin.plugin.compose`, JDK `21` targeting Java `17` bytecode).
- **Guest Runtime**: Ubuntu 24.04.5 LTS ARM64 Root Chroot & Non-Root PRoot fallback with `ProotAndChrootOptimizer` (kernels 3.18–6.12+ GKI/16KB page size) and `DeviceHardwareOptimizer` (Snapdragon/MediaTek/UNISOC/Exynos/Tensor, 6–16GB RAM + ZRAM/SwapRAM).

## 2. Key Architectural Decisions
- **2026-10-26**: Upgraded to **Hermes-Lite v2** (`AGENTS.md`, `HERMES_LITE_REPORT.md`, `.agents/hooks.json` intercepting `run_command|write_file|edit_file`, `security_gate.py` with Memory/Skill Guard & 24 KB quota check, `session_search.py` SQLite FTS5 + BM25, `context_compressor.py`, `skill_hub.py`, `hermes_init.py`, and `web_and_ui_tool.py`) both in `/workspace/nextcraft-forge` and globally via `AntigravityCustomizations.kt`.
- **2026-10-25**: Fixed local self-update version downgrade (`versionCode = 1750`, `pm install -r -d` in `SilentSelfUpdate.kt`) and enabled automatic GitHub sync on startup and after agent turns (`--rebase=merges`).
- **2026-10-25**: Merged open PRs (`#103`, `#102`, `#97`, `#90`, `#89`, `#88`, `#87`, `#86`, `#85`, `#84`, `#82`, `#81`, `#34`) and aligned `compileSdk` to `release(37) { minorApiLevel = 2 }`.

## 3. Custom Skills Index
- `skill-creator`: `.agents/skills/skill-creator/SKILL.md` — Closed-loop generator for creating and sanitizing reusable skills.
