#!/usr/bin/env python3
"""Print the arm64 syscall numbers Android's app seccomp filter allows, one per line (input for android-seccomp-run).

Names come from audit-android-seccomp.py (AOSP's SYSCALLS.TXT and the allow/block lists); numbers come from the host's own
<sys/syscall.h>, so run it on an ARM64 Linux host with gcc.
"""
import importlib.util
import os
import subprocess
import sys
import tempfile

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("audit", os.path.join(here, "audit-android-seccomp.py"))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)

names = sorted(audit.allowlist())
src = "#include <stdio.h>\n#include <sys/syscall.h>\nint main(void){\n"
for n in names:
    src += f'#ifdef SYS_{n}\nprintf("%d {n}\\n", SYS_{n});\n#else\nprintf("-1 {n}\\n");\n#endif\n'
src += "return 0;}\n"
with tempfile.TemporaryDirectory() as d:
    c, exe = os.path.join(d, "n.c"), os.path.join(d, "n")
    open(c, "w").write(src)
    subprocess.run(["gcc", "-o", exe, c], check=True)
    out = subprocess.run([exe], check=True, capture_output=True, text=True).stdout.splitlines()
numbers, missing = [], []
for line in out:
    num, name = line.split()
    (numbers if int(num) >= 0 else missing).append(int(num) if int(num) >= 0 else name)
if missing:
    print(f"no number on this host for: {' '.join(missing)}", file=sys.stderr)
print(f"{len(numbers)} allowed syscalls", file=sys.stderr)
for n in sorted(set(numbers)):
    print(n)
