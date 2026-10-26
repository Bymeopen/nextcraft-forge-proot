# Long-Term Project & Architecture Memory (`nextcraft-forge`)

## 1. System & Toolchain Snapshot
- **Target Platform**: Android (`arm64-v8a`, `minSdk 26`, `targetSdk 35`, `compileSdk = 37` with `/root/android-sdk/platforms/android-37` & `android-37.2` both installed locally).
- **Build System**: Gradle `9.8.1`, AGP `9.4.1` (built-in Kotlin `2.4.21`, Compose plugin `org.jetbrains.kotlin.plugin.compose`, JDK `21` targeting Java `17` bytecode). Keep `app/build.gradle.kts`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, and `.github/workflows/*` 100% identical to GitHub remote (`origin/main` / `proot/main`).
- **Guest Runtime**: Ubuntu 24.04.5 LTS ARM64 Root Chroot & Non-Root PRoot fallback with `ProotAndChrootOptimizer` (kernels 3.18–6.12+ GKI/16KB page size) and `DeviceHardwareOptimizer` (Snapdragon/MediaTek/UNISOC/Exynos/Tensor, 6–16GB RAM + ZRAM/SwapRAM).

## 2. Key Architectural Decisions
- **2026-10-26 (UI/UX Safety & Auto-Push on Build)**: Wrapped all 1-tap destructive actions (`Merge PR`, `Delete Branch`, `Delete Snapshot`, `Delete API Key`, `Delete SSH Host`, `Delete Connector/Skill`, `Delete/Clear Chat`, `Revert File/All`) with `ConfirmedAction`/confirmation modals, unified `glassSolid` state through `MainViewModel`/`ForgeUiState`, removed redundant `CODE_VIEWER` launcher duplicate in `ToolsScreen.kt`, added system prompt/MCP overhead to `ChatUsageSheet`, enforced `48.dp` touch target on `ForgeGlyphPillIcon`, and wired automatic GitHub commit+push (`syncProjectWithGitHub`) after every successful Android build (`MainViewModel.runAndroidBuild`) and Build Matrix run (`BuildMatrixController.runSelected`).
- **2026-10-26**: Consolidated **Sokudo Universal Multi-Agent Suite** (`multi-agent-personas`, `visual-architecture-canvas`, `self-healing-loop`, `deploy-and-preview`, 5 Role Personas, 5-Pillar Auditor, and Kanban Task Board) + **Hermes-Lite v2** into `AGENTS.md` and `AntigravityCustomizations.kt`, and synchronized portable runtime fixes (`e0d072a`, `ca101f6`, `ff8ed5a`, `8b0eddd`) with `nextcraft-forge-proot`.
- **2026-10-26**: Upgraded to **Hermes-Lite v2** (`AGENTS.md`, `HERMES_LITE_REPORT.md`, `.agents/hooks.json` intercepting `run_command|write_file|edit_file`, `security_gate.py` with Memory/Skill Guard & 24 KB quota check, `session_search.py` SQLite FTS5 + BM25, `context_compressor.py`, `skill_hub.py`, `hermes_init.py`, and `web_and_ui_tool.py`) both in `/workspace/nextcraft-forge` and globally via `AntigravityCustomizations.kt`.
- **2026-10-25**: Fixed local self-update version downgrade (`versionCode = 1750`, `pm install -r -d` in `SilentSelfUpdate.kt`) and enabled automatic GitHub sync on startup and after agent turns (`--rebase=merges`).
- **2026-10-25**: Merged open PRs (`#103`, `#102`, `#97`, `#90`, `#89`, `#88`, `#87`, `#86`, `#85`, `#84`, `#82`, `#81`, `#34`) and aligned `compileSdk` to `release(37) { minorApiLevel = 2 }`.

## 3. Custom Skills Index
- `skill-creator`: `.agents/skills/skill-creator/SKILL.md` — Closed-loop generator for creating and sanitizing reusable skills.
- `multi-agent-personas`: `.agents/skills/multi-agent-personas/SKILL.md` — 5 Universal Agent Personas & 5-Pillar Fullstack Auditor.
- `visual-architecture-canvas`: `.agents/skills/visual-architecture-canvas/SKILL.md` — Mermaid system/DB/UI blueprint engine.
- `self-healing-loop`: `.agents/skills/self-healing-loop/SKILL.md` — Git-checkpointed max-3-iteration surgical repair loop.
- `deploy-and-preview`: `.agents/skills/deploy-and-preview/SKILL.md` — Live daemon preview, cross-compilation (`.apk`, `.exe`, Linux, iOS), and CI/CD.
