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

## Bug yang ditemukan dan perbaikannya (branch lokal, belum di GitHub)

| # | Bug | Perbaikan | Commit |
| :--- | :--- | :--- | :--- |
| 1 | `HexFormat` (API 34) dan `readNBytes` (API 33) dipakai padahal minSdk 31: checkpoint crash di Android 12/13, impor ZIP gagal di 12/12L | `toHex`, `readUpTo` + `ApiLevelCompatTest` | `2f605b0` |
| 2 | Sampel Mini Sekolah tidak bisa dibangun offline (Kotlin tidak disematkan) | sematkan `org.jetbrains.kotlin.android` 2.4.21 | `cd64bae` |
| 3 | Pratinjau Web: latar WebView gelap, halaman tanpa latar tampil hitam di atas gelap | latar putih setelah paint pertama | `d9c6010` |
| 4 | `.kotlin/` tidak di-ignore, file sesi ikut ter-commit otomatis | `.gitignore` | `8be4049` |

## Temuan yang belum diperbaiki
- Manifest rilis di repo `proot` menunjuk URL APK ke repo utama (HTTP 404). Akar: `scripts/make-update-manifest.sh` memakai `REPO` bawaan.
- Server MCP tidak memvalidasi argumen wajib (mis. `items` kosong menghasilkan diagram kosong), dan `platform` tak dikenal diam-diam jatuh ke `tailwind`.
- 4 error lint Compose (`NonObservableLocale`, `LocalContextGetResourceValueCall` x3): teks bisa basi sampai restart setelah ganti bahasa.

## Belum terverifikasi (butuh pengujian ulang)
- **Bersihkan terminal**: BUG nyata (TerminalSession.reset() tidak menghapus buffer layar). Diperbaiki di `41d4d0f`; diuji ulang di perangkat dengan tap nyata di layar utama (⋮ → Bersihkan terminal): isi lama terhapus, hanya prompt tersisa. 362 tes unit lulus, 0 gagal.
- Penyebab kegagalan sesi berulang ("Claude Code reported an error") belum terbukti. Aplikasi sehat (0 crash/ANR); transkrip sesi 51 MB berisi 63 screenshot, dugaan: ukuran data sesi.

## Belum diuji
Ctrl+C, buat/edit/hapus file di tab File dan tab Perubahan, kirim pesan tanpa login, cabang chat terisolasi, tab Alat (manajer toolchain, diagnostik), Setelan (tema, bahasa, Glass solid), Atur AI, dialog Proyek baru, rotasi, ukuran font 2.0.
