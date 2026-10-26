# REA (Reverse Engine Anything) di NextCraft Forge

REA adalah alat belajar dan riset reverse engineering yang berjalan di dalam chroot Ubuntu aplikasi ini. Intinya paket
Python (`rea`) tanpa dependensi wajib selain `python3`; kemampuan tambahan (disassembly, emulasi, debugger) muncul bila
alatnya terpasang, dan `rea doctor` selalu menguji kemampuan itu sungguhan, tidak menebak.

> **Penggunaan yang sah.** Hanya untuk software milik sendiri, CTF, crackme, interoperabilitas, riset keamanan
> berizin, dan analisis malware defensif. Jangan memakainya untuk melanggar lisensi atau menyerang sistem tanpa izin.
> REA menampilkan pengingat ini saat pertama kali dijalankan.

## 1. Pasang di aplikasi

Syarat: runtime Linux Forge sudah terpasang (layar pengantar pertama) dan akses root diberikan.

1. Buka **Alat ▸ REA**.
2. Di bagian **Pemasangan**, tekan **Pasang**. Aplikasi menyalin paket REA ke chroot, memasang `python3`, `python3-capstone`
   dan `python3-pyelftools` lewat apt bila belum ada, lalu menulis penanda versi. Selesai bila status menjadi
   *Terpasang, versi x.y.z*.
3. Di bagian **Kemampuan (rea doctor)**, tekan **Periksa kemampuan**. Baris yang ✅ sudah siap, ⚠️ opsional dan belum ada,
   ❌ tidak berfungsi. Baris **Mode** di atasnya menyimpulkan apa yang bisa kamu lakukan (bagian 3).
4. Opsional: tekan **Pasang alat opsional** (perlu internet). Ini memasang `strace`, `ltrace`, `qemu-user`, `binwalk`,
   `squashfs-tools`, `cpio` lewat apt, dan `unicorn` serta `frida` (apt atau pip). `qemu-user` cukup besar. Hasil pemeriksaan
   diperbarui otomatis; kalau ada yang tetap ⚠️, lihat bagian 8.
5. Aplikasi yang diperbarui membawa versi REA yang lebih baru: tekan **Pasang ulang** supaya paket di chroot ikut diperbarui.
   **Hapus** menghilangkan REA dari chroot tanpa menyentuh proyekmu.

Status REA juga tampil di **Alat ▸ Diagnostik Sistem** (baris *REA*: versi atau *belum terpasang*).

Semua perintah di bawah juga bisa dijalankan dari **Terminal** (`rea ...`). Tambahkan `--json` untuk keluaran mesin:
satu objek JSON di baris terakhir stdout.

## 2. Dari layar aplikasi

| Bagian | Fungsi |
|---|---|
| Analisis berkas | Pilih berkas proyek, jalankan `id` dan `analyze` (string menarik, fungsi, disassembly). Di tab **File**, menu ⋮ pada berkas punya **Analisis dengan REA** yang langsung memilih berkas itu. |
| Laporan | Membuat laporan Markdown di `.rea/` dalam proyek, bisa dibaca dan disalin. |
| Periksa kemampuan | Tabel kemampuan dan mode. |
| Belajar | 13 pelajaran, latihan pada biner yang dikompilasi di perangkatmu, pemeriksaan jawaban, kunci jawaban (dengan konfirmasi), dan kuis. Progres tersimpan di `~/.rea/progress.json`. |

Folder `.rea/` dan `.forge/` dikecualikan dari checkpoint dan snapshot proyek.

## 3. Mode

| Mode | Syarat (diuji `rea doctor`) | Yang bisa dilakukan |
|---|---|---|
| `static-only` | selalu | `id`, `analyze`, `report`, `apk`, `dex`, `firmware`, `learn` |
| `emulated-dynamic` | `qemu-user` atau `unicorn` berfungsi | tambah `emu` (satu fungsi) dan `run --mode emu` (QEMU + gdbstub, tanpa ptrace) |
| `full-dynamic` | `ptrace` dan `gdb` berfungsi | tambah `run --mode gdb`, `strace`, `frida` |

Di sebagian kernel atau pembatas seccomp, ptrace ditolak. Perintah yang membutuhkannya gagal dengan pesan yang
menyarankan `--mode emu`; `rea doctor` memperlihatkan hasil uji ptrace yang sebenarnya.

## 4. Perintah

| Perintah | Fungsi |
|---|---|
| `rea doctor` | Uji kemampuan nyata: lingkungan (chroot/proot), ptrace, alat, pustaka, RAM, disk, dan mode. |
| `rea id BERKAS` | Tipe, arsitektur, hash, entropi; mengenali ELF, PE, Mach-O, DEX, APK, ZIP, firmware, dan lain-lain. |
| `rea analyze BERKAS` | `--strings --interesting`, `--disasm [FUNGSI]`, `--cfg [FUNGSI] [--dot]`, `--xrefs`, `--entropy`. |
| `rea report BERKAS -o laporan.md` | Laporan Markdown (ringkasan, string, disassembly, CFG, entropi, bagian dari plugin). |
| `rea apk BERKAS.apk` | Manifest, izin, komponen, DEX, pustaka native, tanda tangan, temuan keamanan. |
| `rea dex BERKAS --filter com.contoh` | Daftar class dari DEX atau APK. |
| `rea decompile BERKAS --tool jadx\|apktool` | Dekompilasi bila alatnya terpasang (opsional). |
| `rea emu BERKAS --func NAMA --reg x0=1 --mem ALAMAT:HEX --dump ALAMAT:PANJANG` | Emulasi Unicorn satu fungsi (arm64, arm, x86, x86_64). Tanpa relokasi dan syscall: cocok untuk dekripsi dan algoritma kecil. |
| `rea run BERKAS --mode auto\|emu\|gdb\|strace\|frida` | Eksekusi dinamis. `--break FUNGSI\|ALAMAT`, `--examine $sp:32` (gdb), `--trace file` (strace), `--hook FUNGSI` (frida), `--run-to-end`. |
| `rea firmware BERKAS [--extract -o FOLDER]` | Pindai signature filesystem dan kontainer, kernel, bootloader; ekstrak (bagian 6). |
| `rea learn list\|show N\|start N\|check N JAWABAN\|quiz N\|answer N\|reset` | Mode belajar. |
| `rea plugins list\|new NAMA` | Plugin (bagian 5). |

Contoh cepat:

```sh
rea id /tmp/h
rea analyze /tmp/h --strings --interesting --disasm main
rea run /tmp/h --mode gdb --break add --examine '$sp:16'
rea emu /tmp/h --func decode --reg x0=0x600000 --reg x1=9 --reg x2=0x5a --mem 0x600000:<hex> --dump 0x600000:9
rea learn list
```

## 5. Plugin

Plugin adalah berkas Python di `~/.rea/plugins/` (di chroot: `/root/.rea/plugins/`) yang mendaftarkan sebuah kelas:

```python
import rea

@rea.plugin("catatan", formats=["ELF"])      # tipe dari `rea id`; ["*"] untuk semua
class Catatan:
    def on_identify(self, info):             # setelah `rea id`
        return {"ukuran_kib": info["size"] // 1024}

    def on_parse(self, path, data):          # setelah parser format, di `analyze` dan `report`
        return None

    def on_analyze(self, path, data):        # di akhir `analyze`; `data` berisi semua hasil
        return {"fungsi": len((data.get("elf") or {}).get("functions", []))}

    def on_report(self, data, markdown):     # kembalikan teks Markdown: jadi bagian "Plugin: catatan"
        return None
```

- Semua hook opsional; minimal satu harus ada. Hasil (dict, list, angka, teks) muncul di `data.plugins.<nama>.<hook>`.
- `rea plugins new nama` membuat kerangka siap pakai; `rea plugins list` menampilkan plugin yang termuat dan galat muatnya.
- Plugin bawaan **hardening** (ELF): NX, PIE, RELRO, stack canary, FORTIFY, beserta saran, juga di laporan.
- Satu plugin yang rusak, melempar galat, atau lambat (batas 5 detik per hook) tidak menghentikan perintah: hasilnya peringatan.
  Hasil plugin dibatasi 64 KB dan harus bisa menjadi JSON.
- **Keamanan:** plugin adalah kode Python yang berjalan dengan hak yang sama dengan REA (root di chroot). Pasang hanya yang
  kamu percayai. `REA_NO_PLUGINS=1` atau `rea --no-plugins ...` mematikan semuanya.

## 6. Firmware

`rea firmware imej.bin` memindai (streaming, berkas besar aman) dan melaporkan hanya temuan yang header-nya valid:

| Jenis | Deteksi | Ekstraksi |
|---|---|---|
| squashfs (LE/BE) | versi, kompresi, ukuran | `unsquashfs` (apt: `squashfs-tools`) |
| cramfs | nama, ukuran | `fsck.cramfs` (apt: `util-linux`) |
| jffs2 | node (digabung per 4 MB) | `jefferson` bila terpasang |
| cpio (newc) | entri pertama | `cpio` |
| gzip, xz, bzip2, lzma | harus bisa mulai didekompresi | Python (dibatasi 256 MB keluaran) |
| zip | entri pertama | Python (menolak entri yang keluar folder) |
| uImage, Android boot, DTB | header, ukuran | dipotong menjadi berkas (Android boot: kernel, ramdisk, second) |
| ext2/3/4, kernel arm64/zImage/bzImage | tanda di posisi tetap | hanya dilaporkan |

Juga dideteksi: banner kernel `Linux version ...` (termasuk di dalam aliran terkompresi) dan bootloader (U-Boot, barebox, GRUB).
`rea id` menyebut berkas sebagai `firmware` bila ada signature kuat; teks biasa (misalnya log dmesg) tidak.
Alat yang belum terpasang dilaporkan dengan perintah `apt install` yang tepat, tidak gagal diam-diam. Ekstraksi
ditulis ke `.rea/<nama>.extracted` atau folder dari `-o`. Imej terenkripsi tidak punya signature yang terbaca
(lihat `rea analyze --entropy`).

## 7. Kontrak JSON

```json
{"schema": 1, "command": "id", "ok": true, "data": {}, "warnings": [], "error": null, "mode": "static-only"}
```

`error` berisi `code` (mis. `file_not_found`, `missing_dependency`, `not_supported`, `usage`, `symbol_not_found`,
`bad_format`, `tool_missing`), `message`, dan `hint` (saran perbaikan). Aplikasi membaca baris JSON terakhir dan mengabaikan
baris lain sebelumnya.

## 8. Pemecahan masalah

| Gejala | Penyebab dan solusi |
|---|---|
| REA tampil "Belum terpasang" padahal sudah dipasang | Penanda versi di `/root` tidak terbaca aplikasi (folder berpindah pemilik setelah `apt`). Buka layar REA lagi: aplikasi memulihkan aksesnya. Tetap gagal: **Pasang ulang**. |
| `Permission denied (EACCES)` saat menulis ke `/etc` atau `/root` | Biasanya `/etc/resolv.conf` dikembalikan `apt` sebagai symlink ke `/run/systemd`. Aplikasi menggantinya di setiap sesi chroot. Jika berulang, kirim `ls -ldZ /etc /etc/resolv.conf /root /root/.claude`. |
| `ptrace` ❌ di doctor | Kernel atau seccomp menolak ptrace. Pakai `rea run --mode emu` dan `rea emu`. |
| `unicorn` atau `frida` tidak terpasang | `python3 -m pip install --break-system-packages unicorn frida` (perlu internet; ada wheel aarch64). |
| `qemu-aarch64` tidak ada | `apt install qemu-user`. |
| `rea run --mode emu` tidak kena breakpoint | Gunakan versi REA terbaru (≥ 0.7): alamat muat PIE dibaca dari auxv. |
| Pesan "tool_missing" saat `firmware --extract` | Pasang paket yang disebut di pesan (mis. `apt install squashfs-tools`). |

## 9. Pengembangan

Paket ada di `app/src/main/assets/rea/rea/` dan tesnya di `rea-tests/`:

```sh
pip install pytest capstone pyelftools unicorn frida
sudo apt-get install qemu-user gcc-aarch64-linux-gnu gdb strace squashfs-tools cpio
PYTHONPATH=app/src/main/assets/rea python3 -m pytest rea-tests -q
```

Tes yang butuh alat (gdb, QEMU, mksquashfs, ...) dilewati bila alatnya tidak ada; CI memasang semuanya supaya tidak ada yang
dilewati. Versi paket (`rea/__init__.py`) harus sama dengan `ReaController.REA_VERSION` di sisi Kotlin; tes unit memeriksanya.
