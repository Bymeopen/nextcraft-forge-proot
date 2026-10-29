#!/usr/bin/env bash
set -euo pipefail

# Build the NextCraft Core bundle for the PRoot engine: Alpine Linux (musl) ARM64 instead of Ubuntu (glibc).
#
# Why: Android's per-app seccomp filter raises SIGSYS for syscalls outside bionic's list. glibc 2.39 issues
# set_robust_list/rseq/faccessat2/fchmodat2 in every process, so an Ubuntu guest cannot start under proot on Android 16
# (measured on-device, docs/RUNTIME_KNOWN_ISSUES.md). musl issues none of them (audit-alpine-rootfs.sh checks this).
#
# Needs an ARM64 host with docker. The rootfs is assembled with `apk --root` inside the official alpine image, so there
# is no chroot or mount on the host. Package versions come from the Alpine repository of the pinned release branch; the
# resolved list is written to /.nextcraft-packages inside the bundle.
#
#   sudo NEXTCRAFT_CORE_ALPINE_VERSION=2026.10.3 ./build-core-alpine.sh      # root only so tar keeps ownership simple
#
# Environment: NEXTCRAFT_CORE_ALPINE_VERSION (default 2026.10.3), NEXTCRAFT_OUT_DIR (default dist/runtime-bundles),
# NEXTCRAFT_ALPINE_BRANCH (default v3.24).
VERSION="${NEXTCRAFT_CORE_ALPINE_VERSION:-2026.10.3}"
BRANCH="${NEXTCRAFT_ALPINE_BRANCH:-v3.24}"
IMAGE="alpine:${BRANCH#v}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="${NEXTCRAFT_OUT_DIR:-$ROOT_DIR/dist/runtime-bundles}"
ARCHIVE="nextcraft-core-alpine-arm64-$VERSION.tar.zst"

# Tools every guest shell, agent and project template relies on. Node comes from Alpine (v24 on 3.24), npm separately.
PACKAGES="alpine-baselayout alpine-release alpine-keys apk-tools busybox busybox-binsh musl-utils bash coreutils findutils grep sed gawk \
  git curl ca-certificates jq ripgrep zstd unzip zip tar nodejs npm"
# xz is left out on purpose: its CLI calls landlock_create_ruleset, which Android refuses (busybox still unpacks .xz).

[[ "$(uname -m)" == "aarch64" ]] || { echo "Run this on an ARM64 Linux host." >&2; exit 1; }
command -v docker >/dev/null || { echo "docker is required" >&2; exit 1; }
command -v zstd >/dev/null || { echo "zstd is required on the host" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf --one-file-system "$work" || true' EXIT
rootfs="$work/rootfs"
mkdir -p "$rootfs"

echo "Assembling Alpine ${BRANCH} rootfs with apk --root in $IMAGE"
docker run --rm -v "$rootfs:/target" -v "$SCRIPT_DIR:/src:ro" -e BRANCH="$BRANCH" -e PACKAGES="$PACKAGES" "$IMAGE" sh -euc '
  mkdir -p /target/etc/apk
  printf "https://dl-cdn.alpinelinux.org/alpine/%s/main\nhttps://dl-cdn.alpinelinux.org/alpine/%s/community\n" "$BRANCH" "$BRANCH" > /target/etc/apk/repositories
  apk add --root /target --initdb --arch aarch64 --keys-dir /etc/apk/keys --no-cache $PACKAGES
  rm -rf /target/var/cache/apk/*
  # The faccessat shim (seccomp-compat.c), compiled against the same musl as the guest.
  apk add --no-cache build-base >/dev/null
  mkdir -p /target/opt/nextcraft/lib
  gcc -O2 -shared -fPIC -o /target/opt/nextcraft/lib/libnextcraft-compat.so /src/seccomp-compat.c
'

echo "Configuring the guest"
docker run --rm -v "$rootfs:/target" -e VERSION="$VERSION" "$IMAGE" chroot /target /bin/sh -euc '
  # Root logs in with bash; the Android networking groups mirror the Ubuntu Core bundle (AID_INET 3003, AID_NET_RAW 3004).
  sed -i "s#^root:.*#root:x:0:0:root:/root:/bin/bash#" /etc/passwd
  # Alpine is not usr-merged; the installer and Ubuntu-era scripts look for /usr/bin/bash.
  ln -sfn ../../bin/bash /usr/bin/bash
  grep -q "^aid_inet:" /etc/group || printf "aid_inet:x:3003:root\naid_net_raw:x:3004:root\n" >> /etc/group
  mkdir -p /workspace/nextcraft /nextcraft-bridge /nextcraft-bridge-responses /opt/nextcraft/bin /root/.gradle/init.d \
    /system /apex /vendor /product /tmp /dev /proc /sys /root
  chmod 1777 /tmp
  printf "export LANG=C.UTF-8\n" > /etc/profile.d/00-nextcraft-lang.sh
  apk info -v 2>/dev/null | sort > /.nextcraft-packages
  printf "alpine-%s-arm64\n" "$(cat /etc/alpine-release)" > /.nextcraft-rootfs-version
  printf "nextcraft-core-alpine-%s\n" "$VERSION" > /.nextcraft-core-tools-version
  printf "core-bundle-alpine-%s\n" "$VERSION" > /.nextcraft-runtime-ready
  printf "{\"WEB\":true,\"PYTHON\":false,\"CPP\":false,\"PHP\":false,\"ANDROID\":false}\n" > /.nextcraft-dev-stacks.json
  rm -rf /root/.cache /root/.npm /var/log/* /etc/resolv.conf /tmp/* /var/tmp/*
'

mkdir -p "$OUT_DIR"
ZSTD_CLEVEL=19 ZSTD_NBTHREADS=0 tar --exclude='._*' --exclude='.DS_Store' --sort=name --mtime=2026-09-05T00:00:00Z \
  --owner=0 --group=0 --numeric-owner --use-compress-program=zstd -cf "$OUT_DIR/$ARCHIVE" -C "$rootfs" .
sha256sum "$OUT_DIR/$ARCHIVE"
wc -c "$OUT_DIR/$ARCHIVE"
cat "$rootfs/.nextcraft-rootfs-version"
echo "Core (Alpine) bundle created: $OUT_DIR/$ARCHIVE"
