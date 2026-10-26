"""``rea run --mode emu``: program dijalankan di QEMU user-mode dengan gdbstub (``-g``), tanpa ptrace.

QEMU berhenti di titik masuk menunggu debugger; REA tersambung, membaca register, memasang breakpoint bila diminta,
lalu menutup atau melepas proses. Yang diuji nyata adalah koneksi dan stop reply, bukan sekadar menjalankan proses.
"""

from __future__ import annotations

import os
import re
import shutil
import socket
import subprocess
from typing import Any

from ..parsers.elf import function_symbols, open_elf
from ..result import E_MISSING_DEP, E_NOT_SUPPORTED, E_SYMBOL, E_USAGE, ReaError
from .gdbrsp import GdbClient, parse_registers, parse_stop_reply
from .proc import StreamDrain

_QEMU = {"aarch64": "qemu-aarch64", "arm": "qemu-arm", "x86_64": "qemu-x86_64", "386": "qemu-i386"}
_LAYOUT: dict[str, list[tuple[str, int]]] = {
    "aarch64": [(f"x{i}", 8) for i in range(31)] + [("sp", 8), ("pc", 8), ("cpsr", 4)],
    "arm": [(f"r{i}", 4) for i in range(13)] + [("sp", 4), ("lr", 4), ("pc", 4)],
    "x86_64": [(n, 8) for n in ("rax", "rbx", "rcx", "rdx", "rsi", "rdi", "rbp", "rsp")]
    + [(f"r{i}", 8) for i in range(8, 16)]
    + [("rip", 8), ("eflags", 4)],
    "386": [(n, 4) for n in ("eax", "ecx", "edx", "ebx", "esp", "ebp", "esi", "edi", "eip", "eflags")],
}
_PC = {"aarch64": "pc", "arm": "pc", "x86_64": "rip", "386": "eip"}
_BREAK_KIND = {"aarch64": 4, "arm": 4, "x86_64": 1, "386": 1}
DEFAULT_TIMEOUT = 10.0
MAX_OUTPUT = 16 * 1024


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def _inspect_elf(path: str) -> tuple[str, int, list[dict[str, Any]]]:
    with open(path, "rb") as handle:
        if handle.read(4) != b"\x7fELF":
            raise ReaError(E_NOT_SUPPORTED, "QEMU user-mode hanya menjalankan ELF.", "Untuk potongan kode mentah pakai `rea emu`.")
        handle.seek(0)
        elf = open_elf(handle)
        machine = str(elf.header["e_machine"]).replace("EM_", "").lower()
        return machine, elf.header["e_entry"], function_symbols(elf)


def qemu_binary(machine: str) -> str:
    name = _QEMU.get(machine)
    if name is None:
        raise ReaError(E_NOT_SUPPORTED, f"Arsitektur {machine} belum didukung untuk QEMU gdbstub.", f"Didukung: {', '.join(_QEMU)}.")
    path = shutil.which(name)
    if path is None:
        raise ReaError(E_MISSING_DEP, f"{name} belum terpasang.", "Pasang: apt install qemu-user")
    return path


_WORD = {"aarch64": 8, "x86_64": 8, "arm": 4, "386": 4}
_AT_ENTRY = 9


def _read_auxv(client: GdbClient, machine: str, timeout: float) -> dict[int, int]:
    """Vektor auxv guest lewat ``qXfer:auxv:read``; ``AT_ENTRY`` adalah entry program yang sebenarnya (sudah dengan bias)."""
    word = _WORD[machine]
    data = b""
    for _ in range(16):
        reply = client.request(f"qXfer:auxv:read::{len(data):x},400", timeout)
        if reply[:1] not in ("l", "m"):
            return {}
        data += reply[1:].encode("latin-1")
        if reply[0] == "l":
            break
    return {
        int.from_bytes(data[i : i + word], "little"): int.from_bytes(data[i + word : i + 2 * word], "little")
        for i in range(0, len(data) - 2 * word + 1, 2 * word)
    }


def _load_bias(client: GdbClient, machine: str, entry: int, timeout: float) -> tuple[int, str]:
    """Selisih alamat muat dan alamat di berkas ELF. Berhenti pertama QEMU ada di ld.so untuk program dinamis, jadi PC
    awal tidak bisa dipakai; auxv (AT_ENTRY) benar untuk program statis, PIE, dan dinamis."""
    at_entry = _read_auxv(client, machine, timeout).get(_AT_ENTRY)
    if at_entry:
        return at_entry - entry, "auxv"
    offsets = client.request("qOffsets", timeout)
    match = re.match(r"Text=([0-9a-fA-F]+)", offsets)
    if match:
        return int(match[1], 16), "qOffsets"
    return 0, "none"


def _resolve_break(spec: str, symbols: list[dict[str, Any]], bias: int) -> int:
    spec = spec.strip()
    try:
        return int(spec, 0)
    except ValueError:
        pass
    matches = [s for s in symbols if s["name"] == spec]
    if not matches:
        raise ReaError(E_SYMBOL, f"Simbol {spec!r} tidak ada di ELF.", "Pakai alamat (0x...) atau nama fungsi dari `rea analyze`.")
    return matches[0]["addr"] + bias


def _read_stream(stream: Any) -> str:
    try:
        return stream.read(MAX_OUTPUT).decode("utf-8", "replace")
    except (OSError, ValueError):
        return ""


def run_emulated(
    path: str,
    *,
    port: int | None = None,
    breakpoints: list[str] | None = None,
    program_args: list[str] | None = None,
    run_to_end: bool = False,
    timeout: float = DEFAULT_TIMEOUT,
) -> dict[str, Any]:
    """Menjalankan ``path`` di QEMU dengan gdbstub dan mengembalikan hasil sesi debug."""
    machine, entry, symbols = _inspect_elf(path)
    qemu = qemu_binary(machine)
    port = port or free_port()
    process = subprocess.Popen(
        [qemu, "-g", str(port), os.path.abspath(path), *(program_args or [])],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    # Both pipes are read from the start: a program that prints more than a pipe holds would otherwise block forever.
    out_drain = StreamDrain(process.stdout, MAX_OUTPUT)
    err_drain = StreamDrain(process.stderr, MAX_OUTPUT)
    client: GdbClient | None = None
    layout = _LAYOUT[machine]
    try:
        try:
            client = GdbClient.connect("127.0.0.1", port, timeout, alive=lambda: process.poll() is None)
        except ReaError:
            if process.poll() is not None:
                reason = err_drain.text(wait=1.0).strip()[:300] or "tanpa pesan"
                raise ReaError(
                    E_NOT_SUPPORTED,
                    f"QEMU berhenti sebelum debugger tersambung: {reason}",
                    "Pastikan arsitektur ELF cocok dengan qemu-user yang terpasang (`rea doctor`).",
                ) from None
            raise
        features = client.request("qSupported:multiprocess+;swbreak+;hwbreak+", timeout)
        first = parse_stop_reply(client.request("?", timeout))
        registers = parse_registers(client.request("g", timeout), layout)
        pc_name = _PC[machine]
        pc = registers.get(pc_name)
        # Program PIE dimuat di alamat lain daripada di berkas ELF; selisihnya dipakai untuk simbol dan breakpoint.
        bias, bias_source = _load_bias(client, machine, entry, timeout)
        result: dict[str, Any] = {
            "mode": "emu",
            "connected": True,
            "tool": qemu,
            "port": port,
            "arch": machine,
            "entry": entry + bias,
            "bias": bias,
            "bias_source": bias_source,
            "supported": features[:200],
            "stop": first,
            "registers": registers,
            "breakpoints": [],
            "exit": None,
            "output": "",
        }
        wanted = [(_spec, _resolve_break(_spec, symbols, bias)) for _spec in (breakpoints or [])]
        for spec, address in wanted:
            ok = client.request(f"Z0,{address:x},{_BREAK_KIND[machine]}", timeout) == "OK"
            result["breakpoints"].append({"spec": spec, "addr": address, "set": ok, "hit": False})
        if wanted:
            reply = parse_stop_reply(client.request("c", timeout))
            result["stop"] = reply
            if reply["kind"] == "stopped":
                registers = parse_registers(client.request("g", timeout), layout)
                result["registers"] = registers
                for item in result["breakpoints"]:
                    item["hit"] = registers.get(pc_name) == item["addr"]
            else:
                result["exit"] = reply
        if run_to_end and result["exit"] is None:
            # `D` (detach) derruba qemu-user 8.2 dengan SIGSEGV; melepas breakpoint lalu `c` sampai proses selesai aman.
            for item in result["breakpoints"]:
                client.request(f"z0,{item['addr']:x},{_BREAK_KIND[machine]}", timeout)
            final = parse_stop_reply(client.request("c", timeout))
            result["exit"] = final
            result["stop"] = final
            try:
                process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                process.kill()
            result["output"] = out_drain.text(wait=1.0)
        return result
    except (TimeoutError, ConnectionError) as error:
        raise ReaError(E_USAGE, f"Sesi gdbstub terputus: {error}", "Coba lagi dengan --timeout lebih besar.") from error
    finally:
        if client is not None:
            try:
                client.send("k", 1.0)
            except Exception:  # noqa: BLE001 - penutupan terbaik-usaha
                pass
            client.close()
        if process.poll() is None:
            process.kill()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            pass
        for stream in (process.stdout, process.stderr):
            if stream is not None:
                stream.close()
