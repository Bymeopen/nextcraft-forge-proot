package com.nextcraft.forge.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeMarkersTest {
    private fun rootfs(): File = Files.createTempDirectory("rootfs").toFile()

    @Test
    fun legacyMarkerIsCopiedAndKept() {
        val root = rootfs()
        File(root, ".pocket-rootfs-version").writeText("ubuntu-24.04.5-arm64\n")
        RuntimeMarkers.syncAll(root)
        assertEquals("ubuntu-24.04.5-arm64\n", File(root, ".nextcraft-rootfs-version").readText())
        assertTrue("downgraded builds still find it", File(root, ".pocket-rootfs-version").isFile)
    }

    @Test
    fun newerLegacyMarkerFromBundleWins() {
        val root = rootfs()
        File(root, ".nextcraft-agy-version").writeText("1.0")
        File(root, ".pocket-agy-version").writeText("2.0")
        assertEquals("2.0", RuntimeMarkers.read(File(root, ".nextcraft-agy-version")))
    }

    @Test
    fun syncIsIdempotentAndKeepsNewOnlyMarkers() {
        val root = rootfs()
        File(root, ".nextcraft-claude-version").writeText("3.0")
        RuntimeMarkers.syncAll(root)
        RuntimeMarkers.syncAll(root)
        assertEquals("3.0", File(root, ".nextcraft-claude-version").readText())
        assertFalse(File(root, ".pocket-claude-version").exists())
    }

    @Test
    fun writeAndDeleteCoverBothNames() {
        val root = rootfs()
        val marker = File(root, "root/.nextcraft-rea-version")
        marker.writeMarker("0.9")
        assertEquals("0.9", File(root, "root/.pocket-rea-version").readText())
        marker.deleteMarker()
        assertNull(RuntimeMarkers.read(marker))
        assertFalse(File(root, "root/.pocket-rea-version").exists())
    }

    @Test
    fun legacyGradleInitScriptIsMovedOrDropped() {
        val root = rootfs()
        val legacy = File(root, RuntimeMarkers.LEGACY_GRADLE_INIT_SCRIPT).apply { parentFile.mkdirs(); writeText("init") }
        RuntimeMarkers.syncAll(root)
        assertFalse(legacy.exists())
        assertEquals("init", File(root, RuntimeMarkers.GRADLE_INIT_SCRIPT).readText())
        legacy.writeText("init")
        RuntimeMarkers.syncAll(root)
        assertFalse("Gradle must not load two init scripts", legacy.exists())
    }
}
