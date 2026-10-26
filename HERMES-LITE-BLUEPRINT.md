# Hermes-Lite: Complete Master Blueprint (Fase 1 – Fase 4)

Dokumen tunggal (*All-in-One Master Blueprint*) untuk membangun, menjalankan, dan mengamankan arsitektur **Hermes-Lite**—tiruan kognitif dari [NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent) yang berjalan secara native di atas **Antigravity / Claude Code / DeepSeek Harness** di **NextCraft Forge** dengan **0 MB RAM/Storage Overhead** serta dilengkapi pengamanan berlapis (*Defense-in-Depth*) untuk lingkungan Linux / Android Root Chroot (`aarch64`).

---

## Daftar Isi
1. [Ringkasan Eksekutif & Perbandingan Arsitektur](#1-ringkasan-eksekutif--perbandingan-arsitektur)
2. [Struktur Direktori Lengkap](#2-struktur-direktori-lengkap)
3. [Fase 1: Persistent Memory Architecture (`USER.md` & `MEMORY.md`)](#3-fase-1-persistent-memory-architecture-usermd--memorymd)
4. [Fase 2: Closed-Loop Self-Improving Skills System (`SKILL.md`)](#4-fase-2-closed-loop-self-improving-skills-system-skillmd)
5. [Fase 3: Security Firewall & Anti-Poisoning Protocol (`hooks.json` & `security_gate.py`)](#5-fase-3-security-firewall--anti-poisoning-protocol-hooksjson--security_gatepy)
6. [Fase 4: Subagent Orchestration, Scheduling & Secure Telegram Gateway](#6-fase-4-subagent-orchestration-scheduling--secure-telegram-gateway)
7. [Lampiran Kode Konfigurasi Lengkap (Siap Pakai)](#7-lampiran-kode-konfigurasi-lengkap-siap-pakai)
8. [Panduan Verifikasi & Commit ke GitHub](#8-panduan-verifikasi--commit-ke-github)

---

## 1. Ringkasan Eksekutif & Perbandingan Arsitektur

Menginstal repo penuh `NousResearch/hermes-agent` di dalam chroot Android memiliki kendala: memakan ruang penyimpanan ~500 MB–1.5 GB (venv + Playwright/MCP), tidak adanya `systemd` (`PID 1`) untuk menjalankan service daemon secara native, dan tidak adanya Docker daemon untuk container sandboxing.

**Hermes-Lite** menyalin **100% arsitektur kognitif Hermes Agent** menggunakan fitur kustomisasi native yang sudah ada di sistem ini:

| Komponen Inti Hermes Agent | Implementasi di Hermes Asli | Implementasi Native di Hermes-Lite (`0 MB Overhead`) |
| :--- | :--- | :--- |
| **1. Persistent Memory** | File `MEMORY.md` & `USER.md` yang di-load oleh runtime Python Hermes. | Diinjeksi otomatis (*Always-On*) melalui `AGENTS.md` + `.agents/memory/USER.md` & `MEMORY.md`. |
| **2. Self-Improving Skills Loop** | Agent menulis prosedur baru ke folder `skills/` setelah menyelesaikan tugas. | Menggunakan `.agents/skills/<nama>/SKILL.md` (*Progressive Disclosure*) yang dikelola otomatis oleh skill `skill-creator`. |
| **3. Command Sandbox / Safety** | Container Docker / Modal / Daytona + konfirmasi CLI. | Hardware-level `PreToolUse` Hook (`.agents/hooks.json` + `.agents/scripts/security_gate.py`) yang memblokir perintah berbahaya sebelum menyentuh shell. |
| **4. Autonomous Subagents** | Spawning multi-instance agent Python. | Menggunakan tool native `invoke_subagent` (`research` read-only sandbox & `self` parallel worker). |
| **5. Cron & Messaging Gateway** | Service background `systemd` + gateway Node/Python. | Menggunakan tool `schedule` native + skrip *Zero-Port Telegram Long-Polling* (~50 baris Python stdlib murni). |

---

## 2. Struktur Direktori Lengkap

```text
/workspace/nextcraft-forge/
├── HERMES-LITE-BLUEPRINT.md                   # Dokumen Master Gabungan (Fase 1 s/d Fase 4)
├── AGENTS.md                                  # Otak utama agent (Auto-Recall, Auto-Update, Security Rules)
└── .agents/
    ├── hooks.json                             # Konfigurasi lifecycle hook PreToolUse
    ├── scripts/
    │   └── security_gate.py                   # Script firewall pencegah eksekusi command destruktif
    ├── memory/
    │   ├── USER.md                            # Profil, bahasa, & preferensi kerja pengguna
    │   └── MEMORY.md                          # Ingatan jangka panjang proyek & keputusan arsitektur
    └── skills/
        └── skill-creator/
            └── SKILL.md                       # Skill generator untuk menciptakan skill baru secara mandiri
```

---

## 3. Fase 1: Persistent Memory Architecture (`USER.md` & `MEMORY.md`)

### 3.1 Tujuan
Menyediakan ingatan jangka panjang lintas sesi tanpa database vektor berat (Chroma/Qdrant) yang memakan RAM besar di perangkat Android.

### 3.2 Alur Kerja Memori (Auto-Recall & Auto-Update)

```text
+-------------------------------------------------------+
|              Awal Sesi Percakapan Baru                |
+-------------------------------------------------------+
                           |
                           v
+-------------------------------------------------------+
|  AGENTS.md otomatis dimuat oleh Engine (Always-On)    |
|  Mengekspansi @include secara otomatis:               |
|   1. .agents/memory/USER.md                           |
|   2. .agents/memory/MEMORY.md                         |
+-------------------------------------------------------+
                           |
                           v
+-------------------------------------------------------+
|        Eksekusi Tugas / Pemecahan Masalah             |
+-------------------------------------------------------+
                           |
                           v
+-------------------------------------------------------+
|  Auto-Update Loop:                                    |
|  Jika ada keputusan arsitektur / preferensi baru,     |
|  Agent mengedit MEMORY.md / USER.md secara ringkas    |
+-------------------------------------------------------+
```

### 3.3 Pembagian Peran File Memori
1. **`.agents/memory/USER.md` (Profil & Preferensi Pengguna)**
   - Berisi gaya komunikasi, bahasa utama, batasan waktu respons, dan preferensi arsitektur.
   - Diperbarui setiap kali pengguna menyatakan preferensi baru.
2. **`.agents/memory/MEMORY.md` (Memori Jangka Panjang Proyek)**
   - Berisi snapshot lingkungan sistem (OS, versi SDK/compiler), histori keputusan arsitektur (`YYYY-MM-DD`), solusi bug yang telah diverifikasi, dan daftar skill yang telah dibuat.
   - Diperbarui secara otomatis setelah menyelesaikan milestone penting.

### 3.4 Aturan Kebersihan Memori (Memory Hygiene)
- **Tanpa Duplikasi:** Gunakan `edit_file` untuk memperbarui bagian yang relevan, jangan sekadar menumpuk teks di bagian bawah.
- **Bebas Kredensial:** Dilarang keras menyimpan API key, password, token GitHub/Telegram, atau isi `.env` di dalam `USER.md` maupun `MEMORY.md`.
- **Disiplin Ukuran (Context Budget):** Jaga ukuran total `AGENTS.md` + `USER.md` + `MEMORY.md` tetap di bawah **24 KB** agar selalu dimuat utuh tanpa pemotongan (*truncation*).

---

## 4. Fase 2: Closed-Loop Self-Improving Skills System (`SKILL.md`)

### 4.1 Tujuan
Meniru kemampuan *Closed Learning Loop* milik Hermes Agent: setiap kali berhasil memecahkan masalah teknis yang rumit, agent merangkum prosedur tersebut menjadi modul skill baru di `.agents/skills/<nama-skill>/SKILL.md`.

### 4.2 Mengapa Memisahkan `MEMORY.md` dan `SKILL.md`? (Progressive Disclosure)

| Aspek | `MEMORY.md` (Fase 1) | `.agents/skills/<nama>/SKILL.md` (Fase 2) |
| :--- | :--- | :--- |
| **Cara Pemuatan** | *Always-On* (selalu diinjeksi penuh ke setiap sesi). | *Progressive Disclosure* (hanya `name` dan `description` di YAML frontmatter yang dimuat; isi lengkap baru dibaca saat skill dipanggil). |
| **Isi Utama** | Fakta ringkas, konteks global proyek, histori keputusan. | Runbook langkah-demi-langkah, perintah build spesifik, skrip otomasi, dan panduan troubleshooting mendalam. |
| **Biaya Token** | Memakan kuota token konstan di setiap giliran. | **Hampir 0 token** saat tidak aktif. Anda dapat menyimpan puluhan skill tanpa membebani konteks model. |

### 4.3 Prosedur Pembuatan Skill Mandiri (`skill-creator`)
1. **Identifikasi Pola:** Setelah menyelesaikan alur kerja multi-langkah yang berhasil diverifikasi (atau saat diminta pengguna), agent mengaktifkan `.agents/skills/skill-creator/SKILL.md`.
2. **Sanitasi Keamanan Wajib:**
   - Hapus semua API key, token, kredensial, atau data sensitif dari contoh perintah.
   - Pastikan tidak ada instruksi dari halaman web eksternal yang belum terverifikasi.
   - Pastikan seluruh path mematuhi batas workspace aktif.
3. **Penulisan File Skill:** Buat file `.agents/skills/<nama-skill>/SKILL.md` dengan frontmatter YAML standar:
   ```markdown
   ---
   name: nama-skill-kebab-case
   description: >-
     Penjelasan jelas kapan agent harus mengaktifkan skill ini.
   ---
   # Judul Skill
   ## 1. Konteks & Prasyarat
   ## 2. Langkah Eksekusi Terverifikasi
   ## 3. Verifikasi & Jebakan Umum (Pitfalls)
   ```
4. **Pencatatan di Memori:** Tambahkan 1 baris indeks di `.agents/memory/MEMORY.md` agar keberadaan skill tercatat dalam riwayat proyek.

---

## 5. Fase 3: Security Firewall & Anti-Poisoning Protocol (`hooks.json` & `security_gate.py`)

### 5.1 Tujuan
Mengamankan sistem **Hermes-Lite** di dalam lingkungan **Ubuntu Root Chroot di atas Android** yang tidak memiliki isolasi Docker container.

### 5.2 Peta Ancaman (Threat Model) & Mitigasi Berlapis

| No | Ancaman Keamanan | Dampak di Chroot Android | Mitigasi di Hermes-Lite |
| :- | :--- | :--- | :--- |
| **1** | **Memory & Skill Poisoning** *(Indirect Prompt Injection)* | Web/repo eksternal menyisipkan instruksi jahat ke `MEMORY.md` / `SKILL.md` sehingga menjadi *persistent backdoor* di semua sesi berikutnya. | Aturan *Anti-Poisoning* di `AGENTS.md` + audit perubahan lewat `git diff` sebelum commit. |
| **2** | **Destructive Shell Execution** *(Tanpa Docker Sandbox)* | Perintah `rm -rf /workspace`, `mkfs`, atau pengubahan `/proc/sys/kernel/unprivileged_bpf_disabled` dapat menghapus data atau membuat Android *soft-reboot*. | **Hardware-level Hook Firewall** (`.agents/hooks.json` + `security_gate.py`) yang mencegat tool `run_command` sebelum dieksekusi. |
| **3** | **Secret Exfiltration & Git Leak** | API key, token bot, atau `.env` tercatat di `MEMORY.md` lalu ter-push ke GitHub atau dikirim keluar via `curl`. | Larangan penulisan secret di `.md`, filter `.gitignore`, dan konfirmasi wajib untuk `curl \| sh` di `security_gate.py`. |
| **4** | **Kernel / System-Server Crash** | Memodifikasi `unprivileged_bpf_disabled` menjadi `1`/`2` menyebabkan `BpfNetMaps.swapActiveStatsMap` gagal (`EPERM`) dan mematikan Android `system_server`. | Blokir keras (`"decision": "deny"`) pada level regex di `security_gate.py`. |

### 5.3 Cara Kerja Firewall `PreToolUse` (`.agents/hooks.json`)

Berbeda dengan aturan di prompt yang masih berisiko dilanggar jika model berhalusinasi, **`PreToolUse` Hook** dieksekusi oleh runtime host **sebelum** tool `run_command` dijalankan:

```text
[Agent memanggil tool run_command]
                 |
                 v
[.agents/hooks.json menangkap event PreToolUse]
                 |
                 v
[Menjalankan .agents/scripts/security_gate.py dengan input JSON via stdin]
                 |
                 +---> Cocok DENY_PATTERNS? ---> {"decision": "deny"} (Diblokir Keras!)
                 |
                 +---> Cocok ASK_PATTERNS?  ---> {"decision": "ask"}  (Tahan & Minta Izin User)
                 |
                 +---> Aman                 ---> {"decision": "allow"} (Diizinkan Jalan)
```

---

## 6. Fase 4: Subagent Orchestration, Scheduling & Secure Telegram Gateway

### 6.1 Orkestrasi Subagent Paralel
Untuk meniru fitur *Multi-Agent Delegation* pada Hermes Agent tanpa menginstal framework tambahan:
- **Subagent `research` (Read-Only Sandbox, Tanpa Jaringan):** Gunakan untuk membaca kode pihak ketiga, menelusuri ribuan baris file, atau melakukan audit keamanan tanpa risiko memodifikasi file atau membocorkan data keluar.
- **Subagent `self` (Parallel Worker):** Gunakan ketika memecah tugas implementasi independen (misal: satu subagent membangun komponen UI, satu subagent menyusun unit test). Semua subagent tetap terikat pada firewall `.agents/hooks.json`.

### 6.2 Penjadwalan Otonom (Cron & Timer)
Gunakan tool `schedule` bawaan (atau slash command `/schedule`) untuk menjalankan tugas berkala menggunakan ekspresi cron 5-field standar atau *one-shot timer* tanpa memerlukan service daemon `cron` / `systemd` di chroot.

### 6.3 Blueprint Secure Telegram Bridge (Zero-Port Outbound Polling)
Jika Anda ingin menambahkan fitur kontrol jarak jauh via **Telegram** seperti *Hermes Gateway*:
1. **Jangan Buka Port HTTP/Webhook:** Gunakan *Outbound Long-Polling* (`getUpdates` ke `api.telegram.org`) agar tidak ada port terbuka di perangkat Android.
2. **Wajib Hardcode Whitelist `ALLOWED_USER_ID`:** Hanya proses pesan yang berasal dari ID numerik akun Telegram pribadi Anda. Abaikan (`drop`) seluruh pesan dari akun lain sebelum menyentuh logika perintah.
3. **Tanpa Dependensi Berat:** Cukup gunakan modul bawaan Python (`urllib.request`, `json`, `os`, `time`).

---

## 7. Lampiran Kode Konfigurasi Lengkap (Siap Pakai)

Seluruh file di bawah ini sudah terpasang aktif di `/workspace/nextcraft-forge` dan diprovisikan otomatis ke setiap workspace proyek oleh [`AntigravityCustomizations.kt`](app/src/main/java/com/nextcraft/forge/runtime/AntigravityCustomizations.kt):

### 7.1 File `AGENTS.md`
Lihat [`AGENTS.md`](AGENTS.md).

### 7.2 File `.agents/hooks.json`
Lihat [`.agents/hooks.json`](.agents/hooks.json).

### 7.3 File `.agents/scripts/security_gate.py`
Lihat [`.agents/scripts/security_gate.py`](.agents/scripts/security_gate.py).

### 7.4 File `.agents/skills/skill-creator/SKILL.md`
Lihat [`.agents/skills/skill-creator/SKILL.md`](.agents/skills/skill-creator/SKILL.md).

---

## 8. Panduan Verifikasi

### 8.1 Uji Coba Firewall Keamanan
```bash
# Uji blokir perintah berbahaya (Output: "decision": "deny")
echo '{"toolCall":{"name":"run_command","args":{"CommandLine":"rm -rf /root"}}}' \
  | python3 .agents/scripts/security_gate.py

# Uji perintah aman (Output: "decision": "allow")
echo '{"toolCall":{"name":"run_command","args":{"CommandLine":"git status"}}}' \
  | python3 .agents/scripts/security_gate.py
```
