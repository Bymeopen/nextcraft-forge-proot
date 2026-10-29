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

# 2. Stage and commit any local modifications. Key material and env files are never staged, and a lightweight
#    secret scan over the staged diff aborts the sync before anything is committed.
git add -A -- . ':!*.env' ':!.env.*' ':!*.pem' ':!*.jks' ':!*.p12' ':!*.keystore' ':!*.key'
# No `grep -q` here: it exits at the first match, the upstream grep then dies of SIGPIPE and pipefail turns the
# pipeline into "failed", so a real secret in a large diff would pass the scan.
if git diff --cached -U0 | grep -E '^\+' | grep -Ev '^\+\+\+' | grep -E 'ghp_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9_-]{20,}|AKIA[0-9A-Z]{16}' >/dev/null; then
    echo "sync-github: a GitHub/OpenAI/AWS-style secret is present in the staged changes; aborting. Remove it and run again." >&2
    git reset -q
    exit 1
fi
if ! git diff --cached --quiet; then
    git commit -m "$COMMIT_MSG"
fi

# 3. Sync with GitHub remote origin/main
if git remote get-url origin >/dev/null 2>&1; then
    # `git push origin main` sends the local main branch; from another branch it would push nothing of what was just committed.
    if [[ "$(git symbolic-ref --short -q HEAD || true)" != "main" ]]; then
        echo "sync-github: committed locally, but not pushing: current branch is not main." >&2
        exit 0
    fi
    git pull --rebase=merges --autostash origin main
    git push -u origin main
fi
