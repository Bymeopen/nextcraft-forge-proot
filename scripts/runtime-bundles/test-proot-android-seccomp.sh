#!/usr/bin/env bash
set -uo pipefail

# Reproduce the Android failure on an ARM64 Linux host and show that the patched musl proot fixes it.
#
# android-seccomp-run installs a filter shaped like Android's app policy (AOSP allowlist, default SIGSYS). Under it:
#   A. the glibc proot shipped today must die (the device's "Bad system call" with no output),
#   B. plain musl proot starts but its Ubuntu guest must die (the device's "guest /bin/true exited 159"),
#   C. the patched musl proot must run the same Ubuntu guest: shell, bash, Node and npm.
# A and B failing is what makes C meaningful: it proves the harness reproduces the failure.
#
#   test-proot-android-seccomp.sh <patched-proot> <patched-loader> <plain-proot> <glibc-proot> <core-ubuntu.tar.zst>
# Absolute paths: proot changes directory inside the guest, so a relative PROOT_LOADER would stop resolving.
PATCHED="$(realpath "${1:?patched proot}")"; LOADER="$(realpath "${2:?loader}")"; PLAIN="$(realpath "${3:?plain musl proot}")"
GLIBC="$(realpath "${4:?glibc proot}")"; CORE="$(realpath "${5:?core tar.zst}")"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
fail=0

gcc -O2 -o "$work/run" "$SCRIPT_DIR/android-seccomp-run.c"
python3 "$SCRIPT_DIR/gen-android-allowlist.py" > "$work/allow.txt"
mkdir -p "$work/rootfs" "$work/tmp"
zstd -dc "$CORE" | tar -x -C "$work/rootfs"

export PROOT_TMP_DIR="$work/tmp" PROOT_LOADER="$LOADER" PROOT_NO_SECCOMP=1
guest_args=(--kill-on-exit --link2symlink -0 -r "$work/rootfs" -b /dev -b /proc -b /sys -w /)

check() { # check <label> <expect: ok|die> <command...>
  local label="$1" expect="$2"; shift 2
  "$@" >"$work/out.txt" 2>&1; local rc=$?
  local verdict="FAIL"
  if [[ "$expect" == ok && $rc -eq 0 ]] || [[ "$expect" == die && $rc -ne 0 ]]; then verdict="ok"; fi
  printf '[%s] %-58s expected %-3s rc=%s\n' "$verdict" "$label" "$expect" "$rc"
  if [[ "$verdict" == FAIL ]]; then fail=1; tail -n 12 "$work/out.txt" | sed 's/^/        /'; fi
}
HARN=("$work/run" "$work/allow.txt")

echo "== A. glibc proot under the Android-like filter"
check "glibc proot --version" die "${HARN[@]}" "$GLIBC" --version

echo "== B. plain musl proot: starts, but its Ubuntu guest dies"
check "plain musl proot --version" ok "${HARN[@]}" "$PLAIN" --version
check "plain musl proot, guest /bin/true" die "${HARN[@]}" "$PLAIN" "${guest_args[@]}" /bin/true

echo "== C. patched musl proot: the same guest must live"
check "patched proot --version" ok "${HARN[@]}" "$PATCHED" --version
check "guest /bin/true" ok "${HARN[@]}" "$PATCHED" "${guest_args[@]}" /bin/true
check "guest bash: command lookup, files, [ -x ]" ok "${HARN[@]}" "$PATCHED" "${guest_args[@]}" /bin/bash -c 'ls / >/dev/null && [ -x /bin/sh ] && echo ok'
check "guest node" ok "${HARN[@]}" "$PATCHED" "${guest_args[@]}" /usr/local/bin/node -e 'require("fs").readdirSync("/"); require("os").cpus(); console.log("node ok")'
check "guest npm --version" ok "${HARN[@]}" "$PATCHED" "${guest_args[@]}" /usr/local/bin/npm --version
check "guest without GLIBC_TUNABLES (rseq must be answered too)" ok env -u GLIBC_TUNABLES "${HARN[@]}" "$PATCHED" "${guest_args[@]}" /bin/bash -c 'echo ok'

if [[ $fail -eq 0 ]]; then echo "ALL CHECKS PASSED"; else echo "SOME CHECKS FAILED"; fi
exit $fail
