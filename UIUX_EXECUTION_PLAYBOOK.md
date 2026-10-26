# UI/UX EXECUTION PLAYBOOK — NextCraft Forge (Android · Jetpack Compose · M3)

> Status: **rencana eksekusi, belum ada kode aplikasi yang diubah.**
> Dokumen ini melengkapi `ZUAN_UI_UX_ARCHITECTURE_100.md`, `AIRBNB_UI_UX_ARCHITECTURE_100.md`, dan `MULTI_THEME_ARCHITECTURE_100.md`. Dokumen-dokumen itu menjawab *"seperti apa tampilannya"*. Dokumen ini menjawab *"urutan kerja, aturan, dan bukti lulus"*.

> **Konteks produk: aplikasi mobile LOKAL (offline-first, tanpa backend sendiri, distribusi sideload via `AppUpdater` + manifest HTTPS).**
> Konsekuensi: satu-satunya trafik keluar adalah panggilan ke provider AI (dan cek update), tidak ada analitik/A-B server, tidak ada staged rollout Play Console, TMS bersifat opsional. Semua metrik dikumpulkan **di perangkat** atau lewat uji manual. Bagian E7/E8, §3, dan DoD di bawah sudah disesuaikan.

---

> **Pembaruan 2026-10-19 (audit UX ulang):** tabel baseline di §0 sudah usang. Kondisi terukur sekarang: 557 string (id & en lengkap), 627 pemakaian `stringResource`, 13 literal `Text`, 56 `semantics`/`Role`, 6 file guard test. Yang masih terbuka: locale ar/es/hi/ru/zh-CN hanya stub (disembunyikan dari pemilih lewat `AppLanguage.translated`), screenshot test (Paparazzi/Roborazzi) belum ada, dan `MainViewModel` masih ±3.100 baris.

## 0. Audit baseline (diukur dari repo, bukan asumsi)

| Temuan | Nilai terukur | Dampak | Prioritas |
|---|---|---|---|
| String di `res/values/strings.xml` | **2** (`app_name`, `app_tagline`) | Seluruh teks UI tidak ada di resource | P0 |
| Pemakaian `stringResource(...)` di `java/` | **0** | Lokalisasi resmi Android tidak terpakai | P0 |
| Literal `Text("...")` di `ui/*.kt` | **87** | Tidak bisa diterjemahkan lewat TMS, tidak bisa dites pseudo-locale | P0 |
| `contentDescription = null` | **28** vs 19 berisi teks | Ikon tanpa label untuk TalkBack | P0 |
| `semantics` / `Role.` | **0** | Elemen interaktif custom tidak terbaca benar | P1 |
| Direktori `src/test`, `src/androidTest` | **tidak ada** (hanya `src/main`) | Tidak ada jaring pengaman regresi | P0 |
| `android:supportsRtl` | `true` (sudah benar) | Arab siap secara manifest, belum teruji | P1 |
| `localeConfig` (per-app language, Android 13+) | **tidak ada** | Bahasa app tidak muncul di Settings sistem | P1 |
| Locale terdaftar | id, en, es, zh-CN, ar, hi, ru (7) | Fondasi bagus, isinya kosong | — |
| Mekanisme bahasa | `LocaleHelper` + `AppStrings.get(context, @StringRes id)` | Infrastruktur sudah ada, tinggal diisi | — |
| Compose BOM | `2022.10.00` (lawas) | M3 Expressive & API baru butuh BOM lebih baru | P1 |
| Token desain | `Theme.kt` (505 baris), konstanta `ForgeCyan…` | Sudah ada embrio token, belum berlapis primitive → semantic | P1 |

**Kesimpulan:** bottleneck UX internasional ada di **lapisan i18n + aksesibilitas + test**, bukan di visual. Urutan eksekusi mengikuti itu.

---

## 1. Peta eksekusi (dependency graph)

```
        ┌──────────────┐
        │ E0 Guardrail │  lint, test harness, baseline screenshot
        └──────┬───────┘
               ▼
        ┌──────────────┐        ┌──────────────┐
        │ E1 i18n Core │───────▶│ E2 A11y Core │   (paralel setelah E1 selesai
        └──────┬───────┘        └──────┬───────┘    per-layar)
               ▼                       ▼
        ┌──────────────────────────────────────┐
        │ E3 Token & Theme (primitive→semantic)│
        └──────────────────┬───────────────────┘
                           ▼
        ┌──────────────────────────────────────┐
        │ E4 Komponen inti + 5 state wajib     │
        └──────────────────┬───────────────────┘
                           ▼
        ┌──────────────────────────────────────┐
        │ E5 Migrasi 7 layar (1 PR / layar)    │
        └──────────────────┬───────────────────┘
                           ▼
        ┌──────────────────────────────────────┐
        │ E6 QA gate → E7 Rollout → E8 Iterasi │
        └──────────────────────────────────────┘
```

Aturan: **satu PR = satu tujuan**, ≤ 400 baris diff, selalu hijau di CI sebelum merge.

---

## 2. Rincian per tahap

### E0 — Guardrail (wajib sebelum mengubah UI)

| Tugas | Detail | Bukti lulus |
|---|---|---|
| Tambah `src/test` + `src/androidTest` | JUnit5 (logic), Compose UI test (`createComposeRule`) | `./gradlew test` hijau |
| Lint ketat | `lint { warningsAsErrors = true; abortOnError = true }`, aktifkan `HardcodedText`, `MissingTranslation`, `RtlHardcoded`, `ContentDescription` | Lint gagal bila ada literal baru |
| Screenshot baseline | Paparazzi atau Roborazzi: 7 layar × (light, dark) × (en, ar, id) × fontScale (1.0, 2.0) | Golden image tersimpan |
| Detekt/ktlint | Satu gaya kode | CI hijau |

### E1 — i18n Core (inti kelancaran internasional)

```
 literal Text("Run")  ──▶  stringResource(R.string.action_run)
        │                          │
        │ (ekstrak otomatis)       ▼
        ▼                  values/strings.xml  ──▶ TMS (Crowdin/Lokalise) ──▶ values-xx/strings.xml
  daftar 87 string                │
                                  └─▶ plurals.xml, formatted="false" bila perlu
```

Aturan penamaan key: `{layar}_{elemen}_{tujuan}`, contoh `studio_composer_hint`, `launch_build_action`.

```kotlin
// SALAH: literal + konkatenasi (tidak bisa dilokalisasi, urutan kata beda tiap bahasa)
Text("Build " + count + " files")

// BENAR: resource berparameter + plural
Text(pluralStringResource(R.plurals.launch_files_built, count, count))
```

```xml
<!-- values/strings.xml -->
<plurals name="launch_files_built">
    <item quantity="one">Built %d file</item>
    <item quantity="other">Built %d files</item>
</plurals>
```

Karena `AppStrings.get(context, id)` sudah ada, aturan penggunaannya:

| Konteks | API |
|---|---|
| Di dalam `@Composable` | `stringResource(id)` |
| Di ViewModel / non-UI | `UiText` (sealed: `Res(id,args)` / `Raw(text)`), di-resolve di UI. **Jangan** simpan `Context` di ViewModel. |
| Service / notifikasi | `AppStrings.get(context, id)` |

Tambahan:
1. `res/xml/locales_config.xml` + `android:localeConfig` di manifest, agar bahasa muncul di Settings sistem (Android 13+).
2. Format angka/tanggal/ukuran file pakai `NumberFormat.getInstance(locale)` / `DateFormat`, bukan `String.format("%d")` mentah.
3. Hindari string yang disusun dari potongan (`"Total " + n + " " + unit`).

**Gate E1:** `grep 'Text("'` di `ui/` = 0, lint `MissingTranslation` = 0, pseudo-locale (`en-XA`, `ar-XB`) lolos tanpa teks terpotong.

### E2 — Aksesibilitas inti

| Aturan | Implementasi Compose | Uji |
|---|---|---|
| Semua ikon informatif punya label | `Icon(..., contentDescription = stringResource(...))`. Ikon dekoratif tetap `null`, tapi **harus disengaja** (komentar). | 28 `null` ditinjau satu per satu |
| Target sentuh ≥ 48dp | `Modifier.minimumInteractiveComponentSize()` | UI test ukuran |
| Peran semantik | `Modifier.semantics { role = Role.Button }`, `clickable(role=…, onClickLabel=…)` | `onNodeWithContentDescription` |
| Kontras | Teks 4.5:1, komponen UI 3:1, cek di kedua tema | Skrip kontras di test token |
| Font scale | Semua teks `sp`, tidak ada tinggi tetap pada teks; uji 200% | Screenshot fontScale 2.0 |
| Urutan fokus & TalkBack | `traversalIndex`, `heading()` untuk judul seksi | Manual + Accessibility Test Framework |
| Reduce motion | Baca `Settings.Global.ANIMATOR_DURATION_SCALE`; bila 0, matikan animasi dekoratif | Test dengan scale 0 |
| Status tidak hanya warna | Error = warna + ikon + teks | Review desain |

```kotlin
// Aktifkan pemeriksaan otomatis di UI test
composeRule.enableAccessibilityChecks()
```

### E3 — Token & Theme (primitive → semantic)

Saat ini `Theme.kt` memegang konstanta mentah. Targetnya:

```
 Primitive (ForgeCyan, space.4, radius.md)
        │  tidak boleh dipakai langsung oleh layar
        ▼
 Semantic (ForgeColors.actionPrimary, surfaceElevated, textSecondary, ForgeSpacing.md)
        │  dipetakan dari ColorScheme + preset tema (24 preset Theme Studio)
        ▼
 CompositionLocal  (LocalForgeColors, LocalForgeSpacing, LocalForgeShapes)
        ▼
 Komponen & layar
```

```kotlin
@Immutable
data class ForgeSpacing(val xs: Dp = 4.dp, val sm: Dp = 8.dp, val md: Dp = 16.dp, val lg: Dp = 24.dp)

val LocalForgeSpacing = staticCompositionLocalOf { ForgeSpacing() }

// Pemakaian: tidak ada angka dp "ajaib" di layar
Modifier.padding(horizontal = LocalForgeSpacing.current.md)
```

Aturan emas: **261 pemakaian `.dp` di `ui/*.kt` dikurangi bertahap** ke token. Target akhir: literal `.dp` hanya boleh ada di `Theme.kt` dan file token. Tegakkan dengan custom lint rule atau detekt `ForbiddenPattern`.

RTL: ganti `Alignment.Start/End` yang dimaksudkan fisik, gunakan `padding(start=, end=)` (sudah RTL-aware), tidak pernah `left/right`. Ikon berarah (panah, "back") pakai `Icons.AutoMirrored.*`.

### E4 — Komponen inti + lima state wajib

Setiap komponen/pola data wajib mendefinisikan state ini sebelum dianggap selesai:

```
        ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐
        │ Loading │   │  Empty  │   │  Error  │   │ Success │   │ Offline │
        │skeleton │   │ ilustrasi│  │ pesan + │   │ konten  │   │ banner +│
        │ <1 dtk  │   │ + CTA   │   │ tombol  │   │         │   │ cache   │
        └─────────┘   └─────────┘   │ retry   │   └─────────┘   └─────────┘
                                    └─────────┘
```

```kotlin
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Empty(val reason: UiText) : UiState<Nothing>
    data class Error(val message: UiText, val retry: (() -> Unit)?) : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
}
```

Daftar komponen minimal (urutan build): `ForgeButton` (primary/secondary/text), `ForgeTextField`, `ForgeCard`, `ForgeChip`, `ForgeDialog/BottomSheet`, `ForgeTopBar`, `ForgeNavBar`, `EmptyState`, `ErrorState`, `ProgressIndicator`. Tiap komponen punya `@Preview` (light/dark/RTL/fontScale 2) dan satu UI test.

### E5 — Migrasi 7 layar (urutan risiko rendah → tinggi)

| Urut | Layar (file) | Risiko | Fokus utama |
|---|---|---|---|
| 1 | Settings (`SettingsScreenModern.kt`, 850 baris) | Rendah | Selector bahasa, tema; paling banyak string |
| 2 | Console (`TerminalScreen.kt`, 199) | Rendah | Monospace, RTL tidak dibalik untuk kode |
| 3 | Launch (`AndroidAndPreviewScreen.kt`, 519) | Sedang | Progress, status build, error |
| 4 | Code (`FilesAndDiffScreen.kt`, 535) | Sedang | Diff warna + ikon (bukan warna saja) |
| 5 | Studio (`AgentScreen.kt`, 726) | Tinggi | Chat, markdown, composer, streaming |
| 6 | Shell (`NextCraftForgeApp.kt`, 763) | Tinggi | Navigasi global, dilakukan setelah komponen stabil |
| 7 | Onboarding/Setup root | Sedang | Aha-moment ≤ 60 detik |

Per layar, **definisi selesai (DoD)**:

```
[ ] 0 literal teks, 0 `.dp` mentah
[ ] 5 state UI ada & punya preview
[ ] contentDescription/role terisi, TalkBack lulus manual
[ ] Screenshot golden: light/dark × en/ar × fontScale 1.0/2.0
[ ] Tidak ada regresi startup/jank (lihat §3)
[ ] 1 UI test jalur utama
```

### E6 — QA gate

| Uji | Alat | Ambang lulus |
|---|---|---|
| Unit + UI | JUnit5, Compose test | 100% hijau |
| Aksesibilitas otomatis | ATF / `enableAccessibilityChecks` | 0 isu kritis |
| Aksesibilitas manual | TalkBack, Switch Access, Font 200%, Display 200% | Semua jalur utama selesai |
| Pseudo-locale | `en-XA`, `ar-XB` | Tidak ada teks terpotong/tumpang tindih |
| Performa | Macrobenchmark + Baseline Profile | Cold start < 2 dtk (device menengah), jank < 5% frame |
| Perangkat | Android 8 (minSdk) s.d. terbaru, layar 360dp s.d. tablet, low-RAM | Tidak crash, layout adaptif |
| Usability | 5 user per pasar utama | Task success ≥ 85%, SUS ≥ 75 |

### E7 — Rilis lokal (tanpa Play Console)

```
 Debug APK ─▶ Internal (tim) ─▶ Beta channel (manifest "beta") ─▶ Stable (manifest "stable")
                                      │                                  │
                          ≥ 7 hari, 0 crash lokal, checklist QA     tanda tangan release dari env
```

| Aspek | Aturan |
|---|---|
| Saluran update | `AppUpdater` membaca manifest HTTPS: pisahkan field `beta` dan `stable`; stable hanya dipromosikan setelah gate lolos |
| Integritas | Sertakan SHA-256 APK di manifest, verifikasi sebelum instal; keystore hanya dari environment (sudah dilakukan di `build.gradle.kts`) |
| Rollback | Simpan APK stabil sebelumnya; manifest bisa menunjuk balik versi lama. Skema data (DataStore/Room) wajib migrasi **maju dan kompatibel mundur** satu versi |
| Kegagalan | Crash saat start 2x berturut-turut → mode aman (reset preferensi UI, bukan data pengguna) |

### E8 — Iterasi tanpa analitik server

| Kebutuhan | Cara lokal |
|---|---|
| Tahu fitur dipakai/tidak | Hitung lokal (counter di DataStore), tampil di layar "Diagnostik" opsional; ekspor manual oleh pengguna bila mau kirim feedback |
| Crash | Handler lokal menulis log ke file; tombol "Bagikan laporan" (tanpa kirim otomatis) |
| Validasi UX | Uji 5 pengguna per putaran (tatap muka/remote), bukan A/B statistik |
| Preferensi | Satu sumber kebenaran di `AppPreferences` |

---

## 3. Anggaran performa (Android)

| Metrik | Target |
|---|---|
| Cold start (TTID) | < 2 dtk |
| Frame jank | < 5% (p95 < 16 ms/frame di 60 Hz) |
| Recomposition | Layar statis: 0 recomposition tak perlu (cek Layout Inspector) |
| APK/AAB | Pantau, R8 + resource shrinking aktif |
| Memori layar chat panjang | `LazyColumn` dengan `key`, tanpa render ulang seluruh riwayat |

Praktik: `@Stable/@Immutable` model UI, `remember`/`derivedStateOf`, `key` di list, Baseline Profile, hindari efek `blur` berat di device low-end (sediakan fallback tanpa blur untuk gaya kaca di dokumen ZKM).

---

### UX koneksi AI provider (hybrid: lokal + jaringan)

```
 Composer ─▶ validasi lokal (key ada? model dipilih?) ─▶ request ─▶ stream token ─▶ render bertahap
    │                                                        │
    │                                       ┌────────────────┼─────────────────┐
    ▼                                       ▼                ▼                 ▼
 Draft tersimpan lokal                 401/403 key        429 limit        timeout/5xx/offline
 (tidak hilang bila gagal)             → "Perbarui key"   → hitung mundur  → "Coba lagi" + draft utuh
```

| Area | Aturan UX |
|---|---|
| Status koneksi | Chip per provider: Terhubung / Key salah / Limit / Offline. Cek ringan saat dibuka, bukan tiap pesan. |
| Streaming | Token tampil bertahap, tombol **Stop** selalu terlihat, auto-scroll berhenti bila pengguna menggulir ke atas, markdown dirender tanpa kedip. |
| Error taksonomi | Setiap kode punya pesan manusiawi + aksi: 401 → buka Settings key; 429 → tampilkan waktu tunggu (`Retry-After`); 5xx/timeout → coba lagi; offline → antre/draft. Pesan dari `ProviderApiClient` dipetakan ke `UiText` resource (lokalisasi), bukan teks mentah server. |
| Tidak kehilangan kerja | Prompt dan percakapan disimpan lokal sebelum dikirim. Gagal kirim tidak mengosongkan composer. |
| Transparansi data | Layar jelas: "Isi percakapan dikirim ke {provider}". Tampilkan provider & model aktif di composer. Opsi mode "hanya lokal" bila tersedia. |
| Biaya & limit | Tampilkan estimasi token/konteks bila provider mengembalikan usage; peringatan sebelum konteks penuh. |
| Keamanan key | `ApiKeyVault` (terenkripsi), tampil ter-mask, tombol uji koneksi, tidak pernah masuk log/laporan crash/screenshot (`FLAG_SECURE` pada layar key). |
| Jaringan | Timeout bertahap, retry eksponensial hanya untuk request idempoten, batalkan saat layar ditinggalkan (coroutine scope terikat ViewModel), `network_security_config` ketat (HTTPS saja kecuali localhost gateway). |
| Konten AI & i18n | Jawaban AI mengikuti bahasa pengguna (instruksi sistem membawa locale); UI tetap dari resource. Blok kode tetap LTR di RTL. |
| Aksesibilitas | Streaming diumumkan sebagai `liveRegion = Polite` secara throttled (jangan per token), hasil akhir diumumkan sekali. |

Uji wajib: provider palsu (MockWebServer) untuk skenario 401, 429, 5xx, stream terputus di tengah, respons lambat, dan offline, masing-masing dengan UI test + screenshot.

### Prinsip khusus app lokal

| Prinsip | Terapan |
|---|---|
| Offline-first | Fitur non-AI (Code, Console, Settings, riwayat) jalan tanpa jaringan; state **Offline** hanya untuk fitur AI dan update |
| Cepat di perangkat terbatas | Uji di RAM 3–4 GB, storage penuh, Android minSdk; I/O berat (chroot, build) di `Dispatchers.IO` + indikator progres dan tombol batal |
| Daya & panas | Proses panjang (runtime/build) lewat foreground service dengan notifikasi, tidak menahan layar/wakelock tanpa batas |
| Privasi by default | Tidak ada telemetri; satu-satunya data keluar adalah prompt ke provider pilihan pengguna; API key hanya di `ApiKeyVault`, tampilan di-mask, tidak masuk log/laporan crash |
| Data pengguna | Backup/ekspor lokal, konfirmasi sebelum hapus workspace, checkpoint (`WorkspaceCheckpoints` sudah ada) dijadikan pola "Undo" yang terlihat di UI |

---

## 4. Definition of Done global (rilis internasional)

```
[ ] E1: 0 hardcoded string; 7 locale lengkap (fallback en)
[ ] E2: TalkBack end-to-end lulus; kontras AA di semua preset tema
[ ] E3: 0 literal warna/dp di layar (hanya di file token)
[ ] E4: 5 state di setiap layar berdata
[ ] E6: gate QA hijau; pseudo-locale bersih
[ ] Privasi: consent & penyimpanan kunci API aman (ApiKeyVault sudah ada, audit tampilan "masked")
[ ] Distribusi: README/halaman rilis + screenshot per locale, manifest update teruji (beta → stable, rollback)
```

## 5. Risiko & mitigasi

| Risiko | Mitigasi |
|---|---|
| Migrasi string besar, banyak konflik merge | 1 PR per layar, freeze literal baru via lint sejak E0 |
| TMS eksternal berlebihan untuk app lokal | Cukup `strings.xml` di Git + skrip cek kelengkapan key per locale; TMS opsional |
| Terjemahan mesin keliru istilah teknis | Glosarium istilah (chroot, rootfs, build) + review penutur asli untuk ar/hi/zh |
| Upgrade Compose BOM memecah API | Naik BOM di PR terpisah **sebelum** E3, dengan screenshot baseline sebagai pembanding |
| Efek kaca/blur berat | Tema "Performance" tanpa blur, deteksi RAM rendah |
| Kode/terminal ikut terbalik di RTL | Paksa `LayoutDirection.Ltr` hanya pada blok kode/terminal |

## 6. Langkah pertama (hari 1–3)

1. E0: tambah test harness + lint rule + screenshot baseline.
2. E1: ekstrak 87 literal ke `strings.xml` (mulai dari `SettingsScreenModern.kt`), isi `en` + `id` dulu.
3. Pasang `locales_config.xml`.
4. Buka PR #1 (E0) lalu PR #2 (E1 Settings).

---

## Status & keputusan (ringkasan untuk melanjutkan sesi)

Branch: `ccr-a85f0c92-q5dlfw`. Build: `ANDROID_HOME=/root/android-sdk gradle :app:testDebugUnitTest` (15 tes hijau). Tidak ada PR.

**Keputusan tetap**
- Satu bahasa bentuk untuk 4 tema (Linear/System/White/Glass); tema hanya mengubah warna & permukaan.
- Linear dark = abu netral `#0B0B0B/#131313`, aksen indigo `#5E6AD2`. White = koral `#D70F47` (AA). Glass = indigo `#7C8CFF`, transparan.
- Nav (Forge Navigation Study, menggantikan kapsul 4 tab): **5 tab berlabel** Projects, Terminal, Agent, Tools, Settings. Satu pil fisik meluncur (spring ≈420 ms, overshoot kecil) + glow radial di Dark/Glass. Material per tema: Dark/White bar penuh lebar, Glass kapsul mengambang. Ikon Canvas kustom (`NavIcons.kt`) morph saat terpilih; kursor terminal berkedip & satelit agen mengorbit saat ada proses. Reduce-motion → semua snap. Bar hilang saat keyboard.
- Back: Files/Android/Preview → Tools; Terminal/Agent/Tools/Settings → Projects; Projects keluar (`forgeBackTarget`).
- Komponen bersama di `ForgeComponents.kt`: LargeTitle, SectionLabel, ToggleRow, StatusIcon, ActionPill, PillIcon, PillTabs, PrimaryButton.
- Teks UI wajib lewat `strings.xml` (en + id); tes `NoHardcodedTextTest` (batas 13) dan `AccessibilityGuardTest` (role wajib; ikon dekoratif ≤ 29).

**Selesai**: tema, nav (study 5 tab + pil meluncur + glyph kustom + glow), Projects (hero + Quick/New + kartu import ZIP/Git/GitHub, Quick = QUICK_PROJECT), Tools, Launch, Agent (header + tab chat pil + kartu persetujuan + banner OAuth + composer; top bar lama dihapus), Files (header, pohon, perubahan, editor, dialog New file), Terminal, Preview, Settings (seksi + kulit kapsul/bulat), onboarding runtime, dialog New Project, toggle, i18n (layar + ViewModel via UiText + label enum), a11y pass. **Semua layar sudah di gaya baru di kode.**

**Belum**: locale es/ar/hi/ru/zh; blur Glass + fallback; isi form Settings masih form (bukan baris datar) meski kulitnya baru; durasi per tahap di Launch; **uji di perangkat** (bentuk glyph nav & glow, keyboard vs bar, Back, TalkBack, font 200%, reduce-motion, ZIP picker, transparansi Glass).

- Design study (semua layar, 4 tema, interaktif): `docs/design-study/` + artifact https://claude.ai/artifact/Mgq8C7tfQtXSS17fnjhy9Y
- Settings ditata ulang mengikuti referensi: General (Appearance tiles, Language radio, Safety), tab agen + Access mode / Endpoint / Model & access, Google account card, Runtime updates, Developer tools (Add/Remove), Linux runtime, Danger zone, About.
- Dalam-proyek mengikuti referensi: header proyek (kembali, nama, agen · chat, run, riwayat), dialog Project chats, baris tool-call terlipat + "Worked for", pill Latest; Terminal header proyek + baris tombol ↑↓ Ctrl+C Clear; Preview bilah URL berstatus + empty state; Android menu Force rebuild / Copy APK path.
- Arsitektur informasi: Projects → daftar proyek → daftar chat berjudul (ProjectChatsScreen) → percakapan (AgentScreen). Tab Agent = AgentConfigScreen (tab agen, akun, mode akses, model). Settings tanpa seksi AI. Back: percakapan → chats → daftar.
- Nav mengikuti referensi: bar global 5 tab (Projects, Terminal, Agent, Tools, Settings); di dalam proyek bar berganti Chat / Files / Terminal / Preview (ProjectPane). Glyph baru: file, preview.
- Files dalam proyek: header kartu (+ file/folder/unggah, pilih → Unduh ZIP, refresh), pohon buka-tutup, kebab per baris (new file/folder/upload here, rename, delete). Chat: tool-call per jenis (Think aksen, Write kuning, Bash/Read redup), lampiran foto/file semua ekstensi via attachments/. Inset: imePadding/statusBars/navigationBars (edge-to-edge targetSdk 35).
- Motion batch 1 (ForgeMotion.kt): push/pop slide+fade antar kedalaman proyek, cross-fade tab/pane, pressed scale pada tombol/pill, tool-call expand + chevron putar, tombol kirim spring + warna animasi, caret streaming berkedip, dialog pop-in, animateItemPlacement. Semua snap saat reduce-motion.
- Batch 2: ForgeType (24/17/16/15/13/12, label seksi uppercase tracking 0.6) dan ForgeSpace (20/14/16/56/24/8) dipakai komponen bersama; ukuran font ganjil disapu; tepi kartu Linear 1px putih 6%.
- Batch 3: ForgeIcons (21 glyph stroke 1.8dp) dipakai baris Settings/Agent config, menu Files, pohon file, jenis tool-call, riwayat dan lampir di chat; divider pohon dihapus (hanya dalam kartu); placeholder 60%; haptic tick tab dan kirim.

- Batch A (reset): shared ForgeProjectHeader / ForgeListRow / ForgeEmptyState (ForgeShared.kt); project header on Chats, Files, Terminal, Preview and tool screens; Tools launcher single-accent stroke tiles; uniform empty states (Projects, Chats, Preview); diff review buttons 48dp.

- UX audit fixes: removed doubled status-bar inset, compact user bubbles (long-press copy), Agent endpoint/credentials subtitles, localised model/auth/project strings, borderless GitHub dialog, auto-approve warning, aligned settings toggles, Android-only run button, active chat marked by weight, Editor duplicate removed from Tools, nav pill fits Pengaturan.

- Phone sizes 6.1-6.9in: real Compose renders at 360/393/412/440dp (opt-in harness: gradle :app:testDebugUnitTest -PforgeScreenshots -PforgeRoboDir=<dir with android-all-instrumented-14-robolectric-10818077-i6.jar>, output app/build/screenshots). Fixed: two-line list subtitles, project card meta line + compact tile under 400dp, chat copy button no longer overlaps text, 10.5sp nav labels when 5 tabs share <76dp slots.

- Batch B (chat): grouped tool steps (3+ fold into one row, live step shown), inline diff card with keep/revert under the last finished reply, approval as non-dismissable bottom sheet with risk label and affected files, contextual quick-action chips plus starter ideas, "N new" pill. Components in ChatExtras.kt; renders in the phone-size harness (chatB, approval).

- Round to 8.5: theme-level dialog/sheet/menu surfaces and one shape scale, fading edges on scrolling tabs, project card status pills (agent working, N files changed), landscape compact bar/header and no two-pane on landscape phones, diff actions wrap. Verified in renders: dark/white/glass at 393dp, landscape 932x430.

- Visual fixes round: onboarding actions pinned (install button no longer below the fold on 360dp), English default runtime message mapped through runtimeText, composer short hint (TalkBack keeps the agent name), fading edges on quick chips and terminal keys, workspace error banners localised. Harness gained composer, terminal and onboarding scenarios.

- Language round: in-app language now reaches the ViewModel (str()/quantity() wrap the application context) and foreground-service notifications (LocaleHelper); notification texts, auth errors, provider/build/diff/installer sentences are translated at display through MessageText (central UiText.Raw path, messageText() in Compose). MessageTextTest keeps table, English and Indonesian resources in sync and scans sources for unmapped sentences. Opt-in Robolectric tests start the real service and check Indonesian and in-app-English output.

- Files search: header search toggle, capsule field with result count, matches in tree order with the folders that lead to them (dimmed), empty state. Pure filter tested in FileSearchTest; renders in the harness (filesSearch, filesSearchEmpty).
