@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
internal fun ToolScaffold(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bodyScroll = rememberScrollState()
    val bgTint = MaterialTheme.colorScheme.background
    Column(modifier = Modifier.fillMaxSize()) {
        ForgeProjectHeader(
            title = title,
            subtitle = subtitle,
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .forgeFadingVertical(bodyScroll, bgTint)
                .verticalScroll(bodyScroll)
                .padding(horizontal = 14.dp)
                .padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
internal fun ToolCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ForgeThemedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(all = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
internal fun ToolSectionLabel(text: String) {
    ForgeSectionLabel(
        text = text,
        horizontalPadding = 4.dp,
    )
}

@Composable
internal fun LogBox(
    text: String,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val logScroll = rememberScrollState()
    val hasOutput = text.isNotEmpty()
    val palette = MaterialTheme.colorScheme
    LaunchedEffect(text.length) {
        logScroll.scrollTo(logScroll.maxValue)
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(if (hasOutput) 260.dp else 72.dp),
        shape = RoundedCornerShape(14.dp),
        color = palette.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Text(
            text = if (hasOutput) text else placeholder,
            fontFamily = FontFamily.Monospace,
            fontSize = ForgeType.caption,
            color = if (hasOutput) palette.onSurface else palette.onSurfaceVariant,
            modifier = Modifier
                .verticalScroll(logScroll)
                .padding(all = 12.dp),
        )
    }
}
