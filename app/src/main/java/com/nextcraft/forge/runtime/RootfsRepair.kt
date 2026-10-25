package com.nextcraft.forge.runtime

import java.io.File

/**
 * Failure-safe activation of a rebuilt Ubuntu 24.04 rootfs while retaining the user's `/root` home directory.
 *
 * PRoot never mounts anything (bindings are ptrace path translations) and every guest process runs as the app
 * UID, so there is nothing to unmount and nothing root-owned to reclaim: all operations are plain file moves and
 * deletes done by the app itself.
 */
internal object RootfsRepair {

    private fun moveDirectory(source: File, target: File): Boolean {
        if (target.exists()) return false
        return source.renameTo(target)
    }

    /**
     * Replaces [target] with [content] by writing beside it and renaming over it, so a symlink standing there (an
     * `apt install` puts /etc/resolv.conf back as a link into /run/systemd) is replaced rather than followed.
     * Under PRoot the app UID owns every rootfs file, so no privilege is involved; [staging] and [uid] are kept for
     * call-site compatibility with the chroot engine. [RootPathGuard] still gates the target as defense in depth.
     */
    @Suppress("UNUSED_PARAMETER")
    fun writeAsRoot(target: File, content: ByteArray, staging: File, uid: Int, mode: Int = 0b110100100): Boolean {
        if (!RootPathGuard.isInsideAppData(target.canonicalPath)) return false
        val parent = target.parentFile ?: return false
        parent.mkdirs()
        val temp = File(parent, target.name + ".nextcraft-tmp")
        return runCatching {
            temp.writeBytes(content)
            android.system.Os.chmod(temp.absolutePath, mode)
            android.system.Os.rename(temp.absolutePath, target.absolutePath)
            true
        }.getOrElse { temp.delete(); false }
    }

    /**
     * Restores owner read/write/execute on [target]. Every rootfs file is already owned by the app UID under PRoot
     * (there is no root to chown to), so a file dpkg or an agent left at mode 0700/0444 is not a privilege problem:
     * the owning process can always chmod its own file regardless of its current mode. [uid] is kept for call-site
     * compatibility with the chroot engine, where it names who to chown to.
     */
    @Suppress("UNUSED_PARAMETER")
    fun reclaimOwnership(target: File, uid: Int, recursive: Boolean = true): Boolean {
        val fixed = runCatching {
            val mode = android.system.Os.lstat(target.path).st_mode
            if (android.system.OsConstants.S_ISLNK(mode)) return@runCatching true
            val ownerBits = if (android.system.OsConstants.S_ISDIR(mode)) 0b111000000 else 0b110000000
            android.system.Os.chmod(target.path, (mode and 0b111111111111) or ownerBits)
            true
        }.getOrDefault(false)
        if (!recursive || !target.isDirectory) return fixed
        var all = fixed
        target.listFiles()?.forEach { child -> all = reclaimOwnership(child, uid, recursive = true) && all }
        return all
    }

    /**
     * While the marker exists chroot-exec refuses to mount (it checks after taking the mount lock), so no launch can add
     * mounts between [ensureUnmounted] and the rename or delete that follows. Nested calls share one marker.
     */
    private val maintenanceDepth = java.util.concurrent.atomic.AtomicInteger()

    internal fun maintenanceMarker(runtimeChild: File): File = File(runtimeChild.absoluteFile.parentFile, MAINTENANCE_MARKER)

    private inline fun <T> underMaintenance(runtimeChild: File, block: () -> T): T {
        val marker = maintenanceMarker(runtimeChild)
        if (maintenanceDepth.getAndIncrement() == 0) runCatching { marker.parentFile?.mkdirs(); marker.writeText("1") }
        try {
            return block()
        } finally {
            if (maintenanceDepth.decrementAndGet() == 0) marker.delete()
        }
    }

    const val MAINTENANCE_MARKER = ".rootfs-maintenance"

    fun safeDeleteRecursively(targetDir: File): Boolean = underMaintenance(targetDir) { safeDeleteLocked(targetDir) }

    private fun safeDeleteLocked(targetDir: File): Boolean {
        if (!targetDir.exists()) return true
        return targetDir.deleteRecursively() || !targetDir.exists()
    }

    fun reconcileInterruptedSwap(
        rootfs: File,
        staging: File,
        backup: File,
        validate: (File) -> Boolean,
    ) {
        if (!backup.exists()) return
        underMaintenance(rootfs) { reconcileLocked(rootfs, staging, backup, validate) }
    }

    private fun reconcileLocked(rootfs: File, staging: File, backup: File, validate: (File) -> Boolean) {

        if (!rootfs.exists()) {
            check(moveDirectory(backup, rootfs)) { "Could not restore the previous Linux environment" }
            safeDeleteRecursively(staging)
            return
        }

        if (validate(rootfs)) {
            restoreHome(backup, rootfs)
            check(validate(rootfs)) { "Repaired Linux environment failed validation" }
            check(safeDeleteRecursively(backup)) { "Could not remove the previous Linux environment" }
            safeDeleteRecursively(staging)
            return
        }

        rollback(rootfs, staging, backup)
    }

    fun activate(
        rootfs: File,
        staging: File,
        backup: File,
        validate: (File) -> Boolean,
    ) {
        require(staging.isDirectory && validate(staging)) {
            "Rebuilt Linux environment failed validation"
        }
        check(!backup.exists()) { "A previous Linux environment repair is still pending" }
        underMaintenance(rootfs) { activateLocked(rootfs, staging, backup, validate) }
    }

    private fun activateLocked(rootfs: File, staging: File, backup: File, validate: (File) -> Boolean) {

        val hadRootfs = rootfs.exists()
        if (hadRootfs) {
            check(moveDirectory(rootfs, backup)) { "Could not preserve the previous Linux environment" }
        }

        try {
            check(moveDirectory(staging, rootfs)) { "Could not activate the rebuilt Linux environment" }
            if (hadRootfs) restoreHome(backup, rootfs)
            check(validate(rootfs)) { "Rebuilt Linux environment failed validation after activation" }
            // The new rootfs is validated and active. Failing to delete the old copy must not roll it back: a leftover
            // backup is cleaned up by reconcileInterruptedSwap on the next start.
            if (hadRootfs) safeDeleteRecursively(backup)
        } catch (error: Throwable) {
            if (hadRootfs && backup.exists()) {
                runCatching { rollback(rootfs, staging, backup) }
                    .onFailure(error::addSuppressed)
            }
            throw error
        }
    }

    private fun restoreHome(backup: File, rootfs: File) {
        val preservedHome = File(backup, "root")
        if (!preservedHome.exists()) return
        val freshHome = File(rootfs, "root")
        if (freshHome.exists()) {
            check(safeDeleteRecursively(freshHome)) { "Could not replace the rebuilt Linux home" }
        }
        check(moveDirectory(preservedHome, freshHome)) { "Could not restore the Linux home" }
    }

    private fun rollback(rootfs: File, staging: File, backup: File) {
        val activeHome = File(rootfs, "root")
        val backupHome = File(backup, "root")
        if (!backupHome.exists() && activeHome.exists()) {
            check(moveDirectory(activeHome, backupHome)) { "Could not preserve the Linux home during rollback" }
        }
        if (rootfs.exists()) {
            check(safeDeleteRecursively(rootfs)) { "Could not remove the failed Linux environment" }
        }
        check(moveDirectory(backup, rootfs)) { "Could not restore the previous Linux environment" }
        safeDeleteRecursively(staging)
    }
}
