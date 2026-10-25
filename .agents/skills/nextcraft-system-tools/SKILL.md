---
name: nextcraft-system-tools
description: >-
  NextCraft background daemon runner (`keep-running`). Activate this skill when a dev server or
  long-running process needs to outlive the current agent turn inside the PRoot runtime.
---

# NextCraft System Bridge Skill

NextCraft provides one specialized bridge utility in `/opt/nextcraft/bin/` for the PRoot runtime:

## Background Process Runner (`keep-running`)
- **Path**: `/opt/nextcraft/bin/keep-running`
- **Usage**: `keep-running <logfile> <command> [args...]`
- Runs a long-lived process detached via `setsid nohup` so it survives after the agent turn ends.

There is no host Android shell bridge, service inspector, screen capture, or x86_64 emulation here:
PRoot has no real root (unlike NextCraft Forge's Root Chroot build), so tools that need to join
Android's own process namespace or run as a privileged UID (an `android-shell`/`android-dumpsys`/
`android-screencap`/`run-x86` kind of bridge) are not available. Do not invoke commands under
`/opt/nextcraft/bin/` other than `keep-running` -- they do not exist in this runtime.
