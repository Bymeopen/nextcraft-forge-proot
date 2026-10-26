# Audit Fix List — NextCraft Forge (2026-10-26)

Status: putaran 1 selesai (runtime/security inti). Putaran 2: area lain (lihat bagian B).

## A. Sudah dipindai (putaran 1)

Cakupan: `LocalFormatGateway`, `OpenRouterRoutingGateway`, `ForgeMcpServer`, `ApiKeyVault`, `AppPreferences`,
`RuntimeInstaller`, `update/*`, `forge_spawn.c`, `NativeSpawnProcess`, `network_security_config.xml`,
`MainViewModel` (struktur), `app/build.gradle.kts`, manifest, CI workflows (daftar), `rea-tests`, i18n parity.

Verifikasi: CI HEAD `e4b9cce` run 761 PASS; `rea-tests` 134 passed/25 skipped (butuh capstone, pyelftools, unicorn);
`test-chroot-reaper.sh` PASS. Unit test Android tidak bisa dijalankan lokal (tanpa SDK).

| # | Sev | Temuan | Lokasi | Status |
|---|---|---|---|---|
| A1 | MEDIUM | Checksum biner Claude Code/Antigravity hanya dari manifest remote yang sama (TOFU, tanpa signature pin) | `RuntimeInstaller.kt:732-739, 773-778, 887-889` | OPEN |
| A2 | MEDIUM | Tidak ada unit test untuk gateway HTTP, `ApiKeyVault`, `AppUpdater.verifyApk`, `NativeSpawnProcess`, `safeChild`/`resolveSafeSymlinkTarget` | `app/src/test` | OPEN |
| A3 | MEDIUM | `MainViewModel` god object: 5738 baris, 311 fungsi, 92 launch, ±9 dependensi di konstruktor | `ui/MainViewModel.kt` | OPEN |
| A4 | MEDIUM | Hook keamanan memanggil `python3 scripts/security_gate.py` (relatif), file ada di `.agents/scripts/` | `.agents/hooks.json:10` | OPEN |
| A5 | LOW | Vault menghapus ciphertext diam-diam saat AEAD/decode gagal | `data/ApiKeyVault.kt:213-215` | OPEN |
| A6 | LOW | `.git-credentials` plaintext (0600) di rootfs; file `.env-*` berisi key dibersihkan setelah 10 menit | `RuntimeInstaller.kt:557, 2160-2162` | OPEN |
| A7 | LOW | `web_preview_inspect` tanpa filter SSRF/private-range | `ForgeMcpServer.kt:470-478` | OPEN |
| A8 | LOW | Gateway menerima provider key asli sebagai bearer (surface redundan) | `LocalFormatGateway.kt:819-820` | OPEN |
| A9 | LOW | Fallback `sh -c` di host saat chroot tak ada (pemanggil: terminal user, build, git-sync) | `MainViewModel.kt:4324-4331` | OPEN |
| A10 | LOW | `opendir/readdir` setelah fork (non async-signal-safe); hasil `setpgid` parent diabaikan | `cpp/forge_spawn.c:28-38, 227` | OPEN |
| A11 | LOW | `waitpid` polling 20 ms; `getInputStream()` buka `FileInputStream` tiap panggilan; pid via refleksi | `NativeSpawnProcess.kt:48-52, 25, 149-153` | OPEN |
| A12 | LOW | `::1` tidak di allowlist cleartext | `res/xml/network_security_config.xml` | OPEN |
| A13 | LOW | Tidak ada `gradlew` wrapper (butuh Gradle 9.x global) | repo root | OPEN |
| A14 | LOW | `AppUpdater` varian tak dikenal fallback ke root manifest | `update/AppUpdater.kt:63-66` | OPEN |
