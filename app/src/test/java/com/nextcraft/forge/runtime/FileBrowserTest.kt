package com.nextcraft.forge.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileBrowserTest {
    @Test
    fun parseLsLong_readsFilesFoldersAndLinks() {
        val output = """
            total 24
            drwx------ 3 root root 4096 2026-10-22 03:01 .claude
            -rw-r--r-- 1 root root  220 2026-10-22 03:00 .bashrc
            -rw-r--r-- 1 root root   12 2026-10-22 03:00 name with spaces.txt
            lrwxrwxrwx 1 root root    9 2026-10-22 03:00 current -> /tmp/work
            drwxr-xr-x 2 root root 4096 Oct 22 03:02 old dir
            crw-rw-rw- 1 root root 1, 3 2026-10-22 03:00 null
        """.trimIndent()
        val items = FileBrowser.parseLsLong(output)
        assertEquals(listOf(".claude", ".bashrc", "name with spaces.txt", "current", "old dir"), items.map { it.name })
        assertTrue(items[0].isDirectory)
        assertEquals(220L, items[1].sizeBytes)
        assertTrue(items[3].isLink && !items[3].isDirectory)
        assertTrue(items[4].isDirectory)
    }

    @Test
    fun decode_detectsBinaryAndTruncation() {
        assertTrue(FileBrowser.decode(byteArrayOf(0x50, 0x4B, 0, 1), 4) is BrowserRead.Binary)
        val text = FileBrowser.decode("halo".toByteArray(), 4) as BrowserRead.Text
        assertEquals("halo", text.text)
        assertTrue(!text.truncated)
        val cut = FileBrowser.decode("abcdef".toByteArray(), 600) as BrowserRead.Text
        assertTrue(cut.truncated)
    }

    @Test
    fun outsideAppData_isRefused() {
        assertEquals(BrowserListing.Denied(BrowserDenial.OUTSIDE_APP_DATA), FileBrowser.list(File("/data")))
        assertEquals(BrowserRead.Denied(BrowserDenial.OUTSIDE_APP_DATA), FileBrowser.read(File("/etc/hosts")))
    }
}
