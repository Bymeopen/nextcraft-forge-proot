#!/usr/bin/env bash
set -euo pipefail

# Build a static ARM64 `proot` for the NextCraft Forge non-root (PRoot) engine, as app/src/main/jniLibs/arm64-v8a/libproot.so.
#
# Android forbids executing files from the app data directory, so the binary ships inside the APK as a "native
# library"; the app runs it from its nativeLibraryDir. It is linked statically against glibc and talloc inside a
# chroot copy of the Core rootfs (Ubuntu 24.04 arm64), so it has no runtime dependencies on the device.
#
# Sources (verified against the pinned checksums):
#   proot  v5.5.0   https://github.com/proot-me/proot (git tag, commit pinned below)
#   talloc 2.4.3    https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz
#
# Needs root (chroot and mount). On an ARM64 host it runs natively; on x86_64 it needs qemu-user-static with an
# aarch64 binfmt handler registered with the `F` flag (see README.md).
#
#   sudo NEXTCRAFT_CORE_ROOTFS=/path/to/extracted/core-rootfs ./build-proot-static.sh
#
# Environment: NEXTCRAFT_OUT_DIR (default dist/runtime-bundles), NEXTCRAFT_WORK_DIR, NEXTCRAFT_KEEP_WORK,
# NEXTCRAFT_DNS, NEXTCRAFT_EXTRA_CA_FILE (TLS-intercepting proxy CA, used for git/curl inside the chroot only).
CORE_ROOTFS="${NEXTCRAFT_CORE_ROOTFS:?Set NEXTCRAFT_CORE_ROOTFS to an extracted Core bundle rootfs}"
PROOT_TAG="v5.5.0"
PROOT_COMMIT="${NEXTCRAFT_PROOT_COMMIT:-25dc6a3134891f98a79f57ce1c2c1b23ff15cad1}"   # commit behind the tag
TALLOC_VERSION="2.4.3"
TALLOC_URL="https://www.samba.org/ftp/talloc/talloc-${TALLOC_VERSION}.tar.gz"
TALLOC_SHA256="dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd"
DNS_SERVER="${NEXTCRAFT_DNS:-1.1.1.1}"
EXTRA_CA_FILE="${NEXTCRAFT_EXTRA_CA_FILE:-}"
WORK_PARENT="${NEXTCRAFT_WORK_DIR:-}"
KEEP_WORK="${NEXTCRAFT_KEEP_WORK:-0}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="${NEXTCRAFT_OUT_DIR:-$ROOT_DIR/dist/runtime-bundles}"

[[ "$(id -u)" == "0" ]] || { echo "Run as root (chroot and mount are required)" >&2; exit 1; }
for tool in chroot tar sha256sum file; do
  command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 1; }
done
grep -qx 'ubuntu-24.04.5-arm64' "$CORE_ROOTFS/.pocket-rootfs-version" 2>/dev/null || {
  echo "$CORE_ROOTFS is not an extracted Core 24.04.5 rootfs" >&2; exit 1; }
[[ -z "$EXTRA_CA_FILE" || -f "$EXTRA_CA_FILE" ]] || { echo "NEXTCRAFT_EXTRA_CA_FILE not found: $EXTRA_CA_FILE" >&2; exit 1; }

work="$(mktemp -d ${WORK_PARENT:+-p "$WORK_PARENT"})"
rootfs="$work/rootfs"
mounted=0
cleanup() {
  if [[ "$mounted" == "1" ]]; then
    umount -lf "$rootfs/dev" "$rootfs/proc" 2>/dev/null || true
  fi
  if [[ "$KEEP_WORK" != "1" && -n "$work" && "$work" != "/" ]] && ! grep -q " $work/" /proc/mounts; then
    rm -rf --one-file-system -- "$work"
  else
    echo "Work directory kept: $work" >&2
  fi
}
trap cleanup EXIT

echo "Copying Core rootfs"
cp -a "$CORE_ROOTFS" "$rootfs"
if [[ "$(uname -m)" != "aarch64" ]]; then
  [[ -e "$rootfs/usr/bin/qemu-aarch64-static" ]] || cp "$(command -v qemu-aarch64-static)" "$rootfs/usr/bin/qemu-aarch64-static"
fi
ca_env=""
if [[ -n "$EXTRA_CA_FILE" ]]; then
  cp "$EXTRA_CA_FILE" "$rootfs/extra-ca.pem"
  ca_env="GIT_SSL_CAINFO=/extra-ca.pem CURL_CA_BUNDLE=/extra-ca.pem"
fi
mount --bind /dev "$rootfs/dev"
mount -t proc proc "$rootfs/proc"
mounted=1

guest() {
  # shellcheck disable=SC2086
  chroot "$rootfs" /usr/bin/env -i HOME=/root USER=root LANG=C.UTF-8 DEBIAN_FRONTEND=noninteractive $ca_env \
    GLIBC_TUNABLES=glibc.pthread.rseq=0 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    /bin/bash -lc "$1"
}

guest "printf 'nameserver $DNS_SERVER\\n' > /etc/resolv.conf; apt-get update -qq && apt-get install -y -qq --no-install-recommends build-essential pkg-config python3 git ca-certificates curl"

echo "Fetching sources"
guest "mkdir -p /build && cd /build && curl -fsSL -o talloc.tar.gz '$TALLOC_URL' && echo '$TALLOC_SHA256  talloc.tar.gz' | sha256sum -c --status"
guest "cd /build && git clone -q --depth 1 --branch '$PROOT_TAG' https://github.com/proot-me/proot proot"
actual_commit="$(guest "git -C /build/proot rev-parse HEAD" | tail -1 | tr -d '\r')"
if [[ -n "$PROOT_COMMIT" && "$actual_commit" != "$PROOT_COMMIT" ]]; then
  echo "proot $PROOT_TAG resolved to $actual_commit, expected $PROOT_COMMIT" >&2; exit 1
fi
echo "proot $PROOT_TAG = $actual_commit"

echo "Building talloc (static)"
guest "cd /build && tar -xzf talloc.tar.gz && cd talloc-$TALLOC_VERSION && \
  ./configure --prefix=/build/talloc-prefix --disable-python --disable-rpath >/dev/null && \
  make -j\$(nproc) >/dev/null && make install >/dev/null && \
  ar rcs /build/talloc-prefix/lib/libtalloc.a bin/default/talloc.c.*.o"
# talloc's waf install only ships the shared library; the archive above is built from its single object file.

echo "Building proot (static)"
# LDFLAGS/CFLAGS go through the environment, not make arguments: the GNUmakefile appends `pkg-config --libs talloc`
# with `+=`, and a command-line LDFLAGS would override that and drop -ltalloc.
# --build-id=none and a fixed SOURCE_DATE_EPOCH keep the binary byte-identical across builds.
guest "cd /build/proot/src && \
  PKG_CONFIG_PATH=/build/talloc-prefix/lib/pkgconfig LDFLAGS='-static -L/build/talloc-prefix/lib -Wl,--build-id=none' \
  CFLAGS='-O2 -I/build/talloc-prefix/include -ffile-prefix-map=/build=.' SOURCE_DATE_EPOCH=1757116800 \
  make -j\$(nproc) >/dev/null && \
  strip --strip-all --remove-section=.comment --remove-section=.note proot && ./proot --version | head -1"

mkdir -p "$OUT_DIR"
cp "$rootfs/build/proot/src/proot" "$OUT_DIR/libproot.so"
# proot runs every guest execve through this small static ELF loader. By default it extracts the loader into
# PROOT_TMP_DIR at runtime, which Android refuses to exec from app data (SELinux, targetSdk >= 29); proot-exec.sh
# therefore points PROOT_LOADER at a copy shipped in the APK next to proot.
cp "$rootfs/build/proot/src/loader/loader" "$OUT_DIR/libproot_loader.so"
chmod 755 "$OUT_DIR/libproot.so" "$OUT_DIR/libproot_loader.so"
for f in libproot.so libproot_loader.so; do
  desc="$(file -b "$OUT_DIR/$f")"
  [[ "$desc" == *"ARM aarch64"* && "$desc" == *"statically linked"* ]] || { echo "Unexpected binary $f: $desc" >&2; exit 1; }
done
sha256sum "$OUT_DIR/libproot.so" "$OUT_DIR/libproot_loader.so"
wc -c "$OUT_DIR/libproot.so" "$OUT_DIR/libproot_loader.so"
echo "proot $PROOT_TAG ($actual_commit) built: $OUT_DIR/libproot.so + libproot_loader.so"
echo "Copy both to app/src/main/jniLibs/arm64-v8a/ and record the SHA-256 values in docs/RUNTIME_KNOWN_ISSUES.md"
