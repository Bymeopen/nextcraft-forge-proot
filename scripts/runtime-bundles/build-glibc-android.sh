#!/usr/bin/env bash
set -euo pipefail

# Rebuild Ubuntu 24.04's glibc (libc6 + libc-bin) so that its own syscalls skip what Android's per-app seccomp filter
# refuses (set_robust_list, rseq, faccessat2, fchmodat2, openat2; see glibc-android-syscalls.py). Everything else in the
# Ubuntu userland stays stock; only libc6 and libc-bin are replaced, with the same ABI.
#
# Needs an ARM64 host with docker (GitHub's ubuntu-24.04-arm runner). Takes about half an hour.
#
#   NEXTCRAFT_OUT_DIR=out scripts/runtime-bundles/build-glibc-android.sh
#
# Output: libc6_*.deb, libc-bin_*.deb, libc6-dev_*.deb, libc-dev-bin_*.deb in $NEXTCRAFT_OUT_DIR/glibc-debs.
# The version carries a "+nextcraft" suffix so it sorts above the stock package; the core bundle holds it with apt-mark.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="${NEXTCRAFT_OUT_DIR:-$ROOT_DIR/dist/runtime-bundles}/glibc-debs"

[[ "$(uname -m)" == "aarch64" ]] || { echo "Run on an ARM64 host (got $(uname -m))" >&2; exit 1; }
command -v docker >/dev/null || { echo "docker is required" >&2; exit 1; }
mkdir -p "$OUT_DIR"

docker run --rm -v "$OUT_DIR:/out" -v "$SCRIPT_DIR:/src:ro" ubuntu:24.04 bash -euxc '
  export DEBIAN_FRONTEND=noninteractive
  # The official image lists binary sources only; the build needs the source package and its build dependencies.
  sed -i "s/^Types: deb\$/Types: deb deb-src/" /etc/apt/sources.list.d/ubuntu.sources
  apt-get update
  apt-get install -y --no-install-recommends build-essential devscripts fakeroot dpkg-dev python3 ca-certificates
  mkdir /build && cd /build
  apt-get source glibc
  cd glibc-*/
  apt-get build-dep -y glibc
  python3 /src/glibc-android-syscalls.py .
  dch --local +nextcraft -D noble --force-distribution "Skip syscalls Android seccomp refuses (set_robust_list, rseq, faccessat2, fchmodat2, openat2)"
  # -B: architecture-dependent packages only; no tests (they are slow and run against the host kernel).
  DEB_BUILD_OPTIONS="nocheck nodoc noudeb parallel=$(nproc)" dpkg-buildpackage -B -uc -us
  cd /build
  cp libc6_*.deb libc-bin_*.deb libc6-dev_*.deb libc-dev-bin_*.deb /out/
  ls -l /out
'
