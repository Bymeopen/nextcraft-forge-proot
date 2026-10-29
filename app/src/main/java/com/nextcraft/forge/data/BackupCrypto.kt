package com.nextcraft.forge.data

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password encryption for backup files, streamed in chunks so a multi-GB backup never sits in memory (Android's
 * AES-GCM cipher streams buffer the whole message before checking the tag).
 *
 * Format: "NCBK" | version 1 | salt (16) | nonce prefix (12) | chunks. Each chunk is a 4-byte length followed by
 * AES-256-GCM ciphertext+tag of up to [CHUNK] plaintext bytes. The chunk index and a "last chunk" flag go into the
 * nonce and the associated data, so chunks cannot be reordered, dropped or truncated without the tag failing.
 * The key comes from the password with PBKDF2-HMAC-SHA256 ([ITERATIONS] rounds).
 */
internal object BackupCrypto {
    private val MAGIC = byteArrayOf('N'.code.toByte(), 'C'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val VERSION: Byte = 1
    private const val CHUNK = 1 shl 20
    private const val ITERATIONS = 210_000
    private const val TAG_BITS = 128

    class WrongPasswordException : Exception("Wrong password or damaged backup")

    /** True when [header] (the first bytes of a file) starts like an encrypted backup. */
    fun isEncrypted(header: ByteArray): Boolean = header.size >= MAGIC.size && MAGIC.indices.all { header[it] == MAGIC[it] }

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        var raw: ByteArray? = null
        try {
            raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES")
        } finally {
            raw?.fill(0)
            spec.clearPassword()
        }
    }

    private fun nonce(prefix: ByteArray, index: Long, last: Boolean): ByteArray = prefix.copyOf().also { n ->
        // The last 8 bytes carry the chunk index; the top bit of byte 4 marks the final chunk.
        for (i in 0 until 8) n[4 + i] = (n[4 + i].toInt() xor (index ushr (8 * (7 - i))).toInt()).toByte()
        if (last) n[4] = (n[4].toInt() xor 0x80).toByte()
    }

    private fun aad(index: Long, last: Boolean): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).apply { writeLong(index); writeBoolean(last) } }.toByteArray()

    /** Wraps [out]: everything written is encrypted; closing writes the final chunk and closes [out]. */
    fun encrypt(out: OutputStream, password: CharArray): OutputStream {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val prefix = ByteArray(12).also(random::nextBytes)
        val key = key(password, salt)
        val data = DataOutputStream(out)
        data.write(MAGIC)
        data.writeByte(VERSION.toInt())
        data.write(salt)
        data.write(prefix)
        return object : FilterOutputStream(data) {
            private val buffer = ByteArray(CHUNK)
            private var filled = 0
            private var index = 0L
            private var closed = false

            private fun flushChunk(last: Boolean) {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(prefix, index, last)))
                cipher.updateAAD(aad(index, last))
                val sealed = cipher.doFinal(buffer, 0, filled)
                data.writeInt(sealed.size)
                data.write(sealed)
                index++
                filled = 0
            }

            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                var offset = off
                var remaining = len
                while (remaining > 0) {
                    // A full buffer is only sealed once more data arrives, so the true last chunk gets the flag.
                    if (filled == CHUNK) flushChunk(last = false)
                    val n = minOf(remaining, CHUNK - filled)
                    System.arraycopy(b, offset, buffer, filled, n)
                    filled += n
                    offset += n
                    remaining -= n
                }
            }

            override fun close() {
                if (closed) return
                closed = true
                flushChunk(last = true)
                data.flush()
                data.close()
            }
        }
    }

    /** Wraps [input] (positioned at the start of an encrypted backup) and returns the plaintext stream. */
    fun decrypt(input: InputStream, password: CharArray): InputStream {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size).also(data::readFully)
        require(isEncrypted(magic)) { "Not an encrypted backup" }
        require(data.readByte() == VERSION) { "Unsupported backup version" }
        val salt = ByteArray(16).also(data::readFully)
        val prefix = ByteArray(12).also(data::readFully)
        val key = key(password, salt)
        return object : InputStream() {
            private var chunk = ByteArray(0)
            private var pos = 0
            private var index = 0L
            private var done = false

            private fun next(): Boolean {
                if (done) return false
                val size = try {
                    data.readInt()
                } catch (_: EOFException) {
                    throw WrongPasswordException() // ended before the flagged last chunk: truncated
                }
                require(size in 16..(CHUNK + 16)) { "Damaged backup" }
                val sealed = try {
                    ByteArray(size).also(data::readFully)
                } catch (_: EOFException) {
                    throw WrongPasswordException() // cut off inside a chunk: same verdict as a cut between chunks
                }
                // The last chunk is the one the stream ends after; try it as "not last" first, the common case.
                val plain = open(sealed, last = false) ?: open(sealed, last = true)?.also { done = true }
                    ?: throw WrongPasswordException()
                // Nothing may follow the final chunk: appended or spliced-in bytes mean the file is not what was written.
                if (done && data.read() != -1) throw WrongPasswordException()
                chunk = plain
                pos = 0
                index++
                return true
            }

            private fun open(sealed: ByteArray, last: Boolean): ByteArray? = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(prefix, index, last)))
                cipher.updateAAD(aad(index, last))
                cipher.doFinal(sealed)
            } catch (_: AEADBadTagException) {
                null
            }

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                while (pos >= chunk.size) if (!next()) return -1
                val n = minOf(len, chunk.size - pos)
                System.arraycopy(chunk, pos, b, off, n)
                pos += n
                return n
            }

            override fun close() = data.close()
        }
    }
}
