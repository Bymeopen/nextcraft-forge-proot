"""Firmware: memindai signature filesystem dan kontainer, mendeteksi kernel dan bootloader, dan mengekstrak lewat alat.

Pemindaian murni Python (stdlib, streaming; berkas besar tidak dimuat penuh). Setiap temuan divalidasi dari header-nya,
bukan hanya dari magic, supaya data acak tidak menghasilkan temuan palsu. Ekstraksi memakai alat yang terpasang
(`unsquashfs`, `fsck.cramfs`, `cpio`, `jefferson`) dan pustaka Python untuk gzip/xz/bzip2/lzma/zip; alat yang tidak ada
dilaporkan dengan perintah pemasangannya. Semua keluaran dibatasi ukurannya.
"""

from __future__ import annotations

import bz2
import gzip
import lzma
import os
import re
import shutil
import struct
import subprocess
import zipfile
import zlib
from typing import Any, Callable

from ..result import E_BAD_FORMAT, E_USAGE, ReaError

CHUNK = 4 * 1024 * 1024
OVERLAP = 512
MAX_HITS = 200
MAX_CARVE = 512 * 1024 * 1024
MAX_DECOMPRESS = 256 * 1024 * 1024
KERNEL_SCAN_DECOMPRESS = 64 * 1024 * 1024
TOOL_TIMEOUT = 600
PROBE = 64 * 1024
JFFS2_MERGE_WINDOW = 4 * 1024 * 1024

_SQUASH_COMPRESSION = {1: "gzip", 2: "lzma", 3: "lzo", 4: "xz", 5: "lz4", 6: "zstd"}
_UIMAGE_OS = {1: "openbsd", 2: "netbsd", 3: "freebsd", 5: "linux", 17: "u-boot", 22: "android"}
_UIMAGE_ARCH = {2: "arm", 3: "x86", 5: "mips", 7: "ppc", 22: "arm64", 24: "riscv"}
_UIMAGE_TYPE = {2: "kernel", 3: "ramdisk", 4: "multi", 5: "firmware", 6: "script", 8: "filesystem", 14: "fdt"}
_UIMAGE_COMP = {0: "none", 1: "gzip", 2: "bzip2", 3: "lzma", 4: "lzo", 5: "lz4", 6: "zstd"}

_KERNEL_BANNER = re.compile(rb"Linux version (\d+\.\d+[\w.+\-]*) \(([^\n\x00]{0,160})")
_BOOTLOADERS = (
    ("U-Boot", re.compile(rb"U-Boot (\d{4}\.\d{2}[\w.+\-]*|\d+\.\d+\.\d+[\w.+\-]*)")),
    ("barebox", re.compile(rb"barebox (\d{4}\.\d{2}\.\d+[\w.+\-]*)")),
    ("GRUB", re.compile(rb"GRUB (\d+\.\d+[\w.+\-]*)")),
)

Reader = Callable[[int, int], bytes]


def _printable(raw: bytes) -> str:
    """Teks sampai NUL atau akhir baris pertama (banner kernel diikuti sampah biner)."""
    return re.split(rb"[\x00\r\n]", raw, maxsplit=1)[0].decode("ascii", "replace").strip()


def _try_decompress(kind: str, data: bytes) -> bool:
    """Apakah ``data`` (awal sebuah aliran) bisa mulai didekompresi tanpa galat? Dipotong tidak masalah."""
    try:
        if kind == "gzip":
            return len(zlib.decompressobj(31).decompress(data, 4096)) > 0
        if kind == "xz":
            lzma.LZMADecompressor(format=lzma.FORMAT_XZ).decompress(data, 4096)
            return True
        if kind == "bzip2":
            return len(bz2.BZ2Decompressor().decompress(data, 4096)) > 0
        if kind == "lzma":
            return len(lzma.LZMADecompressor(format=lzma.FORMAT_ALONE).decompress(data, 4096)) > 0
    except (zlib.error, lzma.LZMAError, OSError, ValueError, EOFError):
        return False
    return False


# --- validator: (read, offset) -> (detail, size) atau None ------------------------------------------------------------


def _v_squashfs(read: Reader, offset: int) -> tuple[dict, int | None] | None:
    head = read(offset, 96)
    if len(head) < 96:
        return None
    order = "<" if head[:4] == b"hsqs" else ">"
    block_size = struct.unpack(order + "I", head[12:16])[0]
    compression = struct.unpack(order + "H", head[20:22])[0]
    block_log = struct.unpack(order + "H", head[22:24])[0]
    major = struct.unpack(order + "H", head[28:30])[0]
    minor = struct.unpack(order + "H", head[30:32])[0]
    if major != 4 or not 12 <= block_log <= 20 or block_size != 1 << block_log or compression not in _SQUASH_COMPRESSION:
        return None
    inodes = struct.unpack(order + "I", head[4:8])[0]
    used = struct.unpack(order + "Q", head[40:48])[0]
    detail = {"version": f"{major}.{minor}", "compression": _SQUASH_COMPRESSION[compression], "block_size": block_size, "inodes": inodes, "endian": "little" if order == "<" else "big"}
    return detail, used if 96 < used <= MAX_CARVE else None


def _v_cramfs(read: Reader, offset: int) -> tuple[dict, int | None] | None:
    head = read(offset, 48)
    if len(head) < 48 or head[16:32] != b"Compressed ROMFS":
        return None
    size, flags = struct.unpack("<II", head[4:12])
    return {"name": _printable(head[32:48]), "flags": flags}, size if 48 < size <= MAX_CARVE else None


def _v_jffs2(read: Reader, offset: int) -> tuple[dict, int | None] | None:
    head = read(offset, 12)
    if len(head) < 12:
        return None
    node_type, total = struct.unpack("<HI", head[2:8])
    names = {0xE001: "dirent", 0xE002: "inode", 0x2003: "cleanmarker", 0x2004: "padding", 0x2006: "summary"}
    if node_type not in names or not 12 <= total <= 0x100000:
        return None
    return {"node": names[node_type], "nodes": 1}, None


def _v_compressed(kind: str) -> Callable[[Reader, int], tuple[dict, int | None] | None]:
    def validate(read: Reader, offset: int) -> tuple[dict, int | None] | None:
        data = read(offset, PROBE)
        if kind == "gzip" and (len(data) < 10 or data[3] & 0xE0 or data[8] not in (0, 2, 4)):
            return None
        if kind == "xz" and (len(data) < 12 or data[6] != 0):
            return None
        if kind == "lzma":
            if len(data) < 13 or data[0] != 0x5D:
                return None
            dictionary = struct.unpack("<I", data[1:5])[0]
            if dictionary == 0 or dictionary & (dictionary - 1):
                return None
        return ({"decompresses": True}, None) if _try_decompress(kind, data) else None

    return validate


def _v_zip(read: Reader, offset: int) -> tuple[dict, int | None] | None:
    head = read(offset, 30)
    if len(head) < 30:
        return None
    version, flags, method = struct.unpack("<HHH", head[4:10])
    if version > 63 or method not in (0, 8, 9, 12, 14, 93, 95, 98):
        return None
    name_len = struct.unpack("<H", head[26:28])[0]
    name = read(offset + 30, min(name_len, 200))
    return {"first_entry": name.decode("utf-8", "replace")}, None


def _v_uimage(read: Reader, offset: int) -> tuple[dict, int | None] | None:
    head = read(offset, 64)
    if len(head) < 64:
        return None
    size, load, entry = struct.unpack(">III", head[12:24])
    os_id, arch, kind, comp = head[28], head[29], head[30], head[31]
    if os_id > 40 or arch > 40 or not 1 <= kind <= 40 or comp > 6 or size > MAX_CARVE:
        return None
    detail = {
        "name": _printable(head[32:64]),
        "os": _UIMAGE_OS.get(os_id, str(os_id)),
        "arch": _UIMAGE_ARCH.get(arch, str(arch)),
        "type": _UIMAGE_TYPE.get(kind, str(kind)),
        "compression": _UIMAGE_COMP.get(comp, str(comp)),
        "load": load,
        "entry": entry,
        "payload_size": size,
    }
    return detail, 64 + size


def _v_android_boot(read: Reader, offset: int) -> tuple[dict, int | None] | None:
    head = read(offset, 1632)
    if len(head) < 80:
        return None
    kernel, _ka, ramdisk, _ra, second, _sa, _tags, page = struct.unpack("<8I", head[8:40])
    version = struct.unpack("<I", head[40:44])[0]
    if page not in (2048, 4096, 8192, 16384) or max(kernel, ramdisk, second) > MAX_CARVE or version > 4:
        return None
    pages = lambda n: (n + page - 1) // page  # noqa: E731
    total = page * (1 + pages(kernel) + pages(ramdisk) + pages(second))
    detail = {
        "header_version": version,
        "kernel_size": kernel,
        "ramdisk_size": ramdisk,
        "second_size": second,
        "page_size": page,
        "name": _printable(head[48:64]),
        "cmdline": _printable(head[64:576]),
    }
    return detail, total


def _v_dtb(read: Reader, offset: int) -> tuple[dict, int | None] | None:
    head = read(offset, 40)
    if len(head) < 40:
        return None
    total, _off_struct, _off_strings, _off_rsvmap, version = struct.unpack(">5I", head[4:24])
    if not 0x38 <= total <= 64 * 1024 * 1024 or not 16 <= version <= 17:
        return None
    return {"version": version, "total_size": total}, total


def _v_cpio(read: Reader, offset: int) -> tuple[dict, int | None] | None:
    head = read(offset, 110)
    if len(head) < 110 or not re.fullmatch(rb"[0-9a-fA-F]{104}", head[6:110]):
        return None
    name_size = int(head[94:102], 16)
    name = read(offset + 110, min(name_size, 100)).split(b"\x00", 1)[0].decode("ascii", "replace")
    return {"format": "newc", "first_entry": name}, None


def _v_elf(read: Reader, offset: int) -> tuple[dict, int | None] | None:
    head = read(offset, 20)
    if len(head) < 20 or head[4] not in (1, 2) or head[5] not in (1, 2) or head[6] != 1:
        return None
    order = "<" if head[5] == 1 else ">"
    machine = struct.unpack(order + "H", head[18:20])[0]
    names = {3: "x86", 8: "mips", 20: "ppc", 40: "arm", 62: "x86-64", 183: "arm64", 243: "riscv"}
    if machine not in names:
        return None
    return {"arch": names[machine], "bits": 64 if head[4] == 2 else 32}, None


# nama, magic, validator, termasuk "filesystem/kontainer"
SIGNATURES: tuple[tuple[str, bytes, Callable[[Reader, int], tuple[dict, int | None] | None]], ...] = (
    ("squashfs", b"hsqs", _v_squashfs),
    ("squashfs", b"sqsh", _v_squashfs),
    ("cramfs", b"\x45\x3d\xcd\x28", _v_cramfs),
    ("jffs2", b"\x85\x19", _v_jffs2),
    ("gzip", b"\x1f\x8b\x08", _v_compressed("gzip")),
    ("xz", b"\xfd7zXZ\x00", _v_compressed("xz")),
    ("bzip2", b"BZh", _v_compressed("bzip2")),
    ("lzma", b"\x5d\x00\x00", _v_compressed("lzma")),
    ("zip", b"PK\x03\x04", _v_zip),
    ("uimage", b"\x27\x05\x19\x56", _v_uimage),
    ("android-boot", b"ANDROID!", _v_android_boot),
    ("dtb", b"\xd0\x0d\xfe\xed", _v_dtb),
    ("cpio", b"070701", _v_cpio),
    ("elf", b"\x7fELF", _v_elf),
)
FILESYSTEMS = {"squashfs", "cramfs", "jffs2", "cpio"}
# Yang cukup kuat untuk membuat `rea id` menyebut berkas sebagai firmware (tanpa kompresi umum dan ELF).
STRONG = {"squashfs", "cramfs", "uimage", "android-boot", "dtb", "cpio"}


def _fixed_offset_hits(read: Reader, size: int) -> list[dict[str, Any]]:
    """Tanda yang posisinya tetap di awal berkas: ext2/3/4 (superblok di 1024), kernel arm64, zImage ARM, bzImage x86."""
    hits: list[dict[str, Any]] = []
    if size >= 1082 and read(1080, 2) == b"\x53\xef":
        hits.append({"offset": 0, "type": "ext", "detail": {"note": "superblok ext2/3/4 di 0x438"}, "size": None})
    if size >= 64 and read(56, 4) == b"ARM\x64":
        hits.append({"offset": 0, "type": "linux-image", "detail": {"arch": "arm64", "format": "Image"}, "size": None})
    if size >= 44 and read(36, 4) == b"\x18\x28\x6f\x01":
        hits.append({"offset": 0, "type": "linux-image", "detail": {"arch": "arm", "format": "zImage"}, "size": None})
    if size >= 518 and read(514, 4) == b"HdrS":
        hits.append({"offset": 0, "type": "linux-image", "detail": {"arch": "x86", "format": "bzImage"}, "size": None})
    return hits


def _scan_banners(path: str) -> tuple[dict[str, Any] | None, dict[str, Any] | None]:
    """Banner kernel (``Linux version ...``) dan bootloader dari byte mentah, streaming."""
    kernel: dict[str, Any] | None = None
    bootloader: dict[str, Any] | None = None
    with open(path, "rb") as handle:
        position = 0
        tail = b""
        while kernel is None or bootloader is None:
            chunk = handle.read(CHUNK)
            if not chunk:
                break
            data = tail + chunk
            base = position - len(tail)
            if kernel is None:
                match = _KERNEL_BANNER.search(data)
                if match:
                    kernel = {"version": match[1].decode("ascii", "replace"), "banner": _printable(data[match.start() : match.start() + 240]), "offset": base + match.start(), "in": "raw"}
            if bootloader is None:
                for name, pattern in _BOOTLOADERS:
                    match = pattern.search(data)
                    if match:
                        bootloader = {"name": name, "version": match[1].decode("ascii", "replace"), "offset": base + match.start()}
                        break
            position += len(chunk)
            tail = data[-OVERLAP:]
    return kernel, bootloader


def _decompress_bytes(kind: str, data: bytes, limit: int) -> bytes:
    """Dekompres aliran yang diawali ``data`` sampai ``limit`` byte; aliran terpotong atau rusak mengembalikan yang sudah terbaca."""
    out = bytearray()
    try:
        if kind == "gzip":
            engine = zlib.decompressobj(31)
        elif kind == "bzip2":
            engine = bz2.BZ2Decompressor()
        elif kind == "xz":
            engine = lzma.LZMADecompressor(format=lzma.FORMAT_XZ)
        else:
            engine = lzma.LZMADecompressor(format=lzma.FORMAT_ALONE)
        view = memoryview(data)
        for start in range(0, len(view), 1 << 20):
            out += engine.decompress(view[start : start + (1 << 20)], limit - len(out))
            if len(out) >= limit or getattr(engine, "eof", False):
                break
    except (zlib.error, lzma.LZMAError, OSError, ValueError, EOFError):
        pass
    return bytes(out[:limit])


def scan(path: str, max_hits: int = MAX_HITS) -> dict[str, Any]:
    """Memindai ``path``: temuan signature berformat valid, kernel, bootloader, dan filesystem yang ditemukan."""
    try:
        size = os.path.getsize(path)
    except OSError as error:
        raise ReaError(E_BAD_FORMAT, f"Tidak bisa membaca berkas: {error}", "") from error
    max_hits = max(1, min(max_hits, MAX_HITS))
    hits: list[dict[str, Any]] = []
    truncated = False
    with open(path, "rb") as handle:
        def read(offset: int, count: int) -> bytes:
            handle.seek(offset)
            return handle.read(count)

        hits += _fixed_offset_hits(read, size)
        seen: set[tuple[str, int]] = set()
        last_jffs2 = -JFFS2_MERGE_WINDOW
        position = 0
        tail_len = 0
        while position < size:
            handle.seek(position - tail_len)
            data = handle.read(CHUNK + tail_len)
            base = position - tail_len
            found: list[tuple[int, str, Callable]] = []
            for kind, magic, validator in SIGNATURES:
                start = 0
                while True:
                    at = data.find(magic, start)
                    if at < 0:
                        break
                    start = at + 1
                    # Temuan di area tumpang tindih sudah diperiksa pada putaran sebelumnya.
                    if base + at + len(magic) <= position and tail_len:
                        continue
                    found.append((base + at, kind, validator))
            for offset, kind, validator in sorted(found, key=lambda item: item[0]):
                if (kind, offset) in seen:
                    continue
                seen.add((kind, offset))
                if kind == "jffs2" and offset - last_jffs2 < JFFS2_MERGE_WINDOW:
                    for earlier in reversed(hits):
                        if earlier["type"] == "jffs2":
                            earlier["detail"]["nodes"] = earlier["detail"].get("nodes", 1) + 1
                            break
                    continue
                if kind == "elf" and offset == 0:
                    continue  # header berkas ELF itu sendiri, bukan ELF yang tertanam
                checked = validator(read, offset)
                if checked is None:
                    continue
                detail, item_size = checked
                if kind == "jffs2":
                    last_jffs2 = offset
                if len(hits) >= max_hits:
                    truncated = True
                    break
                hits.append({"offset": offset, "type": kind, "detail": detail, "size": item_size})
            if truncated:
                break
            position += CHUNK
            tail_len = OVERLAP
    hits.sort(key=lambda h: (h["offset"], h["type"]))
    kernel, bootloader = _scan_banners(path)
    if kernel is None:
        # Kernel biasanya dikompres: cari banner di dalam beberapa aliran terkompresi pertama.
        with open(path, "rb") as handle:
            for hit in [h for h in hits if h["type"] in ("gzip", "xz", "bzip2", "lzma")][:4]:
                handle.seek(hit["offset"])
                raw = _decompress_bytes(hit["type"], handle.read(CHUNK * 4), KERNEL_SCAN_DECOMPRESS)
                match = _KERNEL_BANNER.search(raw)
                if match:
                    kernel = {"version": match[1].decode("ascii", "replace"), "banner": _printable(raw[match.start() : match.start() + 240]), "offset": hit["offset"], "in": hit["type"]}
                    break
    filesystems = sorted({h["type"] for h in hits if h["type"] in FILESYSTEMS} | ({"ext"} if any(h["type"] == "ext" for h in hits) else set()))
    return {
        "size": size,
        "hits": hits,
        "truncated": truncated,
        "kernel": kernel,
        "bootloader": bootloader,
        "filesystems": filesystems,
        "firmware": bool(kernel or bootloader or filesystems or any(h["type"] in STRONG | {"linux-image"} for h in hits)),
    }


def looks_like_firmware(path: str, sample: int = 8 * 1024 * 1024, binary_only: bool = False) -> dict[str, Any] | None:
    """Pemeriksaan murah untuk ``rea id``: hanya tanda yang kuat dalam ``sample`` byte pertama. ``binary_only`` menolak banner teks."""
    try:
        with open(path, "rb") as handle:
            head = handle.read(sample)
    except OSError:
        return None
    if len(head) < 4096:
        return None

    def read(offset: int, count: int) -> bytes:
        return head[offset : offset + count]

    kinds: list[str] = []
    for kind, magic, validator in SIGNATURES:
        if kind not in STRONG:
            continue
        at = head.find(magic)
        # Tiga percobaan pertama per signature cukup; berkas besar penuh magic palsu tidak boleh membuat `rea id` lambat.
        for _ in range(3):
            if at < 0:
                break
            if validator(read, at) is not None:
                kinds.append(kind)
                break
            at = head.find(magic, at + 1)
    for hit in _fixed_offset_hits(read, len(head)):
        kinds.append(hit["type"])
    kernel = _KERNEL_BANNER.search(head)
    boot = next((name for name, pattern in _BOOTLOADERS if pattern.search(head)), None)
    if not kinds and (binary_only or (not kernel and not boot)):
        return None
    return {"signatures": sorted(set(kinds)), "kernel": kernel[1].decode("ascii", "replace") if kernel else None, "bootloader": boot}


# --- ekstraksi -------------------------------------------------------------------------------------------------------


def carve(path: str, offset: int, size: int | None, out_path: str, cap: int = MAX_CARVE) -> int:
    """Menyalin ``size`` byte (atau sampai akhir berkas) dari ``offset`` ke ``out_path``; mengembalikan jumlah byte."""
    total = os.path.getsize(path)
    length = min(size if size is not None else total - offset, total - offset, cap)
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(path, "rb") as source, open(out_path, "wb") as target:
        source.seek(offset)
        remaining = length
        while remaining > 0:
            block = source.read(min(1 << 20, remaining))
            if not block:
                break
            target.write(block)
            remaining -= len(block)
    return length - remaining


def _run_tool(command: list[str], cwd: str | None = None, stdin_path: str | None = None) -> tuple[bool, str]:
    stdin = open(stdin_path, "rb") if stdin_path else subprocess.DEVNULL
    try:
        done = subprocess.run(command, cwd=cwd, stdin=stdin, capture_output=True, text=True, timeout=TOOL_TIMEOUT, check=False)
    except subprocess.TimeoutExpired:
        return False, f"melewati batas waktu {TOOL_TIMEOUT} detik"
    except OSError as error:
        return False, str(error)
    finally:
        if stdin_path:
            stdin.close()
    return done.returncode == 0, (done.stderr or done.stdout).strip()[-300:]


def _tool_missing(binary: str, package: str) -> dict[str, Any]:
    return {"status": "tool_missing", "output": None, "detail": f"{binary} belum terpasang", "hint": f"Pasang: apt install {package}"}


def _extract_tree(kind: str, carved: str, folder: str) -> dict[str, Any]:
    os.makedirs(folder, exist_ok=True)
    if kind == "squashfs":
        tool = shutil.which("unsquashfs")
        if not tool:
            return _tool_missing("unsquashfs", "squashfs-tools")
        ok, message = _run_tool([tool, "-no-progress", "-no-xattrs", "-f", "-d", folder, carved])
    elif kind == "cramfs":
        tool = shutil.which("fsck.cramfs")
        if not tool:
            return _tool_missing("fsck.cramfs", "util-linux")
        shutil.rmtree(folder, ignore_errors=True)  # fsck.cramfs --extract menolak folder yang sudah ada
        ok, message = _run_tool([tool, f"--extract={folder}", carved])
    elif kind == "cpio":
        tool = shutil.which("cpio")
        if not tool:
            return _tool_missing("cpio", "cpio")
        ok, message = _run_tool([tool, "-idm", "--no-absolute-filenames", "--quiet"], cwd=folder, stdin_path=carved)
    elif kind == "jffs2":
        tool = shutil.which("jefferson")
        if not tool:
            return _tool_missing("jefferson", "python3-jefferson (atau pip install jefferson)")
        ok, message = _run_tool([tool, "-d", folder, carved])
    else:
        return {"status": "skipped", "output": None, "detail": f"{kind} belum didukung untuk ekstraksi", "hint": ""}
    return {"status": "ok" if ok else "failed", "output": folder, "detail": "" if ok else message, "hint": ""}


def _extract_compressed(kind: str, carved: str, target: str) -> dict[str, Any]:
    with open(carved, "rb") as handle:
        data = handle.read(MAX_DECOMPRESS + 1)
    out = _decompress_bytes(kind, data, MAX_DECOMPRESS)
    if not out:
        return {"status": "failed", "output": None, "detail": "dekompresi tidak menghasilkan data", "hint": ""}
    with open(target, "wb") as handle:
        handle.write(out)
    note = f"{len(out)} byte" + (" (dipotong di batas)" if len(out) >= MAX_DECOMPRESS else "")
    return {"status": "ok", "output": target, "detail": note, "hint": ""}


def _extract_zip(carved: str, folder: str) -> dict[str, Any]:
    os.makedirs(folder, exist_ok=True)
    root = os.path.realpath(folder)
    total = 0
    try:
        with zipfile.ZipFile(carved) as archive:
            for info in archive.infolist():
                destination = os.path.realpath(os.path.join(folder, info.filename))
                if not (destination == root or destination.startswith(root + os.sep)):
                    return {"status": "failed", "output": None, "detail": f"entri keluar dari folder: {info.filename[:80]}", "hint": ""}
                total += info.file_size
                if total > MAX_DECOMPRESS:
                    return {"status": "failed", "output": None, "detail": "ukuran terekstrak melewati batas", "hint": ""}
            archive.extractall(folder)
    except (zipfile.BadZipFile, OSError, RuntimeError, zlib.error, lzma.LZMAError, EOFError, NotImplementedError) as error:
        return {"status": "failed", "output": None, "detail": str(error)[:200], "hint": ""}
    return {"status": "ok", "output": folder, "detail": "", "hint": ""}


def _extract_android_boot(path: str, hit: dict[str, Any], folder: str) -> dict[str, Any]:
    detail = hit["detail"]
    page = detail["page_size"]
    pages = lambda n: (n + page - 1) // page  # noqa: E731
    os.makedirs(folder, exist_ok=True)
    start = hit["offset"] + page
    written = []
    for name, length in (("kernel", detail["kernel_size"]), ("ramdisk", detail["ramdisk_size"]), ("second", detail["second_size"])):
        if length:
            carve(path, start, length, os.path.join(folder, name))
            written.append(name)
        start += pages(length) * page
    return {"status": "ok", "output": folder, "detail": ", ".join(written), "hint": ""}


def extract(path: str, out_dir: str, hits: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Mengekstrak temuan yang punya extractor. Hasil per temuan: ``status`` ok, failed, tool_missing, atau skipped."""
    if os.path.exists(out_dir) and not os.path.isdir(out_dir):
        raise ReaError(E_USAGE, f"Bukan folder: {out_dir}", "Pilih folder keluaran lain dengan -o.")
    os.makedirs(out_dir, exist_ok=True)
    results: list[dict[str, Any]] = []
    for hit in hits:
        kind, offset = hit["type"], hit["offset"]
        label = f"{offset:08x}-{kind}"
        carved = os.path.join(out_dir, "_carved", label + ".bin")
        entry: dict[str, Any] = {"offset": offset, "type": kind}
        if kind in ("squashfs", "cramfs", "cpio", "jffs2", "zip"):
            carve(path, offset, hit.get("size"), carved)
            folder = os.path.join(out_dir, label)
            entry.update(_extract_zip(carved, folder) if kind == "zip" else _extract_tree(kind, carved, folder))
        elif kind in ("gzip", "xz", "bzip2", "lzma"):
            carve(path, offset, hit.get("size"), carved)
            entry.update(_extract_compressed(kind, carved, os.path.join(out_dir, label + ".out")))
        elif kind == "android-boot":
            entry.update(_extract_android_boot(path, hit, os.path.join(out_dir, label)))
        elif kind in ("uimage", "dtb"):
            size = hit.get("size")
            target = os.path.join(out_dir, label + (".bin" if kind == "uimage" else ".dtb"))
            if kind == "uimage" and size:
                carve(path, offset + 64, size - 64, target)
            else:
                carve(path, offset, size, target)
            entry.update({"status": "ok", "output": target, "detail": "", "hint": ""})
        else:
            entry.update({"status": "skipped", "output": None, "detail": "tidak ada extractor", "hint": ""})
        if os.path.isdir(os.path.join(out_dir, "_carved")) and kind not in ("uimage", "dtb"):
            try:
                os.remove(carved)
            except OSError:
                pass
        results.append(entry)
    try:
        os.rmdir(os.path.join(out_dir, "_carved"))
    except OSError:
        pass
    return results
