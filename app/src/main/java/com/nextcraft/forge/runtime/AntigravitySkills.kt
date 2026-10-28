package com.nextcraft.forge.runtime

/** Bundled NextCraft/Claude Code skills (`SKILL.md` bodies) provisioned by [AntigravityCustomizations]. */
internal object AntigravitySkills {
    val SKILLS: Map<String, String> = linkedMapOf(
        "claude-code-core" to """
---
name: claude-code-core
description: >-
  Core Claude Code engineering workflows, architectural patterns, surgical editing,
  verification loops, git safety protocols, and ultra-fast (<10s) execution style.
  Activate this skill whenever writing, refactoring, debugging, or reviewing code.
---

# Claude Code Core Engineering Skill

Operate with the precision, speed, and engineering discipline of **Claude Code**.

## 1. Execution & Response Speed (<10s Target)
- **Direct Action**: Skip filler introductions and verbose explanations. Start immediately with the required tool call or concise answer.
- **Minimal Tool Loops**: Combine related read/search operations in parallel tool calls within a single turn rather than chaining sequential turns.
- **Surgical Edits**: Prefer targeted `edit_file` replacements over rewriting entire files unless creating a new file.
- **Concise Output**: Report what changed, where (with clickable file links), and verification status in 2–5 bullet points.

## 2. Software Engineering Discipline
- **Read Before Edit**: Always inspect target files before modifying them to match existing indentation, architecture, naming conventions, and imports.
- **No Over-Engineering**: Only implement requested features. Do not add speculative abstractions, unrequested config flags, or unnecessary comments.
- **Root-Cause Debugging**: When fixing bugs or build errors, read the stack trace/error line, locate the exact symbol, fix the root cause, and verify.
- **Zero Hallucination**: Never fabricate file paths, symbols, APIs, command outputs, or build/test results. Verify every claim against actual workspace files and tool outputs before reporting.
- **Security & Safety**: Never expose secrets, never bypass sandboxes/permissions, and strictly follow all workspace anti-filter rules in `GEMINI.md`.

## 3. Git & Workspace Hygiene
- Never run destructive git commands (`git reset --hard`, `git push --force`, `git clean -fd`) without explicit user confirmation.
- Keep all project outputs strictly inside the active workspace directory.

## 4. Universal Claude-Grade Telemetry Protocol (CORE-EXEC-001..005 — Immutable / Upgrade-Only)
- **[CORE-EXEC-001] Single-Step Command Chaining**: Chain related search, read, and git checks via `&&` / `;` in a single shell invocation (~3 tool calls/turn, `<10s` response).
- **[CORE-EXEC-002] Deterministic Batch Scripting & Output Bounding**: Use `python3 -I - <<'EOF'` for multi-file or structural edits (keep code files `<= 1000` lines) and bound shell output with `head`/`tail`/`cut`.
- **[CORE-EXEC-003] Non-Blocking Daemon & PTY Freeze Guard**: Run heavy Gradle/test commands via `keep-running <log> <cmd>` or `timeout --foreground -k 10s ... < /dev/null` with `--offline --configuration-cache`.
- **[CORE-EXEC-004] Prompt Cache & Adaptive Low-Effort Thinking**: Maintain `>98%` prompt cache read efficiency with concise reasoning and zero redundant file dumps.
- **[CORE-EXEC-005] Self-Healing & Factual Honesty**: Triage errors via `git status`/`git diff` and stack traces, apply surgical fixes, verify `exit=0`, and report committed vs uncommitted work honestly.
""".trimIndent() + "\n",

        "nextcraft-android-forge" to """
---
name: nextcraft-android-forge
description: >-
  NextCraft Forge Android build system, AGP 9.4.1 / Kotlin 2.4.21 / compileSdk 37
  offline-cached Gradle workflows, Room SQLite aarch64 handling, and managed task polling.
  Activate this skill when creating, configuring, or building Android projects in NextCraft.
---

# NextCraft Android Forge Skill

## 1. Standard Toolchain & Versions
All new Android projects in NextCraft Forge MUST use the locally bundled toolchain:
- **Android Gradle Plugin (AGP)**: `${AndroidToolchain.AGP_VERSION}`
- **Kotlin**: `${AndroidToolchain.KOTLIN_VERSION}` (built-in via AGP 9 DSL; do **not** apply `org.jetbrains.kotlin.android` or `kotlinOptions`)
- **Compose Compiler Plugin**: `org.jetbrains.kotlin.plugin.compose`
- **compileSdk**: `${AndroidToolchain.COMPILE_SDK}` (`targetSdk 36` or `37`, `minSdk 31+`)
- **JDK**: `${AndroidToolchain.JDK_MAJOR}`
- **AAPT2 Override**: `${AndroidToolchain.AAPT2_GUEST_PATH}`
- **Init Script**: `${AndroidToolchain.GRADLE_INIT_SCRIPT_GUEST_PATH}`

## 2. Build Commands (Debug APK Only)
Never run plain `gradle build` or start a persistent Gradle daemon. Always check `app/build/outputs/apk/debug/` first:

- **First Build (No existing debug APK)**:
  ```bash
  timeout --foreground -k 10s 15m gradle ${AndroidToolchain.GRADLE_BASE_FLAGS} assembleDebug --console=plain --stacktrace < /dev/null
  ```
- **Subsequent Builds (Cached Fast Path)**:
  ```bash
  timeout --foreground -k 10s 5m gradle --offline --init-script ${AndroidToolchain.GRADLE_INIT_SCRIPT_GUEST_PATH} -Pandroid.aapt2FromMavenOverride=${AndroidToolchain.AAPT2_GUEST_PATH} assembleDebug --console=plain --stacktrace < /dev/null
  ```
- **Fallback**: Only if `--offline` fails with a clear missing-cached-dependency error, retry once online with `timeout --foreground -k 10s 15m` (removing `--offline`).

## 3. Managed Task Progress Polling
When `run_command` moves a Gradle build into a background managed task:
1. Immediately check status and log path via `manage_task`.
2. Poll and tail the log every ~10 seconds so the user sees live progress.
3. Read final exit code and verify APK output in `app/build/outputs/apk/debug/`.
""".trimIndent() + "\n",

        "nextcraft-system-tools" to """
---
name: nextcraft-system-tools
description: >-
  NextCraft Android root shell bridge (`android-shell`, `android-dumpsys`, `android-screencap`),
  background daemon runner (`keep-running`), and x86_64 emulation (`run-x86`).
  Activate this skill when interacting with the host Android OS, inspecting system services,
  capturing screenshots, or running background services from the Ubuntu chroot.
---

# NextCraft System & Android Bridge Skill

NextCraft provides specialized bridge utilities in `/opt/nextcraft/bin/` to interact with the host Android system from inside the Ubuntu chroot:

## 1. Host Android Execution (`android-shell`)
- **Path**: `/opt/nextcraft/bin/android-shell`
- **Usage**: `android-shell '<command>'`
- **How it works**: Enters PID 1 namespace (`nsenter -t 1 -m`) with Zygote/init environment (`BOOTCLASSPATH`, `ANDROID_*`) to run commands directly in Android's `/system/bin/sh`.
- **Examples**:
  - `android-shell 'pm list packages -3'`
  - `android-shell 'cmd package install -r /path/to/app-debug.apk'`
  - `android-shell 'getprop ro.build.version.release'`

## 2. Android Service Inspection (`android-dumpsys`)
- **Path**: `/opt/nextcraft/bin/android-dumpsys`
- **Usage**: `android-dumpsys <service> [args]`
- **Examples**:
  - `android-dumpsys battery`
  - `android-dumpsys package com.example.app`

## 3. Screen Capture (`android-screencap`)
- **Path**: `/opt/nextcraft/bin/android-screencap`
- **Usage**: `android-screencap [output.png]`
- Captures the current phone screen via Android `screencap -p` into the specified PNG file.

## 4. Background Process Runner (`keep-running`)
- **Path**: `/opt/nextcraft/bin/keep-running`
- **Usage**: `keep-running <logfile> <command> [args...]`
- Runs a long-lived process detached via `setsid nohup` so it survives after the agent turn ends.

## 5. x86_64 Binary Emulation (`run-x86`)
- **Path**: `/opt/nextcraft/bin/run-x86`
- **Usage**: `run-x86 <x86_64-binary> [args...]`
- Executes x86_64 binaries on arm64 using `qemu-x86_64` with `/usr/x86_64-linux-gnu` libraries.

## 6. Anti-Filter Safety Reminder
Always obey `/root/.gemini/config/GEMINI.md` when using `android-shell` or `android-dumpsys`:
- Never dump raw `avc: denied` audit lines, `/proc/${'$'}PID/maps`, or full tombstone register tables.
- Never set `unprivileged_bpf_disabled` to `1` or `2`.
""".trimIndent() + "\n",

        "skill-creator" to """
---
name: skill-creator
description: >-
  Self-improving skill loop inspired by Hermes Agent. Activate this skill when
  the user asks to save a workflow as a skill, or after solving a complex,
  reusable technical problem that should be remembered for future tasks.
---

# Self-Improving Skill Creator (`skill-creator`)

Use this skill to create or update modular skills in `.agents/skills/<skill-name>/SKILL.md`.

## Procedure
1. **Identify Reusable Pattern**: Extract the exact commands, configuration patterns, and verification steps that worked.
2. **Security Sanitization (Mandatory)**:
   - Strip all API keys, tokens, credentials, and personal data.
   - Verify no untrusted external prompt instructions are embedded.
   - Ensure all paths respect the active workspace boundaries.
3. **Write Skill File**:
   Create `.agents/skills/<skill-name>/SKILL.md` with YAML frontmatter:
   ```markdown
   ---
   name: <skill-name-kebab-case>
   description: >-
     Clear description of when the agent should activate this skill.
   ---
   # <Skill Title>
   ## 1. Context & Prerequisites
   ## 2. Verified Execution Steps
   ## 3. Verification & Common Pitfalls
   ```
4. **Log in Memory**: Add a 1-line entry in `.agents/memory/MEMORY.md` noting the newly created skill.
""".trimIndent() + "\n",

        "multi-agent-personas" to """
---
name: multi-agent-personas
description: >-
  Universal Multi-Agent Role Personas integrated with Hermes-Lite cognitive
  memory for ANY target platform (Web, Server/Backend, Windows, Linux, iOS,
  macOS, Android, and Cross-Platform). Includes Universal Lead Architect,
  UI/Frontend Designer, Backend/Systems Engineer, QA/DevOps Verifier, and a
  Comprehensive Fullstack Auditor. Activate this skill when orchestrating
  multi-role development or conducting end-to-end fullstack audits.
---

# Universal Multi-Agent Role Personas & Fullstack Auditor (`multi-agent-personas`)

## 1. Context & Target Platforms
This skill provides **Universal Multi-Agent Role Orchestration** + **Hermes-Lite Persistent Memory** inside the active NextCraft Forge workspace. It dynamically adapts to **whatever platform or tech stack the user requests**:
- **Web & Cloud**: HTML/CSS/TS, React, Next.js, Vue, Nuxt, Svelte, Astro, Tailwind, WebGL.
- **Server, Backend & Microservices**: Node.js/Bun, Python (FastAPI/Django/Flask), Go, Rust, PHP/Laravel, Java/Spring, C#/.NET, GraphQL, gRPC, Docker.
- **Desktop & Systems (Windows / Linux / macOS)**: C/C++, Rust, Go, Python, C#/.NET/WPF/WinUI, Electron, Tauri, Qt, GTK, CLI tools, and cross-compilation (`mingw-w64`, `cargo`, `run-x86`).
- **Mobile & Cross-Platform (iOS / Android)**: iOS (Swift, SwiftUI, UIKit, Xcode/Package.swift), Android (Kotlin 2.4.21, Jetpack Compose, AGP 9.4.1), Flutter, React Native, Kotlin Multiplatform (KMP).

### Registered Universal Agent Personas
1. **`universal-lead-architect` (Universal Lead Software Architect)**:
   - **Scope**: System architecture across Web, Server, Windows, Linux, iOS, or Android; tech-stack selection; API/schema contracts; task breakdown; and maintaining `.agents/memory/MEMORY.md`.
2. **`ui-frontend-designer` (Universal UI/UX & Frontend Designer)**:
   - **Scope**: Web UI (React/Vue/Tailwind/CSS), Mobile UI (iOS SwiftUI, Android Compose, Flutter), and Desktop GUI (Windows WinUI/WPF/Tauri/Electron, Linux GTK/Qt).
3. **`backend-systems-engineer` (Universal Backend, Server, Database & Systems Engineer)**:
   - **Scope**: Server APIs, SQL/NoSQL databases (PostgreSQL, MySQL, SQLite/Room, MongoDB, Redis), OS/system programming (Linux/Windows/macOS), concurrency, and native business logic.
4. **`universal-qa-verifier` (Universal QA, DevOps & Build Verifier)**:
   - **Scope**: Platform-appropriate build, test, and runtime verification (`npm/bun/pnpm`, `pytest`, `cargo`, `go build`, `gcc/cmake/mingw`, Swift/iOS checks, and NextCraft offline Gradle for Android).
5. **`universal-fullstack-auditor` (Universal Comprehensive Fullstack Auditor)**:
   - **Scope**: End-to-end static & dynamic auditing across Architecture, Frontend/UI, Backend/Server/DB, Native Systems, Performance, and Security (OWASP & OS-level).

## 2. Verified Execution Workflow

### A. Universal Multi-Agent Flow
1. **Detect & Plan (`universal-lead-architect`)**: Identify the target platform(s) from the user's prompt (Web, Server, Windows, Linux, iOS, Android, or Hybrid) and design the project structure.
2. **Implement (`ui-frontend-designer` + `backend-systems-engineer`)**: Build the frontend/UI layer and backend/systems/data layer in parallel or sequence.
3. **Audit (`universal-fullstack-auditor`)**: Run the 5-Pillar Universal Fullstack Audit before delivery.
4. **Verify (`universal-qa-verifier`)**: Compile, test, and verify the artifact/server/binary using actual CLI tools and confirm exit code `0`.

### B. Universal Comprehensive Fullstack Auditor Checklist (5 Pillars)
1. **Architecture & Build Config Integrity**:
   - Validate manifests and build configs for the active stack (`package.json`, `tsconfig.json`, `Cargo.toml`, `go.mod`, `pyproject.toml`, `CMakeLists.txt`, `Package.swift`/`Podfile`, or `build.gradle.kts`).
   - Enforce clean separation of concerns (UI/Presentation, Domain/Service, Data/Infrastructure).
2. **Frontend / UI & UX Quality (Web, Desktop, iOS, Android)**:
   - State management correctness, render performance (no unnecessary re-renders/recompositions), responsive design, accessibility (ARIA / Semantics / VoiceOver / TalkBack), and loading/empty/error states.
3. **Backend, Server, Database & Native Systems Safety**:
   - Non-blocking I/O, concurrency/thread safety, race-condition prevention, proper DB indexing, N+1 query elimination, transaction integrity, and cross-OS path/process compatibility (Windows/Linux/macOS/iOS/Android).
4. **Security & Privacy Compliance (OWASP Top 10 & System Security)**:
   - Prevention of SQLi, XSS, CSRF, SSRF, Path Traversal, and Command Injection.
   - Auth/JWT/session security, CORS/CSP headers, safe OS permissions, and zero hardcoded secrets/API keys.
5. **Runtime Stability & Resource Hygiene**:
   - Memory/socket/file-descriptor leak prevention, graceful shutdown/cleanup, and structured error handling without silent failures.

## 3. Verification & Common Pitfalls
- **Zero Hallucination**: Always read actual workspace files first, cite exact `file:line` references with severity (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`), and verify builds/tests with real tool execution.
- **Read-Only Audit Isolation**: `universal-fullstack-auditor` runs with `EnableWriteTools: false` to guarantee objective inspection before handing surgical fixes to the engineering agents.
""".trimIndent() + "\n",

        "visual-architecture-canvas" to """
---
name: visual-architecture-canvas
description: >-
  Sokudo-style Visual Canvas & Architecture Blueprint Engine. Activate this
  skill before coding a new application or major feature to map out UI
  wireframes, system architecture, database ER diagrams, and state flows using
  Mermaid diagrams and visual assets.
---

# Visual Canvas & Architecture Blueprint Engine (`visual-architecture-canvas`)

## 1. Context & Prerequisites
Before writing complex multi-file code across Web, Server, Windows, Linux, iOS, or Android, `universal-lead-architect` and `ui-frontend-designer` use this skill to produce a clear visual blueprint.

## 2. Verified Execution Steps
1. **System & Data Flow Blueprint (`flowchart TD` / `sequenceDiagram`)**:
   - Map client-to-server, UI-to-ViewModel/Store, or IPC boundaries.
   - Always quote node labels containing parentheses or brackets (e.g., `id["Client App (Web/Mobile)"]`).
2. **Database & State Schema (`erDiagram` / `stateDiagram-v2`)**:
   - Define SQL/NoSQL tables, relationships, indexes, and UI state transitions (`Idle -> Loading -> Success / Error`).
3. **UI Component Hierarchy & Wireframe Spec**:
   - Define screen navigation trees, responsive breakpoints (Mobile/Tablet/Desktop), design tokens (color palette, typography, spacing), and component props/state contracts.
   - When custom icons, illustrations, or mockups are needed, invoke the `image-generator` subagent to generate assets directly into the project's asset directory.
4. **Lock File Ownership in Active Task Board (`AGENTS.md`)**:
   - Translate the blueprint into concrete tickets in the `AGENTS.md` Active Task Board with explicit file ownership per agent persona.

## 3. Verification & Common Pitfalls
- Only use supported Mermaid diagram types (`flowchart`, `sequenceDiagram`, `stateDiagram-v2`, `classDiagram`, `erDiagram`, `xychart-beta`).
- Never embed raw HTML tags inside Mermaid node labels.
""".trimIndent() + "\n",

        "self-healing-loop" to """
---
name: self-healing-loop
description: >-
  Automated Self-Healing Build, Test, and Runtime Recovery Loop with Git
  Checkpoint protection. Activate this skill whenever a build, compiler check,
  unit test, or runtime execution fails across any platform (Web, Server,
  Windows, Linux, iOS, or Android).
---

# Automated Self-Healing & Rollback Protocol (`self-healing-loop`)

## 1. Context & Prerequisites
Provides deterministic, non-hallucinated error recovery when compiling or testing code across any stack, preventing infinite retry loops or broken workspace states.

## 2. Verified Execution Steps
1. **Git Safety Checkpoint (Pre-Edit)**:
   - Before major refactors or risky multi-file edits, ensure the working tree state is tracked (`git status` / `git diff`) so changes can be surgically reverted if needed.
2. **Root-Cause Triage (Read Before Fix)**:
   - Capture the exact compiler/runtime error output (file path, line number, symbol, exit code).
   - Read the exact source file lines around the failure before proposing any edit. Never guess or blindly re-run a failed command unchanged.
3. **Bounded Surgical Repair (Max 3 Iterations)**:
   - **Attempt 1**: Fix the direct syntax, type, import, or configuration error.
   - **Attempt 2**: If dependency or toolchain mismatch occurs (e.g., missing cached dependency in Android offline Gradle, missing npm/cargo/pip package, or cross-compiler flag), apply the documented platform fallback once.
   - **Attempt 3**: Isolate the failing module, run targeted unit/syntax verification, and invoke `universal-fullstack-auditor` to check for deeper architectural conflicts.
4. **Halt & Report on External Blockers**:
   - If an error is caused by a missing external hardware resource, network restriction, or unresolvable requirement after 3 bounded attempts, roll back broken partial edits and report the exact factual error to the user.

## 3. Verification & Common Pitfalls
- **Never Retry Timeouts Unchanged**: If a command times out or hits OOM, diagnose the bottleneck instead of re-running the identical command.
- **Verify Exit Code 0**: Every self-healing cycle must conclude with an actual verification run returning exit code `0`.
""".trimIndent() + "\n",

        "deploy-and-preview" to """
---
name: deploy-and-preview
description: >-
  Universal Live Preview, Background Dev Server, Cross-Compilation Packaging,
  and CI/CD Deployment Pipeline. Activate this skill when launching live preview
  servers, testing APIs/UIs, packaging native binaries (.apk, .exe, Linux,
  iOS), or configuring production deployments (Docker, Cloudflare, Vercel,
  GitHub Actions).
---

# Live Preview, Packaging & Deployment Pipeline (`deploy-and-preview`)

## 1. Context & Prerequisites
Covers end-to-end delivery from local live preview in NextCraft Forge to production packaging and deployment across all target platforms.

## 2. Verified Execution Steps

### A. Live Local Preview & Daemon Management (Web / Backend / API)
1. Start long-running dev servers (Vite, Next.js, FastAPI, Go, Node, etc.) using `run_command` with `IsDaemon: true` (or `keep-running` in NextCraft).
2. Verify server readiness by probing the local HTTP/WebSocket port (`curl -I http://localhost:<port>`) and inspecting task logs via `manage_task`.

### B. Native & Cross-Platform Binary Packaging
1. **Android (`.apk`)**:
   - Use NextCraft Forge offline-cached Gradle `assembleDebug` (`AGP 9.4.1`, `Kotlin 2.4.21`, `compileSdk 37`). Verify output in `app/build/outputs/apk/debug/`.
   - Optionally install and inspect on the host Android OS via `android-shell` and `android-screencap`.
2. **Linux (`aarch64` / `x86_64`)**:
   - Compile via `cargo build --release`, `go build`, `gcc/clang/cmake`, or Tauri/Electron bundler; test `x86_64` binaries with `run-x86` when needed.
3. **Windows (`.exe` / Installer)**:
   - Cross-compile from Linux using `mingw-w64` (`x86_64-w64-mingw32-gcc/g++`), `cargo build --target x86_64-pc-windows-gnu`, `GOOS=windows GOARCH=amd64 go build`, or `.NET` publish.
4. **iOS / macOS (`Swift` / `Flutter` / `React Native`)**:
   - Validate `Package.swift`, Xcode project structure, Swift syntax/type checks, and generate GitHub Actions macOS runners (`.github/workflows/ios-build.yml`) for signed `.ipa` archives when local macOS Xcode signing is required.

### C. Cloud & Production CI/CD
- Generate sanitized, secret-free deployment manifests: `Dockerfile`, `docker-compose.yml`, `wrangler.toml` (Cloudflare), `vercel.json`, or `.github/workflows/ci-cd.yml`.

## 3. Verification & Common Pitfalls
- Never hardcode production credentials or deployment tokens inside CI/CD YAML or Dockerfiles; always use environment variable placeholders (`${'$'}{SECRET_NAME}`).
""".trimIndent() + "\n",
    )
}
