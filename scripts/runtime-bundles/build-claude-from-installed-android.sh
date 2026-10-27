#!/usr/bin/env bash
set -euo pipefail

# NextCraft Forge — Export verified ARM64 Claude Code binary from a rooted device.
TARGET_SERIAL="${ADB_SERIAL:-}"
APP_ID="${NEXTCRAFT_PACKAGE:-com.nextcraft.forge}"
CLAUDE_VER="${NEXTCRAFT_CLAUDE_VERSION:-2.1.263}"
EXPECTED_HASH="${NEXTCRAFT_CLAUDE_SHA256:-7d25d7c8ae6c6e009cc7dae4e817f674179fd31fb7761bcd56fee4c2902b4c03}"
RELEASE_TAG="${NEXTCRAFT_CLAUDE_BUNDLE_VERSION:-2026.09.1}"
OUT_FILENAME="nextcraft-claude-arm64-${RELEASE_TAG}.tar.zst"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${HERE}/../.." && pwd)"
GUEST_ROOT="/data/data/${APP_ID}/files/runtime/ubuntu"
DIST_FOLDER="${REPO_ROOT}/dist/runtime-bundles"

run_adb() {
  if [[ -n "${TARGET_SERIAL}" ]]; then
    adb -s "${TARGET_SERIAL}" "$@"
  else
    adb "$@"
  fi
}

if ! command -v zstd >/dev/null 2>&1; then
  echo "Error: zstd compression utility is missing." >&2
  exit 1
fi

run_adb wait-for-device
run_adb shell "su -c 'test -x ${GUEST_ROOT}/usr/local/bin/claude'"

WORK_STAGE="$(mktemp -d)"
cleanup_stage() { rm -rf "${WORK_STAGE}"; }
trap cleanup_stage EXIT

BIN_STAGE="${WORK_STAGE}/rootfs/usr/local/bin"
mkdir -p "${BIN_STAGE}" "${DIST_FOLDER}"

run_adb exec-out "su -c 'cat ${GUEST_ROOT}/usr/local/bin/claude'" > "${BIN_STAGE}/claude"
computed_hash="$(shasum -a 256 "${BIN_STAGE}/claude" | awk '{print $1}')"
if [[ "${computed_hash}" != "${EXPECTED_HASH}" ]]; then
  echo "Fatal: SHA-256 verification failed for exported claude binary (${computed_hash})" >&2
  exit 1
fi

chmod 0755 "${BIN_STAGE}/claude"
printf '%s\n' "${CLAUDE_VER}" > "${WORK_STAGE}/rootfs/.nextcraft-claude-version"

python3 "${HERE}/create_deterministic_tar.py" "${WORK_STAGE}/rootfs" "${WORK_STAGE}/bundle.tar"
zstd -19 -T0 -f "${WORK_STAGE}/bundle.tar" -o "${DIST_FOLDER}/${OUT_FILENAME}"
shasum -a 256 "${DIST_FOLDER}/${OUT_FILENAME}"
wc -c "${DIST_FOLDER}/${OUT_FILENAME}" "${WORK_STAGE}/bundle.tar"
echo "Packaged NextCraft Claude overlay -> dist/runtime-bundles/${OUT_FILENAME}"
