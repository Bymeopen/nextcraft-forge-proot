package com.nextcraft.forge.ui

import com.nextcraft.forge.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTextTest {
    @Test
    fun res_keepsIdAndArgsInOrder() {
        val t = UiText.res(R.string.msg_gh_cloned, "owner/repo", "my-workspace") as UiText.Res
        assertEquals(R.string.msg_gh_cloned, t.id)
        assertEquals(listOf("owner/repo", "my-workspace"), t.args)
    }

    @Test
    fun raw_isPassedThroughUntouched() {
        val t = UiText.Raw("Error 429: rate limited")
        assertEquals("Error 429: rate limited", t.asString(null as android.content.Context?))
    }

    /** Status/banner text must never be assigned a bare String literal again. */
    @Test
    fun viewModel_statusFields_useUiText() {
        val src = File("src/main/java/com/nextcraft/forge/ui")
            .listFiles { f -> f.name.startsWith("MainViewModel") && f.extension == "kt" }
            .orEmpty()
            .joinToString("\n") { it.readText() }
        val bare = Regex("""(bannerMessage|githubStatusMessage|apiStatusMessage|activeStatusLine)\s*=\s*"[^"\n]*"""")
            .findAll(src).map { it.value }.toList()
        assertTrue("bare String literals assigned to UiText fields:\n" + bare.joinToString("\n"), bare.isEmpty())
    }
}
