"""Disassembly per fungsi dengan capstone (ARM64, ARM/Thumb, x86, x86-64)."""

from __future__ import annotations

from typing import Any

from ..parsers.elf import function_symbols, open_elf
from ..result import E_BAD_FORMAT, E_MISSING_DEP, E_NOT_SUPPORTED, E_SYMBOL, ReaError

DEFAULT_COUNT = 64
MAX_COUNT = 5000
_FALLBACK_BYTES = 256


def _capstone():
    try:
        import capstone
    except ImportError as error:
        raise ReaError(E_MISSING_DEP, "capstone belum terpasang.", "Pasang: apt install python3-capstone") from error
    return capstone


def _engine(machine: str, little_endian: bool, thumb: bool, detail: bool = False):
    cs = _capstone()
    endian = 0 if little_endian else cs.CS_MODE_BIG_ENDIAN
    table = {
        "x86_64": (cs.CS_ARCH_X86, cs.CS_MODE_64),
        "386": (cs.CS_ARCH_X86, cs.CS_MODE_32),
        "aarch64": (cs.CS_ARCH_ARM64, cs.CS_MODE_ARM),
        "arm": (cs.CS_ARCH_ARM, cs.CS_MODE_THUMB if thumb else cs.CS_MODE_ARM),
    }
    if machine not in table:
        raise ReaError(E_NOT_SUPPORTED, f"Arsitektur {machine} belum didukung untuk disassembly.", "Didukung: x86, x86-64, arm, arm64.")
    arch, mode = table[machine]
    engine = cs.Cs(arch, mode | endian)
    engine.detail = detail
    if arch == cs.CS_ARCH_X86:
        engine.syntax = cs.CS_OPT_SYNTAX_INTEL
    return engine


def _read_at(elf, handle, address: int, size: int) -> bytes:
    """Membaca ``size`` byte pada alamat virtual lewat segmen PT_LOAD."""
    for segment in elf.iter_segments():
        if segment["p_type"] != "PT_LOAD":
            continue
        start, filesz = segment["p_vaddr"], segment["p_filesz"]
        if start <= address < start + filesz:
            handle.seek(segment["p_offset"] + (address - start))
            return handle.read(min(size, start + filesz - address))
    raise ReaError(E_BAD_FORMAT, f"Alamat 0x{address:x} tidak berada di segmen yang dimuat.", "Periksa alamat dengan `rea analyze` (daftar segmen).")


def disassemble(path: str, symbol: str | None = None, address: int | None = None, count: int = DEFAULT_COUNT) -> dict[str, Any]:
    """Disassembly satu fungsi (nama simbol), atau mulai dari ``address``; tanpa keduanya: titik masuk ELF."""
    count = max(1, min(count, MAX_COUNT))
    with open(path, "rb") as handle:
        elf = open_elf(handle)
        machine = str(elf.header["e_machine"]).replace("EM_", "").lower()
        functions = function_symbols(elf)
        name = symbol
        size = 0
        if address is None:
            if symbol:
                match = next((f for f in functions if f["name"] == symbol), None)
                if match is None:
                    hint = "Simbol tidak ada (biner di-strip?). Pakai --addr 0x... atau tanpa argumen untuk titik masuk."
                    raise ReaError(E_SYMBOL, f"Simbol tidak ditemukan: {symbol}", hint)
                address, size = match["addr"], match["size"]
            else:
                address, name = elf.header["e_entry"], "entry"
        else:
            owner = next((f for f in functions if f["addr"] <= address < f["addr"] + max(f["size"], 1)), None)
            name = owner["name"] if owner else None
            size = (owner["addr"] + owner["size"] - address) if owner and owner["size"] else 0
        thumb = machine == "arm" and bool(address & 1)
        address &= ~1 if machine == "arm" else ~0
        engine = _engine(machine, elf.little_endian, thumb)
        code = _read_at(elf, handle, address, size if size else _FALLBACK_BYTES)
    instructions = []
    for insn in engine.disasm(code, address):
        instructions.append(
            {
                "addr": insn.address,
                "size": insn.size,
                "bytes": bytes(insn.bytes).hex(),
                "mnemonic": insn.mnemonic,
                "operands": insn.op_str,
            }
        )
        if len(instructions) >= count:
            break
    return {
        "function": name,
        "address": address,
        "size": size,
        "machine": machine,
        "thumb": thumb,
        "instructions": instructions,
        "truncated": bool(size) and sum(i["size"] for i in instructions) < size,
    }
