#!/usr/bin/env bash
set -euo pipefail

# NextCraft Forge — Export pinned DeepSeek Harness (dsh) runtime from device chroot.
TARGET_SERIAL="${ADB_SERIAL:-}"
APP_ID="${NEXTCRAFT_PACKAGE:-com.nextcraft.forge}"
RELEASE_TAG="${NEXTCRAFT_DSH_BUNDLE_VERSION:-2026.09.1}"
EXPECTED_DSH_VER="${NEXTCRAFT_DSH_VERSION:-0.2.1-rc.2}"
OUT_FILENAME="nextcraft-dsh-arm64-${RELEASE_TAG}.tar.zst"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${HERE}/../.." && pwd)"
GUEST_ROOT="/data/data/${APP_ID}/files/runtime/ubuntu"
DIST_FOLDER="${REPO_ROOT}/dist/runtime-bundles"
DEVICE_TMP_TAR="/data/local/tmp/nextcraft-dsh-stage.tar"

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
run_adb shell "su -c 'test -f ${GUEST_ROOT}/usr/local/lib/dsh/node_modules/.bin/dsh'"

detected_ver="$(run_adb exec-out "su -c 'cat ${GUEST_ROOT}/.nextcraft-dsh-version 2>/dev/null'" | tr -d '\r\n')"
if [[ "${detected_ver}" != "${EXPECTED_DSH_VER}" ]]; then
  echo "Fatal: DeepSeek Harness version mismatch (want ${EXPECTED_DSH_VER}, got ${detected_ver})" >&2
  exit 1
fi

WORK_STAGE="$(mktemp -d)"
cleanup_stage() {
  rm -rf "${WORK_STAGE}"
  run_adb shell "su -c 'rm -f ${DEVICE_TMP_TAR}'" >/dev/null 2>&1 || true
}
trap cleanup_stage EXIT

mkdir -p "${WORK_STAGE}/rootfs" "${DIST_FOLDER}"

run_adb shell "su -c 'tar -cf ${DEVICE_TMP_TAR} -C ${GUEST_ROOT} usr/local/lib/dsh usr/local/bin/dsh'"
run_adb exec-out "su -c 'cat ${DEVICE_TMP_TAR}'" > "${WORK_STAGE}/pulled.tar"
tar -xf "${WORK_STAGE}/pulled.tar" -C "${WORK_STAGE}/rootfs"
printf '%s\n' "${EXPECTED_DSH_VER}" > "${WORK_STAGE}/rootfs/.nextcraft-dsh-version"

test -L "${WORK_STAGE}/rootfs/usr/local/bin/dsh"
test -f "${WORK_STAGE}/rootfs/usr/local/lib/dsh/node_modules/.bin/dsh"

python3 "${HERE}/create_deterministic_tar.py" "${WORK_STAGE}/rootfs" "${WORK_STAGE}/bundle.tar"
zstd -19 -T0 -f "${WORK_STAGE}/bundle.tar" -o "${DIST_FOLDER}/${OUT_FILENAME}"
shasum -a 256 "${DIST_FOLDER}/${OUT_FILENAME}"
wc -c "${DIST_FOLDER}/${OUT_FILENAME}" "${WORK_STAGE}/bundle.tar"
echo "Packaged NextCraft DeepSeek overlay -> dist/runtime-bundles/${OUT_FILENAME}"
