"""Memuat plugin dari ``~/.rea/plugins/`` dan dari paket (``rea/plugins/builtin``), serta menjalankan hook dengan aman.

Plugin adalah kode Python biasa dan berjalan dengan hak yang sama dengan REA: pasang hanya plugin yang kamu percayai.
``REA_NO_PLUGINS=1`` atau ``rea --no-plugins`` mematikan semuanya. Satu plugin yang rusak, lambat, atau melempar galat
tidak menghentikan perintah: hasilnya menjadi peringatan.
"""

from __future__ import annotations

import importlib.util
import json
import os
import signal
import sys
from typing import Any

from . import api

HOOK_TIMEOUT = 5.0
MAX_RESULT_BYTES = 64 * 1024
MAX_PLUGIN_FILES = 50


def user_dir() -> str:
    return os.path.join(os.path.expanduser("~"), ".rea", "plugins")


def builtin_dir() -> str:
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), "builtin")


def disabled() -> bool:
    return os.environ.get("REA_NO_PLUGINS", "") not in ("", "0")


def _import_file(path: str, module_name: str) -> None:
    spec = importlib.util.spec_from_file_location(module_name, path)
    if spec is None or spec.loader is None:
        raise ImportError("bukan modul Python")
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    try:
        spec.loader.exec_module(module)
    except BaseException:
        sys.modules.pop(module_name, None)
        raise


def load_plugins(directories: list[str] | None = None) -> tuple[list[api.Plugin], list[dict[str, str]]]:
    """Memuat ulang semua plugin. Mengembalikan (plugin yang berhasil, galat per berkas)."""
    api.reset()
    errors: list[dict[str, str]] = []
    if disabled():
        return [], errors
    builtin = builtin_dir()
    dirs = directories if directories is not None else [builtin, user_dir()]
    for directory in dirs:
        try:
            names = sorted(n for n in os.listdir(directory) if n.endswith(".py") and not n.startswith("_"))
        except OSError:
            continue
        for name in names[:MAX_PLUGIN_FILES]:
            path = os.path.join(directory, name)
            before = len(api.registry())
            api.set_loading_source(path)
            try:
                _import_file(path, "rea_plugin_" + name[:-3].replace("-", "_"))
            except Exception as error:  # noqa: BLE001 - plugin pihak ketiga: apa pun galatnya, jangan jatuhkan REA
                errors.append({"source": path, "error": f"{type(error).__name__}: {error}"[:300]})
                del api._registry[before:]  # plugin yang gagal di tengah berkas tidak boleh setengah terdaftar
            finally:
                api.set_loading_source("")
            for plugin in api.registry()[before:]:
                plugin.builtin = os.path.abspath(directory) == os.path.abspath(builtin)
    return api.registry(), errors


class _HookTimeout(Exception):
    pass


def _call_with_timeout(function: Any, args: tuple, timeout: float) -> Any:
    """Batas waktu lewat SIGALRM bila bisa (hanya thread utama di Unix); selain itu tanpa batas."""
    if not hasattr(signal, "setitimer") or not _in_main_thread():
        return function(*args)

    def on_alarm(_signum: int, _frame: Any) -> None:
        raise _HookTimeout()

    previous = signal.signal(signal.SIGALRM, on_alarm)
    signal.setitimer(signal.ITIMER_REAL, timeout)
    try:
        return function(*args)
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous)


def _in_main_thread() -> bool:
    import threading

    return threading.current_thread() is threading.main_thread()


def _clean(value: Any) -> Any:
    """Hasil plugin harus bisa menjadi JSON dan tidak boleh sangat besar."""
    text = json.dumps(value, default=str, ensure_ascii=False)
    if len(text.encode("utf-8")) > MAX_RESULT_BYTES:
        return {"truncated": True, "note": f"hasil plugin lebih dari {MAX_RESULT_BYTES} byte dibuang"}
    return json.loads(text)


def run_hook(plugins: list[api.Plugin], hook: str, fmt: str, *args: Any, timeout: float = HOOK_TIMEOUT) -> tuple[dict[str, Any], list[str]]:
    """Menjalankan ``hook`` di setiap plugin yang menangani ``fmt``. Mengembalikan ({nama: hasil}, peringatan)."""
    results: dict[str, Any] = {}
    warnings: list[str] = []
    for plugin in plugins:
        if hook not in plugin.hooks or not plugin.handles(fmt):
            continue
        method = getattr(plugin.instance, hook)
        try:
            value = _call_with_timeout(method, args, timeout)
        except _HookTimeout:
            warnings.append(f"Plugin {plugin.name}: {hook} melewati batas waktu {timeout:g} detik.")
            continue
        except Exception as error:  # noqa: BLE001
            warnings.append(f"Plugin {plugin.name}: {hook} gagal ({type(error).__name__}: {str(error)[:150]}).")
            continue
        if value is not None:
            results[plugin.name] = _clean(value)
    return results, warnings


TEMPLATE = '''"""Plugin REA: {name}. Simpan di ~/.rea/plugins/ ; lihat `rea plugins list`."""

import rea


@rea.plugin("{name}", formats=["ELF"])  # tipe dari `rea id`; ["*"] untuk semua berkas
class {klass}:
    def on_identify(self, info):
        """Dipanggil setelah `rea id`. Kembalikan dict untuk ditampilkan di data.plugins.{name}."""
        return {{"ukuran": info.get("size")}}

    def on_analyze(self, path, data):
        """Dipanggil di akhir `rea analyze`; `data` berisi hasil (identify, elf, strings, ...)."""
        functions = (data.get("elf") or {{}}).get("functions", [])
        return {{"jumlah_fungsi": len(functions)}}

    def on_report(self, data, markdown):
        """Kembalikan teks Markdown yang ditambahkan sebagai bagian di `rea report`."""
        return None
'''
