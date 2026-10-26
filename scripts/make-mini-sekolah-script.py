#!/usr/bin/env python3
"""Regenerates samples/mini-sekolah/make-project.sh from the project files next to it.

The generated shell script recreates the whole sample project (all files inlined as quoted heredocs), so it can be
pasted into a prompt for the in-app agent. CI rebuilds the project from the repo files and diffs it against what the
script produces, so the two cannot drift. Run from the repository root: python3 scripts/make-mini-sekolah-script.py
"""
import os

ROOT = "samples/mini-sekolah"
SKIP_DIRS = {"build", ".gradle"}
SKIP_FILES = {"make-project.sh"}


def project_files():
    found = []
    for base, dirs, files in os.walk(ROOT):
        dirs[:] = sorted(d for d in dirs if d not in SKIP_DIRS)
        for name in sorted(files):
            if name in SKIP_FILES:
                continue
            found.append(os.path.relpath(os.path.join(base, name), ROOT))
    return found


def main():
    out = [
        "#!/usr/bin/env bash",
        "# Creates the Mini Sekolah sample (students + grades in SQLite, plain Android framework, Java).",
        "# Usage: bash make-project.sh [target-dir]   (default /workspace/mini-sekolah)",
        "set -eu",
        'DEST="${1:-/workspace/mini-sekolah}"',
        'mkdir -p "$DEST"',
        'cd "$DEST"',
    ]
    for index, rel in enumerate(project_files()):
        text = open(os.path.join(ROOT, rel), encoding="utf-8").read()
        marker = "FORGE_EOF_%d" % index
        assert marker not in text, rel
        assert text.endswith("\n"), rel + " must end with a newline"
        out.append("mkdir -p \"$(dirname '%s')\"" % rel)
        out.append("cat > '%s' <<'%s'" % (rel, marker))
        out.append(text[:-1])
        out.append(marker)
    out.append('echo "Proyek siap di $DEST"')
    with open(os.path.join(ROOT, "make-project.sh"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(out) + "\n")


if __name__ == "__main__":
    main()
