package com.nextcraft.forge.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCryptoTest {
    private fun seal(data: ByteArray, password: String): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.encrypt(out, password.toCharArray()).use { it.write(data) }
        return out.toByteArray()
    }

    private fun open(sealed: ByteArray, password: String): ByteArray =
        BackupCrypto.decrypt(ByteArrayInputStream(sealed), password.toCharArray()).use { it.readBytes() }

    @Test
    fun roundTripAcrossSeveralChunks() {
        val data = Random(7).nextBytes(2_500_000) // three chunks, the last one partial
        val sealed = seal(data, "s3cret")
        assertTrue(BackupCrypto.isEncrypted(sealed.copyOf(4)))
        assertArrayEquals(data, open(sealed, "s3cret"))
    }

    @Test
    fun emptyAndExactChunkSizes() {
        assertArrayEquals(ByteArray(0), open(seal(ByteArray(0), "p"), "p"))
        val exact = Random(1).nextBytes(1 shl 20)
        assertArrayEquals(exact, open(seal(exact, "p"), "p"))
    }

    @Test
    fun wrongPasswordIsRejected() {
        val sealed = seal("hello".toByteArray(), "right")
        try {
            open(sealed, "wrong")
            fail("wrong password accepted")
        } catch (_: BackupCrypto.WrongPasswordException) {
        }
    }

    @Test
    fun truncatedBackupIsRejected() {
        val data = Random(3).nextBytes(2_200_000)
        val sealed = seal(data, "p")
        // Drop the last chunk entirely: the stream must not end quietly with partial data.
        val firstChunkEnd = 4 + 1 + 16 + 12 + 4 + (1 shl 20) + 16
        val cut = sealed.copyOf(firstChunkEnd + 4 + (1 shl 20) + 16)
        try {
            open(cut, "p")
            fail("truncated backup accepted")
        } catch (_: BackupCrypto.WrongPasswordException) {
        }
    }

    @Test
    fun plainZipIsNotTakenForEncrypted() {
        assertFalse(BackupCrypto.isEncrypted(byteArrayOf(0x50, 0x4b, 0x03, 0x04)))
    }

    // Header: "NCBK" (4) + version (1) + salt (16) + nonce prefix (12); then per chunk: length (4) + ciphertext + tag (16).
    private val headerSize = 4 + 1 + 16 + 12
    private val fullChunkSize = 4 + (1 shl 20) + 16

    private fun assertRejected(sealed: ByteArray, password: String = "p") {
        try {
            open(sealed, password)
            fail("tampered backup accepted")
        } catch (_: BackupCrypto.WrongPasswordException) {
        }
    }

    @Test
    fun flippedCiphertextBitIsRejected() {
        val sealed = seal(Random(11).nextBytes(5_000), "p")
        sealed[headerSize + 4 + 100] = (sealed[headerSize + 4 + 100].toInt() xor 0x01).toByte()
        assertRejected(sealed)
    }

    @Test
    fun flippedTagBitIsRejected() {
        val sealed = seal(Random(12).nextBytes(5_000), "p")
        val last = sealed.lastIndex
        sealed[last] = (sealed[last].toInt() xor 0x80).toByte()
        assertRejected(sealed)
    }

    @Test
    fun swappedChunksAreRejected() {
        // Chunk index is part of the nonce and the associated data, so reordering must fail the tag check.
        val sealed = seal(Random(13).nextBytes(2_200_000), "p")
        val first = sealed.copyOfRange(headerSize, headerSize + fullChunkSize)
        val second = sealed.copyOfRange(headerSize + fullChunkSize, headerSize + 2 * fullChunkSize)
        val swapped = sealed.copyOf()
        second.copyInto(swapped, headerSize)
        first.copyInto(swapped, headerSize + fullChunkSize)
        assertRejected(swapped)
    }

    @Test
    fun tamperedSaltOrNoncePrefixIsRejected() {
        val sealed = seal(Random(14).nextBytes(5_000), "p")
        val badSalt = sealed.copyOf().also { it[4 + 1] = (it[4 + 1].toInt() xor 0x01).toByte() }
        val badNonce = sealed.copyOf().also { it[4 + 1 + 16] = (it[4 + 1 + 16].toInt() xor 0x01).toByte() }
        assertRejected(badSalt)
        assertRejected(badNonce)
    }

    @Test
    fun unsupportedVersionAndBadMagicAreRefused() {
        val sealed = seal(Random(15).nextBytes(100), "p")
        val badVersion = sealed.copyOf().also { it[4] = 2 }
        val badMagic = sealed.copyOf().also { it[0] = 'X'.code.toByte() }
        for (bad in listOf(badVersion, badMagic)) {
            try {
                open(bad, "p")
                fail("header accepted")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun everyBackupGetsFreshSaltAndNoncePrefix() {
        val data = Random(16).nextBytes(1_000)
        val a = seal(data, "p")
        val b = seal(data, "p")
        assertFalse(a.copyOfRange(5, headerSize).contentEquals(b.copyOfRange(5, headerSize)))
        assertFalse(a.contentEquals(b))
        assertArrayEquals(data, open(a, "p"))
        assertArrayEquals(data, open(b, "p"))
    }

    @Test
    fun bytesAfterTheFinalChunkAreRejected() {
        val sealed = seal(Random(18).nextBytes(2_000), "p")
        assertRejected(sealed + ByteArray(7) { 0x55 })
        // A second backup spliced onto the first must not be read as a valid one either.
        assertRejected(sealed + seal(Random(19).nextBytes(500), "p"))
    }

    @Test
    fun truncationInsideAChunkOrItsLengthIsWrongPassword() {
        val sealed = seal(Random(20).nextBytes(2_000), "p")
        assertRejected(sealed.copyOf(headerSize + 100)) // inside the ciphertext
        assertRejected(sealed.copyOf(headerSize + 2)) // inside the 4-byte length
        assertRejected(sealed.copyOf(headerSize)) // header only, no chunk at all
    }

    @Test
    fun unicodeAndSpacedPasswordsRoundTrip() {
        val data = Random(17).nextBytes(3_000)
        val password = "kata sandi  ünïcode 密码 🔐"
        assertArrayEquals(data, open(seal(data, password), password))
        assertRejected(seal(data, password), "kata sandi ünïcode 密码 🔐") // single space instead of two
    }
}
