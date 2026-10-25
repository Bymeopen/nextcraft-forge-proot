package com.nextcraft.forge.ui

import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.res.stringResource
import com.nextcraft.forge.R
import androidx.annotation.StringRes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.combinedClickable

/**
 * Linear-style toggle cell: label and optional description on the left, switch on the right.
 * The whole row is the touch target (>= 56dp tall) and is announced as one switch by TalkBack.
 */
@Composable
fun ForgeToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = ForgeType.rowTitle,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = ForgeType.secondary,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        // onCheckedChange = null: the row's toggleable owns the click so the switch is not a second target.
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = scheme.onPrimary,
                checkedTrackColor = scheme.primary,
                checkedBorderColor = scheme.primary,
                uncheckedThumbColor = scheme.onSurfaceVariant,
                uncheckedTrackColor = scheme.surfaceVariant,
                uncheckedBorderColor = forgeControlBorder(),
            ),
        )
    }
}

/**
 * True while the soft keyboard is showing. The window uses adjustResize (no edge-to-edge), so Compose
 * never sees an IME inset; the root view's insets are the reliable signal.
 */
@Composable
fun rememberImeVisible(): State<Boolean> {
    val view = LocalView.current
    val visible = remember { mutableStateOf(false) }
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            visible.value = ViewCompat.getRootWindowInsets(view)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return visible
}

/**
 * Where the system Back button goes from [tab]; null = let the system leave the app.
 * Tool screens return to the Tools launcher; the five bottom tabs return to Projects.
 */
fun forgeBackTarget(tab: ForgeTab): ForgeTab? = when (tab) {
    ForgeTab.PROJECTS -> null
    ForgeTab.FILES, ForgeTab.ANDROID, ForgeTab.PREVIEW,
    ForgeTab.TOOLCHAINS, ForgeTab.DIAGNOSTICS, ForgeTab.BUILD_MATRIX, ForgeTab.SSH, ForgeTab.CICD, ForgeTab.ROM, ForgeTab.CHECKPOINTS, ForgeTab.CODE_VIEWER, ForgeTab.FILE_BROWSER, ForgeTab.REA, ForgeTab.BACKUP,
    -> ForgeTab.TOOLS
    ForgeTab.TERMINAL, ForgeTab.AGENT, ForgeTab.TOOLS, ForgeTab.SETTINGS -> ForgeTab.PROJECTS
}

/**
 * Text button in the theme's accent text colour. Material's default is `primary`, which is 3.7:1 on dark
 * cards; accentText is checked >= 4.5:1 on every surface (ContrastTest).
 */
@Composable
fun ForgeTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = LocalForgeThemeTokens.current.accentText),
        content = content,
    )
}

/**
 * Keeps a small control's look but reserves a 44dp square for it. Compose already widens hit-testing to 48dp,
 * but neighbouring small controls then fight over the same pixels and TalkBack focus boxes stay tiny.
 */
fun Modifier.forgeMinTouch(): Modifier =
    sizeIn(minWidth = ForgeSpace.minTouch, minHeight = ForgeSpace.minTouch).wrapContentSize(Alignment.Center)

/** Screen and section titles: TalkBack users jump between them with heading navigation. */
fun Modifier.forgeHeading(): Modifier = semantics { heading() }

/** Text that appears or changes on its own (banners, errors) is announced without moving focus. */
fun Modifier.forgeLive(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }

/** Conveys "selected" for tabs and radio rows built on clickable (role alone only says "tab"). */
fun Modifier.forgeSelected(isSelected: Boolean): Modifier = semantics { selected = isSelected }

/** Large left-aligned screen title (Linear style) with an optional quiet subtitle. */
@Composable
fun ForgeLargeTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    horizontalPadding: Dp = 20.dp,
    /** Optional status chip or action at the end of the title row (e.g. the agent's connection state). */
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.padding(start = horizontalPadding, end = horizontalPadding, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = ForgeType.screenTitle, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp, modifier = Modifier.forgeHeading())
            if (subtitle != null) {
                Text(text = subtitle, fontSize = ForgeType.secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(12.dp))
            trailing()
        }
    }
}

/** Quiet section label that groups the cards/rows below it. */
@Composable
fun ForgeSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 20.dp,
    /** Space above the label. A parent that already spaces its children (spacedBy) passes less so the gap is not doubled. */
    topPadding: Dp = ForgeSpace.section,
) {
    Text(
        text = text.uppercase(),
        fontSize = ForgeType.caption,
        fontWeight = FontWeight.Medium,
        letterSpacing = ForgeType.sectionTracking,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .padding(start = horizontalPadding, end = horizontalPadding, top = topPadding, bottom = ForgeSpace.labelToCard)
            .forgeHeading(),
    )
}

enum class ForgeStatus(@StringRes val labelRes: Int) {
    DONE(R.string.status_done),
    RUNNING(R.string.status_running),
    FAILED(R.string.status_failed),
    TODO(R.string.status_waiting),
    SKIPPED(R.string.status_skipped),
}

/** Status glyph whose shape (not only colour) carries the state: check, half-filled ring, cross, ring, dotted ring. */
@Composable
fun ForgeStatusIcon(
    status: ForgeStatus,
    modifier: Modifier = Modifier,
    iconSize: Dp = 20.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    val done = scheme.primary
    val onDone = scheme.onPrimary
    val running = tokens.warningText
    val failed = scheme.error
    val onFailed = scheme.onError
    val ring = scheme.onSurfaceVariant.copy(alpha = 0.55f)
    val description = stringResource(status.labelRes)
    Canvas(
        modifier = modifier
            .size(iconSize)
            .semantics { contentDescription = description },
    ) {
        val stroke = 2.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        val radius = (minOf(w, h) - stroke) / 2f
        val c = center
        when (status) {
            ForgeStatus.DONE -> {
                drawCircle(done, radius = minOf(w, h) / 2f)
                val check = Path().apply {
                    moveTo(w * 0.28f, h * 0.52f)
                    lineTo(w * 0.44f, h * 0.68f)
                    lineTo(w * 0.74f, h * 0.34f)
                }
                drawPath(check, onDone, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            ForgeStatus.RUNNING -> {
                drawCircle(running, radius, style = Stroke(width = stroke))
                val inner = radius * 0.55f
                drawArc(running, -90f, 180f, true, Offset(c.x - inner, c.y - inner), Size(inner * 2, inner * 2))
            }
            ForgeStatus.FAILED -> {
                drawCircle(failed, radius = minOf(w, h) / 2f)
                val d = w * 0.17f
                drawLine(onFailed, Offset(c.x - d, c.y - d), Offset(c.x + d, c.y + d), stroke, StrokeCap.Round)
                drawLine(onFailed, Offset(c.x + d, c.y - d), Offset(c.x - d, c.y + d), stroke, StrokeCap.Round)
            }
            ForgeStatus.TODO -> drawCircle(ring, radius, style = Stroke(width = stroke))
            ForgeStatus.SKIPPED -> drawCircle(
                ring,
                radius,
                style = Stroke(
                    width = stroke,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                ),
            )
        }
    }
}

/** Capsule that groups 1-2 header actions at the top right of a screen (Linear style). */
@Composable
fun ForgeActionPill(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .heightIn(min = ForgeSpace.minTouch)
            .clip(RoundedCornerShape(100))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun ForgePillIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .forgePressScale(interaction, pressedScale = 0.88f, haptic = true)
            .size(width = 48.dp, height = 48.dp) // 48 dp is the minimum touch target
            .clip(RoundedCornerShape(100))
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Text tabs; the selected one sits in a soft pill. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun ForgePillTabs(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: ((Int) -> Unit)? = null,
    longPressLabel: String? = null,
    /** A few short options (e.g. Low / Medium / High): equal-width segments that fill the row instead of scrolling. */
    equalWidth: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    // Equal segments only while they can hold their labels; more than three options scroll instead of being cut to "R…".
    val equalWidth = equalWidth && items.size <= 3
    Row(
        modifier = modifier
            .fillMaxWidth()
            // The fade at a cut edge tells the reader there are more tabs to scroll to.
            .then(if (equalWidth) Modifier else Modifier.forgeFadingEdges(scroll, scheme.background).horizontalScroll(scroll))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
    ) {
        items.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .then(if (equalWidth) Modifier.weight(1f) else Modifier.widthIn(max = 168.dp))
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(100))
                    .background(if (selected) scheme.onSurface.copy(alpha = 0.09f) else Color.Transparent)
                    .combinedClickable(
                        role = Role.Tab,
                        onClickLabel = label,
                        onLongClickLabel = longPressLabel,
                        onLongClick = onLongPress?.let { cb -> { cb(index) } },
                        onClick = { onSelect(index) },
                    )
                    .forgeSelected(selected)
                    .padding(horizontal = if (equalWidth) 8.dp else 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    // Equal segments are narrower (a third of the row at 360dp): one size down keeps "Antigravity" whole.
                    fontSize = if (equalWidth) ForgeType.control else ForgeType.body,
                    color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
        }
    }
}

/** Capsule primary action, 44dp tall. */
@Composable
fun ForgePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(100),
        interactionSource = interaction,
        modifier = modifier.forgePressScale(interaction, haptic = true).heightIn(min = 44.dp),
    ) {
        Text(text = text, fontWeight = FontWeight.SemiBold)
    }
}

/** True when the user disabled animations (Android "Remove animations" / animator scale 0). */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}

/**
 * Vertical twin of [forgeFadingEdges]: fades the top and bottom edge of a scrolling screen into [background] while more
 * content continues past it, so a row cut off by the bottom bar reads as "scroll for more" and not as a clipping bug.
 */
fun Modifier.forgeFadingVertical(scroll: androidx.compose.foundation.ScrollState, background: Color, height: Dp = 24.dp): Modifier = this
    .drawWithContent {
        drawContent()
        val h = height.toPx()
        if (scroll.value > 0) {
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(background, Color.Transparent), startY = 0f, endY = h),
                size = Size(size.width, h),
            )
        }
        if (scroll.value < scroll.maxValue) {
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, background), startY = size.height - h, endY = size.height),
                topLeft = Offset(0f, size.height - h),
                size = Size(size.width, h),
            )
        }
    }

/**
 * Fades the edge of a horizontally scrolling row into [background] wherever more content continues past it. A plain
 * gradient drawn over the content: no offscreen layer or blend mode, so it renders the same on every device.
 */
fun Modifier.forgeFadingEdges(scroll: androidx.compose.foundation.ScrollState, background: Color, width: Dp = 28.dp): Modifier = this
    .drawWithContent {
        drawContent()
        val w = width.toPx()
        if (scroll.value > 0) {
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(background, Color.Transparent), startX = 0f, endX = w),
                size = Size(w, size.height),
            )
        }
        if (scroll.value < scroll.maxValue) {
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, background), startX = size.width - w, endX = size.width),
                topLeft = Offset(size.width - w, 0f),
                size = Size(w, size.height),
            )
        }
    }
