package com.agychat.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agychat.app.domain.model.ToolExecutionItem
import com.agychat.app.ui.theme.*
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Visual configuration for tool execution items.
 */
data class ToolVisualConfig(
    val icon: ImageVector,
    val iconTint: Color,
    val toolTitle: String,
    val tagLabel: String
)

/**
 * Modern Developer Terminal Container Card for AGY Executions.
 * Features macOS window controls, running step counter pill, and spring accordion expansion.
 */
@Composable
fun AgyTerminalExecutionCard(
    toolItems: List<ToolExecutionItem>,
    singleStatus: String?,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onOpenFile: ((String) -> Unit)? = null
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f

    val terminalCardBg = if (isDark) Color(0xFF15161C) else Color(0xFFF6F5F2)
    val terminalBorder = if (isDark) Color(0xFF272832) else Color(0xFFE2DFD8)
    val terminalHeaderBg = if (isDark) Color(0xFF1D1F27) else Color(0xFFECEAE3)
    val terminalCodeBg = if (isDark) Color(0xFF0E0F13) else Color(0xFFFFFFFF)
    val terminalCodeBorder = if (isDark) Color(0xFF24252F) else Color(0xFFDDD9D0)
    val greenAccent = Color(0xFF10B981)
    val amberAccent = Color(0xFFF59E0B)

    val anyRunning = toolItems.any { it.state == "ACTIVE" }
    val totalToolsCount = maxOf(toolItems.size, if (!singleStatus.isNullOrBlank()) 1 else 0)

    val infiniteTransition = rememberInfiniteTransition(label = "terminalActivePulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "terminalPulse"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(terminalCardBg)
            .border(1.dp, terminalBorder, RoundedCornerShape(12.dp))
    ) {
        // Developer Terminal Card Header Bar with macOS Window Dots
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(terminalHeaderBg)
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // macOS window control dots
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(Color(0xFFFF5F56).copy(alpha = 0.85f)))
                    Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(Color(0xFFFFBD2E).copy(alpha = 0.85f)))
                    Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(Color(0xFF27C93F).copy(alpha = 0.85f)))
                }

                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (anyRunning) amberAccent.copy(alpha = 0.2f) else greenAccent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Terminal,
                        contentDescription = null,
                        modifier = Modifier
                            .size(13.dp)
                            .then(if (anyRunning) Modifier.alpha(pulseAlpha) else Modifier),
                        tint = if (anyRunning) amberAccent else greenAccent
                    )
                }

                Spacer(Modifier.width(8.dp))

                Text(
                    text = "AGY Execution",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.5.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.width(8.dp))

                // Pill counter
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (anyRunning) amberAccent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(
                        0.5.dp,
                        if (anyRunning) amberAccent.copy(alpha = 0.4f) else Color.Transparent
                    )
                ) {
                    Text(
                        text = if (anyRunning) "Running..." else "$totalToolsCount step${if (totalToolsCount != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = if (anyRunning) amberAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }

            val terminalChevronRotation by animateFloatAsState(
                targetValue = if (isExpanded) 180f else 0f,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
                label = "terminalChevronRotation"
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                modifier = Modifier
                    .size(18.dp)
                    .rotate(terminalChevronRotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Body
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(tween(180)) + expandVertically(spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)),
            exit = fadeOut(tween(140)) + shrinkVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (toolItems.isEmpty() && !singleStatus.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(terminalCodeBg)
                            .border(0.6.dp, terminalCodeBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = singleStatus,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                } else {
                    for (item in toolItems) {
                        ToolExecutionItemRow(
                            item = item,
                            codeBg = terminalCodeBg,
                            borderColor = terminalCodeBorder,
                            isDark = isDark,
                            onOpenFile = onOpenFile
                        )
                    }
                }
            }
        }
    }
}

/**
 * 2X Elevated Tool Execution Item Row.
 * Supports status pills (Running ⏳, Success ✅, Failed ❌), duration tag,
 * expandable formatted JSON parameters view, and syntax-highlighted command output.
 */
@Composable
fun ToolExecutionItemRow(
    item: ToolExecutionItem,
    codeBg: Color,
    borderColor: Color,
    isDark: Boolean,
    onOpenFile: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var isOutputExpanded by remember { mutableStateOf(false) }
    var isParamsExpanded by remember { mutableStateOf(false) }
    var isOutputCopied by remember { mutableStateOf(false) }
    var isParamsCopied by remember { mutableStateOf(false) }

    LaunchedEffect(isOutputCopied) { if (isOutputCopied) { delay(1800); isOutputCopied = false } }
    LaunchedEffect(isParamsCopied) { if (isParamsCopied) { delay(1800); isParamsCopied = false } }

    val visualConfig = when (item.toolName) {
        "run_command" -> ToolVisualConfig(
            Icons.Default.Terminal,
            Color(0xFF10B981),
            item.command ?: "bash command",
            "TERMINAL"
        )
        "replace_file_content", "write_to_file", "sed_file", "create_file" -> ToolVisualConfig(
            Icons.Default.EditNote,
            Color(0xFFF59E0B),
            item.targetFile ?: "File Edit",
            "FILE_OPS"
        )
        "view_file", "read_resource", "read_url_content" -> ToolVisualConfig(
            Icons.Default.Description,
            Color(0xFF3B82F6),
            item.targetFile ?: "View File",
            "BROWSER"
        )
        "grep_search", "find_by_name", "search_web" -> ToolVisualConfig(
            Icons.Default.Search,
            Color(0xFF8B5CF6),
            item.parametersSummary ?: item.toolName,
            "BROWSER"
        )
        "generate_image" -> ToolVisualConfig(
            Icons.Default.Image,
            Color(0xFFEC4899),
            item.parametersSummary ?: "Generate Image",
            "IMAGE"
        )
        else -> ToolVisualConfig(
            Icons.Default.Code,
            ClaudeTerracotta,
            item.parametersSummary ?: item.toolName,
            "TOOL"
        )
    }
    val (icon, iconTint, toolTitle, tagLabel) = visualConfig

    val infiniteTransition = rememberInfiniteTransition(label = "toolRowPulse")
    val activePulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "activePulseAlpha"
    )

    // Synthesize structured JSON parameters
    val paramsJson = remember(item) {
        val raw = item.parametersSummary?.trim()
        if (!raw.isNullOrBlank() && raw.startsWith("{") && raw.endsWith("}")) {
            raw
        } else {
            val map = linkedMapOf<String, String>()
            map["tool"] = item.toolName
            if (!item.command.isNullOrBlank()) map["command"] = item.command
            if (!item.targetFile.isNullOrBlank()) map["targetFile"] = item.targetFile
            if (!raw.isNullOrBlank() && raw != item.toolName) map["summary"] = raw
            map.entries.joinToString(prefix = "{\n  ", separator = ",\n  ", postfix = "\n}") { (k, v) ->
                val safeV = v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                "\"$k\": \"$safeV\""
            }
        }
    }

    val outputLinesCount = remember(item.output) {
        item.output?.lines()?.size ?: 0
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(codeBg)
            .border(0.8.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        // Top Header: Tool icon, Tag, Title, and Status pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = iconTint
                )

                Spacer(Modifier.width(6.dp))

                // Tool Category Tag
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = iconTint.copy(alpha = if (isDark) 0.16f else 0.12f),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, iconTint.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = tagLabel,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 8.5.sp,
                            letterSpacing = 0.5.sp
                        ),
                        color = iconTint,
                        modifier = Modifier.padding(horizontal = 4.5.dp, vertical = 1.dp)
                    )
                }

                Spacer(Modifier.width(7.dp))

                Text(
                    text = toolTitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.5.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.width(6.dp))

            // Right side: View button, Duration, Status pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (!item.targetFile.isNullOrBlank() && onOpenFile != null) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = ClaudeTerracotta.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(0.6.dp, ClaudeTerracotta.copy(alpha = 0.4f)),
                        modifier = Modifier.clickable { onOpenFile(item.targetFile) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(Icons.Default.Visibility, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.size(11.dp))
                            Text(
                                text = "View",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                color = ClaudeTerracotta
                            )
                        }
                    }
                }

                // Duration tag
                if (item.durationSeconds > 0.0) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = if (isDark) Color(0xFF22232B) else Color(0xFFE8E6E0),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, if (isDark) Color(0xFF33343F) else Color(0xFFD6D3CA))
                    ) {
                        Text(
                            text = String.format(Locale.US, "%.2fs", item.durationSeconds),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                        )
                    }
                }

                // Status pills: Running ⏳, Success ✅, Failed ❌
                when (item.state) {
                    "ACTIVE" -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFF59E0B).copy(alpha = 0.18f),
                            border = androidx.compose.foundation.BorderStroke(0.6.dp, Color(0xFFF59E0B).copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(5.5.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFF59E0B))
                                        .alpha(activePulseAlpha)
                                )
                                Text(
                                    text = "Running ⏳",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color(0xFFF59E0B)
                                )
                            }
                        }
                    }
                    "ERROR" -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFEF4444).copy(alpha = 0.16f),
                            border = androidx.compose.foundation.BorderStroke(0.6.dp, Color(0xFFEF4444).copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = "Failed ❌",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color(0xFFEF4444)
                                )
                            }
                        }
                    }
                    else -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF10B981).copy(alpha = 0.16f),
                            border = androidx.compose.foundation.BorderStroke(0.6.dp, Color(0xFF10B981).copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = "Success ✅",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color(0xFF10B981)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Show command line if tool is run_command and command is present
        if (item.toolName == "run_command" && !item.command.isNullOrBlank()) {
            Spacer(Modifier.height(5.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isDark) Color(0xFF090A0D) else Color(0xFF222328))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$ ",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF10B981)
                    )
                )
                Text(
                    text = item.command,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFE2E2E6)
                    )
                )
            }
        }

        // Action Toggles Row: Expandable JSON Parameters + Command Output
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // JSON Parameter Toggle
            Surface(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    isParamsExpanded = !isParamsExpanded
                },
                shape = RoundedCornerShape(5.dp),
                color = if (isParamsExpanded) ClaudeTerracotta.copy(alpha = 0.15f) else Color.Transparent
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = if (isParamsExpanded) "▼ { } Params" else "▶ { } Params",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = if (isParamsExpanded) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }

            // Output Toggle (if output present)
            if (!item.output.isNullOrBlank()) {
                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        isOutputExpanded = !isOutputExpanded
                    },
                    shape = RoundedCornerShape(5.dp),
                    color = if (isOutputExpanded) ClaudeTerracotta.copy(alpha = 0.15f) else Color.Transparent
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = if (isOutputExpanded) "▼ Output ($outputLinesCount)" else "▶ Output ($outputLinesCount)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = if (isOutputExpanded) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        // Expandable JSON Parameters View
        AnimatedVisibility(
            visible = isParamsExpanded,
            enter = fadeIn(tween(180)) + expandVertically(spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)),
            exit = fadeOut(tween(140)) + shrinkVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
        ) {
            Column(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (isDark) Color(0xFF090A0D) else Color(0xFF1E1F24))
                    .border(0.6.dp, if (isDark) Color(0xFF22232B) else Color(0xFF33343C), RoundedCornerShape(7.dp))
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "JSON Parameters",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp
                        ),
                        color = Color(0xFFA6A6B0)
                    )
                    IconButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Parameters JSON", paramsJson))
                            isParamsCopied = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            Toast.makeText(context, "Parameters copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = if (isParamsCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = "Copy JSON",
                            modifier = Modifier.size(11.dp),
                            tint = if (isParamsCopied) Color(0xFF10B981) else Color(0xFFA6A6B0)
                        )
                    }
                }

                Text(
                    text = SyntaxHighlighter.highlight(paramsJson, "json", isDark = true),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                )
            }
        }

        // Expandable Syntax-Highlighted Output View
        if (!item.output.isNullOrBlank()) {
            AnimatedVisibility(
                visible = isOutputExpanded,
                enter = fadeIn(tween(180)) + expandVertically(spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)),
                exit = fadeOut(tween(140)) + shrinkVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
            ) {
                Column(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (isDark) Color(0xFF090A0D) else Color(0xFF1E1F24))
                        .border(0.6.dp, if (isDark) Color(0xFF22232B) else Color(0xFF33343C), RoundedCornerShape(7.dp))
                        .padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Terminal Output ($outputLinesCount lines)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp
                            ),
                            color = Color(0xFFA6A6B0)
                        )
                        IconButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Command Output", item.output))
                                isOutputCopied = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                Toast.makeText(context, "Output copied", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                imageVector = if (isOutputCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                                contentDescription = "Copy output",
                                modifier = Modifier.size(11.dp),
                                tint = if (isOutputCopied) Color(0xFF10B981) else Color(0xFFA6A6B0)
                            )
                        }
                    }

                    Text(
                        text = SyntaxHighlighter.highlight(item.output, "sh", isDark = true),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}
