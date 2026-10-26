"""Fase A6: backend dinamis penuh (strace, gdb, Frida). Uji nyata dilewati bila ptrace atau alatnya tidak ada."""

import json
import shutil
import subprocess

import pytest

from rea.capability import OK, check_ptrace
from rea.dynamic import gdb as gdb_mod
from rea.dynamic import strace as strace_mod
from rea.dynamic.frida_runner import build_script
from rea.dynamic.proc import raise_if_ptrace_denied
from rea.result import ReaError

HELLO_C = """
#include <stdio.h>
int add(int a, int b) { return a + b; }
int main(void) { printf("hello %d\\n", add(2, 3)); return 0; }
"""

TRACE = """\
101  execve("/bin/prog", ["/bin/prog"], 0x7ffd /* 3 vars */) = 0
101  openat(AT_FDCWD, "/etc/ld.so.cache", O_RDONLY|O_CLOEXEC) = 3
101  openat(AT_FDCWD, "/etc/missing", O_RDONLY) = -1 ENOENT (No such file or directory)
101  write(1, "hi\\n", 3) = 3
102  read(3,  <unfinished ...>
101  --- SIGCHLD {si_signo=SIGCHLD} ---
101  +++ exited with 7 +++
"""


@pytest.fixture(scope="module")
def hello(tmp_path_factory):
    compiler = shutil.which("gcc")
    if not compiler:
        pytest.skip("tidak ada gcc")
    directory = tmp_path_factory.mktemp("hello")
    (directory / "hello.c").write_text(HELLO_C)
    binary = directory / "hello"
    subprocess.run([compiler, "-g", "-O0", "-o", str(binary), str(directory / "hello.c")], check=True)
    return str(binary)


@pytest.fixture(scope="module")
def ptrace_ok():
    if check_ptrace().status != OK:
        pytest.skip("ptrace tidak tersedia di mesin uji")


# --- strace --------------------------------------------------------------------------------------------------------


def test_parse_trace_extracts_events_files_errors_and_exit():
    parsed = strace_mod.parse_trace(TRACE)
    assert [e["name"] for e in parsed["events"]] == ["execve", "openat", "openat", "write"]
    assert parsed["files"] == ["/etc/ld.so.cache"]  # yang gagal (ENOENT) tidak dihitung
    assert parsed["execs"] == ["/bin/prog"]
    assert parsed["errors"] == {"ENOENT": 1}
    assert parsed["exit"] == {"code": 7, "signal": None}
    assert parsed["total"] == 4
    assert parsed["unparsed_lines"] == 1  # baris <unfinished ...>


def test_strace_filter_rejects_shell_metacharacters(hello):
    with pytest.raises(ReaError) as error:
        strace_mod.trace(hello, [], trace_filter="write; id")
    assert error.value.code == "usage"


@pytest.mark.skipif(not shutil.which("strace"), reason="strace tidak ada")
def test_strace_records_the_write_of_the_program(hello, ptrace_ok):
    result = strace_mod.trace(hello, [], trace_filter="write")
    assert result["total"] == 1
    event = result["events"][0]
    assert event["name"] == "write" and event["ret"] == "8" and "hello 5" in event["args"]
    assert result["exit"]["code"] == 0
    assert result["program_output"] == "hello 5\n"


@pytest.mark.skipif(not shutil.which("strace"), reason="strace tidak ada")
def test_strace_lists_opened_libraries(hello, ptrace_ok):
    result = strace_mod.trace(hello, [])
    assert any("libc" in path for path in result["files"])
    assert result["execs"]


def test_ptrace_denied_is_explained_with_the_alternative():
    with pytest.raises(ReaError) as error:
        raise_if_ptrace_denied("strace", "strace: ptrace(PTRACE_TRACEME, ...): Operation not permitted")
    assert error.value.code == "not_supported"
    assert "--mode emu" in error.value.hint


# --- gdb -----------------------------------------------------------------------------------------------------------


def test_gdb_output_parsers():
    regs = gdb_mod.parse_registers("rax            0x1c                28\nrip            0x401136            0x401136 <add+4>\n")
    assert regs == {"rax": 0x1C, "rip": 0x401136}
    frames = gdb_mod.parse_backtrace("#0  add (a=2, b=3) at hello.c:2\n#1  0x0000000000401180 in main () at hello.c:3\n")
    assert [f["function"] for f in frames] == ["add", "main"]
    assert frames[1]["pc"] == 0x401180 and frames[0]["source"] == "hello.c:2"
    assert gdb_mod.parse_memory("0x7ffe:\t0x68\t0x65\n0x7fff:\t0x6c\n") == b"hel"
    assert gdb_mod.parse_examine("$sp:16") == ("$sp", 16)


def test_gdb_startup_noise_is_not_program_output():
    head = (
        "This GDB supports auto-downloading debuginfo from the following URLs:\n"
        "  <https://debuginfod.ubuntu.com>\n"
        "Debuginfod has been disabled.\n"
        "To make this setting permanent, add 'set debuginfod enabled off' to .gdbinit.\n"
        "Breakpoint 1 at 0x1157: file hello.c, line 2.\n"
        "[Thread debugging using libthread_db enabled]\n"
        "real output\n"
    )
    assert gdb_mod._NOISE.sub("", head).strip() == "real output"
    assert "set debuginfod enabled off" in gdb_mod._commands([], [], False)


@pytest.mark.parametrize("spec", ["add; shell id", "$(id)", "-ex", "a" * 200])
def test_gdb_rejects_dangerous_breakpoint_specs(hello, spec):
    with pytest.raises(ReaError) as error:
        gdb_mod.debug(hello, [], breakpoints=[spec])
    assert error.value.code == "usage"


@pytest.mark.skipif(not shutil.which("gdb"), reason="gdb tidak ada")
def test_gdb_stops_at_a_function_and_reads_state(hello, ptrace_ok):
    result = gdb_mod.debug(hello, [], breakpoints=["add"], examines=[("$sp", 8)])
    assert result["stopped"] and result["breakpoints"] == [{"spec": "add", "set": True, "hit": True}]
    assert result["backtrace"][0]["function"] == "add" and result["backtrace"][0]["args"] == "a=2, b=3"
    assert result["backtrace"][1]["function"] == "main"
    assert len(result["memory"][0]["hex"]) == 16
    assert "gdb" not in result["program_output"].lower() and "Breakpoint" not in result["program_output"]


@pytest.mark.skipif(not shutil.which("gdb"), reason="gdb tidak ada")
def test_gdb_reports_an_unknown_breakpoint_and_the_program_output(hello, ptrace_ok):
    result = gdb_mod.debug(hello, [], breakpoints=["no_such_function"])
    assert result["breakpoints"] == [{"spec": "no_such_function", "set": False, "hit": False}]
    assert not result["stopped"] and result["exit"] == {"code": 0}
    assert result["program_output"] == "hello 5"


@pytest.mark.skipif(not shutil.which("gdb"), reason="gdb tidak ada")
def test_gdb_without_breakpoints_stops_at_the_first_instruction(hello, ptrace_ok):
    result = gdb_mod.debug(hello, [])
    assert result["stopped"] and result["backtrace"] and result["registers"]


# --- Frida ---------------------------------------------------------------------------------------------------------


def test_hook_specs_are_validated_and_never_become_code():
    with pytest.raises(ReaError):
        build_script([])
    for bad in ('puts"); evil(); ("', "a b", "x;y", "1abc"):
        with pytest.raises(ReaError) as error:
            build_script([bad])
        assert error.value.code == "usage"
    script = build_script(["puts", "0x401136"])
    assert 'JSON.parse("[\\"puts\\", \\"0x401136\\"]")' in script


def test_frida_missing_module_explains_how_to_install(monkeypatch):
    import builtins

    real_import = builtins.__import__

    def fake(name, *args, **kwargs):
        if name == "frida":
            raise ImportError("no frida")
        return real_import(name, *args, **kwargs)

    monkeypatch.setattr(builtins, "__import__", fake)
    from rea.dynamic.frida_runner import hook

    with pytest.raises(ReaError) as error:
        hook("/bin/true", [], ["puts"])
    assert error.value.code == "missing_dependency" and "pip install" in error.value.hint


def test_frida_hooks_a_function_and_sees_its_arguments(hello, ptrace_ok):
    pytest.importorskip("frida")
    from rea.dynamic.frida_runner import hook

    result = hook(hello, [], ["add"])
    assert result["unresolved"] == []
    assert [c["args"][:2] for c in result["calls"] if c["fn"] == "add"] == [["0x2", "0x3"]]
    assert result["program_output"] == "hello 5\n"


# --- CLI -----------------------------------------------------------------------------------------------------------


def _last(out):
    return json.loads(out.stdout.strip().splitlines()[-1])


@pytest.mark.skipif(not shutil.which("strace"), reason="strace tidak ada")
def test_cli_run_strace(run_rea, hello, ptrace_ok):
    payload = _last(run_rea("run", hello, "--mode", "strace", "--trace", "write", "--json"))
    assert payload["ok"] and payload["mode"] == "full-dynamic" and payload["data"]["total"] == 1


@pytest.mark.skipif(not shutil.which("gdb"), reason="gdb tidak ada")
def test_cli_run_gdb_with_a_bad_breakpoint_warns(run_rea, hello, ptrace_ok):
    payload = _last(run_rea("run", hello, "--mode", "gdb", "--break", "nope", "--json"))
    assert payload["ok"] and any("nope" in w for w in payload["warnings"])


def test_cli_run_gdb_without_gdb_names_the_package(run_rea, hello):
    payload = _last(run_rea("run", hello, "--mode", "gdb", "--json", env={"PATH": "/nonexistent"}))
    assert payload["error"]["code"] == "missing_dependency" and "apt install gdb" in payload["error"]["hint"]


def test_cli_run_frida_needs_a_hook(run_rea, hello):
    pytest.importorskip("frida")
    payload = _last(run_rea("run", hello, "--mode", "frida", "--json"))
    assert payload["error"]["code"] == "usage"


@pytest.mark.skipif(not shutil.which("gdb"), reason="gdb tidak ada")
def test_cli_auto_picks_a_full_dynamic_backend_when_ptrace_works(run_rea, hello, ptrace_ok):
    payload = _last(run_rea("run", hello, "--break", "add", "--json"))
    assert payload["ok"] and payload["mode"] == "full-dynamic" and payload["data"]["mode"] == "gdb"
