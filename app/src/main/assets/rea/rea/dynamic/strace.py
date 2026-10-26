"""``rea run --mode strace``: jejak syscall lewat strace, diurai menjadi peristiwa, ringkasan, berkas yang dibuka, dan exec."""

from __future__ import annotations

import os
import re
import tempfile
from collections import Counter
from typing import Any

from ..result import E_USAGE, ReaError
from .proc import raise_if_ptrace_denied, require_tool, run_limited

DEFAULT_TIMEOUT = 20.0
MAX_EVENTS = 1000
MAX_FILES = 100
_FILTER = re.compile(r"[A-Za-z0-9_,=!%:.\-]{1,200}")
_LINE = re.compile(
    r"^(?P<pid>\d+)\s+(?P<name>[a-z_0-9]+)\((?P<args>.*)\)\s+=\s+(?P<ret>-?\d+|\?|0x[0-9a-f]+)"
    r"(?:\s+(?P<errno>E[A-Z0-9]+)\s+\((?P<msg>[^)]*)\))?"
)
_EXIT = re.compile(r"\+\+\+ (exited with (?P<code>\d+)|killed by (?P<sig>SIG[A-Z0-9]+)).*\+\+\+")
_QUOTED = re.compile(r'"((?:[^"\\]|\\.)*)"')


def parse_trace(text: str) -> dict[str, Any]:
    """Memecah keluaran strace (``-f``) menjadi peristiwa. Baris ``unfinished``/``resumed`` dihitung tetapi tidak diurai."""
    events: list[dict[str, Any]] = []
    counts: Counter[str] = Counter()
    errors: Counter[str] = Counter()
    files: list[str] = []
    execs: list[str] = []
    exit_info: dict[str, Any] | None = None
    unparsed = 0
    for line in text.splitlines():
        done = _EXIT.search(line)
        if done:
            exit_info = {"code": int(done["code"]) if done["code"] is not None else None, "signal": done["sig"]}
            continue
        match = _LINE.match(line)
        if not match:
            if line.strip() and not line.lstrip("0123456789 ").startswith(("---", "+++")):
                unparsed += 1
            continue
        name = match["name"]
        counts[name] += 1
        failed = match["errno"] is not None
        if failed:
            errors[match["errno"]] += 1
        first = _QUOTED.search(match["args"])
        if name in ("open", "openat", "openat2", "creat", "stat", "newfstatat", "access", "faccessat") and first and not failed:
            if first[1] not in files and len(files) < MAX_FILES:
                files.append(first[1])
        if name in ("execve", "execveat") and first:
            execs.append(first[1])
        if len(events) < MAX_EVENTS:
            events.append(
                {
                    "pid": int(match["pid"]),
                    "name": name,
                    "args": match["args"][:300],
                    "ret": match["ret"],
                    "errno": match["errno"],
                }
            )
    return {
        "events": events,
        "total": sum(counts.values()),
        "truncated": sum(counts.values()) > len(events),
        "summary": [{"name": n, "count": c} for n, c in counts.most_common(30)],
        "errors": dict(errors),
        "files": files,
        "execs": execs,
        "exit": exit_info,
        "unparsed_lines": unparsed,
    }


def trace(path: str, program_args: list[str], trace_filter: str | None = None, timeout: float = DEFAULT_TIMEOUT) -> dict[str, Any]:
    strace = require_tool("strace", "strace")
    command = [strace, "-f", "-q", "-s", "96"]
    if trace_filter:
        if not _FILTER.fullmatch(trace_filter):
            raise ReaError(E_USAGE, f"Filter syscall tidak valid: {trace_filter!r}", "Contoh: --trace file, --trace network, --trace openat,read")
        command += ["-e", f"trace={trace_filter}"]
    with tempfile.TemporaryDirectory(prefix="rea-strace-") as directory:
        log = os.path.join(directory, "trace.txt")
        done = run_limited([*command, "-o", log, os.path.abspath(path), *program_args], timeout)
        text = ""
        if os.path.exists(log):
            with open(log, encoding="utf-8", errors="replace") as handle:
                text = handle.read(8 * 1024 * 1024)
    raise_if_ptrace_denied("strace", done.stderr + text[:500])
    parsed = parse_trace(text)
    parsed.update(
        {
            "mode": "strace",
            "timed_out": done.timed_out,
            "program_output": done.stdout,
            "program_error": done.stderr[:2000],
            "filter": trace_filter,
        }
    )
    return parsed
