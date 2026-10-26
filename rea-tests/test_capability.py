from rea import capability
from rea.capability import FAIL, OK, WARN, Check, determine_mode


def test_mode_rules():
    base = [Check("ptrace", "ptrace", FAIL), Check("gdb", "gdb", WARN), Check("qemu", "qemu", WARN), Check("py_unicorn", "u", WARN)]
    assert determine_mode(base) == "static-only"
    full = [Check("ptrace", "ptrace", OK), Check("gdb", "gdb", OK)]
    assert determine_mode(full) == "full-dynamic"
    assert determine_mode([Check("ptrace", "p", OK), Check("gdb", "g", WARN), Check("qemu", "q", OK)]) == "emulated-dynamic"
    assert determine_mode([Check("py_unicorn", "u", OK)]) == "emulated-dynamic"


def test_ptrace_check_returns_a_definite_answer():
    # Hasilnya bergantung pada mesin (sandbox CI sering memblokir ptrace); yang diuji: ada jawaban pasti dan tidak menggantung.
    check = capability.check_ptrace()
    assert check.id == "ptrace" and check.status in (OK, FAIL)


def test_doctor_reports_every_check_with_status():
    mode, checks = capability.run_doctor(".")
    assert mode in (capability.MODE_FULL, capability.MODE_EMULATED, capability.MODE_STATIC)
    ids = {c.id for c in checks}
    assert {"python", "env", "ptrace", "gdb", "strace", "qemu", "py_capstone", "py_elftools", "memory", "disk"} <= ids
    assert all(c.status in (OK, WARN, FAIL) for c in checks)
    # Peringatan dan kegagalan harus punya saran, supaya aplikasi bisa menampilkannya.
    assert all(c.hint for c in checks if c.status != OK and c.id not in ("env", "memory", "disk", "python", "ptrace"))


def test_missing_tool_is_a_warning_not_a_crash():
    check = capability.check_tool("x", "x", "alat-yang-tidak-ada", ["--version"], "apt install x")
    assert check.status == WARN and check.hint == "apt install x"
    required = capability.check_tool("x", "x", "alat-yang-tidak-ada", ["--version"], "apt install x", required=True)
    assert required.status == FAIL
