package com.nextcraft.forge.ui

/** Pixel comparison used by the screenshot tests; plain ARGB int arrays so it is unit-testable without Android. */
internal object ImageDiff {
    /** Largest per-channel difference that still counts as the same pixel (anti-aliasing noise between runs). */
    const val CHANNEL_TOLERANCE = 8

    /** Share of differing pixels above which a screenshot counts as changed. */
    const val MAX_DIFF_RATIO = 0.005

    /** True when any of red, green or blue differs by more than [tolerance]; alpha is ignored. */
    fun differs(a: Int, b: Int, tolerance: Int = CHANNEL_TOLERANCE): Boolean =
        kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) > tolerance ||
            kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) > tolerance ||
            kotlin.math.abs((a and 0xFF) - (b and 0xFF)) > tolerance

    /** Number of differing pixels; both arrays must have the same size. */
    fun countDifferences(a: IntArray, b: IntArray, tolerance: Int = CHANNEL_TOLERANCE): Int {
        require(a.size == b.size) { "images differ in size" }
        var count = 0
        for (i in a.indices) if (differs(a[i], b[i], tolerance)) count++
        return count
    }
}
