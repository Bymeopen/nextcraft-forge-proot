# Runtime 24.04 — Masalah yang Diketahui dan Rencana Perbaikan

Status per 2026-10-18, dari uji di perangkat root (kernel 5.10 GKI, Ubuntu 24.04.5 arm64, glibc 2.39) memakai
`scripts/runtime-bundles/chroot-health.sh` dan diagnosa manual di terminal chroot.

## Status

| # | Masalah | Status |
|---|---|---|
| 1 | `/apex` tanpa submount, `aapt2`/`zipalign` gagal | Diperbaiki, **diverifikasi di perangkat 2026-10-22** (lihat di bawah) |
| 2 | Satu stack gagal menghentikan stack lain | Diperbaiki |
| 3 | Skrip init Gradle rusak di bundle Android | Diperbaiki di bundle `nextcraft-android-arm64-2026.10.1` |

> **Verifikasi 2026-10-22 (build debug, proyek sampel `samples/mini-sekolah`):** di chroot, `/apex` berisi 80 submount;
> `gradle --no-daemon :app:assembleDebug` selesai (build dingin sekitar 99 detik pada uji sebelumnya, 16 detik dengan
> cache), tanpa "Killed" atau OutOfMemoryError. APK 894.097 byte lolos `aapt2 dump badging`, `zipalign -c 4`, dan
> `apksigner verify`. Heap Gradle terkelola: `-Xmx1280m` (RAM 11 GB). Memori available terendah sekitar 5,0 GB.

### 1. `aapt2` dan `zipalign` tidak bisa jalan: `/apex` di-bind tanpa submount (kritis)

**Gejala:** pemasangan stack Android gagal dengan
`/root/android-sdk/build-tools/35.0.0/aapt2: cannot execute: required file not found` (bundle 2026.09.1; bundle
2026.10.1 memakai biner statis, lihat bagian asal-usul biner di bawah).

**Bukti (terminal chroot):**

```
/system/bin/linker64 -> /apex/com.android.runtime/bin/linker64
tmpfs /apex tmpfs ro,seclabel,nosuid,nodev,noexec,relatime,...   # satu-satunya baris /apex
ERR  apex submounts   0 submount
```

**Akar masalah:** `aapt2` dan `zipalign` adalah biner Bionic dengan interpreter `/system/bin/linker64`, yang di
Android 10+ adalah symlink ke `/apex/com.android.runtime/bin/linker64`. `/apex` adalah tmpfs, dan tiap modul APEX
adalah mount tersendiri di bawahnya. `chroot-exec.sh` memasang `/system`, `/apex`, `/vendor`, `/product` dengan
`mount -o bind` (non-rekursif), sehingga di dalam chroot `/apex` hanya berisi direktori kosong. Linker tidak
ditemukan, dan kernel mengembalikan `ENOENT` untuk interpreter ELF.

**Perbaikan (diterapkan):** `mount_bionic` di kedua salinan `chroot-exec.sh` (string `CHROOT_EXEC_SCRIPT` di
`RuntimeInstaller.kt` dan `scripts/runtime-bundles/chroot-exec.sh`):

1. Bind `/system`, `/apex`, `/vendor`, `/product` tanpa rekursi (belum ada submount, jadi aman).
2. Jadikan mount itu `slave` (`--make-rslave`, dengan cadangan `-o rslave`/`--make-slave`/`-o slave`). Bila
   semuanya gagal, berhenti di bind lama dan tampilkan peringatan: `aapt2` tetap gagal, tetapi Android aman.
3. Bind setiap submount (modul APEX) satu per satu, induk lebih dulu, masing-masing langsung dijadikan `slave`.
4. Mount lama yang non-rekursif (dari versi sebelumnya) dideteksi dan diganti otomatis.

`rbind` sengaja **tidak** dipakai: salinannya adalah peer dari mount Android yang *shared* sampai dijadikan
slave, dan `umount` salinan yang masih peer ikut melepas modul APEX asli. Sebagai lapisan kedua,
`unmount-chroot.sh` (kedua salinan) dan `RootfsRepair.ensureUnmounted` menjadikan semua mount di bawah rootfs
`private` sebelum melepasnya.

**Diuji** di Linux dengan `/apex` tiruan yang *shared* (tmpfs + submount modul), dijalankan dengan `dash`:
linker terlihat di dalam rootfs, salinan tidak lagi *shared*, mount lama terganti, kedua skrip unmount melepas
semuanya tanpa menyentuh modul asli, dan jalur cadangan tanpa `rbind`/`--make-rslave` juga lulus. Uji kontrol
memastikan risikonya nyata: `rbind` tanpa `slave` lalu `umount` memang melepas modul asli.

**Belum diverifikasi di perangkat:** dukungan opsi propagasi di `mount` milik toybox/busybox Magisk/KernelSU.
Setelah update, `chroot-health.sh` harus menunjukkan `apex submounts` > 0, `linker64` OK, `aapt2` dan
`zipalign` OK, lalu build APK template berhasil. Bila muncul peringatan "mount propagation cannot be changed",
`mount` di perangkat tidak mendukung opsi propagasi.

### 2. Kegagalan satu stack menghentikan stack berikutnya

**Gejala:** toggle C/C++ dan PHP aktif, tetapi `gcc`, `make`, `cmake`, `gdb`, `php`, dan `composer` tidak
terpasang setelah verifikasi Android gagal.

**Akar masalah:** loop pemasangan stack di `ensureInstalled` memanggil `applyStack` berurutan tanpa
penanganan error, sehingga exception dari verifikasi Android menghentikan stack sesudahnya.

**Perbaikan (diterapkan):** setiap stack dipasang dalam `try`; kegagalan dicatat (`Log.e`, pesan progres),
stack berikutnya tetap dipasang, agen tetap dipasang, lalu semua kegagalan dilaporkan sekaligus di akhir.
Pembatalan (`CancellationException`) tetap menghentikan proses. Stack yang gagal tidak ditandai terpasang,
jadi akan dicoba lagi pada pemasangan berikutnya.

### 3. Skrip init Gradle di dalam bundle Android rusak (kecil)

`root/.gradle/init.d/pocketdev-android.gradle` di `pocketdev-android-arm64-2026.09.1.tar.zst` kehilangan tanda
kutip string, sehingga Gradle gagal mengompilasinya. Tidak berdampak karena `writeAndroidGradleInitScript` selalu
menimpanya setelah overlay dan saat peluncuran.

**Selesai:** bundle `nextcraft-android-arm64-2026.10.1` (dibangun oleh `scripts/runtime-bundles/build-android-bundle.sh`)
berisi skrip yang identik dengan `RuntimeInstaller.ANDROID_GRADLE_INIT_SCRIPT`, dan `platform-tools` yang rusak
(`adb` 0 byte) dibuang.

### Asal-usul biner native Build-Tools ARM64

Google tidak menerbitkan Build-Tools untuk Linux ARM64. Bundle 2026.09.1 memakai biner Bionic dinamis tanpa asal yang
terdokumentasi, yang bergantung pada `/system/bin/linker64` dan submount `/apex` (masalah nomor 1). Bundle 2026.10.1
memakai rilis GitHub `lzhiyong/android-sdk-tools` 35.0.2 (pihak ketiga, dibangun dari AOSP tag `platform-tools-35.0.2`
dengan NDK, **statis**, SHA-256 dipin di skrip). Karena statis, `aapt2`, `aapt`, `aidl`, `zipalign`, `dexdump`, dan
`split-select` tidak lagi membutuhkan linker Android: `file` melaporkan `statically linked`, dan `aapt2 version` berjalan
tanpa bind `/system`/`/apex`. Mount Bionic di `chroot-exec.sh` tetap dipertahankan untuk kompatibilitas bundle lama dan
alat lain. Kompatibilitas dengan AGP 9.4.1 diverifikasi di x86_64 memakai `aapt2` resmi Build-Tools 35.0.1 (generasi
sumber yang sama) lewat `android.aapt2FromMavenOverride`; `aapt2` ARM64 itu sendiri diuji dengan `qemu-user-static`.

### Mesin PRoot (tanpa root, ADR-007)

Bila `su` tidak tersedia, aplikasi memakai `libproot.so` dari APK (`proot` 5.5.0 + `talloc` 2.4.3 statis, dibangun oleh
`scripts/runtime-bundles/build-proot-static.sh` dari `proot-me/proot` commit `25dc6a31…` dan `talloc-2.4.3.tar.gz`
SHA-256 `dc46c40b…`; `libproot.so` 938.648 byte, SHA-256 `de90c7a62c5a27e23383625ade5995b99f761e35cf49665555af5b394299361f`; `libproot_loader.so` 73.552 byte, SHA-256 `350434c827459c6cd87827f728e9c933ee905baa4e597c430f44b013517f7677`; dua build berturut-turut identik). Bundle Core/Python/Claude/DSH/Agy/Android
tidak berubah; `aapt2` statis membuat build Android tidak lagi bergantung pada `/apex`. Yang tidak tersedia di mode ini:
`pm install` senyap (jatuh ke `PackageInstaller`), penonaktifan phantom-process killer (`adb shell device_config put
activity_manager max_phantom_processes 2147483647` bisa dilakukan manual), `chroot-health.sh` bagian *Mount* (ERR wajar).
Bila `proot` gagal dengan `seccomp`/`ptrace` di kernel tertentu, coba `PROOT_NO_SECCOMP=1` lewat environment agen.

**Exit 159 (signal 31).** `159 = 128 + 31` = `SIGSYS`: satu syscall ditolak filter seccomp (filter app Android atau
filter proot). Terlihat saat `npm install @anthropic-ai/claude-code` dan verifikasi bundel Antigravity. Untuk
menemukan syscall pelakunya, jalankan di tab Terminal:

```bash
sh scripts/runtime-bundles/proot-diag.sh      # laporan di ~/proot-diag.log, log npm verbose di ~/proot-diag.log.npm
android-shell logcat -d | grep -iE 'SIGSYS|seccomp|audit.*syscall'   # nomor syscall (arm64)
```

Bila guest mati karena sinyal, `proot-exec.sh` juga menjalankan `libsigsys_probe.so` (sumber `scripts/runtime-bundles/sigsys-probe.c`,
statis tanpa libc) yang mencetak syscall yang ditolak filter seccomp perangkat (`BLOCKED (SIGSYS, nr=N)`).

**Selesai (2026-10-28).** Penyebab exit 159 adalah biner PRoot, bukan rootfs: `libproot.so` statis (glibc) dari
`build-proot-static.sh` mati dengan SIGSYS di perangkat non-root Android 16, juga build musl yang dicoba. Ganti ke
`libproot.so` yang di-link ke bionic (`/system/bin/linker64`, SHA-256 `f6d4a32166dd41199e9da1f812aa5950ecd33de082bb65e9f114b79b8569a83f`)
dan `libproot_loader.so` statis (SHA-256 `be8635349125a2ad645269ac7c095db5cea104fa1bf03719cc5a9677e010e629`), diambil
dari APK buatan pengguna tanggal 22. Dengan keduanya, bundle Core 2026.10.1 apa adanya (Ubuntu 24.04.5, glibc 2.39
stok) berjalan di perangkat: Terminal terbuka, Claude Code dan Antigravity CLI bisa dipasang (dikonfirmasi pengguna).
Skrip sumber untuk build bionic itu belum ada di repo; sebelum mengganti biner, catat cara membangunnya.
Eksperimen yang tidak dipakai (glibc yang ditambal, rootfs Alpine, proot musl, harness seccomp) dihapus dari
repo dan masih ada di riwayat git sebelum commit pembersihan.


## Belum terverifikasi

- Fungsi nyata agen (Claude Code, DSH, Antigravity) di perangkat. Di uji ini belum dipasang karena belum dipilih.
- `gh` (dipasang lewat tombol **Hubungkan** GitHub).
- Build APK end-to-end di perangkat. Dengan bundle 2026.10.1, `gradle assembleDebug --offline` kedua template
  (Compose dan Views) sudah lulus di chroot Core 24.04 ARM64 lewat `qemu-user-static` di x86_64 (biner `aapt2`
  statis, tanpa `/system`/`/apex`); belum diulang di perangkat root.
- Hasil DSH `npm install` berubah bila dibangun di hari lain; pin hasilnya lewat SHA-256.
- Migrasi pengguna lama dari rootfs 20.04 ke 24.04: belum ada jalurnya, pengguna lama sengaja tetap di 20.04.

## Cara cek ulang

Di terminal chroot aplikasi:

```bash
bash /path/ke/chroot-health.sh 2>&1 | tee chroot-health.log
```

ERR pada stack yang belum dipasang adalah wajar. ERR pada stack yang terpasang adalah masalah nyata.
