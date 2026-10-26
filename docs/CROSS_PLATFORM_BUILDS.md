# Build aplikasi Windows, Linux, dan iOS dari dalam Forge

Tujuan: proyek yang dibuka di Forge (HP Android, chroot Ubuntu arm64) bisa di-build untuk Windows, Linux, dan iOS,
bukan hanya Android. Forge sendiri tetap aplikasi Android.

## Apa yang mungkin di perangkat

| Target | Di chroot (arm64) | Cara | Dites di perangkat? |
|---|---|---|---|
| Linux arm64 | Ya, native | gcc/cmake/go/cargo biasa | Ya, sama dengan build lokal |
| Linux x86_64 | Ya, cross-compile | `gcc-x86-64-linux-gnu`, Go `GOARCH=amd64`, `cargo --target` | Tidak bisa dijalankan di HP (butuh qemu-user) |
| Windows x86_64 | Ya, cross-compile | MinGW-w64 (C/C++), Go `GOOS=windows`, Rust `x86_64-pc-windows-gnu`, `dotnet publish -r win-x64` | Tidak. `.exe` tidak bisa dijalankan di HP |
| iOS | **Tidak** | Xcode hanya ada di macOS, dan lisensi Apple mengikatnya ke perangkat keras Apple | Build lewat runner **macOS di GitHub Actions** |

Konsekuensi: hasil build Windows dan Linux x86_64 adalah file yang dipindahkan ke mesin lain untuk dicoba. Forge hanya
memastikan file itu terbentuk. iOS tanpa Mac atau CI macOS tidak mungkin. Build tanpa tanda tangan hanya cukup untuk
simulator; untuk perangkat nyata atau TestFlight pengguna perlu akun Apple Developer (US$99/tahun) dan sertifikat sendiri
(disimpan sebagai secrets GitHub).

## Yang sudah ada di kode (persiapan)

`runtime/CrossPlatformBuilds.kt`:

- `detectCrossTargets(workspace)` mendeteksi dari file proyek: `go.mod`, `Cargo.toml`, `CMakeLists.txt`, `*.csproj`/`*.sln`,
  `pubspec.yaml` (Flutter), `*.xcodeproj`/`*.xcworkspace`.
- Target yang bisa dibuat di chroot otomatis muncul di **Build matrix** (lewat `detectBuildTargets`), bersama target yang sudah ada.
- Setiap perintah diawali pengecekan alat. Kalau alat belum ada, hasilnya pesan jelas dan kode keluar 127, misalnya:
  `x86_64-w64-mingw32-gcc is missing. Install it in the terminal: apt-get install -y gcc-mingw-w64-x86-64`.
- `crossToolchainInstallCommand(targets)` menyusun satu perintah `apt-get install` untuk semua alat yang dibutuhkan.
- `iosWorkflowYaml(project, scheme)` menghasilkan workflow GitHub Actions `macos-latest` (build simulator tanpa tanda tangan,
  artefak `.app`). Target iOS tidak muncul di Build matrix karena tidak bisa jalan di perangkat.

Hasil build ditaruh di `dist/<os>-<arch>/` (Go, .NET) atau di folder `build-*`/`target/` bawaan alatnya.

## Belum dikerjakan

1. **Antarmuka**: tombol "Pasang alat cross-build" yang menjalankan `crossToolchainInstallCommand`, dan tombol "Buat workflow iOS"
   yang menulis `.github/workflows/build-ios.yml` lalu memakai `CiCdController` untuk dispatch dan mengunduh artefak.
2. **Template proyek**: `AndroidProjectTemplateGenerator` baru membuat proyek Android. Template Go/Rust/CMake/.NET/Flutter
   lintas platform belum ada.
3. **Pengujian nyata**: perintah cross-compile belum dijalankan di chroot HP. Tes unit memeriksa deteksi dan pesan alat hilang,
   bukan hasil kompilasi. Versi paket (misal `dotnet-sdk-8.0`) perlu dicek pada Ubuntu 24.04 arm64 saat dicoba.
4. **Electron/Tauri/Wine**: sengaja tidak dimasukkan. Build installer Windows dari Electron butuh Wine x86 yang berat di arm64.
5. **Flutter Windows/iOS**: hanya Linux yang ditawarkan di perangkat; Flutter SDK harus dipasang manual (bukan paket apt).
6. **Paket RAM**: chroot sudah sempit; cross-compile Rust/.NET bisa memakan RAM besar dan perlu dibatasi (`-j1`) pada HP kecil.
