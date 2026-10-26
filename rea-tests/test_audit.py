"""Regression tests for defects found in the audit of REA 0.9.0; each failed before its fix."""

import json
import os
import resource
import shutil
import time
import zipfile
import zlib

import pytest

from rea import cli
from rea.analysis import strings as strings_mod
from rea.dynamic import proc
from rea.parsers import firmware


def _last_json(text: str) -> dict:
    return json.loads(text.strip().splitlines()[-1])


def test_usage_error_still_prints_the_json_line(capsys):
    code = cli.main(["id", "--json"])  # the file argument is missing
    out = capsys.readouterr().out
    assert code == 2
    result = _last_json(out)
    assert result["schema"] == 1 and result["ok"] is False and result["error"]["code"] == "usage"


def test_help_and_version_exit_without_json(capsys):
    assert cli.main(["--version"]) == 0
    assert "{" not in capsys.readouterr().out


def test_truncated_elf_is_bad_format_not_internal(tmp_path, capsys):
    source = shutil.which("ls")
    if not source:
        pytest.skip("no ls binary")
    broken = tmp_path / "trunc.elf"
    broken.write_bytes(open(source, "rb").read(4000))
    cli.main(["analyze", str(broken), "--json"])
    result = _last_json(capsys.readouterr().out)
    assert result["ok"] is False
    assert result["error"]["code"] == "bad_format"


def test_scoring_a_separator_heavy_run_is_fast():
    start = time.monotonic()
    strings_mod.score("a." * 2048)
    assert time.monotonic() - start < 0.5


def test_string_runs_are_cut_at_4096(tmp_path):
    blob = tmp_path / "long.bin"
    blob.write_bytes(b"a." * 100_000)
    pattern = strings_mod._ASCII.pattern % 4
    import re

    longest = max(len(m.group(0)) for m in re.finditer(pattern.encode() if isinstance(pattern, str) else pattern, blob.read_bytes()))
    assert longest <= 4096


def test_run_limited_does_not_buffer_endless_output():
    if not shutil.which("yes"):
        pytest.skip("no yes binary")
    before = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    result = proc.run_limited(["yes"], 1.0)
    grown_mb = (resource.getrusage(resource.RUSAGE_SELF).ru_maxrss - before) / 1024
    assert result.timed_out
    assert len(result.stdout) == proc.MAX_OUTPUT
    assert grown_mb < 200  # about 1 GB per second before the fix


def test_zip_with_corrupt_member_fails_cleanly(tmp_path):
    archive = tmp_path / "x.zip"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("a.txt", "hello world " * 200)
    data = bytearray(archive.read_bytes())
    data[40:60] = b"\xff" * 20  # damage the deflate stream, keep the directory
    archive.write_bytes(bytes(data))
    result = firmware._extract_zip(str(archive), str(tmp_path / "out"))
    assert result["status"] == "failed"


def test_cpio_tool_neutralises_dotdot_entries(tmp_path):
    """The audit feared path traversal through cpio; GNU cpio strips '../', so the extractor can rely on it."""
    cpio = shutil.which("cpio")
    if not cpio:
        pytest.skip("no cpio")
    import subprocess

    def entry(name, mode, data=b""):
        nm = name.encode() + b"\0"
        header = b"070701" + b"".join(b"%08X" % v for v in (1, mode, 0, 0, 1, 0, len(data), 0, 0, 0, 0, len(nm), 0))
        out = header + nm
        out += b"\0" * ((4 - len(out) % 4) % 4) + data
        return out + b"\0" * ((4 - len(data) % 4) % 4)

    archive = entry("../../escaped.txt", 0o100644, b"x") + entry("TRAILER!!!", 0)
    out = tmp_path / "out"
    out.mkdir()
    subprocess.run([cpio, "-idm", "--no-absolute-filenames", "--quiet"], cwd=out, input=archive, capture_output=True)
    assert not (tmp_path / "escaped.txt").exists()
    assert not os.path.exists(tmp_path.parent / "escaped.txt")
