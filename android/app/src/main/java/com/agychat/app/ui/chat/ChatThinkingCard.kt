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
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agychat.app.ui.theme.*
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * 2X Elevated Thinking Accordion Card for Next AI.
 * Features an organic pulsing brain/sparkle ambient glow, live elapsed ticker badge,
 * live token estimation counter, spring-animated expansion, and one-tap Copy Thinking.
 */
@Composable
fun ThinkingAccordionCard(
    thinkingText: String,
    isExpanded: Boolean,
    isActivelyThinking: Boolean = false,
    thinkingDurationMs: Long = 0L,
    onToggle: () -> Unit,
    onOpenFile: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f

    // Next AI flagship palette tokens
    val bgColor = if (isDark) Color(0xFF13131B) else Color(0xFFF7F6FC)
    val baseBorderColor = if (isDark) Color(0xFF262436) else Color(0xFFE5E1F4)
    val accentColor = if (isDark) Color(0xFFA78BFA) else Color(0xFF6D28D9)
    val textPrimaryColor = if (isDark) Color(0xFFEDE9FE) else Color(0xFF322E4A)
    val textSecondaryColor = if (isDark) Color(0xFFA6A1BD) else Color(0xFF6A6582)

    var isCopied by remember { mutableStateOf(false) }
    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(1800)
            isCopied = false
        }
    }

    var activeElapsedMs by remember { mutableStateOf(thinkingDurationMs.coerceAtLeast(0L)) }
    LaunchedEffect(isActivelyThinking) {
        if (isActivelyThinking) {
            val startWallTime = System.currentTimeMillis() - thinkingDurationMs.coerceAtLeast(0L)
            while (true) {
                val elapsed = System.currentTimeMillis() - startWallTime
                activeElapsedMs = elapsed.coerceAtLeast(0L)
                delay(100L)
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "thinkingAmbientPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "brainPulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sparklePulse"
    )

    val cardBorderBrush = if (isActivelyThinking) {
        Brush.linearGradient(
            colors = listOf(
                accentColor.copy(alpha = 0.30f + (0.50f * pulseAlpha)),
                ClaudeTerracotta.copy(alpha = 0.20f + (0.35f * pulseAlpha)),
                accentColor.copy(alpha = 0.25f)
            ),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        )
    } else {
        Brush.linearGradient(
            colors = listOf(baseBorderColor, baseBorderColor)
        )
    }

    val wordCount = remember(thinkingText) {
        thinkingText.trim().split(Regex("\\s+")).count { it.isNotBlank() }
    }
    val estimatedTokens = remember(wordCount) {
        (wordCount * 1.33).toInt().coerceAtLeast(1)
    }

    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
        label = "chevronRotation"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(1.2.dp, cardBorderBrush, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle()
                }),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Ambient Glowing Aura with Brain/Sparkle Icon
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .then(
                            if (isActivelyThinking) {
                                Modifier.drawBehind {
                                    drawCircle(
                                        brush = Brush.radialGradient(
                                            colors = listOf(
                                                accentColor.copy(alpha = 0.55f * pulseAlpha),
                                                ClaudeTerracotta.copy(alpha = 0.25f * pulseAlpha),
                                                Color.Transparent
                                            )
                                        ),
                                        radius = size.maxDimension * 0.88f * pulseScale
                                    )
                                }
                            } else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isActivelyThinking) Icons.Default.Psychology else Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier
                            .size(17.dp)
                            .then(
                                if (isActivelyThinking) Modifier.graphicsLayer {
                                    scaleX = pulseScale
                                    scaleY = pulseScale
                                } else Modifier
                            ),
                        tint = accentColor
                    )
                }

                Spacer(Modifier.width(8.dp))

                Text(
                    text = if (isActivelyThinking) "Thinking" else "Thought Process",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        letterSpacing = 0.2.sp
                    ),
                    color = textPrimaryColor
                )

                Spacer(Modifier.width(8.dp))

                // Real-time Timer Badge
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isActivelyThinking) accentColor.copy(alpha = 0.16f) else textSecondaryColor.copy(alpha = 0.12f),
                    border = androidx.compose.foundation.BorderStroke(
                        0.6.dp,
                        if (isActivelyThinking) accentColor.copy(alpha = 0.45f) else textSecondaryColor.copy(alpha = 0.25f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (isActivelyThinking) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .graphicsLayer {
                                        scaleX = pulseScale
                                        scaleY = pulseScale
                                        alpha = pulseAlpha
                                    }
                                    .clip(CircleShape)
                                    .background(accentColor)
                            )
                            Text(
                                text = "Thinking ${String.format(Locale.US, "%.1fs", activeElapsedMs / 1000.0)}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = accentColor
                            )
                        } else {
                            val durationSec = if (thinkingDurationMs > 0L) {
                                String.format(Locale.US, "%.1fs", thinkingDurationMs / 1000.0)
                            } else {
                                val estimatedS = (wordCount / 65.0).coerceAtLeast(1.0)
                                String.format(Locale.US, "%.1fs", estimatedS)
                            }
                            Text(
                                text = "Thought for $durationSec",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = textSecondaryColor
                            )
                        }
                    }
                }

                // Estimated Tokens Pill
                if (estimatedTokens > 0) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isDark) Color(0xFF1E1D28) else Color(0xFFEBE7F5),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, if (isDark) Color(0xFF2E2B3D) else Color(0xFFD6D0E8))
                    ) {
                        Text(
                            text = "~$estimatedTokens tokens",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = textSecondaryColor,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isExpanded) {
                    IconButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Thinking Process", thinkingText))
                            isCopied = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            Toast.makeText(context, "Copied thinking process", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = "Copy thinking",
                            modifier = Modifier.size(13.dp),
                            tint = if (isCopied) Color(0xFF10B981) else textSecondaryColor
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                }

                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(chevronRotation),
                    tint = textSecondaryColor
                )
            }
        }

        // Accordion Content with Spring Animation
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(tween(200)) + expandVertically(spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)),
            exit = fadeOut(tween(160)) + shrinkVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                HorizontalDivider(
                    color = if (isDark) Color(0xFF262438) else Color(0xFFEAE6F5),
                    thickness = 0.8.dp,
                    modifier = Modifier.padding(bottom = 10.dp)
                )

                Row(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .heightIn(min = 28.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(accentColor.copy(alpha = 0.85f), ClaudeTerracotta.copy(alpha = 0.5f))
                                )
                            )
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        MarkdownContent(
                            text = thinkingText,
                            textColor = if (isDark) Color(0xFFD4CFE6).copy(alpha = 0.88f) else Color(0xFF4A4460).copy(alpha = 0.92f),
                            isStreaming = isActivelyThinking,
                            onLinkClick = onOpenFile
                        )
                    }
                }
            }
        }
    }
}
