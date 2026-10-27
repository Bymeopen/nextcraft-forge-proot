# Laporan uji aplikasi (2026-10-27)

Perangkat: Redmi (Android 17, SDK 37). Build uji: `.x` (side-by-side) dari branch lokal, aplikasi utama 1.0.110-glassfix.
Status: sedang berjalan. Hasil di bawah sudah dijalankan dan diperiksa, bukan perkiraan.

## Hasil

| Area | Hasil | Bukti |
| :--- | :--- | :--- |
| Unit test | LULUS, 359 test, 0 gagal | `gradle testDebugUnitTest` |
| `verify-core-fixes.sh`, `check-embedded-scripts.py` | LULUS (exit 0) | dijalankan |
| Lint (`lintDebug`) | 4 error, 216 peringatan (sebelumnya 7 error) | `app/build/reports/lint-results-debug.txt` |
| Kesehatan chroot | LULUS, 45 OK / 0 ERR | `chroot-health.sh` di chroot `.x` |
| Build Android di perangkat (online) | LULUS, 2m25s, APK 894 KB | sampel `mini-sekolah` |
| Build Android di perangkat (offline) | GAGAL lalu diperbaiki, 1m02s | Kotlin 2.2.10 tak ada di cache offline |
| Server MCP bawaan | LULUS: initialize, 5 alat, panggilan, galat alat, ping | stdio JSON-RPC |
| `web_preview_inspect` | LULUS (200, judul, viewport, H1; `file://` ditolak) | server python di chroot |
| Integritas rilis (proot dev-latest) | SHA-256 manifest cocok | unduh + hitung |
| Crash / ANR | 0 untuk NextCraft | logcat crash, dropbox |
| UI: proyek cepat, laci obrolan, menu +, lembar agen, lembar model | LULUS | layar overlay |
| UI: tab File (kosong), Terminal (perintah), Pratinjau (server 3000) | LULUS | layar overlay |
- **Tab File (UI `.x`, tap nyata, diverifikasi di disk)**: buat file `tes/halo.txt` (folder induk dibuat otomatis), edit + Simpan (isi `halo dunia` tertulis), ganti nama folder (path di editor ikut berubah), hapus file dan folder (dialog konfirmasi + banner). Path `../luar.txt` ditolak, tidak ada berkas di luar proyek.
- **Tab Alat (UI `.x`)**: daftar Alat tampil; Diagnostik Sistem menampilkan data nyata (Snapdragon SM8450, 8 inti, RAM 10,9 GB, chroot, Ubuntu 24.04.5 arm64, root KernelSU); Manajer toolchain ada di Alat (stack bahasa + agen coding). Tombol Copot sengaja tidak ditekan (merusak).
- **Setelan (UI `.x`)**: tema Gelap/Kaca/Putih berganti langsung dan terbaca; opsi Solid Glass muncul hanya di tema Kaca; Bahasa English berlaku seketika (semua label ikut berganti); pengaturan dikembalikan ke Gelap + Bawaan sistem.
- **Latar Pratinjau (`d9c6010`)**: diverifikasi di perangkat. `index.html` polos tampil berlatar putih dengan teks hitam terbaca di tema aplikasi gelap. Catatan: server uji lama (pid yatim dari sebelum pasang ulang) menahan port 3000 dan memberi `ERR_EMPTY_RESPONSE`; setelah dimatikan lewat PID, server baru dilayani normal.
- **Atur AI (UI `.x`)**: tab Claude/DeepSeek/Antigravity berpindah; status masuk akun Claude tampil; "Uji koneksi" DeepSeek tanpa kunci menampilkan galat jelas ("Ditolak: Periksa kunci API"). Tombol Keluar tidak ditekan.
- **Proyek baru + build Android (UI `.x`)**: dialog Proyek baru dengan templat Android Compose menghasilkan proyek AGP 9.4.1 / Kotlin 2.4.21 / compileSdk 37.2; `gradle assembleDebug` di chroot `.x` BUILD SUCCESSFUL (2m15s, exit 0), APK 29 MB terpasang dan berjalan ("Built with NextCraft Forge"), lalu dicopot.
- **Hapus proyek (UI `.x`)**: dialog konfirmasi jelas; jendela urungkan 6 dtk; setelah perbaikan `72bb7c2` folder berisi `build/` dan `.gradle/` milik root terhapus penuh (diverifikasi di disk).

## Bug yang ditemukan dan perbaikannya (branch lokal, belum di GitHub)

| # | Bug | Perbaikan | Commit |
| :--- | :--- | :--- | :--- |
| 1 | `HexFormat` (API 34) dan `readNBytes` (API 33) dipakai padahal minSdk 31: checkpoint crash di Android 12/13, impor ZIP gagal di 12/12L | `toHex`, `readUpTo` + `ApiLevelCompatTest` | `2f605b0` |
| 2 | Sampel Mini Sekolah tidak bisa dibangun offline (Kotlin tidak disematkan) | sematkan `org.jetbrains.kotlin.android` 2.4.21 | `cd64bae` |
| 3 | Pratinjau Web: latar WebView gelap, halaman tanpa latar tampil hitam di atas gelap | latar putih setelah paint pertama | `d9c6010` |
| 4 | `.kotlin/` tidak di-ignore, file sesi ikut ter-commit otomatis | `.gitignore` | `8be4049` |
- Label templat Android di dialog Proyek baru menyebut "SDK 35" padahal generator memakai compileSdk 37 (14 string, 7 bahasa). Diperbaiki di `2ccfd52`; label "SDK 37" terverifikasi di perangkat.
- Hapus proyek meninggalkan berkas milik root (`build/`, `.gradle/`, `.kotlin/`, `.git/`; 66 MB setelah satu build Compose) karena build/git berjalan sebagai root di chroot. Dialog menyebut "hapus permanen". Diperbaiki di `72bb7c2` (cadangan `su rm -rf` pada engine root, dengan pengaman id); tes `DeleteRecursivelyNoFollowOrRootTest`. Catatan: proyek lama `97fde7f3…` di `.x` masih punya `.git`/`.forge` milik root (sisa lama, tidak dihapus).

## Temuan yang belum diperbaiki
- Manifest rilis di repo `proot` menunjuk URL APK ke repo utama (HTTP 404). Akar: `scripts/make-update-manifest.sh` memakai `REPO` bawaan.
- Server MCP tidak memvalidasi argumen wajib (mis. `items` kosong menghasilkan diagram kosong), dan `platform` tak dikenal diam-diam jatuh ke `tailwind`.
- 4 error lint Compose (`NonObservableLocale`, `LocalContextGetResourceValueCall` x3): teks bisa basi sampai restart setelah ganti bahasa.
- Tab File: path `../x` ditolak tanpa pesan galat (dialog menutup begitu saja); dialog hapus folder berjudul "Hapus File". Keduanya kosmetik (LOW).

## Belum terverifikasi (butuh pengujian ulang)
- Penyebab kegagalan sesi berulang ("Claude Code reported an error") belum terbukti. Aplikasi sehat (0 crash/ANR); transkrip sesi 51 MB berisi 63 screenshot, dugaan: ukuran data sesi.

## Belum diuji
Ctrl+C dan mematikan server dari terminal, tab Perubahan (butuh proyek git), kirim pesan tanpa login, cabang chat terisolasi + `.forge/chats` lewat UI, rotasi layar, ukuran font 2.0, Glass solid hidup/mati.
