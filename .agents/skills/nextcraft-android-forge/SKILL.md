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
- **Android Gradle Plugin (AGP)**: `9.4.1`
- **Kotlin**: `2.4.21` (built-in via AGP 9 DSL; do **not** apply `org.jetbrains.kotlin.android` or `kotlinOptions`)
- **Compose Compiler Plugin**: `org.jetbrains.kotlin.plugin.compose`
- **compileSdk**: `37` (`targetSdk 36` or `37`, `minSdk 26+`)
- **JDK**: `21`
- **AAPT2 Override**: `/root/android-sdk/build-tools/36.0.0/aapt2`
- **Init Script**: `/root/.gradle/init.d/nextcraft-android.gradle`

## 2. Build Commands (Debug APK Only)
Never run plain `gradle build` or start a persistent Gradle daemon. Always check `app/build/outputs/apk/debug/` first:

- **First Build (No existing debug APK)**:
  ```bash
  timeout 15m gradle --init-script /root/.gradle/init.d/nextcraft-android.gradle -Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/36.0.0/aapt2 assembleDebug --console=plain --stacktrace
  ```
- **Subsequent Builds (Cached Fast Path)**:
  ```bash
  timeout 5m gradle --offline --init-script /root/.gradle/init.d/nextcraft-android.gradle -Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/36.0.0/aapt2 assembleDebug --console=plain --stacktrace
  ```
- **Fallback**: Only if `--offline` fails with a clear missing-cached-dependency error, retry once online with `timeout 15m` (removing `--offline`).

## 3. Managed Task Progress Polling
When `run_command` moves a Gradle build into a background managed task:
1. Immediately check status and log path via `manage_task`.
2. Poll and tail the log every ~10 seconds so the user sees live progress.
3. Read final exit code and verify APK output in `app/build/outputs/apk/debug/`.
