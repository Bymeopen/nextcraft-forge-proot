"""Logika ``rea learn``: daftar, tampilan, latihan, pemeriksaan jawaban, kuis, kunci jawaban, dan progres."""

from __future__ import annotations

import os
import shutil
import subprocess
from typing import Any

from ..result import E_BAD_FORMAT, E_MISSING_DEP, E_USAGE, ReaError
from . import answers, progress
from .content import BY_NUMBER, LESSONS
from .model import STAGES, Lesson

MAX_ANSWER = 300
PASS_RATIO = 0.5


def lesson(n: int) -> Lesson:
    found = BY_NUMBER.get(n)
    if found is None:
        raise ReaError(E_USAGE, f"Pelajaran {n} tidak ada.", f"Pilih 1 sampai {len(LESSONS)}; daftar: rea learn list")
    return found


def exercise_dir(n: int) -> str:
    return os.path.join(progress.root_dir(), "learn", f"lesson{n:02d}")


def _summary(item: Lesson, record: dict[str, Any] | None) -> dict[str, Any]:
    quiz = (record or {}).get("quiz")
    return {
        "n": item.n,
        "stage": item.stage,
        "stage_name": item.stage_name,
        "title": item.title,
        "exercise_done": bool((record or {}).get("exercise_done")),
        "quiz": None if not quiz else {"score": quiz["score"], "total": quiz["total"], "best": quiz["best"]},
        "has_exercise": item.source is not None or item.files is not None,
    }


def list_lessons() -> dict[str, Any]:
    data = progress.load()
    rows = [_summary(item, data["lessons"].get(str(item.n))) for item in LESSONS]
    done = sum(1 for row in rows if row["exercise_done"])
    return {"stages": list(STAGES), "lessons": rows, "total": len(rows), "exercises_done": done}


def show(n: int) -> dict[str, Any]:
    item = lesson(n)
    record = progress.load()["lessons"].get(str(n))
    folder = exercise_dir(n)
    return {
        **_summary(item, record),
        "theory": item.theory,
        "diagram": item.diagram,
        "task": item.task,
        "hint": item.hint,
        "requires": list(item.requires),
        "quiz_count": len(item.quiz),
        "started": os.path.isdir(folder),
        "exercise_dir": folder if os.path.isdir(folder) else None,
        "answer_viewed": bool((record or {}).get("answer_viewed")),
        "attempts": int((record or {}).get("attempts", 0)),
    }


def _compiler() -> str:
    found = shutil.which("gcc") or shutil.which("cc")
    if found is None:
        raise ReaError(E_MISSING_DEP, "Tidak ada kompilator C untuk membuat biner latihan.", "Pasang: apt install gcc")
    return found


def start(n: int) -> dict[str, Any]:
    """Menyiapkan folder latihan: mengompilasi program C-nya (atau membuat berkas data) di mesin ini."""
    item = lesson(n)
    folder = exercise_dir(n)
    os.makedirs(folder, exist_ok=True)
    created: list[str] = []
    if item.source is not None:
        source = item.source.replace("{BLOB}", answers.xor_blob())
        path = os.path.join(folder, "prog.c")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(source)
        binary = os.path.join(folder, "prog")
        try:
            done = subprocess.run([_compiler(), "-O0", "-o", binary, path], capture_output=True, text=True, timeout=120, check=False)
        except subprocess.TimeoutExpired as error:
            raise ReaError(E_BAD_FORMAT, "Kompilasi latihan melewati batas waktu.", "Coba lagi.") from error
        if done.returncode != 0:
            raise ReaError(E_BAD_FORMAT, f"Kompilasi latihan gagal: {done.stderr.strip()[:300]}", "Periksa kompilator (apt install gcc).")
        created += ["prog", "prog.c"]
    if item.files is not None:
        answers.GENERATORS[item.files](folder)
        created += sorted(f for f in os.listdir(folder) if f not in created)
    return {"n": n, "title": item.title, "dir": folder, "files": created, "program": os.path.join(folder, "prog") if item.source else None, "task": item.task}


def normalize(text: str) -> str:
    return " ".join(text.strip().lower().split())


def _same(given: str, expected: str) -> bool:
    a, b = normalize(given), normalize(expected)
    if a == b:
        return True
    try:
        return int(a, 0) == int(b, 0)
    except ValueError:
        return False


def _expected(item: Lesson) -> list[str]:
    expected = list(item.accepted)
    if item.dynamic:
        folder = exercise_dir(item.n)
        if not os.path.isdir(folder):
            raise ReaError(E_USAGE, "Latihan ini belum disiapkan.", f"Jalankan dulu: rea learn start {item.n}")
        expected += answers.DYNAMIC[item.dynamic](folder)
    return [e for e in expected if e]


def check(n: int, given: str) -> dict[str, Any]:
    item = lesson(n)
    if not given.strip() or len(given) > MAX_ANSWER:
        raise ReaError(E_USAGE, f"Jawaban harus 1 sampai {MAX_ANSWER} karakter.", "Contoh: rea learn check 4 nama_fungsi")
    correct = any(_same(given, e) for e in _expected(item))
    data = progress.load()
    record = progress.entry(data, n)
    record["attempts"] = int(record["attempts"]) + 1
    if correct:
        record["exercise_done"] = True
        record["solved_at"] = progress.now()
    progress.save(data)
    return {
        "n": n,
        "correct": correct,
        "attempts": record["attempts"],
        "message": "Benar. " + item.explanation if correct else "Belum tepat. Baca petunjuk: rea learn show " + str(n),
        "hint": None if correct else item.hint,
    }


def reveal_answer(n: int) -> dict[str, Any]:
    item = lesson(n)
    expected = _expected(item)
    data = progress.load()
    progress.entry(data, n)["answer_viewed"] = True
    progress.save(data)
    return {"n": n, "answers": expected[:3], "explanation": item.explanation}


def quiz(n: int, chosen: list[int] | None = None) -> dict[str, Any]:
    """Tanpa ``chosen``: pertanyaan (tanpa kunci). Dengan ``chosen``: penilaian, dan skor terbaik disimpan."""
    item = lesson(n)
    if chosen is None:
        return {"n": n, "title": item.title, "questions": [{"text": q.text, "options": list(q.options)} for q in item.quiz]}
    if len(chosen) != len(item.quiz):
        raise ReaError(E_USAGE, f"Jawab semua {len(item.quiz)} pertanyaan.", f"Contoh: rea learn quiz {n} --answers {','.join('1' for _ in item.quiz)}")
    for number, question in zip(chosen, item.quiz):
        if not 1 <= number <= len(question.options):
            raise ReaError(E_USAGE, f"Pilihan {number} di luar 1..{len(question.options)}.", "")
    correct = [number == q.answer for number, q in zip(chosen, item.quiz)]
    score = sum(correct)
    data = progress.load()
    record = progress.entry(data, n)
    previous = record.get("quiz") or {}
    record["quiz"] = {"score": score, "total": len(correct), "best": max(score, int(previous.get("best", 0)))}
    progress.save(data)
    return {"n": n, "score": score, "total": len(correct), "correct": correct, "passed": score / len(correct) >= PASS_RATIO}


def reset(n: int | None = None) -> dict[str, Any]:
    data = progress.load()
    if n is None:
        data["lessons"] = {}
    else:
        lesson(n)
        data["lessons"].pop(str(n), None)
    progress.save(data)
    return {"reset": "all" if n is None else n}
