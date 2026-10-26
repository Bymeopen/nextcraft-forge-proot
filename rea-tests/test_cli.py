import json


def last_json(stdout):
    lines = [line for line in stdout.splitlines() if line.startswith("{")]
    assert lines, f"tidak ada baris JSON di stdout: {stdout!r}"
    return json.loads(lines[-1])


def test_doctor_json_contract(run_rea, tmp_path):
    done = run_rea("doctor", "--json", cwd=tmp_path)
    assert done.returncode == 0, done.stderr
    result = last_json(done.stdout)
    assert result["schema"] == 1 and result["ok"] is True and result["command"] == "doctor"
    assert result["mode"] in ("full-dynamic", "emulated-dynamic", "static-only")
    assert all({"id", "label", "status", "detail", "hint"} <= set(c) for c in result["data"]["checks"])
    # Pemberitahuan penggunaan sah muncul sekali, ke stderr; stdout hanya JSON.
    assert "penggunaan yang sah" in done.stderr
    again = run_rea("doctor", "--json", cwd=tmp_path)
    assert "penggunaan yang sah" not in again.stderr


def test_id_json_and_error_contract(run_rea, tmp_path, hello_elf):
    ok = run_rea("id", hello_elf, "--json", cwd=tmp_path)
    assert ok.returncode == 0, ok.stderr
    data = last_json(ok.stdout)["data"]
    assert data["type"] == "ELF" and len(data["sha256"]) == 64

    missing = run_rea("id", "tidak-ada.bin", "--json", cwd=tmp_path)
    assert missing.returncode == 1
    result = last_json(missing.stdout)
    assert result["ok"] is False and result["error"]["code"] == "file_not_found" and result["error"]["hint"]


def test_version_and_usage(run_rea, tmp_path):
    assert run_rea("--version", cwd=tmp_path).returncode == 0
    assert run_rea(cwd=tmp_path).returncode == 2
