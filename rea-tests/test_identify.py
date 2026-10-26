import hashlib
import zipfile

import pytest

from rea.identify import entropy_of, identify
from rea.result import ReaError


def test_entropy_bounds():
    assert entropy_of(b"") == 0.0
    assert entropy_of(b"aaaa") == 0.0
    assert round(entropy_of(bytes(range(256))), 3) == 8.0


def test_elf_is_identified_with_arch_and_hashes(hello_elf):
    info = identify(hello_elf)
    assert info["type"] == "ELF"
    assert info["bits"] in (32, 64)
    assert info["arch"] in {"x86-64", "arm64", "arm", "x86", "riscv", "mips", "ppc", "ppc64"}
    with open(hello_elf, "rb") as handle:
        assert info["sha256"] == hashlib.sha256(handle.read()).hexdigest()
    assert info["size"] > 0


def test_text_zip_apk_and_unknown(tmp_path):
    text = tmp_path / "a.txt"
    text.write_text("halo dunia\n")
    assert identify(str(text))["type"] == "text"

    plain_zip = tmp_path / "a.zip"
    with zipfile.ZipFile(plain_zip, "w") as z:
        z.writestr("x.txt", "x")
    assert identify(str(plain_zip))["type"] == "ZIP"

    apk = tmp_path / "a.apk"
    with zipfile.ZipFile(apk, "w") as z:
        z.writestr("AndroidManifest.xml", "x")
        z.writestr("classes.dex", "x")
    assert identify(str(apk))["type"] == "APK"

    empty = tmp_path / "empty"
    empty.write_bytes(b"")
    info = identify(str(empty))
    assert info["type"] == "unknown" and info["size"] == 0 and info["entropy"] == 0.0


def test_dex_and_pe_headers(tmp_path):
    dex = tmp_path / "c.dex"
    dex.write_bytes(b"dex\n035\x00" + b"\x00" * 100)
    assert identify(str(dex))["type"] == "DEX"
    pe = bytearray(b"MZ" + b"\x00" * 0x3E)
    pe[0x3C:0x40] = (0x40).to_bytes(4, "little")
    pe += b"PE\x00\x00" + (0x8664).to_bytes(2, "little") + b"\x00" * 40
    path = tmp_path / "w.exe"
    path.write_bytes(bytes(pe))
    info = identify(str(path))
    assert info["type"] == "PE" and info["arch"] == "x86-64"


def test_high_entropy_unknown_is_flagged(tmp_path):
    import os

    path = tmp_path / "noise.bin"
    path.write_bytes(os.urandom(64 * 1024))
    info = identify(str(path))
    assert info["packed_hint"] is True


def test_errors_have_codes(tmp_path):
    with pytest.raises(ReaError) as missing:
        identify(str(tmp_path / "tidak-ada"))
    assert missing.value.code == "file_not_found"
    with pytest.raises(ReaError) as folder:
        identify(str(tmp_path))
    assert folder.value.code == "not_a_file"
