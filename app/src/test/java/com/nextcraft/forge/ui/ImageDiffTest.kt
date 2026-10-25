package com.nextcraft.forge.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDiffTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun identicalImages_haveNoDifferences() {
        val image = intArrayOf(argb(255, 10, 20, 30), argb(255, 200, 100, 50))
        assertEquals(0, ImageDiff.countDifferences(image, image.copyOf()))
    }

    @Test
    fun smallChannelNoise_isTolerated_butRealChangesAreNot() {
        val base = argb(255, 100, 100, 100)
        assertFalse(ImageDiff.differs(base, argb(255, 106, 94, 100)))
        assertTrue(ImageDiff.differs(base, argb(255, 120, 100, 100)))
        assertTrue(ImageDiff.differs(base, argb(255, 100, 100, 130)))
    }

    @Test
    fun alphaIsIgnored() {
        assertFalse(ImageDiff.differs(argb(255, 5, 5, 5), argb(0, 5, 5, 5)))
    }

    @Test
    fun countsOnlyTheDifferingPixels() {
        val a = intArrayOf(argb(255, 0, 0, 0), argb(255, 0, 0, 0), argb(255, 0, 0, 0))
        val b = intArrayOf(argb(255, 0, 0, 0), argb(255, 255, 0, 0), argb(255, 0, 40, 0))
        assertEquals(2, ImageDiff.countDifferences(a, b))
    }
}
