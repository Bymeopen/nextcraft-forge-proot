#!/usr/bin/env python3
"""Fails when scripts/runtime-bundles/{chroot-exec,unmount-chroot,proot-exec}.sh differ from the copies embedded in RuntimeSupervisorScripts.kt.

The app installs the embedded strings, so those are the source of truth; regenerate the files with
`python3 scripts/check-embedded-scripts.py --write` after changing them.
"""
import re
import sys
import textwrap
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RUNTIME_DIR = ROOT / "app/src/main/java/com/nextcraft/forge/runtime"
PAIRS = {
    "CHROOT_EXEC_SCRIPT": "chroot-exec.sh",
    "UNMOUNT_CHROOT_SCRIPT": "unmount-chroot.sh",
    "PROOT_EXEC_SCRIPT": "proot-exec.sh",
}


def embedded(source: str, name: str) -> str:
    match = re.search(rf'(?:private|internal)\s+val\s+{re.escape(name)}\s*=\s*"""', source)
    if not match:
        raise ValueError(f"{name} not found in runtime sources")
    start = match.end()
    end = source.index('""".trimIndent()', start)
    text = source[start:end].replace("${'$'}", "$")
    return textwrap.dedent(text).strip() + "\n"


def main() -> int:
    write = "--write" in sys.argv
    source = "\n".join(
        p.read_text()
        for p in sorted(RUNTIME_DIR.glob("Runtime*.kt"))
    )
    status = 0
    for name, filename in PAIRS.items():
        target = ROOT / "scripts/runtime-bundles" / filename
        expected = embedded(source, name)
        if write:
            target.write_text(expected)
        elif target.read_text().strip() != expected.strip():
            print(f"{filename} differs from {name} in RuntimeSupervisorScripts.kt (run with --write)")
            status = 1
    return status


sys.exit(main())
