---
trigger: always_on
description: Universal Claude-Grade Execution Protocol (CORE-EXEC-001..005). Immutable append/upgrade-only operational notes for all AI agents in NextCraft Forge.
---

# Universal Claude-Grade Execution Protocol (Immutable — Upgrade-Only)

> **Governance Policy**: Catatan protokol ini (`CORE-EXEC-001` s/d `CORE-EXEC-005`) wajib dijalankan oleh **seluruh agen AI** (Claude, Antigravity/Gemini, DeepSeek/DSH, OpenRouter, dan Subagents) di NextCraft Forge. Catatan ini **dilarang dihapus atau dikurangi** (dijaga secara hardware-level oleh `security_gate.py`), namun **boleh di-upgrade** agar semakin efisien.

1. **`[CORE-EXEC-001]` Single-Step Command Chaining (`<10s` Turn Target)**:
   - Gabungkan operasi baca, cari (`grep`/`rg`), dan cek status (`git status`/`git diff`) dalam satu perintah shell berantai (`&&` / `;`) agar rata-rata hanya ~3 pemanggilan tool per giliran.
2. **`[CORE-EXEC-002]` Deterministic Batch Scripting & Stream Filtering**:
   - Gunakan `python3 -I - <<'EOF'` untuk manipulasi kode struktural/multi-file secara deterministik (jaga ukuran file kode `<= 1000` baris) dan selalu batasi output shell dengan `head`/`tail`/`cut -c1-200` agar jendela konteks hemat token.
3. **`[CORE-EXEC-003]` Non-Blocking Daemon & PTY Freeze Guard**:
   - Gunakan `keep-running <logfile> <cmd>` atau `timeout --foreground -k 10s ... < /dev/null` beserta `--offline --configuration-cache` untuk tugas berat (`gradle testDebugUnitTest` / `assembleDebug`) agar proses background tidak terkena `SIGTTIN` freeze atau `SIGKILL` (`Exit 137`).
4. **`[CORE-EXEC-004]` Prompt Cache & Adaptive Low-Effort Thinking**:
   - Pertahankan efisiensi *prompt cache read* (`>98%`) dengan berpikir singkat, langsung mengeksekusi tool nyata, dan tidak mencetak ulang file besar yang belum berubah.
5. **`[CORE-EXEC-005]` Self-Healing & Kejujuran Faktual (`100%` Verified)**:
   - Saat terjadi error atau reset *working tree*, langsung periksa kondisi aktual (`git status`/`git diff`), terapkan perbaikan bedah (*surgical fix*), jalankan verifikasi hingga `exit code 0`, dan laporkan secara jujur mana yang sudah di-commit dan mana yang belum.
