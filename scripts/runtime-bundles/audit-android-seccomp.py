#!/usr/bin/env python3
"""Fail when a program uses a syscall that Android's per-app seccomp filter refuses (SIGSYS).

Input: one or more `strace -o` logs (strace's default format, optional leading pid). The allowlist is computed the way
bionic's genseccomp does: SYSCALLS.TXT - SECCOMP_BLOCKLIST_{APP,COMMON}.TXT + SECCOMP_ALLOWLIST_{APP,COMMON}.TXT,
fetched from AOSP (arm64 only). Measured against a real device in docs/RUNTIME_KNOWN_ISSUES.md, so this is a guard, not
a proof: a device may block more than AOSP does.

  audit-android-seccomp.py proot.strace [more.strace ...]
"""
import base64
import re
import sys
import urllib.request

BASE = "https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/"
LINE = re.compile(r"(?:([\w|]+):)?([\w|]+)\(.*\)\s+(\S+)\s*$")


def fetch(name: str) -> list[str]:
    with urllib.request.urlopen(BASE + name + "?format=TEXT", timeout=60) as r:
        return base64.b64decode(r.read()).decode().splitlines()


def names(lines: list[str]) -> set[str]:
    out = set()
    for raw in lines:
        m = LINE.match(raw.split("#")[0].strip())
        if not m:
            continue
        # The last column lists architectures: "all", "lp64", "lp32", or a comma list such as "arm,x86,arm64,x86_64".
        if not {"all", "lp64", "arm64"} & set(m.group(3).split(",")):
            continue
        # "func|alias:syscall(args)" names the syscall after the colon; without a colon it is the first function name.
        out.add(m.group(2) if m.group(1) else m.group(2).split("|")[0])
    return out


def allowlist() -> set[str]:
    f = names
    return (
        (f(fetch("SYSCALLS.TXT")) - f(fetch("SECCOMP_BLOCKLIST_APP.TXT")) - f(fetch("SECCOMP_BLOCKLIST_COMMON.TXT")))
        | f(fetch("SECCOMP_ALLOWLIST_APP.TXT"))
        | f(fetch("SECCOMP_ALLOWLIST_COMMON.TXT"))
    )


def main(paths: list[str]) -> int:
    allowed = allowlist()
    # Sanity: a broken parse would flag everything, or nothing.
    for must in ("openat", "brk", "mmap", "futex", "clone", "exit_group", "newfstatat", "renameat"):
        assert must in allowed, f"allowlist parse is broken: {must} missing ({len(allowed)} entries)"
    used: dict[str, tuple[str, str]] = {}
    for p in paths:
        for line in open(p, errors="replace"):
            m = re.match(r"^(?:\d+\s+)?(?:\[pid\s+\d+\]\s+)?([a-z_0-9]+)\(", line)
            if m:
                used.setdefault(m.group(1), (p, line.strip()[:150]))
    bad = {k: v for k, v in used.items() if k not in allowed}
    print(f"allowlist: {len(allowed)} syscalls; seen in logs: {len(used)}")
    for k in sorted(bad):
        print(f"NOT ALLOWED by Android: {k} (first seen in {bad[k][0].split('/')[-1]})\n    {bad[k][1]}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]) if len(sys.argv) > 1 else 2)
