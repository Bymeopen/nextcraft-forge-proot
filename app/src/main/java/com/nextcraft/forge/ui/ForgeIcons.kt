package com.nextcraft.forge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One stroke icon set (1.8dp, round caps) so rows, menus and headers share the same weight as the
 * navigation glyphs. Every icon is drawn in a 24-unit box; [ForgeIcon] scales it to [size].
 */
enum class ForgeGlyph {
    FOLDER, FILE, EDIT, TRASH, UPLOAD, DOWNLOAD, PLUS, INFO, LINK, REFRESH, SPARK, TERMINAL, SLIDERS, GLOBE, SHIELD, WARNING, SEARCH, CODE, HISTORY, LOCK, BUILD, MIC
}

@Composable
fun ForgeIcon(
    glyph: ForgeGlyph,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    description: String? = null,
) {
    val m = if (description == null) modifier else modifier.semantics { contentDescription = description }
    Canvas(modifier = m.size(size)) {
        val u = this.size.width / 24f
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, p(x1, y1), p(x2, y2), stroke.width, StrokeCap.Round)
        fun path(build: Path.() -> Unit) = drawPath(Path().apply(build), tint, style = stroke)
        fun rr(x: Float, y: Float, w: Float, h: Float, r: Float) =
            drawRoundRect(tint, p(x, y), Size(w * u, h * u), CornerRadius(r * u), style = stroke)
        when (glyph) {
            ForgeGlyph.FOLDER -> path { moveTo(3 * u, 7 * u); lineTo(3 * u, 19 * u); lineTo(21 * u, 19 * u); lineTo(21 * u, 9 * u); lineTo(12 * u, 9 * u); lineTo(10 * u, 6 * u); lineTo(3 * u, 6 * u); close() }
            ForgeGlyph.FILE -> { path { moveTo(6 * u, 3 * u); lineTo(14 * u, 3 * u); lineTo(19 * u, 8 * u); lineTo(19 * u, 21 * u); lineTo(6 * u, 21 * u); close() }; path { moveTo(14 * u, 3 * u); lineTo(14 * u, 8 * u); lineTo(19 * u, 8 * u) } }
            ForgeGlyph.EDIT -> { path { moveTo(4 * u, 20 * u); lineTo(8 * u, 19 * u); lineTo(19 * u, 8 * u); lineTo(16 * u, 5 * u); lineTo(5 * u, 16 * u); close() }; line(13f, 8f, 16f, 11f) }
            ForgeGlyph.TRASH -> { line(4f, 7f, 20f, 7f); line(9f, 7f, 9f, 4f); line(15f, 7f, 15f, 4f); line(9f, 4f, 15f, 4f); path { moveTo(6 * u, 7 * u); lineTo(7 * u, 20 * u); lineTo(17 * u, 20 * u); lineTo(18 * u, 7 * u) }; line(10f, 11f, 10f, 16f); line(14f, 11f, 14f, 16f) }
            ForgeGlyph.UPLOAD -> { line(12f, 16f, 12f, 4f); path { moveTo(7 * u, 9 * u); lineTo(12 * u, 4 * u); lineTo(17 * u, 9 * u) }; line(4f, 20f, 20f, 20f) }
            ForgeGlyph.DOWNLOAD -> { line(12f, 4f, 12f, 16f); path { moveTo(7 * u, 11 * u); lineTo(12 * u, 16 * u); lineTo(17 * u, 11 * u) }; line(4f, 20f, 20f, 20f) }
            ForgeGlyph.PLUS -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            ForgeGlyph.INFO -> { drawCircle(tint, 9 * u, p(12f, 12f), style = stroke); line(12f, 11f, 12f, 16f); drawCircle(tint, 1.1f * u, p(12f, 8f)) }
            ForgeGlyph.LINK -> { path { moveTo(10 * u, 14 * u); lineTo(14 * u, 10 * u) }; path { moveTo(13 * u, 7 * u); lineTo(15 * u, 5 * u); addArc(androidx.compose.ui.geometry.Rect(p(13f, 3f), Size(8 * u, 8 * u)), 225f, 180f); lineTo(17 * u, 11 * u) }; path { moveTo(11 * u, 17 * u); lineTo(9 * u, 19 * u); addArc(androidx.compose.ui.geometry.Rect(p(3f, 13f), Size(8 * u, 8 * u)), 45f, 180f); lineTo(7 * u, 13 * u) } }
            ForgeGlyph.REFRESH -> { drawArc(tint, -60f, 300f, false, p(4f, 4f), Size(16 * u, 16 * u), style = stroke); path { moveTo(20 * u, 4 * u); lineTo(20 * u, 9 * u); lineTo(15 * u, 9 * u) } }
            ForgeGlyph.SPARK -> path { moveTo(12 * u, 3 * u); lineTo(14 * u, 10 * u); lineTo(21 * u, 12 * u); lineTo(14 * u, 14 * u); lineTo(12 * u, 21 * u); lineTo(10 * u, 14 * u); lineTo(3 * u, 12 * u); lineTo(10 * u, 10 * u); close() }
            ForgeGlyph.TERMINAL -> { rr(3f, 5f, 18f, 14f, 3f); path { moveTo(7 * u, 9 * u); lineTo(10 * u, 12 * u); lineTo(7 * u, 15 * u) }; line(12f, 15f, 16f, 15f) }
            ForgeGlyph.SLIDERS -> { line(4f, 7f, 20f, 7f); line(4f, 12f, 20f, 12f); line(4f, 17f, 20f, 17f); drawCircle(tint, 2 * u, p(9f, 7f)); drawCircle(tint, 2 * u, p(15f, 12f)); drawCircle(tint, 2 * u, p(11f, 17f)) }
            ForgeGlyph.GLOBE -> { drawCircle(tint, 9 * u, p(12f, 12f), style = stroke); line(3f, 12f, 21f, 12f); drawArc(tint, -90f, 180f, false, p(8f, 3f), Size(8 * u, 18 * u), style = stroke); drawArc(tint, 90f, 180f, false, p(8f, 3f), Size(8 * u, 18 * u), style = stroke) }
            ForgeGlyph.SHIELD -> { path { moveTo(12 * u, 3 * u); lineTo(20 * u, 6 * u); lineTo(20 * u, 12 * u); quadraticBezierTo(20 * u, 18 * u, 12 * u, 21 * u); quadraticBezierTo(4 * u, 18 * u, 4 * u, 12 * u); lineTo(4 * u, 6 * u); close() }; path { moveTo(9 * u, 12 * u); lineTo(11 * u, 14 * u); lineTo(15 * u, 10 * u) } }
            ForgeGlyph.WARNING -> { path { moveTo(12 * u, 4 * u); lineTo(21 * u, 20 * u); lineTo(3 * u, 20 * u); close() }; line(12f, 10f, 12f, 14f); drawCircle(tint, 1.1f * u, p(12f, 17f)) }
            ForgeGlyph.SEARCH -> { drawCircle(tint, 6 * u, p(10.5f, 10.5f), style = stroke); line(15f, 15f, 20f, 20f) }
            ForgeGlyph.CODE -> { path { moveTo(8 * u, 7 * u); lineTo(3 * u, 12 * u); lineTo(8 * u, 17 * u) }; path { moveTo(16 * u, 7 * u); lineTo(21 * u, 12 * u); lineTo(16 * u, 17 * u) } }
            ForgeGlyph.HISTORY -> { drawArc(tint, -150f, 300f, false, p(4f, 4f), Size(16 * u, 16 * u), style = stroke); path { moveTo(4 * u, 4 * u); lineTo(4 * u, 9 * u); lineTo(9 * u, 9 * u) }; path { moveTo(12 * u, 8 * u); lineTo(12 * u, 12 * u); lineTo(15 * u, 14 * u) } }
            ForgeGlyph.LOCK -> { rr(5f, 11f, 14f, 10f, 2.5f); drawArc(tint, 180f, 180f, false, p(8f, 4f), Size(8 * u, 12 * u), style = stroke); line(8f, 10f, 8f, 11f); line(16f, 10f, 16f, 11f) }
            ForgeGlyph.MIC -> { rr(9f, 3f, 6f, 11f, 3f); drawArc(tint, 0f, 180f, false, p(5f, 5f), Size(14 * u, 12 * u), style = stroke); line(12f, 17f, 12f, 21f); line(9f, 21f, 15f, 21f) }
            ForgeGlyph.BUILD -> { path { moveTo(14 * u, 5 * u); quadraticBezierTo(18 * u, 4 * u, 19 * u, 8 * u); lineTo(16 * u, 11 * u); lineTo(13 * u, 8 * u); close() }; line(13f, 10f, 5f, 18f); line(5f, 18f, 6f, 19f); line(6f, 19f, 14f, 11f) }
        }
    }
}

/** Convenience: the [ForgeGlyph] for a [ForgeTab] so launchers and rows reuse the nav shapes. */
fun ForgeTab.glyph(): ForgeGlyph = when (this) {
    ForgeTab.PROJECTS -> ForgeGlyph.FOLDER
    ForgeTab.TERMINAL -> ForgeGlyph.TERMINAL
    ForgeTab.AGENT -> ForgeGlyph.SPARK
    ForgeTab.TOOLS, ForgeTab.ANDROID -> ForgeGlyph.BUILD
    ForgeTab.FILES -> ForgeGlyph.FILE
    ForgeTab.PREVIEW -> ForgeGlyph.GLOBE
    ForgeTab.SETTINGS -> ForgeGlyph.SLIDERS
    else -> ForgeGlyph.BUILD
}

@Suppress("unused")
private fun DrawScope.unused() = Unit
