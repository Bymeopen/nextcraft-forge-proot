package com.nextcraft.forge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface MdAstNode {
    data class Heading(val depth: Int, val rawText: String) : MdAstNode
    data class FencedCode(val langTag: String, val snippet: String) : MdAstNode
    data class UnorderedItem(val indentLevel: Int, val rawText: String) : MdAstNode
    data class OrderedItem(val ordinalPrefix: String, val rawText: String) : MdAstNode
    data class QuoteBlock(val rawText: String) : MdAstNode
    data class GridTable(val matrix: List<List<String>>) : MdAstNode
    data object ThematicBreak : MdAstNode
    data class BodyParagraph(val rawText: String) : MdAstNode
    data class EmbeddedImage(val altText: String, val uri: String) : MdAstNode
    data class EmbeddedVideo(val uri: String) : MdAstNode
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    onRunCode: ((String) -> Unit)? = null,
    selectable: Boolean = true,
) {
    val astNodes = remember(markdown) { tokenizeMarkdownDocument(markdown) }
    val fgTint = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color
    val themeTokens = LocalForgeThemeTokens.current

    SelectionWrapper(enabled = selectable) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (node in astNodes) {
                when (node) {
                    is MdAstNode.Heading -> RenderHeadingNode(node, fgTint, themeTokens)
                    is MdAstNode.FencedCode -> RenderFencedCodeNode(node, onRunCode, themeTokens)
                    is MdAstNode.UnorderedItem -> RenderUnorderedItemNode(node, fgTint, themeTokens)
                    is MdAstNode.OrderedItem -> RenderOrderedItemNode(node, fgTint, themeTokens)
                    is MdAstNode.QuoteBlock -> RenderQuoteBlockNode(node, fgTint, themeTokens)
                    is MdAstNode.GridTable -> RenderGridTableNode(node, fgTint, themeTokens)
                    is MdAstNode.EmbeddedImage -> ChatImage(alt = node.altText, src = node.uri)
                    is MdAstNode.EmbeddedVideo -> ChatVideo(src = node.uri)
                    MdAstNode.ThematicBreak -> Divider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                    )
                    is MdAstNode.BodyParagraph -> Text(
                        text = buildStyledInlineSpans(
                            raw = node.rawText,
                            highlightColor = themeTokens.accentText,
                            codePillBg = themeTokens.inlineCodeBackground,
                        ),
                        color = fgTint,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionWrapper(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (enabled) SelectionContainer(content = content) else content()
}

@Composable
private fun RenderHeadingNode(
    node: MdAstNode.Heading,
    fgTint: Color,
    themeTokens: ForgeThemeTokens,
) {
    val typography = MaterialTheme.typography
    val textStyle = when (node.depth) {
        1 -> typography.titleLarge.copy(fontWeight = FontWeight.Bold)
        2 -> typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        else -> typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
    }
    Text(
        text = buildStyledInlineSpans(
            raw = node.rawText,
            highlightColor = themeTokens.accentText,
            codePillBg = themeTokens.inlineCodeBackground,
        ),
        style = textStyle,
        color = fgTint,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun RenderUnorderedItemNode(
    node: MdAstNode.UnorderedItem,
    fgTint: Color,
    themeTokens: ForgeThemeTokens,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (node.indentLevel * 12).dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "•",
            color = themeTokens.accentText,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = buildStyledInlineSpans(
                raw = node.rawText,
                highlightColor = themeTokens.accentText,
                codePillBg = themeTokens.inlineCodeBackground,
            ),
            color = fgTint,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun RenderOrderedItemNode(
    node: MdAstNode.OrderedItem,
    fgTint: Color,
    themeTokens: ForgeThemeTokens,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "${node.ordinalPrefix}.",
            color = themeTokens.accentText,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = buildStyledInlineSpans(
                raw = node.rawText,
                highlightColor = themeTokens.accentText,
                codePillBg = themeTokens.inlineCodeBackground,
            ),
            color = fgTint,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun RenderQuoteBlockNode(
    node: MdAstNode.QuoteBlock,
    fgTint: Color,
    themeTokens: ForgeThemeTokens,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(themeTokens.cardElevatedColor.withAlphaScaled(0.65f))
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(20.dp)
                .background(themeTokens.accentText),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = buildStyledInlineSpans(
                raw = node.rawText,
                highlightColor = themeTokens.accentText,
                codePillBg = themeTokens.inlineCodeBackground,
            ),
            color = fgTint.copy(alpha = 0.92f),
            fontStyle = FontStyle.Italic,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private val SHELL_LANG_IDENTIFIERS = setOf("sh", "bash", "zsh", "shell", "console")

@Composable
private fun RenderFencedCodeNode(
    node: MdAstNode.FencedCode,
    onRunCode: ((String) -> Unit)?,
    themeTokens: ForgeThemeTokens,
) {
    val clipboard = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    var justCopied by remember { mutableStateOf(false) }
    val runnableInShell = node.langTag.lowercase() in SHELL_LANG_IDENTIFIERS
    val boxShape = RoundedCornerShape(10.dp)
    val palette = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(boxShape)
            .border(1.dp, palette.outline.copy(alpha = 0.45f), boxShape),
        color = themeTokens.terminalBackground,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(themeTokens.terminalHeaderBackground)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = node.langTag.ifBlank { "code" }.uppercase(),
                    color = palette.onSurfaceVariant,
                    fontSize = ForgeType.caption,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (runnableInShell && onRunCode != null && node.snippet.isNotBlank()) {
                        IconButton(
                            onClick = { onRunCode(node.snippet) },
                            modifier = Modifier.size(ForgeSpace.minTouch),
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.md_run_in_terminal),
                                tint = themeTokens.successText,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(node.snippet))
                            justCopied = true
                            coroutineScope.launch {
                                delay(1500L)
                                justCopied = false
                            }
                        },
                        modifier = Modifier.size(ForgeSpace.minTouch),
                    ) {
                        Icon(
                            imageVector = if (justCopied) Icons.Default.Check else Icons.Default.Share,
                            contentDescription = stringResource(R.string.md_copy_code),
                            tint = if (justCopied) themeTokens.successText else palette.onSurfaceVariant,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                Text(
                    text = node.snippet,
                    color = themeTokens.terminalText,
                    style = ForgeCodeTextStyle,
                )
            }
        }
    }
}

@Composable
private fun RenderGridTableNode(
    node: MdAstNode.GridTable,
    fgTint: Color,
    themeTokens: ForgeThemeTokens,
) {
    if (node.matrix.isEmpty()) return
    val boxShape = RoundedCornerShape(10.dp)
    val palette = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(boxShape)
            .border(1.dp, palette.outline.copy(alpha = 0.45f), boxShape),
        color = themeTokens.cardContainerColor,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            node.matrix.forEachIndexed { rIdx, rowCells ->
                val isHeaderRow = rIdx == 0 && node.matrix.size > 1
                val rowBg = when {
                    isHeaderRow -> themeTokens.cardElevatedColor.withAlphaScaled(0.78f)
                    rIdx % 2 == 1 -> Color.Transparent
                    else -> themeTokens.cardElevatedColor.withAlphaScaled(0.25f)
                }
                Row(
                    modifier = Modifier
                        .background(rowBg)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (cellRaw in rowCells) {
                        Text(
                            text = buildStyledInlineSpans(
                                raw = cellRaw,
                                highlightColor = themeTokens.accentText,
                                codePillBg = themeTokens.inlineCodeBackground,
                            ),
                            color = fgTint,
                            style = if (isHeaderRow) {
                                MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            } else {
                                MaterialTheme.typography.bodySmall
                            },
                            modifier = Modifier
                                .width(136.dp)
                                .padding(end = 10.dp),
                        )
                    }
                }
                if (rIdx < node.matrix.lastIndex) {
                    Divider(color = palette.outline.copy(alpha = 0.25f))
                }
            }
        }
    }
}

private val ORDERED_LIST_LINE_PATTERN = Regex("""^(\d+)\.\s+(.*)$""")
private val TABLE_SEPARATOR_ROW_PATTERN = Regex("""^\|?(\s*:?-{2,}:?\s*\|)+\s*:?-{2,}:?\s*\|?$""")

private fun tokenizeMarkdownDocument(document: String): List<MdAstNode> {
    if (document.isBlank()) return emptyList()
    val rawLines = document.replace("\r\n", "\n").lines()
    val outNodes = ArrayList<MdAstNode>()
    var insideFence = false
    var activeFenceLang = ""
    val fenceBuffer = StringBuilder()
    val paragraphAccumulator = StringBuilder()
    val pendingTableRows = ArrayList<List<String>>()

    fun commitPendingParagraph() {
        if (paragraphAccumulator.isNotEmpty()) {
            outNodes += MdAstNode.BodyParagraph(paragraphAccumulator.toString().trimEnd())
            paragraphAccumulator.setLength(0)
        }
    }

    fun commitPendingTable() {
        if (pendingTableRows.isNotEmpty()) {
            outNodes += MdAstNode.GridTable(pendingTableRows.toList())
            pendingTableRows.clear()
        }
    }

    for (rawLine in rawLines) {
        val leadingStripped = rawLine.trimStart()
        val fenceMarker = when {
            leadingStripped.startsWith("```") -> "```"
            leadingStripped.startsWith("~~~") -> "~~~"
            else -> null
        }
        if (fenceMarker != null) {
            if (insideFence) {
                outNodes += MdAstNode.FencedCode(activeFenceLang, fenceBuffer.toString().trimEnd('\n'))
                fenceBuffer.setLength(0)
                activeFenceLang = ""
                insideFence = false
            } else {
                commitPendingParagraph()
                commitPendingTable()
                insideFence = true
                activeFenceLang = leadingStripped.removePrefix(fenceMarker).trim()
            }
            continue
        }
        if (insideFence) {
            fenceBuffer.append(rawLine).append('\n')
            continue
        }

        val fullTrimmed = leadingStripped.trimEnd()
        val looksLikeTableRow = fullTrimmed.startsWith("|") && fullTrimmed.endsWith("|") && fullTrimmed.count { it == '|' } >= 2
        if (looksLikeTableRow) {
            commitPendingParagraph()
            if (!TABLE_SEPARATOR_ROW_PATTERN.matches(fullTrimmed)) {
                val parsedCells = fullTrimmed
                    .removePrefix("|")
                    .removeSuffix("|")
                    .split("|")
                    .map(String::trim)
                if (parsedCells.isNotEmpty()) pendingTableRows += parsedCells
            }
            continue
        } else {
            commitPendingTable()
        }

        val mediaImg = imageRefOfLine(rawLine)
        val mediaVid = if (mediaImg == null) videoPathOfLine(rawLine) else null
        when {
            rawLine.isBlank() -> commitPendingParagraph()
            mediaVid != null -> {
                commitPendingParagraph()
                outNodes += MdAstNode.EmbeddedVideo(mediaVid)
            }
            mediaImg != null -> {
                commitPendingParagraph()
                outNodes += MdAstNode.EmbeddedImage(mediaImg.alt, mediaImg.src)
            }
            leadingStripped == "---" || leadingStripped == "***" -> {
                commitPendingParagraph()
                outNodes += MdAstNode.ThematicBreak
            }
            leadingStripped.startsWith("#") -> {
                val hashCount = leadingStripped.takeWhile { it == '#' }.length
                if (hashCount in 1..6 && leadingStripped.getOrNull(hashCount) == ' ') {
                    commitPendingParagraph()
                    outNodes += MdAstNode.Heading(hashCount, leadingStripped.substring(hashCount + 1).trim())
                } else {
                    if (paragraphAccumulator.isNotEmpty()) paragraphAccumulator.append('\n')
                    paragraphAccumulator.append(rawLine)
                }
            }
            leadingStripped.startsWith("- ") || leadingStripped.startsWith("* ") -> {
                commitPendingParagraph()
                val indentSpaces = rawLine.length - leadingStripped.length
                outNodes += MdAstNode.UnorderedItem(indentSpaces / 2, leadingStripped.substring(2).trim())
            }
            leadingStripped.startsWith("> ") || leadingStripped == ">" -> {
                commitPendingParagraph()
                outNodes += MdAstNode.QuoteBlock(leadingStripped.removePrefix(">").trim())
            }
            else -> {
                val orderedMatch = ORDERED_LIST_LINE_PATTERN.matchEntire(leadingStripped)
                if (orderedMatch != null) {
                    commitPendingParagraph()
                    outNodes += MdAstNode.OrderedItem(orderedMatch.groupValues[1], orderedMatch.groupValues[2])
                } else {
                    if (paragraphAccumulator.isNotEmpty()) paragraphAccumulator.append('\n')
                    paragraphAccumulator.append(rawLine)
                }
            }
        }
    }
    if (insideFence) {
        outNodes += MdAstNode.FencedCode(activeFenceLang, fenceBuffer.toString().trimEnd('\n'))
    }
    commitPendingTable()
    commitPendingParagraph()
    return outNodes
}

private fun buildStyledInlineSpans(
    raw: String,
    highlightColor: Color,
    codePillBg: Color,
): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    val len = raw.length
    while (cursor < len) {
        when {
            raw.startsWith("**", cursor) -> {
                val closeIdx = raw.indexOf("**", cursor + 2)
                if (closeIdx > cursor + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(raw.substring(cursor + 2, closeIdx))
                    }
                    cursor = closeIdx + 2
                } else {
                    append(raw[cursor])
                    cursor++
                }
            }
            raw.startsWith("~~", cursor) -> {
                val closeIdx = raw.indexOf("~~", cursor + 2)
                if (closeIdx > cursor + 2) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(raw.substring(cursor + 2, closeIdx))
                    }
                    cursor = closeIdx + 2
                } else {
                    append(raw[cursor])
                    cursor++
                }
            }
            raw[cursor] == '`' -> {
                val closeIdx = raw.indexOf('`', cursor + 1)
                if (closeIdx > cursor + 1) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codePillBg,
                            color = highlightColor,
                            fontWeight = FontWeight.Medium,
                        ),
                    ) {
                        append(raw.substring(cursor + 1, closeIdx))
                    }
                    cursor = closeIdx + 1
                } else {
                    append(raw[cursor])
                    cursor++
                }
            }
            raw[cursor] == '*' && (cursor + 1 < len && raw[cursor + 1] != ' ') -> {
                val closeIdx = raw.indexOf('*', cursor + 1)
                if (closeIdx > cursor + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(raw.substring(cursor + 1, closeIdx))
                    }
                    cursor = closeIdx + 1
                } else {
                    append(raw[cursor])
                    cursor++
                }
            }
            raw[cursor] == '[' -> {
                val rightBracket = raw.indexOf(']', cursor + 1)
                val leftParen = if (rightBracket != -1 && rightBracket + 1 < len && raw[rightBracket + 1] == '(') {
                    rightBracket + 1
                } else {
                    -1
                }
                val rightParen = if (leftParen != -1) raw.indexOf(')', leftParen + 1) else -1
                if (rightBracket != -1 && leftParen != -1 && rightParen != -1) {
                    val anchorLabel = raw.substring(cursor + 1, rightBracket)
                    withStyle(
                        SpanStyle(
                            color = highlightColor,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.Medium,
                        ),
                    ) {
                        append(anchorLabel)
                    }
                    cursor = rightParen + 1
                } else {
                    append(raw[cursor])
                    cursor++
                }
            }
            else -> {
                append(raw[cursor])
                cursor++
            }
        }
    }
}

internal fun Color.withAlphaScaled(factor: Float): Color = copy(alpha = alpha * factor)
