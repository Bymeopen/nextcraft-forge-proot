"""Laporan Markdown dari hasil analisis (identifikasi, ELF, string, disassembly)."""

from __future__ import annotations

import datetime as _dt
from typing import Any

MAX_FUNCTIONS = 50
MAX_IMPORTS = 50
MAX_STRINGS = 50


def _cell(value: Any) -> str:
    """Isi sel tabel Markdown: satu baris, tanpa pipa yang merusak tabel."""
    return str(value).replace("|", "\\|").replace("\n", " ").replace("\r", " ")


def _code(value: Any) -> str:
    text = str(value).replace("`", "'").replace("\n", " ")
    return f"`{text}`"


def build_report(data: dict[str, Any], generated_at: _dt.datetime | None = None, notes: str = "") -> str:
    """Membangun laporan; ``data`` memuat ``identify`` dan opsional ``elf``, ``strings``, ``disasm``, ``notes_missing``."""
    info = data["identify"]
    when = (generated_at or _dt.datetime.now(_dt.timezone.utc)).strftime("%Y-%m-%d %H:%M UTC")
    lines: list[str] = [f"# Laporan REA: {info['path']}", "", f"_Dibuat {when}. Hanya untuk penggunaan yang sah._", ""]

    lines += ["## Ringkasan", "", "| Properti | Nilai |", "|---|---|"]
    rows = [("Tipe", info["type"]), ("Ukuran", f"{info['size']} bayt")]
    for key, label in (("arch", "Arsitektur"), ("bits", "Bit"), ("endian", "Endian"), ("elf_type", "Jenis ELF")):
        if key in info:
            rows.append((label, info[key]))
    rows += [("MD5", info["md5"]), ("SHA256", info["sha256"]), ("Entropi", info["entropy"])]
    lines += [f"| {label} | {_cell(value)} |" for label, value in rows]
    if info.get("packed_hint"):
        lines += ["", "> ⚠️ Entropi tinggi: berkas mungkin dipak, dikompresi, atau dienkripsi."]
    lines.append("")

    elf = data.get("elf")
    if elf:
        lines += ["## ELF", ""]
        lines.append(f"- Tipe: {elf['type']}, mesin: {elf['machine']}, {elf['class']}-bit, {elf['endian']}-endian")
        lines.append(f"- Titik masuk: `0x{elf['entry']:x}`")
        lines.append(f"- Simbol: {'tidak ada (di-strip)' if elf['stripped'] else 'ada'}")
        if elf.get("interpreter"):
            lines.append(f"- Interpreter: {_code(elf['interpreter'])}")
        if elf["needed"]:
            lines.append("- Pustaka: " + ", ".join(_code(lib) for lib in elf["needed"]))
        lines.append(f"- {len(elf['sections'])} section, {len(elf['segments'])} segment, {elf['relocations']} relokasi")
        lines.append("")
        executable = [s for s in elf["sections"] if "X" in s["flags"]]
        if executable:
            lines += ["### Section yang dapat dieksekusi", "", "| Nama | Alamat | Ukuran |", "|---|---|---|"]
            lines += [f"| {_cell(s['name'])} | `0x{s['addr']:x}` | {s['size']} |" for s in executable]
            lines.append("")
        if elf["functions"]:
            lines += [f"### Fungsi ({len(elf['functions'])})", "", "| Nama | Alamat | Ukuran |", "|---|---|---|"]
            lines += [f"| {_cell(f['name'])} | `0x{f['addr']:x}` | {f['size']} |" for f in elf["functions"][:MAX_FUNCTIONS]]
            if len(elf["functions"]) > MAX_FUNCTIONS:
                lines.append(f"\n_{len(elf['functions']) - MAX_FUNCTIONS} fungsi lain tidak ditampilkan._")
            lines.append("")
        if elf["imports"]:
            lines += [f"### Impor ({len(elf['imports'])})", ""]
            lines += [f"- {_code(name)}" for name in elf["imports"][:MAX_IMPORTS]]
            lines.append("")

    found = data.get("strings")
    if found is not None:
        interesting = sorted((s for s in found["strings"] if s["score"] >= 3), key=lambda s: -s["score"])[:MAX_STRINGS]
        lines += [f"## String menarik ({len(interesting)} dari {found['total']})", ""]
        if interesting:
            lines += ["| Offset | Skor | String |", "|---|---|---|"]
            lines += [f"| `0x{s['offset']:x}` | {s['score']} | {_code(s['text'][:120])} |" for s in interesting]
        else:
            lines.append("_Tidak ada string dengan skor menarik._")
        lines.append("")

    entropy = data.get("entropy")
    if entropy:
        mp = entropy["map"]
        lines += ["## Entropi", "", f"`{mp['spark']}`  (blok {mp['block_size']} bayt; {mp['high_blocks']} blok berentropi tinggi)", ""]
        if entropy["sections"]:
            lines += ["| Section | Ukuran | Entropi |", "|---|---|---|"]
            lines += [f"| {_cell(r['name'])} | {r['size']} | {r['entropy']:.2f}{' ⚠️' if r['high'] else ''} |" for r in entropy["sections"]]
            lines.append("")

    graph = data.get("cfg")
    if graph:
        lines += [f"## CFG: {graph['function']}", "", f"{len(graph['blocks'])} blok, {graph['back_edges']} back-edge (loop).", "", "```", graph["ascii"], "```", ""]

    xrefs = data.get("xrefs")
    if xrefs:
        lines += [f"## Call graph ({xrefs['functions_scanned']} fungsi)", ""]
        if xrefs["call_graph"]:
            lines += [f"- {_code(caller)} → " + ", ".join(_code(c) for c in callees) for caller, callees in list(xrefs["call_graph"].items())[:MAX_FUNCTIONS]]
        else:
            lines.append("_Tidak ada pemanggilan langsung._")
        lines.append("")
        if xrefs["strings"]:
            lines += ["### String dan fungsi yang memakainya", "", "| String | Fungsi |", "|---|---|"]
            for row in xrefs["strings"][:MAX_STRINGS]:
                users = ", ".join(sorted({r["function"] for r in row["refs"]}))
                lines.append(f"| {_code(row['text'][:80])} | {_cell(users)} |")
            lines.append("")

    code = data.get("disasm")
    if code:
        lines += [f"## Disassembly: {code['function'] or '?'} @ `0x{code['address']:x}`", "", "```asm"]
        lines += [f"{i['addr']:x}:  {i['bytes']:<20} {i['mnemonic']} {i['operands']}".rstrip() for i in code["instructions"]]
        lines += ["```", ""]
    for note in data.get("missing", []):
        lines += [f"> Catatan: {note}", ""]

    lines += ["## Catatan analis", "", notes.strip() or "_(kosong)_", ""]
    return "\n".join(lines)
