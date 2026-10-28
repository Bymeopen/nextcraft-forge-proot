# Mini Sekolah

Sampel kecil untuk menguji toolchain Android di perangkat: database sekolah mini (siswa dan nilai) dengan SQLite,
widget bawaan Android, Java, tanpa dependensi tambahan.

- Daftar siswa dengan pencarian (nama, NIS, kelas), tambah, ubah (tekan lama), hapus (tekan lama).
- Layar siswa: daftar nilai per mata pelajaran, rata-rata berwarna, tambah nilai, hapus nilai (tekan lama).
- Menu titik tiga: "Isi data contoh" (5 siswa dengan nilai).
- NIS unik; hapus siswa ikut menghapus nilainya (ON DELETE CASCADE).

Build: `gradle --no-daemon :app:assembleDebug` di folder ini. APK ada di `app/build/outputs/apk/debug/`.
