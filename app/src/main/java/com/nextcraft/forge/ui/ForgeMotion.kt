@file:OptIn(ExperimentalAnimationApi::class)

package com.nextcraft.forge.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.with
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat

/**
 * One motion vocabulary for the whole app. Durations and springs live here so every screen moves the
 * same way, and every helper collapses to a snap when the user has disabled animations.
 */
object ForgeMotion {
    const val SCREEN_MS = 280
    const val FADE_MS = 180
    const val EXPAND_MS = 220

    /** Pill, send button, pressed scale: slight overshoot, settles fast. */
    fun <T> springSnappy(): androidx.compose.animation.core.SpringSpec<T> =
        spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium)

    /** Sheets and morphing shapes (Expressive): a little more overshoot than [springSnappy], still settles quickly. */
    fun <T> springExpressive(): androidx.compose.animation.core.SpringSpec<T> =
        spring(dampingRatio = 0.62f, stiffness = 380f)

    /** Smooth critically-damped physics spring for spatial screen and layout transitions without wobble. */
    fun <T> springFluid(): androidx.compose.animation.core.SpringSpec<T> =
        spring(dampingRatio = 0.84f, stiffness = 400f)

    /** Screen push: fluid spring slide 22% + subtle depth scale (96% -> 100%) + fade in; leaving screen drifts 10% + scales to 97%. */
    fun screenTransition(forward: Boolean, reduceMotion: Boolean): ContentTransform {
        if (reduceMotion) return fadeIn(snap()) with fadeOut(snap())
        val dir = if (forward) 1 else -1
        val offsetSpring = spring<IntOffset>(dampingRatio = 0.84f, stiffness = 400f)
        val scaleSpring = spring<Float>(dampingRatio = 0.86f, stiffness = 420f)
        return ContentTransform(
            targetContentEnter = slideInHorizontally(offsetSpring) { (it * 0.22f * dir).toInt() } +
                scaleIn(scaleSpring, initialScale = 0.96f) +
                fadeIn(tween(FADE_MS, easing = FastOutSlowInEasing)),
            initialContentExit = slideOutHorizontally(offsetSpring) { (-it * 0.10f * dir).toInt() } +
                scaleOut(scaleSpring, targetScale = 0.97f) +
                fadeOut(tween(FADE_MS * 2 / 3, easing = FastOutSlowInEasing)),
            sizeTransform = SizeTransform(clip = false),
        )
    }

    /** Sibling tab / sub-view switch: directional 8% horizontal drift + subtle 98.5% scale + fade so tabs never feel stiff. */
    fun tabTransition(forward: Boolean, reduceMotion: Boolean): ContentTransform {
        if (reduceMotion) return fadeIn(snap()) with fadeOut(snap())
        val dir = if (forward) 1 else -1
        val offsetSpring = spring<IntOffset>(dampingRatio = 0.86f, stiffness = 460f)
        val scaleSpring = spring<Float>(dampingRatio = 0.88f, stiffness = 480f)
        return ContentTransform(
            targetContentEnter = slideInHorizontally(offsetSpring) { (it * 0.08f * dir).toInt() } +
                scaleIn(scaleSpring, initialScale = 0.985f) +
                fadeIn(tween(FADE_MS, easing = FastOutSlowInEasing)),
            initialContentExit = slideOutHorizontally(offsetSpring) { (-it * 0.06f * dir).toInt() } +
                scaleOut(scaleSpring, targetScale = 0.985f) +
                fadeOut(tween(FADE_MS / 2, easing = FastOutSlowInEasing)),
            sizeTransform = SizeTransform(clip = false),
        )
    }

    /** Tab switch: content cross-fades with subtle directional drift. */
    fun crossfade(reduceMotion: Boolean, forward: Boolean = true): ContentTransform =
        tabTransition(forward = forward, reduceMotion = reduceMotion)

    /** Smooth spring vertical expand + fade for collapsible cards, banners, and disclosure sections. */
    fun expandEnter(reduceMotion: Boolean = false): EnterTransition =
        if (reduceMotion) EnterTransition.None
        else expandVertically(
            animationSpec = spring<IntSize>(dampingRatio = 0.82f, stiffness = 420f),
            expandFrom = Alignment.Top,
        ) + fadeIn(tween(FADE_MS, easing = FastOutSlowInEasing))

    /** Smooth spring vertical shrink + fade for collapsible cards, banners, and disclosure sections. */
    fun shrinkExit(reduceMotion: Boolean = false): ExitTransition =
        if (reduceMotion) ExitTransition.None
        else shrinkVertically(
            animationSpec = spring<IntSize>(dampingRatio = 0.85f, stiffness = 460f),
            shrinkTowards = Alignment.Top,
        ) + fadeOut(tween(FADE_MS * 2 / 3, easing = FastOutSlowInEasing))
}

/**
 * Hosts a screen keyed by [key]; a new key pushes (slide from the right) when [forward], pops otherwise,
 * and uses a directional sibling tab transition when [slide] is false.
 */
@Composable
fun <K> ForgeScreenHost(
    key: K,
    forward: Boolean,
    slide: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (K) -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    AnimatedContent(
        targetState = key,
        modifier = modifier,
        transitionSpec = {
            if (slide) ForgeMotion.screenTransition(forward, reduceMotion)
            else ForgeMotion.tabTransition(forward, reduceMotion)
        },
        label = "forgeScreenHost",
    ) { state -> content(state) }
}

/** Scales the element to 96% while pressed; pair it with the clickable that owns [interactionSource]. */
fun Modifier.forgePressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.96f,
    haptic: Boolean = false,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    if (haptic) {
        // One light tick on touch-down, so every pill and button answers the finger the same way (the system's
        // touch-feedback setting still applies).
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        LaunchedEffect(pressed) { if (pressed) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) }
    }
    val reduceMotion = rememberReduceMotion()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = if (reduceMotion) snap() else ForgeMotion.springSnappy(),
        label = "pressScale",
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Dialogs and menus scale in from 92% with a fade on first composition instead of popping. */
fun Modifier.forgePopIn(): Modifier = composed {
    val reduceMotion = rememberReduceMotion()
    var shown by remember { mutableStateOf(reduceMotion) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else tween(ForgeMotion.EXPAND_MS, easing = FastOutSlowInEasing),
        label = "popIn",
    )
    graphicsLayer {
        alpha = progress
        scaleX = 0.92f + 0.08f * progress
        scaleY = 0.92f + 0.08f * progress
    }
}

/** 0..1 blink clock for the streaming caret; constant 1 under reduce-motion. */
@Composable
fun rememberBlink(): Float {
    val reduceMotion = rememberReduceMotion()
    if (reduceMotion) return 1f
    // Two states only, so toggle them on a timer: an infinite transition would recompose on every frame.
    var on by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(450)
            on = !on
        }
    }
    return if (on) 1f else 0.15f
}

/* ───────────────────────── Type and spacing scale ───────────────────────── */

/** Fixed type scale: five sizes, nothing in between, so every screen reads with the same rhythm. */
object ForgeType {
    val screenTitle = 24.sp     // one per screen, SemiBold
    val cardTitle = 17.sp       // card headings, SemiBold
    val rowTitle = 16.sp        // list rows, Medium
    val body = 15.sp            // prose, Normal
    val control = 14.sp         // buttons, inline status and helper text
    val secondary = 13.sp       // subtitles and metadata, muted
    /** Bottom-bar label when five tabs share a 360dp phone: "Pengaturan" must stay inside its pill. */
    val navCompact = 10.5.sp
    val caption = 12.sp         // section labels (uppercase, tracked) and badges
    val sectionTracking = 0.6.sp
}

/** Width breakpoints (Material window size classes): >= [expanded] shows a project as two panes. */
object ForgeBreakpoint {
    const val EXPANDED_DP = 840
    const val MIN_PANE_HEIGHT_DP = 480
    val readableMaxWidth = 720.dp
}

/** True on tablets, foldables and landscape tablets where chat and workspace fit side by side. */
@Composable
fun rememberIsExpanded(): Boolean {
    val c = LocalConfiguration.current
    // A 6.9in phone in landscape is ~930dp wide but only ~430dp tall: two panes would leave neither one usable.
    return c.screenWidthDp >= ForgeBreakpoint.EXPANDED_DP && c.screenHeightDp >= ForgeBreakpoint.MIN_PANE_HEIGHT_DP
}

/** Landscape phones: every vertical dp counts, so bars and headers shrink. */
@Composable
fun rememberCompactHeight(): Boolean = LocalConfiguration.current.screenHeightDp < ForgeBreakpoint.MIN_PANE_HEIGHT_DP

/** 4dp grid. Screen edge 20, card edge 14, card inner 16, row 56, section gap 24, label-to-card 8. */
object ForgeSpace {
    val screen = 20.dp
    val card = 14.dp
    val cardInner = 16.dp
    val row = 56.dp
    val minTouch = 44.dp
    val section = 24.dp
    val labelToCard = 8.dp
    val header = 64.dp
}
