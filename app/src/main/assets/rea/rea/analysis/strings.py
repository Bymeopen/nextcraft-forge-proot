"""Ekstraksi string ASCII dan UTF-16LE dari berkas (streaming lewat mmap) dengan skor "menarik"."""

from __future__ import annotations

import mmap
import re
from typing import Any

# A run is cut at 4096 characters: an unbounded run (a long printable section) was read whole into memory and scored.
_ASCII = re.compile(rb"[\x20-\x7e]{%d,4096}")
_UTF16 = re.compile(rb"(?:[\x20-\x7e]\x00){%d,4096}")
_URL = re.compile(r"\b(?:https?|ftp|wss?)://[^\s\"'<>]+", re.IGNORECASE)
_EMAIL = re.compile(r"\b[A-Za-z0-9.+-]{1,64}@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}\b")
_IPV4 = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")
_PATH = re.compile(r"^(?:/[\w.\-@+]+){2,}/?$|^[A-Za-z]:\\[\w\\. -]+$")
_HEXKEY = re.compile(r"\b[0-9a-fA-F]{32,}\b")
_B64KEY = re.compile(r"\b[A-Za-z0-9+/]{24,}={0,2}\b")
_FLAG = re.compile(r"\b[A-Za-z][A-Za-z0-9_]{1,12}\{[\x20-\x7e]{3,}\}")  # REA{...}, FLAG{...}, CTF{...}
_KEYWORDS = ("password", "passwd", "secret", "token", "api_key", "apikey", "private", "credential", "license", "serial")


def score(text: str) -> int:
    """Skor kasar seberapa menarik sebuah string untuk analis (0 = biasa saja)."""
    points = 0
    lowered = text.lower()
    if _URL.search(text):
        points += 5
    if _EMAIL.search(text):
        points += 4
    if _IPV4.search(text) and all(int(part) <= 255 for part in _IPV4.search(text).group(0).split(".")):
        points += 4
    if _PATH.match(text):
        points += 3
    if _HEXKEY.search(text) or (_B64KEY.search(text) and any(c.isdigit() for c in text) and any(c.isalpha() for c in text)):
        points += 4
    if _FLAG.search(text):
        points += 4
    if any(word in lowered for word in _KEYWORDS):
        points += 3
    if "%s" in text or "%d" in text or "%x" in text:
        points += 1
    if len(text) >= 24:
        points += 1
    return points


def extract(
    path: str,
    min_len: int = 4,
    max_items: int = 500,
    only_interesting: bool = False,
    include_utf16: bool = True,
) -> dict[str, Any]:
    """String dengan offset, encoding, dan skor. Berkas kosong atau tak terbaca menghasilkan daftar kosong."""
    min_len = max(2, min(min_len, 256))
    patterns = [("ascii", re.compile(_ASCII.pattern % min_len))]
    if include_utf16:
        patterns.append(("utf16le", re.compile(_UTF16.pattern % min_len)))
    found: list[dict[str, Any]] = []
    total = 0
    with open(path, "rb") as handle:
        try:
            view = mmap.mmap(handle.fileno(), 0, access=mmap.ACCESS_READ)
        except ValueError:  # berkas kosong
            return {"strings": [], "total": 0, "truncated": False, "min_len": min_len}
        try:
            for encoding, pattern in patterns:
                for match in pattern.finditer(view):
                    raw = match.group(0)
                    text = raw.decode("ascii") if encoding == "ascii" else raw.decode("utf-16-le")
                    points = score(text)
                    if only_interesting and points < 3:
                        continue
                    total += 1
                    if len(found) < max_items:
                        found.append({"offset": match.start(), "encoding": encoding, "text": text[:500], "score": points})
        finally:
            view.close()
    found.sort(key=lambda item: item["offset"])
    return {"strings": found, "total": total, "truncated": total > len(found), "min_len": min_len}
