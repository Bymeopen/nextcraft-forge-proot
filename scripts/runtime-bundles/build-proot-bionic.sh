#!/usr/bin/env bash
set -euo pipefail

# Build the PRoot binaries of the NextCraft Forge non-root (PRoot) engine against Android's own libc (bionic):
#   app/src/main/jniLibs/arm64-v8a/libproot.so         the proot executable (loader bundled inside)
#   app/src/main/jniLibs/arm64-v8a/libproot_loader.so  the small static loader proot runs every guest execve through
#
# Why bionic: a proot that is statically linked against glibc (build-proot-static.sh) or musl dies with SIGSYS
# (exit 159, signal 31) on Android 16 phones without root. The build of this script is dynamically linked
# (/system/bin/linker64, needs only libc.so and libdl.so) and runs there.
#
# Sources (checked against the pins below):
#   proot  Termux's fork, tag v5.1.107.98   https://github.com/termux/proot   (has the kompat, memfd and SysV IPC work)
#   talloc 2.4.3                            https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz
# Toolchain: the Android NDK (clang, target aarch64-linux-android31). Any NDK r27 or newer works; the version used is
# printed and recorded in the output next to the binaries. No ARM64 host is needed (cross-compiled); the result cannot be
# run on a glibc host, so the checks at the end are static (file type, needed libraries, version string).
#
#   ANDROID_NDK_HOME=/path/to/ndk ./build-proot-bionic.sh
#
# Environment: ANDROID_NDK_HOME (or ANDROID_NDK_ROOT / ANDROID_NDK_LATEST_HOME), NEXTCRAFT_ANDROID_API (default 31, the
# app's minSdk), NEXTCRAFT_OUT_DIR (default dist/runtime-bundles), NEXTCRAFT_WORK_DIR, NEXTCRAFT_KEEP_WORK.
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${ANDROID_NDK_LATEST_HOME:-}}}"
API="${NEXTCRAFT_ANDROID_API:-31}"
PROOT_TAG="v5.1.107.98"
PROOT_COMMIT="${NEXTCRAFT_PROOT_COMMIT:-e2d843ee6dab076ea0ed1ffd5d0ea12232185499}"   # commit behind the tag
TALLOC_VERSION="2.4.3"
TALLOC_URL="https://www.samba.org/ftp/talloc/talloc-${TALLOC_VERSION}.tar.gz"
TALLOC_SHA256="dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="${NEXTCRAFT_OUT_DIR:-$ROOT_DIR/dist/runtime-bundles}"
KEEP_WORK="${NEXTCRAFT_KEEP_WORK:-0}"

[[ -n "$NDK" && -d "$NDK" ]] || { echo "Set ANDROID_NDK_HOME to an Android NDK (r27 or newer)" >&2; exit 1; }
TOOLS="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
[[ -x "$TOOLS/aarch64-linux-android${API}-clang" ]] || { echo "No aarch64-linux-android${API}-clang in $TOOLS" >&2; exit 1; }
for tool in git curl tar sha256sum python3 make; do
  command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 1; }
done
NDK_VERSION="$(sed -n 's/^Pkg.Revision *= *//p' "$NDK/source.properties" 2>/dev/null | head -1)"
echo "NDK ${NDK_VERSION:-unknown} at $NDK, API $API"

work="$(mktemp -d ${NEXTCRAFT_WORK_DIR:+-p "$NEXTCRAFT_WORK_DIR"})"
cleanup() {
  if [[ "$KEEP_WORK" == "1" ]]; then echo "Work directory kept: $work" >&2; else rm -rf -- "$work"; fi
}
trap cleanup EXIT

export CC="$TOOLS/aarch64-linux-android${API}-clang"
export AR="$TOOLS/llvm-ar" RANLIB="$TOOLS/llvm-ranlib" STRIP="$TOOLS/llvm-strip"
export OBJCOPY="$TOOLS/llvm-objcopy" OBJDUMP="$TOOLS/llvm-objdump" READELF="$TOOLS/llvm-readelf"
export SOURCE_DATE_EPOCH=1757116800   # fixed timestamps where the toolchain embeds one

echo "Fetching sources"
curl -fsSL --retry 3 -o "$work/talloc.tar.gz" "$TALLOC_URL"
echo "$TALLOC_SHA256  $work/talloc.tar.gz" | sha256sum -c --status
git clone -q --depth 1 --branch "$PROOT_TAG" https://github.com/termux/proot "$work/proot"
actual_commit="$(git -C "$work/proot" rev-parse HEAD)"
[[ "$actual_commit" == "$PROOT_COMMIT" ]] || { echo "proot $PROOT_TAG resolved to $actual_commit, expected $PROOT_COMMIT" >&2; exit 1; }
echo "proot $PROOT_TAG = $actual_commit"

echo "Building talloc (static, cross-compiled)"
tar -xzf "$work/talloc.tar.gz" -C "$work"
talloc_src="$work/talloc-$TALLOC_VERSION"
# talloc configures with waf, which runs test programs; when cross-compiling it needs the answers instead.
cat > "$work/cross-answers.txt" <<'EOF'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for -D_LARGE_FILES: OK
Checking correct behavior of strtoll: OK
Checking for working strptime: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking for HAVE_SECURE_MKSTEMP: OK
Checking whether the realpath function allows a NULL argument: OK
EOF
(
  cd "$talloc_src"
  # waf appends every check it has no answer for to the answers file and stops; print the file then, so the missing
  # questions are visible in the log instead of just "answers file is incomplete".
  if ! CFLAGS="-O2 -fPIC" ./configure --prefix="$work/talloc-prefix" --cross-compile --cross-answers="$work/cross-answers.txt" \
      --disable-python --disable-rpath >"$work/talloc-configure.log" 2>&1; then
    tail -n 15 "$work/talloc-configure.log" >&2
    echo "--- answers file after configure (unanswered checks were appended):" >&2
    cat "$work/cross-answers.txt" >&2
    exit 1
  fi
  # The shared library may fail to link under the NDK; only its object file is needed for the static archive.
  make -j"$(nproc)" >/dev/null 2>&1 || true
  ls bin/default/talloc.c.*.o >/dev/null
  mkdir -p "$work/talloc-prefix/lib"
  "$AR" rcs "$work/talloc-prefix/lib/libtalloc.a" bin/default/talloc.c.*.o
)

echo "Building proot (bionic)"
# CFLAGS/LDFLAGS/CPPFLAGS go through the environment: the GNUmakefile appends to them with `+=` (and adds -ltalloc to
# LDFLAGS), which a command-line override would replace. --build-id=none and the prefix map keep builds repeatable.
(
  cd "$work/proot/src"
  CPPFLAGS="-I$talloc_src" CFLAGS="-O2 -ffile-prefix-map=$work=." \
    LDFLAGS="-L$work/talloc-prefix/lib -Wl,--build-id=none" \
    make -j"$(nproc)" V=1 proot loader/loader >/dev/null
  "$STRIP" --strip-all proot loader/loader
)

mkdir -p "$OUT_DIR"
cp "$work/proot/src/proot" "$OUT_DIR/libproot.so"
cp "$work/proot/src/loader/loader" "$OUT_DIR/libproot_loader.so"
chmod 755 "$OUT_DIR/libproot.so" "$OUT_DIR/libproot_loader.so"

echo "Checking the result"
main_desc="$(file -b "$OUT_DIR/libproot.so")"
loader_desc="$(file -b "$OUT_DIR/libproot_loader.so")"
echo "libproot.so: $main_desc"
echo "libproot_loader.so: $loader_desc"
[[ "$main_desc" == *"ARM aarch64"* && "$main_desc" == *"/system/bin/linker64"* ]] || { echo "libproot.so is not an Android-dynamic aarch64 binary" >&2; exit 1; }
[[ "$loader_desc" == *"ARM aarch64"* && "$loader_desc" == *"statically linked"* ]] || { echo "loader is not a static aarch64 binary" >&2; exit 1; }
needed="$("$READELF" -d "$OUT_DIR/libproot.so" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p' | sort | tr '\n' ' ')"
echo "needed libraries: $needed"
# Only what every Android phone has; anything else (libtalloc.so, libtermux-exec.so, ...) would not load from the APK.
for lib in $needed; do
  case "$lib" in libc.so|libdl.so|libm.so) ;; *) echo "unexpected needed library: $lib" >&2; exit 1 ;; esac
done
strings -a "$OUT_DIR/libproot.so" | grep -q "^$PROOT_TAG" || { echo "version string $PROOT_TAG not found" >&2; exit 1; }

{
  echo "proot $PROOT_TAG ($actual_commit) from github.com/termux/proot"
  echo "talloc $TALLOC_VERSION (sha256 $TALLOC_SHA256)"
  echo "NDK ${NDK_VERSION:-unknown}, android$API"
} > "$OUT_DIR/proot-bionic-build.txt"
sha256sum "$OUT_DIR/libproot.so" "$OUT_DIR/libproot_loader.so"
wc -c "$OUT_DIR/libproot.so" "$OUT_DIR/libproot_loader.so"
echo "built: $OUT_DIR/libproot.so + libproot_loader.so"
