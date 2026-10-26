"""Dekorator dan registri plugin. Modul ini sengaja tidak mengimpor bagian REA lain, supaya ``import rea`` tetap ringan."""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any, Callable

HOOKS = ("on_identify", "on_parse", "on_analyze", "on_report")
_NAME = re.compile(r"[a-z0-9][a-z0-9_-]{0,39}")


@dataclass
class Plugin:
    name: str
    formats: tuple[str, ...]
    instance: Any
    hooks: tuple[str, ...]
    source: str = ""
    builtin: bool = False

    def handles(self, fmt: str) -> bool:
        return "*" in self.formats or fmt.lower() in {f.lower() for f in self.formats}

    def as_dict(self) -> dict[str, Any]:
        return {"name": self.name, "formats": list(self.formats), "hooks": list(self.hooks), "source": self.source, "builtin": self.builtin}


_registry: list[Plugin] = []
_loading_source: list[str] = [""]


def plugin(name: str, formats: list[str] | tuple[str, ...] = ("*",)) -> Callable[[Any], Any]:
    """Mendaftarkan kelas (atau objek) yang punya metode hook. ``formats`` memakai tipe dari ``rea id`` (``ELF``, ``APK``, ...), ``*`` untuk semua."""
    if not isinstance(name, str) or not _NAME.fullmatch(name):
        raise ValueError(f"Nama plugin harus huruf kecil, angka, - atau _ (1-40 karakter): {name!r}")
    formats_tuple = tuple(str(f) for f in formats) or ("*",)

    def register(target: Any) -> Any:
        instance = target() if isinstance(target, type) else target
        hooks = tuple(h for h in HOOKS if callable(getattr(instance, h, None)))
        if not hooks:
            raise ValueError(f"Plugin {name!r} tidak punya hook ({', '.join(HOOKS)})")
        if any(p.name == name for p in _registry):
            raise ValueError(f"Nama plugin sudah dipakai: {name!r}")
        _registry.append(Plugin(name=name, formats=formats_tuple, instance=instance, hooks=hooks, source=_loading_source[0]))
        return target

    return register


def registry() -> list[Plugin]:
    return list(_registry)


def reset() -> None:
    _registry.clear()


def set_loading_source(path: str) -> None:
    _loading_source[0] = path
