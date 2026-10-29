#!/usr/bin/env python3
"""List which arm64 syscall numbers an ELF can issue (static scan of `mov x8, #N; svc #0`), without running it.

  scan-syscall-numbers.py <elf> [<elf> ...]

Used on a Core bundle's libc.so.6 and ld.so to check that glibc-android-syscalls.py really removed the syscalls Android's
seccomp filter refuses. Needs `pip install capstone`. Exit status 1 when a watched number is present.
"""
import re
import struct
import sys

from capstone import CS_ARCH_ARM64, CS_MODE_ARM, Cs

# Refused by Android (device probe + AOSP lists) and removed by the glibc patch.
WATCHED = {99: "set_robust_list", 293: "rseq", 437: "openat2", 439: "faccessat2", 452: "fchmodat2"}


def scan(path: str) -> set[int]:
    d = open(path, "rb").read()
    phoff = struct.unpack_from("<Q", d, 32)[0]
    phsz, phnum = struct.unpack_from("<HH", d, 54)
    md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    md.skipdata = True
    found: set[int] = set()
    for i in range(phnum):
        p_type, p_flags, off, va, _, filesz, _, _ = struct.unpack_from("<IIQQQQQQ", d, phoff + i * phsz)
        if p_type != 1 or not p_flags & 1:  # PT_LOAD, executable
            continue
        window = []
        for ins in md.disasm(d[off:off + filesz], va):
            window = (window + [ins])[-14:]
            if ins.mnemonic != "svc":
                continue
            for w in reversed(window[:-1]):
                if w.mnemonic in ("mov", "movz") and w.op_str.split(",")[0] in ("x8", "w8"):
                    m = re.search(r"#(0x[0-9a-f]+|\d+)", w.op_str)
                    if m:
                        found.add(int(m.group(1), 0))
                    break
    return found


def main(paths: list[str]) -> int:
    bad = 0
    for p in paths:
        found = scan(p)
        hit = sorted(n for n in found if n in WATCHED)
        print(f"{p}: {len(found)} syscall numbers; watched present: "
              f"{', '.join(f'{WATCHED[n]}({n})' for n in hit) or 'none'}")
        bad |= bool(hit)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]) if len(sys.argv) > 1 else 2)
