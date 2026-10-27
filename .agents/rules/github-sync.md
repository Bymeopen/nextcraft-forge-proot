---
trigger: always_on
description: Keep the local project in /workspace/nextcraft-forge verified and synchronized with GitHub (origin/main) after every project change.
---

# Mandatory Local & GitHub Synchronization Rule

Whenever any source file, script, configuration, or documentation in `/workspace/nextcraft-forge` is created, modified, or deleted:

1. Run `bash scripts/verify-core-fixes.sh` to verify that the core architectural, security, and Kotlin/Compose compilation checks pass (exit code 0).
2. Run `bash scripts/sync-github.sh "<concise commit message>"` so that:
   - All changes are staged (except `*.env`, `*.pem`, `*.jks`, `*.p12`, `*.keystore`, `*.key`) and scanned for leaked tokens (`ghp_`, `sk-`, `AKIA`); the sync aborts if any are found.
   - All changes are committed locally on branch `main`.
   - All commits are immediately synchronized (`git pull --rebase=merges --autostash origin main && git push -u origin main`) with the `origin` remote.
3. Never store API keys, tokens, passwords, or `.env` contents in any `.md` file (enforced by `.agents/scripts/security_gate.py`).
