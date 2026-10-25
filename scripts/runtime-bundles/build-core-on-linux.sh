#!/usr/bin/env bash
set -euo pipefail

# Build the NextCraft Core bundle (Ubuntu 24.04.5 LTS noble ARM64) on an ARM64 Linux host with root, e.g. a GitHub
# Actions ubuntu-24.04-arm runner. Same steps as build-core-on-rooted-android.sh, with a local chroot instead of adb.
#
#   sudo NEXTCRAFT_CORE_VERSION=2026.10.2 ./build-core-on-linux.sh
#
# Environment:
#   NEXTCRAFT_CORE_VERSION  bundle version (default 2026.10.2)
#   NEXTCRAFT_OUT_DIR       output directory (default: dist/runtime-bundles, git-ignored)
#   NEXTCRAFT_DNS           resolver used inside the chroot while building (default 1.1.1.1)
VERSION="${NEXTCRAFT_CORE_VERSION:-2026.10.2}"
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

work="$(mktemp -d)"
rootfs="$work/rootfs"
# Unmount before deleting: rm -rf must never walk into the host's /dev or /proc.
unmount_rootfs() {
  # grep exits 1 once nothing is mounted; that is the normal end state, not an error.
  { grep " $rootfs/" /proc/mounts || true; } | cut -d' ' -f2 | sort -r | while read -r m; do umount -l "$m" 2>/dev/null || true; done
}
trap 'unmount_rootfs; rm -rf --one-file-system "$work" || true' EXIT

curl -fL --retry 3 -o "$work/$ROOTFS_FILE" "$ROOTFS_URL"
printf '%s  %s\n' "$ROOTFS_SHA256" "$work/$ROOTFS_FILE" | sha256sum -c -
mkdir -p "$rootfs"
tar -xzf "$work/$ROOTFS_FILE" -C "$rootfs"
rm -f "$work/$ROOTFS_FILE"
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

# 3. Node.js v24.22.1 ARM64, verified against the official SHASUMS256.txt.
guest 'set -e; NODE_VERSION=v24.22.1; NODE_FILE=node-${NODE_VERSION}-linux-arm64.tar.gz; cd /tmp
curl -fsSLO https://nodejs.org/dist/${NODE_VERSION}/${NODE_FILE}
curl -fsSL https://nodejs.org/dist/${NODE_VERSION}/SHASUMS256.txt | grep "  ${NODE_FILE}" | sha256sum -c -
mkdir -p /usr/local/lib/nodejs; tar -xzf ${NODE_FILE} -C /usr/local/lib/nodejs
ln -sfn node-${NODE_VERSION}-linux-arm64 /usr/local/lib/nodejs/current
for c in node npm npx corepack; do ln -sfn ../lib/nodejs/current/bin/$c /usr/local/bin/$c; done
rm -f /tmp/${NODE_FILE}'
guest "apt-get clean && rm -rf /var/lib/apt/lists/* /var/cache/apt/* /tmp/* /var/tmp/*"

# 4. Mount points and version markers.
guest "mkdir -p /workspace /workspace/nextcraft /nextcraft-bridge /nextcraft-bridge-responses /opt/nextcraft /root/.gradle/init.d /system /apex /vendor /product"
guest "printf 'ubuntu-24.04.5-arm64\n' > /.nextcraft-rootfs-version"
guest "printf 'nextcraft-core-$VERSION\n' > /.nextcraft-core-tools-version"
guest "printf 'core-bundle-$VERSION\n' > /.nextcraft-runtime-ready"
guest "printf 'ubuntu-maintenance-v1\n' > /.nextcraft-system-upgrade-version"
guest "printf '{\"WEB\":true,\"PYTHON\":false,\"CPP\":false,\"PHP\":false,\"ANDROID\":false}\n' > /.nextcraft-dev-stacks.json"
guest "rm -rf /root/.cache /root/.npm /root/.composer /root/.gradle/caches /root/.ssh; find /var/log -type f -delete; rm -f /etc/ssh/ssh_host_* /etc/machine-id /var/lib/dbus/machine-id /root/.bash_history /etc/resolv.conf"

# 5. Deterministic archive, made after the virtual filesystems are gone.
unmount_rootfs
mkdir -p "$OUT_DIR"
ZSTD_CLEVEL=19 ZSTD_NBTHREADS=0 tar --exclude='._*' --exclude='.DS_Store' --sort=name --mtime=2026-09-05T00:00:00Z \
  --owner=0 --group=0 --numeric-owner --use-compress-program=zstd -cf "$OUT_DIR/$ARCHIVE" -C "$rootfs" .
sha256sum "$OUT_DIR/$ARCHIVE"
wc -c "$OUT_DIR/$ARCHIVE"
echo "Core bundle created: $OUT_DIR/$ARCHIVE"
