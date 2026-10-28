#!/usr/bin/env bash
set -euo pipefail

# Run a battery of guest commands from an Alpine Core bundle under strace and fail when any of them uses a syscall that
# Android's per-app seccomp filter refuses (see audit-android-seccomp.py). Needs an ARM64 Linux host, root and docker.
#
#   sudo scripts/runtime-bundles/audit-alpine-rootfs.sh out/nextcraft-core-alpine-arm64-2026.10.3.tar.zst
#
# strace is installed into the extracted copy only; the archive is not modified. It is a guard, not a proof: the real
# device decides (sigsys-probe.c).
ARCHIVE="${1:?usage: audit-alpine-rootfs.sh <core-alpine.tar.zst>}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
[[ "$(id -u)" == 0 ]] || { echo "Run as root (chroot and mounts)." >&2; exit 1; }

work="$(mktemp -d)"
rootfs="$work/rootfs"
logs="$work/logs"
unmount_rootfs() {
  { grep " $rootfs/" /proc/mounts || true; } | cut -d' ' -f2 | sort -r | while read -r m; do umount -l "$m" 2>/dev/null || true; done
}
trap 'unmount_rootfs; rm -rf --one-file-system "$work" || true' EXIT
mkdir -p "$rootfs" "$logs"
zstd -dc "$ARCHIVE" | tar -x -C "$rootfs"

# strace is a guest tool here so that only guest syscalls are recorded (a host-side `chroot` would add its own).
docker run --rm -v "$rootfs:/target" alpine:3.24 sh -euc 'apk add --root /target --no-cache --keys-dir /etc/apk/keys strace >/dev/null'
cp /etc/resolv.conf "$rootfs/etc/resolv.conf"
mount --bind /dev "$rootfs/dev"
mount -t proc proc "$rootfs/proc"
mkdir -p "$rootfs/trace"

n=0
run() { # run <label> <command string run by bash inside the guest>
  n=$((n + 1))
  echo "== $1"
  chroot "$rootfs" /usr/bin/env -i HOME=/root LANG=C.UTF-8 PATH=/usr/local/bin:/usr/bin:/bin:/sbin \
    UV_USE_IO_URING=0 LD_PRELOAD=/opt/nextcraft/lib/libnextcraft-compat.so \
    /usr/bin/strace -f -qq -o "/trace/$n.strace" /bin/bash -c "$2" 2>&1 | tail -n 4 || true
  mv "$rootfs/trace/$n.strace" "$logs/$n-$1.strace"
}

# One command per run, so a blocked syscall names the tool that issued it. The environment above is what proot-exec.sh gives
# the guest: UV_USE_IO_URING=0 (libuv would call io_uring_setup) and the faccessat shim (seccomp-compat.c).
run shell 'ls / >/dev/null; cat /etc/passwd >/dev/null; id; uname -a; env true'
run bash-test '[ -x /bin/sh ] && [ -f /etc/passwd ] && [ -d / ]; type ls'
run bash-files 'set -e; d=$(mktemp -d); echo hi > $d/f; cp $d/f $d/g; mv $d/g $d/h; ln -s f $d/l; chmod 600 $d/f; stat $d/f >/dev/null; find $d | sort; rm -rf $d'
run node-fs-net 'node -e "const fs=require(\"fs\");fs.accessSync(\"/etc/passwd\");fs.readdirSync(\"/\");fs.statSync(\"/\");require(\"os\").cpus();require(\"dns\").lookup(\"localhost\",()=>{});require(\"net\").createServer().listen(0,\"127.0.0.1\",function(){this.close()})"'
run node-worker 'node -e "new (require(\"worker_threads\").Worker)(\"1\",{eval:true}).on(\"exit\",()=>{})"'
run npm 'npm --version; npm config get registry'
run git 'git --version; d=$(mktemp -d); git init -q $d; git -C $d status >/dev/null; rm -rf $d'
run curl 'curl --version | head -1'
run jq 'jq -n 1'
run rg 'rg --version | head -1'
run zstd 'zstd --version'
run unzip 'unzip -v | head -1'
run awk 'awk "BEGIN{print 1}"'

python3 "$SCRIPT_DIR/audit-android-seccomp.py" "$logs"/*.strace
