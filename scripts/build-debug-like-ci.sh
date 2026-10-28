#!/usr/bin/env bash
# Local debug build with the same version and signing key as the GitHub "dev-latest" debug release, so the
# two APKs install over each other without uninstalling (Android compares signature and versionCode).
# Usage: scripts/build-debug-like-ci.sh [extra gradle args]
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

MANIFEST_URL="https://github.com/Bymeopen/nextcraft-forge/releases/download/dev-latest/update-manifest.json"

# The rolling release carries the versionCode/versionName CI used for its latest debug build.
manifest="$(curl -fsSL "$MANIFEST_URL")"
version_code="$(python3 -I -c 'import json,sys; print(json.load(sys.stdin)["versionCode"])' <<<"$manifest")"
version_name="$(python3 -I -c 'import json,sys; print(json.load(sys.stdin)["versionName"])' <<<"$manifest")"

echo "Building debug $version_name (versionCode $version_code), signed with app/debug.keystore"
gradle :app:assembleDebug -PversionCode="$version_code" -PversionName="$version_name" "$@"
