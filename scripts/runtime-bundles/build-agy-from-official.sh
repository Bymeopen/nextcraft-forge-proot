#!/usr/bin/env bash
set -euo pipefail

# NextCraft Forge — Assemble Antigravity CLI (agy) ARM64 runtime overlay.
AGY_VER="${NEXTCRAFT_AGY_VERSION:-1.4.5}"
RELEASE_TAG="${NEXTCRAFT_AGY_BUNDLE_VERSION:-2026.09.1}"
DOWNLOAD_URI="${NEXTCRAFT_AGY_URL:-https://storage.googleapis.com/antigravity-public/antigravity-cli/1.4.5-4952523841339392/linux-arm/cli_linux_arm64.tar.gz}"
EXPECTED_SHA512="${NEXTCRAFT_AGY_SHA512:-cf60004b48015a2737dcb56ce11227fef3a722f2e9329c13318e290951e350c395118bd8680c1042b2fdee9d2ead05fb84bb2596fa2d0012a44bc068655d34eb}"

if [[ "${#EXPECTED_SHA512}" -ne 128 ]]; then
  echo "Fatal: EXPECTED_SHA512 must be a 128-character hex digest." >&2
  exit 1
fi

OUT_FILENAME="nextcraft-agy-arm64-${RELEASE_TAG}.tar.zst"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${HERE}/../.." && pwd)"
TARGET_OUT_DIR="${NEXTCRAFT_OUT_DIR:-${REPO_ROOT}/dist/runtime-bundles}"

for tool in curl zstd python3; do
  if ! command -v "${tool}" >/dev/null 2>&1; then
    echo "Fatal: missing required host tool '${tool}'." >&2
    exit 1
  fi
done

WORK_STAGE="$(mktemp -d)"
trap 'rm -rf "${WORK_STAGE}"' EXIT

STAGE_ROOT="${WORK_STAGE}/rootfs"
AGY_CFG_ROOT="${STAGE_ROOT}/root/.gemini"
mkdir -p "${STAGE_ROOT}/root/.local/bin" "${AGY_CFG_ROOT}/config/skills" "${AGY_CFG_ROOT}/antigravity-cli/cache" "${TARGET_OUT_DIR}"

curl -fL --retry 3 -o "${WORK_STAGE}/upstream-agy.tar.gz" "${DOWNLOAD_URI}"
computed_sha512="$(shasum -a 512 "${WORK_STAGE}/upstream-agy.tar.gz" | awk '{print $1}')"
if [[ "${computed_sha512}" != "${EXPECTED_SHA512}" ]]; then
  echo "Fatal: SHA-512 mismatch on downloaded Antigravity archive." >&2
  exit 1
fi

tar -xzf "${WORK_STAGE}/upstream-agy.tar.gz" -C "${WORK_STAGE}" antigravity
install -m 0755 "${WORK_STAGE}/antigravity" "${STAGE_ROOT}/root/.local/bin/agy"
printf '%s\n' "${AGY_VER}" > "${STAGE_ROOT}/.nextcraft-agy-version"

cp "${REPO_ROOT}/GEMINI.md" "${AGY_CFG_ROOT}/config/GEMINI.md"
cp -r "${REPO_ROOT}/.agents/skills/"* "${AGY_CFG_ROOT}/config/skills/"

python3 - "${AGY_CFG_ROOT}/antigravity-cli" <<'PY'
import json
import sys
from pathlib import Path

cli_dir = Path(sys.argv[1])
(cli_dir / "settings.json").write_text(
    json.dumps(
        {
            "trustedWorkspaces": [
                "/workspace",
                "/workspace/hello",
                "/workspace/antigravity-auth",
                "/workspace/antigravity-models",
                "/root",
            ]
        },
        indent=2,
    )
    + "\n",
    encoding="utf-8",
)
(cli_dir / "cache" / "onboarding.json").write_text(
    json.dumps(
        {
            "consumerOnboardingComplete": True,
            "enterpriseOnboardingComplete": False,
            "onboardingComplete": True,
        },
        indent=2,
    )
    + "\n",
    encoding="utf-8",
)
steps = [
    "POST_ONBOARDING_STEP_TYPE_MANAGER_WELCOME",
    "POST_ONBOARDING_STEP_TYPE_USAGE_MODE",
    "POST_ONBOARDING_STEP_TYPE_AGENT_CONFIGURATION",
    "POST_ONBOARDING_STEP_TYPE_ADD_WORKSPACE",
]
body_lines = ["post_onboarding: {"] + [f"  completed_steps: {s}" for s in steps] + ["}"]
for mig_key in (3, 4, 5):
    body_lines.extend(["migrations: {", f"  key: {mig_key}", "  value: MIGRATION_STATUS_COMPLETED", "}"])
(cli_dir / "jetski_state.pbtxt").write_text("\n".join(body_lines) + "\n", encoding="utf-8")
PY

python3 "${HERE}/create_deterministic_tar.py" "${STAGE_ROOT}" "${WORK_STAGE}/bundle.tar"
zstd -19 -T0 -f "${WORK_STAGE}/bundle.tar" -o "${TARGET_OUT_DIR}/${OUT_FILENAME}"
sha256sum "${TARGET_OUT_DIR}/${OUT_FILENAME}"
wc -c "${TARGET_OUT_DIR}/${OUT_FILENAME}" "${WORK_STAGE}/bundle.tar"
echo "Packaged NextCraft Antigravity overlay -> ${TARGET_OUT_DIR}/${OUT_FILENAME}"
