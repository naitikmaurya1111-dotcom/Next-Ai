package com.agychat.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agychat.app.ui.theme.ClaudeTerracotta
import com.agychat.app.ui.theme.CodeBlockBg
import com.agychat.app.ui.theme.CodeBlockBorder
import com.agychat.app.ui.theme.CodeBlockHeader
import kotlinx.coroutines.delay

fun sanitizeMarkdownInput(raw: String): String {
    if (raw.isBlank()) return ""
    return raw
        // Strip ANSI escape codes (e.g. \u001B[31m, \u001B[0m)
        .replace(Regex("\u001B\\[[;?0-9]*[a-zA-Z]"), "")
        // Strip terminal query responses and bracketed paste noise (e.g. //#]jsi^9)
        .replace(Regex("//#\\][^\r\n]*"), "")
        .replace(Regex("\\[\\?[0-9;]*[a-zA-Z]"), "")
        // Strip non-printable ASCII control characters except \n, \r, \t
        .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F]"), "")
}

@Composable
fun MarkdownContent(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val cleanText = remember(text) { sanitizeMarkdownInput(text) }
    val sections = remember(cleanText) { parseMarkdownBlocks(cleanText) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        for (section in sections) {
            when (section) {
                is MarkdownBlock.Code -> {
                    CodeBlockView(language = section.language, code = section.code)
                }
                is MarkdownBlock.Heading -> {
                    val style = when (section.level) {
                        1 -> MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3).sp,
                            lineHeight = 26.sp
                        )
                        2 -> MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.2).sp,
                            lineHeight = 22.sp
                        )
                        else -> MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = buildFormattedInlineText(section.text, textColor),
                        style = style,
                        color = textColor,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                }
                is MarkdownBlock.Blockquote -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.5.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp))
                                .background(ClaudeTerracotta)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = buildFormattedInlineText(section.text, MaterialTheme.colorScheme.onSurfaceVariant),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontStyle = FontStyle.Italic,
                                lineHeight = 22.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is MarkdownBlock.ListItem -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        if (section.isOrdered) {
                            Text(
                                text = "${section.index}.",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = ClaudeTerracotta,
                                modifier = Modifier.width(22.dp)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .padding(top = 8.dp, end = 10.dp)
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(ClaudeTerracotta)
                            )
                        }
                        Text(
                            text = buildFormattedInlineText(section.text, textColor),
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 22.sp,
                            color = textColor,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                is MarkdownBlock.Divider -> {
                    HorizontalDivider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        thickness = 0.8.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                }
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = buildFormattedInlineText(section.text, textColor),
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 23.sp,
                        color = textColor
                    )
                }
            }
        }
    }
}

@Composable
fun CodeBlockView(language: String, code: String) {
    val context = LocalContext.current
    val displayLang = language.ifBlank { "TEXT" }.uppercase()
    var isCopied by remember { mutableStateOf(false) }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(2000)
            isCopied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CodeBlockBg)
            .border(1.dp, CodeBlockBorder, RoundedCornerShape(12.dp))
    ) {
        // Top Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CodeBlockHeader)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = displayLang,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                color = Color(0xFFA6A6B0),
                fontWeight = FontWeight.SemiBold
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF2B2B32).copy(alpha = 0.6f))
                    .border(0.5.dp, Color(0xFF3E3E48), RoundedCornerShape(6.dp))
            ) {
                IconButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Code", code))
                        isCopied = true
                        Toast.makeText(context, "Code copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    if (isCopied) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Copied",
                            modifier = Modifier.size(14.dp),
                            tint = Color(0xFF4CAF50)
                        )
                    } else {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy code",
                            modifier = Modifier.size(14.dp),
                            tint = Color(0xFFA6A6B0)
                        )
                    }
                }
            }
        }

        // Code Content with Horizontal Scroll
        Text(
            text = code,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp
            ),
            color = Color(0xFFEDEDF0),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(14.dp)
        )
    }
}

/**
 * Parses markdown inline styles like **bold**, *italic*, and `inline code`.
 */
@Composable
fun buildFormattedInlineText(raw: String, baseColor: Color): androidx.compose.ui.text.AnnotatedString {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val inlineCodeBg = if (isDark) Color(0xFF2C2B27) else Color(0xFFEFECE5)
    val inlineCodeText = if (isDark) Color(0xFFF0EBE1) else Color(0xFF9C4927)

    return buildAnnotatedString {
        var cursor = 0
        val textLength = raw.length

        while (cursor < textLength) {
            val nextBold = raw.indexOf("**", cursor)
            val nextCode = raw.indexOf("`", cursor)

            val nextToken = when {
                nextBold != -1 && nextCode != -1 -> minOf(nextBold, nextCode)
                nextBold != -1 -> nextBold
                nextCode != -1 -> nextCode
                else -> -1
            }

            if (nextToken == -1) {
                append(raw.substring(cursor))
                break
            }

            // Append text preceding the token
            if (nextToken > cursor) {
                append(raw.substring(cursor, nextToken))
            }

            if (nextToken == nextBold && nextBold + 2 < textLength) {
                val endBold = raw.indexOf("**", nextBold + 2)
                if (endBold != -1) {
                    val boldContent = raw.substring(nextBold + 2, endBold)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = baseColor)) {
                        append(boldContent)
                    }
                    cursor = endBold + 2
                    continue
                }
            }

            if (nextToken == nextCode && nextCode + 1 < textLength) {
                val endCode = raw.indexOf("`", nextCode + 1)
                if (endCode != -1) {
                    val codeContent = raw.substring(nextCode + 1, endCode)
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = inlineCodeBg,
                            color = inlineCodeText,
                            fontSize = 13.sp
                        )
                    ) {
                        append(" $codeContent ")
                    }
                    cursor = endCode + 1
                    continue
                }
            }

            // Fallback if no matching closing tag found
            append(raw[cursor])
            cursor++
        }
    }
}

sealed class MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Code(val language: String, val code: String) : MarkdownBlock()
    data class Blockquote(val text: String) : MarkdownBlock()
    data class ListItem(val isOrdered: Boolean, val index: Int, val text: String) : MarkdownBlock()
    object Divider : MarkdownBlock()
}

fun parseMarkdownBlocks(raw: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = raw.split("\n")
    var inCodeBlock = false
    var codeLang = ""
    val codeBuffer = StringBuilder()
    val paraBuffer = StringBuilder()
    var listIndex = 1

    fun flushPara() {
        if (paraBuffer.isNotEmpty()) {
            val content = paraBuffer.toString().trim()
            if (content.isNotEmpty()) {
                blocks.add(MarkdownBlock.Paragraph(content))
            }
            paraBuffer.clear()
        }
    }

    for (line in lines) {
        val trimmed = line.trim()

        if (trimmed.startsWith("```")) {
            if (inCodeBlock) {
                blocks.add(MarkdownBlock.Code(codeLang, codeBuffer.toString().trimEnd()))
                codeBuffer.clear()
                codeLang = ""
                inCodeBlock = false
            } else {
                flushPara()
                codeLang = trimmed.removePrefix("```").trim()
                inCodeBlock = true
            }
            continue
        }

        if (inCodeBlock) {
            codeBuffer.append(line).append("\n")
            continue
        }

        when {
            trimmed.startsWith("### ") -> {
                flushPara()
                blocks.add(MarkdownBlock.Heading(3, trimmed.removePrefix("### ").trim()))
            }
            trimmed.startsWith("## ") -> {
                flushPara()
                blocks.add(MarkdownBlock.Heading(2, trimmed.removePrefix("## ").trim()))
            }
            trimmed.startsWith("# ") -> {
                flushPara()
                blocks.add(MarkdownBlock.Heading(1, trimmed.removePrefix("# ").trim()))
            }
            trimmed.startsWith("> ") -> {
                flushPara()
                blocks.add(MarkdownBlock.Blockquote(trimmed.removePrefix("> ").trim()))
            }
            trimmed == "---" || trimmed == "***" -> {
                flushPara()
                blocks.add(MarkdownBlock.Divider)
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushPara()
                val itemText = if (trimmed.startsWith("- ")) trimmed.removePrefix("- ") else trimmed.removePrefix("* ")
                blocks.add(MarkdownBlock.ListItem(isOrdered = false, index = 0, text = itemText.trim()))
            }
            trimmed.matches(Regex("^\\d+\\.\\s+.*")) -> {
                flushPara()
                val match = Regex("^(\\d+)\\.\\s+(.*)").find(trimmed)
                if (match != null) {
                    val num = match.groupValues[1].toIntOrNull() ?: listIndex
                    val itemText = match.groupValues[2]
                    blocks.add(MarkdownBlock.ListItem(isOrdered = true, index = num, text = itemText.trim()))
                    listIndex = num + 1
                } else {
                    blocks.add(MarkdownBlock.Paragraph(trimmed))
                }
            }
            trimmed.isEmpty() -> {
                flushPara()
                listIndex = 1
            }
            else -> {
                if (paraBuffer.isNotEmpty()) paraBuffer.append("\n")
                paraBuffer.append(line)
            }
        }
    }

    if (inCodeBlock && codeBuffer.isNotEmpty()) {
        blocks.add(MarkdownBlock.Code(codeLang, codeBuffer.toString().trimEnd()))
    } else {
        flushPara()
    }

    return blocks
}
