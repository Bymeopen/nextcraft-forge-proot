package com.nextcraft.forge.ui

import androidx.compose.ui.graphics.luminance
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.ripple
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextcraft.forge.R

/**
 * Shared screen furniture for Batch A of the UI reset. Every screen that lives *inside* a project
 * (chats, conversation, files, terminal, preview, launcher, developer tools) uses the same header,
 * every launcher list uses the same row, and every empty/loading/error moment uses the same state block.
 */

/** Back arrow, 24sp title, 13sp subtitle and an optional action capsule on the right. 64dp tall. */
@Composable
fun ForgeProjectHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    /** A project's name: draws its identity dot (same colour everywhere the project shows), so projects tell apart at a glance. */
    accentSeed: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val compact = rememberCompactHeight()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 48.dp else ForgeSpace.header)
            .padding(start = if (onBack != null) 4.dp else ForgeSpace.screen, end = 14.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            // 48dp IconButton keeps the back target at the accessibility minimum.
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.a11y_back), tint = scheme.onSurface)
            }
        }
        if (accentSeed != null) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(10.dp)
                    .background(projectAccent(accentSeed, dark = scheme.background.luminance() < 0.5f), androidx.compose.foundation.shape.CircleShape),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = title,
                fontSize = if (compact) ForgeType.cardTitle else ForgeType.screenTitle,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.2).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = ForgeType.secondary,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (actions != null) ForgeActionPill(content = actions)
    }
}

/** A [ForgePillIcon] drawn with a stroke glyph instead of a Material vector. */
@Composable
fun ForgeGlyphPillIcon(
    glyph: ForgeGlyph,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .forgeMinTouch()
            .forgePressScale(interaction, pressedScale = 0.88f)
            .size(width = 48.dp, height = 48.dp)
            .clip(RoundedCornerShape(100))
            .clickable(interactionSource = interaction, indication = ripple(), enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ForgeIcon(glyph, tint = if (enabled) tint else tint.copy(alpha = 0.38f), description = description)
    }
}

/**
 * Launcher row: 40dp accent tile with a stroke glyph, title, one-line subtitle, optional badge and a
 * chevron. One accent colour for every tile so status, not category, is what gets colour.
 */
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
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .forgePressScale(interaction, pressedScale = 0.985f)
            .clickable(interactionSource = interaction, indication = ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = ForgeSpace.screen, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).background(tokens.cardElevatedColor, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { ForgeIcon(glyph, tint = scheme.primary, size = 22.dp) }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = ForgeType.rowTitle, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                // Two lines: on 360dp phones one line cut the descriptions mid-word ("paket bah…").
                Text(subtitle, fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!badge.isNullOrBlank()) {
            Text(
                text = badge,
                fontSize = ForgeType.caption,
                color = scheme.onSurface,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .background(scheme.onSurface.copy(alpha = 0.07f), RoundedCornerShape(100))
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = scheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
    }
}

/** Empty / loading / error block: 56dp glyph, 17sp title, one line of help, at most one CTA. */
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
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(56.dp).background(tint.copy(alpha = 0.10f), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) { ForgeIcon(glyph, tint = tint, size = 26.dp) }
        Spacer(modifier = Modifier.height(14.dp))
        Text(title, fontSize = ForgeType.cardTitle, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.forgeHeading())
        if (!message.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(message, fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(18.dp))
            ForgePrimaryButton(text = actionLabel, onClick = onAction)
        }
    }
}

/** Stable colour per project name: the hue comes from the name, lightness from the theme so it reads on both. */
internal fun projectAccent(seed: String, dark: Boolean): Color {
    val hue = ((seed.hashCode().toLong() and 0x7fffffff) % 360).toFloat()
    return Color.hsv(hue, 0.55f, if (dark) 0.95f else 0.62f)
}
