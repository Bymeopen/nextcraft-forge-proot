"""Menjalankan alat dinamis (strace, gdb) sebagai proses anak dengan batas waktu dan keluaran dipotong."""

from __future__ import annotations

import os
import shutil
import signal
import subprocess
import threading
from dataclasses import dataclass

from ..result import E_MISSING_DEP, E_NOT_SUPPORTED, ReaError

MAX_OUTPUT = 64 * 1024
_PTRACE_DENIED = ("operation not permitted", "ptrace: ", "ptrace(ptrace_", "permission denied")


@dataclass
class Completed:
    code: int | None
    stdout: str
    stderr: str
    timed_out: bool


def require_tool(binary: str, package: str) -> str:
    path = shutil.which(binary)
    if path is None:
        raise ReaError(E_MISSING_DEP, f"{binary} belum terpasang.", f"Pasang: apt install {package}")
    return path


class StreamDrain:
    """Membaca pipa di thread sendiri dan menyimpan hanya ``limit`` byte pertama; sisanya dibuang.

    Tanpa ini program yang mencetak terus-menerus memenuhi pipa (proses anak macet) atau, lewat ``communicate()``,
    memenuhi memori: 2 detik ``yes`` sudah menghabiskan sekitar 1,7 GB.
    """

    def __init__(self, stream, limit: int = MAX_OUTPUT) -> None:
        self._stream = stream
        self._limit = limit
        self._parts: list[bytes] = []
        self._kept = 0
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def _run(self) -> None:
        try:
            while True:
                chunk = self._stream.read1(65536) if hasattr(self._stream, "read1") else self._stream.read(65536)
                if not chunk:
                    return
                room = self._limit - self._kept
                if room > 0:
                    self._parts.append(chunk[:room])
                    self._kept += min(len(chunk), room)
        except (OSError, ValueError):
            return  # pipa ditutup di tengah pembacaan

    def text(self, wait: float = 2.0) -> str:
        self._thread.join(wait)
        return b"".join(self._parts).decode("utf-8", "replace")


def run_limited(command: list[str], timeout: float, cwd: str | None = None) -> Completed:
    """Menjalankan ``command`` di grup proses sendiri; saat batas waktu habis seluruh grup dihentikan."""
    process = subprocess.Popen(
        command,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        cwd=cwd,
        start_new_session=True,
    )
    out_drain = StreamDrain(process.stdout)
    err_drain = StreamDrain(process.stderr)
    timed_out = False
    try:
        process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait()
    return Completed(
        None if timed_out else process.returncode,
        out_drain.text(),
        err_drain.text(),
        timed_out,
    )


def raise_if_ptrace_denied(tool: str, text: str) -> None:
    """Kernel atau seccomp yang menolak ptrace dijelaskan dengan alternatifnya, bukan sebagai galat mentah."""
    lowered = text.lower()
    if "operation not permitted" in lowered and ("ptrace" in lowered or "seize" in lowered or "traceme" in lowered):
        raise ReaError(
            E_NOT_SUPPORTED,
            f"{tool} tidak bisa memakai ptrace di lingkungan ini (ditolak).",
            "Pakai --mode emu (QEMU gdbstub), yang tidak butuh ptrace; jalankan `rea doctor` untuk melihat mode yang tersedia.",
        )
