#!/usr/bin/env bash
set -euo pipefail

# Build the NextCraft Forge Python bundle (Ubuntu 24.04 noble, Python 3.12, ARM64) as an overlay of the Core bundle.
#
# The overlay holds only the files that `apt-get install python3 python3-pip python3-venv` adds on top of a Core
# rootfs (no dpkg metadata, same as the previous Python 3.8 bundle). It runs in a chroot copy of the Core rootfs, so
# it needs root. On an ARM64 host it runs natively; on x86_64 it needs qemu-user-static with an aarch64 binfmt
# handler registered with the `F` (fix-binary) flag.
#
#   sudo NEXTCRAFT_CORE_ROOTFS=/path/to/extracted/core-rootfs ./build-python-bundle.sh
#
# NEXTCRAFT_CORE_ROOTFS must be an extracted nextcraft-core-arm64-*.tar.zst. It is only read; a copy is used.
CORE_ROOTFS="${NEXTCRAFT_CORE_ROOTFS:?Set NEXTCRAFT_CORE_ROOTFS to an extracted Core bundle rootfs}"
BUNDLE_VERSION="${NEXTCRAFT_PYTHON_BUNDLE_VERSION:-2026.10.1}"
DNS_SERVER="${NEXTCRAFT_DNS:-1.1.1.1}"
WORK_PARENT="${NEXTCRAFT_WORK_DIR:-}"
KEEP_WORK="${NEXTCRAFT_KEEP_WORK:-0}"
ARCHIVE="nextcraft-python-arm64-${BUNDLE_VERSION}.tar.zst"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="${NEXTCRAFT_OUT_DIR:-$ROOT_DIR/dist/runtime-bundles}"

[[ "$(id -u)" == "0" ]] || { echo "Run as root (chroot and mount are required)" >&2; exit 1; }
for tool in zstd python3 chroot tar find; do
  command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 1; }
done
[[ -x "$CORE_ROOTFS/usr/bin/apt-get" && ( -f "$CORE_ROOTFS/.nextcraft-rootfs-version" || -f "$CORE_ROOTFS/.pocket-rootfs-version" ) ]] || {
  echo "$CORE_ROOTFS does not look like an extracted Core bundle" >&2
  exit 1
}
core_marker="$CORE_ROOTFS/.nextcraft-rootfs-version"
[[ -f "$core_marker" ]] || core_marker="$CORE_ROOTFS/.pocket-rootfs-version"
grep -qx 'ubuntu-24.04.5-arm64' "$core_marker" || {
  echo "Core rootfs is $(cat "$core_marker"), expected ubuntu-24.04.5-arm64" >&2
  exit 1
}

work="$(mktemp -d ${WORK_PARENT:+-p "$WORK_PARENT"})"
rootfs="$work/rootfs"
payload="$work/payload"
mounted=0

cleanup() {
  if [[ "$mounted" == "1" ]]; then
    umount -lf "$rootfs/dev" "$rootfs/proc" 2>/dev/null || true
  fi
  # Only delete the directory this script created itself via mktemp, and only when no mount is left inside it.
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
  # The F-flag binfmt handler already holds the interpreter, so copying it in is only needed for non-F setups.
  [[ -e "$rootfs/usr/bin/qemu-aarch64-static" ]] || cp "$(command -v qemu-aarch64-static)" "$rootfs/usr/bin/qemu-aarch64-static"
fi

mount --bind /dev "$rootfs/dev"
mount -t proc proc "$rootfs/proc"
mounted=1

guest() {
  chroot "$rootfs" /usr/bin/env -i HOME=/root USER=root LANG=C.UTF-8 DEBIAN_FRONTEND=noninteractive \
    GLIBC_TUNABLES=glibc.pthread.rseq=0 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    /bin/bash -lc "$1"
}

# ctime of everything dpkg writes during the install is newer than this marker.
marker="$rootfs/.python-build-marker"
touch "$marker"
sleep 1

guest "printf 'nameserver $DNS_SERVER\\n' > /etc/resolv.conf; apt-get update -qq && apt-get install -y --no-install-recommends python3 python3-pip python3-venv"
# Ubuntu 24.04 marks the system Python as externally managed (PEP 668), which makes `pip3 install` fail. The chroot is
# a disposable dev environment (like the Python 3.8 bundle was), so keep plain `pip install` working.
guest "rm -f /usr/lib/python3.12/EXTERNALLY-MANAGED"
guest "python3 --version | grep -q '^Python 3.12' && pip3 --version"

echo "Collecting overlay files"
mkdir -p "$payload"
(
  cd "$rootfs"
  # Skip state that belongs to the rootfs rather than to Python: dpkg/apt data, caches, logs, resolv.conf, ld cache,
  # timezone selection (tzdata's postinst would override the user's), docs, and the qemu helper itself.
  find . -xdev \( -type f -o -type l \) -cnewer "$marker" \
    -not -path './var/*' -not -path './proc/*' -not -path './dev/*' -not -path './tmp/*' -not -path './root/*' \
    -not -path './usr/bin/qemu-aarch64-static' -not -path './.python-build-marker' \
    -not -path './etc/resolv.conf' -not -path './etc/ld.so.cache' -not -path './etc/localtime' -not -path './etc/timezone' \
    -not -path './usr/share/doc/*' -not -path './usr/share/man/*' -not -path './usr/share/info/*' \
    -not -path './usr/share/lintian/*' -not -path './usr/share/bug/*' -not -path './usr/share/doc-base/*' \
    -not -path './usr/share/pixmaps/*' -not -path './usr/share/binfmts/*' \
    | sort > "$work/files.list"
  tar -cf - -T "$work/files.list" | tar -xf - -C "$payload"
)
[[ -x "$payload/usr/bin/python3.12" ]] || { echo "Overlay is missing usr/bin/python3.12" >&2; exit 1; }
[[ -e "$payload/usr/bin/pip3" ]] || { echo "Overlay is missing usr/bin/pip3" >&2; exit 1; }
[[ ! -e "$payload/usr/lib/python3.12/EXTERNALLY-MANAGED" ]]

printf 'python-bundle-%s\n' "$BUNDLE_VERSION" > "$payload/.nextcraft-python-tools-version"

mkdir -p "$OUT_DIR"
python3 "$SCRIPT_DIR/create_deterministic_tar.py" "$payload" "$work/payload.tar"
zstd -19 -T0 -f "$work/payload.tar" -o "$OUT_DIR/$ARCHIVE"
sha256sum "$OUT_DIR/$ARCHIVE"
wc -c "$OUT_DIR/$ARCHIVE" "$work/payload.tar"
echo "Python bundle created: $OUT_DIR/$ARCHIVE"
