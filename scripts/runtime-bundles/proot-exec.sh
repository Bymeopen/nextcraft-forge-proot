#!/system/bin/sh
# Non-root counterpart of chroot-exec.sh (ADR-007): same arguments, same guest layout, but the rootfs is entered with the
# static proot shipped in the APK (NEXTCRAFT_PROOT) instead of kernel mounts and chroot. Always run as
# `/system/bin/sh proot-exec.sh …`: the app UID may not exec files from its data directory.
set -e

if [ "$#" -lt 6 ]; then
    echo "Usage: proot-exec.sh <rootfs> <host_workspace> <guest_workspace> <host_bridge> <app_uid> <cmd...>" >&2
    exit 2
fi

ROOTFS="$1"
HOST_WORKSPACE="$2"
GUEST_WORKSPACE="$3"
HOST_BRIDGE="$4"
APP_UID="$5"
shift 5

if [ -z "$ROOTFS" ] || [ -z "$HOST_WORKSPACE" ] || [ -z "$GUEST_WORKSPACE" ] || [ -z "$HOST_BRIDGE" ] || [ -z "$APP_UID" ]; then
    echo "Usage: proot-exec.sh <rootfs> <host_workspace> <guest_workspace> <host_bridge> <app_uid> <cmd...>" >&2
    exit 2
fi

case "$GUEST_WORKSPACE" in
    *..*|/workspace/.|/workspace/*/*|*[!a-zA-Z0-9._/-]*)
        echo "Invalid guest workspace path: $GUEST_WORKSPACE" >&2
        exit 65
        ;;
    /root|/workspace|/workspace/[a-zA-Z0-9._-]*) ;;
    *)
        echo "Invalid guest workspace path: $GUEST_WORKSPACE" >&2
        exit 65
        ;;
esac

PROOT="${NEXTCRAFT_PROOT:-}"
if [ -z "$PROOT" ] || [ ! -x "$PROOT" ]; then
    echo "PRoot binary not available: ${PROOT:-NEXTCRAFT_PROOT unset}" >&2
    exit 127
fi
# proot keeps its own state (translated /proc entries, temporary files) here; it must be app-writable.
export PROOT_TMP_DIR="${NEXTCRAFT_PROOT_TMP:-$ROOTFS/tmp}"
# Every guest execve goes through proot's ELF loader. By default proot extracts it into PROOT_TMP_DIR, which Android
# (SELinux, targetSdk >= 29) refuses to exec from app data, so nothing in the guest would start. The loader ships in
# the APK next to proot itself (libproot_loader.so) and is named here.
if [ -n "${NEXTCRAFT_PROOT_LOADER:-}" ]; then
    export PROOT_LOADER="$NEXTCRAFT_PROOT_LOADER"
fi
HOST_RESP="$HOST_BRIDGE-responses"
SHM_DIR="$ROOTFS/tmp/.nextcraft-shm"

mkdir -p "$ROOTFS$GUEST_WORKSPACE" "$HOST_WORKSPACE" "$HOST_BRIDGE" "$HOST_RESP" "$PROOT_TMP_DIR" "$SHM_DIR" \
    "$ROOTFS/nextcraft-bridge" "$ROOTFS/pocketdev-bridge" "$ROOTFS/pocket-bridge" \
    "$ROOTFS/nextcraft-bridge-responses" "$ROOTFS/pocketdev-bridge-responses" \
    "$ROOTFS/tmp" "$ROOTFS/dev" "$ROOTFS/proc" "$ROOTFS/sys"

if [ ! -s "$ROOTFS/etc/resolv.conf" ]; then
    mkdir -p "$ROOTFS/etc"
    printf "nameserver 8.8.8.8\nnameserver 1.1.1.1\noptions use-vc timeout:2 attempts:2 rotate\n" > "$ROOTFS/etc/resolv.conf"
fi

unset ANDROID_ROOT ANDROID_DATA ANDROID_ART_ROOT ANDROID_I18N_ROOT ANDROID_TZDATA_ROOT
unset ANDROID_ASSETS ANDROID_BOOTLOGO ANDROID_RUNTIME_ROOT ANDROID_STORAGE
unset ASEC_MOUNTPOINT BOOTCLASSPATH DEX2OATBOOTCLASSPATH EXTERNAL_STORAGE SYSTEMSERVERCLASSPATH
unset LD_PRELOAD LD_LIBRARY_PATH

export HOME="/root"
export USER="root"
export LOGNAME="root"
export TERM="${TERM:-xterm-256color}"
export LANG="${LANG:-C.UTF-8}"
export TMPDIR="/tmp"
export GLIBC_TUNABLES="glibc.pthread.rseq=0"
export NEXTCRAFT_BRIDGE_DIR="/nextcraft-bridge"
export POCKETDEV_BRIDGE_DIR="/nextcraft-bridge"
export NEXTCRAFT_RESPONSE_DIR="/nextcraft-bridge-responses"

BASE_PATH="/opt/nextcraft/bin:/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
if [ -d "$ROOTFS/root/android-sdk" ]; then
    # Keep in sync with AndroidToolchain.kt and chroot-exec.sh.
    export ANDROID_HOME="${ANDROID_HOME:-/root/android-sdk}"
    export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/root/android-sdk}"
    export GRADLE_HOME="${GRADLE_HOME:-/opt/gradle/gradle-9.8.1}"
    export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/root/.gradle}"
    export ORG_GRADLE_PROJECT_android_aapt2FromMavenOverride="/root/android-sdk/build-tools/36.0.0/aapt2"
    export PATH="/opt/gradle/gradle-9.8.1/bin:/root/android-sdk/build-tools/36.0.0:/root/android-sdk/cmdline-tools/latest/bin:$BASE_PATH"
else
    export PATH="$BASE_PATH"
fi

# Bindings mirror the chroot mounts (same guest paths as chroot-exec.sh). Later bindings take precedence, so
# /dev/shm lands on an app-writable directory. The Bionic directories are bound only where they exist.
BIONIC_BINDS=""
for bionic_dir in /system /apex /vendor /product; do
    [ -d "$bionic_dir" ] && BIONIC_BINDS="$BIONIC_BINDS -b $bionic_dir"
done
WORKSPACE_BIND=""
if [ "$HOST_WORKSPACE" != "$ROOTFS$GUEST_WORKSPACE" ]; then
    WORKSPACE_BIND="-b $HOST_WORKSPACE:$GUEST_WORKSPACE"
fi

# --kill-on-exit ends every guest process with proot, so a cancelled build never lingers.
# shellcheck disable=SC2086
exec "$PROOT" --kill-on-exit --link2symlink -0 -r "$ROOTFS" \
    -b /dev -b /dev/pts -b /proc -b /sys \
    -b "$SHM_DIR:/dev/shm" \
    $BIONIC_BINDS \
    $WORKSPACE_BIND \
    -b "$HOST_BRIDGE:/nextcraft-bridge" -b "$HOST_BRIDGE:/pocketdev-bridge" -b "$HOST_BRIDGE:/pocket-bridge" \
    -b "$HOST_RESP:/nextcraft-bridge-responses" -b "$HOST_RESP:/pocketdev-bridge-responses" \
    -w "$GUEST_WORKSPACE" \
    /usr/bin/env GUEST_WORKSPACE="$GUEST_WORKSPACE" /bin/sh -c 'cd "$GUEST_WORKSPACE" && exec "$@"' -- "$@"
