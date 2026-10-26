package com.nextcraft.forge.runtime

import com.nextcraft.forge.BuildConfig

/**
 * Root helpers (`rm -rf`, `mv`, `chown`, writing files as root) must only ever touch this app's own data.
 * A bug, a stale setting or a crafted path must not be able to aim them at `/data`, `/sdcard` or another app.
 */
internal object RootPathGuard {
    private val appDataRoots = Regex("""/data/(data|user/\d+|user_de/\d+)""")
    private val sharedAppRoots = Regex("""/(storage/emulated/\d+|sdcard)/Android/(data|media)""")

    /**
     * True when [canonicalPath] (already canonical: no `..`, symlinks resolved) is strictly *inside* the app's own
     * data directory, e.g. `/data/user/0/<package>/files/runtime/rootfs`. The directory itself does not qualify.
     */
    fun isInsideAppData(canonicalPath: String, packageName: String = BuildConfig.APPLICATION_ID): Boolean {
        val marker = "/$packageName/"
        val at = canonicalPath.indexOf(marker)
        if (at < 0 || canonicalPath.length == at + marker.length) return false
        val prefix = canonicalPath.substring(0, at)
        return appDataRoots.matches(prefix) || sharedAppRoots.matches(prefix)
    }
}
