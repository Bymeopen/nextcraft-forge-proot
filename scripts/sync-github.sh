#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

COMMIT_MSG="${1:-Sync project changes to GitHub}"

# 1. Keep BLOK_1_SAMPAI_7_COMPLETE.md synchronized with source files
python3 - "$ROOT_DIR" <<'PY'
import pathlib, re, sys

root = pathlib.Path(sys.argv[1])
blok_files_all = ["BLOK_1_SAMPAI_7_COMPLETE.md"]

section_re = re.compile(
    r"(## \d+\. `([^`]+)` \()\d+( baris\)\n\n```([a-zA-Z0-9_-]*)\n)(.*?)(\n```)",
    re.DOTALL
)
bullet_re = re.compile(r"(- `([^`]+)` \()\d+( baris\))")

for bf in blok_files_all:
    bp = root / bf
    if not bp.is_file():
        continue
    text = bp.read_text(encoding="utf-8")

    def repl_bullet(m):
        prefix, rel_path, suffix = m.groups()
        src = root / rel_path
        if src.is_file():
            new_code = src.read_text(encoding="utf-8").rstrip("\n")
            line_count = len(new_code.splitlines())
            return f"{prefix}{line_count}{suffix}"
        return m.group(0)

    def repl_section(m):
        prefix, rel_path, mid, _lang, _old_code, suffix = m.groups()
        src = root / rel_path
        if src.is_file():
            new_code = src.read_text(encoding="utf-8").rstrip("\n")
            line_count = len(new_code.splitlines())
            return f"{prefix}{line_count}{mid}{new_code}{suffix}"
        return m.group(0)

    new_text = bullet_re.sub(repl_bullet, text)
    new_text = section_re.sub(repl_section, new_text)
    if new_text != text:
        bp.write_text(new_text, encoding="utf-8")
PY

# 2. Stage and commit any local modifications
git add -A
if ! git diff --cached --quiet; then
    git commit -m "$COMMIT_MSG"
fi

# 3. Sync with GitHub remote origin/main
if git remote get-url origin >/dev/null 2>&1; then
    git pull --rebase=merges --autostash origin main
    git push -u origin main
fi
