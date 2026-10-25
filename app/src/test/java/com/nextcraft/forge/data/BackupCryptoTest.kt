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
}
