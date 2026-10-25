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
import com.nextcraft.forge.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface MarkdownBlock {
    data class Header(val level: Int, val text: String) : MarkdownBlock
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock
    data class BulletItem(val depth: Int, val text: String) : MarkdownBlock
    data class NumberedItem(val number: String, val text: String) : MarkdownBlock
    data class BlockQuote(val text: String) : MarkdownBlock
    data class Table(val rows: List<List<String>>) : MarkdownBlock
    object HorizontalRule : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Image(val alt: String, val src: String) : MarkdownBlock
    data class Video(val src: String) : MarkdownBlock
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    onRunCode: ((String) -> Unit)? = null,
    // SelectionContainer inside a LazyColumn item whose content changes (streaming chat) is a suspected cause of the
    // Compose Stack.pop crash; chat messages use the Copy button instead.
    selectable: Boolean = true,
) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    val resolvedColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color
    val tokens = LocalForgeThemeTokens.current

    OptionalSelection(selectable) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Header -> HeaderBlock(block, resolvedColor, tokens)
                    is MarkdownBlock.CodeBlock -> CodeSnippetBlock(block, onRunCode, tokens)
                    is MarkdownBlock.BulletItem -> BulletBlock(block, resolvedColor, tokens)
                    is MarkdownBlock.NumberedItem -> NumberedBlock(block, resolvedColor, tokens)
                    is MarkdownBlock.BlockQuote -> BlockQuoteView(block, resolvedColor, tokens)
                    is MarkdownBlock.Table -> TableBlockView(block, resolvedColor, tokens)
                    is MarkdownBlock.Image -> ChatImage(alt = block.alt, src = block.src)
                    is MarkdownBlock.Video -> ChatVideo(src = block.src)
                    MarkdownBlock.HorizontalRule -> Divider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                    )
                    is MarkdownBlock.Paragraph -> Text(
                        text = parseInlineMarkdown(
                            text = block.text,
                            accentColor = tokens.accentText,
                            inlineCodeBg = tokens.inlineCodeBackground,
                        ),
                        color = resolvedColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionalSelection(enabled: Boolean, content: @Composable () -> Unit) {
    if (enabled) SelectionContainer(content = content) else content()
}

@Composable
private fun HeaderBlock(
    block: MarkdownBlock.Header,
    color: Color,
    tokens: ForgeThemeTokens,
) {
    val style = when (block.level) {
        1 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
        2 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        else -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
    }
    Text(
        text = parseInlineMarkdown(
            text = block.text,
            accentColor = tokens.accentText,
            inlineCodeBg = tokens.inlineCodeBackground,
        ),
        style = style,
        color = color,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun BulletBlock(
    block: MarkdownBlock.BulletItem,
    color: Color,
    tokens: ForgeThemeTokens,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (block.depth * 12).dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "•",
            color = tokens.accentText,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = parseInlineMarkdown(
                text = block.text,
                accentColor = tokens.accentText,
                inlineCodeBg = tokens.inlineCodeBackground,
            ),
            color = color,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun NumberedBlock(
    block: MarkdownBlock.NumberedItem,
    color: Color,
    tokens: ForgeThemeTokens,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "${block.number}.",
            color = tokens.accentText,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = parseInlineMarkdown(
                text = block.text,
                accentColor = tokens.accentText,
                inlineCodeBg = tokens.inlineCodeBackground,
            ),
            color = color,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun BlockQuoteView(
    block: MarkdownBlock.BlockQuote,
    color: Color,
    tokens: ForgeThemeTokens,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(tokens.cardElevatedColor.withAlphaScaled(0.65f))
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(20.dp)
                .background(tokens.accentText),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = parseInlineMarkdown(
                text = block.text,
                accentColor = tokens.accentText,
                inlineCodeBg = tokens.inlineCodeBackground,
            ),
            color = color.copy(alpha = 0.92f),
            fontStyle = FontStyle.Italic,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun CodeSnippetBlock(
    block: MarkdownBlock.CodeBlock,
    onRunCode: ((String) -> Unit)?,
    tokens: ForgeThemeTokens,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val isShell = block.language.lowercase() in setOf("sh", "bash", "zsh", "shell", "console")
    val shape = RoundedCornerShape(10.dp)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), shape),
        color = tokens.terminalBackground,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.terminalHeaderBackground)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = block.language.ifBlank { "code" }.uppercase(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = ForgeType.caption,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isShell && onRunCode != null && block.code.isNotBlank()) {
                        IconButton(
                            onClick = { onRunCode(block.code) },
                            modifier = Modifier.size(ForgeSpace.minTouch),
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.md_run_in_terminal),
                                tint = tokens.successText,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(block.code))
                            copied = true
                            scope.launch {
                                delay(1500)
                                copied = false
                            }
                        },
                        modifier = Modifier.size(ForgeSpace.minTouch),
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Default.Check else Icons.Default.Share,
                            contentDescription = stringResource(R.string.md_copy_code),
                            tint = if (copied) tokens.successText else MaterialTheme.colorScheme.onSurfaceVariant,
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
                    text = block.code,
                    color = tokens.terminalText,
                    style = ForgeCodeTextStyle,
                )
            }
        }
    }
}

@Composable
private fun TableBlockView(
    block: MarkdownBlock.Table,
    color: Color,
    tokens: ForgeThemeTokens,
) {
    if (block.rows.isEmpty()) return
    val shape = RoundedCornerShape(10.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), shape),
        color = tokens.cardContainerColor,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            block.rows.forEachIndexed { rowIdx, cells ->
                val isHeader = rowIdx == 0 && block.rows.size > 1
                Row(
                    modifier = Modifier
                        .background(
                            if (isHeader) {
                                tokens.cardElevatedColor.withAlphaScaled(0.78f)
                            } else if (rowIdx % 2 == 1) {
                                Color.Transparent
                            } else {
                                tokens.cardElevatedColor.withAlphaScaled(0.25f)
                            },
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    cells.forEach { cellText ->
                        Text(
                            text = parseInlineMarkdown(
                                text = cellText,
                                accentColor = tokens.accentText,
                                inlineCodeBg = tokens.inlineCodeBackground,
                            ),
                            color = color,
                            style = if (isHeader) {
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
                if (rowIdx < block.rows.lastIndex) {
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                }
            }
        }
    }
}

private val NUMBERED_ITEM_REGEX = Regex("""^(\d+)\.\s+(.*)$""")
private val TABLE_DIVIDER_REGEX = Regex("""^\|?(\s*:?-{2,}:?\s*\|)+\s*:?-{2,}:?\s*\|?$""")

private fun parseMarkdown(raw: String): List<MarkdownBlock> {
    if (raw.isBlank()) return emptyList()
    val lines = raw.replace("\r\n", "\n").lines()
    val blocks = ArrayList<MarkdownBlock>()
    var inCodeBlock = false
    var codeLang = ""
    val codeBuf = StringBuilder()
    val paraBuf = StringBuilder()
    val tableRows = ArrayList<List<String>>()

    fun flushParagraph() {
        if (paraBuf.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(paraBuf.toString().trimEnd())
            paraBuf.setLength(0)
        }
    }

    fun flushTable() {
        if (tableRows.isNotEmpty()) {
            blocks += MarkdownBlock.Table(tableRows.toList())
            tableRows.clear()
        }
    }

    for (line in lines) {
        val trimmed = line.trimStart()
        val fencePrefix = when {
            trimmed.startsWith("```") -> "```"
            trimmed.startsWith("~~~") -> "~~~"
            else -> null
        }
        if (fencePrefix != null) {
            if (inCodeBlock) {
                blocks += MarkdownBlock.CodeBlock(codeLang, codeBuf.toString().trimEnd('\n'))
                codeBuf.setLength(0)
                codeLang = ""
                inCodeBlock = false
            } else {
                flushParagraph()
                flushTable()
                inCodeBlock = true
                codeLang = trimmed.removePrefix(fencePrefix).trim()
            }
            continue
        }
        if (inCodeBlock) {
            codeBuf.append(line).append('\n')
            continue
        }
        val trimmedBoth = trimmed.trimEnd()
        val isTableRow = trimmedBoth.startsWith("|") && trimmedBoth.endsWith("|") && trimmedBoth.count { it == '|' } >= 2
        if (isTableRow) {
            flushParagraph()
            if (!TABLE_DIVIDER_REGEX.matches(trimmedBoth)) {
                val cells = trimmedBoth
                    .removePrefix("|")
                    .removeSuffix("|")
                    .split("|")
                    .map { it.trim() }
                if (cells.isNotEmpty()) {
                    tableRows += cells
                }
            }
            continue
        } else {
            flushTable()
        }

        val image = imageRefOfLine(line)
        val video = if (image == null) videoPathOfLine(line) else null
        when {
            line.isBlank() -> flushParagraph()
            video != null -> {
                flushParagraph()
                blocks += MarkdownBlock.Video(video)
            }
            image != null -> {
                flushParagraph()
                blocks += MarkdownBlock.Image(image.alt, image.src)
            }
            trimmed == "---" || trimmed == "***" -> {
                flushParagraph()
                blocks += MarkdownBlock.HorizontalRule
            }
            trimmed.startsWith("#") -> {
                val hashes = trimmed.takeWhile { it == '#' }.length
                if (hashes in 1..6 && trimmed.getOrNull(hashes) == ' ') {
                    flushParagraph()
                    blocks += MarkdownBlock.Header(hashes, trimmed.substring(hashes + 1).trim())
                } else {
                    if (paraBuf.isNotEmpty()) paraBuf.append('\n')
                    paraBuf.append(line)
                }
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushParagraph()
                val leadingSpaces = line.length - trimmed.length
                blocks += MarkdownBlock.BulletItem(leadingSpaces / 2, trimmed.substring(2).trim())
            }
            trimmed.startsWith("> ") || trimmed == ">" -> {
                flushParagraph()
                blocks += MarkdownBlock.BlockQuote(trimmed.removePrefix(">").trim())
            }
            else -> {
                val numMatch = NUMBERED_ITEM_REGEX.matchEntire(trimmed)
                if (numMatch != null) {
                    flushParagraph()
                    blocks += MarkdownBlock.NumberedItem(numMatch.groupValues[1], numMatch.groupValues[2])
                } else {
                    if (paraBuf.isNotEmpty()) paraBuf.append('\n')
                    paraBuf.append(line)
                }
            }
        }
    }
    if (inCodeBlock) {
        blocks += MarkdownBlock.CodeBlock(codeLang, codeBuf.toString().trimEnd('\n'))
    }
    flushTable()
    flushParagraph()
    return blocks
}

private fun parseInlineMarkdown(
    text: String,
    accentColor: Color,
    inlineCodeBg: Color,
): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = text.length
    while (i < n) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(text[i])
                    i++
                }
            }
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(text[i])
                    i++
                }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = inlineCodeBg,
                            color = accentColor,
                            fontWeight = FontWeight.Medium,
                        ),
                    ) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(text[i])
                    i++
                }
            }
            text[i] == '*' && (i + 1 < n && text[i + 1] != ' ') -> {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(text[i])
                    i++
                }
            }
            text[i] == '[' -> {
                val closeBracket = text.indexOf(']', i + 1)
                val openParen = if (closeBracket != -1 && closeBracket + 1 < n && text[closeBracket + 1] == '(') {
                    closeBracket + 1
                } else {
                    -1
                }
                val closeParen = if (openParen != -1) text.indexOf(')', openParen + 1) else -1
                if (closeBracket != -1 && openParen != -1 && closeParen != -1) {
                    val label = text.substring(i + 1, closeBracket)
                    withStyle(
                        SpanStyle(
                            color = accentColor,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.Medium,
                        ),
                    ) {
                        append(label)
                    }
                    i = closeParen + 1
                } else {
                    append(text[i])
                    i++
                }
            }
            else -> {
                append(text[i])
                i++
            }
        }
    }
}

/** Scales the colour's own alpha; `copy(alpha = x)` would replace it and turn translucent Glass tokens bright. */
internal fun Color.withAlphaScaled(factor: Float): Color = copy(alpha = alpha * factor)
