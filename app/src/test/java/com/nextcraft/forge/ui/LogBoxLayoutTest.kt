package com.nextcraft.forge.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LogBox scrolls its text vertically and is placed inside screens that scroll vertically. Compose throws
 * "Vertically scrollable component was measured with an infinity maximum height" when such a child has no upper
 * height bound, which force-closed Diagnostics, Build Matrix, SSH and ROM Studio when their log was empty.
 */
class LogBoxLayoutTest {
    @Test
    fun logBox_hasABoundedHeight() {
        val source = File("src/main/java/com/nextcraft/forge/ui/ToolsKit.kt").readText()
        // Comments may mention heightIn to explain why it is banned; only code counts.
        val body = source.substringAfter("internal fun LogBox(").substringBefore("\n}\n")
            .lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
        assertTrue("LogBox must use a fixed .height(...)", ".height(" in body)
        assertTrue("heightIn(...) leaves the max height unbounded inside a scrolling screen", "heightIn(" !in body)
    }
}
