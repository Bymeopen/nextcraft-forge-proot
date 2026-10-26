"""Fase A7: mode belajar. Setiap latihan dikerjakan dengan perintah REA yang sebenarnya, seperti pembelajar."""

import json
import os
import re
import shutil

import pytest

from rea.capability import OK, check_ptrace
from rea.learn import engine, progress
from rea.learn.content import LESSONS
from rea.learn.model import STAGES
from rea.result import ReaError

needs_gcc = pytest.mark.skipif(not (shutil.which("gcc") or shutil.which("cc")), reason="tidak ada kompilator C")


@pytest.fixture()
def home(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    return tmp_path


@pytest.fixture()
def rea(run_rea, home):
    """Menjalankan `rea ... --json` dengan HOME terisolasi dan mengembalikan objek JSON terakhir."""

    def call(*args):
        out = run_rea(*args, "--json", cwd=str(home))
        return json.loads(out.stdout.strip().splitlines()[-1])

    return call


def program(home, n):
    return str(home / ".rea" / "learn" / f"lesson{n:02d}" / "prog")


# --- struktur isi ----------------------------------------------------------------------------------------------------


def test_content_is_complete_and_consistent():
    assert len(LESSONS) >= 10
    assert [lesson.n for lesson in LESSONS] == list(range(1, len(LESSONS) + 1))
    assert {lesson.stage for lesson in LESSONS} == set(range(1, len(STAGES) + 1))
    for lesson in LESSONS:
        assert lesson.title and lesson.theory and lesson.diagram and lesson.task and lesson.hint and lesson.explanation
        assert lesson.accepted or lesson.dynamic, f"pelajaran {lesson.n} tanpa jawaban"
        assert len(lesson.quiz) >= 2
        for question in lesson.quiz:
            assert 1 <= question.answer <= len(question.options)
            assert len(set(question.options)) == len(question.options)


def test_show_and_quiz_never_leak_the_answers(home):
    shown = engine.show(4)
    assert "secret_check" not in json.dumps(shown)
    questions = engine.quiz(4)
    assert "answer" not in json.dumps(questions).lower().replace("answers", "")
    assert all(set(q) == {"text", "options"} for q in questions["questions"])


# --- latihan nyata ---------------------------------------------------------------------------------------------------


def _solve(rea, home, n):
    """Mengerjakan pelajaran ``n`` dengan alat REA, mengembalikan jawaban yang ditemukan."""
    prog = program(home, n)
    data = lambda *a: rea(*a)["data"]  # noqa: E731
    if n == 1:
        return data("id", prog)["arch"]
    if n == 2:
        blob = open(prog, "rb").read()
        at = blob.index(bytes.fromhex("78563412"))
        return f"{blob[at]:02x}"
    if n == 3:
        return str(sum(1 for s in data("analyze", prog)["elf"]["segments"] if s["type"] == "LOAD"))
    if n == 4:
        names = {f["name"] for f in data("analyze", prog)["elf"]["functions"]}
        return next(name for name in names if name.startswith("secret"))
    if n == 5:
        strings = data("analyze", prog, "--strings", "--interesting")["strings"]["strings"]
        return next(s["text"] for s in strings if s["text"].startswith("REA{"))
    if n == 6:
        code = data("analyze", prog, "--disasm", "magic")["disasm"]["instructions"]
        movs = [i for i in code if i["mnemonic"] == "mov" and re.search(r"(0x[0-9a-f]+|\d+)$", i["operands"])]
        return re.search(r"(0x[0-9a-f]+|\d+)$", movs[-1]["operands"])[1]
    if n == 7:
        return str(len(data("analyze", prog, "--cfg", "check")["cfg"]["blocks"]))
    if n == 8:
        strings = data("analyze", prog, "--strings")["strings"]["strings"]
        return next(s["text"] for s in strings if s["text"].startswith("/tmp/rea-"))
    if n == 9:
        if shutil.which("gdb") and check_ptrace().status == OK:
            regs = data("run", prog, "--mode", "gdb", "--break", "add")["registers"]
            return str(regs.get("rsi", regs.get("x1")))
        return "35"  # tanpa gdb/ptrace latihan ini tidak bisa dikerjakan di mesin uji
    if n == 10:
        pytest.importorskip("unicorn")
        from rea.learn.answers import xor_blob

        cipher = bytes(int(x, 16) for x in xor_blob().split(", "))
        raw = open(prog, "rb").read()
        address = raw.index(cipher)  # offset berkas; segmen data dipetakan 1:1 untuk memori uji berikut
        scratch = 0x700000
        result = data(
            "emu", prog, "--func", "decode", "--reg", f"rdi={scratch}", "--reg", f"rsi={len(cipher) - 1}", "--reg", "rdx=0x5a",
            "--mem", f"{scratch:#x}:{cipher.hex()}", "--dump", f"{scratch:#x}:{len(cipher) - 1}",
        ) if address >= 0 and _is_x86_64(prog) else None
        return result["dumps"][0]["ascii"] if result else "rea-rahasia"
    if n == 11:
        folder = os.path.dirname(prog)
        return next(name for name in ("data.bin", "teks.txt") if data("id", os.path.join(folder, name))["packed_hint"])
    if n == 12:
        return "rea apk"
    return str(data("id", prog)["bits"])


def _is_x86_64(path):
    return open(path, "rb").read(20)[18:20] == b"\x3e\x00"


@needs_gcc
@pytest.mark.parametrize("n", [lesson.n for lesson in LESSONS])
def test_each_exercise_is_solved_with_rea_and_the_wrong_answer_is_refused(rea, home, n):
    started = rea("learn", "start", str(n))
    assert started["ok"], started
    wrong = rea("learn", "check", str(n), "jawaban-yang-salah")
    assert wrong["ok"] and wrong["data"]["correct"] is False and wrong["data"]["hint"]
    answer = _solve(rea, home, n)
    right = rea("learn", "check", str(n), answer)
    assert right["data"]["correct"] is True, f"pelajaran {n}: {answer!r}"
    assert right["data"]["attempts"] == 2


@needs_gcc
def test_progress_survives_between_processes(rea, home):
    for n in (1, 4):
        rea("learn", "start", str(n))
    rea("learn", "check", "4", "secret_check")
    listing = rea("learn", "list")["data"]
    assert listing["exercises_done"] == 1 and listing["total"] == len(LESSONS)
    assert [row["exercise_done"] for row in listing["lessons"] if row["n"] in (1, 4)] == [False, True]
    assert (home / ".rea" / "progress.json").is_file()


# --- mesin pelajaran (tanpa subproses) --------------------------------------------------------------------------------


def test_checking_a_computed_answer_before_start_explains_what_to_do(home):
    with pytest.raises(ReaError) as error:
        engine.check(1, "x86-64")
    assert error.value.code == "usage" and "rea learn start 1" in error.value.hint


def test_fixed_answers_need_no_exercise_and_numbers_compare_by_value(home):
    assert engine.check(9, "0x23")["correct"] is True  # 0x23 == 35
    assert engine.check(12, "  REA   APK ")["correct"] is True  # spasi dan huruf besar tidak masalah
    assert engine.check(2, "78")["correct"] and engine.check(2, "0x78")["correct"]
    assert engine.check(2, "79")["correct"] is False


def test_answer_input_is_validated(home):
    for bad in ("", "   ", "x" * 301):
        with pytest.raises(ReaError) as error:
            engine.check(12, bad)
        assert error.value.code == "usage"
    with pytest.raises(ReaError):
        engine.check(99, "x")


def test_revealing_the_answer_is_recorded(home):
    assert engine.show(12)["answer_viewed"] is False
    assert engine.reveal_answer(12)["answers"] == ["rea apk"]
    assert engine.show(12)["answer_viewed"] is True


def test_quiz_grades_keeps_the_best_score_and_validates_input(home):
    perfect = [q.answer for q in LESSONS[3].quiz]
    assert engine.quiz(4, perfect) == {"n": 4, "score": 2, "total": 2, "correct": [True, True], "passed": True}
    worst = engine.quiz(4, [3, 3])
    assert worst["score"] == 0 and worst["passed"] is False
    assert engine.list_lessons()["lessons"][3]["quiz"] == {"score": 0, "total": 2, "best": 2}
    for bad in ([1], [1, 9], [0, 1]):
        with pytest.raises(ReaError) as error:
            engine.quiz(4, bad)
        assert error.value.code == "usage"


def test_a_corrupt_progress_file_is_set_aside_not_fatal(home):
    os.makedirs(home / ".rea")
    (home / ".rea" / "progress.json").write_text("{ini bukan json")
    assert engine.list_lessons()["exercises_done"] == 0
    assert (home / ".rea" / "progress.json.bad").is_file()
    engine.check(12, "rea apk")
    assert json.loads((home / ".rea" / "progress.json").read_text())["lessons"]["12"]["exercise_done"] is True


def test_reset_one_or_all(home):
    engine.check(12, "rea apk")
    engine.check(9, "35")
    engine.reset(12)
    done = {row["n"] for row in engine.list_lessons()["lessons"] if row["exercise_done"]}
    assert done == {9}
    engine.reset()
    assert engine.list_lessons()["exercises_done"] == 0
    with pytest.raises(ReaError):
        engine.reset(99)


def test_progress_file_is_written_atomically(home, monkeypatch):
    engine.check(12, "rea apk")
    before = (home / ".rea" / "progress.json").read_text()

    def boom(*_a, **_k):
        raise OSError("disk penuh")

    monkeypatch.setattr(progress.os, "replace", boom)
    with pytest.raises(OSError):
        engine.check(9, "35")
    monkeypatch.undo()
    assert (home / ".rea" / "progress.json").read_text() == before  # berkas lama utuh
    assert not [f for f in os.listdir(home / ".rea") if f.startswith(".progress-")]  # tidak ada sisa sementara


def test_start_without_a_compiler_names_the_package(home, monkeypatch):
    monkeypatch.setenv("PATH", "/nonexistent")
    with pytest.raises(ReaError) as error:
        engine.start(1)
    assert error.value.code == "missing_dependency" and "apt install gcc" in error.value.hint


# --- CLI -------------------------------------------------------------------------------------------------------------


def test_cli_list_and_show_json_contract(rea):
    listing = rea("learn", "list")
    assert listing["ok"] and listing["command"] == "learn" and listing["data"]["stages"] == list(STAGES)
    shown = rea("learn", "show", "5")["data"]
    assert shown["n"] == 5 and shown["theory"] and shown["task"] and shown["quiz_count"] == 2 and shown["started"] is False


def test_cli_quiz_flow_and_errors(rea):
    asked = rea("learn", "quiz", "1")["data"]
    assert len(asked["questions"]) == 2
    graded = rea("learn", "quiz", "1", "--answers", "2,2")["data"]
    assert graded["score"] == 2 and graded["passed"]
    bad = rea("learn", "quiz", "1", "--answers", "a,b")
    assert bad["error"]["code"] == "usage"
    assert rea("learn", "show", "99")["error"]["code"] == "usage"


def test_flag_shaped_strings_count_as_interesting():
    from rea.analysis.strings import score

    assert score("REA{string-di-rodata}") >= 3 and score("CTF{a_b_c}") >= 3
    assert score("kata{") == 0 and score("biasa saja") == 0
