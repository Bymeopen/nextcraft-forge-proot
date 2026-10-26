import json
import os
import os
import re
import shutil
import subprocess

import pytest

pytest.importorskip("capstone")
pytest.importorskip("elftools")

from rea.analysis import disasm, strings  # noqa: E402
from rea.parsers import elf  # noqa: E402
from rea.result import ReaError  # noqa: E402

SOURCE = r'''
#include <stdio.h>
int helper(int a) { return a * 3 + 1; }
int main(void) {
    puts("hello rea");
    puts("https://example.com/api/v1");
    return helper(2);
}
'''


@pytest.fixture(scope="module")
def sample(tmp_path_factory):
    compiler = shutil.which("gcc") or shutil.which("cc")
    if not compiler:
        pytest.skip("tidak ada kompilator C")
    directory = tmp_path_factory.mktemp("analyze")
    (directory / "t.c").write_text(SOURCE)
    # -O0 -fno-pie ... bukan perlu; default sudah cukup. Tanpa -s agar simbol ada.
    subprocess.run([compiler, "-O0", "-o", str(directory / "t"), str(directory / "t.c")], check=True)
    return str(directory / "t")


def test_elf_summary(sample):
    summary = elf.summarize(sample)
    assert summary["class"] in (32, 64)
    assert summary["stripped"] is False
    assert any(f["name"] == "main" for f in summary["functions"])
    assert any(f["name"] == "helper" for f in summary["functions"])
    assert any(s["name"] == ".text" and "X" in s["flags"] for s in summary["sections"])
    assert any(seg["type"] == "LOAD" for seg in summary["segments"])
    assert any(lib.startswith("libc") for lib in summary["needed"])
    assert "puts" in "".join(summary["imports"])


def test_strings_found_with_offsets_and_scores(sample):
    result = strings.extract(sample)
    texts = {item["text"]: item for item in result["strings"]}
    assert "hello rea" in texts
    assert texts["https://example.com/api/v1"]["score"] >= 5
    with open(sample, "rb") as handle:
        data = handle.read()
    assert data[texts["hello rea"]["offset"]:].startswith(b"hello rea")


def test_interesting_filter_keeps_urls_not_symbol_versions(sample):
    texts = [item["text"] for item in strings.extract(sample, only_interesting=True)["strings"]]
    assert "https://example.com/api/v1" in texts
    assert not any("GLIBC_2" in t and "@" in t for t in texts), texts


def test_utf16_and_limits(tmp_path):
    path = tmp_path / "w.bin"
    path.write_bytes(b"\x00\x01" + "Secret Token".encode("utf-16-le") + b"\x00\x00" + b"plain ascii here")
    found = {item["text"]: item for item in strings.extract(str(path))["strings"]}
    assert found["Secret Token"]["encoding"] == "utf16le"
    assert found["plain ascii here"]["encoding"] == "ascii"
    assert found["Secret Token"]["score"] >= 3
    limited = strings.extract(str(path), max_items=1)
    assert limited["truncated"] is True and len(limited["strings"]) == 1 and limited["total"] == 2
    empty = tmp_path / "empty"
    empty.write_bytes(b"")
    assert strings.extract(str(empty))["strings"] == []


def _objdump_instructions(binary, name, count):
    objdump = shutil.which("objdump")
    if not objdump:
        pytest.skip("tidak ada objdump")
    text = subprocess.run([objdump, "-d", "-M", "intel", "--no-show-raw-insn", binary], capture_output=True, text=True, check=True).stdout
    block = text.split(f"<{name}>:")[1]
    rows = []
    for line in block.splitlines()[1:]:
        match = re.match(r"\s*([0-9a-f]+):\s+(\S+)", line)
        if match:
            rows.append((int(match.group(1), 16), match.group(2)))
        if len(rows) == count:
            break
    return rows


def test_main_disassembly_matches_objdump(sample):
    import platform

    if platform.machine() not in ("x86_64", "aarch64"):
        pytest.skip("arsitektur uji tidak dikenal")
    ours = disasm.disassemble(sample, symbol="main", count=5)
    assert ours["function"] == "main" and len(ours["instructions"]) == 5
    expected = _objdump_instructions(sample, "main", 5) if platform.machine() == "x86_64" else None
    if expected is not None:
        assert [(i["addr"], i["mnemonic"]) for i in ours["instructions"]] == expected
    # Alamat berurutan dan ukuran konsisten, apa pun arsitekturnya.
    addresses = [i["addr"] for i in ours["instructions"]]
    sizes = [i["size"] for i in ours["instructions"]]
    assert addresses[0] == ours["address"]
    assert all(addresses[i] + sizes[i] == addresses[i + 1] for i in range(len(addresses) - 1))


def test_disassembly_errors_and_entry(sample):
    with pytest.raises(ReaError) as missing:
        disasm.disassemble(sample, symbol="tidak_ada")
    assert missing.value.code == "symbol_not_found" and missing.value.hint
    entry = disasm.disassemble(sample, count=3)
    assert entry["function"] in ("entry", "_start") or entry["instructions"]
    with pytest.raises(ReaError) as bad:
        disasm.disassemble(sample, address=0x7FFFFFFF, count=3)
    assert bad.value.code == "bad_format"


def test_non_elf_is_rejected_cleanly(tmp_path):
    path = tmp_path / "notelf"
    path.write_bytes(b"MZ" + b"\x00" * 100)
    with pytest.raises(ReaError) as error:
        elf.summarize(str(path))
    assert error.value.code == "bad_format"


def test_cli_analyze_json(run_rea, tmp_path, sample):
    done = run_rea("analyze", sample, "--strings", "--interesting", "--disasm", "main", "--count", "4", "--json", cwd=tmp_path)
    assert done.returncode == 0, done.stderr
    result = json.loads([line for line in done.stdout.splitlines() if line.startswith("{")][-1])
    assert result["ok"] is True
    assert result["data"]["identify"]["type"] == "ELF"
    assert any(s["text"].startswith("https://") for s in result["data"]["strings"]["strings"])
    assert len(result["data"]["disasm"]["instructions"]) == 4

    bad = run_rea("analyze", sample, "--disasm", "tidak_ada", "--json", cwd=tmp_path)
    assert bad.returncode == 1
    error = json.loads([line for line in bad.stdout.splitlines() if line.startswith("{")][-1])["error"]
    assert error["code"] == "symbol_not_found"


def test_report_markdown_has_the_expected_sections(sample, tmp_path, run_rea):
    out = tmp_path / "out" / "r.md"
    done = run_rea("report", sample, "-o", str(out), "--notes", "diperiksa manual | aman", "--json", cwd=tmp_path)
    assert done.returncode == 0, done.stderr
    result = json.loads([line for line in done.stdout.splitlines() if line.startswith("{")][-1])
    assert result["ok"] is True and result["data"]["path"] == str(out)
    text = out.read_text(encoding="utf-8")
    for heading in ("# Laporan REA:", "## Ringkasan", "## ELF", "### Fungsi", "## String menarik", "## Disassembly: main", "## Catatan analis"):
        assert heading in text, heading
    assert "https://example.com/api/v1" in text
    assert "diperiksa manual | aman" in text
    assert "SHA256" in text


def test_report_default_path_and_non_elf(run_rea, tmp_path):
    sample_text = tmp_path / "catatan.txt"
    sample_text.write_text("kunci: https://contoh.test/x\n")
    done = run_rea("report", str(sample_text), "--json", cwd=tmp_path)
    assert done.returncode == 0, done.stderr
    result = json.loads([line for line in done.stdout.splitlines() if line.startswith("{")][-1])
    assert result["data"]["path"] == os.path.join(".rea", "catatan.txt.report.md")
    text = (tmp_path / ".rea" / "catatan.txt.report.md").read_text(encoding="utf-8")
    assert "belum tersedia" in text and "https://contoh.test/x" in text


def test_report_builder_escapes_table_cells():
    from rea.report.markdown import build_report

    data = {
        "identify": {"path": "a|b", "type": "unknown", "size": 3, "md5": "m", "sha256": "s", "entropy": 1.0},
        "strings": {"strings": [{"offset": 1, "score": 5, "text": "x|y`z", "encoding": "ascii"}], "total": 1, "truncated": False},
    }
    text = build_report(data, notes="")
    assert "x|y" in text  # dalam kode inline; pipa di dalam backtick tidak merusak sel karena sudah dibungkus
    assert "`x|y'z`" in text


LOOP_SOURCE = r'''
#include <stdio.h>
int sum(int n) { int s = 0; for (int i = 0; i < n; i++) s += i; return s; }
int straight(int n) { return n * 2 + 1; }
int main(void) { puts("hello rea"); return sum(10) + straight(3); }
'''


@pytest.fixture(scope="module")
def loop_elf(tmp_path_factory):
    compiler = shutil.which("gcc") or shutil.which("cc")
    if not compiler:
        pytest.skip("tidak ada kompilator C")
    directory = tmp_path_factory.mktemp("loop")
    (directory / "loop.c").write_text(LOOP_SOURCE)
    subprocess.run([compiler, "-O0", "-o", str(directory / "loop"), str(directory / "loop.c")], check=True)
    return str(directory / "loop")


def test_cfg_of_a_loop_has_exactly_one_back_edge(loop_elf):
    from rea.cli import _cfg_for

    graph = _cfg_for(loop_elf, "sum")
    assert graph["back_edges"] == 1
    assert sum(1 for e in graph["edges"] if e["back"]) == 1
    assert len(graph["blocks"]) >= 3
    assert "loop" in graph["ascii"]
    # Blok pertama adalah titik masuk; semua edge menunjuk blok yang ada.
    assert graph["blocks"][0]["start"] == graph["address"]
    assert all(0 <= e["from"] < len(graph["blocks"]) and 0 <= e["to"] < len(graph["blocks"]) for e in graph["edges"])
    assert graph["dot"].startswith("digraph") and "loop" in graph["dot"]


def test_cfg_without_a_loop_has_none(loop_elf):
    from rea.cli import _cfg_for

    graph = _cfg_for(loop_elf, "straight")
    assert graph["back_edges"] == 0
    assert len(graph["blocks"]) == 1 and graph["edges"] == []


def test_cfg_unknown_symbol_is_a_clear_error(loop_elf):
    from rea.cli import _cfg_for

    with pytest.raises(ReaError) as error:
        _cfg_for(loop_elf, "tidak_ada")
    assert error.value.code == "symbol_not_found"


def test_xrefs_link_strings_and_calls_to_functions(loop_elf):
    import platform

    from rea.analysis import xrefs

    found = strings.extract(loop_elf, max_items=3000)["strings"]
    result = xrefs.analyze_xrefs(loop_elf, found)
    graph = result["call_graph"]
    assert {"sum", "straight"} <= set(graph["main"])
    if platform.machine() in ("x86_64", "aarch64"):
        assert "puts" in graph["main"], graph  # stub PLT diberi nama impornya
        hello = [row for row in result["strings"] if row["text"] == "hello rea"]
        assert hello and {r["function"] for r in hello[0]["refs"]} == {"main"}


def test_entropy_map_and_sections(loop_elf, tmp_path):
    from rea.analysis import entropy

    mapped = entropy.entropy_map(loop_elf)
    assert mapped["blocks"] and all(0.0 <= b["entropy"] <= 8.0 for b in mapped["blocks"])
    assert len(mapped["spark"]) <= 64
    sections = entropy.section_entropy(loop_elf, elf.summarize(loop_elf)["sections"])
    assert any(s["name"] == ".text" for s in sections)
    noise = tmp_path / "noise"
    noise.write_bytes(os.urandom(64 * 1024))
    assert entropy.entropy_map(str(noise))["high_ratio"] > 0.9
    assert entropy.entropy_map(str(noise), block=1024, max_blocks=8)["block_size"] == 8192  # blok diperbesar agar jumlahnya terbatas


def test_cli_analyze_cfg_xrefs_entropy_json(run_rea, tmp_path, loop_elf):
    done = run_rea("analyze", loop_elf, "--cfg", "sum", "--xrefs", "--entropy", "--json", cwd=tmp_path)
    assert done.returncode == 0, done.stderr
    result = json.loads([line for line in done.stdout.splitlines() if line.startswith("{")][-1])
    data = result["data"]
    assert data["cfg"]["back_edges"] == 1 and "dot" not in data["cfg"]
    assert data["xrefs"]["functions_scanned"] >= 3
    assert data["entropy"]["map"]["blocks"]
    with_dot = run_rea("analyze", loop_elf, "--cfg", "sum", "--dot", "--json", cwd=tmp_path)
    assert "digraph" in json.loads([line for line in with_dot.stdout.splitlines() if line.startswith("{")][-1])["data"]["cfg"]["dot"]
    text_only = tmp_path / "x.txt"
    text_only.write_text("halo")
    bad = run_rea("analyze", str(text_only), "--cfg", "--json", cwd=tmp_path)
    assert bad.returncode == 1


def test_report_includes_the_new_sections(loop_elf, tmp_path, run_rea):
    out = tmp_path / "r.md"
    done = run_rea("report", loop_elf, "-o", str(out), "--json", cwd=tmp_path)
    assert done.returncode == 0, done.stderr
    text = out.read_text(encoding="utf-8")
    for heading in ("## Entropi", "## CFG: main", "## Call graph"):
        assert heading in text, heading
    assert "sum" in text and "straight" in text
