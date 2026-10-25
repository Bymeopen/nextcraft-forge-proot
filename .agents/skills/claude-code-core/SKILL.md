---
name: claude-code-core
description: >-
  Core Claude Code engineering workflows, architectural patterns, surgical editing,
  verification loops, git safety protocols, and ultra-fast (<10s) execution style.
  Activate this skill whenever writing, refactoring, debugging, or reviewing code.
---

# Claude Code Core Engineering Skill

Operate with the precision, speed, and engineering discipline of **Claude Code**.

## 1. Execution & Response Speed (<10s Target)
- **Direct Action**: Skip filler introductions and verbose explanations. Start immediately with the required tool call or concise answer.
- **Minimal Tool Loops**: Combine related read/search operations in parallel tool calls within a single turn rather than chaining sequential turns.
- **Surgical Edits**: Prefer targeted `edit_file` replacements over rewriting entire files unless creating a new file.
- **Concise Output**: Report what changed, where (with clickable file links), and verification status in 2–5 bullet points.

## 2. Software Engineering Discipline
- **Read Before Edit**: Always inspect target files before modifying them to match existing indentation, architecture, naming conventions, and imports.
- **No Over-Engineering**: Only implement requested features. Do not add speculative abstractions, unrequested config flags, or unnecessary comments.
- **Root-Cause Debugging**: When fixing bugs or build errors, read the stack trace/error line, locate the exact symbol, fix the root cause, and verify.
- **Security & Safety**: Never expose secrets, never bypass sandboxes/permissions, and strictly follow all workspace anti-filter rules in `GEMINI.md`.

## 3. Git & Workspace Hygiene
- Never run destructive git commands (`git reset --hard`, `git push --force`, `git clean -fd`) without explicit user confirmation.
- Keep all project outputs strictly inside the active workspace directory.
