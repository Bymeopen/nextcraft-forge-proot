package com.nextcraft.forge.runtime

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** One row of a directory listing. Symbolic links are listed but never followed. */
internal data class BrowserEntry(val name: String, val isDirectory: Boolean, val isLink: Boolean, val sizeBytes: Long)

internal enum class BrowserDenial { OUTSIDE_APP_DATA, MISSING, NO_ACCESS }

internal sealed interface BrowserListing {
    data class Entries(val items: List<BrowserEntry>, val viaRoot: Boolean) : BrowserListing
    data class Denied(val reason: BrowserDenial) : BrowserListing
}

internal sealed interface BrowserRead {
    data class Text(val text: String, val truncated: Boolean, val sizeBytes: Long) : BrowserRead
    data class Binary(val sizeBytes: Long) : BrowserRead
    data class Denied(val reason: BrowserDenial) : BrowserRead
}

/**
 * Reads folders and files inside the app's own data (projects, the Ubuntu home, the whole Ubuntu root). The Ubuntu files
 * are owned by root, so when the app's own user cannot read something it asks root, but only ever inside the app data
 * directory ([RootPathGuard]). Read-only: nothing here writes or deletes.
 */
internal object FileBrowser {
    private const val ROOT_TIMEOUT_SECONDS = 10L
    private const val BINARY_SNIFF_BYTES = 4096

    fun list(dir: File): BrowserListing {
        val canonical = runCatching { dir.canonicalFile }.getOrNull() ?: return BrowserListing.Denied(BrowserDenial.MISSING)
        if (!RootPathGuard.isInsideAppData(canonical.path)) return BrowserListing.Denied(BrowserDenial.OUTSIDE_APP_DATA)
        val direct = canonical.listFiles()
        if (direct != null) return BrowserListing.Entries(sorted(direct.map(::entryOf)), viaRoot = false)
        if (!canonical.exists()) return BrowserListing.Denied(BrowserDenial.MISSING)
        val output = runRoot("ls -lA -- ${shellSingleQuote(canonical.path)}")?.toString(Charsets.UTF_8)
            ?: return BrowserListing.Denied(BrowserDenial.NO_ACCESS)
        return BrowserListing.Entries(sorted(parseLsLong(output)), viaRoot = true)
    }

    fun readBytes(file: File, maxBytes: Int = 10 * 1024 * 1024): ByteArray? {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (!RootPathGuard.isInsideAppData(canonical.path)) return null
        if (canonical.isDirectory) return null
        val direct = canonical.takeIf { it.isFile && it.canRead() }
        if (direct != null) {
            if (direct.length() > maxBytes) return null
            return runCatching { direct.readBytes() }.getOrNull()
        }
        val resolved = runRoot("readlink -f -- ${shellSingleQuote(canonical.path)}")?.toString(Charsets.UTF_8)?.trim()
            ?: return null
        if (resolved.isEmpty() || !RootPathGuard.isInsideAppData(resolved)) return null
        val total = runRoot("wc -c < ${shellSingleQuote(canonical.path)}")?.toString(Charsets.UTF_8)?.trim()?.toLongOrNull()
        if (total != null && total > maxBytes) return null
        return runRoot("head -c $maxBytes -- ${shellSingleQuote(canonical.path)}")
    }

    fun read(file: File, maxBytes: Int = 256 * 1024): BrowserRead {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return BrowserRead.Denied(BrowserDenial.MISSING)
        if (!RootPathGuard.isInsideAppData(canonical.path)) return BrowserRead.Denied(BrowserDenial.OUTSIDE_APP_DATA)
        if (canonical.isDirectory) return BrowserRead.Denied(BrowserDenial.MISSING)
        val direct = canonical.takeIf { it.isFile && it.canRead() }
        val bytes: ByteArray
        val size: Long
        if (direct != null) {
            size = direct.length()
            bytes = direct.readHeadBytes(maxBytes)
        } else {
            // The app's own canonicalFile cannot see through root-only directories, so the agent could plant a symlink
            // there that points outside the app data. Resolve again as root and re-check before reading.
            val resolved = runRoot("readlink -f -- ${shellSingleQuote(canonical.path)}")?.toString(Charsets.UTF_8)?.trim()
                ?: return BrowserRead.Denied(BrowserDenial.NO_ACCESS)
            if (resolved.isEmpty() || !RootPathGuard.isInsideAppData(resolved)) return BrowserRead.Denied(BrowserDenial.OUTSIDE_APP_DATA)
            val head = runRoot("head -c $maxBytes -- ${shellSingleQuote(canonical.path)}") ?: return BrowserRead.Denied(BrowserDenial.NO_ACCESS)
            val total = runRoot("wc -c < ${shellSingleQuote(canonical.path)}")?.toString(Charsets.UTF_8)?.trim()?.toLongOrNull()
            bytes = head
            size = total ?: head.size.toLong()
        }
        return decode(bytes, size)
    }

    internal fun decode(bytes: ByteArray, size: Long): BrowserRead {
        if (bytes.take(BINARY_SNIFF_BYTES).any { it == 0.toByte() }) return BrowserRead.Binary(size)
        val truncated = size > bytes.size
        // A cut in the middle of a multi-byte character would show a replacement mark at the end.
        val usable = if (truncated) completeUtf8PrefixLength(bytes, bytes.size) else bytes.size
        return BrowserRead.Text(String(bytes, 0, usable, Charsets.UTF_8), truncated, size)
    }

    /** Parses `ls -lA` output (Android toybox or GNU): one entry per line; `total` and device files are skipped. */
    internal fun parseLsLong(output: String): List<BrowserEntry> {
        val line = Regex("""^([-dl])\S{9}[.+]?\s+\d+\s+\S+\s+\S+\s+(\d+)\s+(?:\d{4}-\d{2}-\d{2}|\w{3}\s+\d+)\s+(?:\d{2}:\d{2}|\d{4})\s+(.+)$""")
        return output.lineSequence().mapNotNull { raw ->
            val match = line.matchEntire(raw.trimEnd()) ?: return@mapNotNull null
            val type = match.groupValues[1]
            val name = match.groupValues[3].substringBefore(" -> ")
            if (name == "." || name == "..") return@mapNotNull null
            BrowserEntry(name, isDirectory = type == "d", isLink = type == "l", sizeBytes = match.groupValues[2].toLongOrNull() ?: 0L)
        }.toList()
    }

    private fun entryOf(file: File): BrowserEntry {
        val link = Files.isSymbolicLink(file.toPath())
        return BrowserEntry(file.name, isDirectory = !link && file.isDirectory, isLink = link, sizeBytes = if (file.isFile) file.length() else 0L)
    }

    /** Folders first, then by name ignoring case. */
    private fun sorted(items: List<BrowserEntry>): List<BrowserEntry> =
        items.sortedWith(compareByDescending<BrowserEntry> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    private fun runRoot(command: String): ByteArray? = runCatching {
        if (!RuntimeEngines.isRootEngine()) return@runCatching null
        val process = ProcessBuilder("su", "-c", command).redirectError(ProcessBuilder.Redirect.to(File("/dev/null"))).start()
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = Thread({
            try {
                if (!process.waitFor(ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    timedOut.set(true)
                    process.destroyForcibly()
                }
            } catch (_: InterruptedException) {
            }
        }, "forge-filebrowser-watchdog").apply { isDaemon = true; start() }
        val output = try {
            process.inputStream.use { it.readBytes() }
        } finally {
            watchdog.interrupt()
            if (process.isAlive) process.destroyForcibly()
        }
        if (!timedOut.get() && process.waitFor(1, TimeUnit.SECONDS) && process.exitValue() == 0) output else null
    }.getOrNull()
}
