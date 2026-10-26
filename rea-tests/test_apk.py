import json
import os
import stat
import sys
import zipfile

import pytest

from conftest import make_apk, make_dex
from rea.integrations import decompile
from rea.parsers import apk, axml, dex
from rea.result import ReaError

FIXTURE = os.path.join(os.path.dirname(__file__), "fixtures", "AndroidManifest.bin")


def last_json(stdout):
    return json.loads([line for line in stdout.splitlines() if line.startswith("{")][-1])


def test_binary_manifest_is_parsed_into_a_tree():
    root = axml.parse_axml(open(FIXTURE, "rb").read())
    assert root["tag"] == "manifest"
    assert root["attrs"]["package"] == "com.nextcraft.forge.x"
    permissions = [n["attrs"]["name"] for n in axml.find_all(root, "uses-permission")]
    assert "android.permission.INTERNET" in permissions
    activities = [n["attrs"]["name"] for n in axml.find_all(root, "activity")]
    assert any(name.endswith("MainActivity") for name in activities)


def test_not_axml_is_rejected():
    with pytest.raises(ReaError) as error:
        axml.parse_axml(b"<manifest/>")
    assert error.value.code == "bad_format"


def test_dex_lists_classes_with_super_and_access():
    parsed = dex.parse_dex(make_dex(["com.example.A", "com.example.b.B"]))
    assert parsed["class_count"] == 2 and parsed["checksum_ok"] is True
    names = {c["name"]: c for c in parsed["classes"]}
    assert set(names) == {"com.example.A", "com.example.b.B"}
    assert names["com.example.A"]["super"] == "java.lang.Object"
    assert names["com.example.A"]["access"] == ["public", "final"]
    only_b = dex.parse_dex(make_dex(["com.example.A", "com.example.b.B"]), name_filter="com.example.b")
    assert [c["name"] for c in only_b["classes"]] == ["com.example.b.B"] and only_b["matched"] == 1
    limited = dex.parse_dex(make_dex(["a.A", "a.B", "a.C"]), limit=2)
    assert len(limited["classes"]) == 2 and limited["truncated"] is True


def test_dex_checksum_and_garbage():
    assert dex.parse_dex(make_dex(["a.A"], corrupt_checksum=True))["checksum_ok"] is False
    with pytest.raises(ReaError):
        dex.parse_dex(b"not a dex file at all, definitely not" * 10)
    truncated = make_dex(["a.A", "a.B"])[:120]
    with pytest.raises(ReaError):
        dex.parse_dex(truncated)


def test_apk_summary(tmp_path):
    path = make_apk(str(tmp_path / "a.apk"))
    summary = apk.summarize_apk(path)
    manifest = summary["manifest"]
    assert manifest["package"] == "com.nextcraft.forge.x"
    assert manifest["main_activity"].endswith("MainActivity")
    assert manifest["version_code"] and manifest["target_sdk"]
    assert any(p["name"] == "android.permission.INTERNET" for p in manifest["permissions"])
    assert summary["dex"][0]["classes"] == 2 and summary["dex"][0]["checksum_ok"] is True
    assert summary["native_libs"] == {"arm64-v8a": ["libnative.so"]}
    assert summary["signature"]["v1"] is True and summary["signature"]["blocks"] == ["v2"]
    assert len(summary["signature"]["v1_certificate_sha256"]) == 64
    assert summary["has_resources_arsc"] is True and summary["assets"] == 1
    assert any(f["level"] == "high" and "debuggable" in f["message"] for f in summary["findings"])


def test_unsigned_apk_is_a_finding(tmp_path):
    path = str(tmp_path / "u.apk")
    make_apk(path, v2_block=False)
    with zipfile.ZipFile(path) as archive:
        keep = [n for n in archive.namelist() if not n.startswith("META-INF/")]
        data = {n: archive.read(n) for n in keep}
    with zipfile.ZipFile(path, "w") as archive:
        for name, content in data.items():
            archive.writestr(name, content)
    summary = apk.summarize_apk(path)
    assert summary["signature"] == {"v1": False, "blocks": [], "v1_certificate_sha256": None}
    assert any("tidak bertanda tangan" in f["message"] for f in summary["findings"])


def test_not_a_zip_is_a_clear_error(tmp_path):
    bad = tmp_path / "x.apk"
    bad.write_bytes(b"PK\x03\x04 broken")
    with pytest.raises(ReaError) as error:
        apk.summarize_apk(str(bad))
    assert error.value.code == "bad_format"


def test_cli_apk_and_dex(run_rea, tmp_path):
    path = make_apk(str(tmp_path / "a.apk"))
    done = run_rea("apk", path, "--json", cwd=tmp_path)
    assert done.returncode == 0, done.stderr
    data = last_json(done.stdout)["data"]
    assert data["manifest"]["package"] == "com.nextcraft.forge.x" and data["identify"]["type"] == "APK"
    listed = run_rea("dex", path, "--filter", "com.example.util", "--json", cwd=tmp_path)
    classes = last_json(listed.stdout)["data"]["classes"]
    assert [c["name"] for c in classes] == ["com.example.util.Helper"]
    not_apk = tmp_path / "a.txt"
    not_apk.write_text("x")
    bad = run_rea("apk", str(not_apk), "--json", cwd=tmp_path)
    assert bad.returncode == 1 and last_json(bad.stdout)["error"]["code"] == "bad_format"


def _fake_tool(directory, name, body):
    path = directory / name
    path.write_text("#!/bin/sh\n" + body)
    path.chmod(path.stat().st_mode | stat.S_IEXEC)


def test_decompile_without_tools_says_what_to_install(run_rea, tmp_path):
    path = make_apk(str(tmp_path / "a.apk"))
    empty = tmp_path / "bin"
    empty.mkdir()
    env = {"PATH": str(empty)}
    # Perlu python di PATH untuk menjalankan modul: panggil lewat jalur penuh, lalu atur PATH hanya untuk pencarian alat.
    done = run_rea("decompile", path, "--json", cwd=tmp_path, env=env)
    result = last_json(done.stdout)
    assert done.returncode == 1 and result["error"]["code"] == "tool_missing"
    assert "jadx" in result["error"]["hint"] and "apktool" in result["error"]["hint"]
    named = run_rea("decompile", path, "--tool", "jadx", "--json", cwd=tmp_path, env=env)
    assert last_json(named.stdout)["error"]["code"] == "tool_missing"


def test_decompile_runs_the_tool_and_reports_files(run_rea, tmp_path):
    path = make_apk(str(tmp_path / "a.apk"))
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    _fake_tool(bin_dir, "jadx", 'mkdir -p "$2"; echo class > "$2/A.java"; echo ok\n')
    env = {"PATH": str(bin_dir) + os.pathsep + os.environ["PATH"]}
    done = run_rea("decompile", path, "--tool", "jadx", "-o", str(tmp_path / "out"), "--json", cwd=tmp_path, env=env)
    assert done.returncode == 0, done.stderr
    data = last_json(done.stdout)["data"]
    assert data["tool"] == "jadx" and data["files"] == 1 and data["partial"] is False
    assert (tmp_path / "out" / "A.java").exists()


def test_decompile_partial_and_failed(tmp_path):
    source = make_apk(str(tmp_path / "a.apk"))
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    _fake_tool(bin_dir, "jadx", 'mkdir -p "$2"; echo class > "$2/A.java"; echo "1 error" >&2; exit 3\n')
    old = os.environ["PATH"]
    os.environ["PATH"] = str(bin_dir) + os.pathsep + old
    try:
        partial = decompile.decompile(source, tool="jadx", out_dir=str(tmp_path / "o1"))
        assert partial["partial"] is True and partial["files"] == 1 and partial["returncode"] == 3
        _fake_tool(bin_dir, "jadx", "echo boom >&2; exit 1\n")
        with pytest.raises(ReaError) as error:
            decompile.decompile(source, tool="jadx", out_dir=str(tmp_path / "o2"))
        assert error.value.code == "tool_failed" and "boom" in error.value.hint
    finally:
        os.environ["PATH"] = old
