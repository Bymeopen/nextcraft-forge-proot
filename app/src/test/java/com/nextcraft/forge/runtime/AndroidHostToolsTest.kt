package com.nextcraft.forge.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidHostToolsTest {
    @Test
    fun hintOnlyWhenEnabledAndRelevant() {
        val ask = "Test aplikasi ini dan ambil screenshot"
        assertEquals(ask, AndroidHostTools.augment(ask, enabled = false))
        assertTrue(AndroidHostTools.augment(ask, enabled = true).contains("android-shell"))
        assertEquals("Fix the login form", AndroidHostTools.augment("Fix the login form", enabled = true))
    }

    @Test
    fun settingInstallsAndRemovesTheHelpers() {
        val root = Files.createTempDirectory("rootfs").toFile()
        val write: (File, String) -> Unit = { f, body -> f.parentFile.mkdirs(); f.writeText(body) }
        AndroidHostTools.apply(root, enabled = true, write)
        val shell = File(root, "opt/nextcraft/bin/android-shell")
        assertTrue(shell.isFile)
        AndroidHostTools.apply(root, enabled = false, write)
        assertFalse(shell.exists())
    }
}
