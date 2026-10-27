package com.nextcraft.forge.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedGradlePropertiesTest {
    private val aapt2 = "${RuntimeInstaller.ANDROID_AAPT2_PROPERTY}=${RuntimeInstaller.ANDROID_AAPT2_GUEST_PATH}"

    @Test
    fun aapt2LineUsesTheRealPropertyKey() {
        assertEquals("android.aapt2FromMavenOverride", RuntimeInstaller.ANDROID_AAPT2_PROPERTY)
        assertTrue(aapt2.startsWith("android.aapt2FromMavenOverride=/"))
        // Regression: an unbraced "$RuntimeInstaller.X" template printed Companion@hash.X.
        assertFalse(aapt2.contains("@"))
    }

    @Test
    fun managedLinesAreRecognisedWithEqualsOrColon() {
        assertTrue(isManagedGradleLine(aapt2))
        assertTrue(isManagedGradleLine("  org.gradle.parallel : true"))
        assertFalse(isManagedGradleLine("nextcraft.sideBySideSuffix=x"))
        assertFalse(isManagedGradleLine("# android.aapt2FromMavenOverride=/x"))
    }

    @Test
    fun mergeKeepsUserLinesAndReplacesManagedOnes() {
        val existing = listOf("nextcraft.sideBySideSuffix=x", "org.gradle.parallel=false", "android.aapt2FromMavenOverride=/old")
        val managed = listOf(aapt2, "org.gradle.parallel=true")
        assertEquals(
            listOf("nextcraft.sideBySideSuffix=x", aapt2, "org.gradle.parallel=true"),
            mergeManagedGradleLines(existing, managed),
        )
    }

    @Test
    fun repeatedMergesAreIdempotent() {
        val managed = listOf(aapt2, "org.gradle.workers.max=4")
        val once = mergeManagedGradleLines(listOf("custom=1"), managed)
        val twentyTimes = (1..20).fold(once) { acc, _ -> mergeManagedGradleLines(acc, managed) }
        assertEquals(once, twentyTimes)
    }
}
