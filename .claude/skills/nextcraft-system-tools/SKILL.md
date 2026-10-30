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
- **How it works**: Enters PID 1 namespace (`nsenter -t 1 -m -r -w`) with Zygote's environment (`BOOTCLASSPATH`, `ANDROID_*`) to run commands directly in Android's `/system/bin/sh`.
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
- Never dump raw `avc: denied` audit lines, `/proc/$PID/maps`, or full tombstone register tables.
- Never set `unprivileged_bpf_disabled` to `1` or `2`.
