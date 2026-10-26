# Interactive terminal

The Terminal screen is a full VT100/xterm terminal, so editors, pagers and TUIs (`vim`, `nano`, `htop`, `tmux`,
`mc`, `lazygit`…) run in it.

## How it works
- **One general shell for the whole app, not tied to a project.** With the root chroot installed and root granted it is
  `su -mm -c <chroot-exec.sh … /bin/bash -l>` started in `/root` (built by `RuntimeInstaller.prepareChrootShellCommand`,
  so the environment and secrets handling are the same as every other chroot launch). Without them it is the Android
  shell (`/system/bin/sh`) in the app's files directory. Projects are at `/workspace/<name>`: the "Go to <project>"
  chip does a `cd`, and "Run in terminal" from chat changes into the project first. `claude` starts the Claude Code
  interface (a full-screen TUI) in this terminal.
- **PTY + emulator:** the Termux `terminal-emulator` library spawns the process on its own PTY and parses its output
  into a character grid; `terminal-view` draws it. `TerminalView` resizes the PTY to the view (and to the soft
  keyboard), so full-screen programs reflow.
- **Input:** the soft keyboard types straight into the shell. The key row adds Esc, Tab, Ctrl/Alt (armed for the next
  key), arrows, Home/End/PgUp/PgDn, `^C` and `- / | ~`. Pinch zooms the font.
- **Chat integration:** "Run in terminal" on a code block and the quick-command chips type the command and Enter
  into the shell (queued until the shell is up).
- Code: `ui/TerminalScreen.kt` (screen), `ui/ForgeTerminalClients.kt` (emulator callbacks, theming),
  `MainViewModel` ("Interactive terminal" section: sessions, restart, input queue).

## Dependency and licence
`com.github.termux.termux-app:terminal-emulator` and `:terminal-view`, pinned to `v0.118.0`, resolved from JitPack
(the repository is restricted to that one group in `settings.gradle.kts`).

**Licence (decision recorded 2026-10-20: use the Termux libraries, treat them as GPLv3).** The `termux/termux-app`
repository is GPLv3-only with an exception for code derived from Jack Palevich's Android-Terminal-Emulator
(Apache 2.0). Its LICENSE points at the `terminal-view` and `terminal-emulator` libraries but they carry no licence file
or per-file header of their own, and the published POM says "Other", so the boundary between the Apache and the Termux
parts is not stated. The safe reading is GPLv3.

What that means in practice:
- **Using the app yourself or inside one organisation, without handing the APK to anyone else:** no obligation.
- **Giving the APK to other people (public release, Play Store, clients):** the whole app must be released under GPLv3
  with its source, the licence text and these notices (see `THIRD_PARTY_NOTICES.md`). If that is not acceptable, swap
  the emulator (JediTerm, or a Compose-native one) behind `ForgeTerminalClients.kt` / `TerminalScreen.kt`, or ask the
  Termux maintainers to confirm the two libraries are Apache-2.0 only.

## Known limits
- Closing a session kills its `su` process; children inside the chroot normally get SIGHUP from the closed PTY, but
  that depends on the root manager's `su` and has not been verified on a device.
- Not verified on a device yet: rendering, keyboard behaviour, colour theming per theme, resize with the keyboard.
