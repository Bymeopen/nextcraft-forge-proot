package com.nextcraft.forge.runtime

import com.nextcraft.forge.ui.readUpTo
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiLevelCompatTest {
    @Test
    fun toHexMatchesLowerCaseHexOfEveryByte() {
        val all = ByteArray(256) { it.toByte() }
        val expected = all.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        assertEquals(expected, WorkspaceCheckpoints.toHex(all))
        assertEquals("", WorkspaceCheckpoints.toHex(ByteArray(0)))
        assertEquals("00ff7f80", WorkspaceCheckpoints.toHex(byteArrayOf(0, -1, 127, -128)))
    }

    @Test
    fun readUpToReturnsFewerBytesAtEndOfStream() {
        assertArrayEquals(byteArrayOf(1, 2), readUpTo(ByteArrayInputStream(byteArrayOf(1, 2)), 4))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), readUpTo(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)), 4))
        assertEquals(0, readUpTo(ByteArrayInputStream(ByteArray(0)), 4).size)
    }

    @Test
    fun readUpToHandlesShortReads() {
        // A stream that hands out one byte per read() call, like some content providers do.
        val slow = object : InputStream() {
            private var i = 0
            override fun read(): Int = if (i < 4) ++i else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (i >= 4) return -1
                b[off] = (++i).toByte()
                return 1
            }
        }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), readUpTo(slow, 4))
    }
}
