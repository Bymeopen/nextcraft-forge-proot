# NextCraft Forge

Aplikasi Android (Kotlin, Jetpack Compose) yang menjalankan lingkungan pengembangan dan agen AI di perangkat
(rootfs chroot/PRoot, terminal, build Gradle).

- minSdk 31, targetSdk 36, compileSdk 37, ABI `arm64-v8a`
- Build: `gradle :app:assembleDebug`; test: `gradle :app:testDebugUnitTest`
- Release: lihat [docs/RELEASING.md](docs/RELEASING.md) (butuh `FORGE_KEYSTORE_*`); `-PrequireNdk` mewajibkan NDK
- Arsitektur: [ARCHITECTURE.md](ARCHITECTURE.md); izin: [docs/PERMISSIONS.md](docs/PERMISSIONS.md)
- Lisensi: [LICENSE](LICENSE), pihak ketiga: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
