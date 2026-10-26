"""Identifikasi berkas: tipe, arsitektur, hash, dan entropi. Hanya pustaka standar."""

from __future__ import annotations

import hashlib
import math
import os
import struct
import zipfile
from typing import Any

from .result import E_FILE_NOT_FOUND, E_NOT_A_FILE, E_PERMISSION, ReaError

_SAMPLE_BYTES = 8 * 1024 * 1024
_CHUNK = 1024 * 1024

_ELF_MACHINES = {
    0x03: "x86",
    0x08: "mips",
    0x14: "ppc",
    0x15: "ppc64",
    0x28: "arm",
    0x3E: "x86-64",
    0xB7: "arm64",
    0xF3: "riscv",
}
_PE_MACHINES = {0x14C: "x86", 0x8664: "x86-64", 0x1C0: "arm", 0xAA64: "arm64"}
_ELF_TYPES = {1: "relocatable", 2: "executable", 3: "shared", 4: "core"}
_MACHO_MAGICS = {
    b"\xfe\xed\xfa\xce": ("Mach-O", 32, "big"),
    b"\xce\xfa\xed\xfe": ("Mach-O", 32, "little"),
    b"\xfe\xed\xfa\xcf": ("Mach-O", 64, "big"),
    b"\xcf\xfa\xed\xfe": ("Mach-O", 64, "little"),
}


def entropy_of(data: bytes) -> float:
    """Entropi Shannon dalam bit per byte (0 sampai 8)."""
    if not data:
        return 0.0
    counts = [0] * 256
    for byte in data:
        counts[byte] += 1
    total = len(data)
    return -sum((c / total) * math.log2(c / total) for c in counts if c)


def _looks_text(buf: bytes) -> bool:
    """Teks = UTF-8 yang valid (huruf yang terpotong di ujung boleh) tanpa karakter kontrol selain tab dan baris baru.
    Padding 0xff atau data biner dengan byte tinggi bukan teks."""
    if not buf or any(b < 32 and b not in (9, 10, 13) or b == 127 for b in buf):
        return False
    try:
        buf.decode("utf-8")
    except UnicodeDecodeError as error:
        return error.start >= len(buf) - 3
    return True


def _detect(head: bytes, path: str) -> dict[str, Any]:
    info: dict[str, Any] = {"type": "unknown"}
    if head[:4] == b"\x7fELF" and len(head) >= 20:
        bits = 64 if head[4] == 2 else 32
        endian = "little" if head[5] == 1 else "big"
        order = "<" if endian == "little" else ">"
        e_type, machine = struct.unpack(order + "HH", head[16:20])
        info.update(
            type="ELF",
            bits=bits,
            endian=endian,
            arch=_ELF_MACHINES.get(machine, f"machine-0x{machine:x}"),
            elf_type=_ELF_TYPES.get(e_type, f"type-{e_type}"),
        )
    elif head[:2] == b"MZ" and len(head) >= 0x40:
        info["type"] = "PE"
        (pe_offset,) = struct.unpack("<I", head[0x3C:0x40])
        try:
            with open(path, "rb") as handle:
                handle.seek(pe_offset)
                sig = handle.read(6)
            if sig[:4] == b"PE\x00\x00":
                (machine,) = struct.unpack("<H", sig[4:6])
                info["arch"] = _PE_MACHINES.get(machine, f"machine-0x{machine:x}")
        except OSError:
            pass
    elif head[:4] in _MACHO_MAGICS:
        name, bits, endian = _MACHO_MAGICS[head[:4]]
        info.update(type=name, bits=bits, endian=endian)
    elif head[:4] == b"dex\n":
        info.update(type="DEX", version=head[4:7].decode("ascii", "replace"))
    elif head[:4] == b"PK\x03\x04" or head[:4] == b"PK\x05\x06":
        info["type"] = "ZIP"
        try:
            with zipfile.ZipFile(path) as archive:
                names = set(archive.namelist())
            if "AndroidManifest.xml" in names:
                info["type"] = "APK"
        except (zipfile.BadZipFile, OSError):
            pass
    elif head[:4] in (b"hsqs", b"sqsh"):
        info["type"] = "SquashFS"
    elif head[:4] == b"\x28\xcd\x3d\x45" or head[:4] == b"\x45\x3d\xcd\x28":
        info["type"] = "CramFS"
    elif head[:2] in (b"\x85\x19", b"\x19\x85"):
        info["type"] = "JFFS2"
    elif head[:2] == b"\x1f\x8b":
        info["type"] = "gzip"
    elif head[:6] == b"\xfd7zXZ\x00":
        info["type"] = "xz"
    elif head[:3] == b"BZh":
        info["type"] = "bzip2"
    elif head[:5] == b"%PDF-":
        info["type"] = "PDF"
    elif head[:8] == b"\x89PNG\r\n\x1a\n":
        info["type"] = "PNG"
    elif _looks_text(head[:512]):
        info["type"] = "text"
    return info


def identify(path: str) -> dict[str, Any]:
    """Mengembalikan ``data`` untuk perintah ``id``. Melempar ReaError untuk galat yang diharapkan."""
    if not os.path.exists(path):
        raise ReaError(E_FILE_NOT_FOUND, f"Berkas tidak ditemukan: {path}", "Periksa jalur; jalur relatif terhadap folder kerja.")
    if not os.path.isfile(path):
        raise ReaError(E_NOT_A_FILE, f"Bukan berkas biasa: {path}", "Pilih berkas, bukan folder atau perangkat.")
    md5 = hashlib.md5(usedforsecurity=False)
    sha256 = hashlib.sha256()
    sample = bytearray()
    size = 0
    try:
        with open(path, "rb") as handle:
            head = handle.read(4096)
            md5.update(head)
            sha256.update(head)
            size += len(head)
            sample.extend(head)
            while True:
                chunk = handle.read(_CHUNK)
                if not chunk:
                    break
                md5.update(chunk)
                sha256.update(chunk)
                size += len(chunk)
                if len(sample) < _SAMPLE_BYTES:
                    sample.extend(chunk[: _SAMPLE_BYTES - len(sample)])
    except PermissionError as error:
        raise ReaError(E_PERMISSION, f"Tidak boleh membaca berkas: {path}", "Periksa izin berkas.") from error
    info = _detect(bytes(head), path)
    if info["type"] in ("unknown", "text"):
        # Imej firmware tidak diawali magic yang dikenal (bootloader, padding 0xff yang lolos heuristik teks); cari tanda
        # kuat di sampel pertama. Berkas teks hanya dianggap firmware bila ada signature biner yang valid: log dmesg atau
        # dokumen yang menyebut "U-Boot 2022.01" bukan firmware.
        from .parsers import firmware

        hint = firmware.looks_like_firmware(path, binary_only=info["type"] == "text")
        if hint is not None:
            info["type"] = "firmware"
            info["firmware"] = hint
    entropy = round(entropy_of(bytes(sample)), 3)
    info.update(
        path=path,
        size=size,
        md5=md5.hexdigest(),
        sha256=sha256.hexdigest(),
        entropy=entropy,
        # Entropi tinggi pada berkas yang bukan arsip biasanya berarti terkompresi, terenkripsi, atau dipak.
        packed_hint=bool(entropy > 7.2 and info["type"] in ("ELF", "PE", "Mach-O", "DEX", "unknown")),
        entropy_sample_bytes=len(sample),
    )
    return info
