"""Capability Detector (``rea doctor``): setiap kemampuan diuji sungguhan, bukan ditebak."""

from __future__ import annotations

import ctypes
import ctypes.util
import importlib.util
import os
import platform
import shutil
import signal
import subprocess
import sys
import time
from dataclasses import asdict, dataclass
from typing import Callable

OK, WARN, FAIL = "ok", "warn", "fail"

MODE_FULL = "full-dynamic"
MODE_EMULATED = "emulated-dynamic"
MODE_STATIC = "static-only"

_PTRACE_TRACEME = 0
_PTRACE_CONT = 7
_TEST_TIMEOUT = 5.0


@dataclass
class Check:
    id: str
    label: str
    status: str
    detail: str = ""
    hint: str = ""

    def as_dict(self) -> dict[str, str]:
        return asdict(self)


def _run(command: list[str], timeout: float = _TEST_TIMEOUT) -> tuple[int, str]:
    try:
        done = subprocess.run(command, capture_output=True, text=True, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        return -1, str(error)
    return done.returncode, (done.stdout + done.stderr).strip()


def environment_kind() -> str:
    """``chroot``, ``proot``, atau ``native``. ``TracerPid`` hanya membedakan proot; chroot dikenali dari / dan PID 1."""
    try:
        with open("/proc/self/status", encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("TracerPid:") and int(line.split()[1]) != 0:
                    return "proot"
    except (OSError, ValueError):
        pass
    try:
        root, init_root = os.stat("/"), os.stat("/proc/1/root/.")
        if (root.st_dev, root.st_ino) != (init_root.st_dev, init_root.st_ino):
            return "chroot"
    except OSError:
        pass
    return "native"


def check_environment() -> Check:
    kind = environment_kind()
    uid = os.getuid() if hasattr(os, "getuid") else -1
    detail = f"{kind}, uid={uid}, {platform.machine()}, kernel {platform.release()}"
    if kind == "proot":
        return Check("env", "Lingkungan", WARN, detail, "Di proot, ptrace tidak tersedia; pakai mode statis atau emulasi.")
    return Check("env", "Lingkungan", OK, detail)


def check_ptrace() -> Check:
    """Fork, ``PTRACE_TRACEME``, exec program kecil, lalu tunggu SIGTRAP: bukti nyata bahwa ptrace bekerja."""
    label = "ptrace"
    if not hasattr(os, "fork"):
        return Check("ptrace", label, FAIL, "os.fork tidak tersedia", "Butuh Linux.")
    libc_name = ctypes.util.find_library("c")
    if not libc_name:
        return Check("ptrace", label, FAIL, "libc tidak ditemukan")
    libc = ctypes.CDLL(libc_name, use_errno=True)
    libc.ptrace.restype = ctypes.c_long
    libc.ptrace.argtypes = [ctypes.c_long, ctypes.c_long, ctypes.c_void_p, ctypes.c_void_p]
    target = shutil.which("true") or "/bin/true"
    pid = os.fork()
    if pid == 0:
        try:
            if libc.ptrace(_PTRACE_TRACEME, 0, None, None) != 0:
                os._exit(42)
            os.execv(target, [target])
        finally:
            os._exit(43)
    deadline = time.monotonic() + _TEST_TIMEOUT
    status = 0
    result: Check | None = None
    while time.monotonic() < deadline:
        done, status = os.waitpid(pid, os.WNOHANG)
        if done == pid:
            break
        time.sleep(0.02)
    else:
        os.kill(pid, signal.SIGKILL)
        os.waitpid(pid, 0)
        return Check("ptrace", label, FAIL, "waktu habis menunggu proses uji", "ptrace mungkin diblokir seccomp.")
    if os.WIFSTOPPED(status) and os.WSTOPSIG(status) == signal.SIGTRAP:
        libc.ptrace(_PTRACE_CONT, pid, None, None)
        os.waitpid(pid, 0)
        result = Check("ptrace", label, OK, "PTRACE_TRACEME dan exec berhasil")
    elif os.WIFEXITED(status) and os.WEXITSTATUS(status) == 42:
        result = Check("ptrace", label, FAIL, "PTRACE_TRACEME ditolak", "Kernel atau seccomp memblokir ptrace; gunakan mode statis atau QEMU gdbstub.")
    else:
        result = Check("ptrace", label, FAIL, f"hasil tak terduga (status={status})", "Jalankan ulang; jika tetap, anggap ptrace tidak tersedia.")
    return result


def check_tool(tool_id: str, label: str, binary: str, version_args: list[str], hint: str, required: bool = False) -> Check:
    path = shutil.which(binary)
    if not path:
        return Check(tool_id, label, FAIL if required else WARN, "tidak terpasang", hint)
    code, output = _run([path, *version_args])
    first = output.splitlines()[0] if output else ""
    if code != 0 and not first:
        return Check(tool_id, label, WARN, f"{path} ada tetapi tidak bisa dijalankan", hint)
    return Check(tool_id, label, OK, first or path)


def check_python_module(module: str, label: str, hint: str, required: bool = False) -> Check:
    try:
        found = importlib.util.find_spec(module) is not None
    except (ImportError, ValueError):
        found = False
    if found:
        return Check(f"py_{module}", label, OK, "terpasang")
    return Check(f"py_{module}", label, FAIL if required else WARN, "tidak terpasang", hint)


def check_memory() -> Check:
    try:
        with open("/proc/meminfo", encoding="utf-8") as handle:
            fields = {line.split(":")[0]: int(line.split()[1]) for line in handle if ":" in line}
    except (OSError, ValueError, IndexError):
        return Check("memory", "Memori", WARN, "tidak bisa membaca /proc/meminfo")
    available_mb = fields.get("MemAvailable", 0) // 1024
    total_mb = fields.get("MemTotal", 0) // 1024
    detail = f"{available_mb} MB tersedia dari {total_mb} MB"
    if available_mb < 300:
        return Check("memory", "Memori", WARN, detail, "Memori sempit: jalankan satu analisis pada satu waktu dan tutup aplikasi lain.")
    return Check("memory", "Memori", OK, detail)


def check_disk(path: str = ".") -> Check:
    try:
        usage = shutil.disk_usage(path)
    except OSError:
        return Check("disk", "Ruang disk", WARN, "tidak bisa membaca")
    free_mb = usage.free // (1024 * 1024)
    detail = f"{free_mb} MB bebas"
    if free_mb < 500:
        return Check("disk", "Ruang disk", WARN, detail, "Kurang dari 500 MB; analisis berkas besar bisa gagal.")
    return Check("disk", "Ruang disk", OK, detail)


def determine_mode(checks: list[Check]) -> str:
    """Mode terbaik dari hasil uji: dinamis penuh butuh ptrace dan gdb; emulasi cukup QEMU atau Unicorn."""
    status = {c.id: c.status for c in checks}
    if status.get("ptrace") == OK and status.get("gdb") == OK:
        return MODE_FULL
    if status.get("qemu") == OK or status.get("py_unicorn") == OK:
        return MODE_EMULATED
    return MODE_STATIC


def run_doctor(workdir: str = ".", extra: list[Callable[[], Check]] | None = None) -> tuple[str, list[Check]]:
    py = f"Python {sys.version_info.major}.{sys.version_info.minor}"
    checks: list[Check] = [
        Check("python", "Python", OK if sys.version_info >= (3, 9) else FAIL, py, "Butuh Python 3.9 atau lebih baru."),
        check_environment(),
        check_ptrace(),
        check_tool("gdb", "gdb", "gdb", ["--version"], "apt install gdb"),
        check_tool("strace", "strace", "strace", ["-V"], "apt install strace"),
        check_tool("ltrace", "ltrace", "ltrace", ["--version"], "apt install ltrace"),
        check_tool("qemu", "qemu-aarch64", "qemu-aarch64", ["--version"], "apt install qemu-user"),
        check_tool("objdump", "objdump", "objdump", ["--version"], "apt install binutils"),
        check_tool("readelf", "readelf", "readelf", ["--version"], "apt install binutils"),
        check_tool("radare2", "radare2", "radare2", ["-v"], "Opsional; tidak selalu tersedia di Ubuntu arm64."),
        check_tool("jadx", "jadx", "jadx", ["--version"], "Opsional; untuk dekompilasi APK."),
        check_tool("apktool", "apktool", "apktool", ["--version"], "Opsional; untuk APK."),
        check_tool("binwalk", "binwalk", "binwalk", ["--help"], "Opsional; untuk firmware."),
        check_python_module("capstone", "capstone", "Dibutuhkan fase 2 (disassembly): pip install capstone"),
        check_python_module("elftools", "pyelftools", "Dibutuhkan fase 2 (parser ELF): pip install pyelftools"),
        check_python_module("lief", "lief", "Opsional: pip install lief"),
        check_python_module("unicorn", "unicorn", "Opsional (emulasi): pip install unicorn"),
        check_python_module("keystone", "keystone-engine", "Opsional: pip install keystone-engine"),
        check_python_module("frida", "frida", "Opsional (hook fungsi): pip install frida"),
        check_memory(),
        check_disk(workdir),
    ]
    for extra_check in extra or []:
        checks.append(extra_check())
    return determine_mode(checks), checks


def quick_mode() -> tuple[str, list[Check]]:
    """Mode dinamis tanpa uji penuh doctor: hanya ptrace, gdb, dan qemu (dipakai ``rea run --mode auto``)."""
    checks = [
        check_ptrace(),
        check_tool("gdb", "gdb", "gdb", ["--version"], "apt install gdb"),
        check_tool("qemu", "qemu-aarch64", "qemu-aarch64", ["--version"], "apt install qemu-user"),
    ]
    return determine_mode(checks), checks
