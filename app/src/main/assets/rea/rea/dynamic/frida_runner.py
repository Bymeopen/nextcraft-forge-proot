"""``rea run --mode frida``: gagang fungsi (hook) dengan Frida, mencatat setiap panggilan, argumen, dan nilai kembali.

Frida opsional: tanpa modul ``frida`` perintah gagal dengan petunjuk pemasangan. Program dijalankan lewat ``spawn`` dengan
stdout ditangkap (``stdio=pipe``) supaya tidak bercampur dengan keluaran JSON REA.
"""

from __future__ import annotations

import json
import re
import threading
import time
from typing import Any

from ..result import E_MISSING_DEP, E_NOT_SUPPORTED, E_USAGE, ReaError

DEFAULT_TIMEOUT = 15.0
MAX_MESSAGES = 500
MAX_HOOKS = 20
_SPEC = re.compile(r"(0x[0-9a-fA-F]{1,16}|[A-Za-z_][A-Za-z0-9_.@$]{0,120})")

# Skrip diselipkan lewat JSON.parse, jadi nama hook tidak pernah menjadi kode JavaScript.
_SCRIPT = r"""
var specs = JSON.parse(%SPECS%);
function resolve(spec) {
  if (/^0x/i.test(spec)) return ptr(spec);
  var address = null;
  if (typeof Module.findGlobalExportByName === 'function') address = Module.findGlobalExportByName(spec);
  else if (typeof Module.findExportByName === 'function') address = Module.findExportByName(null, spec);
  if (address === null && typeof DebugSymbol !== 'undefined') {
    try { var sym = DebugSymbol.fromName(spec); if (sym && !sym.address.isNull()) address = sym.address; } catch (e) {}
  }
  return address;
}
specs.forEach(function (spec) {
  var address = resolve(spec);
  if (address === null) { send({type: 'unresolved', spec: spec}); return; }
  Interceptor.attach(address, {
    onEnter: function (args) {
      var record = {type: 'call', fn: spec, args: [], tid: this.threadId};
      for (var i = 0; i < 4; i++) record.args.push(args[i].toString());
      try {
        var text = args[0].readUtf8String(64);
        if (text !== null && /^[\x20-\x7e]+$/.test(text)) record.str0 = text;
      } catch (e) {}
      send(record);
    },
    onLeave: function (ret) { send({type: 'ret', fn: spec, ret: ret.toString()}); }
  });
  send({type: 'hooked', spec: spec, address: address.toString()});
});
"""


def _frida():
    try:
        import frida
    except ImportError as error:
        raise ReaError(E_MISSING_DEP, "frida belum terpasang.", "Pasang: pip install --break-system-packages frida (alat opsional)") from error
    return frida


def build_script(specs: list[str]) -> str:
    """Skrip JavaScript untuk daftar hook; setiap spesifikasi divalidasi (nama simbol atau alamat heksadesimal)."""
    if not specs:
        raise ReaError(E_USAGE, "Mode frida butuh minimal satu --hook.", "Contoh: --hook puts --hook 0x401136")
    if len(specs) > MAX_HOOKS:
        raise ReaError(E_USAGE, f"Maksimal {MAX_HOOKS} hook.", "")
    for spec in specs:
        if not _SPEC.fullmatch(spec):
            raise ReaError(E_USAGE, f"Spesifikasi hook tidak valid: {spec!r}", "Pakai nama simbol atau alamat 0x...")
    return _SCRIPT.replace("%SPECS%", json.dumps(json.dumps(specs)))


def hook(path: str, program_args: list[str], hooks: list[str], timeout: float = DEFAULT_TIMEOUT) -> dict[str, Any]:
    source = build_script(hooks)
    frida = _frida()
    messages: list[dict[str, Any]] = []
    errors: list[str] = []
    output: list[bytes] = []
    done = threading.Event()

    def on_message(message: dict[str, Any], _data: Any) -> None:
        if message.get("type") == "send":
            if len(messages) < MAX_MESSAGES:
                messages.append(message["payload"])
        elif message.get("type") == "error":
            errors.append(str(message.get("description", message))[:300])

    def on_output(_pid: int, fd: int, data: bytes) -> None:
        if fd == 1 and sum(len(chunk) for chunk in output) < 64 * 1024:
            output.append(data)

    import os

    device = frida.get_local_device()
    device.on("output", on_output)
    try:
        pid = device.spawn([os.path.abspath(path), *program_args], stdio="pipe")
    except Exception as error:  # noqa: BLE001 - frida lempar banyak tipe; semuanya jadi pesan yang jelas
        raise _explain(error) from error
    session = None
    try:
        session = device.attach(pid)
        session.on("detached", lambda *_: done.set())
        script = session.create_script(source)
        script.on("message", on_message)
        script.load()
        device.resume(pid)
        finished = done.wait(timeout)
        time.sleep(0.2)  # pesan terakhir tiba sedikit setelah sesi terlepas
    except Exception as error:  # noqa: BLE001
        raise _explain(error) from error
    finally:
        try:
            device.kill(pid)
        except Exception:  # noqa: BLE001 - proses sudah selesai
            pass
        if session is not None:
            try:
                session.detach()
            except Exception:  # noqa: BLE001
                pass
        device.off("output", on_output)
    calls = [m for m in messages if m.get("type") == "call"]
    return {
        "mode": "frida",
        "frida_version": frida.__version__,
        "timed_out": not finished,
        "hooked": [{"spec": m["spec"], "address": m["address"]} for m in messages if m.get("type") == "hooked"],
        "unresolved": [m["spec"] for m in messages if m.get("type") == "unresolved"],
        "calls": calls,
        "returns": [m for m in messages if m.get("type") == "ret"],
        "script_errors": errors,
        "program_output": b"".join(output).decode("utf-8", "replace")[:4000],
        "truncated": len(messages) >= MAX_MESSAGES,
    }


def _explain(error: Exception) -> ReaError:
    text = str(error)
    if "not permitted" in text.lower() or "ptrace" in text.lower() or "permission" in text.lower():
        return ReaError(E_NOT_SUPPORTED, f"Frida tidak bisa menyuntik proses di lingkungan ini: {text[:200]}", "Frida butuh ptrace; pakai --mode emu atau jalankan `rea doctor`.")
    return ReaError(E_NOT_SUPPORTED, f"Frida gagal: {text[:300]}", "Periksa versi Frida dan jalankan `rea doctor`.")
