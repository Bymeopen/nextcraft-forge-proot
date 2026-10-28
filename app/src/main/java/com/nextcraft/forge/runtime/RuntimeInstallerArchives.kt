package com.nextcraft.forge.runtime

import android.os.Process as AndroidProcess
import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream

// Archive extraction, download and checksum helpers for [RuntimeInstaller].
internal fun RuntimeInstaller.openZstdInputStream(
    archive: File,
    consumed: java.util.concurrent.atomic.AtomicLong? = null,
): java.io.InputStream {
    val fileStream = archive.inputStream()
    // Counts compressed bytes read so unpacking can report MB/s; the external-zstd fallback is not counted.
    val counted = if (consumed == null) fileStream else object : java.io.FilterInputStream(fileStream) {
        override fun read(): Int = super.read().also { if (it >= 0) consumed.incrementAndGet() }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) consumed.addAndGet(it.toLong()) }
    }
    val rawStream = BufferedInputStream(counted)
    return try {
        ZstdCompressorInputStream(rawStream)
    } catch (_: Throwable) {
        runCatching { rawStream.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
        val process = runCatching {
            ProcessBuilder("zstd", "-d", "-c", archive.absolutePath).start()
        }.getOrElse {
            val escapedPath = archive.absolutePath.replace("'", "'\"'\"'")
            val pathExport = "export PATH=/system/bin:/system/xbin:/sbin:/vendor/bin:/data/adb/magisk:/data/adb/ksu/bin:/data/adb/ap/bin:${'$'}PATH; "
            ProcessBuilder("su", "-c", "${pathExport}zstd -d -c '$escapedPath'").start()
        }
        object : java.io.FilterInputStream(BufferedInputStream(process.inputStream)) {
            internal var reachedEof = false

            override fun read(): Int = super.read().also { if (it < 0) reachedEof = true }

            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { if (it < 0) reachedEof = true }

            override fun close() {
                try {
                    super.close()
                } finally {
                    runCatching { process.errorStream.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
                    runCatching { process.outputStream.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
                    val finished = runCatching {
                        process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                    }.getOrDefault(false)
                    if (!finished) {
                        process.destroyForcibly()
                    } else if (reachedEof && process.exitValue() != 0) {
                        throw java.io.IOException("zstd decompression failed with exit code ${process.exitValue()}")
                    }
                }
            }
        }
    }
}

/**
 * Opens [target] for writing during an unpack. An earlier unpack may have left a read-only file (mode 0555) or root
 * rewrote it, which made the app UID's open fail with EACCES (Antigravity install stopped at 80%). A leftover is
 * replaced; if the folder itself is root's it is reclaimed first.
 */
internal fun RuntimeInstaller.openForWrite(target: File): FileOutputStream {
    try {
        return FileOutputStream(target)
    } catch (first: java.io.IOException) {
        val parent = target.parentFile
        if (parent != null && !parent.canWrite()) RootfsRepair.reclaimOwnership(parent, AndroidProcess.myUid(), recursive = false)
        if (target.exists() && !target.canWrite() && !target.delete()) {
            RootfsRepair.reclaimOwnership(target, AndroidProcess.myUid(), recursive = false)
            target.delete()
        }
        return FileOutputStream(target)
    }
}

@Suppress("DEPRECATION")
internal suspend fun RuntimeInstaller.extractZstdTar(
    archive: File,
    destination: File,
    /** Receives (compressed bytes consumed, archive size) at most every ~400 ms. */
    report: (suspend (Long, Long) -> Unit)? = null,
) {
    val consumed = if (report != null) java.util.concurrent.atomic.AtomicLong() else null
    val archiveSize = archive.length()
    var lastReport = 0L
    val rootDir = destination.canonicalFile
    val rootPath = rootDir.toPath()
    val deferredLinks = mutableListOf<Pair<File, File>>()
    TarArchiveInputStream(openZstdInputStream(archive, consumed)).use { tar ->
        var entry: TarArchiveEntry? = tar.nextTarEntry
        while (entry != null) {
            // Pause/stop from the notification must end a long unpack instead of waiting for it to finish.
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (report != null && consumed != null) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastReport >= 400L) {
                    lastReport = now
                    report(consumed.get().coerceAtMost(archiveSize), archiveSize)
                }
            }
            val currentEntry = entry
            val cleanName = currentEntry.name.removePrefix("./").trimEnd('/')
            if (cleanName.isNotBlank()) {
                val target = safeChild(rootDir, cleanName)
                when {
                    currentEntry.isDirectory -> {
                        if (java.nio.file.Files.isSymbolicLink(target.toPath())) {
                            val resolvedDir = runCatching {
                                resolveSafeSymlinkTarget(
                                    rootPath = rootPath,
                                    parentDir = target.toPath().parent ?: rootPath,
                                    linkName = java.nio.file.Files.readSymbolicLink(target.toPath()).toString(),
                                    errorMessage = "Archive path escapes runtime via symlink",
                                ).toFile()
                            }.getOrNull()
                            if (resolvedDir != null) {
                                resolvedDir.mkdirs()
                            } else {
                                target.delete()
                                target.mkdirs()
                            }
                        } else {
                            target.mkdirs()
                        }
                    }
                    currentEntry.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        resolveSafeSymlinkTarget(
                            rootPath = rootPath,
                            parentDir = target.toPath().parent ?: rootPath,
                            linkName = currentEntry.linkName,
                            errorMessage = "Archive symlink escapes runtime",
                        )
                        if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) {
                            if (target.isDirectory && !java.nio.file.Files.isSymbolicLink(target.toPath())) {
                                target.deleteRecursively()
                            } else {
                                target.delete()
                            }
                        }
                        Os.symlink(currentEntry.linkName, target.absolutePath)
                    }
                    currentEntry.isLink -> {
                        target.parentFile?.mkdirs()
                        val linkTarget = safeChild(rootDir, currentEntry.linkName.removePrefix("./").trimEnd('/'))
                        if (linkTarget.exists() || java.nio.file.Files.isSymbolicLink(linkTarget.toPath())) {
                            linkOrCopyFile(linkTarget, target)
                        } else {
                            deferredLinks += target to linkTarget
                        }
                    }
                    currentEntry.isFile -> {
                        target.parentFile?.mkdirs()
                        if (java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                        openForWrite(target).use { output -> tar.copyTo(output) }
                        runCatching { Os.chmod(target.absolutePath, currentEntry.mode and 0b111111111) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
                    }
                }
            }
            entry = tar.nextTarEntry
        }
    }
    deferredLinks.forEach { (target, linkTarget) ->
        require(linkTarget.isFile || java.nio.file.Files.isSymbolicLink(linkTarget.toPath())) {
            "Archive hard-link target is missing"
        }
        target.parentFile?.mkdirs()
        linkOrCopyFile(linkTarget, target)
        if (!java.nio.file.Files.isSymbolicLink(target.toPath())) {
            runCatching { Os.chmod(target.absolutePath, Os.stat(linkTarget.absolutePath).st_mode and 0b111111111) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
        }
    }
    // Published bundles still carry the .pocket-* marker names.
    RuntimeMarkers.syncAll(destination)
}

@Suppress("DEPRECATION")
internal fun RuntimeInstaller.extractNodeArchive(archive: File, destination: File) {
    val rootDir = destination.canonicalFile
    val rootPath = rootDir.toPath()
    val deferredLinks = mutableListOf<Pair<File, File>>()
    TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
        var entry: TarArchiveEntry? = tar.nextTarEntry
        while (entry != null) {
            val currentEntry = entry
            val relative = currentEntry.name.removePrefix("./").substringAfter('/', "").trimEnd('/')
            if (relative.isNotBlank()) {
                val target = safeChild(rootDir, relative)
                when {
                    currentEntry.isDirectory -> {
                        if (java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                        target.mkdirs()
                    }
                    currentEntry.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        resolveSafeSymlinkTarget(
                            rootPath = rootPath,
                            parentDir = target.toPath().parent ?: rootPath,
                            linkName = currentEntry.linkName,
                            errorMessage = "Archive symlink escapes runtime",
                        )
                        if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                        Os.symlink(currentEntry.linkName, target.absolutePath)
                    }
                    currentEntry.isLink -> {
                        val relativeLink = currentEntry.linkName.removePrefix("./").substringAfter('/', "").trimEnd('/')
                        val linkTarget = safeChild(rootDir, relativeLink)
                        target.parentFile?.mkdirs()
                        if (linkTarget.exists() || java.nio.file.Files.isSymbolicLink(linkTarget.toPath())) {
                            linkOrCopyFile(linkTarget, target)
                        } else {
                            deferredLinks += target to linkTarget
                        }
                    }
                    currentEntry.isFile -> {
                        target.parentFile?.mkdirs()
                        if (java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                        FileOutputStream(target).use { output -> tar.copyTo(output) }
                        runCatching { Os.chmod(target.absolutePath, currentEntry.mode and 0b111111111) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
                    }
                }
            }
            entry = tar.nextTarEntry
        }
    }
    deferredLinks.forEach { (target, linkTarget) ->
        require(linkTarget.isFile || java.nio.file.Files.isSymbolicLink(linkTarget.toPath())) {
            "Node.js archive hard-link target is missing"
        }
        target.parentFile?.mkdirs()
        linkOrCopyFile(linkTarget, target)
        if (!java.nio.file.Files.isSymbolicLink(target.toPath())) {
            runCatching { Os.chmod(target.absolutePath, Os.stat(linkTarget.absolutePath).st_mode and 0b111111111) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
        }
    }
}

internal fun RuntimeInstaller.linkOrCopyFile(source: File, destination: File) {
    if (destination.exists() || java.nio.file.Files.isSymbolicLink(destination.toPath())) destination.delete()
    if (java.nio.file.Files.isSymbolicLink(source.toPath())) {
        val symTarget = java.nio.file.Files.readSymbolicLink(source.toPath()).toString()
        Os.symlink(symTarget, destination.absolutePath)
        return
    }
    val linked = runCatching {
        Os.link(source.absolutePath, destination.absolutePath)
        true
    }.getOrDefault(false)
    if (!linked) {
        source.inputStream().use { input -> FileOutputStream(destination).use { input.copyTo(it) } }
        runCatching { Os.chmod(destination.absolutePath, Os.stat(source.absolutePath).st_mode and 0b111111111) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
    }
}

internal suspend fun RuntimeInstaller.downloadVerified(
    url: String,
    destination: File,
    expectedChecksum: String,
    algorithm: String = "SHA-256",
    onBytes: suspend (downloaded: Long, total: Long) -> Unit,
) {
    destination.parentFile?.mkdirs()
    if (destination.isFile && digest(destination, algorithm).equals(expectedChecksum, ignoreCase = true)) {
        onBytes(destination.length(), destination.length())
        return
    }
    val temporary = File(destination.parentFile, "${destination.name}.part")
    var existing = temporary.takeIf(File::isFile)?.length() ?: 0L
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        if (existing > 0L) connection.setRequestProperty("Range", "bytes=$existing-")
        val code = connection.responseCode
        if (code == 416 && existing > 0L) {
            // Stale/complete .part cannot be resumed; discard it and restart now (existing is 0 on the retry).
            temporary.delete()
            return downloadVerified(url, destination, expectedChecksum, algorithm, onBytes)
        }
        check(code in 200..299) { "Download failed with HTTP $code" }
        val resumed = code == HttpURLConnection.HTTP_PARTIAL && existing > 0L
        if (!resumed) {
            temporary.delete()
            existing = 0L
        }
        val total = connection.contentLengthLong.takeIf { it >= 0L }?.plus(existing) ?: -1L
        connection.inputStream.use { input ->
            FileOutputStream(temporary, resumed).use { output ->
                val buffer = ByteArray(128 * 1024)
                var downloaded = existing
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    downloaded += count
                    onBytes(downloaded, total)
                }
            }
        }
    } finally {
        connection.disconnect()
    }
    val actual = digest(temporary, algorithm)
    if (!actual.equals(expectedChecksum, ignoreCase = true)) {
        temporary.delete()
        error("Downloaded file checksum did not match")
    }
    destination.delete()
    check(temporary.renameTo(destination)) { "Could not finish download" }
}

internal fun RuntimeInstaller.fetchText(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/json")
        val code = connection.responseCode
        check(code in 200..299) { "Request failed with HTTP $code" }
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

internal fun RuntimeInstaller.digest(file: File, algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm)
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
