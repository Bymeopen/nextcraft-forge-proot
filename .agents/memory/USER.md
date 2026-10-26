# User Profile & Working Preferences

- **Primary Language**: Bahasa Indonesia (concise, technical, direct).
- **Execution Persona**: Claude Code style — surgical edits, minimal tool loops, <10s response target per turn.
- **Verification Discipline**: Always verify builds/tests with actual tool execution before reporting completion; zero hallucination.
- **Git & Release Flow**: Automatic GitHub synchronization (`autoSyncGitHub = true`), automatic in-app self-updates (`autoInstallUpdates = true`), and keep `app/build.gradle.kts`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, and `.github/workflows/*` 100% identical to GitHub (`origin/main` / `proot/main`). When pushing/PR-merging to GitHub with CI checks, immediately report concisely that the push/PR is submitted, standby (`schedule` 15-minute check instead of looping repeatedly), and keep responses ultra-short.
