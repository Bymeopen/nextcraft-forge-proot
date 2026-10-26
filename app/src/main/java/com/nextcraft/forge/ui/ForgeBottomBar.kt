@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.AndroidTemplate
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.runtime.RuntimeSetupStatus
import androidx.compose.ui.res.stringResource
import com.nextcraft.forge.R
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding

private const val NAV_MAX_FONT_SCALE = 1.3f

// Bottom inset at or below this is the gesture-navigation handle strip; the bar overlaps its top GESTURE_INSET_TRIM.
private val GESTURE_INSET_MAX = 34.dp
private val GESTURE_INSET_TRIM = 10.dp

internal data class NavEntry(val tab: ForgeTab, @StringRes val label: Int)

/** Global bar: five destinations; Terminal also appears inside the project bar. */
internal val navEntries = listOf(
    NavEntry(ForgeTab.PROJECTS, R.string.nav_projects),
    NavEntry(ForgeTab.TERMINAL, R.string.nav_terminal),
    NavEntry(ForgeTab.AGENT, R.string.nav_agent),
    NavEntry(ForgeTab.TOOLS, R.string.nav_tools),
    NavEntry(ForgeTab.SETTINGS, R.string.nav_settings),
)

/** In-project bar. The AGENT key draws the chat glyph; the others map 1:1 to ProjectPane. */
internal val projectEntries = listOf(
    NavEntry(ForgeTab.AGENT, R.string.nav_chat),
    NavEntry(ForgeTab.FILES, R.string.nav_files),
    NavEntry(ForgeTab.TERMINAL, R.string.nav_terminal),
    NavEntry(ForgeTab.PREVIEW, R.string.nav_preview),
)

// Workspace screens that benefit from the full width of a tablet.
internal val fullWidthTabs = setOf(ForgeTab.TERMINAL, ForgeTab.FILES, ForgeTab.PREVIEW, ForgeTab.ANDROID)

// Screens reachable from the Tools launcher keep the Tools tab highlighted.
private val toolsGroup = setOf(
    ForgeTab.TOOLS, ForgeTab.FILES, ForgeTab.ANDROID, ForgeTab.PREVIEW,
    ForgeTab.TOOLCHAINS, ForgeTab.DIAGNOSTICS, ForgeTab.BUILD_MATRIX, ForgeTab.SSH, ForgeTab.CICD, ForgeTab.ROM, ForgeTab.CHECKPOINTS, ForgeTab.CODE_VIEWER, ForgeTab.FILE_BROWSER, ForgeTab.REA, ForgeTab.BACKUP,
)

private fun ForgeTab.navIndex(): Int {
    val tab = if (this in toolsGroup) ForgeTab.TOOLS else this
    return navEntries.indexOfFirst { it.tab == tab }.coerceAtLeast(0)
}

/**
 * Five labelled tabs with ONE physical selection pill that slides between them (420 ms, slight overshoot).
 * Material differs per theme: Dark/White draw a full-width bar fused to the screen, Glass floats a capsule.
 * With animations disabled the pill snaps and only colours change.
 */
@Composable
internal fun ForgeBottomBar(
    entries: List<NavEntry>,
    selectedTab: ForgeTab,
    changedCount: Int,
    agentRunning: Boolean,
    terminalBusy: Boolean,
    onSelectTab: (ForgeTab) -> Unit,
    /** Long-press on a tab (opens the command palette); null = no long-press action. */
    onLongPressTab: (() -> Unit)? = null,
) {
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    val reduceMotion = rememberReduceMotion()
    val haptics = LocalHapticFeedback.current
    val tap = { tab: ForgeTab -> haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSelectTab(tab) }
    val longPressLabel = onLongPressTab?.let { stringResource(R.string.palette_title) }
    val onLongPress = onLongPressTab?.let { open -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); open() } }
    // Global entries collapse the Tools group onto Tools; the project bar matches its keys directly.
    val selectedIndex = entries.indexOfFirst { it.tab == selectedTab }.takeIf { it >= 0 } ?: selectedTab.navIndex()
    val pillOffset by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.72f, stiffness = 380f),
        label = "navPill",
    )

    val floating = tokens.isGlass
    val outerPadding = if (floating) PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp) else PaddingValues(0.dp)
    val shape = if (floating) RoundedCornerShape(100) else RoundedCornerShape(0.dp)
    val pillFill = if (tokens.isWhite) scheme.primary.copy(alpha = 0.10f) else scheme.primary.copy(alpha = 0.18f)
    val pillEdge = scheme.primary.copy(alpha = 0.30f)
    val pillShape = RoundedCornerShape(if (floating) 100 else 22)

    // Glass on Android 12+: the bar blurs and refracts the screen behind it; otherwise the translucent surface below.
    val backdrop = LocalForgeBackdrop.current.takeIf { floating }
    Surface(
        shape = shape,
        color = if (backdrop != null) Color.Transparent else tokens.bottomBarContainerColor,
        contentColor = scheme.onSurface,
        border = if (floating) tokens.cardBorderStroke else null,
        shadowElevation = if (floating) 12.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(outerPadding)
            .forgeGlassSurface(backdrop, tokens.bottomBarContainerColor),
    ) {
        // Gesture navigation only needs a thin strip for its handle: keep the bar close to it instead of floating a full
        // extra inset above the screen edge. Three-button navigation keeps its whole inset so the bar never sits under
        // the buttons.
        val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val bottomInset = if (navInset <= GESTURE_INSET_MAX) (navInset - GESTURE_INSET_TRIM).coerceAtLeast(0.dp) else navInset
        Column(
            modifier = Modifier
                .padding(bottom = bottomInset)
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
        ) {
            if (!floating) Divider(color = scheme.outlineVariant, thickness = 0.5.dp)
            // Taller with larger system text, and labels stop scaling at 1.3x (five tabs share the width).
            val density = LocalDensity.current
            val navFontScale = density.fontScale.coerceAtMost(NAV_MAX_FONT_SCALE)
            val compactHeight = rememberCompactHeight()
            val barHeight = (if (compactHeight) 56.dp else 66.dp) + 16.dp * (navFontScale - 1f).coerceAtLeast(0f)
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .padding(horizontal = 6.dp, vertical = if (compactHeight) 2.dp else 6.dp),
            ) {
                val slot = maxWidth / entries.size
                val labels = entries.map { stringResource(it.label) }
                val longestLabel = labels.maxOf { it.length }
                val pillInset = if (entries.size >= 5) 2.dp else 6.dp
                // Pill: the only moving object. A radial gradient drawn behind it gives the soft outward glow
                // (no blur, so it costs nothing on low-end GPUs). White skips the glow: it would look muddy.
                val glow = !tokens.isWhite
                Box(
                    modifier = Modifier
                        .offset(x = slot * pillOffset)
                        .width(slot)
                        .fillMaxHeight(),
                ) {
                    if (glow) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .drawBehind {
                                    drawRect(
                                        brush = Brush.radialGradient(
                                            colors = listOf(scheme.primary.copy(alpha = 0.28f), Color.Transparent),
                                            center = center,
                                            // Half the short side: the glow fades out inside the slot. A larger radius is cut off by the slot edge and shows as a faint rectangle.
                                            radius = size.minDimension / 2f,
                                        ),
                                    )
                                },
                        )
                    }
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .padding(pillInset)
                            .clip(pillShape)
                            .background(pillFill)
                            .border(1.dp, pillEdge, pillShape),
                    )
                }
                Row(modifier = Modifier.fillMaxSize()) {
                    entries.forEachIndexed { index, entry ->
                        val label = stringResource(entry.label)
                        val selected = index == selectedIndex
                        val tint by animateColorAsState(
                            targetValue = if (selected) scheme.primary else scheme.onSurfaceVariant,
                            animationSpec = if (reduceMotion) snap() else tween(220),
                            label = "navTint",
                        )
                        // Glyph morph follows the same spring as the pill so icon and pill arrive together.
                        val morph by animateFloatAsState(
                            targetValue = if (selected) 1f else 0f,
                            animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.72f, stiffness = 380f),
                            label = "navMorph",
                        )
                        val live = when (entry.tab) {
                            ForgeTab.AGENT -> agentRunning
                            ForgeTab.TERMINAL -> terminalBusy
                            else -> false
                        }
                        val badge: String? = when (entry.tab) {
                            ForgeTab.AGENT -> if (agentRunning) stringResource(R.string.nav_badge_agent_running) else null
                            ForgeTab.TOOLS, ForgeTab.FILES -> if (changedCount > 0) quantityStringResource(R.plurals.nav_badge_files_changed, changedCount, changedCount) else null
                            ForgeTab.TERMINAL -> if (terminalBusy) stringResource(R.string.nav_badge_terminal_busy) else null
                            else -> null
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(pillShape)
                                .combinedClickable(
                                    role = Role.Tab,
                                    onClickLabel = label,
                                    onLongClick = onLongPress,
                                    onLongClickLabel = longPressLabel,
                                    onClick = { tap(entry.tab) },
                                )
                                .forgeSelected(selected),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Box {
                                NavGlyph(
                                    tab = entry.tab,
                                    progress = morph,
                                    live = live,
                                    tint = tint,
                                    reduceMotion = reduceMotion,
                                    modifier = Modifier.semantics {
                                        contentDescription = if (badge == null) label else "$label, $badge"
                                    },
                                )
                                if (badge != null) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .offset(x = 3.dp, y = (-2).dp)
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(scheme.primary),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            CompositionLocalProvider(LocalDensity provides Density(density.density, navFontScale)) {
                                Text(
                                    text = label,
                                    // One size for every tab: when any label is long, all labels on the narrow bar shrink together.
                                    fontSize = when {
                                        slot < 76.dp && longestLabel >= 9 -> ForgeType.navCompact * 0.92f
                                        slot < 76.dp -> ForgeType.navCompact
                                        else -> ForgeType.caption
                                    },
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = tint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
