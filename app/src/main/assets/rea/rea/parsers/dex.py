"""Pembaca DEX (Dalvik Executable): header, daftar class, dan statistik; tanpa dependensi."""

from __future__ import annotations

import struct
import zlib
from typing import Any

from ..result import E_BAD_FORMAT, ReaError

NO_INDEX = 0xFFFFFFFF
_MAGIC = b"dex\n"
MAX_DEX_BYTES = 64 * 1024 * 1024

_ACCESS = (
    (0x1, "public"), (0x2, "private"), (0x4, "protected"), (0x8, "static"), (0x10, "final"), (0x200, "interface"),
    (0x400, "abstract"), (0x1000, "synthetic"), (0x2000, "annotation"), (0x4000, "enum"),
)


def _uleb(data: bytes, position: int) -> tuple[int, int]:
    result = shift = 0
    while True:
        byte = data[position]
        position += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, position
        shift += 7


def descriptor_to_name(descriptor: str) -> str:
    """``Lcom/foo/Bar;`` menjadi ``com.foo.Bar``; array dan primitif dibiarkan apa adanya."""
    if descriptor.startswith("L") and descriptor.endswith(";"):
        return descriptor[1:-1].replace("/", ".")
    return descriptor


def access_names(flags: int) -> list[str]:
    return [name for bit, name in _ACCESS if flags & bit]


def parse_dex(data: bytes, name_filter: str = "", limit: int = 2000) -> dict[str, Any]:
    """Header dan daftar class. ``name_filter`` memotong berdasarkan awalan paket (``com.foo``)."""
    if len(data) < 0x70 or data[:4] != _MAGIC:
        raise ReaError(E_BAD_FORMAT, "Bukan berkas DEX.", "Header harus diawali `dex\\n`.")
    try:
        version = data[4:7].decode("ascii")
        checksum, = struct.unpack_from("<I", data, 8)
        file_size, header_size, endian = struct.unpack_from("<III", data, 32)
        counts = struct.unpack_from("<IIIIIIIIIIII", data, 56)
        string_size, string_off, type_size, type_off, _ps, _po, field_size, _fo, method_size, _mo, class_size, class_off = counts
        string_offsets = struct.unpack_from(f"<{string_size}I", data, string_off)

        def string(index: int) -> str:
            position = string_offsets[index]
            _, position = _uleb(data, position)
            end = data.index(b"\x00", position)
            return data[position:end].decode("utf-8", errors="replace")

        type_ids = struct.unpack_from(f"<{type_size}I", data, type_off)
        classes: list[dict[str, Any]] = []
        total = 0
        for index in range(class_size):
            class_idx, access, super_idx, _ifaces, _src, _ann, class_data, _static = struct.unpack_from("<8I", data, class_off + index * 32)
            name = descriptor_to_name(string(type_ids[class_idx]))
            if name_filter and not name.startswith(name_filter):
                continue
            total += 1
            if len(classes) >= limit:
                continue
            fields = methods = 0
            if class_data:
                static_f, position = _uleb(data, class_data)
                instance_f, position = _uleb(data, position)
                direct_m, position = _uleb(data, position)
                virtual_m, position = _uleb(data, position)
                fields, methods = static_f + instance_f, direct_m + virtual_m
            classes.append(
                {
                    "name": name,
                    "access": access_names(access),
                    "super": None if super_idx == NO_INDEX else descriptor_to_name(string(type_ids[super_idx])),
                    "fields": fields,
                    "methods": methods,
                }
            )
    except (struct.error, IndexError, ValueError) as error:
        raise ReaError(E_BAD_FORMAT, f"DEX rusak: {error}", "Berkas mungkin dipotong, dimodifikasi, atau memakai tata letak yang tak dikenal.") from error
    return {
        "version": version,
        "size": len(data),
        "declared_size": file_size,
        "header_size": header_size,
        "little_endian": endian == 0x12345678,
        "checksum_ok": zlib.adler32(data[12:]) & 0xFFFFFFFF == checksum,
        "strings": string_size,
        "types": type_size,
        "fields": field_size,
        "methods": method_size,
        "class_count": class_size,
        "matched": total,
        "classes": classes,
        "truncated": total > len(classes),
    }
