@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R

/** Shared frame of the developer-tool screens: back arrow, title, subtitle and a scrolling body. */
@Composable
internal fun ToolScaffold(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ForgeProjectHeader(title = title, subtitle = subtitle, onBack = onBack)
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .forgeFadingVertical(scroll, MaterialTheme.colorScheme.background)
                .verticalScroll(scroll)
                .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** A themed card with the standard inner padding. */
@Composable
internal fun ToolCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    ForgeThemedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun ToolSectionLabel(text: String) {
    ForgeSectionLabel(text, horizontalPadding = 4.dp)
}

/** Monospace output box that follows the newest line. */
@Composable
internal fun LogBox(text: String, placeholder: String, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(text.length) { scroll.scrollTo(scroll.maxValue) }
    Surface(
        // Always a bounded height: the text inside scrolls vertically and every caller sits in a vertically scrolling
        // screen, so a height with no upper limit (heightIn(min = ...)) crashes with "infinity maximum height".
        modifier = modifier.fillMaxWidth().height(if (text.isEmpty()) 72.dp else 260.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Text(
            text = text.ifEmpty { placeholder },
            fontFamily = FontFamily.Monospace,
            fontSize = ForgeType.caption,
            color = if (text.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.verticalScroll(scroll).padding(12.dp),
        )
    }
}
