#!/usr/bin/env bash
set -euo pipefail

# Export the checksum-verified official Claude Code ARM64 binary from an
# existing NextCraft Forge installation on a rooted device.
# Only the executable and version marker are included; authentication, settings,
# conversations, projects, and device data are never copied.
ADB_SERIAL="${ADB_SERIAL:-}"
PACKAGE="${NEXTCRAFT_PACKAGE:-com.nextcraft.forge}"
VERSION="${NEXTCRAFT_CLAUDE_VERSION:-2.1.263}"
SOURCE_SHA256="${NEXTCRAFT_CLAUDE_SHA256:-7d25d7c8ae6c6e009cc7dae4e817f674179fd31fb7761bcd56fee4c2902b4c03}"
BUNDLE_VERSION="${NEXTCRAFT_CLAUDE_BUNDLE_VERSION:-2026.09.1}"
ARCHIVE="nextcraft-claude-arm64-${BUNDLE_VERSION}.tar.zst"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
APP_ROOTFS="/data/data/${PACKAGE}/files/runtime/ubuntu"

adb_cmd() {
  if [[ -n "$ADB_SERIAL" ]]; then adb -s "$ADB_SERIAL" "$@"; else adb "$@"; fi
}

command -v zstd >/dev/null || { echo "zstd is required" >&2; exit 1; }
adb_cmd wait-for-device
adb_cmd shell "su -c 'test -x ${APP_ROOTFS}/usr/local/bin/claude'"

temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT
mkdir -p "$temp_dir/payload/usr/local/bin" "$ROOT_DIR/dist/runtime-bundles"

adb_cmd exec-out "su -c 'cat ${APP_ROOTFS}/usr/local/bin/claude'" \
  > "$temp_dir/payload/usr/local/bin/claude"
actual_sha256="$(shasum -a 256 "$temp_dir/payload/usr/local/bin/claude" | cut -d' ' -f1)"
[[ "$actual_sha256" == "$SOURCE_SHA256" ]] || {
  echo "Claude Code source checksum mismatch" >&2
  exit 1
}
chmod 0755 "$temp_dir/payload/usr/local/bin/claude"
printf '%s\n' "$VERSION" > "$temp_dir/payload/.nextcraft-claude-version"
printf '%s\n' "$VERSION" > "$temp_dir/payload/.nextcraft-claude-version"

python3 "$SCRIPT_DIR/create_deterministic_tar.py" "$temp_dir/payload" "$temp_dir/payload.tar"
zstd -19 -T0 -f "$temp_dir/payload.tar" -o "$ROOT_DIR/dist/runtime-bundles/$ARCHIVE"
shasum -a 256 "$ROOT_DIR/dist/runtime-bundles/$ARCHIVE"
wc -c "$ROOT_DIR/dist/runtime-bundles/$ARCHIVE" "$temp_dir/payload.tar"
echo "Claude Code bundle created: dist/runtime-bundles/$ARCHIVE"
