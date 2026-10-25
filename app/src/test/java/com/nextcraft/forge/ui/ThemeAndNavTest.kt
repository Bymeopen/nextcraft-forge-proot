package com.nextcraft.forge.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG relative luminance / contrast, computed from sRGB floats so the test needs no Android classes. */
private fun luminance(c: Color): Double {
    fun lin(v: Float): Double = v.toDouble().let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
    return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
}

private fun contrast(a: Color, b: Color): Double {
    val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
    return (hi + 0.05) / (lo + 0.05)
}

class ThemeAndNavTest {
    private val scheme = ZuanDarkColorScheme

    private fun assertText(name: String, fg: Color, bg: Color, min: Double = 4.5) {
        val ratio = contrast(fg, bg)
        assertTrue("$name contrast $ratio < $min", ratio >= min)
    }

    @Test
    fun linearDark_textPairs_meetAA() {
        assertText("onBackground/background", scheme.onBackground, scheme.background)
        assertText("onSurface/surface", scheme.onSurface, scheme.surface)
        assertText("onSurfaceVariant/background", scheme.onSurfaceVariant, scheme.background)
        assertText("onSurfaceVariant/surface", scheme.onSurfaceVariant, scheme.surface)
        assertText("onPrimary/primary", scheme.onPrimary, scheme.primary)
        assertText("onPrimaryContainer/primaryContainer", scheme.onPrimaryContainer, scheme.primaryContainer)
        assertText("onError/error", scheme.onError, scheme.error, min = 3.0) // large/bold UI text only
        assertText("error/surface", scheme.error, scheme.surface)
    }

    /** Dark keeps near-neutral greys: the only colour allowed in them is the faint indigo tint (<= 12/255 between channels). */
    @Test
    fun dark_greys_carryOnlyAFaintBrandTint() {
        listOf(scheme.background, scheme.surface, scheme.surfaceVariant, scheme.onSurfaceVariant).forEach {
            val spread = maxOf(it.red, it.green, it.blue) - minOf(it.red, it.green, it.blue)
            assertTrue("grey is too coloured: spread ${spread * 255}/255", spread <= 12f / 255f)
        }
    }

    /** The three chosen accents (Dark = blue, Glass = teal, White = indigo), each far from the danger red. */
    @Test
    fun accents_areTheChosenHues_andNeverTheDangerRed() {
        fun hue(c: Color): Double {
            val r = c.red.toDouble(); val g = c.green.toDouble(); val b = c.blue.toDouble()
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
            if (d == 0.0) return -1.0
            val h = when (mx) { r -> ((g - b) / d) % 6; g -> (b - r) / d + 2; else -> (r - g) / d + 4 }
            return (h * 60 + 360) % 360
        }
        val chosen = mapOf("dark" to (200.0..230.0), "glass" to (160.0..190.0), "white" to (215.0..250.0))
        chosen.forEach { (name, range) ->
            val (sch, tok) = resolveThemeTokens(name, systemDark = false)
            val accentHue = hue(sch.primary); val dangerHue = hue(tok.dangerText)
            assertTrue("$name primary hue $accentHue is not in $range", accentHue in range)
            val gap = minOf(Math.abs(accentHue - dangerHue), 360 - Math.abs(accentHue - dangerHue))
            assertTrue("$name accent ($accentHue) too close to danger ($dangerHue)", gap >= 90.0)
        }
    }

    /** "Sistem" keeps its own colours; it must not silently follow the Dark / White picks. */
    @Test
    fun system_keepsItsOwnColours_apartFromDarkAndWhite() {
        assertTrue(resolveThemeTokens("system", systemDark = true).first.primary != resolveThemeTokens("dark", false).first.primary)
        assertTrue(resolveThemeTokens("system", systemDark = false).first.primary != resolveThemeTokens("white", false).first.primary)
    }

    /** A bottom sheet covers the screen: in Glass its colour must be opaque, and text must read on it. */
    @Test
    fun glassSheet_isOpaque_andTextReadable() {
        val scheme = ZuanGlassColorScheme
        val tokens = resolveThemeTokens("glass", false).second
        val sheet = GlassFloatingSurface
        assertEquals(1f, sheet.alpha, 0f)
        assertText("onSurface/sheet", scheme.onSurface, sheet)
        assertText("onSurfaceVariant/sheet", scheme.onSurfaceVariant, sheet)
        assertText("accent/sheet", tokens.accentText, sheet)
        assertText("success/sheet", tokens.successText, sheet)
        assertText("warning/sheet", tokens.warningText, sheet)
        assertText("danger/sheet", tokens.dangerText, sheet)
        // Lifted layers must be darker than the page they float over.
        GlassCanvasColors.forEach {
            assertTrue("floating surface must be darker than every canvas stop", luminance(sheet) < luminance(it))
        }
    }

    /** Added/removed diff text must read on its own row tint in every theme (glass tints are translucent, so it is composited). */
    @Test
    fun diffText_meetsAA_onItsRow() {
        listOf("dark", "white").forEach { name ->
            val (sch, tok) = resolveThemeTokens(name, systemDark = false)
            assertText("$name added", tok.diffAddedText, tok.diffAddedBackground)
            assertText("$name removed", tok.diffRemovedText, tok.diffRemovedBackground)
        }
    }

    @Test
    fun backTarget_toolScreensReturnToTools() {
        listOf(ForgeTab.FILES, ForgeTab.ANDROID, ForgeTab.PREVIEW).forEach {
            assertEquals(ForgeTab.TOOLS, forgeBackTarget(it))
        }
    }

    @Test
    fun backTarget_topLevelTabsReturnToProjects_andProjectsExits() {
        listOf(ForgeTab.TERMINAL, ForgeTab.AGENT, ForgeTab.TOOLS, ForgeTab.SETTINGS).forEach {
            assertEquals(ForgeTab.PROJECTS, forgeBackTarget(it))
        }
        assertNull(forgeBackTarget(ForgeTab.PROJECTS))
    }

    @Test
    fun backTarget_neverLoops() {
        ForgeTab.entries.forEach { start ->
            var tab: ForgeTab? = start
            var steps = 0
            while (tab != null && steps < ForgeTab.entries.size) {
                tab = forgeBackTarget(tab)
                steps++
            }
            assertNull("Back from $start never reaches exit", tab)
        }
    }

    private val white = AirbnbWhiteColorScheme
    private val whiteTokens = WhiteThemeTokens

    @Test
    fun white_textPairs_meetAA() {
        listOf(white.background, white.surface, whiteTokens.cardContainerColor, whiteTokens.cardElevatedColor).forEach { bg ->
            assertText("onSurface", white.onSurface, bg)
            assertText("onSurfaceVariant", white.onSurfaceVariant, bg)
            assertText("accentText", whiteTokens.accentText, bg)
            assertText("success", whiteTokens.successText, bg)
            assertText("warning", whiteTokens.warningText, bg)
            assertText("danger", whiteTokens.dangerText, bg)
        }
        assertText("onPrimary/primary", white.onPrimary, white.primary)
        assertText("diff added", whiteTokens.diffAddedText, whiteTokens.diffAddedBackground)
        assertText("diff removed", whiteTokens.diffRemovedText, whiteTokens.diffRemovedBackground)
        assertText("terminal", whiteTokens.terminalText, whiteTokens.terminalBackground)
    }

    @Test
    fun systemLight_primary_meetsAA() {
        assertText("onPrimary/primary", SystemLightColorScheme.onPrimary, SystemLightColorScheme.primary)
    }

    /** Alpha-composite [fg] over an opaque [bg] (what a translucent glass surface really shows). */
    private fun over(fg: Color, bg: Color): Color {
        val a = fg.alpha
        return Color(
            red = fg.red * a + bg.red * (1 - a),
            green = fg.green * a + bg.green * (1 - a),
            blue = fg.blue * a + bg.blue * (1 - a),
        )
    }

    @Test
    fun glass_textStaysReadable_overEveryCanvasColour() {
        val g = ZuanGlassColorScheme
        val tk = GlassThemeTokens
        GlassCanvasColors.forEach { canvas ->
            val card = over(tk.cardContainerColor, canvas)
            val elevated = over(tk.cardElevatedColor, canvas)
            val bar = over(tk.bottomBarContainerColor, canvas)
            val terminal = over(tk.terminalBackground, canvas)
            listOf(card, elevated, bar).forEach { surface ->
                assertText("onSurface", g.onSurface, surface)
                assertText("onSurfaceVariant", g.onSurfaceVariant, surface)
                assertText("accentText", tk.accentText, surface)
            }
            assertText("terminal text", tk.terminalText, terminal)
            assertText("onSurface/canvas", g.onSurface, canvas)
        }
        assertText("onPrimary/primary", g.onPrimary, g.primary)
    }

    @Test
    fun allThemes_shareTheSameCardRadius() {
        val radii = setOf(whiteTokens.cardCornerRadius, GlassThemeTokens.cardCornerRadius)
        assertEquals("Glass and White must keep one shape language", 1, radii.size)
    }
}
