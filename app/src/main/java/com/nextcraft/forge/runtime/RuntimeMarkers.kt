package com.nextcraft.forge.runtime

import java.io.File

/**
 * Rootfs marker files moved from the old "PocketDev" names (`.pocket-*`) to `.nextcraft-*`.
 *
 * Published runtime bundles and older app builds still write the legacy names, so the app writes both names and,
 * before reading, copies a legacy file over the new one when their contents differ: a difference can only come from
 * a bundle that was just extracted or from an older build, and in both cases the legacy file is the newer one.
 * Keeping the legacy copy also lets a downgraded app find its markers instead of reinstalling the runtime.
 */
internal object RuntimeMarkers {
    const val PREFIX = ".nextcraft-"
    const val LEGACY_PREFIX = ".pocket-"

    /** Every marker, rootfs-relative, by its new name. */
    val PATHS: List<String> = listOf(
        "runtime-ready", "rootfs-version", "claude-version", "bundled-claude-version", "language-tools-version",
        "core-tools-version", "system-upgrade-version", "dev-stacks.json", "dsh-version", "agy-version",
        "github-cli-version", "dsh-android-compat-version", "macos-metadata-repair", "python-tools-version",
    ).map { PREFIX + it } + listOf("root/${PREFIX}rea-version", "root/${PREFIX}android-tools-version")

    /** Gradle loads every script in init.d, so only one of these may exist. Same path as AndroidToolchain (compiled standalone by build-android-maven-cache.sh). */
    const val GRADLE_INIT_SCRIPT = "root/.gradle/init.d/nextcraft-android.gradle"
    const val LEGACY_GRADLE_INIT_SCRIPT = "root/.gradle/init.d/pocketdev-android.gradle"

    /** The `.pocket-*` twin of a `.nextcraft-*` file, or null for any other file. */
    fun legacyOf(file: File): File? =
        file.name.takeIf { it.startsWith(PREFIX) }?.let { File(file.parentFile, LEGACY_PREFIX + it.removePrefix(PREFIX)) }

    /** Copies a newer legacy marker over [file]; a missing legacy file leaves [file] as it is. */
    fun sync(file: File) {
        val legacy = legacyOf(file)?.takeIf { it.isFile } ?: return
        val legacyText = legacy.readTextOrNull() ?: return
        if (file.readTextOrNull() != legacyText) runCatching { file.writeText(legacyText) }
    }

    fun syncAll(rootfs: File) {
        if (!rootfs.isDirectory) return
        PATHS.forEach { sync(File(rootfs, it)) }
        val legacyInit = File(rootfs, LEGACY_GRADLE_INIT_SCRIPT)
        if (legacyInit.isFile) {
            val init = File(rootfs, GRADLE_INIT_SCRIPT)
            runCatching { if (init.isFile) legacyInit.delete() else legacyInit.renameTo(init) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeMarkers", it) }
        }
    }

    /** Reads a marker by its new name after syncing it; null when neither name exists. */
    fun read(file: File): String? {
        sync(file)
        return file.readTextOrNull()
    }

    private fun File.readTextOrNull(): String? = if (isFile) runCatching { readText() }.getOrNull() else null
}

/** Writes a `.nextcraft-*` marker and its `.pocket-*` twin so older builds keep working. */
internal fun File.writeMarker(text: String) {
    parentFile?.mkdirs()
    writeText(text)
    RuntimeMarkers.legacyOf(this)?.let { runCatching { it.writeText(text) } }
}

/** Deletes a `.nextcraft-*` marker together with its `.pocket-*` twin. */
internal fun File.deleteMarker() {
    delete()
    RuntimeMarkers.legacyOf(this)?.delete()
}
