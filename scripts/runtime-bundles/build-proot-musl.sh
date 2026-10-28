#!/usr/bin/env bash
set -euo pipefail

# Build a static ARM64 `proot` against musl (Alpine) as app/src/main/jniLibs/arm64-v8a/libproot.so.
#
# Why musl: Android's per-app seccomp filter raises SIGSYS for every syscall outside bionic's list. glibc 2.39 issues
# set_robust_list (and rseq, faccessat2, fchmodat2) in every process, so the glibc proot built by build-proot-static.sh
# is killed before it prints anything on Android 16 (measured on-device with sigsys-probe.c, see
# docs/RUNTIME_KNOWN_ISSUES.md). musl issues none of them.
#
# Needs an ARM64 host with docker (GitHub's ubuntu-24.04-arm runner). Sources are pinned: the proot tag must resolve to
# PROOT_COMMIT, and talloc comes from the Alpine package (static archive).
#
#   NEXTCRAFT_OUT_DIR=dist/runtime-bundles scripts/runtime-bundles/build-proot-musl.sh
#
# Environment: NEXTCRAFT_OUT_DIR (default dist/runtime-bundles), NEXTCRAFT_ALPINE_IMAGE (default alpine:3.24).
PROOT_TAG="v5.5.0"
PROOT_COMMIT="${NEXTCRAFT_PROOT_COMMIT:-25dc6a3134891f98a79f57ce1c2c1b23ff15cad1}"   # commit behind the tag
ALPINE_IMAGE="${NEXTCRAFT_ALPINE_IMAGE:-alpine:3.24}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="${NEXTCRAFT_OUT_DIR:-$ROOT_DIR/dist/runtime-bundles}"

[[ "$(uname -m)" == "aarch64" ]] || { echo "Run on an ARM64 host (got $(uname -m))" >&2; exit 1; }
command -v docker >/dev/null || { echo "docker is required" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
mkdir -p "$OUT_DIR" "$work/out"

echo "Building proot $PROOT_TAG against musl in $ALPINE_IMAGE"
docker run --rm -e PROOT_TAG="$PROOT_TAG" -e PROOT_COMMIT="$PROOT_COMMIT" -v "$work/out:/out" "$ALPINE_IMAGE" sh -euc '
  apk add --no-cache build-base git linux-headers bsd-compat-headers pkgconf talloc-dev talloc-static >/dev/null
  git clone -q --depth 1 --branch "$PROOT_TAG" https://github.com/proot-me/proot /build/proot
  actual="$(git -C /build/proot rev-parse HEAD)"
  [ "$actual" = "$PROOT_COMMIT" ] || { echo "proot $PROOT_TAG resolved to $actual, expected $PROOT_COMMIT" >&2; exit 1; }
  echo "proot $PROOT_TAG = $actual"
  cd /build/proot/src
  # Environment, not make arguments: the GNUmakefile appends `pkg-config --libs talloc` with `+=` and a command-line
  # LDFLAGS would override it. --build-id=none and the prefix map keep the binary reproducible.
  CFLAGS="-O2 -D_GNU_SOURCE -ffile-prefix-map=/build=." LDFLAGS="-static -Wl,--build-id=none" \
    SOURCE_DATE_EPOCH=1757116800 make -j"$(nproc)" >/dev/null
  strip --strip-all proot
  cp proot /out/libproot.so
  cp loader/loader /out/libproot_loader.so
  chmod 755 /out/libproot.so /out/libproot_loader.so
'

for f in libproot.so libproot_loader.so; do
  cp "$work/out/$f" "$OUT_DIR/$f"
  desc="$(file -b "$OUT_DIR/$f")"
  echo "$f: $desc"
  [[ "$desc" == *"ARM aarch64"* && "$desc" == *"statically linked"* ]] || { echo "Unexpected binary $f" >&2; exit 1; }
done
"$OUT_DIR/libproot.so" --version | head -3
sha256sum "$OUT_DIR/libproot.so" "$OUT_DIR/libproot_loader.so"
wc -c "$OUT_DIR/libproot.so" "$OUT_DIR/libproot_loader.so"
