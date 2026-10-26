"""Fase A8: plugin dan firmware."""

import bz2
import gzip
import json
import lzma
import os
import random
import shutil
import struct
import subprocess
import textwrap
import zipfile

import pytest

from rea.parsers import firmware
from rea.plugins import api, loader

KERNEL_TEXT = b"\x00" * 64 + b"Linux version 5.10.0-rea (builder@host) (gcc 13.2.0) #1 SMP\n" + b"\x01" * 400
UBOOT = b"\x00" * 16 + b"U-Boot 2022.01-rea (Oct 21 2026)\x00" + b"\x00" * 16


def last_json(out):
    return json.loads(out.stdout.strip().splitlines()[-1])


@pytest.fixture()
def home(tmp_path):
    return tmp_path


@pytest.fixture()
def rea(run_rea, home):
    def call(*args, env=None):
        return last_json(run_rea(*args, "--json", cwd=str(home), env=env))

    return call


def write_plugin(home, name, source):
    folder = home / ".rea" / "plugins"
    folder.mkdir(parents=True, exist_ok=True)
    (folder / f"{name}.py").write_text(textwrap.dedent(source))


# --- plugin ------------------------------------------------------------------------------------------------------------


def test_the_builtin_hardening_plugin_is_listed_and_runs_on_elf(rea, hello_elf):
    names = {p["name"]: p for p in rea("plugins", "list")["data"]["plugins"]}
    assert names["hardening"]["builtin"] and names["hardening"]["formats"] == ["ELF"]
    result = rea("analyze", hello_elf)["data"]["plugins"]["hardening"]["on_analyze"]
    assert set(result) == {"nx", "pie", "relro", "canary", "fortify", "advice"}
    assert result["nx"] is True and result["relro"] in ("none", "partial", "full")


def test_the_hardening_report_section_is_appended(run_rea, home, hello_elf):
    out = home / "r.md"
    run_rea("report", hello_elf, "-o", str(out), "--json", cwd=str(home))
    text = out.read_text()
    assert "## Plugin: hardening" in text and "| NX |" in text and "| RELRO |" in text


def test_a_user_plugin_runs_on_identify(rea, home, hello_elf):
    write_plugin(home, "size_note", '''
        import rea

        @rea.plugin("size-note", formats=["ELF"])
        class SizeNote:
            def on_identify(self, info):
                return {"kib": info["size"] // 1024}
    ''')
    data = rea("id", hello_elf)["data"]
    assert data["plugins"]["size-note"]["on_identify"]["kib"] >= 0


def test_a_plugin_only_sees_the_formats_it_asked_for(rea, home, hello_elf):
    write_plugin(home, "pe_only", '''
        import rea

        @rea.plugin("pe-only", formats=["PE"])
        class PeOnly:
            def on_identify(self, info):
                return {"seen": True}
    ''')
    assert "pe-only" not in rea("id", hello_elf)["data"].get("plugins", {})


def test_a_failing_plugin_becomes_a_warning_not_a_crash(rea, home, hello_elf):
    write_plugin(home, "boom", '''
        import rea

        @rea.plugin("boom", formats=["*"])
        class Boom:
            def on_identify(self, info):
                raise RuntimeError("meledak")
    ''')
    payload = rea("id", hello_elf)
    assert payload["ok"] and payload["data"]["type"] == "ELF"
    assert any("boom" in w and "meledak" in w for w in payload["warnings"])


def test_a_broken_plugin_file_is_reported_and_the_others_still_load(rea, home):
    write_plugin(home, "broken", "import rea\n\n@rea.plugin('half')\nclass Half:\n    def on_identify(self, i): return 1\n\nraise ValueError('rusak di tengah')\n")
    write_plugin(home, "syntax", "def (:\n")
    data = rea("plugins", "list")["data"]
    assert {e["source"].rsplit("/", 1)[-1] for e in data["errors"]} == {"broken.py", "syntax.py"}
    names = [p["name"] for p in data["plugins"]]
    assert "hardening" in names and "half" not in names  # plugin yang setengah terdaftar dibuang


def test_a_duplicate_plugin_name_is_an_error(rea, home):
    write_plugin(home, "dup", "import rea\n\n@rea.plugin('hardening')\nclass D:\n    def on_identify(self, i): return 1\n")
    data = rea("plugins", "list")["data"]
    assert any("sudah dipakai" in e["error"] for e in data["errors"])
    assert [p["name"] for p in data["plugins"]].count("hardening") == 1


def test_a_slow_hook_is_cut_off(tmp_path):
    p = tmp_path / "slow.py"
    p.write_text("import time, rea\n\n@rea.plugin('slow')\nclass S:\n    def on_identify(self, i):\n        time.sleep(10)\n")
    plugins, errors = loader.load_plugins([str(tmp_path)])
    assert not errors
    results, warnings = loader.run_hook(plugins, "on_identify", "ELF", {}, timeout=0.3)
    assert results == {} and "batas waktu" in warnings[0]
    api.reset()


def test_plugin_results_are_json_safe_and_bounded(tmp_path):
    p = tmp_path / "wide.py"
    p.write_text("import rea\n\n@rea.plugin('wide')\nclass W:\n    def on_identify(self, i):\n        return {'set': {1, 2}, 'blob': 'x' * 200000}\n")
    plugins, _ = loader.load_plugins([str(tmp_path)])
    results, _ = loader.run_hook(plugins, "on_identify", "ELF", {})
    assert results["wide"] == {"truncated": True, "note": results["wide"]["note"]}
    api.reset()


def test_plugins_can_be_switched_off(rea, home, hello_elf):
    assert "hardening" in rea("analyze", hello_elf)["data"].get("plugins", {})
    assert rea("analyze", hello_elf, env={"REA_NO_PLUGINS": "1"})["data"].get("plugins") is None
    assert rea("plugins", "list", env={"REA_NO_PLUGINS": "1"})["data"]["plugins"] == []


def test_plugins_new_creates_a_working_skeleton(rea, home, hello_elf):
    created = rea("plugins", "new", "my-tool")
    path = created["data"]["path"]
    assert os.path.isfile(path) and path.endswith("my_tool.py")
    assert "my-tool" in [p["name"] for p in rea("plugins", "list")["data"]["plugins"]]
    assert rea("id", hello_elf)["data"]["plugins"]["my-tool"]["on_identify"]["ukuran"] > 0
    again = rea("plugins", "new", "my-tool")
    assert again["error"]["code"] == "usage"
    assert rea("plugins", "new", "Bad Name")["error"]["code"] == "usage"


def test_decorator_validates_names_and_hooks():
    import rea

    with pytest.raises(ValueError):
        rea.plugin("Bad Name")
    with pytest.raises(ValueError):
        rea.plugin("nohooks")(type("Empty", (), {}))
    api.reset()


# --- firmware: pemindaian ----------------------------------------------------------------------------------------------


def make_squashfs(tmp_path):
    if not shutil.which("mksquashfs"):
        pytest.skip("mksquashfs tidak ada")
    root = tmp_path / "rootfs"
    (root / "etc").mkdir(parents=True)
    (root / "etc" / "motd").write_text("halo firmware\n")
    (root / "etc" / "passwd").write_text("root:x:0:0\n")
    image = tmp_path / "fs.sqsh"
    subprocess.run(["mksquashfs", str(root), str(image), "-noappend", "-comp", "gzip", "-quiet"], check=True, capture_output=True)
    return image.read_bytes()


def make_firmware(tmp_path, with_squashfs=True):
    gz = gzip.compress(b"\x00" * 100 + KERNEL_TEXT)
    parts = [b"\xff" * 4096, UBOOT.ljust(8192, b"\xff"), gz.ljust(((len(gz) + 4095) // 4096) * 4096, b"\xff")]
    blob = b"".join(parts)
    squash_at = None
    if with_squashfs:
        squash_at = len(blob)
        blob += make_squashfs(tmp_path)
    path = tmp_path / "fw.bin"
    path.write_bytes(blob)
    return str(path), 4096 + 8192, squash_at


def test_scan_finds_squashfs_gzip_kernel_and_bootloader(tmp_path):
    path, gzip_at, squash_at = make_firmware(tmp_path)
    result = firmware.scan(path)
    kinds = {(h["type"], h["offset"]) for h in result["hits"]}
    assert ("gzip", gzip_at) in kinds and ("squashfs", squash_at) in kinds
    squash = next(h for h in result["hits"] if h["type"] == "squashfs")
    assert squash["detail"]["compression"] == "gzip" and squash["detail"]["version"] == "4.0" and squash["size"] > 96
    assert result["kernel"]["version"] == "5.10.0-rea" and result["kernel"]["in"] == "gzip"
    assert result["kernel"]["banner"].startswith("Linux version 5.10.0-rea")
    assert result["bootloader"]["name"] == "U-Boot" and result["bootloader"]["version"] == "2022.01-rea"
    assert result["filesystems"] == ["squashfs"] and result["firmware"] is True


def test_random_data_has_no_findings_and_is_not_firmware(tmp_path):
    rng = random.Random(7)
    path = tmp_path / "noise.bin"
    path.write_bytes(bytes(rng.randrange(256) for _ in range(2 * 1024 * 1024)))
    result = firmware.scan(str(path))
    assert result["hits"] == [] and result["firmware"] is False
    assert firmware.looks_like_firmware(str(path)) is None


def test_structured_headers_are_validated(tmp_path):
    uimage = b"\x27\x05\x19\x56" + struct.pack(">III", 0, 1234567890, 100) + struct.pack(">II", 0x8000, 0x8000) + b"\x00" * 4 + bytes([5, 22, 2, 1]) + b"my-kernel".ljust(32, b"\x00") + b"P" * 100
    page = 2048
    android = b"ANDROID!" + struct.pack("<8I", 4096, 0x8000, 2048, 0x1000000, 0, 0, 0x100, page) + struct.pack("<I", 0) + b"\x00" * 4 + b"boardname".ljust(16, b"\x00") + b"console=ttyS0".ljust(512, b"\x00")
    android = android.ljust(page, b"\x00") + b"K" * 4096 + b"R" * 2048
    dtb = b"\xd0\x0d\xfe\xed" + struct.pack(">IIIII", 200, 56, 120, 40, 17) + b"\x00" * 200
    zipped = tmp_path / "z.zip"
    with zipfile.ZipFile(zipped, "w") as archive:
        archive.writestr("conf/readme.txt", "hi")
    entries = [uimage, android, dtb, zipped.read_bytes()]
    blob = b"".join(e.ljust(((len(e) + 4095) // 4096) * 4096, b"\xff") for e in entries)
    path = tmp_path / "multi.bin"
    path.write_bytes(blob)
    by_type = {h["type"]: h for h in firmware.scan(str(path))["hits"]}
    assert by_type["uimage"]["detail"] | {} and by_type["uimage"]["detail"]["name"] == "my-kernel"
    assert by_type["uimage"]["detail"]["os"] == "linux" and by_type["uimage"]["detail"]["arch"] == "arm64"
    assert by_type["uimage"]["detail"]["compression"] == "gzip" and by_type["uimage"]["size"] == 64 + 100
    assert by_type["android-boot"]["detail"]["cmdline"] == "console=ttyS0" and by_type["android-boot"]["detail"]["kernel_size"] == 4096
    assert by_type["dtb"]["detail"]["total_size"] == 200
    assert by_type["zip"]["detail"]["first_entry"] == "conf/readme.txt"


def test_bad_headers_are_rejected(tmp_path):
    # Magic correct, header absurd: nothing may be reported.
    junk = b"hsqs" + b"\xff" * 92 + b"\x27\x05\x19\x56" + b"\xff" * 60 + b"ANDROID!" + b"\xff" * 80 + b"\xd0\x0d\xfe\xed" + b"\xff" * 36
    path = tmp_path / "junk.bin"
    path.write_bytes(junk.ljust(8192, b"\x00"))
    assert firmware.scan(str(path))["hits"] == []


def test_text_that_mentions_banners_is_not_firmware(run_rea, tmp_path):
    log = tmp_path / "dmesg.txt"
    log.write_text("Linux version 5.10.1 (a@b) (gcc) #1\nU-Boot 2022.01 started\n" * 200)
    payload = last_json(run_rea("id", str(log), "--json", cwd=str(tmp_path)))
    assert payload["data"]["type"] == "text"


def test_identify_calls_a_padded_image_firmware(run_rea, tmp_path):
    path, _, _ = make_firmware(tmp_path)
    data = last_json(run_rea("id", path, "--json", cwd=str(tmp_path)))["data"]
    assert data["type"] == "firmware" and "squashfs" in data["firmware"]["signatures"]
    assert data["firmware"]["bootloader"] == "U-Boot"


# --- firmware: ekstraksi -----------------------------------------------------------------------------------------------


@pytest.mark.skipif(not shutil.which("unsquashfs"), reason="unsquashfs tidak ada")
def test_extract_squashfs_and_gzip(tmp_path):
    path, _, _ = make_firmware(tmp_path)
    hits = firmware.scan(path)["hits"]
    out = tmp_path / "out"
    results = {r["type"]: r for r in firmware.extract(path, str(out), hits)}
    assert results["squashfs"]["status"] == "ok"
    assert (out / results["squashfs"]["output"].rsplit("/", 1)[-1] / "etc" / "motd").read_text() == "halo firmware\n"
    assert results["gzip"]["status"] == "ok"
    assert b"Linux version 5.10.0-rea" in open(results["gzip"]["output"], "rb").read()
    assert not (out / "_carved").exists()


@pytest.mark.parametrize("kind,compress", [("xz", lzma.compress), ("bzip2", bz2.compress), ("gzip", gzip.compress)])
def test_compressed_streams_roundtrip(tmp_path, kind, compress):
    payload = os.urandom(0) + b"data-asli-" * 500
    blob = b"\xff" * 4096 + compress(payload) + b"\xff" * 4096
    path = tmp_path / "c.bin"
    path.write_bytes(blob)
    hits = [h for h in firmware.scan(str(path))["hits"] if h["type"] == kind]
    assert hits and hits[0]["offset"] == 4096
    results = firmware.extract(str(path), str(tmp_path / "o"), hits[:1])
    assert results[0]["status"] == "ok" and open(results[0]["output"], "rb").read() == payload


def test_decompression_is_capped():
    bomb = gzip.compress(b"\x00" * (8 * 1024 * 1024))
    assert len(firmware._decompress_bytes("gzip", bomb, 1024 * 1024)) == 1024 * 1024


def test_zip_slip_is_refused(tmp_path):
    evil = tmp_path / "evil.zip"
    with zipfile.ZipFile(evil, "w") as archive:
        archive.writestr("../escaped.txt", "x")
    result = firmware._extract_zip(str(evil), str(tmp_path / "dest"))
    assert result["status"] == "failed" and "keluar dari folder" in result["detail"]
    assert not (tmp_path / "escaped.txt").exists()


def test_android_boot_and_uimage_are_split(tmp_path):
    page = 2048
    head = b"ANDROID!" + struct.pack("<8I", 3000, 0x8000, 1500, 0x1000000, 0, 0, 0x100, page) + struct.pack("<I", 0)
    head = head.ljust(page, b"\x00")
    image = head + b"K" * 3000 + b"\x00" * (4096 - 3000) + b"R" * 1500 + b"\x00" * (2048 - 1500)
    uimage = b"\x27\x05\x19\x56" + struct.pack(">III", 0, 0, 40) + struct.pack(">II", 0, 0) + b"\x00" * 4 + bytes([5, 22, 2, 0]) + b"k".ljust(32, b"\x00") + b"P" * 40
    blob = image.ljust(((len(image) + 4095) // 4096) * 4096, b"\xff") + uimage
    path = tmp_path / "boot.img"
    path.write_bytes(blob)
    hits = firmware.scan(str(path))["hits"]
    results = {r["type"]: r for r in firmware.extract(str(path), str(tmp_path / "x"), hits)}
    folder = results["android-boot"]["output"]
    assert open(os.path.join(folder, "kernel"), "rb").read() == b"K" * 3000
    assert open(os.path.join(folder, "ramdisk"), "rb").read() == b"R" * 1500
    assert open(results["uimage"]["output"], "rb").read() == b"P" * 40


@pytest.mark.skipif(not shutil.which("cpio"), reason="cpio tidak ada")
def test_cpio_archive_is_found_and_extracted(tmp_path):
    src = tmp_path / "src"
    (src / "sbin").mkdir(parents=True)
    (src / "sbin" / "init").write_text("#!/bin/sh\n")
    names = subprocess.run(["find", ".", "-mindepth", "1"], cwd=src, capture_output=True, text=True).stdout
    archive = subprocess.run(["cpio", "-o", "-H", "newc", "--quiet"], cwd=src, input=names, capture_output=True, text=True, check=False)
    data = subprocess.run("find . -mindepth 1 | cpio -o -H newc --quiet", shell=True, cwd=src, capture_output=True).stdout
    assert data.startswith(b"070701")
    path = tmp_path / "initrd.bin"
    path.write_bytes(b"\xff" * 4096 + data)
    hits = [h for h in firmware.scan(str(path))["hits"] if h["type"] == "cpio"]
    assert hits and hits[0]["offset"] == 4096
    result = firmware.extract(str(path), str(tmp_path / "o"), hits[:1])[0]
    assert result["status"] == "ok" and os.path.isfile(os.path.join(result["output"], "sbin", "init"))
    del archive


def test_a_missing_tool_names_its_package(run_rea, tmp_path):
    path, _, _ = make_firmware(tmp_path)
    payload = last_json(run_rea("firmware", path, "--extract", "-o", str(tmp_path / "o"), "--json", cwd=str(tmp_path), env={"PATH": "/nonexistent"}))
    assert payload["ok"]
    missing = [r for r in payload["data"]["extracted"] if r["status"] == "tool_missing"]
    assert missing and "squashfs-tools" in missing[0]["hint"]
    assert any("apt install squashfs-tools" in w for w in payload["warnings"])


def test_cli_firmware_contract_and_errors(run_rea, tmp_path):
    path, _, _ = make_firmware(tmp_path, with_squashfs=False)
    payload = last_json(run_rea("firmware", path, "--json", cwd=str(tmp_path)))
    assert payload["ok"] and payload["command"] == "firmware"
    assert payload["data"]["kernel"]["version"] == "5.10.0-rea" and payload["data"]["identify"]["type"] == "firmware"
    missing = last_json(run_rea("firmware", str(tmp_path / "nope.bin"), "--json", cwd=str(tmp_path)))
    assert missing["error"]["code"] == "file_not_found"
    empty = tmp_path / "empty.bin"
    empty.write_bytes(os.urandom(100))
    quiet = last_json(run_rea("firmware", str(empty), "--json", cwd=str(tmp_path)))
    assert quiet["ok"] and any("Tidak ada signature" in w for w in quiet["warnings"])


@pytest.mark.skipif(not (shutil.which("mkfs.cramfs") and shutil.which("fsck.cramfs")), reason="mkfs.cramfs/fsck.cramfs tidak ada")
def test_cramfs_is_found_and_extracted(tmp_path):
    root = tmp_path / "croot"
    root.mkdir()
    (root / "hello.txt").write_text("dari cramfs\n")
    image = tmp_path / "fs.cramfs"
    subprocess.run(["mkfs.cramfs", str(root), str(image)], check=True, capture_output=True)
    path = tmp_path / "fw.bin"
    path.write_bytes(b"\xff" * 4096 + image.read_bytes())
    hits = [h for h in firmware.scan(str(path))["hits"] if h["type"] == "cramfs"]
    assert hits and hits[0]["offset"] == 4096 and hits[0]["size"] == image.stat().st_size
    result = firmware.extract(str(path), str(tmp_path / "o"), hits)[0]
    assert result["status"] == "ok", result
    assert open(os.path.join(result["output"], "hello.txt")).read() == "dari cramfs\n"
