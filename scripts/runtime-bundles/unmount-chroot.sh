#!/system/bin/sh
ROOTFS="$1"
if [ -z "$ROOTFS" ]; then
    echo "Usage: unmount-chroot.sh <rootfs_path>" >&2
    exit 2
fi
ROOTFS_REAL=$(realpath "$ROOTFS" 2>/dev/null || printf '%s' "$ROOTFS")
ROOTFS_CLEAN="${ROOTFS_REAL%/}"
# Refuse "/", relative paths and top-level directories: with such a value the loop below would lazily unmount
# every mount of the device.
case "$ROOTFS_CLEAN" in
    /*/*) ;;
    *)
        echo "Refusing to unmount under '$ROOTFS': rootfs must be an absolute path with at least two components" >&2
        exit 2
        ;;
esac
mounts_at_or_under() {
    while read -r _ mp _; do
        case "$mp" in "$1"|"$1"/*) printf '%s\n' "$mp" ;; esac
    done < /proc/mounts
}
# Make every mount under the rootfs private before unmounting: the Bionic directories are recursive copies of
# shared Android mounts, and unmounting a copy that is still a peer would also unmount the original.
for mp in $(mounts_at_or_under "$ROOTFS_CLEAN" | sort); do
    mount --make-private "$mp" 2>/dev/null || mount -o private "$mp" 2>/dev/null || true
done

for pass in 1 2 3; do
    MOUNTS=$(mounts_at_or_under "$ROOTFS_CLEAN" | sort -r)
    if [ -z "$MOUNTS" ]; then
        break
    fi
    for mnt in $MOUNTS; do
        if [ "$pass" -lt 3 ]; then
            umount "$mnt" 2>/dev/null || true
        else
            umount -l "$mnt" 2>/dev/null || umount -f "$mnt" 2>/dev/null || true
        fi
    done
done
if [ -n "$(mounts_at_or_under "$ROOTFS_CLEAN")" ]; then
    exit 1
fi
rm -rf "$ROOTFS_CLEAN/tmp/.nextcraft-mounts" 2>/dev/null || true
exit 0
