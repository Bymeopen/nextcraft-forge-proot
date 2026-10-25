package com.nextcraft.forge.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshPatchTest {
    private val before = "await link(tmp, finalPath);"
    private val after = "await copyFile(tmp, finalPath, 1);"

    @Test
    fun patchesTheOldImportOrder() {
        val src = "import { link, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";\n$before\n"
        val out = patchDshSource(src, before, after)!!
        assertTrue(out.contains("copyFile, link, mkdir"))
        assertTrue(out.contains(after))
    }

    @Test
    fun patchesTheNewImportList() {
        // DeepSeek 0.2.x added lstat and dropped realpath.
        val src = "import { link, lstat, mkdir, mkdtemp, open, readFile, readdir, rm, stat, truncate } from \"node:fs/promises\";\n\t\t\t$before\n"
        val out = patchDshSource(src, before, after)!!
        assertTrue(out.contains("import { copyFile, link, lstat,"))
        assertTrue(out.contains(after))
    }

    @Test
    fun alreadyPatchedIsLeftAlone() {
        val src = "import { copyFile, link } from \"node:fs/promises\";\n$after\n"
        assertEquals(src, patchDshSource(src, before, after))
    }

    @Test
    fun unknownSourceIsRejected() {
        assertNull(patchDshSource("import { link } from \"node:fs/promises\";\nawait other();\n", before, after))
        assertNull(patchDshSource(before, before, after))
    }

    @Test
    fun olderInstallsStaySupported() {
        assertTrue(isSupportedDshVersion("0.1.2-rc.1"))
        assertTrue(isSupportedDshVersion(RuntimeInstaller.DSH_VERSION))
    }
}
