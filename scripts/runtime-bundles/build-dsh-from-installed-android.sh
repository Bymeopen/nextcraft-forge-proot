#!/usr/bin/env bash
set -euo pipefail

# Package the pinned DeepSeek Harness installation from NextCraft Forge's
# Ubuntu 24.04 Root Chroot runtime. Only the npm payload, launcher symlink, and
# version marker are exported; provider settings, API keys, sessions, chats, and
# workspaces are never included.
ADB_SERIAL="${ADB_SERIAL:-}"
PACKAGE="${NEXTCRAFT_PACKAGE:-com.nextcraft.forge}"
VERSION="${NEXTCRAFT_DSH_BUNDLE_VERSION:-2026.09.1}"
DSH_VERSION="${NEXTCRAFT_DSH_VERSION:-0.2.1-rc.2}"
ARCHIVE="nextcraft-dsh-arm64-${VERSION}.tar.zst"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
APP_DATA="/data/data/${PACKAGE}"
APP_ROOTFS="${APP_DATA}/files/runtime/ubuntu"

adb_cmd() {
  if [[ -n "$ADB_SERIAL" ]]; then adb -s "$ADB_SERIAL" "$@"; else adb "$@"; fi
}

command -v zstd >/dev/null || { echo "zstd is required" >&2; exit 1; }
adb_cmd wait-for-device
adb_cmd shell "su -c 'test -f ${APP_ROOTFS}/usr/local/lib/dsh/node_modules/.bin/dsh'"
actual_version="$(adb_cmd exec-out "su -c 'cat ${APP_ROOTFS}/.nextcraft-dsh-version 2>/dev/null || cat ${APP_ROOTFS}/.pocket-dsh-version'" | tr -d '\r\n')"
[[ "$actual_version" == "$DSH_VERSION" ]] || {
  echo "Expected DeepSeek Harness $DSH_VERSION, found $actual_version" >&2
  exit 1
}

temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"; adb_cmd shell "su -c \"rm -f /data/local/tmp/nextcraft-dsh-export.tar\"" >/dev/null 2>&1 || true' EXIT
mkdir -p "$temp_dir/payload" "$ROOT_DIR/dist/runtime-bundles"

adb_cmd shell "su -c 'tar -cf /data/local/tmp/nextcraft-dsh-export.tar -C ${APP_ROOTFS} usr/local/lib/dsh usr/local/bin/dsh'"
adb_cmd exec-out "su -c 'cat /data/local/tmp/nextcraft-dsh-export.tar'" > "$temp_dir/source.tar"
tar -xf "$temp_dir/source.tar" -C "$temp_dir/payload"
printf '%s\n' "$DSH_VERSION" > "$temp_dir/payload/.nextcraft-dsh-version"
printf '%s\n' "$DSH_VERSION" > "$temp_dir/payload/.nextcraft-dsh-version"

[[ -L "$temp_dir/payload/usr/local/bin/dsh" ]]
[[ -f "$temp_dir/payload/usr/local/lib/dsh/node_modules/.bin/dsh" ]]
python3 "$SCRIPT_DIR/create_deterministic_tar.py" "$temp_dir/payload" "$temp_dir/payload.tar"
zstd -19 -T0 -f "$temp_dir/payload.tar" -o "$ROOT_DIR/dist/runtime-bundles/$ARCHIVE"

shasum -a 256 "$ROOT_DIR/dist/runtime-bundles/$ARCHIVE"
wc -c "$ROOT_DIR/dist/runtime-bundles/$ARCHIVE" "$temp_dir/payload.tar"
echo "DeepSeek Harness bundle created: dist/runtime-bundles/$ARCHIVE"
