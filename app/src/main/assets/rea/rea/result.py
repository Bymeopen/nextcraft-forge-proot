"""Kontrak keluaran: setiap perintah ``--json`` mencetak satu objek JSON di baris terakhir stdout."""

from __future__ import annotations

import json
import sys
from dataclasses import dataclass, field
from typing import Any

from . import SCHEMA

# Kode galat yang dikenal aplikasi; tambahkan di sini, jangan mengarang kode di tempat lain.
E_FILE_NOT_FOUND = "file_not_found"
E_NOT_A_FILE = "not_a_file"
E_PERMISSION = "permission_denied"
E_USAGE = "usage"
E_MISSING_DEP = "missing_dependency"
E_NOT_SUPPORTED = "not_supported"
E_SYMBOL = "symbol_not_found"
E_BAD_FORMAT = "bad_format"
E_INTERNAL = "internal"


class ReaError(Exception):
    """Galat yang diharapkan: punya kode tetap, pesan, dan saran perbaikan."""

    def __init__(self, code: str, message: str, hint: str = "") -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.hint = hint

    def as_dict(self) -> dict[str, str]:
        return {"code": self.code, "message": self.message, "hint": self.hint}


@dataclass
class Result:
    command: str
    ok: bool = True
    data: dict[str, Any] = field(default_factory=dict)
    mode: str | None = None
    warnings: list[str] = field(default_factory=list)
    error: dict[str, str] | None = None

    def as_dict(self) -> dict[str, Any]:
        out: dict[str, Any] = {
            "schema": SCHEMA,
            "command": self.command,
            "ok": self.ok,
            "data": self.data,
            "warnings": self.warnings,
            "error": self.error,
        }
        if self.mode is not None:
            out["mode"] = self.mode
        return out


def failure(command: str, error: ReaError) -> Result:
    return Result(command=command, ok=False, error=error.as_dict())


def to_json_line(result: Result) -> str:
    """Satu baris JSON tanpa newline di dalamnya, supaya mudah diambil dari baris terakhir."""
    return json.dumps(result.as_dict(), ensure_ascii=False, separators=(",", ":"))


def emit_json(result: Result, stream: Any = None) -> None:
    out = stream if stream is not None else sys.stdout
    out.write(to_json_line(result) + "\n")
    out.flush()
