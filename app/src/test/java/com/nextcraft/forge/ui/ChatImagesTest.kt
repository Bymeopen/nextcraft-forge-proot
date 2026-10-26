package com.nextcraft.forge.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatImagesTest {
    @Test
    fun markdownImageLine() {
        assertEquals(MarkdownImageRef("chart", "out/chart.png"), imageRefOfLine("![chart](out/chart.png)"))
        assertEquals(MarkdownImageRef("", "a b.png".replace(" ", "")), imageRefOfLine("![](ab.png \"title\")"))
    }

    @Test
    fun bareImagePathLine() {
        assertEquals(MarkdownImageRef("", "assets/logo.PNG"), imageRefOfLine("  `assets/logo.PNG`  "))
        assertEquals(MarkdownImageRef("", "https://x.dev/a.jpg?v=1"), imageRefOfLine("https://x.dev/a.jpg?v=1"))
    }

    @Test
    fun ordinaryTextIsNotAnImage() {
        assertNull(imageRefOfLine("See the picture ![x](a.png) above"))
        assertNull(imageRefOfLine("notes.txt"))
        assertNull(imageRefOfLine("a sentence ending in photo.png"))
    }

    @Test
    fun sourcesAreClassified() {
        assertEquals(ChatImageSource.Workspace("out/a.png"), classifyImageSource("/workspace/demo/out/a.png", "demo"))
        assertEquals(ChatImageSource.Workspace("a.png"), classifyImageSource("./a.png", "demo"))
        assertEquals(ChatImageSource.Workspace("a.png"), classifyImageSource("file:///workspace/demo/a.png", "demo"))
        assertNull(classifyImageSource("/etc/passwd.png", "demo"))
        assertEquals(ChatImageSource.Remote("https://x.dev/a.png"), classifyImageSource("https://x.dev/a.png", "demo"))
        assertEquals(ChatImageSource.Data("QUJD"), classifyImageSource("data:image/png;base64,QUJD", "demo"))
        assertNull(classifyImageSource("data:image/svg+xml,<svg/>", "demo"))
    }
}
