package com.agychat.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agychat.app.ui.theme.CodeBlockDark

@Composable
fun MarkdownContent(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val sections = parseMarkdownBlocks(text)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (section in sections) {
            when (section) {
                is MarkdownBlock.Code -> {
                    CodeBlockView(language = section.language, code = section.code)
                }
                is MarkdownBlock.Heading -> {
                    Text(
                        text = section.text,
                        style = when (section.level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        },
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                is MarkdownBlock.Blockquote -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(IntrinsicSize.Min)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = section.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = section.text,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 22.sp,
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
    val displayLang = language.ifBlank { "CODE" }.uppercase()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CodeBlockDark)
            .border(1.dp, Color(0xFF2E2E32), RoundedCornerShape(10.dp))
    ) {
        // Header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF18181B))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = displayLang,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFA1A1AA),
                fontWeight = FontWeight.SemiBold
            )
            IconButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Code", code))
                    Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(26.dp)
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy",
                    modifier = Modifier.size(15.dp),
                    tint = Color(0xFFA1A1AA)
                )
            }
        }

        // Code content with horizontal scroll
        Text(
            text = code,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFFF4F4F5),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(12.dp)
        )
    }
}

sealed class MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Code(val language: String, val code: String) : MarkdownBlock()
    data class Blockquote(val text: String) : MarkdownBlock()
}

fun parseMarkdownBlocks(raw: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = raw.split("\n")
    var inCodeBlock = false
    var codeLang = ""
    val codeBuffer = StringBuilder()
    val paraBuffer = StringBuilder()

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
        if (line.trim().startsWith("```")) {
            if (inCodeBlock) {
                // Ending code block
                blocks.add(MarkdownBlock.Code(codeLang, codeBuffer.toString().trimEnd()))
                codeBuffer.clear()
                codeLang = ""
                inCodeBlock = false
            } else {
                // Starting code block
                flushPara()
                codeLang = line.trim().removePrefix("```").trim()
                inCodeBlock = true
            }
            continue
        }

        if (inCodeBlock) {
            codeBuffer.append(line).append("\n")
            continue
        }

        val trimmed = line.trim()
        when {
            trimmed.startsWith("### ") -> {
                flushPara()
                blocks.add(MarkdownBlock.Heading(3, trimmed.removePrefix("### ")))
            }
            trimmed.startsWith("## ") -> {
                flushPara()
                blocks.add(MarkdownBlock.Heading(2, trimmed.removePrefix("## ")))
            }
            trimmed.startsWith("# ") -> {
                flushPara()
                blocks.add(MarkdownBlock.Heading(1, trimmed.removePrefix("# ")))
            }
            trimmed.startsWith("> ") -> {
                flushPara()
                blocks.add(MarkdownBlock.Blockquote(trimmed.removePrefix("> ")))
            }
            trimmed.isEmpty() -> {
                flushPara()
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
