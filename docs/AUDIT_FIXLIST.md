# Audit Fix List — NextCraft Forge (2026-10-26)

Dua putaran pemindaian read-only. Tidak ada kode yang diubah. Semua sitasi `file:baris` diverifikasi dengan membaca baris terkait.

**Cakupan putaran 1**: `LocalFormatGateway`, `OpenRouterRoutingGateway`, `ForgeMcpServer`, `ApiKeyVault`, `AppPreferences`, `RuntimeInstaller`,
`update/*`, `forge_spawn.c`, `NativeSpawnProcess`, `network_security_config.xml`, `MainViewModel` (struktur), gradle/manifest, i18n.
**Cakupan putaran 2**: seluruh `ui/*` (63 file), semua bridge agen & service (`ClaudeRuntimeBridge`, `AntigravityRuntimeBridge`, `DshRuntimeBridge`,
`RuntimeExecutionService`, `RuntimeSetupService`, `AndroidAppInstaller/Receiver`, `BackupManager/Crypto`, `AutoBackup`, `GitHubClient`, `schedule/*`,
controller lainnya), `scripts/**`, `.agents/**`, paket REA (Python), `.github/workflows/*`, `samples/mini-sekolah`.

**Verifikasi nyata**: CI HEAD `e4b9cce` run 761 PASS (unit test, assembleDebug, R8 release, REA). `rea-tests` lokal 134 passed / 25 skipped
(butuh `capstone pyelftools unicorn`). `test-chroot-reaper.sh` PASS. `check-embedded-scripts.py` rc=0. `security_gate.py` diuji dengan 40 payload
(tabel di bagian E). Unit test Android tidak dapat dijalankan di container ini (tanpa SDK). `verify-core-fixes.sh` gagal di tes kernel
`PR_SET_PDEATHSIG` (keterbatasan sandbox, bukan kode aplikasi).

Ringkasan: **0 CRITICAL · 10 HIGH · 31 MEDIUM · 40 LOW**.

---

## Prioritas perbaikan (urut)

| Prio | ID | Sev | Temuan | Lokasi |
|---|---|---|---|---|
| 1 | E1 | HIGH | `security_gate.py` regex `rm` mudah dilewati: `rm -fr /`, `rm -r -f /`, `rm -rf /*`, `rm -rf ~/`, `rm -rf /workspace/x`, `find / -delete`, `shutil.rmtree('/')` semua **allow** | `.agents/scripts/security_gate.py:19` |
| 2 | E2 | HIGH | `dd` ke block device tidak diblok sama sekali (bertentangan dengan AGENTS.md §5.2) | `security_gate.py:17-22` |
| 3 | E3 | HIGH | Gate hanya membaca format `toolCall.{name,args}`; payload gaya Claude Code (`tool_name`/`tool_input`) dan stdin rusak → **allow** (fail-open) | `security_gate.py:93-105` |
| 4 | E4 | HIGH | `edit_file` dengan field `NewContent` / `edits[].newString` tidak dipindai (injeksi/secret ke MEMORY.md lolos) | `security_gate.py:61-66` |
| 5 | E5 | HIGH | `web_and_ui_tool.py fetch` menerima `file://`, tanpa filter private-IP/loopback, tanpa batas ukuran (terbukti membaca `/etc/hostname`) | `.agents/scripts/web_and_ui_tool.py:264-268` |
| 6 | C1 | HIGH | Allowlist Bash auto-approve berisi `gradle`, `javac`, `kotlinc`; Write/Edit di workspace auto-allow → eksekusi kode arbitrer via `build.gradle.kts` tanpa prompt | `runtime/ClaudeRuntimeBridge.kt:1014, 607-609, 1219` |
| 7 | C2 | HIGH | Bypass classifier via `awk -f`, `sed -f`, `jq -f`; `-f` tidak ada di `UNSAFE_TOOL_FLAG_REGEX`; tanpa unit test | `ClaudeRuntimeBridge.kt:1016, 1021-1035, 1177-1186` |
| 8 | C3 | HIGH | Antigravity (`--dangerously-skip-permissions`) dan Dsh (`DSH_PERMISSION_MODE=danger-full-access`) berjalan root di chroot tanpa gate approval; preferensi "interactive_high_risk_approvals" tak berlaku | `AntigravityRuntimeBridge.kt:321`, `DshRuntimeBridge.kt:133, 378-380` |
| 9 | C4 | HIGH | `GradleDaemonReaper` mematikan sesi agy aktif yang sedang menunggu I/O jaringan (CPU delta 0 dalam 1 s) saat `onTrimMemory` | `runtime/GradleDaemonReaper.kt:19-22`, `MainActivity.kt:166` |
| 10 | C5 | HIGH | `BackupManager.restore` menyalin prefs ke disk lalu `edit().apply()` pada instance in-memory lama → restore setting tertimpa | `data/BackupManager.kt:190-200` (perlu uji di perangkat) |
| 11 | B1 | HIGH | `LiveResourceMiniBar` baca `/proc/cpuinfo` + sysfs via `File.readText()` di main dispatcher tiap 5 s (`forceRefresh=true`, plus `registerReceiver` sinkron) | `ui/ForgeComponents.kt:506-518`, `runtime/DeviceHardwareOptimizer.kt:412-429` |

---

## A. Putaran 1 — runtime inti & keamanan

| ID | Sev | Temuan | Lokasi | Status |
|---|---|---|---|---|
| A1 | MEDIUM | Checksum biner Claude Code/Antigravity hanya dari manifest remote yang sama (TOFU, tanpa signature pin) | `RuntimeInstaller.kt:732-739, 773-778, 887-889` | OPEN |
| A2 | MEDIUM | Tidak ada unit test untuk gateway HTTP, `ApiKeyVault`, `AppUpdater.verifyApk`, `NativeSpawnProcess`, `safeChild`/`resolveSafeSymlinkTarget` | `app/src/test` | OPEN |
| A3 | MEDIUM | `MainViewModel` god object: 5738 baris, 311 fungsi, 92 launch, ±9 dependensi di konstruktor | `ui/MainViewModel.kt` | OPEN |
| A4 | MEDIUM | Hook memanggil `python3 scripts/security_gate.py` (relatif); hanya bekerja karena app menyalin ke `<agy-config>/scripts/`; `hermes_init.py:168-174` menyebarkan path rusak yang sama | `.agents/hooks.json:9`, `AntigravityCustomizations.kt:1228` | OPEN |
| A5 | LOW | Vault menghapus ciphertext diam-diam saat AEAD/decode gagal | `data/ApiKeyVault.kt:213-215` | OPEN |
| A6 | LOW | `.git-credentials` plaintext (0600) di rootfs; `.env-*` berisi key dibersihkan setelah 10 menit | `RuntimeInstaller.kt:557, 2160-2162` | OPEN |
| A7 | LOW | `web_preview_inspect` MCP tanpa filter SSRF/private-range | `ForgeMcpServer.kt:470-478` | OPEN |
| A8 | LOW | Gateway menerima provider key asli sebagai bearer | `LocalFormatGateway.kt:819-820` | OPEN |
| A9 | LOW | Fallback `sh -c` di host saat chroot tak ada (pemanggil hanya terminal user, build, git-sync) | `MainViewModel.kt:4324-4331` | OPEN |
| A10 | LOW | `opendir/readdir` setelah fork (non async-signal-safe); hasil `setpgid` parent diabaikan | `cpp/forge_spawn.c:28-38, 227` | OPEN |
| A11 | LOW | `waitpid` polling 20 ms; `getInputStream()` buka stream baru tiap panggilan; pid via refleksi | `NativeSpawnProcess.kt:48-52, 25, 149-153` | OPEN |
| A12 | LOW | `::1` tidak di allowlist cleartext | `res/xml/network_security_config.xml` | OPEN |
| A13 | LOW | Tidak ada `gradlew` wrapper (butuh Gradle 9.x global) | repo root | OPEN |
| A14 | LOW | `AppUpdater` varian tak dikenal fallback ke root manifest | `update/AppUpdater.kt:63-66` | OPEN |

## B. Putaran 2 — UI Compose (`ui/*`, 63 file, 219 composable)

Metrik: 997 `stringResource` vs ±14 literal; 2 dari 19 `items()` tanpa key; 15 `collectAsState` tanpa lifecycle; WebView aman (no JS interface, file access off, skema non-http diblok).

| ID | Sev | Temuan | Lokasi | Status |
|---|---|---|---|---|
| B1 | HIGH | File I/O di main thread tiap 5 s (lihat prioritas #11) | `ForgeComponents.kt:506-518` | OPEN |
| B2 | MEDIUM | `items()` tanpa `key` (inspector node punya `selectedNode`) | `AndroidAndPreviewScreen.kt:472, 984` | OPEN |
| B3 | MEDIUM | ±15 string UI hardcoded Inggris di autocomplete composer (`"Action"`, `"Swarm"`, `"Agent"`, `"File"`, …) | `ChatExtras.kt:886-895, 903-905, 918, 933` | OPEN |
| B4 | MEDIUM | `"RAM"`/`"Swap"` hardcoded; chip "Clean daemons" tinggi ±20dp tanpa `heightIn(min=48dp)` | `ForgeComponents.kt:514, 520, 523, 549-553` | OPEN |
| B5 | MEDIUM | `ForgeThemedCard`/`ForgeElevatedCard` clickable tanpa `Role.Button` (komponen dasar, TalkBack) | `Theme.kt:576, 617` | OPEN |
| B6 | MEDIUM | `parseInlineMarkdown` tanpa `remember`; saat streaming semua blok di-parse ulang tiap token | `MarkdownText.kt:98, 129, 159, 187, 219, 355` | OPEN |
| B7 | MEDIUM | `collectAsState` (bukan `collectAsStateWithLifecycle`) di root app + 14 lokasi | `NextCraftForgeApp.kt:126`, `AgentScreen.kt:141`, `ToolScreensB.kt:56,154,278,279,431` | OPEN |
| B8 | MEDIUM | Sort/filter di hot path tanpa `remember`/`derivedStateOf` | `ClaudeStyleChat.kt:233`, `ProjectsScreen.kt:107-112`, `AgentScreen.kt:304-305` | OPEN |
| B9 | LOW | `Regex` dikompilasi ulang tiap rekomposisi | `AgentScreen.kt:810`, `SettingsScreenModern.kt:636`, `ProjectsScreen.kt:721-722` | OPEN |
| B10 | LOW | SharedPreferences / file crash dibaca di `remember{}` (main thread) | `TerminalScreen.kt:110-115`, `ToolScreensA.kt:183` | OPEN |
| B11 | LOW | `rememberSwipeToDismissBoxState` menangkap callback tanpa key | `ChatExtras.kt:283-291` | OPEN |
| B12 | LOW | WebView factory menangkap `previewUrl` stale; double `loadUrl` komposisi pertama | `AndroidAndPreviewScreen.kt:868, 673` | OPEN |
| B13 | LOW | `destroy()` ganda setelah `onRenderProcessGone`; `openUrl` tanpa allowlist host | `AndroidAndPreviewScreen.kt:859-866, 880-882, 681-696` | OPEN |
| B14 | LOW | `Color.Black/White` hardcoded (bypass token tema) | `ChatImages.kt:106-109, 260`, `ForgeGlass.kt:90` | OPEN |
| B15 | LOW | `map/distinct/take` tiap rekomposisi `ToolStepGroup` | `ChatExtras.kt:103` | OPEN |
| B16 | LOW | `Modifier.composed{}` untuk `forgePopIn` (gunakan `Modifier.Node`) | `ForgeMotion.kt:117` | OPEN |

## C. Putaran 2 — bridge agen, service, data, network, schedule

| ID | Sev | Temuan | Lokasi | Status |
|---|---|---|---|---|
| C1 | HIGH | Allowlist auto-approve berisi `gradle`/`javac`/`kotlinc` (prioritas #6) | `ClaudeRuntimeBridge.kt:1014` | OPEN |
| C2 | HIGH | Bypass `awk -f`/`sed -f`/`jq -f` (prioritas #7) | `ClaudeRuntimeBridge.kt:1016-1035, 1177-1186` | OPEN |
| C3 | HIGH | Antigravity & Dsh tanpa gate approval, root di chroot (prioritas #8) | `AntigravityRuntimeBridge.kt:321`, `DshRuntimeBridge.kt:133, 378-380` | OPEN |
| C4 | HIGH | Reaper mematikan agy aktif saat idle I/O (prioritas #9) | `GradleDaemonReaper.kt:19-22` | OPEN |
| C5 | HIGH | Restore backup tertimpa prefs in-memory lama (prioritas #10) | `BackupManager.kt:190-200` | OPEN |
| C6 | MEDIUM | Bearer token MCP ditulis plaintext ke `$DSH_HOME/cordis.patch.yml` default (tak dibersihkan); `/root/.dsh`, `~/.gemini`, `~/.npmrc`, `~/.netrc` tidak di `CREDENTIAL_PATH_PARTS` → `cat` auto-approve | `DshRuntimeBridge.kt:449-459`, `ForgeMcpServer.kt:649-654`, `ClaudeRuntimeBridge.kt:995-1004, 1104-1107` | OPEN |
| C7 | MEDIUM | `cp`/`mv` `--target-directory=/etc/...` lolos cek outside-workspace (kata berawalan `-` diabaikan) | `ClaudeRuntimeBridge.kt:1046-1048, 1183` | OPEN |
| C8 | MEDIUM | FileBrowser: `canonicalFile` tak menuntaskan dir root-only; `su -c head` mengikuti symlink buatan agen keluar app-data | `runtime/FileBrowser.kt:33-34, 44-45, 54-55` | OPEN |
| C9 | MEDIUM | Silent root `pm install -r -d` untuk APK buatan agen tanpa konfirmasi user (nama paket bebas, downgrade) | `runtime/AndroidAppInstaller.kt:73, 103` | OPEN |
| C10 | MEDIUM | OAuth client ID GitHub CLI (`178c6fc778ccc68e1d6a`) dipakai + scope `repo`+`workflow` (ToS/atribusi; scope luas) | `network/GitHubClient.kt:387-388` | OPEN |
| C11 | MEDIUM | `repo` tool di-`curl` lalu `install -m 0755` ke `/usr/local/bin` hanya cek shebang, tanpa checksum | `runtime/RomStudioController.kt:73-77` | OPEN |
| C12 | MEDIUM | Model dapat membuat jadwal run berulang dari teks balasan `[[schedule: …]]` tanpa konfirmasi user | `schedule/ScheduledTask.kt:63, 75-84` | OPEN |
| C13 | MEDIUM | AutoBackup menimpa (`"wt"`) backup lama sebelum yang baru valid; tanpa temp+rename | `data/AutoBackup.kt:68-73` | OPEN |
| C14 | MEDIUM | `START_REDELIVER_INTENT` memutar ulang `ACTION_REMOVE_*` setelah process death; wake lock jalan tanpa FGS bila di background | `RuntimeSetupService.kt:359, 300-305, 329-335` | OPEN |
| C15 | LOW | Wake lock exec service maks 35 menit, tidak di-re-arm | `RuntimeExecutionService.kt:208, 268` | OPEN |
| C16 | LOW | `SshController.startTunnel` tidak menghapus pid-file | `SshController.kt:128-152`, `ChrootRunner.kt:31-47` | OPEN |
| C17 | LOW | Install APK: tulis seluruh stdin sebelum baca stdout → potensi deadlock pipe 60 s | `AndroidAppInstaller.kt:106-117` | OPEN |
| C18 | LOW | Sweeper `.git/index.lock` hanya cek `pgrep -x git` (race dengan git-lfs/libgit2) | `ChatBranches.kt:399-403, 413-415` | OPEN |
| C19 | LOW | `applyWorkspace` menimpa `.agents/scripts/*` & `SKILL.md` user tiap launch; executable untuk semua | `AntigravityCustomizations.kt:1149-1159` | OPEN |
| C20 | LOW | SharedPreferences + JSON parse di main thread saat startup | `ForgeApplication.kt:17-18` | OPEN |
| C21 | LOW | `GitHubClient` tanpa `instanceFollowRedirects=false` eksplisit | `GitHubClient.kt:174, 193, 213` | OPEN |
| C22 | LOW | Path user berawalan `-` diteruskan ke `rea` sebagai opsi | `ReaController.kt:410, 416` | OPEN |
| C23 | LOW | Backup tak terenkripsi memuat seluruh `workspaces/` (`.env`, `.git`) | `BackupManager.kt:92-95` | OPEN |

## D. Putaran 2 — skrip shell, bundle runtime, REA

| ID | Sev | Temuan | Lokasi | Status |
|---|---|---|---|---|
| D1 | MEDIUM | `unmount-chroot.sh` dengan `ROOTFS=/` → `umount -l/-f` semua mount perangkat; `$ROOTFS` tak divalidasi (chroot-exec.sh juga) | `scripts/runtime-bundles/unmount-chroot.sh:7-13, 29`, `chroot-exec.sh:21-38` | OPEN |
| D2 | MEDIUM | `proot-exec.sh` hanya bind `/pocketdev-bridge-responses`, tidak `/nextcraft-bridge-responses` & tidak ekspor `NEXTCRAFT_*`; `WORKSPACE_BIND` unquoted | `proot-exec.sh:56-59, 95, 104, 106` vs `chroot-exec.sh:250-253, 339` | OPEN |
| D3 | MEDIUM | REA ekstraksi firmware mengandalkan tool eksternal (`unsquashfs`, `cpio`, `jefferson`) untuk keamanan path traversal | `assets/rea/rea/parsers/firmware.py:461-485` | OPEN |
| D4 | MEDIUM | REA: carve 512 MiB + decompress 256 MiB sepenuhnya di RAM | `firmware.py:28-29, 488-497` | OPEN |
| D5 | LOW | `build-core-on-rooted-android.sh`: `$REMOTE` unquoted dalam `su -c`; fallback `rm -rf` tanpa `--one-file-system`; `shasum` tak portabel | `build-core-on-rooted-android.sh:30, 34, 38-39, 72, 74` | OPEN |
| D6 | LOW | Bundle "deterministic" menjalankan `apt-get upgrade` saat build (tidak reproducible) | `build-core-on-rooted-android.sh:55-56, 73` | OPEN |
| D7 | LOW | DNS publik `8.8.8.8`/`1.1.1.1` di-hardcode ke guest | `chroot-exec.sh:256-258`, `proot-exec.sh:56-59` | OPEN |
| D8 | LOW | Reaper: daemon dari perintah sukses tak di-reap; asumsi `setsid` tak fork; `pkill -f "^sleep 300"` di test bisa kena proses lain | `chroot-exec.sh:375-399, 418-430`, `test-chroot-reaper.sh:171-173` | OPEN |
| D9 | LOW | `sync-github.sh` `git add -A` + auto-push ke `origin main` setiap turn agen (file nyasar ikut ter-push) | `scripts/sync-github.sh:100-103`, `MainViewModel.kt:3165-3166` | OPEN |
| D10 | LOW | REA zip read hanya dibatasi ukuran deklarasi header; cert read tanpa cap | `parsers/apk.py:163, 174`, `firmware.py:504-512` | OPEN |
| D11 | LOW | `_run_tool` buka stdin sebelum `try` | `firmware.py:444-452` | OPEN |
| D12 | LOW | `__pycache__` di assets ikut terbungkus ke APK pada build lokal | `app/src/main/assets/rea/` | OPEN |
| D13 | LOW | `.agents/rules/github-sync.md` usang: merujuk `/workspace/mobilebuilder` & `BLOK_1_SAMPAI_7_COMPLETE.md` yang tidak ada | `.agents/rules/github-sync.md` | OPEN |
| D14 | LOW | `verify-core-fixes.sh` abort seluruh suite bila tes kernel PDEATHSIG gagal (tidak tahan sandbox) | `scripts/verify-core-fixes.sh:163-215` | OPEN |

## E. Putaran 2 — `.agents` security gate & CI workflows

| ID | Sev | Temuan | Lokasi | Status |
|---|---|---|---|---|
| E1 | HIGH | Regex `rm` mudah dilewati (prioritas #1) | `security_gate.py:19` | OPEN |
| E2 | HIGH | `dd` tidak diblok (prioritas #2) | `security_gate.py:17-22` | OPEN |
| E3 | HIGH | Format payload tunggal + fail-open (prioritas #3) | `security_gate.py:93-105` | OPEN |
| E4 | HIGH | `edit_file` field alternatif tak dipindai (prioritas #4) | `security_gate.py:61-66` | OPEN |
| E5 | HIGH | `fetch` tanpa allowlist skema/SSRF/limit (prioritas #5) | `web_and_ui_tool.py:264-268` | OPEN |
| E6 | MEDIUM | Pola secret hanya 4 keluarga (AWS `AKIA`, `password=`, Slack `xoxb-` lolos); secret ke `.md` di luar 3 file memori diizinkan | `security_gate.py:32-38, 50-57` | OPEN |
| E7 | MEDIUM | Fork bomb & pipe-to-shell hanya signature (`bomb(){…}`, `bash <(curl …)` lolos) | `security_gate.py:21, 26-27` | OPEN |
| E8 | MEDIUM | Isolasi workspace (§5.1) tidak diterapkan oleh gate | `security_gate.py:59-89` | OPEN |
| E9 | MEDIUM | `secrets.yml` unduh gitleaks tanpa checksum, pipe ke `tar` | `.github/workflows/secrets.yml:23` | OPEN |
| E10 | MEDIUM | Action tidak di-pin SHA di `ci.yml` (release-r8, rea), `release-debug.yml` (`contents: write`), `runtime-bundles.yml`, `sample-sekolah.yml` | lihat baris di masing-masing workflow | OPEN |
| E11 | MEDIUM | `release-debug.yml` menandai build debug sebagai `--latest` tiap push ke main → updater release-signed mengambil manifest yang harus ditolak | `release-debug.yml:15-16, 28, 62-67`, `release-app.yml:3-4, 82` | OPEN |
| E12 | MEDIUM | `sample-sekolah.yml` tanpa blok `permissions:` | `.github/workflows/sample-sekolah.yml` | OPEN |

### Hasil uji `security_gate.py` (dari repo root, `python3 -I`)

Deny: `rm -rf /`, `rm -rf / --no-preserve-root`, `sudo rm -rf /`, `rm -rf $HOME`, `mkfs.ext4 …`, `:(){ :|:& };:`, tulis `unprivileged_bpf_disabled`,
`ghp_…`/`sk-…` ke MEMORY.md/USER.md/SKILL.md, "Ignore all previous instructions" ke MEMORY.md, body 25 KB (kuota). Ask: `curl … | sh`.
**Allow (seharusnya deny)**: `rm -fr /`, `rm -r -f /`, `rm --recursive --force /`, `rm -rf /*`, `rm -rf ~/`, `rm -rf /data`, `rm -rf /workspace/`,
`rm -rf /workspace/nextcraft-forge`, `find / -delete`, `dd if=/dev/zero of=/dev/block/sda`, `bomb(){ bomb|bomb& };bomb`, `bash <(curl …)`,
`shutil.rmtree('/')`, payload `{"tool_name":"Bash",…}`, stdin non-JSON, AWS/Slack/`password=` ke MEMORY.md, `ghp_` ke `docs/notes.md`,
`edit_file` via `NewContent`/`edits[].newString`.

## F. Terverifikasi aman (tidak perlu tindakan)

Gateway loopback (bind 127.0.0.1, token per sesi, constant-time compare, batas ukuran, pool thread terbatas). `ApiKeyVault` AES-256-GCM Keystore
tanpa fallback plaintext. Ekstraksi tar anti zip-slip/symlink. `AppUpdater` verifikasi HTTPS+SHA-256+package+versionCode+sertifikat.
`BackupCrypto` PBKDF2 210k + AES-GCM nonce unik per chunk + AAD. Semua interpolasi shell lewat `shellSingleQuote`/`ChrootRunner.quote`.
`session_search.py` FTS ter-parameterisasi. REA tanpa `shell=True`/`eval`/`pickle`. Service FGS non-exported, tipe `dataSync`, wake lock seimbang.
`AndroidAppInstallReceiver` non-exported dengan validasi intent. `create_deterministic_tar.py` deterministik. `release-app.yml` validasi input,
keystore via env. Tidak ada `pull_request_target`. Kuota memori 24 KB bekerja (saat ini 15.780 B).
