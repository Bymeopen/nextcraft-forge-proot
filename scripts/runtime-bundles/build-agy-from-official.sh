#!/usr/bin/env bash
set -euo pipefail

# Build a portable NextCraft Forge overlay from Google's checksum-pinned official
# Antigravity CLI release. The archive contains only the executable and version
# marker: no OAuth credentials, settings, conversations, projects, or device data.
VERSION="${NEXTCRAFT_AGY_VERSION:-1.4.5}"
BUNDLE_VERSION="${NEXTCRAFT_AGY_BUNDLE_VERSION:-2026.09.1}"
SOURCE_URL="${NEXTCRAFT_AGY_URL:-https://storage.googleapis.com/antigravity-public/antigravity-cli/1.4.5-4952523841339392/linux-arm/cli_linux_arm64.tar.gz}"
SOURCE_SHA512="${NEXTCRAFT_AGY_SHA512:-cf60004b48015a2737dcb56ce11227fef3a722f2e9329c13318e290951e350c395118bd8680c1042b2fdee9d2ead05fb84bb2596fa2d0012a44bc068655d34eb}"
if [ ${#SOURCE_SHA512} -ne 128 ]; then
  echo "ERROR: SOURCE_SHA512 must be exactly 128 hex characters" >&2
  exit 1
fi
ARCHIVE="nextcraft-agy-arm64-${BUNDLE_VERSION}.tar.zst"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v zstd >/dev/null || { echo "zstd is required" >&2; exit 1; }

temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT
OUT_DIR="${NEXTCRAFT_OUT_DIR:-$ROOT_DIR/dist/runtime-bundles}"
mkdir -p "$temp_dir/payload/root/.local/bin" "$OUT_DIR"

curl -fL --retry 3 -o "$temp_dir/agy.tar.gz" "$SOURCE_URL"
actual_sha512="$(shasum -a 512 "$temp_dir/agy.tar.gz" | cut -d' ' -f1)"
[[ "$actual_sha512" == "$SOURCE_SHA512" ]] || {
  echo "Antigravity source checksum mismatch" >&2
  exit 1
}

tar -xzf "$temp_dir/agy.tar.gz" -C "$temp_dir" antigravity
install -m 0755 "$temp_dir/antigravity" "$temp_dir/payload/root/.local/bin/agy"
printf '%s\n' "$VERSION" > "$temp_dir/payload/.nextcraft-agy-version"
mkdir -p "$temp_dir/payload/root/.gemini/config/skills" "$temp_dir/payload/root/.gemini/antigravity-cli/cache"
cp "$ROOT_DIR/GEMINI.md" "$temp_dir/payload/root/.gemini/config/GEMINI.md"
cp -r "$ROOT_DIR/.agents/skills/"* "$temp_dir/payload/root/.gemini/config/skills/"
cat <<'EOF' > "$temp_dir/payload/root/.gemini/antigravity-cli/settings.json"
{
  "trustedWorkspaces": [
    "/workspace",
    "/workspace/hello",
    "/workspace/antigravity-auth",
    "/workspace/antigravity-models",
    "/root"
  ]
}
EOF
cat <<'EOF' > "$temp_dir/payload/root/.gemini/antigravity-cli/cache/onboarding.json"
{
  "consumerOnboardingComplete": true,
  "enterpriseOnboardingComplete": false,
  "onboardingComplete": true
}
EOF
cat <<'EOF' > "$temp_dir/payload/root/.gemini/antigravity-cli/jetski_state.pbtxt"
post_onboarding: {
  completed_steps: POST_ONBOARDING_STEP_TYPE_MANAGER_WELCOME
  completed_steps: POST_ONBOARDING_STEP_TYPE_USAGE_MODE
  completed_steps: POST_ONBOARDING_STEP_TYPE_AGENT_CONFIGURATION
  completed_steps: POST_ONBOARDING_STEP_TYPE_ADD_WORKSPACE
}
migrations: {
  key: 3
  value: MIGRATION_STATUS_COMPLETED
}
migrations: {
  key: 4
  value: MIGRATION_STATUS_COMPLETED
}
migrations: {
  key: 5
  value: MIGRATION_STATUS_COMPLETED
}
EOF

python3 "$SCRIPT_DIR/create_deterministic_tar.py" "$temp_dir/payload" "$temp_dir/payload.tar"
zstd -19 -T0 -f "$temp_dir/payload.tar" -o "$OUT_DIR/$ARCHIVE"
sha256sum "$OUT_DIR/$ARCHIVE"
wc -c "$OUT_DIR/$ARCHIVE" "$temp_dir/payload.tar"
echo "Antigravity bundle created: $OUT_DIR/$ARCHIVE"
