#!/system/bin/sh
set -e

if [ "$#" -lt 6 ]; then
    echo "Usage: chroot-exec.sh <rootfs> <host_workspace> <guest_workspace> <host_bridge> <app_uid> <cmd...>" >&2
    exit 2
fi

ROOTFS="$1"
HOST_WORKSPACE="$2"
GUEST_WORKSPACE="$3"
HOST_BRIDGE="$4"
APP_UID="$5"
shift 5

if [ -z "$ROOTFS" ] || [ -z "$HOST_WORKSPACE" ] || [ -z "$GUEST_WORKSPACE" ] || [ -z "$HOST_BRIDGE" ] || [ -z "$APP_UID" ]; then
    echo "Usage: chroot-exec.sh <rootfs> <host_workspace> <guest_workspace> <host_bridge> <app_uid> <cmd...>" >&2
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

case "$APP_UID" in
    ''|*[!0-9]*)
        echo "Invalid app UID: $APP_UID" >&2
        exit 65
        ;;
esac

# Opt-in (create the file $ROOTFS/.nextcraft-private-mounts): run this session in its own mount namespace, so its
# bind mounts disappear with its last process instead of piling up in the shared namespace. Skipped when unshare is
# missing or already applied.
if [ -z "$NEXTCRAFT_IN_MOUNT_NS" ] && [ -f "$ROOTFS/.nextcraft-private-mounts" ] && command -v unshare >/dev/null 2>&1; then
    NEXTCRAFT_IN_MOUNT_NS=1 exec unshare -m "$0" "$ROOTFS" "$HOST_WORKSPACE" "$GUEST_WORKSPACE" "$HOST_BRIDGE" "$APP_UID" "$@"
fi

# Pure-sh readers of /proc/mounts: stock Android toolbox/toybox does not ship awk on every release, and the
# mount logic must not depend on a Magisk/KernelSU busybox being on PATH.
mounts_at_or_under() {
    while read -r _ mp _; do
        case "$mp" in "$1"|"$1"/*) printf '%s\n' "$mp" ;; esac
    done < /proc/mounts
}
mounts_below() {
    while read -r _ mp _; do
        case "$mp" in "$1"/*) printf '%s\n' "$mp" ;; esac
    done < /proc/mounts
}
is_mounted() {
    while read -r _ mp _; do
        [ "$mp" = "$1" ] && return 0
    done < /proc/mounts
    return 1
}
has_submounts() {
    while read -r _ mp _; do
        case "$mp" in "$1"/*) return 0 ;; esac
    done < /proc/mounts
    return 1
}

mount_once() {
    local src="$1"
    local dst="$2"
    local fstype="$3"
    local opts="$4"
    mkdir -p "$dst"
    if ! is_mounted "$dst"; then
        if [ "$fstype" = "bind" ]; then
            mount -o bind "$src" "$dst"
            if [ "$opts" = "ro" ]; then
                mount -o remount,bind,ro "$dst" 2>/dev/null || true
            fi
        else
            mount -t "$fstype" -o "$opts" "$src" "$dst"
        fi
    fi
}

# Android's /apex is a tmpfs with every APEX module mounted below it, and /system/bin/linker64 (the interpreter
# of Bionic tools such as aapt2) points into /apex/com.android.runtime. A plain bind leaves those modules out.
# A recursive bind is not used: its copies are peers of the shared Android mounts until they are made slaves,
# and unmounting a copy that is still a peer also unmounts the real module. Instead the directory is bound on
# its own, made a slave while it has no submounts yet, and only then are the modules bound one by one.

# On failure MAKE_SLAVE_ERR holds what each attempt printed, so the warning below can say why.
make_slave() {
    local e1 e2 e3 e4
    e1=$(mount --make-rslave "$1" 2>&1) && return 0
    e2=$(mount -o rslave "$1" 2>&1) && return 0
    e3=$(mount --make-slave "$1" 2>&1) && return 0
    e4=$(mount -o slave "$1" 2>&1) && return 0
    # Android's toybox mount reads "--make-rslave DIR" as "mount DIR" (fstab lookup), so also try a busybox mount.
    local bb eb
    for bb in busybox /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox; do
        command -v "$bb" >/dev/null 2>&1 || continue
        "$bb" mount --make-rslave "$1" >/dev/null 2>&1 && return 0
        eb="$eb | $bb: $("$bb" mount --make-rslave "$1" 2>&1)"
    done
    MAKE_SLAVE_ERR="--make-rslave: $e1 | -o rslave: $e2 | --make-slave: $e3 | -o slave: $e4$eb"
    return 1
}

mount_bionic() {
    local src="$1"
    local dst="$2"
    mkdir -p "$dst"
    # Earlier versions bound these directories without their submounts; replace such a mount.
    if is_mounted "$dst" && has_submounts "$src" && ! has_submounts "$dst"; then
        umount "$dst" 2>/dev/null || umount -l "$dst" 2>/dev/null || true
    fi
    is_mounted "$dst" && return 0
    mount -o bind "$src" "$dst" || return 1
    if ! make_slave "$dst"; then
        echo "Warning: mount propagation cannot be changed; $dst is bound without its submounts (${MAKE_SLAVE_ERR:-no error output})" >&2
        mount -o remount,bind,ro "$dst" 2>/dev/null || true
        return 0
    fi
    # Parents first, so each module lands on a mount point that is already a slave copy.
    mounts_below "$src" | sort | while read -r sub; do
        target="$dst${sub#"$src"}"
        [ -d "$sub" ] && [ -d "$target" ] || continue
        is_mounted "$target" && continue
        if mount -o bind "$sub" "$target" 2>/dev/null && ! make_slave "$target"; then
            umount "$target" 2>/dev/null || true
        fi
    done
    mount -o remount,bind,ro "$dst" 2>/dev/null || true
}

mount_dynamic_bind() {
    local src="$1"
    local dst="$2"
    local state_dir="$ROOTFS/tmp/.nextcraft-mounts"
    local state_key
    state_key=$(printf '%s' "$dst" | tr '/' '_')
    local state_file="$state_dir/$state_key"
    mkdir -p "$src" "$dst" "$state_dir"
    if is_mounted "$dst"; then
        local prev_src=""
        [ -f "$state_file" ] && prev_src=$(cat "$state_file" 2>/dev/null || true)
        # A mount whose source was deleted keeps showing the dead directory (link count 0) even after the same path is
        # created again (a discarded and re-created chat branch, a deleted probe folder), so it must be replaced too.
        if [ "$prev_src" != "$src" ] || [ "$(stat -c %h "$dst" 2>/dev/null)" = 0 ]; then
            umount "$dst" 2>/dev/null || umount -l "$dst" 2>/dev/null || true
        fi
    fi
    if ! is_mounted "$dst"; then
        mount -o bind "$src" "$dst"
        printf '%s' "$src" > "$state_file"
    fi
}

# Two launches at once (an agent plus a diagnostic) would both see a mount point as free and stack the
# mounts; the second /apex then has no modules. Serialize the mount phase; fd 9 is closed before the chroot.
exec 9>"$ROOTFS/.nextcraft-mount.lock"
flock 9 2>/dev/null || busybox flock 9 2>/dev/null || true
# The app is repairing or replacing the rootfs (RootfsRepair): mounting now could pin directories it is removing.
if [ -e "${ROOTFS%/*}/.rootfs-maintenance" ]; then
    echo "The Linux environment is being repaired. Try again when it finishes." >&2
    exit 75
fi

# Scope suid/exec to the rootfs only; never relax mount flags on the whole /data partition.
if ! is_mounted "$ROOTFS"; then
    mount -o bind "$ROOTFS" "$ROOTFS" 2>/dev/null || true
fi
mount -o remount,bind,suid,exec "$ROOTFS" 2>/dev/null || true

mount_once "/dev" "$ROOTFS/dev" "bind" "rw"
mount_once "/dev/pts" "$ROOTFS/dev/pts" "bind" "rw"
mount_once "proc" "$ROOTFS/proc" "proc" "rw,nosuid,nodev,noexec"
mount_once "sysfs" "$ROOTFS/sys" "sysfs" "rw,nosuid,nodev,noexec"
TMP_MB="${NEXTCRAFT_TMP_MB:-512}"
SHM_MB="${NEXTCRAFT_SHM_MB:-256}"
mount_once "tmpfs" "$ROOTFS/tmp" "tmpfs" "rw,nosuid,nodev,mode=1777,size=${TMP_MB}M"
mount_once "tmpfs" "$ROOTFS/dev/shm" "tmpfs" "rw,nosuid,nodev,mode=1777,size=${SHM_MB}M"
mkdir -p "$ROOTFS/tmp/.proot-tmp" "$ROOTFS/tmp/.proot-l2s" 2>/dev/null || true
chmod 1777 "$ROOTFS/tmp/.proot-tmp" "$ROOTFS/tmp/.proot-l2s" 2>/dev/null || true
if [ ! -e "$ROOTFS/dev/fd" ]; then
    ln -sf /proc/self/fd "$ROOTFS/dev/fd" 2>/dev/null || true
fi
[ ! -e "$ROOTFS/dev/stdin" ] && ln -sf /proc/self/fd/0 "$ROOTFS/dev/stdin" 2>/dev/null || true
[ ! -e "$ROOTFS/dev/stdout" ] && ln -sf /proc/self/fd/1 "$ROOTFS/dev/stdout" 2>/dev/null || true
[ ! -e "$ROOTFS/dev/stderr" ] && ln -sf /proc/self/fd/2 "$ROOTFS/dev/stderr" 2>/dev/null || true

for bionic_dir in /system /apex /vendor /product; do
    if [ -d "$bionic_dir" ]; then
        mount_bionic "$bionic_dir" "$ROOTFS$bionic_dir"
    fi
done

# Opt-in view of the Android root under /android. Off unless the app-side file runtime/android-root holds "ro" or
# "rw"; it sits outside the rootfs, so the guest cannot switch it on or upgrade ro to rw itself.
ANDROID_ROOT_MODE=$(cat "$(dirname "$ROOTFS")/android-root" 2>/dev/null | tr -d ' \n\r')
mount_android_view() {
    local src="$1"
    local dst="$ROOTFS/android$1"
    [ -d "$src" ] || return 0
    mkdir -p "$dst"
    is_mounted "$dst" && return 0
    mount -o bind "$src" "$dst" 2>/dev/null || return 0
    # Same propagation rule as mount_bionic: a copy that stays a peer would let umount hit the real mount.
    if ! make_slave "$dst"; then
        umount "$dst" 2>/dev/null || true
        echo "Warning: skipped /android$src: mount propagation cannot be changed" >&2
        return 0
    fi
    mounts_below "$src" | sort | while read -r sub; do
        target="$dst${sub#"$src"}"
        [ -d "$sub" ] && [ -d "$target" ] || continue
        is_mounted "$target" && continue
        if mount -o bind "$sub" "$target" 2>/dev/null && ! make_slave "$target"; then
            umount "$target" 2>/dev/null || true
        fi
    done
    if [ "$ANDROID_ROOT_MODE" = "ro" ]; then
        # Read-only must cover every submount too, otherwise /android/data/media etc. stay writable.
        mounts_at_or_under "$dst" | sort | while read -r m; do
            mount -o remount,bind,ro "$m" 2>/dev/null || echo "Warning: $m is still writable" >&2
        done
    fi
}
case "$ANDROID_ROOT_MODE" in
    ro|rw)
        for android_dir in /data /storage /mnt /metadata /cache /odm /system_ext /vendor /product /system /apex; do
            mount_android_view "$android_dir"
        done
        ;;
esac

mkdir -p "$HOST_WORKSPACE" "$HOST_BRIDGE"
if [ "$HOST_WORKSPACE" != "$ROOTFS$GUEST_WORKSPACE" ]; then
    mount_dynamic_bind "$HOST_WORKSPACE" "$ROOTFS$GUEST_WORKSPACE"
fi
mount_dynamic_bind "$HOST_BRIDGE" "$ROOTFS/nextcraft-bridge"
mount_dynamic_bind "$HOST_BRIDGE" "$ROOTFS/pocketdev-bridge"
mount_dynamic_bind "$HOST_BRIDGE" "$ROOTFS/pocket-bridge"
HOST_RESP="$HOST_BRIDGE-responses"
mkdir -p "$HOST_RESP"
chown "$APP_UID:$APP_UID" "$HOST_RESP" 2>/dev/null || true
# The pocket* names are kept for scripts and sessions from older builds.
for resp_dir in nextcraft-bridge-responses pocketdev-bridge-responses; do
    mount_dynamic_bind "$HOST_RESP" "$ROOTFS/$resp_dir"
    mount -o remount,bind,ro "$ROOTFS/$resp_dir" 2>/dev/null || true
done

if [ ! -s "$ROOTFS/etc/resolv.conf" ]; then
    mkdir -p "$ROOTFS/etc"
    printf "nameserver 8.8.8.8\nnameserver 1.1.1.1\n" > "$ROOTFS/etc/resolv.conf"
fi

fix_workspace_ownership() {
    case "$HOST_WORKSPACE" in
        "$ROOTFS"|"$ROOTFS"/*)
            ;;
        *)
            if command -v find >/dev/null 2>&1 && command -v xargs >/dev/null 2>&1; then
                find "$HOST_WORKSPACE" \( ! -user "$APP_UID" -o ! -group "$APP_UID" \) -print0 2>/dev/null | xargs -0 -r chown -h "$APP_UID:$APP_UID" 2>/dev/null || chown -R -h "$APP_UID:$APP_UID" "$HOST_WORKSPACE" 2>/dev/null || true
                find "$HOST_WORKSPACE" ! -perm -u+rw,g+rw -print0 2>/dev/null | xargs -0 -r chmod u+rwX,g+rwX 2>/dev/null || chmod -R u+rwX,g+rwX "$HOST_WORKSPACE" 2>/dev/null || true
            else
                chown -R -h "$APP_UID:$APP_UID" "$HOST_WORKSPACE" 2>/dev/null || true
                chmod -R u+rwX,g+rwX "$HOST_WORKSPACE" 2>/dev/null || true
            fi
            ;;
    esac
    chown -R -h "$APP_UID:$APP_UID" "$HOST_BRIDGE" 2>/dev/null || true
    chmod -R u+rwX,g+rwX "$HOST_BRIDGE" 2>/dev/null || true
}
# INT/TERM must end the script (exit fires the EXIT trap once); a plain trap handler would
# resume execution (keep mounting) and run the ownership fix twice.
trap fix_workspace_ownership EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

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
export CLAUDE_CONFIG_DIR="/root/.claude"
export SSL_CERT_FILE="/etc/ssl/certs/ca-certificates.crt"
export CURL_CA_BUNDLE="/etc/ssl/certs/ca-certificates.crt"
export NODE_EXTRA_CA_CERTS="/etc/ssl/certs/ca-certificates.crt"
export REQUESTS_CA_BUNDLE="/etc/ssl/certs/ca-certificates.crt"
export GIT_SSL_CAINFO="/etc/ssl/certs/ca-certificates.crt"
# Auto-size Node.js V8 heap (--max-old-space-size) and glibc arenas based on phone RAM (4/6/8/10/12/16GB+) and built-in SwapRAM/ZRAM
if [ -z "$NODE_OPTIONS" ]; then
    MEM_KB=$(awk '/^MemTotal:/ {print $2}' /proc/meminfo 2>/dev/null || echo 4194304)
    SWAP_KB=$(awk '/^SwapTotal:/ {print $2}' /proc/meminfo 2>/dev/null || echo 0)
    MEM_MB=$((MEM_KB / 1024))
    SWAP_MB=$((SWAP_KB / 1024))
    if [ "$MEM_MB" -ge 13500 ]; then
        NODE_HEAP_MB=4096
    elif [ "$MEM_MB" -ge 10400 ]; then
        NODE_HEAP_MB=3072
    elif [ "$MEM_MB" -ge 8800 ]; then
        NODE_HEAP_MB=2560
    elif [ "$MEM_MB" -ge 6800 ]; then
        NODE_HEAP_MB=2048
    elif [ "$MEM_MB" -ge 4500 ]; then
        if [ "$SWAP_MB" -ge 2048 ]; then NODE_HEAP_MB=1792; else NODE_HEAP_MB=1536; fi
    else
        NODE_HEAP_MB=1024
    fi
    export NODE_OPTIONS="--dns-result-order=ipv4first --max-old-space-size=$NODE_HEAP_MB"
fi
export MALLOC_ARENA_MAX="${MALLOC_ARENA_MAX:-2}"
export PROOT_TMP_DIR="${PROOT_TMP_DIR:-/tmp/.proot-tmp}"
export PROOT_L2S_DIR="${PROOT_L2S_DIR:-/tmp/.proot-l2s}"
export UV_THREADPOOL_SIZE="${UV_THREADPOOL_SIZE:-4}"
export COREPACK_ENABLE_DOWNLOAD_PROMPT="0"
export GLIBC_TUNABLES="glibc.pthread.rseq=0"
export NEXTCRAFT_BRIDGE_DIR="/nextcraft-bridge"
export POCKETDEV_BRIDGE_DIR="/nextcraft-bridge"
export NEXTCRAFT_RESPONSE_DIR="/nextcraft-bridge-responses"
export PATH="${PATH:-/opt/nextcraft/bin:/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin}"
case ":$PATH:" in
    *:/root/.local/bin:*) ;;
    *) export PATH="/opt/nextcraft/bin:/root/.local/bin:$PATH" ;;
esac

CHROOT_BIN=""
PROOT_BIN=""
for candidate in /system/bin/chroot /system/xbin/chroot /sbin/chroot /debug_ramdisk/chroot "$ROOTFS/usr/sbin/chroot"; do
    if [ -x "$candidate" ]; then
        CHROOT_BIN="$candidate"
        break
    fi
done
if [ -z "$CHROOT_BIN" ]; then
    if command -v busybox >/dev/null 2>&1; then
        CHROOT_BIN="busybox chroot"
    else
        for pcand in "$ROOTFS/usr/bin/proot" "$ROOTFS/usr/local/bin/proot" /system/bin/proot /data/data/com.termux/files/usr/bin/proot; do
            if [ -x "$pcand" ]; then
                PROOT_BIN="$pcand"
                break
            fi
        done
        if [ -z "$PROOT_BIN" ]; then
            echo "chroot or proot binary not found on host or guest rootfs" >&2
            exit 127
        fi
    fi
fi

# Run the guest command as a child and forward INT/TERM to it. With a plain foreground command the shell only runs
# its trap after the command exits, so a cancelled npm install or Gradle build kept running until it finished, and
# a later SIGKILL of this script orphaned it and skipped the ownership fix. Stdin (the
# Claude prompt) is passed through fd 3: a non-interactive shell gives background commands /dev/null.
child_status=0
exec 3<&0
STOPPED=""
forward_signal() {
    STOPPED=1
    if [ -n "$SETSID" ]; then
        kill "-$1" -- "-$child" 2>/dev/null || true
    else
        kill "-$1" "$child" 2>/dev/null || true
    fi
}

# Non-interactive runs (agents, builds) get their own session, so every process they spawn shares one process group
# that can be ended with the command. Interactive terminals keep the controlling tty and are left alone.
SETSID=""
if [ ! -t 0 ] && command -v setsid >/dev/null 2>&1; then
    SETSID="setsid"
fi

# Ends whatever a stopped or failed command left behind (a dev server, a watcher, a helper): TERM, a short grace period, KILL.
reap_group() {
    [ -n "$SETSID" ] || return 0
    kill -s TERM -- "-$child" 2>/dev/null || return 0
    local i=0
    while [ "$i" -lt 10 ] && kill -0 -- "-$child" 2>/dev/null; do
        sleep 0.2
        i=$((i + 1))
    done
    kill -s KILL -- "-$child" 2>/dev/null || true
}

exec 9>&-
if [ -n "$CHROOT_BIN" ]; then
    $SETSID $CHROOT_BIN "$ROOTFS" /usr/bin/env \
        GUEST_WORKSPACE="$GUEST_WORKSPACE" \
        /bin/sh -c 'cd "$GUEST_WORKSPACE" && exec "$@"' -- "$@" <&3 3<&- &
else
    $SETSID "$PROOT_BIN" --root-id --link2symlink --sysvipc --kill-on-exit \
        -r "$ROOTFS" \
        -b /dev -b /dev/urandom:/dev/random -b /proc -b /proc/self/fd:/dev/fd -b /sys -b /system -b /apex -b /vendor -b /product \
        -b "$HOST_WORKSPACE:$GUEST_WORKSPACE" \
        -b "$HOST_BRIDGE:/nextcraft-bridge" \
        -b "$HOST_RESP:/nextcraft-bridge-responses" \
        -w "$GUEST_WORKSPACE" \
        /usr/bin/env GUEST_WORKSPACE="$GUEST_WORKSPACE" \
        /bin/sh -c 'cd "$GUEST_WORKSPACE" && exec "$@"' -- "$@" <&3 3<&- &
fi
child=$!
trap 'forward_signal INT' INT
trap 'forward_signal TERM' TERM
# wait returns early when a trapped signal arrives; keep waiting until the child has really exited.
while :; do
    # `|| child_status=...` keeps set -e from aborting when a trapped signal interrupts wait (status 128+N).
    child_status=0
    wait "$child" || child_status=$?
    kill -0 "$child" 2>/dev/null || break
done
# Only after a stop or a failure: a command that finished normally may have left a dev server running on purpose
# (the Preview tab looks for it), so its processes are not touched.
if [ -n "$STOPPED" ] || [ "$child_status" -ne 0 ]; then
    reap_group
fi
exit "$child_status"
