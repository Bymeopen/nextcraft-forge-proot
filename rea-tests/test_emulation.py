"""Fase A5: emulasi Unicorn (`rea emu`) dan QEMU gdbstub (`rea run --mode emu`)."""

import json
import os
import shutil
import socket
import subprocess
import threading

import pytest

from rea.dynamic.gdbrsp import GdbClient, encode_packet, parse_registers, parse_stop_reply, checksum
from rea.dynamic.gdbrsp import _expand

unicorn = pytest.importorskip("unicorn")
from rea.emulation import unicorn_runner as emu  # noqa: E402
from rea.result import ReaError  # noqa: E402

PLAIN = b"hello rea"
KEY = 0x5A
XOR_C = """
void decode(unsigned char *buf, int n, unsigned char key) {
    for (int i = 0; i < n; i++) buf[i] ^= key;
}
int main(void) { return 0; }
"""
HELLO_C = """
#include <stdio.h>
int add(int a, int b) { return a + b; }
int main(void) { printf("hello %d\\n", add(2, 3)); return 0; }
"""


def build(tmp_path_factory, name, source, compiler, *flags):
    exe = shutil.which(compiler)
    if not exe:
        pytest.skip(f"{compiler} tidak ada")
    directory = tmp_path_factory.mktemp(name)
    src = directory / f"{name}.c"
    src.write_text(source)
    out = directory / name
    subprocess.run([exe, *flags, "-o", str(out), str(src)], check=True, capture_output=True)
    return str(out)


@pytest.fixture(scope="module")
def xor_x64(tmp_path_factory):
    return build(tmp_path_factory, "xor_x64", XOR_C, "gcc", "-O1")


@pytest.fixture(scope="module")
def xor_a64(tmp_path_factory):
    return build(tmp_path_factory, "xor_a64", XOR_C, "aarch64-linux-gnu-gcc", "-O1")


def encrypted() -> bytes:
    return bytes(b ^ KEY for b in PLAIN)


# --- Unicorn ---------------------------------------------------------------------------------------------------------


def test_raw_x86_64_code_runs_and_returns(tmp_path):
    code = tmp_path / "add.bin"
    code.write_bytes(bytes.fromhex("b807000000" "83c005" "c3"))  # mov eax,7; add eax,5; ret
    result = emu.emulate(str(code), arch="x86_64")
    assert result["stop"] == "returned"
    assert result["registers"]["rax"] == 12
    assert result["instructions"] == 3


def test_xor_decode_native_x86_64(xor_x64):
    result = emu.emulate(
        xor_x64,
        func="decode",
        regs=[("rdi", 0x600000), ("rsi", len(PLAIN)), ("rdx", KEY)],
        mems=[(0x600000, encrypted())],
        dumps=[(0x600000, len(PLAIN))],
    )
    assert result["stop"] == "returned"
    assert result["dumps"][0]["ascii"] == "hello rea"


def test_xor_decode_arm64(xor_a64):
    result = emu.emulate(
        xor_a64,
        func="decode",
        regs=[("x0", 0x600000), ("x1", len(PLAIN)), ("x2", KEY)],
        mems=[(0x600000, encrypted())],
        dumps=[(0x600000, len(PLAIN))],
    )
    assert result["arch"] == "arm64"
    assert result["dumps"][0]["ascii"] == "hello rea"


def test_infinite_loop_stops_at_the_instruction_limit(tmp_path):
    code = tmp_path / "loop.bin"
    code.write_bytes(bytes.fromhex("ebfe"))  # jmp $
    result = emu.emulate(str(code), arch="x86_64", max_insns=500)
    assert result["stop"] == "max_insns"
    assert result["instructions"] >= 500


def test_unmapped_memory_access_is_a_fault_not_a_crash(tmp_path):
    code = tmp_path / "fault.bin"
    code.write_bytes(bytes.fromhex("488b0425" "00000000"))  # mov rax, [0]
    result = emu.emulate(str(code), arch="x86_64")
    assert result["stop"] == "fault"
    assert "UNMAPPED" in result["detail"].upper()


def test_unknown_function_and_missing_arch_have_clear_errors(xor_x64, tmp_path):
    with pytest.raises(ReaError) as missing:
        emu.emulate(xor_x64, func="nope")
    assert missing.value.code == "symbol_not_found"
    raw = tmp_path / "x.bin"
    raw.write_bytes(b"\xc3")
    with pytest.raises(ReaError) as arch:
        emu.emulate(str(raw))
    assert arch.value.code == "usage"
    with pytest.raises(ReaError) as reg:
        emu.emulate(str(raw), arch="x86_64", regs=[("bogus", 1)])
    assert reg.value.code == "usage"


def test_cli_emu_json(run_rea, xor_x64):
    out = run_rea(
        "emu", xor_x64, "--func", "decode", "--reg", "rdi=0x600000", "--reg", f"rsi={len(PLAIN)}", "--reg", f"rdx={KEY}",
        "--mem", f"0x600000:{encrypted().hex()}", "--dump", f"0x600000:{len(PLAIN)}", "--json",
    )
    payload = json.loads(out.stdout.strip().splitlines()[-1])
    assert payload["ok"] and payload["command"] == "emu"
    assert payload["data"]["dumps"][0]["ascii"] == "hello rea"


def test_cli_emu_bad_number_is_a_usage_error(run_rea, xor_x64):
    out = run_rea("emu", xor_x64, "--func", "decode", "--reg", "rdi=oops", "--json")
    payload = json.loads(out.stdout.strip().splitlines()[-1])
    assert payload["error"]["code"] == "usage"


# --- Klien GDB RSP ---------------------------------------------------------------------------------------------------


def test_packet_framing_and_escaping():
    assert encode_packet("OK") == b"$OK#9a"
    assert checksum(b"OK") == 0x9A
    assert _expand(b"a}]b") == b"a}b"  # }] = '}' (0x7d ^ 0x20 = 0x5d)
    assert _expand(b"0* ") == b"0" * 4  # ' ' (0x20) - 29 = 3 salinan tambahan


def test_stop_reply_and_register_parsing():
    assert parse_stop_reply("T05thread:1;") == {"kind": "stopped", "signal": 5}
    assert parse_stop_reply("W00") == {"kind": "exited", "code": 0}
    assert parse_stop_reply("X0b")["kind"] == "killed"
    regs = parse_registers("0100000000000000" "0200000000000000" "xxxxxxxxxxxxxxxx", [("a", 8), ("b", 8), ("c", 8)])
    assert regs == {"a": 1, "b": 2}


def test_client_talks_to_a_fake_gdbstub():
    server = socket.socket()
    server.bind(("127.0.0.1", 0))
    server.listen(1)
    port = server.getsockname()[1]
    seen: list[str] = []

    def serve():
        conn, _ = server.accept()
        buffer = b""
        replies = {"?": "T05thread:1;", "g": "2a00000000000000"}
        while True:
            data = conn.recv(4096)
            if not data:
                break
            buffer += data
            while b"$" in buffer and b"#" in buffer[buffer.index(b"$"):]:
                start = buffer.index(b"$")
                end = buffer.index(b"#", start)
                if len(buffer) < end + 3:
                    break
                payload = buffer[start + 1 : end].decode()
                buffer = buffer[end + 3 :]
                seen.append(payload)
                conn.sendall(b"+" + encode_packet(replies.get(payload, "")))
        conn.close()

    thread = threading.Thread(target=serve, daemon=True)
    thread.start()
    client = GdbClient.connect("127.0.0.1", port, 3)
    assert client.request("?") == "T05thread:1;"
    assert parse_registers(client.request("g"), [("x0", 8)]) == {"x0": 42}
    client.close()
    thread.join(timeout=3)
    server.close()
    assert seen == ["?", "g"]


def test_client_times_out_instead_of_hanging():
    server = socket.socket()
    server.bind(("127.0.0.1", 0))
    server.listen(1)
    client = GdbClient.connect("127.0.0.1", server.getsockname()[1], 3)
    with pytest.raises(TimeoutError):
        client.receive(0.3)
    client.close()
    server.close()


# --- QEMU gdbstub (nyata; dilewati bila qemu-user atau kompilator tidak ada) --------------------------------------------


def qemu_cases():
    # (id, kompilator, flag, qemu, variabel lingkungan). PIE dinamis seperti biner di HP: berhenti pertama QEMU ada di ld.so.
    return [
        ("x86_64", "gcc", ["-static", "-O0"], "qemu-x86_64", {}),
        ("aarch64", "aarch64-linux-gnu-gcc", ["-static", "-O0"], "qemu-aarch64", {}),
        ("x86_64-pie", "gcc", ["-O0", "-pie"], "qemu-x86_64", {"QEMU_LD_PREFIX": "/"}),
        ("aarch64-pie", "aarch64-linux-gnu-gcc", ["-O0", "-pie"], "qemu-aarch64", {"QEMU_LD_PREFIX": "/usr/aarch64-linux-gnu"}),
    ]


@pytest.fixture(params=qemu_cases(), ids=lambda c: c[0])
def hello_static(request, tmp_path_factory, monkeypatch):
    arch, compiler, flags, qemu, env = request.param
    if not shutil.which(qemu):
        pytest.skip(f"{qemu} tidak ada")
    if env.get("QEMU_LD_PREFIX", "/") != "/" and not os.path.isdir(env["QEMU_LD_PREFIX"]):
        pytest.skip("pustaka cross tidak ada")
    for key, value in env.items():
        monkeypatch.setenv(key, value)
    return arch, build(tmp_path_factory, f"hello_{arch}", HELLO_C, compiler, *flags)


def test_run_connects_to_the_gdbstub_and_reads_registers(hello_static):
    from rea.dynamic.qemu import run_emulated

    arch, path = hello_static
    result = run_emulated(path)
    assert result["connected"] is True
    assert result["stop"] == {"kind": "stopped", "signal": 5}
    assert result["bias_source"] == "auxv"
    if arch.endswith("pie"):
        # Alamat muat PIE berbeda dari berkas, dan program dinamis mulai di ld.so, bukan di entry-nya.
        assert result["bias"] != 0
        assert result["registers"]["pc" if arch.startswith("aarch64") else "rip"] != result["entry"]
    else:
        assert result["bias"] == 0
        assert result["registers"]["pc" if arch.startswith("aarch64") else "rip"] == result["entry"]


def test_breakpoint_on_a_function_is_hit_and_the_program_finishes(hello_static):
    from rea.dynamic.qemu import run_emulated

    _, path = hello_static
    result = run_emulated(path, breakpoints=["add"], run_to_end=True)
    assert result["breakpoints"][0]["set"] and result["breakpoints"][0]["hit"]
    assert result["exit"] == {"kind": "exited", "code": 0}
    assert result["output"] == "hello 5\n"


def test_cli_run_emu_json(run_rea, hello_static):
    _, path = hello_static
    out = run_rea("run", path, "--mode", "emu", "--break", "add", "--json")
    payload = json.loads(out.stdout.strip().splitlines()[-1])
    assert payload["ok"] and payload["mode"] == "emulated-dynamic"
    assert payload["data"]["breakpoints"][0]["hit"] is True


def test_run_without_qemu_explains_what_to_install(run_rea, hello_static):
    _, path = hello_static
    out = run_rea("run", path, "--mode", "auto", "--json", env={"PATH": "/nonexistent"})
    payload = json.loads(out.stdout.strip().splitlines()[-1])
    assert payload["error"]["code"] == "missing_dependency"
    assert "qemu-user" in payload["error"]["hint"]
