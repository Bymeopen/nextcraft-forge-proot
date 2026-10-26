"""Pembaca Android Binary XML (AndroidManifest.xml di dalam APK) tanpa dependensi."""

from __future__ import annotations

import struct
from typing import Any

from ..result import E_BAD_FORMAT, ReaError

_XML = 0x0003
_STRING_POOL = 0x0001
_RESOURCE_MAP = 0x0180
_START_ELEMENT = 0x0102
_END_ELEMENT = 0x0103
_UTF8 = 0x100

# Nama atribut dari id sumber daya, untuk APK yang nama atributnya dibuang dari string pool (aapt2 dengan shrinking).
_ATTR_IDS = {
    0x01010001: "label",
    0x01010002: "icon",
    0x01010003: "name",
    0x01010006: "permission",
    0x0101000E: "enabled",
    0x0101000F: "debuggable",
    0x01010010: "exported",
    0x01010018: "authorities",
    0x0101020C: "minSdkVersion",
    0x01010270: "targetSdkVersion",
    0x01010271: "maxSdkVersion",
    0x0101021B: "versionCode",
    0x0101021C: "versionName",
    0x01010280: "allowBackup",
    0x0101028E: "required",
    0x010104EC: "usesCleartextTraffic",
}

TYPE_REFERENCE = 0x01
TYPE_STRING = 0x03
TYPE_FLOAT = 0x04
TYPE_INT_DEC = 0x10
TYPE_INT_HEX = 0x11
TYPE_BOOLEAN = 0x12


def _read_string_pool(data: bytes, start: int) -> list[str]:
    _, header_size, size = struct.unpack_from("<HHI", data, start)
    count, _styles, flags, strings_start, _styles_start = struct.unpack_from("<IIIII", data, start + 8)
    offsets = struct.unpack_from(f"<{count}I", data, start + header_size)
    utf8 = bool(flags & _UTF8)
    base = start + strings_start
    out: list[str] = []
    for offset in offsets:
        position = base + offset
        if utf8:
            # Dua panjang: karakter UTF-16 lalu bayt UTF-8; masing-masing 1 atau 2 bayt.
            length = data[position]
            position += 2 if length & 0x80 else 1
            blength = data[position]
            position += 1
            if blength & 0x80:
                blength = ((blength & 0x7F) << 8) | data[position]
                position += 1
            out.append(data[position : position + blength].decode("utf-8", errors="replace"))
        else:
            (length,) = struct.unpack_from("<H", data, position)
            position += 2
            if length & 0x8000:
                (low,) = struct.unpack_from("<H", data, position)
                length = ((length & 0x7FFF) << 16) | low
                position += 2
            out.append(data[position : position + length * 2].decode("utf-16-le", errors="replace"))
    return out


def _value(kind: int, raw: int, strings: list[str], raw_string: int) -> Any:
    if kind == TYPE_STRING:
        return strings[raw] if 0 <= raw < len(strings) else ""
    if kind == TYPE_BOOLEAN:
        return raw != 0
    if kind == TYPE_INT_DEC:
        return struct.unpack("<i", struct.pack("<I", raw))[0]
    if kind == TYPE_INT_HEX:
        return raw
    if kind == TYPE_REFERENCE:
        return f"@0x{raw:08x}"
    if kind == TYPE_FLOAT:
        return struct.unpack("<f", struct.pack("<I", raw))[0]
    if raw_string != 0xFFFFFFFF and 0 <= raw_string < len(strings):
        return strings[raw_string]
    return raw


def parse_axml(data: bytes) -> dict[str, Any]:
    """Mengurai biner XML menjadi pohon ``{"tag", "attrs", "children"}`` (elemen akar dikembalikan)."""
    if len(data) < 8 or struct.unpack_from("<H", data, 0)[0] != _XML:
        raise ReaError(E_BAD_FORMAT, "Bukan Android Binary XML.", "AndroidManifest.xml di APK berformat biner; pastikan berkasnya benar.")
    strings: list[str] = []
    resource_ids: list[int] = []
    root: dict[str, Any] | None = None
    stack: list[dict[str, Any]] = []
    position = struct.unpack_from("<H", data, 2)[0]
    try:
        while position + 8 <= len(data):
            kind, header_size, size = struct.unpack_from("<HHI", data, position)
            if size < 8:
                break
            if kind == _STRING_POOL:
                strings = _read_string_pool(data, position)
            elif kind == _RESOURCE_MAP:
                count = (size - header_size) // 4
                resource_ids = list(struct.unpack_from(f"<{count}I", data, position + header_size))
            elif kind == _START_ELEMENT:
                base = position + 16
                _ns, name, attr_start, attr_size, attr_count = struct.unpack_from("<IIHHH", data, base)
                element: dict[str, Any] = {"tag": strings[name], "attrs": {}, "children": []}
                cursor = base + attr_start
                for _ in range(attr_count):
                    _ans, aname, araw, _vsize, _res0, vtype, vdata = struct.unpack_from("<IIIHBBI", data, cursor)
                    cursor += attr_size or 20
                    label = strings[aname] if 0 <= aname < len(strings) else ""
                    if not label and aname < len(resource_ids):
                        label = _ATTR_IDS.get(resource_ids[aname], f"attr_0x{resource_ids[aname]:08x}")
                    element["attrs"][label] = _value(vtype, vdata, strings, araw)
                if stack:
                    stack[-1]["children"].append(element)
                else:
                    root = element
                stack.append(element)
            elif kind == _END_ELEMENT and stack:
                stack.pop()
            position += size
    except (struct.error, IndexError) as error:
        raise ReaError(E_BAD_FORMAT, f"AndroidManifest.xml rusak: {error}", "Berkas mungkin dipotong atau dimodifikasi.") from error
    if root is None:
        raise ReaError(E_BAD_FORMAT, "AndroidManifest.xml tidak berisi elemen.", "")
    return root


def find_all(node: dict[str, Any], tag: str) -> list[dict[str, Any]]:
    found = [node] if node["tag"] == tag else []
    for child in node["children"]:
        found += find_all(child, tag)
    return found
