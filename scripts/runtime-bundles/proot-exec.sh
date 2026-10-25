#!/system/bin/sh
            # PRoot supervisor (ADR-008): same arguments and guest layout as the chroot app's chroot-exec.sh, but the rootfs is
            # entered with the proot shipped in the APK (NEXTCRAFT_PROOT, termux/proot built with the NDK), no kernel mounts. Always run as
            # `/system/bin/sh proot-exec.sh …`: the app UID may not exec files from its data directory.
            set -e
            # The env file sourced before this script replaces PATH with the guest PATH; the host-side commands below
            # (mkdir, …) live in /system/bin. The guest PATH is set again further down, before proot starts.
            export PATH="/system/bin:/system/xbin${PATH:+:$PATH}"

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

            case "$APP_UID" in
                ''|*[!0-9]*)
                    echo "Invalid app uid: $APP_UID" >&2
                    exit 65
                    ;;
            esac

            PROOT="${NEXTCRAFT_PROOT:-}"
            if [ -z "$PROOT" ] || [ ! -x "$PROOT" ]; then
                echo "PRoot binary not available: ${PROOT:-NEXTCRAFT_PROOT unset}" >&2
                exit 127
            fi
            # proot keeps its own state (translated /proc entries, temporary files) here; it must be app-writable.
            export PROOT_TMP_DIR="${NEXTCRAFT_PROOT_TMP:-$ROOTFS/tmp/.proot-tmp}"
            export PROOT_L2S_DIR="${PROOT_L2S_DIR:-$ROOTFS/tmp/.proot-l2s}"
            # Enable seccomp-BPF syscall filter on Linux 4.19+ unless explicitly disabled; legacy <4.19 kernels fallback to ptrace-only.
            if [ -z "${PROOT_NO_SECCOMP:-}" ]; then
                KVER=$(uname -r 2>/dev/null || echo "5.10.0")
                KMAJ=${KVER%%.*}
                KREST=${KVER#*.}
                KMIN=${KREST%%.*}
                if [ "${KMAJ:-5}" -lt 4 ] || { [ "${KMAJ:-5}" -eq 4 ] && [ "${KMIN:-19}" -lt 19 ]; }; then
                    export PROOT_NO_SECCOMP=1
                fi
            fi
            # Every guest execve goes through proot's ELF loader. By default proot extracts it into PROOT_TMP_DIR, which Android
            # (SELinux, targetSdk >= 29) refuses to exec from app data, so nothing in the guest would start. The loader ships in
            # the APK next to proot itself (libproot_loader.so) and is named here.
            if [ -n "${NEXTCRAFT_PROOT_LOADER:-}" ]; then
                export PROOT_LOADER="$NEXTCRAFT_PROOT_LOADER"
            fi
            HOST_RESP="$HOST_BRIDGE-responses"
            SHM_DIR="$ROOTFS/tmp/.nextcraft-shm"

            mkdir -p "$ROOTFS$GUEST_WORKSPACE" "$HOST_WORKSPACE" "$HOST_BRIDGE" "$HOST_RESP" "$PROOT_TMP_DIR" "$PROOT_L2S_DIR" "$SHM_DIR" \
                "$ROOTFS/nextcraft-bridge" "$ROOTFS/pocketdev-bridge" "$ROOTFS/pocket-bridge" "$ROOTFS/pocketdev-bridge-responses" "$ROOTFS/nextcraft-bridge-responses" \
                "$ROOTFS/tmp" "$ROOTFS/dev" "$ROOTFS/proc" "$ROOTFS/sys"

            if [ ! -s "$ROOTFS/etc/resolv.conf" ]; then
                mkdir -p "$ROOTFS/etc"
                printf "nameserver 8.8.8.8\nnameserver 1.1.1.1\n" > "$ROOTFS/etc/resolv.conf"
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
            # Android's app seccomp policy kills a process on io_uring_setup (SIGSYS, exit 159). libuv (Node 20+) opens
            # an io_uring by default, which took down every npm/node run; this makes it use epoll instead.
            export UV_USE_IO_URING=0
            export UV_THREADPOOL_SIZE="${UV_THREADPOOL_SIZE:-6}"
            # Go binaries (Antigravity's agy, dsh) preempt goroutines with SIGURG many times a second. Under ptrace every
            # signal is a tracer round trip and can interrupt an in-flight connect/read, which surfaces as random
            # "dial tcp"/"i/o timeout" failures that never happen in a kernel chroot. Cooperative preemption only.
            export GODEBUG="${GODEBUG:-asyncpreemptoff=1}"
            export NEXTCRAFT_BRIDGE_DIR="/nextcraft-bridge"
            export POCKETDEV_BRIDGE_DIR="/nextcraft-bridge"
            export NEXTCRAFT_RESPONSE_DIR="/nextcraft-bridge-responses"
            export PATH="${PATH:-/opt/nextcraft/bin:/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin}"
            case ":$PATH:" in
                *:/root/.local/bin:*) ;;
                *) export PATH="/opt/nextcraft/bin:/root/.local/bin:$PATH" ;;
            esac

            BASE_PATH="/opt/nextcraft/bin:/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            if [ -d "$ROOTFS/root/android-sdk" ]; then
                # Keep in sync with AndroidToolchain.kt.
                export ANDROID_HOME="${ANDROID_HOME:-/root/android-sdk}"
                export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/root/android-sdk}"
                export GRADLE_HOME="${GRADLE_HOME:-/opt/gradle/gradle-9.8.1}"
                export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/root/.gradle}"
                export ORG_GRADLE_PROJECT_android_aapt2FromMavenOverride="/root/android-sdk/build-tools/36.0.0/aapt2"
                export PATH="/opt/gradle/gradle-9.8.1/bin:/root/android-sdk/build-tools/36.0.0:/root/android-sdk/cmdline-tools/latest/bin:$BASE_PATH"
            else
                export PATH="$BASE_PATH"
            fi

            # Bindings follow `proot-distro login` (Termux), which is the proven way to run an Ubuntu rootfs on a non-root
            # phone, plus the NextCraft guest paths of chroot-exec.sh. Later bindings take precedence.
            BINDS="-b /dev -b /dev/pts -b /proc -b /sys -b $SHM_DIR:/dev/shm -b /dev/urandom:/dev/random"
            [ -e /dev/fd ] || BINDS="$BINDS -b /proc/self/fd:/dev/fd"
            [ -e /dev/stdin ] || BINDS="$BINDS -b /proc/self/fd/0:/dev/stdin"
            [ -e /dev/stdout ] || BINDS="$BINDS -b /proc/self/fd/1:/dev/stdout"
            [ -e /dev/stderr ] || BINDS="$BINDS -b /proc/self/fd/2:/dev/stderr"
            # Android hides several /proc and /sys entries from apps; programs that read them (nproc, Java, Node, apt)
            # get a plausible static copy instead of EACCES. Only unreadable entries are replaced.
            SYSDATA="$ROOTFS/.nextcraft-sysdata"
            mkdir -p "$SYSDATA/sys_empty"
            fake() {
                # $1 host path, $2 file name under SYSDATA, $3 content
                if [ ! -r "$1" ]; then
                    [ -s "$SYSDATA/$2" ] || printf '%s\n' "$3" > "$SYSDATA/$2"
                    BINDS="$BINDS -b $SYSDATA/$2:$1"
                fi
            }
            fake /proc/loadavg loadavg "0.12 0.07 0.02 2/165 765"
            fake /proc/stat stat "cpu  1000 0 1000 100000 0 0 0 0 0 0
            cpu0 1000 0 1000 100000 0 0 0 0 0 0
            intr 0
            ctxt 0
            btime 1700000000
            processes 1000
            procs_running 1
            procs_blocked 0"
            fake /proc/uptime uptime "1000.00 1000.00"
            fake /proc/version version "Linux version 6.6.0-android (proot@nextcraft) (gcc (GCC) 13.3.0, GNU ld (GNU Binutils) 2.42) #1 SMP PREEMPT"
            fake /proc/vmstat vmstat "nr_free_pages 100000
            nr_zone_inactive_anon 0
            nr_zone_active_anon 0
            pgpgin 0
            pgpgout 0
            pswpin 0
            pswpout 0"
            fake /proc/sys/kernel/cap_last_cap cap_last_cap "40"
            fake /proc/sys/fs/inotify/max_user_watches max_user_watches "4096"
            fake /proc/sys/kernel/overflowuid overflowuid "65534"
            fake /proc/sys/kernel/overflowgid overflowgid "65534"
            [ -r /sys/fs/selinux ] && [ ! -r /sys/fs/selinux/enforce ] && BINDS="$BINDS -b $SYSDATA/sys_empty:/sys/fs/selinux"
            for bionic_dir in /system /apex /vendor /product; do
                [ -d "$bionic_dir" ] && BINDS="$BINDS -b $bionic_dir"
            done
            # Shared storage as proot-distro binds it; readable only when Android granted the app storage access.
            for storage_dir in /storage /sdcard /mnt/sdcard; do
                [ -d "$storage_dir" ] && [ -r "$storage_dir" ] && BINDS="$BINDS -b $storage_dir"
            done
            # /sdcard is a symlink chain the app may not traverse; the real directory is readable with All files access.
            if [ ! -r /sdcard ] && [ -d /storage/emulated/0 ] && [ -r /storage/emulated/0 ]; then
                BINDS="$BINDS -b /storage/emulated/0:/sdcard"
            fi
            # The bind list is word-split on purpose (one -b per path); a path with whitespace would be read as the command.
case "$ROOTFS$HOST_WORKSPACE$GUEST_WORKSPACE$HOST_BRIDGE" in
    *[[:space:]]*) echo "proot-exec: paths must not contain whitespace" >&2; exit 65 ;;
esac
if [ "$HOST_WORKSPACE" != "$ROOTFS$GUEST_WORKSPACE" ]; then
                BINDS="$BINDS -b $HOST_WORKSPACE:$GUEST_WORKSPACE"
            fi
            BINDS="$BINDS -b $HOST_BRIDGE:/nextcraft-bridge -b $HOST_BRIDGE:/pocketdev-bridge -b $HOST_BRIDGE:/pocket-bridge -b $HOST_RESP:/pocketdev-bridge-responses -b $HOST_RESP:/nextcraft-bridge-responses"

            KERNEL_OPT=""
            if [ -n "${NEXTCRAFT_KERNEL_RELEASE:-}" ]; then
                KERNEL_OPT="-k $NEXTCRAFT_KERNEL_RELEASE"
            fi

            # Same extension set as proot-distro: --kill-on-exit ends every guest process with proot (a cancelled build
            # never lingers), --link2symlink emulates hard links, --sysvipc emulates SysV IPC that Android forbids,
            # -L fixes lstat sizes for dpkg. -0 makes the guest see uid 0 (apt, npm -g).
            # shellcheck disable=SC2086
            exec "$PROOT" --kill-on-exit --link2symlink --sysvipc -L -0 $KERNEL_OPT -r "$ROOTFS" \
                $BINDS \
                -w "$GUEST_WORKSPACE" \
                /usr/bin/env GUEST_WORKSPACE="$GUEST_WORKSPACE" /bin/sh -c 'cd "$GUEST_WORKSPACE" && exec "$@"' -- "$@"
