#!/usr/bin/env bash
set -euo pipefail

# Build the NextCraft Forge DeepSeek Harness (dsh) bundle without a phone, by running the same `npm install` the app
# runs (RuntimeInstaller.updateDsh) inside a chroot copy of the Core rootfs, with the Core bundle's own Node/npm.
#
# Needs root. On an ARM64 host it runs natively; on x86_64 it needs qemu-user-static with an aarch64 binfmt handler
# registered with the `F` (fix-binary) flag.
#
#   sudo NEXTCRAFT_CORE_ROOTFS=/path/to/extracted/core-rootfs ./build-dsh-in-chroot.sh
#
# NEXTCRAFT_CORE_ROOTFS must be an extracted nextcraft-core-arm64-*.tar.zst. It is only read; a copy is used.
# Behind a TLS-intercepting proxy, set NEXTCRAFT_EXTRA_CA_FILE to its CA (PEM): it is used for npm only and never
# ends up in the bundle, which holds just /usr/local/lib/dsh and the /usr/local/bin/dsh launcher.
#
# npm resolves the dependency ranges of @deepseek-ai/dsh at build time, so a rebuild on a later day can pick newer
# dependency versions and produce a different archive. Pin the result by its SHA-256 in RuntimeInstaller.
CORE_ROOTFS="${NEXTCRAFT_CORE_ROOTFS:?Set NEXTCRAFT_CORE_ROOTFS to an extracted Core bundle rootfs}"
DSH_VERSION="${NEXTCRAFT_DSH_VERSION:-0.2.1-rc.2}"
BUNDLE_VERSION="${NEXTCRAFT_DSH_BUNDLE_VERSION:-2026.10.1}"
DNS_SERVER="${NEXTCRAFT_DNS:-1.1.1.1}"
EXTRA_CA_FILE="${NEXTCRAFT_EXTRA_CA_FILE:-}"
WORK_PARENT="${NEXTCRAFT_WORK_DIR:-}"
KEEP_WORK="${NEXTCRAFT_KEEP_WORK:-0}"
ARCHIVE="nextcraft-dsh-arm64-${BUNDLE_VERSION}.tar.zst"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="${NEXTCRAFT_OUT_DIR:-$ROOT_DIR/dist/runtime-bundles}"

[[ "$(id -u)" == "0" ]] || { echo "Run as root (chroot and mount are required)" >&2; exit 1; }
for tool in zstd python3 chroot tar; do
  command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 1; }
done
# Same validation as RuntimeInstaller: the version ends up in a shell command inside the guest.
[[ "$DSH_VERSION" =~ ^[0-9A-Za-z.+-]+$ ]] || { echo "Invalid DeepSeek Harness version: $DSH_VERSION" >&2; exit 1; }
[[ -e "$CORE_ROOTFS/usr/local/bin/node" && ( -f "$CORE_ROOTFS/.nextcraft-rootfs-version" || -f "$CORE_ROOTFS/.pocket-rootfs-version" ) ]] || {
  echo "$CORE_ROOTFS does not look like an extracted Core bundle (no Node.js)" >&2
  exit 1
}
[[ -z "$EXTRA_CA_FILE" || -f "$EXTRA_CA_FILE" ]] || { echo "NEXTCRAFT_EXTRA_CA_FILE not found: $EXTRA_CA_FILE" >&2; exit 1; }

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
  [[ -e "$rootfs/usr/bin/qemu-aarch64-static" ]] || cp "$(command -v qemu-aarch64-static)" "$rootfs/usr/bin/qemu-aarch64-static"
fi
ca_env=""
if [[ -n "$EXTRA_CA_FILE" ]]; then
  cp "$EXTRA_CA_FILE" "$rootfs/extra-ca.pem"
  ca_env="NODE_EXTRA_CA_CERTS=/extra-ca.pem npm_config_cafile=/extra-ca.pem"
fi

mount --bind /dev "$rootfs/dev"
mount -t proc proc "$rootfs/proc"
mounted=1

guest() {
  # $ca_env is intentionally unquoted: it is empty or a list of NAME=value words for env.
  # shellcheck disable=SC2086
  chroot "$rootfs" /usr/bin/env -i HOME=/root USER=root LANG=C.UTF-8 $ca_env \
    GLIBC_TUNABLES=glibc.pthread.rseq=0 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    /bin/bash -lc "$1"
}

guest "printf 'nameserver $DNS_SERVER\\n' > /etc/resolv.conf"
guest "set -e; dir=/usr/local/lib/dsh; rm -rf \"\$dir\"; mkdir -p \"\$dir\"; cd \"\$dir\"; npm init -y >/dev/null; npm install --omit=dev --no-audit --no-fund @deepseek-ai/dsh@$DSH_VERSION"
guest "ln -sfn /usr/local/lib/dsh/node_modules/.bin/dsh /usr/local/bin/dsh"
actual_version="$(guest "dsh --version" | tail -1 | tr -d '\r')"
[[ "$actual_version" == "$DSH_VERSION" ]] || { echo "Expected dsh $DSH_VERSION, got '$actual_version'" >&2; exit 1; }

mkdir -p "$payload/usr/local/lib" "$payload/usr/local/bin"
cp -a "$rootfs/usr/local/lib/dsh" "$payload/usr/local/lib/"
cp -a "$rootfs/usr/local/bin/dsh" "$payload/usr/local/bin/"
printf '%s\n' "$DSH_VERSION" > "$payload/.nextcraft-dsh-version"
printf '%s\n' "$DSH_VERSION" > "$payload/.nextcraft-dsh-version"
[[ -L "$payload/usr/local/bin/dsh" ]]
[[ -e "$payload/usr/local/lib/dsh/node_modules/.bin/dsh" ]]
# npm's cache and logs live under /root, outside the payload; make sure nothing from the build host leaked in.
[[ ! -e "$payload/extra-ca.pem" ]]

mkdir -p "$OUT_DIR"
python3 "$SCRIPT_DIR/create_deterministic_tar.py" "$payload" "$work/payload.tar"
zstd -19 -T0 -f "$work/payload.tar" -o "$OUT_DIR/$ARCHIVE"
sha256sum "$OUT_DIR/$ARCHIVE"
wc -c "$OUT_DIR/$ARCHIVE" "$work/payload.tar"
echo "DeepSeek Harness bundle created: $OUT_DIR/$ARCHIVE"
