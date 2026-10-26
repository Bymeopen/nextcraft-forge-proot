"""Decoding fungsi dengan detail capstone: dasar untuk CFG dan xref."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from ..parsers.elf import function_symbols
from .disasm import _capstone, _engine, _read_at

MAX_FUNCTION_BYTES = 64 * 1024
_FALLBACK_BYTES = 256


@dataclass
class FunctionCode:
    name: str
    address: int
    size: int
    machine: str
    thumb: bool
    instructions: list[Any]  # objek capstone (detail aktif)


def decode(elf, handle, function: dict[str, Any] | None = None, address: int | None = None, name: str | None = None) -> FunctionCode:
    """Decode satu fungsi (dari simbol) atau potongan mulai ``address`` dengan detail operand."""
    machine = str(elf.header["e_machine"]).replace("EM_", "").lower()
    start = function["addr"] if function else address
    size = function["size"] if function else 0
    label = function["name"] if function else (name or f"sub_{start:x}")
    thumb = machine == "arm" and bool(start & 1)
    if machine == "arm":
        start &= ~1
    engine = _engine(machine, elf.little_endian, thumb, detail=True)
    code = _read_at(elf, handle, start, min(size, MAX_FUNCTION_BYTES) if size else _FALLBACK_BYTES)
    return FunctionCode(label, start, size, machine, thumb, list(engine.disasm(code, start)))


def defined_functions(elf, limit: int = 500) -> list[dict[str, Any]]:
    """Fungsi dengan ukuran yang diketahui dan alamat di segmen yang dimuat (yang bisa dianalisis)."""
    return [f for f in function_symbols(elf) if f["size"] > 0][:limit]


def is_jump(insn) -> bool:
    return bool(insn.group(_capstone().CS_GRP_JUMP))


def is_call(insn) -> bool:
    return bool(insn.group(_capstone().CS_GRP_CALL))


def is_ret(insn) -> bool:
    return bool(insn.group(_capstone().CS_GRP_RET))


def is_unconditional(machine: str, insn) -> bool:
    """Lompatan tanpa syarat: setelahnya alur tidak jatuh ke instruksi berikutnya."""
    if machine in ("x86_64", "386"):
        return insn.mnemonic in ("jmp", "ljmp")
    if machine == "aarch64":
        return insn.mnemonic in ("b", "br")
    if machine == "arm":
        return insn.mnemonic in ("b", "bx") or insn.mnemonic.startswith("b.w")
    return False


def immediate_target(machine: str, insn) -> int | None:
    """Target langsung (immediate) dari lompatan atau pemanggilan; None untuk yang tidak langsung."""
    cs = _capstone()
    wanted = {"x86_64": cs.x86.X86_OP_IMM, "386": cs.x86.X86_OP_IMM, "aarch64": cs.arm64.ARM64_OP_IMM, "arm": cs.arm.ARM_OP_IMM}.get(machine)
    if wanted is None or not insn.operands:
        return None
    operand = insn.operands[-1]
    return int(operand.imm) if operand.type == wanted else None
