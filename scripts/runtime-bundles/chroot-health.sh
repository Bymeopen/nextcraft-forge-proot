#!/usr/bin/env bash
# Health check for the NextCraft Forge Ubuntu chroot. Run it from the in-app chroot terminal:
#   bash chroot-health.sh 2>&1 | tee chroot-health.log
# Prints one OK/ERR line per component. ERR for a stack that is not installed is expected.
pass=0
fail=0

# check NAME COMMAND: OK when COMMAND exits 0. Each command must fail as a whole (use &&, not ;).
check() {
  local name="$1"
  shift
  local out
  if out=$(timeout 60 bash -c "$*" 2>&1); then
    printf 'OK   %-22s %s\n' "$name" "$(printf '%s' "$out" | head -1 | cut -c1-70)"
    pass=$((pass + 1))
  else
    printf 'ERR  %-22s %s\n' "$name" "$(printf '%s' "$out" | tail -1 | cut -c1-90)"
    fail=$((fail + 1))
  fi
}

# check_optional NAME COMMAND: like check, but a failure prints SKIP and is not counted as an error.
check_optional() {
  local name="$1"
  shift
  local out
  if out=$(timeout 60 bash -c "$*" 2>&1); then
    printf 'OK   %-22s %s\n' "$name" "$(printf '%s' "$out" | head -1 | cut -c1-70)"
    pass=$((pass + 1))
  else
    printf 'SKIP %-22s not installed (optional)\n' "$name"
  fi
}

echo "== Sistem"
check "rootfs"            'cat /.nextcraft-rootfs-version 2>/dev/null || cat /.pocket-rootfs-version'
check "glibc"             'ldd --version | head -1'
check "disk /"            'df -h / | awk "NR==2{print \$4\" free\"; exit (\$5+0>95)}"'
check "/tmp writable"     'f=$(mktemp) && rm -f "$f" && echo ok'
# proot needs --link2symlink because Android storage may refuse hard links; the chroot does not, if the rootfs
# filesystem supports them. dpkg and git rely on link(2), so report it explicitly.
check "hardlinks"         'd=$(mktemp -d /root/.hl.XXXXXX) && : > "$d/a" && ln "$d/a" "$d/b" && [ "$(stat -c %h "$d/a")" = 2 ] && rm -rf "$d" && echo supported'

if [ -n "${PROOT_TMP_DIR:-}" ]; then
  # proot-exec.sh exports PROOT_TMP_DIR; there are no kernel mounts in this mode (ADR-007), only path bindings, and
  # -b bindings are always read-write regardless of mode bits, so the "ro" check below only applies to a real chroot.
  echo "== Mount (mesin PRoot: dilewati)"
  check "engine"            'echo proot'
  for d in /dev /proc /sys /tmp /system; do check "bind $d" "test -d $d && echo bound"; done
else
  echo "== Mount"
  check "engine"            'echo chroot'
  for m in /dev /dev/pts /proc /sys /tmp /system /apex /vendor /product; do
    check "mount $m"        "grep -q ' $m ' /proc/mounts && echo mounted"
  done
  # Bionic tools need the APEX modules mounted under /apex (see docs/RUNTIME_KNOWN_ISSUES.md #1).
  check "apex submounts"    'n=$(grep -c " /apex/" /proc/mounts); echo "$n submount"; [ "$n" -gt 0 ]'
  check "linker64"          't=$(readlink -f /system/bin/linker64) && test -e "$t" && echo "$t"'
  # Host directories bound into the guest are an exposure: they must be read-only so a guest process cannot change the
  # phone's system partitions (see docs/RUNTIME_KNOWN_ISSUES.md). /vendor and /product are optional on some devices.
  for m in /system /apex /vendor /product; do
    check "ro $m"           "grep ' $m ' /proc/mounts | head -1 | awk '{split(\$4,o,\",\"); for(i in o) if(o[i]==\"ro\") ok=1; print \$4; exit !ok}'"
  done
fi

echo "== Jaringan"
check "DNS"               'getent hosts archive.ubuntu.com'
check "HTTPS"             'curl -fsS -o /dev/null -m 15 -w "%{http_code}" https://registry.npmjs.org/'
check "apt index"         'apt-get -o Acquire::Retries=0 update -qq >/dev/null && echo ok'

echo "== Core"
for c in bash git curl jq rg zstd; do check "$c" "$c --version"; done
check "node"              'node --version'
check "npm"               'npm --version'

echo "== Agen"
check "claude"            'claude --version'
check "dsh"               'dsh --version'
check "agy"               '/root/.local/bin/agy --version'
# GitHub CLI is optional (installed when the person connects GitHub), so a missing gh is SKIP, not ERR.
check_optional "gh"       '/root/.local/bin/gh --version'

echo "== Python"
check "python3"           'python3 --version'
check "pip3"              'pip3 --version'
check "venv"              'd=$(mktemp -d) && python3 -m venv "$d/v" && "$d/v/bin/python" -c "print(\"venv ok\")"; rc=$?; rm -rf "$d"; exit $rc'
check "stdlib ssl/sqlite" 'python3 -c "import ssl,sqlite3,ctypes; print(ssl.OPENSSL_VERSION)"'

echo "== Android"
# Versions and paths: keep in sync with app/src/main/java/com/nextcraft/forge/runtime/AndroidToolchain.kt.
JDK_MAJOR=21
GRADLE_VERSION=9.8.1
ANDROID_PLATFORM=android-37.2
BUILD_TOOLS=/root/android-sdk/build-tools/36.0.0
check "java $JDK_MAJOR"   "java -version 2>&1 | head -1 | grep -E '\"$JDK_MAJOR\\.'"
check "javac"             'javac -version'
check "gradle $GRADLE_VERSION" "gradle --version --offline | grep '^Gradle $GRADLE_VERSION'"
check "android.jar"       "ls /root/android-sdk/platforms/$ANDROID_PLATFORM/android.jar"
check "aapt2"             "$BUILD_TOOLS/aapt2 version"
# zipalign without arguments prints usage and exits non-zero; a missing linker gives "cannot execute" instead.
check "zipalign"          "out=\$($BUILD_TOOLS/zipalign 2>&1); echo \"\$out\" | head -1; echo \"\$out\" | grep -qi usage"
check "d8"                "$BUILD_TOOLS/d8 --version"
check "gradle init"       'grep -q "android.aapt2FromMavenOverride" /root/.gradle/init.d/nextcraft-android.gradle && echo ok'

echo "== C/C++"
for c in gcc g++ make cmake gdb; do check "$c" "$c --version"; done
check "compile C"         'd=$(mktemp -d) && printf "int main(){return 0;}" > "$d/a.c" && gcc "$d/a.c" -o "$d/a" && "$d/a" && echo ok; rc=$?; rm -rf "$d"; exit $rc'

echo "== PHP"
check "php"               'php --version'
check "composer"          'composer --version 2>/dev/null | grep -i "composer version"'

echo
echo "Selesai: $pass OK, $fail ERR"
