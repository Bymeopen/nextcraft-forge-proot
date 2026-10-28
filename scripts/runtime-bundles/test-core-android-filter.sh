#!/usr/bin/env bash
set -uo pipefail

# Run a Core bundle's guest under proot inside the Android-shaped seccomp filter (android-seccomp-run.c) and report which
# commands live. `expect` is "ok" for the patched core and "die" for the stock one (the control that shows the filter bites).
#
#   test-core-android-filter.sh <plain-musl-proot> <loader> <core.tar.zst> <ok|die>
PROOT="$(realpath "${1:?proot}")"; LOADER="$(realpath "${2:?loader}")"; CORE="$(realpath "${3:?core tar.zst}")"; EXPECT="${4:?ok|die}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
fail=0

gcc -O2 -o "$work/run" "$SCRIPT_DIR/android-seccomp-run.c"
python3 "$SCRIPT_DIR/gen-android-allowlist.py" > "$work/allow.txt"
mkdir -p "$work/rootfs" "$work/tmp"
zstd -dc "$CORE" | tar -x -C "$work/rootfs"

# What proot-exec.sh gives the guest.
export PROOT_TMP_DIR="$work/tmp" PROOT_LOADER="$LOADER" PROOT_NO_SECCOMP=1
guest_env=(UV_USE_IO_URING=0 HOME=/root LANG=C.UTF-8 PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin)
[[ -f "$work/rootfs/opt/nextcraft/lib/libnextcraft-compat.so" ]] && guest_env+=(LD_PRELOAD=/opt/nextcraft/lib/libnextcraft-compat.so)
guest=("$work/run" "$work/allow.txt" "$PROOT" --kill-on-exit --link2symlink -0 -r "$work/rootfs" -b /dev -b /proc -b /sys -w / /usr/bin/env -i "${guest_env[@]}")

check() { # check <label> <command run by bash in the guest>
  "${guest[@]}" /bin/bash -c "$2" >"$work/out.txt" 2>&1; local rc=$?
  local verdict="FAIL"
  if [[ "$EXPECT" == ok && $rc -eq 0 ]] || [[ "$EXPECT" == die && $rc -ne 0 ]]; then verdict="ok"; fi
  printf '[%s] %-40s expected %-3s rc=%s\n' "$verdict" "$1" "$EXPECT" "$rc"
  if [[ "$verdict" == FAIL ]]; then fail=1; tail -n 6 "$work/out.txt" | sed 's/^/        /'; fi
}

echo "== libc patch marker: $(cat "$work/rootfs/.nextcraft-libc-patch-version" 2>/dev/null || echo none)"
check "bash: command lookup, files"       'ls / >/dev/null && [ -x /bin/sh ] && d=$(mktemp -d) && echo hi > $d/f && cp $d/f $d/g && chmod 600 $d/f && rm -rf $d && echo ok'
check "coreutils, id, uname"              'id && uname -a && env true'
check "node: fs, os, net"                 'node -e "require(\"fs\").accessSync(\"/etc/passwd\");require(\"fs\").readdirSync(\"/\");require(\"os\").cpus();require(\"net\").createServer().listen(0,\"127.0.0.1\",function(){this.close()})"'
check "node: worker thread"               'node -e "new (require(\"worker_threads\").Worker)(\"1\",{eval:true}).on(\"exit\",()=>{})"'
check "npm --version"                     'npm --version'
check "git"                               'git --version && d=$(mktemp -d) && git init -q $d && git -C $d status >/dev/null && rm -rf $d'
check "curl, jq, rg"                      'curl --version | head -1 && jq -n 1 && rg --version | head -1'

if [[ $fail -eq 0 ]]; then echo "RESULT: as expected ($EXPECT)"; else echo "RESULT: NOT as expected"; fi
exit $fail
