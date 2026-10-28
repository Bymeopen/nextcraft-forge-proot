#!/usr/bin/env bash
set -euo pipefail

# Build the Ubuntu 24.04.5 ARM64 Core bundle for the PRoot engine, from the official ubuntu-base tarball. Same layout and
# markers as build-core-on-linux.sh (the installer accepts it unchanged), with two differences that matter on Android:
#   1. libc6 and libc-bin come from build-glibc-android.sh (glibc that does not issue the syscalls Android's seccomp filter
#      refuses) and are held, so a later `apt upgrade` cannot bring back the stock ones.
#   2. /opt/nextcraft/lib/libnextcraft-compat.so (seccomp-compat.c): answers syscall() for io_uring and similar with ENOSYS.
#      proot-exec.sh preloads it when present.
#
#   sudo NEXTCRAFT_GLIBC_DEBS=out/glibc-debs NEXTCRAFT_CORE_VERSION=2026.10.4 ./build-core-ubuntu24-proot.sh
#
# Needs an ARM64 Linux host with root (chroot, mounts) and gcc.
VERSION="${NEXTCRAFT_CORE_VERSION:-2026.10.4}"
DEBS="${NEXTCRAFT_GLIBC_DEBS:?Set NEXTCRAFT_GLIBC_DEBS to the directory with the patched libc6/libc-bin .deb files}"
DNS_SERVER="${NEXTCRAFT_DNS:-1.1.1.1}"
ROOTFS_FILE="ubuntu-base-24.04.5-base-arm64.tar.gz"
ROOTFS_SHA256="a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"
ROOTFS_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/${ROOTFS_FILE}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="${NEXTCRAFT_OUT_DIR:-$ROOT_DIR/dist/runtime-bundles}"
ARCHIVE="nextcraft-core-arm64-$VERSION.tar.zst"

[[ "$(uname -m)" == "aarch64" ]] || { echo "Run this on an ARM64 Linux host." >&2; exit 1; }
[[ "$(id -u)" == 0 ]] || { echo "Run this as root (chroot and mounts)." >&2; exit 1; }
DEBS="$(realpath "$DEBS")"
ls "$DEBS"/libc6_*.deb "$DEBS"/libc-bin_*.deb >/dev/null

work="$(mktemp -d)"
rootfs="$work/rootfs"
# Unmount before deleting: removal must never walk into the host's /dev or /proc.
unmount_rootfs() {
  { grep " $rootfs/" /proc/mounts || true; } | cut -d' ' -f2 | sort -r | while read -r m; do umount -l "$m" 2>/dev/null || true; done
}
trap 'unmount_rootfs; rm -rf --one-file-system "$work" || true' EXIT

curl -fL --retry 3 -o "$work/$ROOTFS_FILE" "$ROOTFS_URL"
printf '%s  %s\n' "$ROOTFS_SHA256" "$work/$ROOTFS_FILE" | sha256sum -c -
mkdir -p "$rootfs"
tar -xzf "$work/$ROOTFS_FILE" -C "$rootfs"
mount --bind /dev "$rootfs/dev"
mount --bind /dev/pts "$rootfs/dev/pts"
mount -t proc proc "$rootfs/proc"
mount -t sysfs sysfs "$rootfs/sys"

guest() {
  chroot "$rootfs" /usr/bin/env -i HOME=/root USER=root LANG=C.UTF-8 GLIBC_TUNABLES=glibc.pthread.rseq=0 \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/bash -lc "$1"
}

# 1. Android paranoid networking (AID_INET = 3003) and an APT that does not drop to the _apt user.
guest "grep -q '^aid_inet:' /etc/group || printf 'aid_inet:x:3003:root,_apt\naid_net_raw:x:3004:root,_apt\n' >> /etc/group"
guest "printf 'APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\n' > /etc/apt/apt.conf.d/99android-chroot"

# 2. Update Ubuntu and install the base tools.
guest "printf 'nameserver $DNS_SERVER\n' > /etc/resolv.conf; apt-get update && DEBIAN_FRONTEND=noninteractive apt-get -y upgrade"
guest "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends git ca-certificates curl wget unzip zip xz-utils zstd jq ripgrep"

# 3. The glibc that skips what Android refuses, replacing the stock libc6 and libc-bin, then held.
mkdir -p "$rootfs/tmp/glibc-debs"
cp "$DEBS"/libc6_*.deb "$DEBS"/libc-bin_*.deb "$rootfs/tmp/glibc-debs/"
guest "dpkg -i /tmp/glibc-debs/libc6_*.deb /tmp/glibc-debs/libc-bin_*.deb && apt-mark hold libc6 libc-bin && dpkg -l libc6 libc-bin | tail -2"

# 4. Node.js v24.22.1 ARM64, verified against the official SHASUMS256.txt.
guest 'set -e; NODE_VERSION=v24.22.1; NODE_FILE=node-${NODE_VERSION}-linux-arm64.tar.gz; cd /tmp
curl -fsSLO https://nodejs.org/dist/${NODE_VERSION}/${NODE_FILE}
curl -fsSL https://nodejs.org/dist/${NODE_VERSION}/SHASUMS256.txt | grep "  ${NODE_FILE}" | sha256sum -c -
mkdir -p /usr/local/lib/nodejs; tar -xzf ${NODE_FILE} -C /usr/local/lib/nodejs
ln -sfn node-${NODE_VERSION}-linux-arm64 /usr/local/lib/nodejs/current
for c in node npm npx corepack; do ln -sfn ../lib/nodejs/current/bin/$c /usr/local/bin/$c; done'
guest "apt-get clean && rm -rf /var/lib/apt/lists/* /var/cache/apt/* /tmp/* /var/tmp/*"

# 5. The syscall() shim, compiled on this host (same glibc 2.39 as the guest).
mkdir -p "$rootfs/opt/nextcraft/lib"
gcc -O2 -shared -fPIC -o "$rootfs/opt/nextcraft/lib/libnextcraft-compat.so" "$SCRIPT_DIR/seccomp-compat.c"

# 6. Mount points and version markers (same as build-core-on-linux.sh, plus a marker for the libc patch).
guest "mkdir -p /workspace /workspace/nextcraft /nextcraft-bridge /nextcraft-bridge-responses /opt/nextcraft /root/.gradle/init.d /system /apex /vendor /product"
guest "printf 'ubuntu-24.04.5-arm64\n' > /.nextcraft-rootfs-version"
guest "printf 'nextcraft-core-$VERSION\n' > /.nextcraft-core-tools-version"
guest "printf 'core-bundle-$VERSION\n' > /.nextcraft-runtime-ready"
guest "printf 'ubuntu-maintenance-v1\n' > /.nextcraft-system-upgrade-version"
guest "printf 'glibc-android-syscalls-v1\n' > /.nextcraft-libc-patch-version"
guest "printf '{\"WEB\":true,\"PYTHON\":false,\"CPP\":false,\"PHP\":false,\"ANDROID\":false}\n' > /.nextcraft-dev-stacks.json"
guest "rm -rf /root/.cache /root/.npm /root/.composer /root/.gradle/caches /root/.ssh; find /var/log -type f -delete; rm -f /etc/ssh/ssh_host_* /etc/machine-id /var/lib/dbus/machine-id /root/.bash_history /etc/resolv.conf"

# 7. Deterministic archive, made after the virtual filesystems are gone.
unmount_rootfs
mkdir -p "$OUT_DIR"
ZSTD_CLEVEL=19 ZSTD_NBTHREADS=0 tar --exclude='._*' --exclude='.DS_Store' --sort=name --mtime=2026-09-05T00:00:00Z \
  --owner=0 --group=0 --numeric-owner --use-compress-program=zstd -cf "$OUT_DIR/$ARCHIVE" -C "$rootfs" .
sha256sum "$OUT_DIR/$ARCHIVE"
wc -c "$OUT_DIR/$ARCHIVE"
echo "Core bundle created: $OUT_DIR/$ARCHIVE"
