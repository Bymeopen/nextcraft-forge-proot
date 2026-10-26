"""Dekompilasi lewat alat eksternal (jadx, apktool) bila terpasang; tanpa alat: pesan jelas, bukan crash."""

from __future__ import annotations

import os
import shutil
import subprocess
import time
from typing import Any

from ..result import E_NOT_SUPPORTED, ReaError

E_TOOL_MISSING = "tool_missing"
E_TOOL_FAILED = "tool_failed"
TIMEOUT = 15 * 60
_HINTS = {
    "jadx": "apt install jadx, atau unduh dari https://github.com/skylot/jadx/releases lalu taruh di PATH.",
    "apktool": "apt install apktool, atau unduh apktool.jar dari https://apktool.org.",
}


def _command(tool: str, binary: str, source: str, out_dir: str) -> list[str]:
    if tool == "jadx":
        return [binary, "-d", out_dir, source]
    return [binary, "d", "-f", "-o", out_dir, source]


def available_tools() -> dict[str, str | None]:
    return {tool: shutil.which(tool) for tool in ("jadx", "apktool")}


def choose_tool(requested: str, source: str) -> str:
    """``auto``: jadx untuk Java/Kotlin yang terbaca, apktool bila hanya jadx tidak ada; selain itu sesuai permintaan."""
    tools = available_tools()
    if requested in ("jadx", "apktool"):
        if not tools[requested]:
            raise ReaError(E_TOOL_MISSING, f"{requested} tidak terpasang.", _HINTS[requested])
        return requested
    if requested != "auto":
        raise ReaError(E_NOT_SUPPORTED, f"Alat tidak dikenal: {requested}", "Pilih jadx, apktool, atau auto.")
    for tool in ("jadx", "apktool"):
        if tools[tool]:
            return tool
    raise ReaError(E_TOOL_MISSING, "Tidak ada dekompiler (jadx atau apktool) yang terpasang.", " ".join(_HINTS.values()))


def decompile(source: str, tool: str = "auto", out_dir: str | None = None, timeout: int = TIMEOUT) -> dict[str, Any]:
    if not os.path.isfile(source):
        raise ReaError("file_not_found", f"Berkas tidak ditemukan: {source}", "Periksa jalur.")
    chosen = choose_tool(tool, source)
    binary = shutil.which(chosen) or chosen
    target = out_dir or os.path.join(".rea", f"{os.path.basename(source)}.{chosen}")
    os.makedirs(target, exist_ok=True)
    started = time.monotonic()
    try:
        done = subprocess.run(
            _command(chosen, binary, source, target), capture_output=True, text=True, timeout=timeout, check=False
        )
    except subprocess.TimeoutExpired as error:
        raise ReaError(E_TOOL_FAILED, f"{chosen} melewati batas waktu {timeout} detik.", "Coba berkas lebih kecil atau jalankan alatnya langsung.") from error
    except OSError as error:
        raise ReaError(E_TOOL_FAILED, f"Tidak bisa menjalankan {chosen}: {error}", _HINTS[chosen]) from error
    files = sum(len(names) for _root, _dirs, names in os.walk(target))
    log_tail = (done.stdout + done.stderr).strip()[-1500:]
    # jadx keluar dengan kode ≠ 0 bila sebagian class gagal didekompilasi tetapi tetap menulis hasil; itu bukan kegagalan total.
    if done.returncode != 0 and files == 0:
        raise ReaError(E_TOOL_FAILED, f"{chosen} gagal (kode {done.returncode}).", log_tail or "Tidak ada keluaran dari alat.")
    return {
        "tool": chosen,
        "output_dir": target,
        "files": files,
        "returncode": done.returncode,
        "seconds": round(time.monotonic() - started, 1),
        "log_tail": log_tail,
        "partial": done.returncode != 0,
    }
