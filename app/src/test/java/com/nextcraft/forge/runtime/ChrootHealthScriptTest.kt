package com.nextcraft.forge.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app writes its bundled copy of chroot-health.sh to the phone; it must not drift from the script in the repo. */
class ChrootHealthScriptTest {
    private val bundled = File("src/main/assets/chroot-health.sh")
    private val source = File("../scripts/runtime-bundles/chroot-health.sh")

    @Test
    fun bundledCopy_matchesTheScriptInTheRepo() {
        assertTrue(bundled.isFile && source.isFile)
        assertEquals(source.readText(), bundled.readText())
    }

    @Test
    fun optionalTools_doNotCountAsErrors() {
        val script = bundled.readText()
        assertTrue("gh is optional and must use check_optional", "check_optional \"gh\"" in script)
    }
}
