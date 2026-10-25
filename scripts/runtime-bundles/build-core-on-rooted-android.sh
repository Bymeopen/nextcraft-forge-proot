#!/usr/bin/env bash
set -euo pipefail

# Build the portable NextCraft Forge Core bundle (Ubuntu 24.04.5 LTS noble ARM64)
# on a rooted ARM64 Android phone. The phone is only the build host: the archive
# is made from the official Ubuntu 24.04.5 ARM64 rootfs.
ADB_SERIAL="${ADB_SERIAL:-}"
REMOTE="${NEXTCRAFT_REMOTE_DIR:-/data/adb/nextcraft-bundle}"
VERSION="${NEXTCRAFT_CORE_VERSION:-2026.10.1}"
DNS_SERVER="${NEXTCRAFT_DNS:-1.1.1.1}"
ROOTFS_FILE="ubuntu-base-24.04.5-base-arm64.tar.gz"
ROOTFS_SHA256="a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"
ROOTFS_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/${ROOTFS_FILE}"

adb_cmd() {
  if [[ -n "$ADB_SERIAL" ]]; then adb -s "$ADB_SERIAL" "$@"; else adb "$@"; fi
}

[[ "$(uname -m)" == "arm64" || "$(uname -m)" == "aarch64" ]] || {
  echo "Run this from an ARM64 workstation controlling the rooted phone." >&2
  exit 1
}
adb_cmd wait-for-device
adb_cmd shell "su -c 'id -u'" | grep -qx 0 || { echo "ADB root is unavailable" >&2; exit 1; }

temp_dir="$(mktemp -d)"
# The rootfs gets /dev, /dev/pts, proc and sys bind-mounted below. If a run fails with those still mounted, the next
# run's `rm -rf` would walk into the real /dev of the phone, so unmount everything under the rootfs first and on exit.
unmount_rootfs() {
  adb_cmd shell "su -c 'for m in \$(grep \" $REMOTE/rootfs/\" /proc/mounts | cut -d\" \" -f2 | sort -r); do umount -l \$m 2>/dev/null; done'" || true
}
trap 'unmount_rootfs; rm -rf "$temp_dir"' EXIT
curl -fL --retry 3 -o "$temp_dir/$ROOTFS_FILE" "$ROOTFS_URL"
printf '%s  %s\n' "$ROOTFS_SHA256" "$temp_dir/$ROOTFS_FILE" | shasum -a 256 -c -
adb_cmd push "$temp_dir/$ROOTFS_FILE" /data/local/tmp/"$ROOTFS_FILE"

unmount_rootfs
adb_cmd shell "su -c 'rm -rf --one-file-system $REMOTE 2>/dev/null || rm -rf $REMOTE; mkdir -p $REMOTE/rootfs $REMOTE/output; toybox tar -xzf /data/local/tmp/$ROOTFS_FILE -C $REMOTE/rootfs; rm -f /data/local/tmp/$ROOTFS_FILE'"
adb_cmd shell "su -c 'mount --bind /dev $REMOTE/rootfs/dev; mount --bind /dev/pts $REMOTE/rootfs/dev/pts; mount -t proc proc $REMOTE/rootfs/proc; mount -t sysfs sysfs $REMOTE/rootfs/sys'"

# Run a command inside the rootfs. The command is base64-encoded so it reaches bash byte-for-byte:
# passing it through adb shell -> su -c '...' -> bash -lc "..." re-parses it three times, which drops
# newlines from printf format strings and breaks any command containing single quotes.
guest() {
  local command="$1" encoded
  encoded="$(printf '%s' "$command" | base64 | tr -d '\n')"
  adb_cmd shell "su -c 'chroot $REMOTE/rootfs /usr/bin/env -i HOME=/root USER=root LANG=C.UTF-8 GLIBC_TUNABLES=glibc.pthread.rseq=0 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/bash -lc \"\$(echo $encoded | base64 -d)\"'"
}

# 1. Konfigurasi Android Paranoid Network (AID_INET = 3003) & APT Non-Sandbox untuk Chroot Android
guest "grep -q '^aid_inet:' /etc/group || printf 'aid_inet:x:3003:root,_apt\naid_net_raw:x:3004:root,_apt\n' >> /etc/group"
guest "printf 'APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\n' > /etc/apt/apt.conf.d/99android-chroot"

# 2. Update sistem Ubuntu 24.04.5 LTS & instal kakas dasar
guest "printf 'nameserver $DNS_SERVER\\n' > /etc/resolv.conf; apt-get update && DEBIAN_FRONTEND=noninteractive apt-get -y upgrade"
guest "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends git ca-certificates curl wget unzip zip xz-utils zstd jq ripgrep"

# 3. Instal Node.js v24.22.1 ARM64 dengan verifikasi SHA256 resmi
guest "set -e; NODE_VERSION=v24.22.1; NODE_FILE=node-\${NODE_VERSION}-linux-arm64.tar.gz; cd /tmp; curl -fsSLO https://nodejs.org/dist/\${NODE_VERSION}/\${NODE_FILE}; curl -fsSL https://nodejs.org/dist/\${NODE_VERSION}/SHASUMS256.txt | grep \"  \${NODE_FILE}\" | sha256sum -c -; mkdir -p /usr/local/lib/nodejs; tar -xzf \${NODE_FILE} -C /usr/local/lib/nodejs; ln -sfn node-\${NODE_VERSION}-linux-arm64 /usr/local/lib/nodejs/current; for c in node npm npx corepack; do ln -sfn ../lib/nodejs/current/bin/\$c /usr/local/bin/\$c; done; rm -f /tmp/\${NODE_FILE}"
guest "apt-get clean && rm -rf /var/lib/apt/lists/* /var/cache/apt/* /tmp/* /var/tmp/*"

# 4. Siapkan direktori mount (/workspace/nextcraft, /nextcraft-bridge) & version markers
guest "mkdir -p /workspace /workspace/nextcraft /nextcraft-bridge /opt/nextcraft /root/.gradle/init.d /system /apex /vendor /product"
guest "printf 'ubuntu-24.04.5-arm64\\n' > /.nextcraft-rootfs-version"
guest "printf 'nextcraft-core-$VERSION\\n' > /.nextcraft-core-tools-version"
guest "printf 'core-bundle-$VERSION\\n' > /.nextcraft-runtime-ready"
guest "printf 'ubuntu-maintenance-v1\\n' > /.nextcraft-system-upgrade-version"
guest "printf '{\"WEB\":true,\"PYTHON\":false,\"CPP\":false,\"PHP\":false,\"ANDROID\":false}\\n' > /.nextcraft-dev-stacks.json"
guest "rm -rf /root/.cache /root/.npm /root/.composer /root/.gradle/caches /root/.ssh; find /var/log -type f -delete; rm -f /etc/ssh/ssh_host_* /etc/machine-id /var/lib/dbus/machine-id /root/.bash_history"

# 5. Unmount virtual filesystems secara bersih sebelum membuat arsip deterministik .tar.zst
adb_cmd shell "su -c 'umount -lf $REMOTE/rootfs/sys; umount -lf $REMOTE/rootfs/proc; umount -lf $REMOTE/rootfs/dev/pts; umount -lf $REMOTE/rootfs/dev; mkdir -p $REMOTE/rootfs/output; mount --bind $REMOTE/output $REMOTE/rootfs/output'"
guest "export ZSTD_CLEVEL=19 ZSTD_NBTHREADS=0; tar --exclude='./output' --exclude='._*' --exclude='.DS_Store' --sort=name --mtime=2026-09-05T00:00:00Z --owner=0 --group=0 --numeric-owner --use-compress-program=/usr/bin/zstd -cf /output/nextcraft-core-arm64-$VERSION.tar.zst -C / ."
adb_cmd shell "su -c 'umount -lf $REMOTE/rootfs/output; sha256sum $REMOTE/output/nextcraft-core-arm64-$VERSION.tar.zst; ls -lh $REMOTE/output/nextcraft-core-arm64-$VERSION.tar.zst'"
echo "Core bundle created on the rooted ARM64 device at $REMOTE/output/nextcraft-core-arm64-$VERSION.tar.zst"
