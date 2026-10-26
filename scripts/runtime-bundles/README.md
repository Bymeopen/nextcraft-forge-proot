# NextCraft Forge Runtime Scripts & Bundle Builders (`Pure Root Chroot Edition`)

Direktori ini berisi dua kelompok skrip untuk **NextCraft Forge (`com.nextcraft.forge`)**:

1. **On-Device Root Chroot Supervisor Scripts** (dipasang ke `/data/data/com.nextcraft.forge/files/runtime/bin/`):
   - `chroot-exec.sh`: Supervisor eksekusi `su -mm` yang mengelola *idempotent kernel mounts* (`/dev`, `/dev/pts`, `/proc`, `/sys`, `/tmp`, `/dev/shm`), *read-only bind mounts* partisi Android (`/system`, `/apex`, `/vendor`, `/product` untuk binary Bionic `aapt2`), *dynamic workspace & bridge bind mounts* (`/workspace/nextcraft`, `/nextcraft-bridge`), pembersihan variabel environment Bionic tanpa menghapus variabel AI/Gateway, kompatibilitas `GLIBC_TUNABLES=glibc.pthread.rseq=0`, dan *Exit Trap* `chown -R $APP_UID:$APP_UID`.
   - `unmount-chroot.sh`: Guard pelepas seluruh mount kernel secara terbalik (`sort -r` pada `/proc/mounts`) dan memverifikasi 0 mount tersisa sebelum operasi `RootfsRepair` diizinkan menghapus atau mengganti direktori rootfs.
   - `proot-exec.sh`: Supervisor fallback **tanpa root** (ADR-007) dengan argumen dan layout guest yang sama seperti `chroot-exec.sh`, tetapi masuk ke rootfs lewat `proot` statis dari APK (`NEXTCRAFT_PROOT`, `-b` untuk `/dev`, `/proc`, `/sys`, Bionic, workspace, bridge; `--link2symlink`, `-0`, `--kill-on-exit`). Selalu dijalankan sebagai `/system/bin/sh proot-exec.sh …`. Salinan identik ada di `RuntimeInstaller.PROOT_EXEC_SCRIPT`.

2. **Reproducible Runtime Bundle Builders** (untuk menghasilkan artefak `.tar.zst` deterministik):
   - `build-core-on-rooted-android.sh`: Membangun bundel `core` dari image resmi **Ubuntu 24.04.5 LTS (`noble`) ARM64** (`ubuntu-base-24.04.5-base-arm64.tar.gz`, SHA-256 terverifikasi), Node.js `v24.19.0`, `git`, `ripgrep`, `jq`, serta konfigurasi jaringan kernel Android (`aid_inet:x:3003:root,_apt` dan `99android-chroot`).
   - `build-agy-from-official.sh`: Mengemas binary resmi Linux ARM64 **Antigravity CLI (`agy`)** dengan verifikasi `SHA-512` (tanpa ponsel).
   - `build-claude-from-official.sh`: Membangun bundel **Claude Code** tanpa ponsel dari rilis resmi Linux ARM64 (`downloads.claude.ai`), dicek terhadap checksum `manifest.json` rilis **dan** SHA-256 yang dipin.
   - `build-dsh-in-chroot.sh`: Membangun bundel **DeepSeek Harness** tanpa ponsel dengan `npm install` yang sama seperti `RuntimeInstaller`, di dalam salinan chroot rootfs Core (native ARM64 atau `qemu-user-static` + binfmt flag `F` di x86_64). Hasilnya bisa berbeda antar hari karena npm me-resolve rentang dependensi; pin hasilnya lewat SHA-256.
   - `build-python-bundle.sh`: Membangun overlay **Python 3.12** (`python3`, `pip`, `venv`) di atas rootfs Core 24.04.5 dengan cara chroot yang sama; reproducible.
   - `build-android-bundle.sh`: Membangun overlay **toolchain Android** (`nextcraft-android-arm64-<versi>.tar.zst`) dari sumber yang dipin checksum: Temurin JDK 21 aarch64 (Adoptium), Gradle 9.8.1 (services.gradle.org), SDK Platform 37.2 dan bagian Java Build-Tools 36.0.0 (dl.google.com, SHA-1 dari `repository2-3.xml` plus SHA-256), biner native ARM64 `aapt`/`aapt2`/`aidl`/`zipalign`/`dexdump`/`split-select` dari rilis GitHub pihak ketiga `lzhiyong/android-sdk-tools` 35.0.2 (build AOSP dengan NDK, **statis**, tidak butuh `/system/bin/linker64`), serta repo Maven offline. Tidak butuh root; `NEXTCRAFT_CORE_ROOTFS` opsional untuk smoke test di chroot. Versi dan path harus sama dengan `AndroidToolchain.kt`.
   - `build-proot-static.sh`: Membangun `proot` 5.5.0 + `talloc` 2.4.3 **statis** (glibc) ARM64 di chroot Core 24.04 (sumber dipin checksum/tag) menjadi `libproot.so` untuk `app/src/main/jniLibs/arm64-v8a/`. Android melarang eksekusi dari direktori data aplikasi, jadi biner dikemas sebagai native library APK (`useLegacyPackaging = true`) dan dijalankan dari `nativeLibraryDir`.
   - `build-android-maven-cache.sh`: Mengisi repo Maven offline dengan membangun kedua template `AndroidProjectTemplateGenerator` (Compose dan Views; template dihasilkan oleh kode Kotlin aplikasi lewat `kotlinc`) secara native di x86_64 dengan JDK, Gradle, dan SDK Linux resmi, lalu memanen `caches/modules-2/files-2.1` ke layout Maven beserta `MANIFEST.sha256`. Dipanggil otomatis oleh `build-android-bundle.sh` bila `NEXTCRAFT_MAVEN_REPO` tidak diset.
   - `build-claude-from-installed-android.sh`: Mengekspor binary **Claude Code (`claude`)** ARM64 terverifikasi `SHA-256` tanpa kredensial atau data pengguna.
   - `build-dsh-from-installed-android.sh`: Mengemas payload npm **DeepSeek Harness (`dsh`)** beserta symlink peluncurnya.
   - `create_deterministic_tar.py`: Menghasilkan arsip GNU tar yang 100% deterministik (`uid=0`, `gid=0`, `mtime=2026-09-06T00:00:00Z`, urutan alfabetis, mempertahankan symlink `followlinks=False` / `dereference=False`, dan membuang metadata macOS `._*`).

Skrip berbasis chroot (`build-python-bundle.sh`, `build-dsh-in-chroot.sh`) butuh root dan `NEXTCRAFT_CORE_ROOTFS` yang menunjuk ke bundel Core hasil ekstrak; skrip hanya membaca direktori itu dan bekerja di salinannya. Semua builder menulis ke `dist/runtime-bundles/` (di-ignore git); arsipnya diunggah sebagai asset release, bukan di-commit.

Di host x86_64, chroot ARM64 memakai `qemu-user-static` dengan handler binfmt flag `F`. `binfmt_misc` bisa hilang setelah container restart; daftarkan ulang dengan teks escape (bukan byte mentah, karena byte NUL memotong magic dan membuat semua biner ELF64 ikut tertangkap):

```bash
mount -t binfmt_misc binfmt_misc /proc/sys/fs/binfmt_misc 2>/dev/null
printf '%s' ':qemu-aarch64:M::\x7fELF\x02\x01\x01\x00\x00\x00\x00\x00\x00\x00\x00\x00\x02\x00\xb7\x00:\xff\xff\xff\xff\xff\xff\xff\x00\xff\xff\xff\xff\xff\xff\xff\xff\xfe\xff\xff\xff:/usr/bin/qemu-aarch64-static:F' > /proc/sys/fs/binfmt_misc/register
```
