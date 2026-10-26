# Merilis aplikasi dan update dalam aplikasi

Cek update di Settings membaca `https://github.com/bymeopen/nextcraft-forge/releases/latest/download/update-manifest.json`.

## Rilis lewat GitHub Actions

1. Tambahkan secret repositori (kunci yang sama dengan aplikasi yang terpasang, atau update akan ditolak karena tanda tangan beda):
   `FORGE_KEYSTORE_BASE64` (`base64 -w0 forge.keystore`), `FORGE_KEYSTORE_PASSWORD`, `FORGE_KEY_ALIAS`, `FORGE_KEY_PASSWORD`.
2. Actions → **Release app** → Run workflow, isi tag (mis. `v1.0.1`), `version_code` (harus lebih besar dari build terpasang; saat ini 100), `version_name`.
3. Workflow membangun APK release bertanda tangan, membuat `update-manifest.json` (`scripts/make-update-manifest.sh`) dan menerbitkan keduanya sebagai rilis **latest**.

Rilis runtime (`runtime-*`) tidak boleh menjadi rilis "latest": tandai sebagai pre-release atau terbitkan lebih dulu, supaya cek update tidak membaca release yang salah.

## Manual

```bash
FORGE_KEYSTORE_FILE=... FORGE_KEYSTORE_PASSWORD=... FORGE_KEY_ALIAS=... FORGE_KEY_PASSWORD=... \
  gradle :app:assembleRelease -PversionCode=101 -PversionName=1.0.1
scripts/make-update-manifest.sh app/build/outputs/apk/release/app-release.apk 101 1.0.1 v1.0.1
```

Unggah APK dan `update-manifest.json` ke rilis `v1.0.1`, lalu tandai sebagai latest.

## CI

`.github/workflows/ci.yml` menjalankan unit test, `assembleDebug`, cek sintaks skrip, dan memastikan
`scripts/runtime-bundles/{chroot-exec,unmount-chroot}.sh` sama dengan string yang dikirim di `RuntimeInstaller.kt`
(`python3 scripts/check-embedded-scripts.py --write` untuk membuat ulang).

## Build debug (`release-debug.yml`)

Debug dan release memakai application id yang sama, `com.nextcraft.forge`: satu aplikasi, satu folder data, satu rootfs
Ubuntu. Tanda tangannya berbeda: debug memakai kunci publik di `app/debug.keystore` (supaya build CI saling menimpa),
release memakai secret `FORGE_KEYSTORE_*`. Android menolak memasang yang satu di atas yang lain, dan pembaruan dalam
aplikasi memeriksa sertifikat lebih dulu lalu menjelaskannya. Berpindah dari satu jenis ke yang lain berarti
menghapus aplikasi, yang ikut menghapus rootfs, chat, dan izin root; cadangkan dulu.

Workflow menerbitkan rilis bergulir `dev-latest` berisi `nextcraft-forge-debug-<versi>.apk` dan manifest yang entri
`artifacts.debug`-nya hanya dibaca build debug; build release tidak menemukan `artifacts.release` di sana dan tidak
menawarkan apa-apa. Jalankan dari tab Actions (`Release debug build`, pilih branch). Build sebelum perubahan ini terpasang
sebagai `com.nextcraft.forge.x` dan tidak bisa memperbarui ke package ini dari dalam aplikasi: pasang APK baru sekali
secara manual.
