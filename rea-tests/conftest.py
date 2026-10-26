import os
import shutil
import subprocess
import sys

import pytest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
PACKAGE_PARENT = os.path.join(ROOT, "app", "src", "main", "assets", "rea")
sys.path.insert(0, PACKAGE_PARENT)

HELLO_C = r'''
#include <stdio.h>
int main(void) { puts("hello rea"); return 0; }
'''


@pytest.fixture(scope="session")
def hello_elf(tmp_path_factory):
    """Program C kecil yang dikompilasi di mesin uji (dilewati bila tidak ada kompilator)."""
    compiler = shutil.which("gcc") or shutil.which("cc")
    if not compiler:
        pytest.skip("tidak ada kompilator C")
    directory = tmp_path_factory.mktemp("fixtures")
    source = directory / "hello.c"
    source.write_text(HELLO_C)
    binary = directory / "hello"
    subprocess.run([compiler, "-o", str(binary), str(source)], check=True)
    return str(binary)


@pytest.fixture()
def run_rea():
    """Menjalankan ``python -m rea`` sebagai proses terpisah, seperti aplikasi."""

    def run(*args, cwd=None, env=None):
        env = dict(os.environ, PYTHONPATH=PACKAGE_PARENT, HOME=str(cwd or ROOT), **(env or {}))
        return subprocess.run(
            [sys.executable, "-m", "rea", *args], capture_output=True, text=True, env=env, cwd=cwd, timeout=60
        )

    return run


def make_dex(classes, superclass="java.lang.Object", corrupt_checksum=False):
    """DEX minimal valid (tanpa kode) dengan class yang diberikan; untuk menguji pembaca tanpa berkas besar."""
    import hashlib
    import struct
    import zlib

    def descriptor(name):
        return "L" + name.replace(".", "/") + ";"

    type_names = [descriptor(superclass)] + [descriptor(c) for c in classes]
    strings = sorted(set(type_names))
    string_index = {text: i for i, text in enumerate(strings)}
    header_size = 0x70
    string_ids_off = header_size
    type_ids_off = string_ids_off + 4 * len(strings)
    class_defs_off = type_ids_off + 4 * len(type_names)
    data_off = class_defs_off + 32 * len(classes)
    blob = bytearray()
    offsets = []
    for text in strings:
        offsets.append(data_off + len(blob))
        encoded = text.encode("utf-8")
        blob += bytes([len(text)]) + encoded + b"\x00"
    body = bytearray()
    for off in offsets:
        body += struct.pack("<I", off)
    for name in type_names:
        body += struct.pack("<I", string_index[name])
    for index, _ in enumerate(classes):
        body += struct.pack("<8I", index + 1, 0x1 | 0x10, 0, 0, 0xFFFFFFFF, 0, 0, 0)
    body += blob
    file_size = header_size + len(body)
    header = bytearray(header_size)
    header[0:8] = b"dex\n035\x00"
    struct.pack_into("<III", header, 32, file_size, header_size, 0x12345678)
    struct.pack_into(
        "<IIIIIIIIIIII", header, 56,
        len(strings), string_ids_off, len(type_names), type_ids_off, 0, 0, 0, 0, 0, 0, len(classes), class_defs_off,
    )
    struct.pack_into("<II", header, 104, len(body) - 0, data_off)
    data = bytearray(header + body)
    data[12:32] = hashlib.sha1(bytes(data[32:])).digest()
    checksum = zlib.adler32(bytes(data[12:])) & 0xFFFFFFFF
    struct.pack_into("<I", data, 8, checksum ^ 1 if corrupt_checksum else checksum)
    return bytes(data)


def make_apk(path, classes=("com.example.Main", "com.example.util.Helper"), v2_block=True):
    """APK sintetis: manifest biner nyata (fixture), DEX buatan, pustaka native, tanda tangan v1 dan blok v2."""
    import struct
    import zipfile

    manifest = open(os.path.join(os.path.dirname(__file__), "fixtures", "AndroidManifest.bin"), "rb").read()
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("AndroidManifest.xml", manifest)
        archive.writestr("classes.dex", make_dex(list(classes)))
        archive.writestr("lib/arm64-v8a/libnative.so", b"\x7fELF")
        archive.writestr("resources.arsc", b"\x00")
        archive.writestr("assets/a.txt", "x")
        archive.writestr("META-INF/CERT.SF", "Signature-Version: 1.0")
        archive.writestr("META-INF/CERT.RSA", b"cert-bytes")
    if v2_block:
        raw = open(path, "rb").read()
        eocd = raw.rfind(b"PK\x05\x06")
        (cd_offset,) = struct.unpack_from("<I", raw, eocd + 16)
        value = b"signer"
        pair = struct.pack("<QI", 4 + len(value), 0x7109871A) + value
        size = len(pair) + 8 + 16
        block = struct.pack("<Q", size) + pair + struct.pack("<Q", size) + b"APK Sig Block 42"
        patched = bytearray(raw[:cd_offset] + block + raw[cd_offset:])
        struct.pack_into("<I", patched, eocd + len(block) + 16, cd_offset + len(block))
        open(path, "wb").write(bytes(patched))
    return path
