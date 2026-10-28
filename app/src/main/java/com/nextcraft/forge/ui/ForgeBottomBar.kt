@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R

private const val MAX_NAV_TEXT_SCALE = 1.3f
private val GESTURE_BAR_HEIGHT_THRESHOLD = 34.dp
private val GESTURE_BAR_OVERLAP_REDUCTION = 10.dp
private val INDICATOR_VERTICAL_INSET = 1.dp

internal data class NavEntry(
    val tab: ForgeTab,
    @StringRes val label: Int,
)

internal val navEntries = listOf(
    NavEntry(ForgeTab.PROJECTS, R.string.nav_projects),
    NavEntry(ForgeTab.TERMINAL, R.string.nav_terminal),
    NavEntry(ForgeTab.AGENT, R.string.nav_agent),
    NavEntry(ForgeTab.TOOLS, R.string.nav_tools),
    NavEntry(ForgeTab.SETTINGS, R.string.nav_settings),
)

internal val projectEntries = listOf(
    NavEntry(ForgeTab.AGENT, R.string.nav_chat),
    NavEntry(ForgeTab.FILES, R.string.nav_files),
    NavEntry(ForgeTab.TERMINAL, R.string.nav_terminal),
    NavEntry(ForgeTab.PREVIEW, R.string.nav_preview),
)

internal val fullWidthTabs = setOf(
    ForgeTab.TERMINAL,
    ForgeTab.FILES,
    ForgeTab.PREVIEW,
    ForgeTab.ANDROID,
)

internal val toolsGroup = setOf(
    ForgeTab.TOOLS,
    ForgeTab.FILES,
    ForgeTab.ANDROID,
    ForgeTab.PREVIEW,
    ForgeTab.TOOLCHAINS,
    ForgeTab.DIAGNOSTICS,
    ForgeTab.BUILD_MATRIX,
    ForgeTab.SSH,
    ForgeTab.CICD,
    ForgeTab.ROM,
    ForgeTab.CHECKPOINTS,
    ForgeTab.CODE_VIEWER,
    ForgeTab.FILE_BROWSER,
    ForgeTab.REA,
    ForgeTab.BACKUP,
)

internal fun ForgeTab.navIndex(): Int {
    val canonicalTab = if (this in toolsGroup) ForgeTab.TOOLS else this
    return navEntries.indexOfFirst { it.tab == canonicalTab }.coerceAtLeast(0)
}

@Composable
internal fun ForgeBottomBar(
    entries: List<NavEntry>,
    selectedTab: ForgeTab,
    changedCount: Int,
    agentRunning: Boolean,
    terminalBusy: Boolean,
    onSelectTab: (ForgeTab) -> Unit,
    onLongPressTab: (() -> Unit)? = null,
) {
    val themeTokens = LocalForgeThemeTokens.current
    val palette = MaterialTheme.colorScheme
    val disableMotion = rememberReduceMotion()
    val hapticFeedback = LocalHapticFeedback.current

    val handleTabSelection = { dest: ForgeTab ->
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onSelectTab(dest)
    }
    val paletteActionDesc = onLongPressTab?.let { stringResource(R.string.palette_title) }
    val handleTabLongPress = onLongPressTab?.let { trigger ->
        {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            trigger()
        }
    }

    val activeSlotIdx = entries.indexOfFirst { it.tab == selectedTab }.takeIf { it >= 0 } ?: selectedTab.navIndex()
    val animatedSlotPosition by animateFloatAsState(
        targetValue = activeSlotIdx.toFloat(),
        animationSpec = if (disableMotion) snap() else spring(dampingRatio = 0.72f, stiffness = 380f),
        label = "navPill",
    )

    val isFloatingCapsule = themeTokens.isGlass
    val barMargin = if (isFloatingCapsule) {
        PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)
    } else {
        PaddingValues(0.dp)
    }
    val containerShape = if (isFloatingCapsule) RoundedCornerShape(100) else RoundedCornerShape(0.dp)
    val indicatorFill = palette.primary.copy(alpha = if (themeTokens.isWhite) 0.10f else 0.18f)
    val indicatorBorder = palette.primary.copy(alpha = 0.30f)
    val indicatorCornerShape = RoundedCornerShape(if (isFloatingCapsule) 100 else 22)

    val glassBackdrop = LocalForgeBackdrop.current.takeIf { isFloatingCapsule }
    Surface(
        shape = containerShape,
        color = if (glassBackdrop != null) Color.Transparent else themeTokens.bottomBarContainerColor,
        contentColor = palette.onSurface,
        border = if (isFloatingCapsule) themeTokens.cardBorderStroke else null,
        shadowElevation = if (isFloatingCapsule) 12.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(barMargin)
            .forgeGlassSurface(glassBackdrop, themeTokens.bottomBarContainerColor),
    ) {
        val rawBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val adjustedBottomPad = if (rawBottomInset <= GESTURE_BAR_HEIGHT_THRESHOLD) {
            (rawBottomInset - GESTURE_BAR_OVERLAP_REDUCTION).coerceAtLeast(0.dp)
        } else {
            rawBottomInset
        }

        Column(
            modifier = Modifier
                .padding(bottom = adjustedBottomPad)
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
        ) {
            if (!isFloatingCapsule) {
                Divider(color = palette.outlineVariant, thickness = 0.5.dp)
            }
            val currentDensity = LocalDensity.current
            val cappedFontScale = currentDensity.fontScale.coerceAtMost(MAX_NAV_TEXT_SCALE)
            val isCompactViewport = rememberCompactHeight()
            val baseHeight = if (isCompactViewport) 58.dp else 66.dp
            val totalBarHeight = baseHeight + (16.dp * (cappedFontScale - 1f).coerceAtLeast(0f))

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(totalBarHeight)
                    .padding(horizontal = 6.dp, vertical = if (isCompactViewport) 2.dp else 4.dp),
            ) {
                val itemWidth = maxWidth / entries.size
                val localizedLabels = entries.map { stringResource(it.label) }
                val maxLabelChars = localizedLabels.maxOf(String::length)
                val indicatorPadding = if (entries.size >= 5) 2.dp else 6.dp
                val drawRadialHalo = !themeTokens.isWhite

                Box(
                    modifier = Modifier
                        .offset(x = itemWidth * animatedSlotPosition)
                        .width(itemWidth)
                        .fillMaxHeight(),
                ) {
                    if (drawRadialHalo) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .drawBehind {
                                    drawRect(
                                        brush = Brush.radialGradient(
                                            colors = listOf(palette.primary.copy(alpha = 0.28f), Color.Transparent),
                                            center = center,
                                            radius = size.minDimension / 2f,
                                        ),
                                    )
                                },
                        )
                    }
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            // Horizontal inset only gives the pill breathing room; vertical stays minimal so the
                            // 24dp glyph + label (~44dp) always sit fully inside the pill.
                            .padding(horizontal = indicatorPadding, vertical = INDICATOR_VERTICAL_INSET)
                            .clip(indicatorCornerShape)
                            .background(indicatorFill)
                            .border(1.dp, indicatorBorder, indicatorCornerShape),
                    )
                }

                Row(modifier = Modifier.fillMaxSize()) {
                    entries.forEachIndexed { idx, navItem ->
                        val itemTitle = localizedLabels[idx]
                        val isCurrentTab = idx == activeSlotIdx
                        val iconAndLabelTint by animateColorAsState(
                            targetValue = if (isCurrentTab) palette.primary else palette.onSurfaceVariant,
                            animationSpec = if (disableMotion) snap() else tween(220),
                            label = "navTint",
                        )
                        val glyphProgress by animateFloatAsState(
                            targetValue = if (isCurrentTab) 1f else 0f,
                            animationSpec = if (disableMotion) snap() else spring(dampingRatio = 0.72f, stiffness = 380f),
                            label = "navMorph",
                        )
                        val isItemBusy = when (navItem.tab) {
                            ForgeTab.AGENT -> agentRunning
                            ForgeTab.TERMINAL -> terminalBusy
                            else -> false
                        }
                        val statusBadgeDesc: String? = when (navItem.tab) {
                            ForgeTab.AGENT -> if (agentRunning) stringResource(R.string.nav_badge_agent_running) else null
                            ForgeTab.TOOLS, ForgeTab.FILES -> if (changedCount > 0) {
                                quantityStringResource(R.plurals.nav_badge_files_changed, changedCount, changedCount)
                            } else {
                                null
                            }
                            ForgeTab.TERMINAL -> if (terminalBusy) stringResource(R.string.nav_badge_terminal_busy) else null
                            else -> null
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(indicatorCornerShape)
                                .combinedClickable(
                                    role = Role.Tab,
                                    onClickLabel = itemTitle,
                                    onLongClick = handleTabLongPress,
                                    onLongClickLabel = paletteActionDesc,
                                    onClick = { handleTabSelection(navItem.tab) },
                                )
                                .forgeSelected(isCurrentTab),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Box {
                                NavGlyph(
                                    tab = navItem.tab,
                                    progress = glyphProgress,
                                    live = isItemBusy,
                                    tint = iconAndLabelTint,
                                    reduceMotion = disableMotion,
                                    modifier = Modifier.semantics {
                                        contentDescription = if (statusBadgeDesc == null) {
                                            itemTitle
                                        } else {
                                            "$itemTitle, $statusBadgeDesc"
                                        }
                                    },
                                )
                                if (statusBadgeDesc != null) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .offset(x = 5.dp, y = (-3).dp)
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(palette.primary),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            CompositionLocalProvider(
                                LocalDensity provides Density(currentDensity.density, cappedFontScale),
                            ) {
                                val resolvedFontSize = when {
                                    itemWidth < 76.dp && maxLabelChars >= 9 -> ForgeType.navCompact * 0.92f
                                    itemWidth < 76.dp -> ForgeType.navCompact
                                    else -> ForgeType.caption
                                }
                                Text(
                                    text = itemTitle,
                                    fontSize = resolvedFontSize,
                                    fontWeight = if (isCurrentTab) FontWeight.SemiBold else FontWeight.Medium,
                                    color = iconAndLabelTint,
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
