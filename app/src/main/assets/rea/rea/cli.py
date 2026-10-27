"""Antarmuka perintah ``rea``. Pesan manusia ke stderr; dengan ``--json`` satu objek JSON di baris terakhir stdout."""

from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import sys
from typing import Sequence

from . import __version__
from .analysis import cfg as cfg_mod
from .analysis import disasm as disasm_mod
from .analysis import entropy as entropy_mod
from .analysis import strings as strings_mod
from .analysis import xrefs as xrefs_mod
from .analysis.code import decode, defined_functions
from .capability import FAIL, MODE_EMULATED, MODE_FULL, OK, WARN, quick_mode, run_doctor
from .dynamic import frida_runner as frida_mod
from .dynamic import gdb as gdb_mod
from .dynamic import qemu as qemu_mod
from .dynamic import strace as strace_mod
from .emulation import unicorn_runner as emu_mod
from .identify import identify
from .integrations import decompile as decompile_mod
from .learn import engine as learn_mod
from .plugins import loader as plugin_loader
from .parsers import apk as apk_mod
from .parsers import elf as elf_mod
from .parsers import firmware as firmware_mod
from .report.markdown import build_report
from .result import E_BAD_FORMAT, E_INTERNAL, E_MISSING_DEP, E_NOT_SUPPORTED, E_USAGE, ReaError, Result, emit_json, failure

_NOTICE = (
    "REA hanya untuk penggunaan yang sah: software milik sendiri, CTF, crackme, interoperabilitas, riset keamanan "
    "berizin, dan analisis malware defensif. Jangan memakainya untuk melanggar lisensi atau menyerang sistem tanpa izin."
)
_SYMBOL = {OK: "✅", WARN: "⚠️ ", FAIL: "❌"}


def _consent_path() -> str:
    return os.path.join(os.path.expanduser("~"), ".rea", "consent.json")


def _show_notice_once() -> None:
    """Pemberitahuan penggunaan sah di pemakaian pertama; dicatat agar tidak diulang."""
    path = _consent_path()
    if os.path.exists(path):
        return
    print(_NOTICE, file=sys.stderr)
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            json.dump({"notice_shown": True, "version": __version__}, handle)
    except OSError:
        pass  # Tidak bisa menulis HOME: pemberitahuan akan tampil lagi, itu tidak berbahaya.


def _doctor(args: argparse.Namespace) -> Result:
    mode, checks = run_doctor(os.getcwd())
    result = Result(command="doctor", mode=mode, data={"checks": [c.as_dict() for c in checks], "version": __version__})
    if not args.json:
        print(f"REA {__version__}  mode: {mode}", file=sys.stderr)
        for check in checks:
            line = f"{_SYMBOL[check.status]} {check.label}: {check.detail}"
            print(line + (f"  → {check.hint}" if check.hint and check.status != OK else ""), file=sys.stderr)
    result.warnings = [f"{c.label}: {c.hint}" for c in checks if c.status == FAIL and c.hint][:5]
    return result


_PLUGIN_STATE: dict = {}


def _plugins() -> list:
    """Plugin terpasang (dimuat sekali per proses). Galat muat tidak fatal: diteruskan sebagai peringatan lewat ``_plugin_errors``."""
    if "loaded" not in _PLUGIN_STATE:
        loaded, errors = plugin_loader.load_plugins()
        _PLUGIN_STATE["loaded"], _PLUGIN_STATE["errors"] = loaded, errors
    return _PLUGIN_STATE["loaded"]


def _run_plugins(result: Result, hook: str, fmt: str, *args) -> dict:
    """Menjalankan satu hook di semua plugin yang cocok; hasilnya dikembalikan, peringatan masuk ke ``result``."""
    plugins = _plugins()
    for item in _PLUGIN_STATE.pop("errors", []):
        result.warnings.append(f"Plugin {os.path.basename(item['source'])} gagal dimuat: {item['error']}")
    values, warnings = plugin_loader.run_hook(plugins, hook, fmt, *args)
    result.warnings += warnings
    return values


def _merge_plugin_results(data: dict, hook: str, values: dict) -> None:
    for name, value in values.items():
        data.setdefault("plugins", {}).setdefault(name, {})[hook] = value


def _id(args: argparse.Namespace) -> Result:
    info = identify(args.file)
    result = Result(command="id", data=info)
    values = _run_plugins(result, "on_identify", info["type"], info)
    _merge_plugin_results(info, "on_identify", values)
    if not args.json:
        for key in ("path", "type", "arch", "bits", "endian", "size", "md5", "sha256", "entropy"):
            if key in info:
                print(f"{key:8} {info[key]}", file=sys.stderr)
        if info.get("packed_hint"):
            print("⚠️  Entropi tinggi: berkas mungkin dipak atau dienkripsi.", file=sys.stderr)
        for name in info.get("plugins", {}):
            print(f"plugin   {name}: {json.dumps(info['plugins'][name], ensure_ascii=False)[:200]}", file=sys.stderr)
    return result


def _parse_address(text: str) -> int:
    try:
        return int(text, 0)
    except ValueError as error:
        raise ReaError(E_USAGE, f"Alamat tidak valid: {text}", "Contoh: --addr 0x401136") from error


def _apk(args: argparse.Namespace) -> Result:
    info = identify(args.file)
    if info["type"] != "APK":
        raise ReaError(E_BAD_FORMAT, f"Bukan APK (terdeteksi: {info['type']}).", "Gunakan `rea id` untuk memeriksa tipe berkas.")
    data = apk_mod.summarize_apk(args.file)
    data["identify"] = info
    if not args.json:
        manifest = data["manifest"]
        print(f"{manifest['package']}  v{manifest['version_name']} ({manifest['version_code']})  sdk {manifest['min_sdk']}→{manifest['target_sdk']}", file=sys.stderr)
        print(f"  activity utama: {manifest['main_activity']}", file=sys.stderr)
        print(f"  {len(manifest['permissions'])} izin, DEX: {', '.join(d['name'] for d in data['dex'])}", file=sys.stderr)
        for finding in data["findings"]:
            print(f"  [{finding['level']}] {finding['message']}", file=sys.stderr)
    return Result(command="apk", data=data)


def _dex(args: argparse.Namespace) -> Result:
    data = apk_mod.list_dex_classes(args.file, name_filter=args.filter or "", limit=args.limit)
    if not args.json:
        print(f"{data['class_count']} class, {data['methods']} method ({data['matched']} cocok):", file=sys.stderr)
        for item in data["classes"]:
            print(f"  {item['name']}  [{' '.join(item['access'])}]  {item['methods']} method", file=sys.stderr)
    return Result(command="dex", data=data)


def _decompile(args: argparse.Namespace) -> Result:
    data = decompile_mod.decompile(args.file, tool=args.tool, out_dir=args.output)
    if not args.json:
        print(f"{data['tool']}: {data['files']} berkas di {data['output_dir']} ({data['seconds']} detik)", file=sys.stderr)
    return Result(command="decompile", data=data)


def _emu(args: argparse.Namespace) -> Result:
    identify(args.file)  # galat berkas yang konsisten dengan perintah lain
    data = emu_mod.emulate(
        args.file,
        arch=args.arch,
        func=args.func,
        start=emu_mod.parse_int(args.start) if args.start else None,
        end=emu_mod.parse_int(args.end) if args.end else None,
        base=emu_mod.parse_int(args.base) if args.base else emu_mod.RAW_BASE,
        regs=[emu_mod.parse_reg(r) for r in args.reg or []],
        mems=[emu_mod.parse_mem(m) for m in args.mem or []],
        dumps=[emu_mod.parse_dump(d) for d in args.dump or []],
        max_insns=args.max_insns,
        timeout_ms=args.timeout_ms,
    )
    result = Result(command="emu", data=data)
    if data["stop"] not in ("returned", "reached_end"):
        result.warnings.append(f"Emulasi berhenti: {data['stop']}" + (f" ({data['detail']})" if data["detail"] else ""))
    if not args.json:
        print(f"{data['arch']}  mulai 0x{data['start']:x}  {data['instructions']} instruksi  berhenti: {data['stop']}", file=sys.stderr)
        for name, value in data["registers"].items():
            if value:
                print(f"  {name:4} 0x{value:x}", file=sys.stderr)
        for dump in data["dumps"]:
            shown = dump["ascii"] if dump["ascii"] is not None else f"({dump['error']})"
            print(f"  [0x{dump['addr']:x}+{dump['size']}] {shown}", file=sys.stderr)
    return result


def _learn(args: argparse.Namespace) -> Result:
    action = args.learn_action
    if action == "list":
        data = learn_mod.list_lessons()
        if not args.json:
            stage = 0
            for row in data["lessons"]:
                if row["stage"] != stage:
                    stage = row["stage"]
                    print(f"\nTahap {stage}: {row['stage_name']}", file=sys.stderr)
                mark = "✅" if row["exercise_done"] else "  "
                quiz = f"  kuis {row['quiz']['best']}/{row['quiz']['total']}" if row["quiz"] else ""
                print(f" {mark} {row['n']:>2}. {row['title']}{quiz}", file=sys.stderr)
            print(f"\n{data['exercises_done']} dari {data['total']} latihan selesai. Mulai: rea learn show 1", file=sys.stderr)
    elif action == "show":
        data = learn_mod.show(args.number)
        if not args.json:
            print(f"Pelajaran {data['n']}: {data['title']}  (tahap {data['stage']}: {data['stage_name']})\n", file=sys.stderr)
            print(data["theory"] + "\n\n" + data["diagram"] + "\n", file=sys.stderr)
            print("Latihan: " + data["task"], file=sys.stderr)
            if data["has_exercise"]:
                print(f"Siapkan: rea learn start {data['n']}", file=sys.stderr)
            print(f"Jawab:   rea learn check {data['n']} <jawaban>      Kuis: rea learn quiz {data['n']}", file=sys.stderr)
    elif action == "start":
        data = learn_mod.start(args.number)
        if not args.json:
            print(f"Folder latihan: {data['dir']}", file=sys.stderr)
            if data["program"]:
                print(f"Program: {data['program']}", file=sys.stderr)
            print("Tugas: " + data["task"], file=sys.stderr)
    elif action == "check":
        data = learn_mod.check(args.number, args.answer)
        if not args.json:
            print(("✅ " if data["correct"] else "❌ ") + data["message"], file=sys.stderr)
            if data["hint"]:
                print("Petunjuk: " + data["hint"], file=sys.stderr)
    elif action == "quiz":
        chosen = None
        if args.answers is not None:
            try:
                chosen = [int(part) for part in args.answers.split(",")]
            except ValueError as error:
                raise ReaError(E_USAGE, "--answers harus angka dipisah koma.", "Contoh: --answers 2,1") from error
        data = learn_mod.quiz(args.number, chosen)
        if not args.json:
            if chosen is None:
                for number, question in enumerate(data["questions"], 1):
                    print(f"{number}. {question['text']}", file=sys.stderr)
                    for index, option in enumerate(question["options"], 1):
                        print(f"   {index}) {option}", file=sys.stderr)
                print(f"Jawab: rea learn quiz {args.number} --answers " + ",".join("?" for _ in data["questions"]), file=sys.stderr)
            else:
                print(f"Skor {data['score']}/{data['total']}: {'lulus' if data['passed'] else 'ulangi pelajarannya'}", file=sys.stderr)
    elif action == "answer":
        data = learn_mod.reveal_answer(args.number)
        if not args.json:
            print("Jawaban: " + " / ".join(data["answers"]) + "\n" + data["explanation"], file=sys.stderr)
    elif action == "reset":
        data = learn_mod.reset(args.number)
        if not args.json:
            print("Progres direset.", file=sys.stderr)
    else:
        raise ReaError(E_USAGE, f"Aksi learn tidak dikenal: {action}", "rea learn --help")
    return Result(command="learn", data=data)


def _plugins_cmd(args: argparse.Namespace) -> Result:
    action = args.plugins_action
    if action == "list":
        loaded = _plugins()
        data = {
            "plugins": [p.as_dict() for p in loaded],
            "errors": _PLUGIN_STATE.get("errors", []),
            "dirs": [plugin_loader.builtin_dir(), plugin_loader.user_dir()],
            "disabled": plugin_loader.disabled(),
        }
        if not args.json:
            if data["disabled"]:
                print("Plugin dimatikan (REA_NO_PLUGINS atau --no-plugins).", file=sys.stderr)
            for plugin in data["plugins"]:
                kind = "bawaan" if plugin["builtin"] else "pengguna"
                print(f"  {plugin['name']:16} {kind:9} {','.join(plugin['formats']):8} hook: {', '.join(plugin['hooks'])}", file=sys.stderr)
            for item in data["errors"]:
                print(f"  ❌ {item['source']}: {item['error']}", file=sys.stderr)
            print(f"Plugin pengguna diletakkan di {plugin_loader.user_dir()} (rea plugins new NAMA).", file=sys.stderr)
        return Result(command="plugins", data=data)
    if action == "new":
        name = args.name
        if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,39}", name):
            raise ReaError(E_USAGE, f"Nama plugin tidak valid: {name!r}", "Huruf kecil, angka, - atau _ (1-40 karakter).")
        folder = plugin_loader.user_dir()
        os.makedirs(folder, exist_ok=True)
        path = os.path.join(folder, name.replace("-", "_") + ".py")
        if os.path.exists(path):
            raise ReaError(E_USAGE, f"Sudah ada: {path}", "Pilih nama lain atau hapus berkas itu.")
        klass = "".join(part.capitalize() for part in re.split(r"[-_]", name)) or "Plugin"
        with open(path, "x", encoding="utf-8") as handle:
            handle.write(plugin_loader.TEMPLATE.format(name=name, klass=klass))
        if not args.json:
            print(f"Kerangka plugin dibuat: {path}", file=sys.stderr)
        return Result(command="plugins", data={"path": path, "name": name})
    raise ReaError(E_USAGE, f"Aksi plugins tidak dikenal: {action}", "rea plugins --help")


def _firmware(args: argparse.Namespace) -> Result:
    info = identify(args.file)
    data = firmware_mod.scan(args.file, max_hits=args.max_hits)
    data["identify"] = {k: info[k] for k in ("path", "type", "size", "sha256", "entropy") if k in info}
    result = Result(command="firmware", data=data)
    if args.extract:
        out_dir = args.output or os.path.join(".rea", os.path.basename(args.file) + ".extracted")
        todo = [h for h in data["hits"] if h["type"] not in ("elf", "ext", "linux-image")]
        data["out_dir"] = out_dir
        data["extracted"] = firmware_mod.extract(args.file, out_dir, todo)
        for item in data["extracted"]:
            if item["status"] == "tool_missing":
                result.warnings.append(f"{item['type']} @0x{item['offset']:x}: {item['detail']}. {item['hint']}")
            elif item["status"] == "failed":
                result.warnings.append(f"{item['type']} @0x{item['offset']:x}: ekstraksi gagal ({item['detail']})")
    if not data["hits"]:
        result.warnings.append("Tidak ada signature firmware yang valid; berkas mungkin terenkripsi atau berformat khusus (coba `rea analyze --entropy`).")
    if not args.json:
        print(f"{info['path']}  {info['type']}  {data['size']} bayt", file=sys.stderr)
        if data["bootloader"]:
            boot = data["bootloader"]
            print(f"  bootloader  {boot['name']} {boot['version']} @0x{boot['offset']:x}", file=sys.stderr)
        if data["kernel"]:
            kernel = data["kernel"]
            print(f"  kernel      Linux {kernel['version']} (di {kernel['in']}, 0x{kernel['offset']:x})", file=sys.stderr)
        for hit in data["hits"]:
            extra = f"  {hit['size']} bayt" if hit.get("size") else ""
            print(f"  0x{hit['offset']:08x}  {hit['type']:13}{extra}  {json.dumps(hit['detail'], ensure_ascii=False)[:100]}", file=sys.stderr)
        for item in data.get("extracted", []):
            where = item["output"] or item["detail"]
            print(f"  ekstrak 0x{item['offset']:x} {item['type']}: {item['status']}  {where}", file=sys.stderr)
    return result


def _stop_text(stop: dict) -> str:
    kind = stop.get("kind")
    if kind == "stopped":
        return f"berhenti dengan sinyal {stop.get('signal')}"
    if kind == "exited":
        return f"program selesai, kode keluar {stop.get('code')}"
    if kind == "killed":
        return f"program dihentikan sinyal {stop.get('signal')}"
    return f"stop reply tak dikenal ({stop.get('raw', '')})"


def _print_registers(registers: dict, names: tuple = ()) -> None:
    for name, value in registers.items():
        if names and name not in names:
            continue
        print(f"  {name:6} 0x{value:x}", file=sys.stderr)


def _run_emu(args: argparse.Namespace, prog_args: list[str], result: Result) -> None:
    data = qemu_mod.run_emulated(
        args.file,
        port=args.port,
        breakpoints=args.break_at or [],
        program_args=prog_args,
        run_to_end=args.run_to_end,
        timeout=args.timeout or qemu_mod.DEFAULT_TIMEOUT,
    )
    result.data, result.mode = data, MODE_EMULATED
    if not args.json:
        pc_name = {"aarch64": "pc", "arm": "pc", "x86_64": "rip", "386": "eip"}[data["arch"]]
        pc = data["registers"].get(pc_name)
        print(f"Tersambung ke gdbstub {data['tool']} port {data['port']}  arsitektur {data['arch']}", file=sys.stderr)
        where = f"  {pc_name}=0x{pc:x}" if pc is not None and data["stop"].get("kind") == "stopped" else ""
        print(f"Status: {_stop_text(data['stop'])}{where}", file=sys.stderr)
        for item in data["breakpoints"]:
            print(f"  breakpoint {item['spec']} @0x{item['addr']:x}: {'kena' if item['hit'] else 'tidak kena'}", file=sys.stderr)
        if data["output"]:
            print("Keluaran program:\n" + data["output"], file=sys.stderr)


def _run_gdb(args: argparse.Namespace, prog_args: list[str], result: Result) -> None:
    data = gdb_mod.debug(
        args.file,
        prog_args,
        breakpoints=args.break_at or [],
        examines=[gdb_mod.parse_examine(e) for e in args.examine or []],
        timeout=args.timeout or gdb_mod.DEFAULT_TIMEOUT,
    )
    result.data, result.mode = data, MODE_FULL
    for item in data["breakpoints"]:
        if not item["set"]:
            result.warnings.append(f"Breakpoint {item['spec']!r} tidak dikenali gdb (simbol tidak ada).")
    if not args.json:
        if data["stopped"]:
            print("Berhenti di program (gdb). Register penting:", file=sys.stderr)
            _print_registers(data["registers"], ("rip", "pc", "sp", "rsp", "rax", "x0", "x1", "x30"))
            for frame in data["backtrace"][:10]:
                print(f"  #{frame['level']} {frame['function']}({frame['args']}) {frame['source'] or ''}", file=sys.stderr)
            for mem in data["memory"]:
                print(f"  [{mem['spec']}] {mem['hex'] or mem['error']}", file=sys.stderr)
        elif data["exit"] is not None:
            print(f"Program selesai tanpa berhenti (kode {data['exit']['code']}).", file=sys.stderr)
        if data["program_output"]:
            print("Keluaran program:\n" + data["program_output"], file=sys.stderr)


def _run_strace(args: argparse.Namespace, prog_args: list[str], result: Result) -> None:
    data = strace_mod.trace(args.file, prog_args, trace_filter=args.trace, timeout=args.timeout or strace_mod.DEFAULT_TIMEOUT)
    result.data, result.mode = data, MODE_FULL
    if data["timed_out"]:
        result.warnings.append("Waktu habis; jejak mungkin tidak lengkap.")
    if not args.json:
        print(f"{data['total']} syscall; keluar: {data['exit']}", file=sys.stderr)
        for item in data["summary"][:10]:
            print(f"  {item['name']:16} {item['count']}", file=sys.stderr)
        for path in data["files"][:10]:
            print(f"  berkas: {path}", file=sys.stderr)


def _run_frida(args: argparse.Namespace, prog_args: list[str], result: Result) -> None:
    data = frida_mod.hook(args.file, prog_args, args.hook or [], timeout=args.timeout or frida_mod.DEFAULT_TIMEOUT)
    result.data, result.mode = data, MODE_FULL
    for spec in data["unresolved"]:
        result.warnings.append(f"Hook {spec!r} tidak ditemukan (bukan simbol terekspor atau simbol debug).")
    if not args.json:
        print(f"Frida {data['frida_version']}: {len(data['hooked'])} hook terpasang, {len(data['calls'])} panggilan", file=sys.stderr)
        for call in data["calls"][:20]:
            print(f"  {call['fn']}({', '.join(call['args'][:3])})" + (f"  \"{call['str0']}\"" if "str0" in call else ""), file=sys.stderr)


def _run(args: argparse.Namespace) -> Result:
    identify(args.file)
    mode = args.mode
    prog_args = shlex.split(args.args or "")
    result = Result(command="run")
    if mode == "auto":
        import shutil

        best, _ = quick_mode()
        if best == MODE_FULL:
            mode = "frida" if args.hook else "gdb" if args.break_at or not shutil.which("strace") else "strace"
        elif best == MODE_EMULATED:
            mode = "emu"
            result.warnings.append("ptrace atau gdb tidak tersedia; memakai QEMU gdbstub (emu).")
        else:
            raise ReaError(E_MISSING_DEP, "Tidak ada mode dinamis yang bisa dipakai (ptrace/gdb/qemu tidak ada).", "Pasang: apt install gdb qemu-user, atau pakai `rea analyze` (statis).")
    {"emu": _run_emu, "gdb": _run_gdb, "strace": _run_strace, "frida": _run_frida}[mode](args, prog_args, result)
    return result


def _cfg_for(path: str, symbol: str | None) -> dict:
    """CFG dari fungsi ``symbol`` (default ``main``, atau fungsi utama/titik masuk bila biner tidak punya simbol)."""
    from .analysis.disasm import _pick_default_function
    from .parsers.elf import function_symbols, open_elf
    from .result import E_SYMBOL

    with open(path, "rb") as handle:
        elf = open_elf(handle)
        functions = function_symbols(elf)
        if symbol and (symbol.strip().startswith(("0x", "0X")) or symbol.strip().isdigit()):
            try:
                addr = int(symbol.strip(), 0)
                code = decode(elf, handle, address=addr, name=f"sub_{addr:x}")
                return cfg_mod.build_cfg(code)
            except ValueError:
                pass
        wanted = symbol or "main"
        match = next((f for f in functions if f["name"] == wanted), None)
        if match is None and symbol and symbol != "main":
            match = next((f for f in functions if symbol in f["name"]), None)
        if match is not None:
            code = decode(elf, handle, function=match)
        elif not symbol or symbol == "main":
            addr, fname, fsize = _pick_default_function(elf, functions)
            if fsize > 0:
                code = decode(elf, handle, function={"name": fname, "addr": addr, "size": fsize})
            else:
                code = decode(elf, handle, address=addr, name=fname)
        else:
            raise ReaError(E_SYMBOL, f"Simbol tidak ditemukan: {symbol}", "Biner mungkin di-strip; pakai `rea analyze --disasm` tanpa nama untuk fungsi utama.")
    return cfg_mod.build_cfg(code)


def _extract_primary_apk_so(apk_path: str) -> tuple[str | None, str | None]:
    """Menyalin pustaka native utama (.so) dari dalam APK ke berkas sementara agar bisa dianalisis ELF/disasm/CFG."""
    import tempfile
    import zipfile

    if not zipfile.is_zipfile(apk_path):
        return None, None
    generic_prefixes = (
        "libc++_shared",
        "libandroidx.",
        "libdatastore_",
        "libconscrypt",
        "libsqlite",
        "libssl",
        "libcrypto",
        "libapplovin",
        "libcrashlytics",
        "libfirebase",
        "libmmkv",
        "lib sentry",
    )
    apk_stem = re.sub(r"[^a-z0-9]", "", os.path.basename(apk_path).lower().split()[0])
    preferred_abis = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    with zipfile.ZipFile(apk_path) as archive:
        so_entries = [n for n in archive.namelist() if n.startswith("lib/") and n.endswith(".so") and n.count("/") >= 2]
        if not so_entries:
            return None, None

        def _score(entry: str) -> tuple[int, int, int, int, int]:
            parts = entry.split("/")
            abi = parts[1] if len(parts) > 2 else ""
            base = parts[-1].lower()
            abi_rank = preferred_abis.index(abi) if abi in preferred_abis else 99
            is_generic = 1 if any(base.startswith(p) for p in generic_prefixes) else 0
            matches_name = 0 if (apk_stem and len(apk_stem) >= 3 and apk_stem in base) else 1
            try:
                size = archive.getinfo(entry).file_size
            except KeyError:
                size = 0
            is_app_sized = 0 if (32 * 1024 <= size <= 1536 * 1024) else 1
            return (abi_rank, is_generic, matches_name, is_app_sized, -size)

        chosen = sorted(so_entries, key=_score)[0]
        raw = archive.read(chosen)
    fd, tmp_path = tempfile.mkstemp(prefix="rea_apk_", suffix="_" + os.path.basename(chosen))
    with os.fdopen(fd, "wb") as handle:
        handle.write(raw)
    return tmp_path, chosen


def _analyze(args: argparse.Namespace) -> Result:
    info = identify(args.file)
    data: dict = {"identify": info}
    result = Result(command="analyze", data=data)
    is_elf = info["type"] == "ELF"
    is_apk = info["type"] == "APK"
    elf_target = args.file if is_elf else None
    tmp_so: str | None = None
    try:
        if is_apk:
            data["apk"] = apk_mod.summarize_apk(args.file)
            tmp_so, so_entry = _extract_primary_apk_so(args.file)
            if tmp_so and so_entry:
                data["apk"]["primary_native_lib"] = so_entry
                elf_target = tmp_so
                data["elf"] = elf_mod.summarize(tmp_so)
                data["elf"]["source_entry"] = so_entry
        elif is_elf:
            data["elf"] = elf_mod.summarize(args.file)
        elif args.disasm is not None or args.addr is not None:
            raise ReaError(E_BAD_FORMAT, f"Disassembly belum mendukung berkas {info['type']}.", "Saat ini mendukung ELF dan APK (dengan pustaka .so).")
        else:
            result.warnings.append(f"Parser untuk {info['type']} belum tersedia; hanya identifikasi dan string.")

        needs_elf = args.cfg is not None or args.xrefs
        if needs_elf and not elf_target:
            if is_apk:
                result.warnings.append("APK tidak memiliki pustaka native (.so); CFG dan xref native dilewati.")
            else:
                raise ReaError(E_BAD_FORMAT, f"CFG dan xref belum mendukung berkas {info['type']}.", "Saat ini mendukung ELF dan APK.")
        if args.strings:
            data["strings"] = strings_mod.extract(
                args.file, min_len=args.min_len, max_items=args.max_strings, only_interesting=args.interesting
            )
        if args.entropy:
            data["entropy"] = {
                "map": entropy_mod.entropy_map(args.file),
                "sections": entropy_mod.section_entropy(elf_target, data["elf"]["sections"]) if elf_target and "elf" in data else [],
            }
        if args.cfg is not None and elf_target:
            graph = _cfg_for(elf_target, args.cfg or None)
            if not args.dot:
                graph.pop("dot", None)
            data["cfg"] = graph
        if args.xrefs and elf_target:
            pool = strings_mod.extract(elf_target, max_items=3000)["strings"]
            data["xrefs"] = xrefs_mod.analyze_xrefs(elf_target, pool)
            result.warnings += data["xrefs"].pop("warnings")
        if args.disasm is not None or args.addr is not None:
            if elf_target:
                address = _parse_address(args.addr) if args.addr is not None else None
                symbol = None if address is not None or args.disasm == "" else args.disasm
                data["disasm"] = disasm_mod.disassemble(elf_target, symbol=symbol, address=address, count=args.count)
            elif is_apk:
                result.warnings.append("APK tidak memiliki pustaka native (.so); disassembly native dilewati.")
    finally:
        if tmp_so and os.path.exists(tmp_so):
            try:
                os.remove(tmp_so)
            except OSError:
                pass
    for hook in ("on_parse", "on_analyze"):
        _merge_plugin_results(data, hook, _run_plugins(result, hook, info["type"], args.file, data))
    if not args.json:
        _print_analysis(data)
        for name, hooks in data.get("plugins", {}).items():
            print(f"plugin {name}: {json.dumps(hooks, ensure_ascii=False)[:300]}", file=sys.stderr)
    return result


def _report(args: argparse.Namespace) -> Result:
    """Laporan Markdown: identifikasi, ringkasan ELF/APK, string menarik, dan disassembly fungsi utama."""
    info = identify(args.file)
    data: dict = {"identify": info, "missing": []}
    elf_target = args.file if info["type"] == "ELF" else None
    tmp_so: str | None = None
    try:
        if info["type"] == "APK":
            data["apk"] = apk_mod.summarize_apk(args.file)
            tmp_so, so_entry = _extract_primary_apk_so(args.file)
            if tmp_so and so_entry:
                data["apk"]["primary_native_lib"] = so_entry
                elf_target = tmp_so
        elif info["type"] != "ELF":
            data["missing"].append(f"Parser khusus untuk {info['type']} belum tersedia; laporan berisi identifikasi dan string.")

        if elf_target:
            data["elf"] = elf_mod.summarize(elf_target)
            if tmp_so and "apk" in data:
                data["elf"]["source_entry"] = data["apk"].get("primary_native_lib")
            try:
                symbol = "main" if any(f["name"] == "main" for f in data["elf"]["functions"]) else None
                data["disasm"] = disasm_mod.disassemble(elf_target, symbol=symbol, count=40)
            except ReaError as error:
                data["missing"].append(f"Disassembly dilewati: {error.message}")

        data["strings"] = strings_mod.extract(args.file, max_items=500)
        data["entropy"] = {
            "map": entropy_mod.entropy_map(args.file),
            "sections": entropy_mod.section_entropy(elf_target, data["elf"]["sections"]) if elf_target and "elf" in data else [],
        }
        if elf_target:
            try:
                data["cfg"] = _cfg_for(elf_target, None)
                data["xrefs"] = xrefs_mod.analyze_xrefs(elf_target, strings_mod.extract(elf_target, max_items=3000)["strings"])
            except ReaError as error:
                data["missing"].append(f"CFG dan xref dilewati: {error.message}")
    finally:
        if tmp_so and os.path.exists(tmp_so):
            try:
                os.remove(tmp_so)
            except OSError:
                pass
    plugin_result = Result(command="report")
    for hook in ("on_parse", "on_analyze"):
        _merge_plugin_results(data, hook, _run_plugins(plugin_result, hook, info["type"], args.file, data))
    text = build_report(data, notes=args.notes or "")
    extra = _run_plugins(plugin_result, "on_report", info["type"], data, text)
    for name, section in extra.items():
        if isinstance(section, str) and section.strip():
            text = text.rstrip("\n") + f"\n\n## Plugin: {name}\n\n{section.strip()}\n"
    out = args.output or os.path.join(".rea", os.path.basename(args.file) + ".report.md")
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, "w", encoding="utf-8") as handle:
        handle.write(text)
    if not args.json:
        print(f"Laporan ditulis ke {out}", file=sys.stderr)
    plugin_result.data = {"path": out, "bytes": len(text.encode("utf-8")), "sections": text.count("\n## ")}
    return plugin_result


def _print_analysis(data: dict) -> None:
    info = data["identify"]
    print(f"{info['path']}  {info['type']} {info.get('arch', '')} {info.get('bits', '')}  {info['size']} bayt", file=sys.stderr)
    apk = data.get("apk")
    if apk:
        manifest = apk["manifest"]
        print(
            f"  paket {manifest['package']} v{manifest['version_name']} ({manifest['version_code']})  "
            f"sdk {manifest['min_sdk']}→{manifest['target_sdk']}  activity: {manifest['main_activity']}",
            file=sys.stderr,
        )
        print(
            f"  {len(manifest['permissions'])} izin, {len(apk['dex'])} DEX, "
            f"native ABI: {', '.join(apk['native_libs'].keys()) or 'tidak ada'}",
            file=sys.stderr,
        )
    elf = data.get("elf")
    if elf:
        src = f" [{elf['source_entry']}]" if elf.get("source_entry") else ""
        print(f"  ELF{src}: tipe {elf['type']}, mesin {elf['machine']}, entry 0x{elf['entry']:x}, "
              f"{'di-strip' if elf['stripped'] else 'ada simbol'}", file=sys.stderr)
        if elf["needed"]:
            print("  bergantung pada: " + ", ".join(elf["needed"]), file=sys.stderr)
        print(f"  {len(elf['sections'])} section, {len(elf['segments'])} segment, {len(elf['functions'])} fungsi", file=sys.stderr)
    found = data.get("strings")
    if found:
        print(f"\n{len(found['strings'])} string (total {found['total']}):", file=sys.stderr)
        for item in found["strings"]:
            print(f"  {item['offset']:08x} [{item['score']}] {item['text']}", file=sys.stderr)
    graph = data.get("cfg")
    if graph:
        print(f"\nCFG {graph['function']} ({len(graph['blocks'])} blok, {graph['back_edges']} back-edge):", file=sys.stderr)
        print(graph["ascii"], file=sys.stderr)
        if graph.get("dot"):
            print(graph["dot"], file=sys.stderr)
    xr = data.get("xrefs")
    if xr:
        print(f"\nCall graph ({xr['functions_scanned']} fungsi):", file=sys.stderr)
        for caller, callees in xr["call_graph"].items():
            print(f"  {caller} -> {', '.join(callees)}", file=sys.stderr)
        for row in xr["strings"]:
            print(f"  \"{row['text'][:60]}\" dipakai oleh " + ", ".join(sorted({r['function'] for r in row['refs']})), file=sys.stderr)
    ent = data.get("entropy")
    if ent:
        print(f"\nEntropi {ent['map']['spark']}  (blok tinggi: {ent['map']['high_blocks']})", file=sys.stderr)
        for row in ent["sections"]:
            print(f"  {row['name']:<20} {row['entropy']:.2f}{'  ⚠️ tinggi' if row['high'] else ''}", file=sys.stderr)
    code = data.get("disasm")
    if code:
        print(f"\n{code['function'] or '?'} @ 0x{code['address']:x}:", file=sys.stderr)
        for insn in code["instructions"]:
            print(f"  {insn['addr']:x}:  {insn['bytes']:<20} {insn['mnemonic']} {insn['operands']}", file=sys.stderr)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="rea", description="Reverse Engine Anything")
    parser.add_argument("--version", action="version", version=f"rea {__version__}")
    parser.add_argument("--no-plugins", action="store_true", help="jangan muat plugin (sama dengan REA_NO_PLUGINS=1)")
    sub = parser.add_subparsers(dest="command", required=True)
    for name, helptext in (
        ("doctor", "cek kemampuan lingkungan"),
        ("id", "identifikasi berkas"),
        ("analyze", "analisis statis berkas ELF"),
        ("report", "laporan Markdown dari analisis"),
        ("apk", "ringkasan APK: manifest, izin, DEX, tanda tangan"),
        ("dex", "daftar class dari DEX atau APK"),
        ("decompile", "dekompilasi APK/DEX dengan jadx atau apktool"),
        ("emu", "emulasi satu fungsi atau potongan kode (Unicorn)"),
        ("run", "jalankan program di QEMU user-mode dengan gdbstub"),
        ("firmware", "pindai firmware: filesystem, kernel, bootloader; opsional ekstrak"),
    ):
        command = sub.add_parser(name, help=helptext)
        command.add_argument("--json", action="store_true", help="keluaran JSON di baris terakhir stdout")
        if name in ("id", "analyze", "report", "apk", "dex", "decompile", "emu", "run", "firmware"):
            command.add_argument("file", help="berkas yang diperiksa")
        if name == "analyze":
            command.add_argument("--strings", action="store_true", help="ekstrak string ASCII dan UTF-16")
            command.add_argument("--min-len", type=int, default=4, help="panjang minimum string (default 4)")
            command.add_argument("--max-strings", type=int, default=500, help="jumlah string maksimum (default 500)")
            command.add_argument("--interesting", action="store_true", help="hanya string yang menarik (URL, jalur, kunci)")
            command.add_argument("--disasm", nargs="?", const="", metavar="FUNGSI", help="disassembly fungsi (tanpa nama: titik masuk)")
            command.add_argument("--addr", metavar="ALAMAT", help="disassembly mulai dari alamat virtual (mis. 0x401136)")
            command.add_argument("--count", type=int, default=64, help="jumlah instruksi maksimum (default 64)")
            command.add_argument("--cfg", nargs="?", const="", metavar="FUNGSI", help="control flow graph (default main)")
            command.add_argument("--dot", action="store_true", help="sertakan keluaran Graphviz DOT untuk --cfg")
            command.add_argument("--xrefs", action="store_true", help="call graph dan fungsi yang memakai tiap string")
            command.add_argument("--entropy", action="store_true", help="peta entropi per blok dan per section")
        if name == "dex":
            command.add_argument("--filter", help="hanya class berawalan paket ini (mis. com.foo)")
            command.add_argument("--limit", type=int, default=500, help="jumlah class maksimum (default 500)")
        if name == "decompile":
            command.add_argument("--tool", default="auto", choices=["auto", "jadx", "apktool"], help="alat dekompilasi")
            command.add_argument("-o", "--output", help="folder keluaran (default .rea/<nama>.<alat>)")
        if name == "firmware":
            command.add_argument("--extract", action="store_true", help="ekstrak filesystem dan aliran terkompresi yang ditemukan")
            command.add_argument("-o", "--output", help="folder ekstraksi (default .rea/<nama>.extracted)")
            command.add_argument("--max-hits", type=int, default=firmware_mod.MAX_HITS, help="jumlah temuan maksimum")
        if name == "emu":
            command.add_argument("--arch", help="arsitektur untuk berkas mentah: arm64, arm, x86_64, x86")
            command.add_argument("--func", help="nama fungsi di ELF yang dijalankan")
            command.add_argument("--start", metavar="ALAMAT", help="alamat awal (ELF: alamat virtual; mentah: alamat dalam --base)")
            command.add_argument("--end", metavar="ALAMAT", help="berhenti di alamat ini (default: saat fungsi kembali)")
            command.add_argument("--base", metavar="ALAMAT", help="alamat muat untuk berkas mentah")
            command.add_argument("--reg", action="append", metavar="NAMA=NILAI", help="nilai awal register (boleh berulang)")
            command.add_argument("--mem", action="append", metavar="ALAMAT:HEX", help="tulis byte ke memori sebelum jalan (boleh berulang)")
            command.add_argument("--dump", action="append", metavar="ALAMAT:PANJANG", help="tampilkan memori setelah selesai (boleh berulang)")
            command.add_argument("--max-insns", type=int, default=emu_mod.DEFAULT_MAX_INSNS, help="batas instruksi")
            command.add_argument("--timeout-ms", type=int, default=emu_mod.DEFAULT_TIMEOUT_MS, help="batas waktu emulasi (ms)")
        if name == "run":
            command.add_argument("--mode", default="auto", choices=["auto", "emu", "gdb", "strace", "frida"], help="mode eksekusi; auto memilih gdb/strace/frida bila ptrace jalan, selain itu emu")
            command.add_argument("--port", type=int, help="port gdbstub (default: acak)")
            command.add_argument("--break", dest="break_at", action="append", metavar="ALAMAT|FUNGSI", help="breakpoint (boleh berulang)")
            command.add_argument("--args", help="argumen program, dalam satu string")
            command.add_argument("--run-to-end", action="store_true", help="lanjutkan sampai program selesai dan tangkap keluarannya")
            command.add_argument("--timeout", type=float, default=None, help="batas waktu (detik; default tergantung mode)")
            command.add_argument("--trace", metavar="FILTER", help="filter syscall untuk strace, mis. file, network, openat,read")
            command.add_argument("--examine", action="append", metavar="ALAMAT:PANJANG", help="gdb: baca memori saat berhenti, mis. $sp:32 (boleh berulang)")
            command.add_argument("--hook", action="append", metavar="SIMBOL|ALAMAT", help="frida: pasang hook pada fungsi (boleh berulang)")
        if name == "report":
            command.add_argument("-o", "--output", help="berkas keluaran (default .rea/<nama>.report.md)")
            command.add_argument("--notes", help="catatan analis yang disisipkan di laporan")
    plugins = sub.add_parser("plugins", help="plugin: daftar dan buat kerangka baru")
    plugin_actions = plugins.add_subparsers(dest="plugins_action", required=True)
    for action, helptext in (("list", "daftar plugin dan galat muat"), ("new", "buat kerangka plugin di ~/.rea/plugins")):
        sub_action = plugin_actions.add_parser(action, help=helptext)
        sub_action.add_argument("--json", action="store_true", help="keluaran JSON di baris terakhir stdout")
        if action == "new":
            sub_action.add_argument("name", help="nama plugin (huruf kecil, angka, - atau _)")
    learn = sub.add_parser("learn", help="mode belajar: pelajaran, latihan pada biner, dan kuis")
    actions = learn.add_subparsers(dest="learn_action", required=True)
    for action, helptext in (
        ("list", "daftar pelajaran dan progres"),
        ("show", "teori, diagram, dan tugas satu pelajaran"),
        ("start", "siapkan (kompilasi) biner latihan"),
        ("check", "periksa jawaban latihan"),
        ("quiz", "kuis pilihan ganda"),
        ("answer", "tampilkan kunci jawaban"),
        ("reset", "hapus progres (semua atau satu pelajaran)"),
    ):
        sub_action = actions.add_parser(action, help=helptext)
        sub_action.add_argument("--json", action="store_true", help="keluaran JSON di baris terakhir stdout")
        if action in ("show", "start", "check", "quiz", "answer"):
            sub_action.add_argument("number", type=int, help="nomor pelajaran")
        if action == "reset":
            sub_action.add_argument("number", type=int, nargs="?", help="nomor pelajaran (kosong = semua)")
        if action == "check":
            sub_action.add_argument("answer", help="jawabanmu")
        if action == "quiz":
            sub_action.add_argument("--answers", help="pilihan tiap pertanyaan, dipisah koma (mis. 2,1)")
    return parser


_FORMAT_ERRORS = {"ELFError", "ELFParseError", "ELFRelocationError", "DWARFError", "error", "RecursionError", "ZeroDivisionError"}


def _is_format_error(error: BaseException) -> bool:
    """Pustaka pengurai (pyelftools, struct) melempar ini untuk masukan yang rusak, bukan karena bug REA."""
    import struct

    return isinstance(error, (struct.error, ZeroDivisionError, RecursionError)) or type(error).__name__ in _FORMAT_ERRORS


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    try:
        args = parser.parse_args(argv)
    except SystemExit as exit_request:
        # argparse sudah mencetak pesan ke stderr; --version dan --help keluar dengan 0.
        code = int(exit_request.code or 0) if isinstance(exit_request.code, int) else 2
        words = list(argv) if argv is not None else sys.argv[1:]
        if code != 0 and "--json" in words:
            # Aplikasi membaca baris terakhir stdout sebagai objek JSON; tanpa itu galat argumen tampak seperti crash.
            command = next((w for w in words if not w.startswith("-")), "rea")
            emit_json(failure(command, ReaError(E_USAGE, "Argumen tidak valid.", f"Jalankan `rea {command} --help`.")))
        return code
    handlers = {"doctor": _doctor, "id": _id, "analyze": _analyze, "report": _report, "apk": _apk, "dex": _dex, "decompile": _decompile, "emu": _emu, "run": _run, "learn": _learn, "plugins": _plugins_cmd, "firmware": _firmware}
    _show_notice_once()
    if args.no_plugins:
        os.environ["REA_NO_PLUGINS"] = "1"
    try:
        result = handlers[args.command](args)
        code = 0
    except ReaError as error:
        result, code = failure(args.command, error), 1
        if not args.json:
            print(f"Galat: {error.message}" + (f"\n{error.hint}" if error.hint else ""), file=sys.stderr)
    except Exception as error:  # noqa: BLE001 - batas terluar: jangan pernah menumpahkan traceback ke aplikasi
        if _is_format_error(error):
            # Berkas terpotong atau rusak bukan galat REA: katakan itu, jangan minta pengguna melapor.
            result = failure(args.command, ReaError(E_BAD_FORMAT, f"Berkas rusak atau terpotong: {error}", "Periksa dengan `rea id`."))
        else:
            result = failure(args.command, ReaError(E_INTERNAL, f"{type(error).__name__}: {error}", "Laporkan galat ini."))
        code = 1
        if not args.json:
            print(f"Galat internal: {error}", file=sys.stderr)
    if args.json:
        emit_json(result)
    return code


if __name__ == "__main__":
    sys.exit(main())
