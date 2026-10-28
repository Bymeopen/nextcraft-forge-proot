# NextCraft Forge (`com.nextcraft.forge`) — Master Engineering Blueprint (Fase 1 s/d Fase 7)

> **Identitas Proyek**: **NextCraft Forge** (`com.nextcraft.forge`) — Autonomous Mobile AI Coding IDE & Native Android Build System  
> **Basis**: NextCraft Forge (`com.nextcraft.forge`, Clean-Room Sovereign Architecture v2.0). Atribusi lisensi & inspirasi konseptual pihak ketiga: lihat [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).  
> **Mesin Runtime Dual-Engine**: **Primary Root Chroot (`su -mm` + Linux Kernel Mounts)** dengan **Automatic Non-Root PRoot Fallback (`libproot.so`)** di atas **Ubuntu 24.04.5 LTS (`noble`) ARM64** (`ProotAndChrootOptimizer` + `DeviceHardwareOptimizer`)  
> **Strategi AI**: **Multi-Agent CLI Harness (`Claude Code`, `Antigravity CLI`, `DeepSeek Harness`) + Cloud AI Providers via On-Device Loopback Gateway + Built-in Forge MCP Server & Reference Index (`forge_reference_*`)** (Tanpa Local LLM Engine / Tanpa Fine-Tuning)  
> **Prinsip Dokumen**: **100% Faktual, Tanpa Placeholder, Tanpa Halusinasi**. Setiap bagian dari *Master Engineering Prompt (Fase 1 s/d Fase 7)* dieksekusi menggunakan arsitektur nyata **Zero-Cloud On-Device** NextCraft Forge.

---

## Pemetaan Paradigma: Template SaaS Cloud Umum vs Arsitektur Nyata NextCraft Forge

Sebelum masuk ke Fase 1–7, tabel berikut memetakan komponen standar pada template *Mobile AI Builder* umum ke komponen faktual yang ada di **NextCraft Forge**:

| Komponen pada Prompt Template | Mengapa Tidak Dipakai Mentah | Ekuivalen Faktual di **NextCraft Forge (`com.nextcraft.forge`)** |
| :--- | :--- | :--- |
| **Flutter (Dart) / React Native** | Butuh akses JNI level rendah ke PTY (`/dev/ptmx`), proses grup UNIX (`setpgid`), `FileObserver` (`inotify`), dan `PackageInstaller` Android. | **Android Native (Kotlin 2.x + Jetpack Compose Material 3 + C11 JNI `libforgespawn.so`)** |
| **Cloud Backend (Python FastAPI + Nginx)** | NextCraft Forge adalah aplikasi **100% Local-First / Zero-Cloud Backend** (tanpa biaya server bulanan). | **On-Device Loopback Gateway (`LocalFormatGateway.kt` & `OpenRouterRoutingGateway.kt` di `127.0.0.1:<random_port>`)** |
| **PostgreSQL + `pgvector` + Redis** | Membuka DB server & Vector DB di HP menghabiskan RAM; pencarian kode sumber jauh lebih presisi dengan pencarian leksikal langsung di filesystem. | **`AppPreferences.kt` (JSON), `ApiKeyVault.kt` (`AndroidKeyStore` AES-GCM), `WorkspaceCheckpoints.kt` (`cp -al` hardlink), & `ripgrep` (`rg`) di `/workspace/nextcraft`** |
| **Celery + Redis/RabbitMQ Workers** | Pekerjaan panjang (kompilasi Gradle, ekstraksi `.tar.zst`, eksekusi AI Agent) berjalan lokal di OS Android. | **Android Foreground Services (`RuntimeExecutionService.kt` & `RuntimeSetupService.kt`) + `PARTIAL_WAKE_LOCK` + Bypass Phantom Process Killer via `su`** |
| **Terraform AWS ECS + Docker ECR** | Tidak ada container cloud; kontainer adalah rootfs Ubuntu 24.04 lokal yang didistribusikan secara statis. | **Deterministic Rootfs Pipeline (`build-core-on-rooted-android.sh` + `create_deterministic_tar.py` + GitHub Releases Static Edge `manifest.json`)** |
| **Local LLM (`llama.cpp`) / Fine-Tuning (`LoRA`)** | Dihapus sesuai keputusan arsitektur: model lokal menguras RAM/baterai yang dibutuhkan oleh kompilasi Gradle/Kotlin. | **Cloud API Providers (`Anthropic`, `OpenRouter`, `DeepSeek`, `Kimi`, `NIM`) + Official CLI Agents (`claude`, `agy`, `dsh`)** |

---

## Daftar Isi (dipecah per fase)

- [FASE 1–2: Discovery & Architecture Decisions](docs/architecture/fase-1-2-discovery-dan-arsitektur.md)
- [FASE 3: Mobile App Layer](docs/architecture/fase-3-mobile-app.md)
- [FASE 4: On-Device Subsystem & Chroot Runtime](docs/architecture/fase-4-runtime-chroot.md)
- [FASE 5–7: Agent Guardrails, DevOps & Ship](docs/architecture/fase-5-7-agent-devops-ship.md)
