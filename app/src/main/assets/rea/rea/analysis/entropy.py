"""Entropi per blok dan per section: tempat berkas yang mungkin dipak atau dienkripsi."""

from __future__ import annotations

from typing import Any

from ..identify import entropy_of

BLOCK = 4096
MAX_BLOCKS = 512
HIGH = 7.2


def entropy_map(path: str, block: int = BLOCK, max_blocks: int = MAX_BLOCKS) -> dict[str, Any]:
    """Entropi tiap ``block`` bayt; berkas besar diperbesar ukuran bloknya agar hasil tidak melebihi ``max_blocks``."""
    with open(path, "rb") as handle:
        handle.seek(0, 2)
        size = handle.tell()
        handle.seek(0)
        step = block
        if size > block * max_blocks:
            step = -(-size // max_blocks)
        blocks = []
        offset = 0
        while offset < size:
            chunk = handle.read(step)
            if not chunk:
                break
            blocks.append({"offset": offset, "size": len(chunk), "entropy": round(entropy_of(chunk), 3)})
            offset += len(chunk)
    high = [b for b in blocks if b["entropy"] >= HIGH]
    return {
        "block_size": step,
        "blocks": blocks,
        "high_blocks": len(high),
        "high_ratio": round(len(high) / len(blocks), 3) if blocks else 0.0,
        "spark": sparkline([b["entropy"] for b in blocks]),
    }


def section_entropy(path: str, sections: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Entropi tiap section yang punya isi di berkas (bukan NOBITS)."""
    rows = []
    with open(path, "rb") as handle:
        for section in sections:
            if section["type"] == "NOBITS" or section["size"] == 0 or not section["name"]:
                continue
            handle.seek(section["offset"])
            data = handle.read(min(section["size"], 8 * 1024 * 1024))
            value = round(entropy_of(data), 3)
            rows.append({"name": section["name"], "size": section["size"], "entropy": value, "high": value >= HIGH})
    return rows


def sparkline(values: list[float], width: int = 64) -> str:
    """Satu baris karakter blok untuk melihat bentuk entropi sekilas (0 sampai 8 bit)."""
    if not values:
        return ""
    bars = "▁▂▃▄▅▆▇█"
    if len(values) > width:
        per = -(-len(values) // width)
        values = [sum(values[i : i + per]) / len(values[i : i + per]) for i in range(0, len(values), per)]
    return "".join(bars[min(7, int(v / 8 * 8))] for v in values)
