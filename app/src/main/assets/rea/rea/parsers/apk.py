"""Ringkasan APK: manifest (izin, komponen), DEX, pustaka native, tanda tangan, dan temuan keamanan dasar."""

from __future__ import annotations

import hashlib
import struct
import zipfile
from typing import Any

from ..result import E_BAD_FORMAT, ReaError
from .axml import find_all, parse_axml
from .dex import MAX_DEX_BYTES, parse_dex

MAX_CERT_BYTES = 1024 * 1024  # sertifikat v1 (PKCS#7) normalnya beberapa KB

DANGEROUS = {
    "READ_SMS", "SEND_SMS", "RECEIVE_SMS", "READ_CONTACTS", "WRITE_CONTACTS", "READ_CALL_LOG", "WRITE_CALL_LOG", "CALL_PHONE",
    "RECORD_AUDIO", "CAMERA", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION",
    "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE", "READ_PHONE_STATE",
    "SYSTEM_ALERT_WINDOW", "REQUEST_INSTALL_PACKAGES", "BIND_ACCESSIBILITY_SERVICE", "BIND_DEVICE_ADMIN",
    "READ_PHONE_NUMBERS", "GET_ACCOUNTS", "BODY_SENSORS", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO",
}
COMPONENT_TAGS = ("activity", "activity-alias", "service", "receiver", "provider")
_SIG_BLOCK_MAGIC = b"APK Sig Block 42"
_SIG_IDS = {0x7109871A: "v2", 0xF05368C0: "v3", 0x1B93AD61: "v3.1"}
_ANDROID_PREFIX = "android.permission."


def _qualify(package: str, name: str) -> str:
    if name.startswith("."):
        return package + name
    return name if "." in name else f"{package}.{name}"


def _signature_blocks(path: str) -> list[str]:
    """Skema v2/v3 di blok penandatanganan APK (antara data zip dan central directory)."""
    with open(path, "rb") as handle:
        handle.seek(0, 2)
        size = handle.tell()
        handle.seek(max(0, size - 65557))
        tail = handle.read()
        eocd = tail.rfind(b"PK\x05\x06")
        if eocd < 0 or eocd + 20 > len(tail):
            return []
        (cd_offset,) = struct.unpack_from("<I", tail, eocd + 16)
        if cd_offset < 32:
            return []
        handle.seek(cd_offset - 24)
        footer = handle.read(24)
        if footer[8:] != _SIG_BLOCK_MAGIC:
            return []
        (block_size,) = struct.unpack_from("<Q", footer, 0)
        total = block_size + 8
        if block_size < 24 or total > cd_offset:
            return []
        handle.seek(cd_offset - total)
        block = handle.read(total)
    # [uint64 ukuran][pasangan: uint64 panjang, uint32 id, nilai]...[uint64 ukuran]["APK Sig Block 42"]
    pairs = block[8:-24]
    found: list[str] = []
    position = 0
    while position + 12 <= len(pairs):
        (length,) = struct.unpack_from("<Q", pairs, position)
        (pair_id,) = struct.unpack_from("<I", pairs, position + 8)
        if pair_id in _SIG_IDS:
            found.append(_SIG_IDS[pair_id])
        if length < 4:
            break
        position += 8 + length
    return found


def read_entry(archive: zipfile.ZipFile, name: str, cap: int) -> bytes:
    """Membaca satu entri zip lewat aliran dengan batas nyata: header ``file_size`` bisa bohong (zip bomb / APK rusak)."""
    chunks: list[bytes] = []
    total = 0
    with archive.open(name) as handle:
        while True:
            block = handle.read(min(1 << 20, cap + 1 - total))
            if not block:
                break
            total += len(block)
            if total > cap:
                raise ReaError(E_BAD_FORMAT, f"Entri {name} lebih besar dari batas {cap} byte.", "Ukuran nyata melebihi header zip.")
            chunks.append(block)
    return b"".join(chunks)


def read_manifest(archive: zipfile.ZipFile) -> dict[str, Any]:
    try:
        raw = archive.read("AndroidManifest.xml")
    except KeyError as error:
        raise ReaError(E_BAD_FORMAT, "APK tanpa AndroidManifest.xml.", "Berkas ini bukan APK yang lengkap.") from error
    root = parse_axml(raw)
    attrs = root["attrs"]
    package = str(attrs.get("package", ""))
    sdk = next(iter(find_all(root, "uses-sdk")), {"attrs": {}})["attrs"]
    app_nodes = find_all(root, "application")
    app = app_nodes[0] if app_nodes else {"attrs": {}, "children": []}
    permissions = []
    for node in find_all(root, "uses-permission") + find_all(root, "uses-permission-sdk-23"):
        name = str(node["attrs"].get("name", ""))
        short = name[len(_ANDROID_PREFIX):] if name.startswith(_ANDROID_PREFIX) else ""
        permissions.append({"name": name, "dangerous": short in DANGEROUS})
    components: dict[str, list[dict[str, Any]]] = {tag: [] for tag in COMPONENT_TAGS}
    main_activity = None
    for tag in COMPONENT_TAGS:
        for node in find_all(app, tag):
            filters = [c for c in node["children"] if c["tag"] == "intent-filter"]
            actions = [str(a["attrs"].get("name", "")) for f in filters for a in f["children"] if a["tag"] == "action"]
            categories = [str(c["attrs"].get("name", "")) for f in filters for c in f["children"] if c["tag"] == "category"]
            declared = node["attrs"].get("exported")
            name = _qualify(package, str(node["attrs"].get("name", "")))
            launcher = "android.intent.action.MAIN" in actions and "android.intent.category.LAUNCHER" in categories
            if launcher and main_activity is None:
                main_activity = name
            components[tag].append(
                {
                    "name": name,
                    "exported": declared,
                    # Tanpa atribut: diekspor bila punya intent-filter (perilaku sebelum Android 12).
                    "exported_effective": bool(declared) if declared is not None else bool(filters),
                    "permission": node["attrs"].get("permission"),
                    "launcher": launcher,
                    "actions": actions,
                }
            )
    return {
        "package": package,
        "version_code": attrs.get("versionCode"),
        "version_name": attrs.get("versionName"),
        "min_sdk": sdk.get("minSdkVersion"),
        "target_sdk": sdk.get("targetSdkVersion"),
        "debuggable": bool(app["attrs"].get("debuggable", False)),
        "allow_backup": app["attrs"].get("allowBackup"),
        "cleartext_traffic": app["attrs"].get("usesCleartextTraffic"),
        "permissions": permissions,
        "features": [str(f["attrs"].get("name", "")) for f in find_all(root, "uses-feature")],
        "components": components,
        "main_activity": main_activity,
    }


def findings(manifest: dict[str, Any], signature: dict[str, Any]) -> list[dict[str, str]]:
    """Temuan keamanan dasar untuk belajar: bukan pengganti audit."""
    out: list[dict[str, str]] = []
    if manifest["debuggable"]:
        out.append({"level": "high", "message": "Aplikasi debuggable: bisa dilampiri debugger dan dibaca datanya lewat run-as."})
    if manifest["allow_backup"] is not False:
        out.append({"level": "medium", "message": "allowBackup aktif (atau tidak diatur): data aplikasi bisa dicadangkan lewat adb."})
    if manifest["cleartext_traffic"] is True:
        out.append({"level": "medium", "message": "usesCleartextTraffic=true: lalu lintas HTTP tanpa enkripsi diizinkan."})
    target = manifest["target_sdk"]
    if isinstance(target, int) and target < 30:
        out.append({"level": "medium", "message": f"targetSdk {target} lama: banyak perlindungan platform baru tidak berlaku."})
    for tag, items in manifest["components"].items():
        for item in items:
            if item["exported_effective"] and not item["permission"] and not item["launcher"]:
                out.append({"level": "info", "message": f"{tag} diekspor tanpa izin: {item['name']}"})
    dangerous = [p["name"] for p in manifest["permissions"] if p["dangerous"]]
    if dangerous:
        out.append({"level": "info", "message": f"{len(dangerous)} izin berbahaya diminta: " + ", ".join(n.rsplit(".", 1)[-1] for n in dangerous)})
    if not signature["v1"] and not signature["blocks"]:
        out.append({"level": "high", "message": "APK tidak bertanda tangan."})
    return out


def summarize_apk(path: str) -> dict[str, Any]:
    try:
        archive = zipfile.ZipFile(path)
    except zipfile.BadZipFile as error:
        raise ReaError(E_BAD_FORMAT, "APK rusak atau bukan zip.", "Periksa apakah unduhan lengkap.") from error
    with archive:
        names = archive.namelist()
        manifest = read_manifest(archive)
        dex_files = sorted(n for n in names if n.startswith("classes") and n.endswith(".dex"))
        dex_summary = []
        for name in dex_files:
            info = archive.getinfo(name)
            if info.file_size > MAX_DEX_BYTES:
                dex_summary.append({"name": name, "size": info.file_size, "skipped": True})
                continue
            parsed = parse_dex(read_entry(archive, name, MAX_DEX_BYTES), limit=0)
            dex_summary.append(
                {"name": name, "size": info.file_size, "classes": parsed["class_count"], "methods": parsed["methods"], "fields": parsed["fields"], "checksum_ok": parsed["checksum_ok"]}
            )
        libs: dict[str, list[str]] = {}
        for name in names:
            if name.startswith("lib/") and name.endswith(".so") and name.count("/") >= 2:
                libs.setdefault(name.split("/")[1], []).append(name.rsplit("/", 1)[-1])
        v1_files = [n for n in names if n.startswith("META-INF/") and n.upper().endswith((".RSA", ".DSA", ".EC"))]
        cert_hash = hashlib.sha256(read_entry(archive, v1_files[0], MAX_CERT_BYTES)).hexdigest() if v1_files else None
        signature = {
            "v1": bool([n for n in names if n.startswith("META-INF/") and n.upper().endswith(".SF")]),
            "blocks": _signature_blocks(path),
            "v1_certificate_sha256": cert_hash,
        }
        return {
            "manifest": manifest,
            "dex": dex_summary,
            "native_libs": libs,
            "signature": signature,
            "entries": len(names),
            "has_resources_arsc": "resources.arsc" in names,
            "assets": sum(1 for n in names if n.startswith("assets/")),
            "findings": findings(manifest, signature),
        }


def list_dex_classes(path: str, name_filter: str = "", limit: int = 2000) -> dict[str, Any]:
    """Class dari berkas ``.dex`` atau dari semua ``classes*.dex`` di dalam APK."""
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            result: dict[str, Any] | None = None
            for name in sorted(n for n in archive.namelist() if n.startswith("classes") and n.endswith(".dex")):
                if archive.getinfo(name).file_size > MAX_DEX_BYTES:
                    continue
                part = parse_dex(read_entry(archive, name, MAX_DEX_BYTES), name_filter=name_filter, limit=max(0, limit - (len(result["classes"]) if result else 0)))
                for item in part["classes"]:
                    item["dex"] = name
                if result is None:
                    result = part
                    result["files"] = [name]
                else:
                    result["classes"] += part["classes"]
                    result["class_count"] += part["class_count"]
                    result["matched"] += part["matched"]
                    result["methods"] += part["methods"]
                    result["fields"] += part["fields"]
                    result["truncated"] = result["truncated"] or part["truncated"]
                    result["files"].append(name)
            if result is None:
                raise ReaError(E_BAD_FORMAT, "APK tanpa classes.dex.", "Mungkin APK resource saja atau bundel.")
            result["truncated"] = result["matched"] > len(result["classes"])
            return result
    with open(path, "rb") as handle:
        data = handle.read(MAX_DEX_BYTES + 1)
    if len(data) > MAX_DEX_BYTES:
        raise ReaError(E_BAD_FORMAT, "Berkas DEX terlalu besar untuk dibaca sekaligus.", "Batas 64 MB.")
    parsed = parse_dex(data, name_filter=name_filter, limit=limit)
    parsed["files"] = [path]
    return parsed
