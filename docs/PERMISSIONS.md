# Izin aplikasi

Semua izin dideklarasikan di `app/src/main/AndroidManifest.xml`. Aplikasi ini didistribusikan lewat GitHub Releases
(sideload). Beberapa izin di bawah kemungkinan besar tidak lolos kebijakan Google Play.

| Izin | Dipakai oleh | Alasan | Risiko Play |
| :--- | :--- | :--- | :--- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | jaringan, pembaruan | Panggilan API provider AI, unduh runtime dan pembaruan | Rendah |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | `RuntimeExecutionService`, `RuntimeSetupService` | Build dan sesi agen yang lama | Sedang |
| `WAKE_LOCK` | layanan runtime | Menjaga proses tetap berjalan | Rendah |
| `POST_NOTIFICATIONS` | layanan runtime | Notifikasi progres | Rendah |
| `RECORD_AUDIO` | `ui/VoiceInput.kt` | Input suara (opsional, mikrofon `required=false`) | Sedang |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | `runtime/BatteryOptimization.kt` | Mengecualikan aplikasi dari optimasi baterai | Tinggi |
| `REQUEST_INSTALL_PACKAGES` | `runtime/AndroidAppInstaller.kt` | Memasang APK hasil build dan pembaruan aplikasi | Tinggi |
| `QUERY_ALL_PACKAGES` | `runtime/AndroidAppInstaller.kt`, `AndroidAppInstallReceiver.kt` | Hanya jika kedua file itu memang memakainya (belum diverifikasi per baris) | Tinggi |

Catatan: kolom "Dipakai oleh" berasal dari pencarian kata kunci, bukan analisis alur. Verifikasi sebelum mengajukan
ke Play. Bila target Play dipilih, mulai dari memangkas tiga izin berisiko tinggi.
