package com.nextcraft.forge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.model.DiffLine
import com.nextcraft.forge.model.DiffLineType

private const val MAX_SHOWN_LINES = 1500

/** Colours of the code tokens: the theme's own accent/status text colours, so every theme keeps its contrast. */
internal fun codeSpanStyle(kind: CodeTokenKind, tokens: ForgeThemeTokens): SpanStyle = when (kind) {
    CodeTokenKind.KEYWORD -> SpanStyle(color = tokens.accentText, fontWeight = FontWeight.SemiBold)
    CodeTokenKind.STRING -> SpanStyle(color = tokens.warningText)
    CodeTokenKind.NUMBER -> SpanStyle(color = tokens.accentText)
    // Comments are italic and a step quieter; the colour alone is never the cue.
    CodeTokenKind.COMMENT -> SpanStyle(color = tokens.terminalText.copy(alpha = 0.78f), fontStyle = FontStyle.Italic)
    CodeTokenKind.ANNOTATION -> SpanStyle(color = tokens.warningText, fontWeight = FontWeight.Medium)
}

internal fun highlightLine(text: String, language: CodeLanguage, tokens: ForgeThemeTokens): AnnotatedString {
    val found = CodeSyntax.tokenize(text, language)
    if (found.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var at = 0
        found.forEach { token ->
            if (token.start > at) append(text.substring(at, token.start))
            withStyle(codeSpanStyle(token.kind, tokens)) { append(text.substring(token.start, token.end)) }
            at = token.end
        }
        if (at < text.length) append(text.substring(at))
    }
}

/**
 * A readable diff: line-number gutter, +/- sign, syntax colouring and a tinted row per change. Shows [previewLines]
 * lines first and offers the rest on request, so a long change does not bury the chat.
 */
@Composable
internal fun DiffCodeView(
    lines: List<DiffLine>,
    path: String,
    modifier: Modifier = Modifier,
    previewLines: Int = 24,
    textStyle: TextStyle = ForgeCodeTextStyle,
    onRevertHunk: ((Int) -> Unit)? = null,
) {
    val tokens = LocalForgeThemeTokens.current
    val language = remember(path) { CodeLanguage.forPath(path) }
    var showAll by remember(lines) { mutableStateOf(false) }
    // Rows are composed eagerly, so even "show all" stops at MAX_SHOWN_LINES (a 256 KB file is thousands of lines).
    val shown = lines.take(if (showAll) MAX_SHOWN_LINES else previewLines)
    val hidden = lines.size - shown.size
    val digits = remember(lines) { (lines.maxOfOrNull { maxOf(it.newLine ?: 0, it.oldLine ?: 0) } ?: 0).toString().length.coerceAtLeast(2) }
    // Long lines scroll sideways; the fading edge is the cue that there is more to the right.
    val sideScroll = rememberScrollState()
    val firstChangedIndexPerHunk = remember(shown) {
        val map = HashMap<Int, Int>()
        shown.forEachIndexed { idx, l ->
            if ((l.type == DiffLineType.ADDITION || l.type == DiffLineType.DELETION) && l.hunkIndex >= 0 && l.hunkIndex !in map) {
                map[l.hunkIndex] = idx
            }
        }
        map
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(tokens.terminalBackground)
                .forgeFadingEdges(sideScroll, tokens.terminalBackground)
                .horizontalScroll(sideScroll)
                .padding(vertical = 6.dp),
        ) {
            shown.forEachIndexed { idx, line ->
                val isFirstChangeOfHunk = onRevertHunk != null && firstChangedIndexPerHunk[line.hunkIndex] == idx
                if (isFirstChangeOfHunk) {
                    Row(
                        modifier = Modifier
                            .background(tokens.terminalBackground)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.diff_revert_hunk),
                            fontSize = ForgeType.caption,
                            fontWeight = FontWeight.SemiBold,
                            color = tokens.dangerText,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(tokens.dangerText.copy(alpha = 0.14f))
                                .clickable(role = androidx.compose.ui.semantics.Role.Button) { onRevertHunk?.invoke(line.hunkIndex) }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                DiffRow(line, language, digits, textStyle, tokens)
            }
        }
        if (hidden > 0 && !showAll) {
            TextButton(onClick = { showAll = true }, modifier = Modifier.heightIn(min = ForgeSpace.minTouch)) {
                Text(stringResource(R.string.diff_show_all, minOf(lines.size, MAX_SHOWN_LINES)))
            }
        } else if (hidden > 0) {
            Text(
                stringResource(R.string.diff_truncated, MAX_SHOWN_LINES),
                fontSize = ForgeType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, top = 6.dp),
            )
        }
        if (showAll && lines.size > previewLines) {
            TextButton(onClick = { showAll = false }, modifier = Modifier.heightIn(min = ForgeSpace.minTouch)) {
                Text(stringResource(R.string.diff_show_less))
            }
        }
    }
}

@Composable
private fun DiffRow(line: DiffLine, language: CodeLanguage, digits: Int, style: TextStyle, tokens: ForgeThemeTokens) {
    val (background, sign, signColor) = when (line.type) {
        DiffLineType.ADDITION -> Triple(tokens.diffAddedBackground, "+", tokens.diffAddedText)
        DiffLineType.DELETION -> Triple(tokens.diffRemovedBackground, "−", tokens.diffRemovedText)
        DiffLineType.INFO -> Triple(Color.Transparent, "⋯", tokens.accentText)
        DiffLineType.CONTEXT -> Triple(Color.Transparent, " ", tokens.terminalText.copy(alpha = 0.7f))
    }
    val number = (line.newLine ?: line.oldLine)?.toString().orEmpty()
    // Notes such as "… 12 unchanged lines …" come from the diff engine in English; translate them for display.
    val shownInfo = if (line.type == DiffLineType.INFO) messageText(line.text) else line.text
    val spoken = when (line.type) {
        DiffLineType.ADDITION -> stringResource(R.string.diff_row_added, number, line.text)
        DiffLineType.DELETION -> stringResource(R.string.diff_row_removed, number, line.text)
        DiffLineType.INFO, DiffLineType.CONTEXT -> stringResource(R.string.diff_row_context, number, shownInfo)
    }
    val text = remember(shownInfo, line.type, language, tokens) {
        if (line.type == DiffLineType.INFO) AnnotatedString(shownInfo) else highlightLine(line.text, language, tokens)
    }
    Row(
        modifier = Modifier
            .background(background)
            .padding(horizontal = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        // The gutter shows the new file's line number (the old one on removed lines), so a change can be found in the editor.
        Text(
            number,
            style = style,
            color = tokens.terminalText.copy(alpha = 0.7f),
            textAlign = TextAlign.End,
            modifier = Modifier.width((digits * 8 + 6).dp),
        )
        // The sign is the primary cue; the row tint only reinforces it.
        Text(sign, style = style, color = signColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp), textAlign = TextAlign.Center)
        Text(text, style = style, color = tokens.terminalText, softWrap = false, maxLines = 1)
    }
}
