# On-device test checklist

CI proves the app compiles and the unit tests pass. These checks need a real device.

## Accessibility
- [ ] TalkBack on: every screen reads in a sensible order; icon buttons are named; the terminal key row reads "Escape", "Control (toggle)", etc.
- [ ] Settings > Display > Font size and Display size at maximum (fontScale 2.0): no clipped or overlapping text in the bottom bar, chat, Settings, Tools.
- [ ] Landscape on every tab: the composer and the key row stay reachable above the keyboard.

## Chat polish (this release)
- [ ] Scroll up in a long chat: the "Terbaru / Latest" pill is opaque and does not show text through it.
- [ ] Short user message: the bubble wraps its text and sits at the right edge.
- [ ] Indonesian: the bottom bar shows "Setelan" without truncation.

## Language
- [ ] Switch EN <-> ID: Claude and Google (Antigravity) sign-in, Snapshots and CI/CD messages change language.

## Terminal (blocked from main until the Termux licence is confirmed, see TERMINAL.md)
- [ ] Opening the tab starts a shell in the project directory inside the chroot.
- [ ] `vim` (edit, `:wq`), `htop`, `tmux` (split, detach/attach), `nano`, `less` render and respond.
- [ ] Ctrl toggle + `c` interrupts; Ctrl+`d` ends the shell and the Restart overlay appears; Restart works.
- [ ] Esc, Tab, arrows, Home/End, PgUp/PgDn behave inside vim/htop.
- [ ] Pinch zooms the font; the keyboard opens on tap; rotation keeps the session.
- [ ] Copy transcript and Clear work; quick-command chips run in the live shell.
- [ ] Delete the project: no stray `su` / bash processes remain (`ps -A | grep -E "su|bash"`).
- [ ] Release build (minified): the terminal still starts (JNI keep rule).
