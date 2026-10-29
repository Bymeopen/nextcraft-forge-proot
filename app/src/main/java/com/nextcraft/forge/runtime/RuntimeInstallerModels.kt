package com.nextcraft.forge.runtime

import java.io.File

// Runtime install models and pure version/config helpers shared with [RuntimeInstaller].
data class InstalledRuntime(
    val chrootExec: File,
    val rootfs: File,
) {
    val binDir: File get() = chrootExec.parentFile ?: rootfs
}

data class RuntimeInstallProgress(
    val message: String,
    val fraction: Float,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val terminalLine: String? = null,
    val indeterminate: Boolean = false,
    val event: RuntimeInstallEvent = RuntimeInstallEvent.STAGE,
    /** True when [downloadedBytes] counts archive bytes unpacked rather than downloaded. */
    val unpacking: Boolean = false,
)

data class AgentUpdateInfo(
    val installedVersion: String,
    val latestVersion: String,
)

/** The pinned dsh, or a newer one left by a later app build: an older app must not reinstall (downgrade) it. */
internal val FS_PROMISES_IMPORT = Regex("""import \{([^}]*)\} from "node:fs/promises";""")

/**
 * Swaps DeepSeek's hard-link publication for COPYFILE_EXCL (`copyFile(src, dst, 1)`): Android's app data filesystem
 * refuses link(2) inside the chroot. Adds `copyFile` to the `node:fs/promises` import whatever else it lists, so new
 * DeepSeek releases that reorder that import still match. Returns the source unchanged when already patched, or null
 * when the expected call or import is missing.
 */
internal fun patchDshSource(source: String, callBefore: String, callAfter: String): String? {
    val import = FS_PROMISES_IMPORT.find(source) ?: return null
    val names = import.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val hasCopyFile = "copyFile" in names
    if (callAfter in source && hasCopyFile) return source
    if (callBefore !in source) return null
    val withImport = if (hasCopyFile) source else source.replaceRange(
        import.range,
        "import { ${(names + "copyFile").sorted().joinToString(", ")} } from \"node:fs/promises\";",
    )
    return withImport.replace(callBefore, callAfter)
}

/** Oldest DeepSeek release the compatibility patch still handles; installs since then keep working without a reinstall. */
internal const val MIN_DSH_VERSION = "0.1.2-rc.1"

internal fun isSupportedDshVersion(version: String): Boolean =
    version == MIN_DSH_VERSION || isAgentVersionNewer(version, MIN_DSH_VERSION)

/** True when [candidate] is a newer agent version than [current] (numeric parts, then pre-release tags). */
internal fun isAgentVersionNewer(candidate: String, current: String): Boolean {
    fun numericParts(value: String) = value.substringBefore('-').substringBefore('+')
        .split('.')
        .map { it.toIntOrNull() ?: 0 }
    val left = numericParts(candidate)
    val right = numericParts(current)
    for (index in 0 until maxOf(left.size, right.size)) {
        val comparison = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
        if (comparison != 0) return comparison > 0
    }
    val candidatePreTag = candidate.substringBefore('+').substringAfter('-', "")
    val currentPreTag = current.substringBefore('+').substringAfter('-', "")
    if (candidatePreTag.isEmpty() || currentPreTag.isEmpty()) {
        // A release (no pre-release tag) always outranks a pre-release of the same numeric version.
        return currentPreTag.isNotEmpty() && candidatePreTag.isEmpty()
    }
    // Same numeric version, both pre-release (e.g. "rc.1" vs "rc.2"): compare identifiers in order.
    val candidateIds = candidatePreTag.split('.')
    val currentIds = currentPreTag.split('.')
    for (index in 0 until maxOf(candidateIds.size, currentIds.size)) {
        val candidateId = candidateIds.getOrNull(index)
        val currentId = currentIds.getOrNull(index)
        if (candidateId == null) return false
        if (currentId == null) return true
        val candidateNum = candidateId.toIntOrNull()
        val currentNum = currentId.toIntOrNull()
        val comparison = if (candidateNum != null && currentNum != null) {
            candidateNum.compareTo(currentNum)
        } else {
            candidateId.compareTo(currentId)
        }
        if (comparison != 0) return comparison > 0
    }
    return false
}

enum class RuntimeInstallEvent { STAGE, COMMAND, OUTPUT, DOWNLOAD, COMMAND_COMPLETED, COMPLETED }

internal data class RuntimeBundle(
    val label: String,
    val fileName: String,
    val sha256: String,
    val compressedBytes: Long,
    val baseUrl: String = RUNTIME_RELEASE_BASE_URL,
)

/** Release hosting the Ubuntu 24.04 bundles built from scripts/runtime-bundles. */
internal const val RUNTIME_RELEASE_BASE_URL = "https://github.com/bymeopen/nextcraft-forge/releases/download/runtime-2026.10.1"

internal data class GitConfigEntry(
    val section: String,
    val key: String,
    val value: String,
    val replace: Boolean = false,
    /** For multi-valued keys (safe.directory): add the value unless that exact value is already there. */
    val multiValue: Boolean = false,
)

/**
 * Adds [entries] to a git config text. An entry whose key already exists in its section is kept as the user set it,
 * unless [GitConfigEntry.replace]. Sections are matched by exact `[name]` header (no subsections are needed here).
 */
internal fun mergeGitConfig(existing: String, entries: List<GitConfigEntry>): String {
    val lines = existing.lines().dropLastWhile { it.isBlank() }.toMutableList()
    fun sectionOf(line: String) = Regex("^\\s*\\[\\s*([A-Za-z0-9.-]+)\\s*]\\s*$").find(line)?.groupValues?.get(1)?.lowercase()
    fun isHeader(line: String) = Regex("^\\s*\\[[^\\]]*]").containsMatchIn(line)
    fun keyOf(line: String) = Regex("^\\s*([A-Za-z][A-Za-z0-9-]*)\\s*(=|$)").find(line)?.groupValues?.get(1)?.lowercase()
    for (entry in entries) {
        val wanted = entry.section.lowercase()
        val start = lines.indexOfFirst { sectionOf(it) == wanted }
        val formatted = "    ${entry.key} = ${entry.value}"
        if (start < 0) {
            lines += "[${entry.section}]"
            lines += formatted
            continue
        }
        var end = start + 1
        // Any header ends the section, including subsections such as [credential "https://github.com"], so an entry is
        // never appended inside (or matched against) another section's keys.
        while (end < lines.size && !isHeader(lines[end])) end++
        val sameKey = (start + 1 until end).filter { keyOf(lines[it]) == entry.key.lowercase() }
        if (entry.multiValue) {
            val present = sameKey.any { lines[it].substringAfter('=', "").trim() == entry.value }
            if (!present) lines.add(end, formatted)
            continue
        }
        val existingIndex = sameKey.firstOrNull()
        when {
            existingIndex == null -> lines.add(end, formatted)
            entry.replace -> lines[existingIndex] = formatted
        }
    }
    return lines.joinToString("\n") + "\n"
}
