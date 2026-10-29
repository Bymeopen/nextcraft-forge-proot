#!/bin/sh
# Diagnoses "exit 159 (signal 31)" under the PRoot engine. Run it in the app's Terminal tab (guest shell):
#   sh /path/to/proot-diag.sh            # report is printed and saved to $HOME/proot-diag.log
# 159 = 128 + 31 = SIGSYS: a syscall was refused by a seccomp filter (Android's app filter or proot's own).
# Each probe is run in isolation so the first one that dies with 159 names the culprit.
LOG="${PROOT_DIAG_LOG:-${HOME:-/root}/proot-diag.log}"
: > "$LOG" 2>/dev/null || LOG="/tmp/proot-diag.log"
: > "$LOG"

say() { printf '%s\n' "$*" | tee -a "$LOG"; }

# probe <label> <cmd...>: runs the command, logs its output tail and a decoded exit status.
probe() {
    label="$1"; shift
    out="$("$@" 2>&1)"
    rc=$?
    note=""
    if [ "$rc" -gt 128 ]; then
        sig=$((rc - 128))
        note=" <- killed by signal $sig ($(kill -l "$sig" 2>/dev/null || echo '?'))"
        [ "$sig" -eq 31 ] && note="$note: SIGSYS, blocked syscall"
    fi
    say "[$rc] $label$note"
    [ -n "$out" ] && printf '%s\n' "$out" | tail -n 5 | sed 's/^/      /' | tee -a "$LOG"
    return 0
}

say "== environment =="
say "date:      $(date 2>/dev/null)"
say "uname:     $(uname -a 2>/dev/null)"
say "arch:      $(uname -m 2>/dev/null)"
say "android:   sdk=$(getprop ro.build.version.sdk 2>/dev/null) release=$(getprop ro.build.version.release 2>/dev/null) device=$(getprop ro.product.model 2>/dev/null)"
say "seccomp:   $(grep -E '^Seccomp' /proc/self/status 2>/dev/null | tr '\n\t' '  ')"
say "PROOT_*:   $(env | grep -E '^(PROOT|NEXTCRAFT_PROOT)' | tr '\n' ' ')"
say "free disk: $(df -h /root /tmp 2>/dev/null | tail -n +2 | tr '\n' ';')"

say ""
say "== basic syscalls (shell builtins and coreutils) =="
probe "echo"             sh -c 'echo ok'
probe "ls /root"         ls /root
probe "stat /proc/self"  stat /proc/self
probe "getent hosts"     getent hosts registry.npmjs.org
probe "dns resolv.conf"  cat /etc/resolv.conf

say ""
say "== node (the failing runtime for Claude Code and Antigravity) =="
probe "node --version"           node --version
probe "node -e (plain)"          node -e 'console.log("node ok", process.version, process.arch)'
probe "node -e (no io_uring)"    env UV_USE_IO_URING=0 node -e 'require("fs").readdirSync("/root"); console.log("fs ok")'
probe "node fs.promises"         node -e 'require("fs/promises").readdir("/root").then(()=>console.log("fs/promises ok"))'
probe "node net/dns"             node -e 'require("dns").lookup("registry.npmjs.org",(e,a)=>console.log(e?e.code:a))'
probe "node worker_threads"      node -e 'new (require("worker_threads").Worker)("1",{eval:true}).on("exit",c=>console.log("worker exit",c))'
probe "npm --version"            npm --version
probe "npm registry ping"        npm ping --registry=https://registry.npmjs.org/
probe "npm view claude-code"     npm view @anthropic-ai/claude-code@2.1.112 dist.tarball

say ""
say "== real install attempt (verbose log in $LOG.npm) =="
tmpdir="$(mktemp -d 2>/dev/null || echo /tmp/proot-diag-npm)"
mkdir -p "$tmpdir"
(cd "$tmpdir" && npm install --prefix "$tmpdir" --no-audit --no-fund --loglevel=verbose @anthropic-ai/claude-code@2.1.112 >"$LOG.npm" 2>&1)
rc=$?
say "[$rc] npm install @anthropic-ai/claude-code@2.1.112"
[ "$rc" -gt 128 ] && say "      killed by signal $((rc - 128)) ($(kill -l $((rc - 128)) 2>/dev/null))"
tail -n 15 "$LOG.npm" | sed 's/^/      /' | tee -a "$LOG"
rm -rf "$tmpdir"

say ""
say "== host-side hints (cannot be read from the guest) =="
say "Kernel SIGSYS reports live in the Android log; from the Android shell bridge:"
say "  android-shell logcat -d | grep -iE 'SIGSYS|seccomp|audit.*syscall'"
say "The audit line carries syscall=<nr> (arm64 numbers); that number is the syscall to special-case."
say ""
say "Report saved to: $LOG   (npm verbose log: $LOG.npm)"
