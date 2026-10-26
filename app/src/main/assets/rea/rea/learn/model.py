"""Model data pelajaran. Isi pelajaran ada di ``content.py``; jawaban yang bergantung pada biner ada di ``answers.py``."""

from __future__ import annotations

from dataclasses import dataclass

STAGES = (
    "Fondasi",
    "Format biner",
    "Analisis statis",
    "Analisis dinamis",
    "Proteksi",
    "Spesialisasi",
    "Otomasi",
)


@dataclass(frozen=True)
class Question:
    """Pertanyaan pilihan ganda; ``answer`` adalah nomor pilihan (mulai 1)."""

    text: str
    options: tuple[str, ...]
    answer: int


@dataclass(frozen=True)
class Lesson:
    n: int
    stage: int  # indeks 1..7 ke STAGES
    title: str
    theory: str
    diagram: str
    task: str
    hint: str
    explanation: str
    quiz: tuple[Question, ...]
    source: str | None = None  # program C latihan; ``{BLOB}`` diisi oleh generator
    files: str | None = None  # nama generator berkas latihan (non-C)
    requires: tuple[str, ...] = ()  # alat opsional yang membantu; latihan tetap bisa dikerjakan tanpanya
    accepted: tuple[str, ...] = ()  # jawaban tetap
    dynamic: str | None = None  # nama fungsi di answers.py yang menghitung jawaban dari berkas latihan

    @property
    def stage_name(self) -> str:
        return STAGES[self.stage - 1]
