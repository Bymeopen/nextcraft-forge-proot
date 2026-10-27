package com.nextcraft.forge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Bottom-bar glyphs from the Forge Navigation Study. Each one has an idle shape and a selected shape;
 * [progress] (0..1) morphs between them so one sliding pill plus one morphing icon tell the whole story.
 * [live] drives the continuous motion (blinking cursor, orbiting satellite); it is ignored under reduce-motion.
 */
@Composable
fun NavGlyph(
    tab: ForgeTab,
    progress: Float,
    live: Boolean,
    tint: Color,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    val animate = live && !reduceMotion
    // Discrete low-power clock (~8 FPS only while live, 0 FPS when idle): avoids 120Hz VSync busy-rendering
    // that previously consumed ~90% CPU on Main/RenderThread and kept Gold cores awake while waiting.
    var t by remember { mutableStateOf(0f) }
    if (animate) {
        LaunchedEffect(Unit) {
            var step = 0
            while (true) {
                delay(125L)
                step = (step + 1) % 12
                t = step / 12f
            }
        }
    } else {
        t = 0f
    }
    Canvas(modifier = modifier.size(size)) {
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (tab) {
            ForgeTab.PROJECTS -> drawTiles(tint, stroke, progress)
            ForgeTab.TERMINAL -> drawPrompt(tint, stroke, progress, blink = animate && t < 0.5f)
            ForgeTab.AGENT -> drawSpark(tint, stroke, progress, orbit = if (animate) t else null)
            ForgeTab.TOOLS, ForgeTab.ANDROID, ForgeTab.TOOLCHAINS, ForgeTab.DIAGNOSTICS, ForgeTab.BUILD_MATRIX,
            ForgeTab.SSH, ForgeTab.CICD, ForgeTab.ROM, ForgeTab.CHECKPOINTS, ForgeTab.CODE_VIEWER, ForgeTab.FILE_BROWSER, ForgeTab.REA, ForgeTab.BACKUP -> drawWrench(tint, stroke, progress)
            ForgeTab.FILES -> drawFile(tint, stroke, progress)
            ForgeTab.PREVIEW -> drawPreview(tint, stroke, progress)
            ForgeTab.SETTINGS -> drawSliders(tint, stroke, progress)
        }
    }
}

/** Four tiles: spread out when idle, pulled together ("collected workspace") when selected. */
private fun DrawScope.drawTiles(tint: Color, stroke: Stroke, p: Float) {
    val w = size.width
    val tile = w * 0.30f
    val gapIdle = w * 0.14f
    val gapSel = w * 0.06f
    val gap = gapIdle + (gapSel - gapIdle) * p
    val total = tile * 2 + gap
    val x0 = (w - total) / 2f
    val y0 = (size.height - total) / 2f
    val r = androidx.compose.ui.geometry.CornerRadius(tile * 0.28f)
    for (ix in 0..1) for (iy in 0..1) {
        drawRoundRect(
            color = tint,
            topLeft = Offset(x0 + ix * (tile + gap), y0 + iy * (tile + gap)),
            size = Size(tile, tile),
            cornerRadius = r,
            style = stroke,
        )
    }
}

/** Terminal prompt: the chevron steps forward when selected; the cursor blinks while a process runs. */
private fun DrawScope.drawPrompt(tint: Color, stroke: Stroke, p: Float, blink: Boolean) {
    val w = size.width
    val h = size.height
    drawRoundRect(
        color = tint,
        topLeft = Offset(w * 0.08f, h * 0.16f),
        size = Size(w * 0.84f, h * 0.68f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.12f),
        style = stroke,
    )
    val shift = w * 0.06f * p
    val chevron = Path().apply {
        moveTo(w * 0.24f + shift, h * 0.36f)
        lineTo(w * 0.38f + shift, h * 0.50f)
        lineTo(w * 0.24f + shift, h * 0.64f)
    }
    drawPath(chevron, tint, style = stroke)
    if (!blink) {
        drawLine(tint, Offset(w * 0.48f + shift, h * 0.64f), Offset(w * 0.70f + shift, h * 0.64f), stroke.width, StrokeCap.Round)
    }
}

/** Four-point spark: rotates 90° into selection; a small satellite orbits while a session is live. */
private fun DrawScope.drawSpark(tint: Color, stroke: Stroke, p: Float, orbit: Float?) {
    val c = center
    val r = size.minDimension * 0.42f
    val pinch = size.minDimension * 0.10f
    rotate(degrees = 90f * p, pivot = c) {
        val spark = Path().apply {
            moveTo(c.x, c.y - r)
            quadraticBezierTo(c.x + pinch, c.y - pinch, c.x + r, c.y)
            quadraticBezierTo(c.x + pinch, c.y + pinch, c.x, c.y + r)
            quadraticBezierTo(c.x - pinch, c.y + pinch, c.x - r, c.y)
            quadraticBezierTo(c.x - pinch, c.y - pinch, c.x, c.y - r)
            close()
        }
        drawPath(spark, tint, style = stroke)
    }
    if (orbit != null) {
        val a = orbit * 2f * Math.PI.toFloat()
        val or = size.minDimension * 0.50f
        drawCircle(tint, radius = size.minDimension * 0.07f, center = Offset(c.x + or * cos(a), c.y + or * sin(a)))
    }
}

/** Wrench: turns 18° when selected, as if tightening. */
private fun DrawScope.drawWrench(tint: Color, stroke: Stroke, p: Float) {
    val w = size.width
    val h = size.height
    rotate(degrees = 18f * p, pivot = center) {
        val head = Path().apply {
            moveTo(w * 0.58f, h * 0.22f)
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(w * 0.52f, h * 0.12f, w * 0.86f, h * 0.46f),
                startAngleDegrees = 200f,
                sweepAngleDegrees = 250f,
                forceMoveTo = false,
            )
        }
        drawPath(head, tint, style = stroke)
        drawLine(tint, Offset(w * 0.56f, h * 0.44f), Offset(w * 0.20f, h * 0.80f), stroke.width, StrokeCap.Round)
    }
}

/** Three sliders whose knobs move to a new position when selected ("adjusting"). */
private fun DrawScope.drawSliders(tint: Color, stroke: Stroke, p: Float) {
    val w = size.width
    val h = size.height
    val rows = listOf(0.26f, 0.50f, 0.74f)
    val idle = listOf(0.34f, 0.66f, 0.46f)
    val sel = listOf(0.62f, 0.38f, 0.70f)
    rows.forEachIndexed { i, fy ->
        val y = h * fy
        drawLine(tint, Offset(w * 0.12f, y), Offset(w * 0.88f, y), stroke.width, StrokeCap.Round)
        val kx = w * (idle[i] + (sel[i] - idle[i]) * p)
        drawCircle(tint, radius = w * 0.075f, center = Offset(kx, y))
    }
}

/** Document sheet with a folded corner; the text lines slide in when selected. */
private fun DrawScope.drawFile(tint: Color, stroke: Stroke, p: Float) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.25f, h * 0.12f)
        lineTo(w * 0.62f, h * 0.12f)
        lineTo(w * 0.78f, h * 0.28f)
        lineTo(w * 0.78f, h * 0.88f)
        lineTo(w * 0.25f, h * 0.88f)
        close()
    }
    drawPath(path, tint, style = stroke)
    drawLine(tint, Offset(w * 0.62f, h * 0.12f), Offset(w * 0.62f, h * 0.28f), stroke.width, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.78f, h * 0.28f), Offset(w * 0.62f, h * 0.28f), stroke.width, StrokeCap.Round)
    val reach = w * (0.30f + 0.18f * p)
    drawLine(tint, Offset(w * 0.37f, h * 0.52f), Offset(w * 0.37f + reach, h * 0.52f), stroke.width, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.37f, h * 0.68f), Offset(w * 0.37f + reach * 0.7f, h * 0.68f), stroke.width, StrokeCap.Round)
}

/** Browser window: title bar dot and a play triangle that fills in when selected. */
private fun DrawScope.drawPreview(tint: Color, stroke: Stroke, p: Float) {
    val w = size.width
    val h = size.height
    drawRoundRect(
        color = tint,
        topLeft = Offset(w * 0.12f, h * 0.18f),
        size = androidx.compose.ui.geometry.Size(w * 0.76f, h * 0.64f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f),
        style = stroke,
    )
    drawLine(tint, Offset(w * 0.12f, h * 0.36f), Offset(w * 0.88f, h * 0.36f), stroke.width, StrokeCap.Round)
    drawCircle(tint, radius = w * 0.035f, center = Offset(w * 0.24f, h * 0.27f))
    val tri = Path().apply {
        moveTo(w * 0.42f, h * 0.46f)
        lineTo(w * 0.64f, h * 0.59f)
        lineTo(w * 0.42f, h * 0.72f)
        close()
    }
    drawPath(tri, tint.copy(alpha = (0.25f + 0.75f * p).coerceIn(0f, 1f)), style = if (p > 0.5f) androidx.compose.ui.graphics.drawscope.Fill else stroke)
}
