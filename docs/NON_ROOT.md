# Menjalankan NextCraft Forge tanpa root (mesin PRoot)

Sejak ADR-007, aplikasi punya dua mesin untuk masuk ke rootfs Ubuntu 24.04:

| | Pure Root Chroot (utama) | PRoot (fallback) |
|---|---|---|
| Syarat | `su` (Magisk / KernelSU / APatch) | tidak ada; `libproot.so` ada di APK |
| Cara masuk | `su -mm` + mount kernel + `chroot-exec.sh` | `proot` statis (ptrace) + `proot-exec.sh` |
| Kecepatan | penuh | build Gradle/npm kira-kira 2–4x lebih lambat |
| Instal APK hasil build | senyap (`pm install`) | dialog `PackageInstaller` biasa |
| Phantom-process killer | dimatikan otomatis | tidak bisa; lihat di bawah |

Pemilihan mesin ada di **Pengaturan → Runtime engine**:

- **Otomatis** (default): `su` diberikan → chroot; tidak → PRoot bila tersedia.
- **Chroot**: hanya root; tanpa `su` runtime tidak bisa dipasang.
- **PRoot**: selalu PRoot, tanpa dialog `su` (berguna untuk uji A/B di HP root).

Mengganti mesin tidak mengubah berkas rootfs; bundle Core, Python, Claude, DSH, Agy, dan Android sama untuk keduanya.
Yang berbeda hanya cara prosesnya diluncurkan.

## Catatan untuk mode PRoot

- Layar onboarding menampilkan *"No root: running on the built-in PRoot engine"*; Diagnostik menampilkan
  `Runtime engine: proot`.
- `chroot-health.sh` melewati bagian *Mount* (tidak ada mount kernel) dan mencetak `engine proot`.
- Android 12+ membunuh proses latar yang banyak (*phantom process killer*). Tanpa root, matikan lewat ADB sekali:
  `adb shell device_config put activity_manager max_phantom_processes 2147483647` dan
  `adb shell settings put global settings_enable_monitor_phantom_procs false`.
- Bila `proot` gagal dengan pesan seccomp/ptrace, set `PROOT_NO_SECCOMP=1` di environment agen (Pengaturan → Agent).
- `apt-get` berjalan sebagai root palsu (`-0`); paket yang memakai hard link ditangani `--link2symlink`.

## Asal biner

`app/src/main/jniLibs/arm64-v8a/libproot.so` (+ `libproot_loader.so`, loader ELF yang dirujuk `PROOT_LOADER`) = `proot` v5.5.0 (`proot-me/proot`, commit `25dc6a31`) + `talloc` 2.4.3,
dibangun statis (glibc) oleh `scripts/runtime-bundles/build-proot-static.sh` di chroot Core 24.04 ARM64. SHA-256 tercatat
di `docs/RUNTIME_KNOWN_ISSUES.md`. Android hanya mengizinkan eksekusi dari `nativeLibraryDir`, karena itu biner dikemas
sebagai native library (`jniLibs.useLegacyPackaging = true`).
