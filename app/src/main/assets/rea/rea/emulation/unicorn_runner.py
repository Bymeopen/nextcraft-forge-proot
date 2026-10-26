"""``rea emu``: menjalankan satu fungsi atau potongan kode di Unicorn, tanpa ptrace dan tanpa menjalankan program utuh.

Cocok untuk dekripsi string, hashing, dan algoritma kecil. Yang tidak dilakukan: relokasi, pustaka dinamis, dan syscall;
kode yang menyentuh memori tak terpetakan berhenti dengan alamat galatnya, bukan diam-diam salah.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from ..parsers.elf import function_symbols, open_elf
from ..result import E_BAD_FORMAT, E_MISSING_DEP, E_NOT_SUPPORTED, E_SYMBOL, E_USAGE, ReaError

PAGE = 0x1000
RAW_BASE = 0x10000000
ELF_PIE_BASE = 0x400000
STACK_TOP = 0x7FF00000
STACK_SIZE = 0x100000
RETURN_ADDR = 0xDEAD0000
DEFAULT_MAX_INSNS = 200_000
MAX_INSNS_LIMIT = 20_000_000
DEFAULT_TIMEOUT_MS = 10_000
MAX_DUMP = 64 * 1024
MAX_MEM_INPUT = 16 * 1024 * 1024

_ELF_MACHINE = {"x86_64": "x86_64", "386": "x86", "aarch64": "arm64", "arm": "arm"}
_GENERAL = {
    "arm64": [f"x{i}" for i in range(31)] + ["sp", "pc"],
    "arm": [f"r{i}" for i in range(13)] + ["sp", "lr", "pc"],
    "x86_64": ["rax", "rbx", "rcx", "rdx", "rsi", "rdi", "rbp", "rsp"] + [f"r{i}" for i in range(8, 16)] + ["rip"],
    "x86": ["eax", "ebx", "ecx", "edx", "esi", "edi", "ebp", "esp", "eip"],
}
_ALIASES = {
    "arm64": {"fp": "x29", "lr": "x30"},
    "arm": {"fp": "r11", "ip": "r12", "r13": "sp", "r14": "lr", "r15": "pc"},
    "x86_64": {"pc": "rip", "sp": "rsp"},
    "x86": {"pc": "eip", "sp": "esp"},
}
ARCHES = tuple(_GENERAL)


def _unicorn():
    try:
        import unicorn
        from unicorn import arm64_const, arm_const, x86_const
    except ImportError as error:
        raise ReaError(
            E_MISSING_DEP,
            "unicorn belum terpasang.",
            "Pasang: apt install python3-unicorn, atau pip install --break-system-packages unicorn",
        ) from error
    return unicorn, {"arm64": arm64_const, "arm": arm_const, "x86_64": x86_const, "x86": x86_const}


def normalize_arch(name: str) -> str:
    table = {"arm64": "arm64", "aarch64": "arm64", "arm": "arm", "x86_64": "x86_64", "x64": "x86_64", "amd64": "x86_64", "x86": "x86", "i386": "x86", "386": "x86"}
    arch = table.get(name.lower())
    if arch is None:
        raise ReaError(E_NOT_SUPPORTED, f"Arsitektur {name} belum didukung untuk emulasi.", f"Didukung: {', '.join(ARCHES)}.")
    return arch


def _align_down(value: int) -> int:
    return value & ~(PAGE - 1)


def _align_up(value: int) -> int:
    return (value + PAGE - 1) & ~(PAGE - 1)


@dataclass
class _Memory:
    """Pemetaan memori Unicorn yang mencatat rentang yang sudah ada, supaya pemetaan ulang tidak bentrok."""

    uc: Any
    perms: int
    ranges: list[tuple[int, int]] = field(default_factory=list)

    def ensure(self, address: int, size: int) -> None:
        start, end = _align_down(address), _align_up(address + max(size, 1))
        gaps = [(start, end)]
        for lo, hi in self.ranges:
            nxt: list[tuple[int, int]] = []
            for g_lo, g_hi in gaps:
                if hi <= g_lo or lo >= g_hi:
                    nxt.append((g_lo, g_hi))
                    continue
                if g_lo < lo:
                    nxt.append((g_lo, lo))
                if hi < g_hi:
                    nxt.append((hi, g_hi))
            gaps = nxt
        for g_lo, g_hi in gaps:
            self.uc.mem_map(g_lo, g_hi - g_lo, self.perms)
            self.ranges.append((g_lo, g_hi))

    def write(self, address: int, data: bytes) -> None:
        if data:
            self.ensure(address, len(data))
            self.uc.mem_write(address, data)


def parse_int(text: str) -> int:
    try:
        return int(text.strip(), 0)
    except ValueError as error:
        raise ReaError(E_USAGE, f"Bukan angka: {text!r}", "Pakai desimal atau heksadesimal (0x...).") from error


def _split_pair(spec: str, separator: str, what: str) -> tuple[str, str]:
    left, found, right = spec.partition(separator)
    if not found:
        raise ReaError(E_USAGE, f"Format {what} salah: {spec!r}", f"Bentuk yang benar: ALAMAT{separator}NILAI")
    return left, right


def parse_reg(spec: str) -> tuple[str, int]:
    name, value = _split_pair(spec, "=", "--reg")
    return name.strip().lower(), parse_int(value)


def parse_mem(spec: str) -> tuple[int, bytes]:
    address, hexdata = _split_pair(spec, ":", "--mem")
    cleaned = hexdata.replace(" ", "")
    try:
        data = bytes.fromhex(cleaned)
    except ValueError as error:
        raise ReaError(E_USAGE, f"Data --mem bukan heksadesimal: {hexdata!r}", "Contoh: --mem 0x600000:48656c6c6f") from error
    return parse_int(address), data


def parse_dump(spec: str) -> tuple[int, int]:
    address, length = _split_pair(spec, ":", "--dump")
    size = parse_int(length)
    if not 0 < size <= MAX_DUMP:
        raise ReaError(E_USAGE, f"Panjang --dump harus 1..{MAX_DUMP} byte.", "Ambil potongan lebih kecil.")
    return parse_int(address), size


def _elf_start(path: str, func: str | None, start: int | None) -> tuple[str, int, int, bool]:
    """Arsitektur, alamat awal (sudah ditambah bias PIE), bias, dan apakah Thumb; hanya membaca header dan simbol."""
    with open(path, "rb") as handle:
        elf = open_elf(handle)
        machine = str(elf.header["e_machine"]).replace("EM_", "").lower()
        arch = _ELF_MACHINE.get(machine)
        if arch is None:
            raise ReaError(E_NOT_SUPPORTED, f"Arsitektur ELF {machine} belum didukung untuk emulasi.", f"Didukung: {', '.join(ARCHES)}.")
        bias = ELF_PIE_BASE if elf.header["e_type"] == "ET_DYN" else 0
        if func:
            matches = [f for f in function_symbols(elf) if f["name"] == func]
            if not matches:
                raise ReaError(E_SYMBOL, f"Fungsi {func!r} tidak ada di ELF.", "Daftar fungsi: rea analyze <berkas> (bagian simbol).")
            begin = matches[0]["addr"]
        elif start is not None:
            begin = start
        else:
            raise ReaError(E_USAGE, "Tentukan fungsi (--func NAMA) atau alamat awal (--start ALAMAT).", "Menjalankan titik masuk ELF utuh tidak didukung: butuh syscall dan loader.")
    thumb = arch == "arm" and bool(begin & 1)
    return arch, (begin & ~1 if thumb else begin) + bias, bias, thumb


def _load_segments(path: str, mem: _Memory, bias: int) -> None:
    with open(path, "rb") as handle:
        elf = open_elf(handle)
        loaded = False
        for segment in elf.iter_segments():
            if segment["p_type"] != "PT_LOAD":
                continue
            vaddr = segment["p_vaddr"] + bias
            mem.ensure(vaddr, segment["p_memsz"])
            handle.seek(segment["p_offset"])
            mem.write(vaddr, handle.read(segment["p_filesz"]))
            loaded = True
        if not loaded:
            raise ReaError(E_BAD_FORMAT, "ELF tidak punya segmen PT_LOAD.", "Berkas ini bukan program yang bisa dimuat.")


def emulate(
    path: str,
    *,
    arch: str | None = None,
    func: str | None = None,
    start: int | None = None,
    end: int | None = None,
    base: int = RAW_BASE,
    regs: list[tuple[str, int]] | None = None,
    mems: list[tuple[int, bytes]] | None = None,
    dumps: list[tuple[int, int]] | None = None,
    max_insns: int = DEFAULT_MAX_INSNS,
    timeout_ms: int = DEFAULT_TIMEOUT_MS,
) -> dict[str, Any]:
    """Menjalankan kode dari ``path`` (ELF atau berkas mentah) dan mengembalikan register, dump memori, dan alasan berhenti."""
    unicorn, consts = _unicorn()
    max_insns = max(1, min(max_insns, MAX_INSNS_LIMIT))
    with open(path, "rb") as handle:
        magic = handle.read(4)
    is_elf = magic == b"\x7fELF"

    if not is_elf and arch is None:
        raise ReaError(E_USAGE, "Berkas mentah butuh --arch.", f"Pilihan: {', '.join(ARCHES)}.")

    thumb, bias = False, 0
    if is_elf:
        arch, begin, bias, thumb = _elf_start(path, func, start)
    arch = normalize_arch(arch or "")
    const = consts[arch]

    if arch == "arm64":
        uc = unicorn.Uc(unicorn.UC_ARCH_ARM64, unicorn.UC_MODE_ARM)
    elif arch == "arm":
        uc = unicorn.Uc(unicorn.UC_ARCH_ARM, unicorn.UC_MODE_THUMB if thumb else unicorn.UC_MODE_ARM)
    else:
        uc = unicorn.Uc(unicorn.UC_ARCH_X86, unicorn.UC_MODE_64 if arch == "x86_64" else unicorn.UC_MODE_32)
    mem = _Memory(uc, unicorn.UC_PROT_ALL)

    if is_elf:
        _load_segments(path, mem, bias)
    else:
        with open(path, "rb") as handle:
            code = handle.read(MAX_MEM_INPUT + 1)
        if len(code) > MAX_MEM_INPUT:
            raise ReaError(E_BAD_FORMAT, "Berkas kode mentah terlalu besar untuk emulasi.", "Potong ke fungsi yang dibutuhkan.")
        mem.write(base, code)
        begin = start if start is not None else base

    def reg(name: str) -> int:
        canonical = _ALIASES[arch].get(name, name)
        attr = f"UC_{'ARM64' if arch == 'arm64' else 'ARM' if arch == 'arm' else 'X86'}_REG_{canonical.upper()}"
        if canonical not in _GENERAL[arch] or not hasattr(const, attr):
            raise ReaError(E_USAGE, f"Register {name!r} tidak dikenal untuk {arch}.", f"Register: {', '.join(_GENERAL[arch])}.")
        return getattr(const, attr)

    # Tumpukan, lalu alamat kembali sentinel: emulasi berhenti saat fungsi kembali.
    mem.ensure(STACK_TOP - STACK_SIZE, STACK_SIZE)
    mem.ensure(RETURN_ADDR, PAGE)
    sp = STACK_TOP - 0x100
    until = end if end is not None else RETURN_ADDR
    if arch == "arm64":
        uc.reg_write(const.UC_ARM64_REG_SP, sp)
        uc.reg_write(const.UC_ARM64_REG_X30, RETURN_ADDR)
        try:  # izinkan instruksi FP/SIMD
            uc.reg_write(const.UC_ARM64_REG_CPACR_EL1, 0x300000)
        except Exception:  # noqa: BLE001 - tidak semua versi Unicorn menyediakan register ini
            pass
    elif arch == "arm":
        uc.reg_write(const.UC_ARM_REG_SP, sp)
        uc.reg_write(const.UC_ARM_REG_LR, RETURN_ADDR | (1 if thumb else 0))
    elif arch == "x86_64":
        uc.reg_write(const.UC_X86_REG_RSP, sp - 8)
        uc.mem_write(sp - 8, RETURN_ADDR.to_bytes(8, "little"))
    else:
        uc.reg_write(const.UC_X86_REG_ESP, sp - 4)
        uc.mem_write(sp - 4, RETURN_ADDR.to_bytes(4, "little"))

    for name, value in regs or []:
        uc.reg_write(reg(name), value)
    for address, data in mems or []:
        mem.write(address, data)

    counter = [0]
    uc.hook_add(unicorn.UC_HOOK_CODE, lambda _uc, _addr, _size, _data: counter.__setitem__(0, counter[0] + 1))
    stop, detail = "returned", ""
    try:
        uc.emu_start(begin | (1 if thumb else 0), until, timeout=timeout_ms * 1000, count=max_insns)
    except unicorn.UcError as error:
        stop, detail = "fault", str(error)
    pc_name = {"arm64": "pc", "arm": "pc", "x86_64": "rip", "x86": "eip"}[arch]
    pc = uc.reg_read(reg(pc_name))
    if stop != "fault":
        if (pc & ~1) == until:
            stop = "returned" if end is None else "reached_end"
        elif counter[0] >= max_insns:
            stop, detail = "max_insns", f"batas {max_insns} instruksi tercapai"
        else:
            stop, detail = "timeout", f"batas waktu {timeout_ms} ms tercapai"

    registers = {name: uc.reg_read(reg(name)) for name in _GENERAL[arch]}
    dumped = []
    for address, size in dumps or []:
        try:
            raw = bytes(uc.mem_read(address, size))
        except unicorn.UcError:
            dumped.append({"addr": address, "size": size, "hex": None, "ascii": None, "error": "memori tidak terpetakan"})
            continue
        dumped.append({"addr": address, "size": size, "hex": raw.hex(), "ascii": "".join(chr(b) if 32 <= b < 127 else "." for b in raw), "error": None})
    return {
        "arch": arch,
        "start": begin,
        "until": until,
        "stop": stop,
        "detail": detail,
        "instructions": counter[0],
        "pc": pc,
        "registers": registers,
        "dumps": dumped,
    }
