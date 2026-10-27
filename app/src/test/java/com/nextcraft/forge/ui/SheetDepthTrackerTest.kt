package com.nextcraft.forge.ui

import androidx.compose.runtime.mutableIntStateOf
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetDepthTrackerTest {
    @Test
    fun repeatedShowCountsOnce() {
        val depth = mutableIntStateOf(0)
        val tracker = SheetDepthTracker(depth)
        tracker.set(true)
        tracker.set(true)
        assertEquals(1, depth.intValue)
        tracker.set(false)
        tracker.set(false)
        assertEquals(0, depth.intValue)
    }

    @Test
    fun hiddenSheetNeverBlurs() {
        val depth = mutableIntStateOf(0)
        SheetDepthTracker(depth).set(false)
        assertEquals(0, depth.intValue)
    }

    @Test
    fun countNeverGoesNegativeAndTrackersAreIndependent() {
        val depth = mutableIntStateOf(0)
        val a = SheetDepthTracker(depth)
        val b = SheetDepthTracker(depth)
        a.set(true)
        b.set(true)
        assertEquals(2, depth.intValue)
        a.set(false)
        assertEquals(1, depth.intValue)
        depth.intValue = 0 // external drift
        b.set(false)
        assertEquals(0, depth.intValue)
    }

    @Test
    fun nullDepthIsIgnored() {
        SheetDepthTracker(null).set(true)
    }
}
