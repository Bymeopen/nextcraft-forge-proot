package com.nextcraft.forge.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Glass card tokens are translucent; Color.copy(alpha = x) replaces that alpha and paints them bright white. */
class TranslucentTokenGuardTest {
    @Test
    fun cardTokens_neverUseCopyAlpha() {
        val dir = File("src/main/java/com/nextcraft/forge/ui")
        assertTrue(dir.isDirectory)
        val offenders = dir.listFiles { f -> f.extension == "kt" }!!.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l ->
                if (Regex("""card(Container|Elevated)Color\.copy\(alpha""").containsMatchIn(l)) "${f.name}:${i + 1}" else null
            }
        }
        assertTrue("use withAlphaScaled instead:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun withAlphaScaled_multipliesAlpha() {
        val c = androidx.compose.ui.graphics.Color(1f, 1f, 1f, 0.14f).withAlphaScaled(0.5f)
        assertEquals(0.07f, c.alpha, 0.01f)
    }
}
