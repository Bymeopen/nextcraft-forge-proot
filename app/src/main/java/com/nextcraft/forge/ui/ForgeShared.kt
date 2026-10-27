package com.nextcraft.forge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextcraft.forge.R

@Composable
fun ForgeProjectHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    accentSeed: String? = null,
) {
    val palette = MaterialTheme.colorScheme
    val isCompactLayout = rememberCompactHeight()
    val minRowHeight = if (isCompactLayout) 48.dp else ForgeSpace.header
    val startPad = if (onBack != null) 4.dp else ForgeSpace.screen

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minRowHeight)
            .padding(start = startPad, end = 14.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = stringResource(R.string.a11y_back),
                    tint = palette.onSurface,
                )
            }
        }
        if (accentSeed != null) {
            val isDarkBg = palette.background.luminance() < 0.5f
            Box(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(10.dp)
                    .background(projectAccent(accentSeed, dark = isDarkBg), CircleShape),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        ) {
            Text(
                text = title,
                fontSize = if (isCompactLayout) ForgeType.cardTitle else ForgeType.screenTitle,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.2).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = ForgeType.secondary,
                    color = palette.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (actions != null) {
            ForgeActionPill(content = actions)
        }
    }
}

@Composable
fun ForgeGlyphPillIcon(
    glyph: ForgeGlyph,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    val touchSource = remember { MutableInteractionSource() }
    val resolvedTint = if (enabled) tint else tint.copy(alpha = 0.38f)
    Box(
        modifier = modifier
            .forgeMinTouch()
            .forgePressScale(touchSource, pressedScale = 0.88f)
            .size(width = 48.dp, height = 48.dp)
            .clip(RoundedCornerShape(100))
            .clickable(
                interactionSource = touchSource,
                indication = ripple(),
                enabled = enabled,
                role = Role.Button,
                onClickLabel = description,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        ForgeIcon(
            glyph = glyph,
            tint = resolvedTint,
            description = description,
        )
    }
}

@Composable
fun ForgeListRow(
    glyph: ForgeGlyph,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badge: String? = null,
    enabled: Boolean = true,
) {
    val palette = MaterialTheme.colorScheme
    val themeTokens = LocalForgeThemeTokens.current
    val touchSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .forgePressScale(touchSource, pressedScale = 0.985f)
            .clickable(
                interactionSource = touchSource,
                indication = ripple(),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .heightIn(min = 64.dp)
            .padding(horizontal = ForgeSpace.screen, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(themeTokens.cardElevatedColor, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            ForgeIcon(glyph = glyph, tint = palette.primary, size = 22.dp)
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = ForgeType.rowTitle,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = ForgeType.secondary,
                    color = palette.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!badge.isNullOrBlank()) {
            Text(
                text = badge,
                fontSize = ForgeType.caption,
                color = palette.onSurface,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .background(palette.onSurface.copy(alpha = 0.07f), RoundedCornerShape(100))
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
            imageVector = Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = palette.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun ForgeEmptyState(
    glyph: ForgeGlyph,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val palette = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(tint.copy(alpha = 0.10f), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            ForgeIcon(glyph = glyph, tint = tint, size = 26.dp)
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = title,
            fontSize = ForgeType.cardTitle,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.forgeHeading(),
        )
        if (!message.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                fontSize = ForgeType.secondary,
                color = palette.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(18.dp))
            ForgePrimaryButton(text = actionLabel, onClick = onAction)
        }
    }
}

internal fun projectAccent(seed: String, dark: Boolean): Color {
    val angle = ((seed.hashCode().toLong() and 0x7fffffffL) % 360L).toFloat()
    val valueBrightness = if (dark) 0.95f else 0.62f
    return Color.hsv(angle, 0.55f, valueBrightness)
}
