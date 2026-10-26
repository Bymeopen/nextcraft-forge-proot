#!/usr/bin/env bash
set -euo pipefail

# Generate update-manifest.json for the in-app updater (AppUpdater.kt) from a signed release APK.
#
#   scripts/make-update-manifest.sh <apk> <versionCode> <versionName> <release-tag> [variant] [notes]
#
# Upload BOTH the APK and update-manifest.json as assets of the GitHub release <release-tag>, and mark that release
# "latest": the app reads releases/latest/download/update-manifest.json. versionCode must match the APK's
# android:versionCode and be higher than the installed build, and the APK must be signed with the same key.
APK="${1:?apk path}"
VERSION_CODE="${2:?versionCode}"
VERSION_NAME="${3:?versionName}"
TAG="${4:?release tag}"
VARIANT="${5:-release}"
NOTES="${6:-}"
REPO="${NEXTCRAFT_REPO:-bymeopen/nextcraft-forge}"
OUT="${NEXTCRAFT_OUT_DIR:-$(dirname "$APK")}/update-manifest.json"
# Optional: the applicationId the APK installs as. The app ignores a manifest meant for another package (for example
# an older side-by-side build) instead of offering an update it would have to reject.
PACKAGE="${NEXTCRAFT_PACKAGE:-}"

[[ -f "$APK" ]] || { echo "APK not found: $APK" >&2; exit 1; }
[[ "$VERSION_CODE" =~ ^[0-9]+$ ]] || { echo "versionCode must be an integer" >&2; exit 1; }
[[ "$VARIANT" == "release" || "$VARIANT" == "debug" ]] || { echo "variant must be release or debug" >&2; exit 1; }

sha256="$(sha256sum "$APK" | cut -d' ' -f1)"
size="$(wc -c < "$APK" | tr -d ' ')"
url="https://github.com/$REPO/releases/download/$TAG/$(basename "$APK")"

python3 - "$OUT" "$VERSION_CODE" "$VERSION_NAME" "$NOTES" "$VARIANT" "$url" "$sha256" "$size" "$PACKAGE" <<'PY'
import json, sys
out, code, name, notes, variant, url, sha, size, package = sys.argv[1:]
manifest = {
    "versionCode": int(code),
    "versionName": name,
    "notes": notes,
    "artifacts": {variant: {"url": url, "sha256": sha, "sizeBytes": int(size)}},
}
if package:
    manifest["packageName"] = package
with open(out, "w") as f:
    json.dump(manifest, f, indent=2)
    f.write("\n")
PY
echo "Wrote $OUT"
cat "$OUT"
