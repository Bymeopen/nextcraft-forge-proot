"""Progres belajar di ``~/.rea/progress.json``: ditulis atomik, dan berkas rusak tidak menghentikan pelajaran."""

from __future__ import annotations

import json
import os
import tempfile
import time
from typing import Any

VERSION = 1


def root_dir() -> str:
    return os.path.join(os.path.expanduser("~"), ".rea")


def progress_path() -> str:
    return os.path.join(root_dir(), "progress.json")


def _empty() -> dict[str, Any]:
    return {"version": VERSION, "lessons": {}}


def load() -> dict[str, Any]:
    """Membaca progres; berkas rusak dipindah ke ``progress.json.bad`` dan progres dimulai dari awal."""
    path = progress_path()
    try:
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
        if isinstance(data, dict) and isinstance(data.get("lessons"), dict):
            return data
    except FileNotFoundError:
        return _empty()
    except (OSError, ValueError):
        pass
    try:
        os.replace(path, path + ".bad")
    except OSError:
        pass
    return _empty()


def save(data: dict[str, Any]) -> None:
    os.makedirs(root_dir(), exist_ok=True)
    handle, temp = tempfile.mkstemp(prefix=".progress-", dir=root_dir())
    try:
        with os.fdopen(handle, "w", encoding="utf-8") as out:
            json.dump(data, out, ensure_ascii=False, indent=1)
        os.replace(temp, progress_path())
    except BaseException:
        try:
            os.unlink(temp)
        except OSError:
            pass
        raise


def entry(data: dict[str, Any], n: int) -> dict[str, Any]:
    return data["lessons"].setdefault(str(n), {"exercise_done": False, "attempts": 0, "answer_viewed": False, "quiz": None})


def now() -> int:
    return int(time.time())
