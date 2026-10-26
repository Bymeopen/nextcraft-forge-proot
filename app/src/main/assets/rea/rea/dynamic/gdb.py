"""``rea run --mode gdb``: gdb em modo batch (breakpoint, registers, backtrace, memori) dengan keluaran yang diurai.

Perintah gdb dipisahkan oleh penanda ``@@REA:...@@`` yang dicetak lewat ``echo``, sehingga keluaran program yang diuji
tidak bercampur dengan hasil yang diurai.
"""

from __future__ import annotations

import os
import re
from typing import Any

from ..result import E_BAD_FORMAT, E_USAGE, ReaError
from .proc import raise_if_ptrace_denied, require_tool, run_limited

DEFAULT_TIMEOUT = 30.0
MAX_EXAMINE = 4096
_SAFE_SPEC = re.compile(r"[A-Za-z0-9_.:$+\-*&]{1,120}")
_MARK = re.compile(r"@@REA:([A-Z]+)(?::([^@]*))?@@\n?")
_REG = re.compile(r"^([a-z0-9_]+)\s+0x([0-9a-f]+)\s", re.M)
_FRAME = re.compile(r"^#(\d+)\s+(?:0x([0-9a-f]+) in )?(\S+) \((.*?)\)(?: at (\S+))?", re.M)
_HIT = re.compile(r"^Breakpoint (\d+), ", re.M)
_EXITED = re.compile(r"\[Inferior \d+ \(process \d+\) exited (?:normally|with code (\d+))\]")
_UNRESOLVED = re.compile(r'Function "([^"]+)" not defined')
_NOISE = re.compile(
    r"^(Breakpoint \d+ at .*|\[Thread debugging using libthread_db enabled\]|Using host libthread_db library.*|"
    r"warning: .*|Starting program: .*|Temporary breakpoint \d+.*|\[Inferior \d+ \(process \d+\) exited .*|"
    r"This GDB supports auto-downloading debuginfo.*|\s+<https?://debuginfod[^>]*>|Debuginfod has been disabled\.|"
    r"To make this setting permanent.*)\n?",
    re.M,
)
_MEM_LINE = re.compile(r"^0x[0-9a-f]+(?: <[^>]*>)?:(.*)$", re.M)
_BYTE = re.compile(r"\b0x([0-9a-f]{2})\b")


def _check_spec(spec: str, what: str) -> str:
    if not _SAFE_SPEC.fullmatch(spec) or spec.startswith("-"):
        raise ReaError(E_USAGE, f"{what} tidak valid: {spec!r}", "Pakai nama fungsi atau alamat (0x...).")
    return spec


def _commands(breakpoints: list[str], examines: list[tuple[str, int]], backtrace: bool) -> list[str]:
    # `set debuginfod` does not exist in old gdb; an unknown `set` only prints an error and the batch carries on.
    cmds = ["set pagination off", "set confirm off", "set style enabled off", "set disable-randomization on", "set debuginfod enabled off"]
    for spec in breakpoints:
        cmds.append(f"break *{spec}" if spec.lower().startswith("0x") else f"break {spec}")
    cmds.append("run" if breakpoints else "starti")
    cmds += ["echo @@REA:REGS@@\\n", "info registers"]
    if backtrace:
        cmds += ["echo @@REA:BT@@\\n", "bt 30"]
    for index, (spec, length) in enumerate(examines):
        cmds += [f"echo @@REA:MEM:{index}@@\\n", f"x/{length}xb {spec}"]
    cmds += ["echo @@REA:END@@\\n", "kill"]
    return cmds


def _sections(text: str) -> tuple[str, dict[str, str]]:
    """Teks sebelum penanda pertama (keluaran program dan pesan breakpoint) dan isi tiap bagian bernama."""
    parts: dict[str, str] = {}
    matches = list(_MARK.finditer(text))
    head = text[: matches[0].start()] if matches else text
    for i, match in enumerate(matches):
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        key = match[1] + (f":{match[2]}" if match[2] else "")
        parts[key] = text[match.end() : end]
    return head, parts


def parse_registers(text: str) -> dict[str, int]:
    return {m[1]: int(m[2], 16) for m in _REG.finditer(text)}


def parse_backtrace(text: str) -> list[dict[str, Any]]:
    return [
        {"level": int(m[1]), "pc": int(m[2], 16) if m[2] else None, "function": m[3], "args": m[4][:120], "source": m[5]}
        for m in _FRAME.finditer(text)
    ]


def parse_memory(text: str) -> bytes:
    # Per line: the address that starts the next line must not be read as a data byte.
    data = bytearray()
    for line in _MEM_LINE.finditer(text):
        data.extend(int(token, 16) for token in _BYTE.findall(line[1]))
    return bytes(data)


def parse_examine(spec: str) -> tuple[str, int]:
    address, _, length = spec.rpartition(":")
    if not address:
        raise ReaError(E_USAGE, f"Format --examine salah: {spec!r}", "Bentuk yang benar: ALAMAT:PANJANG, mis. $sp:32")
    try:
        size = int(length, 0)
    except ValueError as error:
        raise ReaError(E_USAGE, f"Panjang --examine bukan angka: {length!r}", "") from error
    if not 0 < size <= MAX_EXAMINE:
        raise ReaError(E_USAGE, f"Panjang --examine harus 1..{MAX_EXAMINE}.", "")
    return _check_spec(address, "Alamat --examine"), size


def debug(
    path: str,
    program_args: list[str],
    breakpoints: list[str] | None = None,
    examines: list[tuple[str, int]] | None = None,
    backtrace: bool = True,
    timeout: float = DEFAULT_TIMEOUT,
) -> dict[str, Any]:
    gdb = require_tool("gdb", "gdb")
    if not os.path.isfile(path):
        raise ReaError(E_BAD_FORMAT, f"Bukan berkas: {path}", "")
    breakpoints = [_check_spec(b, "Breakpoint") for b in breakpoints or []]
    examines = examines or []
    command = [gdb, "-batch", "-nx", "-q"]
    for cmd in _commands(breakpoints, examines, backtrace):
        command += ["-ex", cmd]
    command += ["--args", os.path.abspath(path), *program_args]
    done = run_limited(command, timeout)
    raise_if_ptrace_denied("gdb", done.stderr + done.stdout[:800])
    head, parts = _sections(done.stdout)
    registers = parse_registers(parts.get("REGS", ""))
    unresolved = {m[1] for m in _UNRESOLVED.finditer(done.stderr + done.stdout)}
    # gdb numbers the breakpoints it accepted in command order; a hit line names the number, not the spec.
    numbered = {i + 1: spec for i, spec in enumerate(b for b in breakpoints if b not in unresolved)}
    hit_specs = {numbered[int(m[1])] for m in _HIT.finditer(head) if int(m[1]) in numbered}
    exited = _EXITED.search(head)
    # Output of the program is what came before the stop report (or everything, if it ran to the end).
    first_hit = _HIT.search(head)
    output = _NOISE.sub("", head[: first_hit.start()] if first_hit else head).strip()
    result: dict[str, Any] = {
        "mode": "gdb",
        "timed_out": done.timed_out,
        "stopped": bool(registers),
        "exit": None if exited is None else {"code": int(exited[1] or 0)},
        "breakpoints": [{"spec": b, "set": b not in unresolved, "hit": b in hit_specs} for b in breakpoints],
        "registers": registers,
        "backtrace": parse_backtrace(parts.get("BT", "")),
        "memory": [],
        "program_output": output[:4000],
        "gdb_error": done.stderr.strip()[:1000],
    }
    for index, (spec, length) in enumerate(examines):
        raw = parse_memory(parts.get(f"MEM:{index}", ""))
        result["memory"].append(
            {
                "spec": spec,
                "size": length,
                "hex": raw.hex() or None,
                "ascii": "".join(chr(b) if 32 <= b < 127 else "." for b in raw) if raw else None,
                "error": None if raw else "memori tidak terbaca (program sudah selesai atau alamat tidak valid)",
            }
        )
    return result
