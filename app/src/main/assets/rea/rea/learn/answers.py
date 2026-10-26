"""Jawaban yang dihitung dari berkas latihan (bukan ditulis tangan), supaya selalu cocok dengan biner yang dikompilasi."""

from __future__ import annotations

import os
import random
from typing import Callable

from ..analysis import cfg as cfg_mod
from ..analysis.code import decode
from ..identify import identify
from ..parsers import elf as elf_mod
from ..parsers.elf import function_symbols, open_elf

PLAIN_XOR = "rea-rahasia"
XOR_KEY = 0x5A


def program(workdir: str) -> str:
    return os.path.join(workdir, "prog")


_ARCH_ALIASES = (
    {"aarch64", "arm64"},
    {"x86-64", "x86_64", "amd64", "x64"},
    {"x86", "i386", "386", "i686"},
)


def arch_names(workdir: str) -> list[str]:
    """Nama arsitektur persis seperti keluaran `rea id`, ditambah ejaan umum yang setara."""
    arch = str(identify(program(workdir)).get("arch", "")).lower()
    for group in _ARCH_ALIASES:
        if arch in group:
            return sorted(group)
    return [arch]


def load_segments(workdir: str) -> list[str]:
    segments = elf_mod.summarize(program(workdir))["segments"]
    return [str(sum(1 for s in segments if s["type"] == "LOAD"))]


def check_blocks(workdir: str) -> list[str]:
    with open(program(workdir), "rb") as handle:
        elf = open_elf(handle)
        match = next(f for f in function_symbols(elf) if f["name"] == "check")
        graph = cfg_mod.build_cfg(decode(elf, handle, function=match))
    return [str(len(graph["blocks"]))]


def packed_file(workdir: str) -> list[str]:
    for name in ("data.bin", "teks.txt"):
        if identify(os.path.join(workdir, name)).get("packed_hint"):
            return [name]
    return []


def bits(workdir: str) -> list[str]:
    return [str(identify(program(workdir)).get("bits", ""))]


def xor_blob() -> str:
    """Isi ``blob`` di program latihan XOR: teks asli di-XOR dengan kunci, dalam bentuk daftar byte C."""
    cipher = bytes(b ^ XOR_KEY for b in PLAIN_XOR.encode()) + bytes([XOR_KEY])  # penutup NUL ikut di-XOR
    return ", ".join(f"0x{b:02x}" for b in cipher)


def make_entropy_files(workdir: str) -> None:
    """``data.bin``: byte acak (seed tetap, jadi hasilnya selalu sama); ``teks.txt``: teks biasa."""
    rng = random.Random(1337)
    with open(os.path.join(workdir, "data.bin"), "wb") as handle:
        handle.write(bytes(rng.randrange(256) for _ in range(8192)))
    with open(os.path.join(workdir, "teks.txt"), "w", encoding="utf-8") as handle:
        handle.write("Ini hanyalah teks biasa yang berulang-ulang. " * 180)


DYNAMIC: dict[str, Callable[[str], list[str]]] = {
    "arch": arch_names,
    "load_segments": load_segments,
    "check_blocks": check_blocks,
    "packed_file": packed_file,
    "bits": bits,
}
GENERATORS: dict[str, Callable[[str], None]] = {"entropy_files": make_entropy_files}
