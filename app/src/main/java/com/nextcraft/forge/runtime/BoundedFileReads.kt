package com.nextcraft.forge.runtime

import android.os.FileObserver
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Reads at most [maxBytes] from the start of a file without requiring newer Android APIs. */
internal fun File.readHeadBytes(maxBytes: Int): ByteArray {
    if (!isFile || maxBytes <= 0) return ByteArray(0)
    val expected = minOf(length(), maxBytes.toLong()).toInt()
    val bytes = ByteArray(expected)
    var offset = 0
    inputStream().use { input ->
        while (offset < expected) {
            val count = input.read(bytes, offset, expected - offset)
            if (count < 0) break
            offset += count
        }
    }
    return if (offset == expected) bytes else bytes.copyOf(offset)
}

/** Reads at most [maxBytes] from the start of a file as UTF-8 text without splitting trailing multi-byte characters. */
internal fun File.readBoundedText(maxBytes: Int): String {
    val bytes = readHeadBytes(maxBytes)
    if (bytes.isEmpty()) return ""
    val usable = if (length() > bytes.size) completeUtf8PrefixLength(bytes, bytes.size) else bytes.size
    val finalLen = if (usable > 0) usable else bytes.size
    return String(bytes, 0, finalLen, Charsets.UTF_8)
}

/** Reads at most [maxBytes] from the end of a file without allocating for the whole file or corrupting leading UTF-8 characters. */
internal fun File.readTailText(maxBytes: Int): String {
    if (!isFile || maxBytes <= 0) return ""
    return RandomAccessFile(this, "r").use { input ->
        val length = input.length()
        val byteCount = minOf(length, maxBytes.toLong()).toInt()
        if (byteCount <= 0) return@use ""
        val seekOffset = length - byteCount
        input.seek(seekOffset)
        val bytes = ByteArray(byteCount)
        var offset = 0
        while (offset < byteCount) {
            val read = input.read(bytes, offset, byteCount - offset)
            if (read < 0) break
            offset += read
        }
        var start = 0
        if (seekOffset > 0L && offset > 0) {
            // Skip up to 3 leading UTF-8 continuation bytes (10xxxxxx) if the seek landed mid-codepoint.
            while (start < minOf(offset, 3) && (bytes[start].toInt() and 0xC0) == 0x80) {
                start++
            }
        }
        String(bytes, start, offset - start, Charsets.UTF_8)
    }
}

/**
 * Returns the byte length of [bytes] (within `0..length`) that ends on a complete UTF-8 character boundary.
 * Prevents 16 KB stream chunk reads from splitting a 2-, 3-, or 4-byte UTF-8 sequence into `\uFFFD`.
 */
internal fun completeUtf8PrefixLength(bytes: ByteArray, length: Int): Int {
    val limit = minOf(length, bytes.size)
    if (limit <= 0) return 0
    val last = bytes[limit - 1].toInt() and 0xFF
    if ((last and 0x80) == 0) return limit
    val minScan = maxOf(0, limit - 4)
    for (index in (limit - 1) downTo minScan) {
        val b = bytes[index].toInt() and 0xFF
        if ((b and 0xC0) != 0x80) {
            val expectedLen = when {
                (b and 0xE0) == 0xC0 -> 2
                (b and 0xF0) == 0xE0 -> 3
                (b and 0xF8) == 0xF0 -> 4
                else -> 1
            }
            val actualLen = limit - index
            return if (actualLen < expectedLen) index else limit
        }
    }
    return limit
}

/**
 * Low-latency (<2ms) persistent FileChannel + inotify (FileObserver) stream reader.
 * Replaces repeated RandomAccessFile open/close polling in agent runtime bridges.
 */
internal class InotifyLogTailer(
    private val file: File,
    private val maxLineBytes: Int = MAX_TAIL_LINE_BYTES,
) : AutoCloseable {
    private val wakeSignal = LinkedBlockingQueue<Unit>(1)
    private val raf: RandomAccessFile
    private val channel: java.nio.channels.FileChannel
    private val directBuf = ByteBuffer.allocateDirect(16 * 1024)
    private val lineOut = ByteArrayOutputStream(4096)
    private var lineOverflowed = false
    private val observer: FileObserver

    init {
        file.parentFile?.mkdirs()
        if (!file.exists()) file.createNewFile()
        raf = RandomAccessFile(file, "r")
        try {
            channel = raf.channel
            @Suppress("DEPRECATION")
            observer = object : FileObserver(
                (file.parentFile ?: file).absolutePath,
                MODIFY or CLOSE_WRITE or CREATE,
            ) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null || path == file.name) {
                        wakeSignal.offer(Unit)
                    }
                }
            }
            observer.startWatching()
        } catch (t: Throwable) {
            runCatching { raf.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("BoundedFileReads", it) }
            throw t
        }
    }

    private fun decodeCurrentLine(): String {
        val bytes = lineOut.toByteArray()
        val overflow = lineOverflowed
        lineOut.reset()
        lineOverflowed = false
        val safeLen = if (overflow) {
            completeUtf8PrefixLength(bytes, bytes.size).let { if (it > 0) it else bytes.size }
        } else {
            bytes.size
        }
        val text = String(bytes, 0, safeLen, StandardCharsets.UTF_8)
        return if (overflow) "$text... [truncated]" else text
    }

    fun hasUnreadBytes(): Boolean = channel.position() < file.length()

    fun hasPendingPartialLine(): Boolean = lineOut.size() > 0 || lineOverflowed

    fun readLines(timeoutMs: Long = 40L, flushIncomplete: Boolean = false): List<String> {
        val result = ArrayList<String>(4)
        drainLines(timeoutMs = timeoutMs, flushIncomplete = flushIncomplete) { line ->
            result.add(line)
        }
        return result
    }

    /**
     * Waits up to [timeoutMs] for an inotify event on [file], then drains all newly appended complete lines.
     */
    fun drainLines(timeoutMs: Long = 40L, flushIncomplete: Boolean = false, onLine: (String) -> Unit) {
        if (timeoutMs > 0L && channel.position() >= file.length()) {
            wakeSignal.poll(timeoutMs, TimeUnit.MILLISECONDS)
        }
        while (true) {
            directBuf.clear()
            val count = channel.read(directBuf)
            if (count <= 0) break
            directBuf.flip()
            for (i in 0 until count) {
                val b = directBuf.get()
                if (b == '\n'.code.toByte()) {
                    onLine(decodeCurrentLine())
                } else if (b != '\r'.code.toByte()) {
                    if (lineOut.size() < maxLineBytes) {
                        lineOut.write(b.toInt())
                    } else {
                        lineOverflowed = true
                    }
                }
            }
        }
        if (flushIncomplete && (lineOut.size() > 0 || lineOverflowed)) {
            onLine(decodeCurrentLine())
        }
    }

    override fun close() {
        runCatching { observer.stopWatching() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("BoundedFileReads", it) }
        runCatching { channel.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("BoundedFileReads", it) }
        runCatching { raf.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("BoundedFileReads", it) }
    }

    companion object {
        private const val MAX_TAIL_LINE_BYTES = 512 * 1024
    }
}

/**
 * Deletes this file or directory tree without following symlinks. Kotlin's deleteRecursively() descends into a
 * symlinked directory, so a link an agent made to another project would delete that project's files.
 */
internal fun java.io.File.deleteRecursivelyNoFollow(): Boolean {
    val isLink = java.nio.file.Files.isSymbolicLink(toPath())
    if (!isLink && isDirectory) listFiles()?.forEach { it.deleteRecursivelyNoFollow() }
    return delete() || (!exists() && !java.nio.file.Files.isSymbolicLink(toPath()))
}
