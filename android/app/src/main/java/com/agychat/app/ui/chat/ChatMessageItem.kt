package com.agychat.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.agychat.app.data.local.LocalFileManager
import com.agychat.app.domain.model.AttachmentItem
import com.agychat.app.domain.model.Message
import com.agychat.app.ui.common.NextAiLogo
import com.agychat.app.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Format epoch millis to a short time string for inline display: e.g. "3:42 PM" */
private fun formatMsgTimestamp(epochMs: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(epochMs))

/** Format a timestamp as a relative human-readable string: "just now", "2m ago", "3h ago", "Yesterday", "Sep 11" */
private fun formatMsgRelativeTime(epochMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - epochMs
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        diff < 172_800_000 -> "Yesterday"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMs))
    }
}

/**
 * 2X Elevated Chat Message Item Component for Next AI.
 * Handles User Bubbles, Assistant Canvas Cards, and System Badges with flagship elegance:
 * - Asymmetric squircle geometry with crystalline multi-stop gradient rims
 * - Multi-attachment hero banner & masonry grid previews
 * - Quoted reply banners with terracotta anchor accents
 * - Quick action capsules (Copy with checkmark feedback, Reply, Edit, Pin, Delete)
 * - Branch navigator switchers (‹ 1/3 ›)
 * - Ambient breathing avatar glow and live streaming status capsule
 * - Thinking disclosures (via ThinkingAccordionCard)
 * - Developer terminal tool cards (via AgyTerminalExecutionCard)
 * - Generated artifact cards with category icon badges and one-tap Open
 * - Autonomous memory update banners (✨ Memory updated)
 * - Pre-token wave bouncing loading dots
 * - Interactive emoji reactions bar (👍, ❤️, 💡, 🔥, 🚀)
 * - Contextual smart follow-up suggestion chips with entrance animations
 */
@Composable
fun MessageItem(
    message: Message,
    isSpeaking: Boolean = false,
    isLastAssistant: Boolean = false,
    isSearchMatch: Boolean = false,
    showFollowupSuggestions: Boolean = true,
    showStreamingCursor: Boolean = true,
    showMemoryActivityBadges: Boolean = true,
    onToggleThinking: () -> Unit,
    onToggleTools: () -> Unit = {},
    onRetry: () -> Unit = {},
    onEditMessage: (String) -> Unit = {},
    onDeleteMessage: () -> Unit = {},
    onToggleSpeak: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onFeedback: (String) -> Unit = {},
    onOpenMemory: () -> Unit = {},
    onOpenFile: (String) -> Unit = {},
    onReply: () -> Unit = {},
    onSendSuggestion: (String) -> Unit = {},
    onSwitchBranch: (String, Int) -> Unit = { _, _ -> },
    onContinueGenerating: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var isCopied by remember { mutableStateOf(false) }
    var isUserPromptCopied by remember { mutableStateOf(false) }
    var selectedReaction by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(2000)
            isCopied = false
        }
    }
    LaunchedEffect(isUserPromptCopied) {
        if (isUserPromptCopied) {
            delay(2000)
            isUserPromptCopied = false
        }
    }

    when (message.role) {
        "user" -> {
            val isDark = MaterialTheme.colorScheme.background.red < 0.5f
            val userBubbleShape = RoundedCornerShape(
                topStart = 24.dp,
                topEnd = 24.dp,
                bottomEnd = 6.dp,
                bottomStart = 24.dp
            )
            val crystalBorderBrush = remember(isDark) {
                if (isDark) {
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0x55FFFFFF),
                            Color(0x20A78BFA),
                            Color(0x18FFFFFF),
                            Color(0x35E07A5F)
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                    )
                } else {
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0x75FFFFFF),
                            Color(0x25C96442),
                            Color(0x18000000),
                            Color(0x45FFFFFF)
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.End
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(min = 48.dp, max = 580.dp)
                        .shadow(
                            elevation = 3.dp,
                            shape = userBubbleShape,
                            ambientColor = if (isDark) Color.Black.copy(alpha = 0.55f) else Color(0x20000000),
                            spotColor = if (isDark) Color(0x30E07A5F) else Color(0x15000000)
                        )
                        .clip(userBubbleShape)
                        .background(
                            brush = Brush.linearGradient(
                                colors = if (isDark)
                                    listOf(UserBubbleDarkBg1, UserBubbleDarkBg2)
                                else
                                    listOf(UserBubbleLightBg1, UserBubbleLightBg2),
                                start = Offset(0f, 0f),
                                end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                            )
                        )
                        .then(
                            if (isSearchMatch)
                                Modifier.border(2.dp, ClaudeTerracotta, userBubbleShape)
                            else
                                Modifier.border(
                                    width = 1.2.dp,
                                    brush = crystalBorderBrush,
                                    shape = userBubbleShape
                                )
                        )
                        .padding(horizontal = 18.dp, vertical = 13.dp)
                ) {
                    Column {
                        // 1. Quoted Reply Snippet
                        if (!message.replyToContent.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                border = androidx.compose.foundation.BorderStroke(
                                    0.6.dp,
                                    ClaudeTerracotta.copy(alpha = 0.35f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(3.dp)
                                            .height(24.dp)
                                            .background(ClaudeTerracotta, RoundedCornerShape(2.dp))
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Column {
                                        Text(
                                            text = "Quoting ${if (message.replyToRole == "user") "User" else "Next AI"}",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 9.5.sp
                                            ),
                                            color = ClaudeTerracotta
                                        )
                                        Text(
                                            text = message.replyToContent,
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        // 2. Pinned Badge
                        if (message.isPinned) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 4.dp),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.PushPin,
                                    contentDescription = "Pinned",
                                    tint = Color(0xFFFFB300),
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    "Pinned",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color(0xFFFFB300)
                                )
                            }
                        }

                        // 3. Multi-Attachment Display (Images & Files)
                        val allAtts = message.allAttachments
                        if (allAtts.isNotEmpty()) {
                            val images = allAtts.filter { it.isImage }
                            val files = allAtts.filter { !it.isImage }

                            if (images.isNotEmpty()) {
                                if (images.size == 1) {
                                    AsyncImage(
                                        model = images[0].uri,
                                        contentDescription = "Attached image",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 240.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .clickable { onOpenFile(images[0].uri) },
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(Modifier.height(8.dp))
                                } else {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        images.chunked(2).forEach { rowImages ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                rowImages.forEach { img ->
                                                    AsyncImage(
                                                        model = img.uri,
                                                        contentDescription = img.name,
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .height(115.dp)
                                                            .clip(RoundedCornerShape(12.dp))
                                                            .clickable { onOpenFile(img.uri) },
                                                        contentScale = ContentScale.Crop
                                                    )
                                                }
                                                if (rowImages.size == 1) {
                                                    Spacer(Modifier.weight(1f))
                                                }
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }
                            }

                            if (files.isNotEmpty()) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    files.forEach { file ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(MaterialTheme.colorScheme.surface)
                                                .border(
                                                    0.6.dp,
                                                    MaterialTheme.colorScheme.outlineVariant,
                                                    RoundedCornerShape(10.dp)
                                                )
                                                .clickable { onOpenFile(file.uri) }
                                                .padding(horizontal = 10.dp, vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.InsertDriveFile,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                                tint = ClaudeTerracotta
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = file.name,
                                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "Tap to view file",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                )
                                            }
                                            Icon(
                                                Icons.Default.OpenInNew,
                                                contentDescription = "Open file",
                                                tint = ClaudeTerracotta,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }

                        // 4. Main Markdown / Text Content
                        if (message.content.isNotBlank() && !message.content.startsWith("Sent an attachment:") && !message.content.startsWith("Sent ")) {
                            MarkdownContent(
                                text = message.content,
                                textColor = MaterialTheme.colorScheme.onSurface,
                                onLinkClick = onOpenFile
                            )
                        }
                    }
                }

                // Interactive Quick Actions for User Message
                Row(
                    modifier = Modifier.padding(top = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Branch Navigation Pill: ‹ 1 / 2 ›
                    if (message.totalBranches > 1) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            border = androidx.compose.foundation.BorderStroke(
                                0.6.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(1.dp),
                                modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        if (message.branchIndex > 0) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onSwitchBranch(message.parentMessageId ?: message.id, message.branchIndex - 1)
                                        }
                                    },
                                    enabled = message.branchIndex > 0,
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ChevronLeft,
                                        contentDescription = "Previous edit",
                                        modifier = Modifier.size(14.dp),
                                        tint = if (message.branchIndex > 0) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                    )
                                }

                                Text(
                                    text = "${message.branchIndex + 1}/${message.totalBranches}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                IconButton(
                                    onClick = {
                                        if (message.branchIndex < message.totalBranches - 1) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onSwitchBranch(message.parentMessageId ?: message.id, message.branchIndex + 1)
                                        }
                                    },
                                    enabled = message.branchIndex < message.totalBranches - 1,
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ChevronRight,
                                        contentDescription = "Next edit",
                                        modifier = Modifier.size(14.dp),
                                        tint = if (message.branchIndex < message.totalBranches - 1) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = formatMsgTimestamp(message.timestamp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(end = 4.dp)
                    )

                    // Crystalline Quick Action Icons Capsule
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isDark) Color(0x221E1E28) else Color(0x26EDEAE3),
                        border = androidx.compose.foundation.BorderStroke(
                            0.6.dp,
                            if (isDark) Color(0x35FFFFFF) else Color(0x22000000)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("User Prompt", message.content))
                                    isUserPromptCopied = true
                                    Toast.makeText(context, "Copied prompt", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    imageVector = if (isUserPromptCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                                    contentDescription = "Copy",
                                    modifier = Modifier.size(13.dp),
                                    tint = if (isUserPromptCopied) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }

                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onReply()
                                },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    Icons.Default.Reply,
                                    contentDescription = "Reply / Quote",
                                    modifier = Modifier.size(13.dp),
                                    tint = ClaudeTerracotta.copy(alpha = 0.85f)
                                )
                            }

                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onEditMessage(message.content)
                                },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Edit",
                                    modifier = Modifier.size(13.dp),
                                    tint = ClaudeTerracotta.copy(alpha = 0.8f)
                                )
                            }

                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onTogglePin()
                                },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    Icons.Default.PushPin,
                                    contentDescription = if (message.isPinned) "Unpin" else "Pin",
                                    modifier = Modifier.size(13.dp),
                                    tint = if (message.isPinned) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }

                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onDeleteMessage()
                                },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Delete",
                                    modifier = Modifier.size(13.dp),
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }
        "assistant" -> {
            val isDark = MaterialTheme.colorScheme.background.red < 0.5f

            val avatarTransition = rememberInfiniteTransition(label = "avatarAmbientGlow")
            val auraScale by avatarTransition.animateFloat(
                initialValue = 1.0f,
                targetValue = 1.25f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "auraScale"
            )
            val auraAlpha by avatarTransition.animateFloat(
                initialValue = 0.30f,
                targetValue = 0.70f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "auraAlpha"
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                // Header: Avatar with ambient warm glow + Model name + Streaming live dot
                Row(
                    modifier = Modifier.padding(bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Next AI Continuum Avatar with ambient warm glow
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .drawBehind {
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            ClaudeTerracotta.copy(alpha = 0.45f * auraAlpha),
                                            ChatGptPurple.copy(alpha = 0.18f * auraAlpha),
                                            Color.Transparent
                                        )
                                    ),
                                    radius = size.maxDimension * 0.85f * auraScale
                                )
                            }
                            .shadow(2.5.dp, CircleShape, spotColor = ClaudeTerracotta.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        NextAiLogo(
                            size = 28.dp,
                            showBackground = true
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = message.modelName ?: "Next AI",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp,
                            letterSpacing = 0.2.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.95f)
                    )
                    if (message.isStreaming) {
                        Spacer(Modifier.width(8.dp))
                        val streamDotTransition = rememberInfiniteTransition(label = "streamDotPulse")
                        val dotScale by streamDotTransition.animateFloat(
                            initialValue = 0.8f,
                            targetValue = 1.25f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(700, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "streamDotScale"
                        )
                        val dotAlpha by streamDotTransition.animateFloat(
                            initialValue = 0.45f,
                            targetValue = 1.0f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(700, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "streamDotAlpha"
                        )

                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(ClaudeTerracotta.copy(alpha = if (isDark) 0.16f else 0.12f))
                                .border(0.6.dp, ClaudeTerracotta.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 8.dp, vertical = 2.5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.5.dp)
                                    .graphicsLayer {
                                        scaleX = dotScale
                                        scaleY = dotScale
                                        alpha = dotAlpha
                                    }
                                    .clip(CircleShape)
                                    .background(ClaudeTerracotta)
                            )
                            Text(
                                text = "generating...",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold),
                                color = ClaudeTerracotta
                            )
                        }
                    }
                }

                // Main content column — borderless canvas-style flow
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isSearchMatch) Modifier
                                .border(1.5.dp, ClaudeTerracotta.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                                .padding(8.dp)
                            else Modifier
                        )
                ) {
                    // Pinned Answer Indicator Banner
                    if (message.isPinned) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFFFFB300).copy(alpha = 0.12f),
                            border = androidx.compose.foundation.BorderStroke(0.6.dp, Color(0xFFFFB300).copy(alpha = 0.35f)),
                            modifier = Modifier.padding(bottom = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.PushPin,
                                    contentDescription = null,
                                    tint = Color(0xFFFFB300),
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "Pinned Answer",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                                    color = Color(0xFFFFB300)
                                )
                            }
                        }
                    }

                    // 1. Thinking Process Card (Pulsing Brain Aura & Elapsed Ticker)
                    if (!message.thinking.isNullOrBlank()) {
                        ThinkingAccordionCard(
                            thinkingText = message.thinking,
                            isExpanded = message.isThinkingExpanded ?: (message.isStreaming && message.isThinking),
                            isActivelyThinking = message.isStreaming && message.isThinking,
                            thinkingDurationMs = message.thinkingDurationMs,
                            onToggle = onToggleThinking,
                            onOpenFile = onOpenFile
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    // 2. AGY Terminal Execution Card (Developer Shell & Tool Items)
                    if (message.toolExecutions.isNotEmpty() || !message.toolExecution.isNullOrBlank()) {
                        val anyRunning = message.toolExecutions.any { it.state == "ACTIVE" }
                        val isToolsCardExpanded = message.isToolsExpanded ?: (anyRunning && message.isStreaming)
                        AgyTerminalExecutionCard(
                            toolItems = message.toolExecutions,
                            singleStatus = message.toolExecution,
                            isExpanded = isToolsCardExpanded,
                            onToggle = onToggleTools,
                            onOpenFile = onOpenFile
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    // 2b. Generated Files Artifact Cards (Derivations, notes, code files, PDFs created or linked)
                    val generatedFiles = remember(message.toolExecutions, message.content) {
                        val list = mutableListOf<String>()
                        // 1. Tool executions (strictly creation/modification tools)
                        message.toolExecutions.forEach { t ->
                            val tName = t.toolName.lowercase()
                            val isCreation = tName in setOf("write_to_file", "replace_file_content", "create_file", "generate_image") ||
                                (t.targetFile?.contains("/brain/") == true)
                            if (isCreation) {
                                t.targetFile?.takeIf { it.isNotBlank() }?.let { raw ->
                                    val clean = LocalFileManager.normalizeFileId(raw)
                                    val fn = LocalFileManager.getFileName(clean)
                                    if (fn.contains('.') && fn.substringAfterLast('.').length in 1..8) list.add(clean)
                                }
                            }
                        }
                        // 2. Markdown links: [label](file:///path), [label](/content/path)
                        val linkRegex = Regex("""\[([^\]]+)\]\(((?:file://|/content/|/root/|/tmp/|https?://[^\s\)]+/api/file|[a-zA-Z0-9_\-./]+\.(?:pdf|md|txt|py|kt|java|json|csv|png|jpg|jpeg|webp|html|svg|sh))[^\s\)]*)\)""")
                        for (m in linkRegex.findAll(message.content)) {
                            val raw = m.groupValues[2].trim()
                            if (raw.isNotBlank() && (!raw.startsWith("http") || raw.contains("/api/file"))) {
                                val clean = LocalFileManager.normalizeFileId(raw)
                                val fn = LocalFileManager.getFileName(clean)
                                if (fn.contains('.') && fn.substringAfterLast('.').length in 1..8) list.add(clean)
                            }
                        }
                        // 3. Explicit file paths mentioned in text
                        val pathRegex = Regex("""(?:file://|/content/|/root/|/tmp/)[a-zA-Z0-9_\-./]+\.(?:pdf|md|txt|py|kt|java|json|csv|png|jpg|jpeg|webp)""")
                        for (m in pathRegex.findAll(message.content)) {
                            val clean = LocalFileManager.normalizeFileId(m.value.trim())
                            val fn = LocalFileManager.getFileName(clean)
                            if (fn.contains('.') && fn.substringAfterLast('.').length in 1..8) list.add(clean)
                        }
                        list.distinctBy { LocalFileManager.normalizeFileId(it) }
                    }

                    if (generatedFiles.isNotEmpty()) {
                        Column(
                            modifier = Modifier.padding(bottom = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            generatedFiles.forEach { rawFilePath ->
                                val cleanPath = remember(rawFilePath) {
                                    var p = rawFilePath.trim()
                                    if (p.startsWith("[") && p.contains("](") && p.endsWith(")")) {
                                        p = p.substringAfter("](").removeSuffix(")")
                                    }
                                    if (p.startsWith("file://")) p = p.removePrefix("file://")
                                    if (p.contains("#")) p = p.substringBefore("#")
                                    p.trim()
                                }
                                val fileName = remember(cleanPath) { cleanPath.substringAfterLast("/").ifBlank { "Artifact" } }
                                val ext = remember(fileName) { fileName.substringAfterLast(".", "").lowercase() }
                                val (icon, tint, typeBadge) = when {
                                    ext == "pdf" -> Triple(Icons.Default.PictureAsPdf, Color(0xFFE53935), "PDF")
                                    ext in setOf("png", "jpg", "jpeg", "webp") -> Triple(Icons.Default.Image, Color(0xFF1E88E5), "IMAGE")
                                    ext in setOf("md", "txt") -> Triple(Icons.Default.Article, ClaudeTerracotta, "NOTES")
                                    ext in setOf("py", "kt", "js", "ts", "java", "cpp", "c", "rs", "go") -> Triple(Icons.Default.Code, ChatGptBlue, ext.uppercase())
                                    ext in setOf("json", "csv", "tsv", "sql") -> Triple(Icons.Default.TableChart, ChatGptEmerald, "DATA")
                                    else -> Triple(Icons.Default.Description, ClaudeTerracotta, if (ext.isNotBlank()) ext.uppercase() else "DOC")
                                }

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenFile(cleanPath) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(tint.copy(alpha = 0.14f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = icon,
                                                    contentDescription = null,
                                                    tint = tint,
                                                    modifier = Modifier.size(19.dp)
                                                )
                                            }
                                            Spacer(Modifier.width(10.dp))
                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = fileName,
                                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Spacer(Modifier.width(6.dp))
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = tint.copy(alpha = 0.12f)
                                                    ) {
                                                        Text(
                                                            text = typeBadge,
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                fontFamily = FontFamily.Monospace,
                                                                fontWeight = FontWeight.Bold,
                                                                fontSize = 8.5.sp
                                                            ),
                                                            color = tint,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = "Artifact • Tap to view & save to phone",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                )
                                            }
                                        }
                                        Button(
                                            onClick = { onOpenFile(cleanPath) },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(30.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = tint)
                                        ) {
                                            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(13.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Open", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Autonomous Memory Update Banner (ChatGPT Style)
                    if (showMemoryActivityBadges && message.memoryUpdates.isNotEmpty()) {
                        Column(
                            modifier = Modifier.padding(bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            message.memoryUpdates.forEach { memUpdate ->
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = ClaudeTerracotta.copy(alpha = 0.10f),
                                    border = androidx.compose.foundation.BorderStroke(0.6.dp, ClaudeTerracotta.copy(alpha = 0.35f)),
                                    modifier = Modifier.clickable { onOpenMemory() }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text("✨", fontSize = 11.sp)
                                        Text(
                                            text = "Memory updated: \"$memUpdate\"",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                            color = ClaudeTerracotta,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Icon(
                                            Icons.Default.ChevronRight,
                                            contentDescription = null,
                                            tint = ClaudeTerracotta,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. Main Assistant Markdown Content with inline streaming cursor
                    if (message.content.isNotBlank()) {
                        MarkdownContent(
                            text = message.content,
                            isStreaming = showStreamingCursor && message.isStreaming,
                            onLinkClick = onOpenFile
                        )
                    }

                    // Initial streaming state before first token arrives: pulsing wave dots
                    if (message.isStreaming && message.content.isBlank() && message.thinking.isNullOrBlank()) {
                        val isDarkWaiting = MaterialTheme.colorScheme.background.red < 0.5f
                        val dotAccent = if (isDarkWaiting) Color(0xFFA78BFA) else Color(0xFF7C3AED)

                        val dotTransition = rememberInfiniteTransition(label = "typingDots")
                        val dot1Scale by dotTransition.animateFloat(
                            initialValue = 0.4f, targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = keyframes {
                                    durationMillis = 1200
                                    0.4f at 0 with FastOutSlowInEasing
                                    1f at 200 with FastOutSlowInEasing
                                    0.4f at 500
                                    0.4f at 1200
                                }
                            ), label = "dot1"
                        )
                        val dot2Scale by dotTransition.animateFloat(
                            initialValue = 0.4f, targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = keyframes {
                                    durationMillis = 1200
                                    0.4f at 150 with FastOutSlowInEasing
                                    1f at 350 with FastOutSlowInEasing
                                    0.4f at 650
                                    0.4f at 1200
                                }
                            ), label = "dot2"
                        )
                        val dot3Scale by dotTransition.animateFloat(
                            initialValue = 0.4f, targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = keyframes {
                                    durationMillis = 1200
                                    0.4f at 300 with FastOutSlowInEasing
                                    1f at 500 with FastOutSlowInEasing
                                    0.4f at 800
                                    0.4f at 1200
                                }
                            ), label = "dot3"
                        )

                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    if (isDarkWaiting) Color(0xFF1E1B2E) else Color(0xFFF5F3FF)
                                )
                                .border(
                                    0.8.dp,
                                    if (isDarkWaiting) Color(0xFF3D3560) else Color(0xFFDDD8F8),
                                    RoundedCornerShape(20.dp)
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(dot1Scale, dot2Scale, dot3Scale).forEach { scale ->
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .graphicsLayer { scaleX = scale; scaleY = scale }
                                        .clip(CircleShape)
                                        .background(dotAccent.copy(alpha = 0.5f + 0.5f * scale))
                                )
                            }
                        }
                    }

                    // 4. Action Bar below assistant message
                    if (!message.isStreaming) {
                        val wordCount = remember(message.content) {
                            if (message.content.isBlank()) 0
                            else message.content.trim().split(Regex("\\s+")).count { it.isNotBlank() }
                        }
                        val estTokens = (wordCount * 1.33).toInt()
                        val isDarkAction = MaterialTheme.colorScheme.background.red < 0.5f
                        val pillBg = if (isDarkAction) Color(0xFF1E1E22) else Color(0xFFF4F4F6)
                        val pillBorder = if (isDarkAction) Color(0xFF2E2E34) else Color(0xFFE2E2E8)
                        val iconTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)

                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Meta row: timestamp + word/token count + generation speed
                            Row(
                                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = formatMsgRelativeTime(message.timestamp),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                )
                                if (wordCount > 0) {
                                    Text(
                                        text = "· $wordCount w · ~$estTokens tk",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                    )
                                }
                                if (message.tokensPerSecond != null && message.tokensPerSecond > 0.0) {
                                    val speedStr = String.format(Locale.US, "%.1f", message.tokensPerSecond)
                                    val durStr = if (message.durationSeconds != null && message.durationSeconds > 0.0)
                                        " · ${String.format(Locale.US, "%.1fs", message.durationSeconds)}" else ""
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                        border = androidx.compose.foundation.BorderStroke(
                                            0.5.dp,
                                            ClaudeTerracotta.copy(alpha = 0.3f)
                                        ),
                                        modifier = Modifier.padding(start = 4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Bolt,
                                                contentDescription = null,
                                                modifier = Modifier.size(10.dp),
                                                tint = ClaudeTerracotta
                                            )
                                            Text(
                                                text = "$speedStr t/s$durStr",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                ),
                                                color = ClaudeTerracotta
                                            )
                                        }
                                    }
                                }
                            }

                            // Action pills row (horizontal scrollable with haptics)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // ── Pill 1: Core actions (Copy, Pin, Speak, Share) ──
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(pillBg)
                                        .border(0.6.dp, pillBorder, RoundedCornerShape(14.dp))
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Copy with animated checkmark
                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            cm.setPrimaryClip(ClipData.newPlainText("Response", message.content))
                                            isCopied = true
                                            Toast.makeText(context, "Copied response", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                                            contentDescription = "Copy",
                                            modifier = Modifier.size(15.dp),
                                            tint = if (isCopied) Color(0xFF10B981) else iconTint
                                        )
                                    }
                                    // Pin toggle
                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onTogglePin()
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.PushPin,
                                            contentDescription = if (message.isPinned) "Unpin" else "Pin",
                                            modifier = Modifier.size(14.dp),
                                            tint = if (message.isPinned) Color(0xFFFFB300) else iconTint
                                        )
                                    }
                                    // TTS Speak / Read Aloud
                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onToggleSpeak()
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            if (isSpeaking) Icons.Default.Stop else Icons.Default.VolumeUp,
                                            contentDescription = if (isSpeaking) "Stop" else "Read aloud",
                                            modifier = Modifier.size(15.dp),
                                            tint = if (isSpeaking) ClaudeTerracotta else iconTint
                                        )
                                    }
                                    // System Share Sheet
                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            val intent = Intent().apply {
                                                action = Intent.ACTION_SEND
                                                putExtra(Intent.EXTRA_TEXT, message.content)
                                                type = "text/plain"
                                            }
                                            context.startActivity(Intent.createChooser(intent, "Share response"))
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Share,
                                            contentDescription = "Share",
                                            modifier = Modifier.size(14.dp),
                                            tint = iconTint
                                        )
                                    }
                                }

                                // ── Pill 2: Feedback thumbs (Like / Dislike) ──
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(pillBg)
                                        .border(0.6.dp, pillBorder, RoundedCornerShape(14.dp))
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onFeedback("like")
                                            Toast.makeText(context, if (message.feedback == "like") "Removed" else "Thanks!", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.ThumbUp,
                                            contentDescription = "Good response",
                                            modifier = Modifier.size(14.dp),
                                            tint = if (message.feedback == "like") ClaudeTerracotta else iconTint
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onFeedback("dislike")
                                            Toast.makeText(context, if (message.feedback == "dislike") "Removed" else "Noted", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.ThumbDown,
                                            contentDescription = "Bad response",
                                            modifier = Modifier.size(14.dp),
                                            tint = if (message.feedback == "dislike") Color(0xFFEF5350) else iconTint
                                        )
                                    }
                                }

                                // ── Pill 3: Interactive Quick Emoji Reactions Bar ──
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(pillBg)
                                        .border(0.6.dp, pillBorder, RoundedCornerShape(14.dp))
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    listOf("👍", "❤️", "💡", "🔥", "🚀").forEach { emoji ->
                                        val isSelected = selectedReaction == emoji
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .background(if (isSelected) ClaudeTerracotta.copy(alpha = 0.2f) else Color.Transparent)
                                                .clickable {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    selectedReaction = if (isSelected) null else emoji
                                                    Toast.makeText(
                                                        context,
                                                        if (selectedReaction != null) "Reacted with $emoji" else "Reaction removed",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(text = emoji, fontSize = 13.sp)
                                        }
                                    }
                                }

                                // ── Pill 4: Branch navigator (if branched edits exist) ──
                                if (message.totalBranches > 1) {
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(pillBg)
                                            .border(0.6.dp, pillBorder, RoundedCornerShape(14.dp))
                                            .padding(horizontal = 4.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(
                                            onClick = {
                                                if (message.branchIndex > 0) {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    onSwitchBranch(message.parentMessageId ?: message.id, message.branchIndex - 1)
                                                }
                                            },
                                            enabled = message.branchIndex > 0,
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.ChevronLeft,
                                                contentDescription = "Previous branch",
                                                modifier = Modifier.size(14.dp),
                                                tint = if (message.branchIndex > 0) ClaudeTerracotta else iconTint.copy(alpha = 0.3f)
                                            )
                                        }
                                        Text(
                                            text = "${message.branchIndex + 1}/${message.totalBranches}",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.5.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        IconButton(
                                            onClick = {
                                                if (message.branchIndex < message.totalBranches - 1) {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    onSwitchBranch(message.parentMessageId ?: message.id, message.branchIndex + 1)
                                                }
                                            },
                                            enabled = message.branchIndex < message.totalBranches - 1,
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.ChevronRight,
                                                contentDescription = "Next branch",
                                                modifier = Modifier.size(14.dp),
                                                tint = if (message.branchIndex < message.totalBranches - 1) ClaudeTerracotta else iconTint.copy(alpha = 0.3f)
                                            )
                                        }
                                    }
                                }

                                // ── Pill 5: Continue generating pill ──
                                if (isLastAssistant && !message.isStreaming && message.content.isNotBlank()) {
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(ClaudeTerracotta.copy(alpha = 0.10f))
                                            .border(0.7.dp, ClaudeTerracotta.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                                            .clickable {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onContinueGenerating()
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(13.dp),
                                            tint = ClaudeTerracotta
                                        )
                                        Text(
                                            "Continue",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
                                            color = ClaudeTerracotta
                                        )
                                    }
                                }

                                // ── Pill 6: Utility actions (Retry, Reply, Delete) ──
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(pillBg)
                                        .border(0.6.dp, pillBorder, RoundedCornerShape(14.dp))
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isLastAssistant) {
                                        IconButton(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onRetry()
                                            },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Refresh,
                                                contentDescription = "Regenerate",
                                                modifier = Modifier.size(14.dp),
                                                tint = iconTint
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onReply()
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Reply,
                                            contentDescription = "Reply",
                                            modifier = Modifier.size(14.dp),
                                            tint = iconTint
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onDeleteMessage()
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.DeleteOutline,
                                            contentDescription = "Delete",
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                            }
                        }

                        // 5. Contextual Quick Follow-Up Suggestion Chips
                        if (showFollowupSuggestions && isLastAssistant && !message.isStreaming && message.content.isNotBlank()) {
                            val suggestions = remember(message.content) {
                                val list = mutableListOf<Triple<String, String, Pair<ImageVector, Color>>>()
                                val cLower = message.content.lowercase()
                                val hasCode = cLower.contains("```")
                                val hasError = cLower.contains("error") || cLower.contains("exception") || cLower.contains("failed") || cLower.contains("fatal")
                                val isExplanation = cLower.contains("because") || cLower.contains("means") || cLower.contains("concept") || cLower.contains("overview")
                                val hasSteps = cLower.contains("1.") || cLower.contains("step 1") || cLower.contains("first,")

                                if (hasCode) {
                                    list.add(Triple("Multi-lens audit", "Perform an exhaustive 3-lens adversarial code review (correctness, security, maintainability) per flash38-swe-protocol.", Icons.Default.Security to ChipSecurityAccent))
                                    list.add(Triple("Edge-case tests", "Write comprehensive unit tests with edge cases (empty, boundary, negative, concurrent) for this code.", Icons.Default.Code to ChipCodeAccent))
                                    list.add(Triple("Optimize code", "How can this code be optimized for maximum speed and memory efficiency?", Icons.Default.Bolt to ChipTakeawayAccent))
                                    list.add(Triple("Save to file", "Please save this code to an appropriate workspace file using the write_to_file tool.", Icons.Default.Article to ChipSearchAccent))
                                } else if (hasError) {
                                    list.add(Triple("How to fix", "What are the exact step-by-step instructions to fix this error?", Icons.Default.Tune to ChipBugAccent))
                                    list.add(Triple("Root cause analysis", "Can you explain the deep root cause of why this error happens?", Icons.Default.Psychology to ChipExplainAccent))
                                    list.add(Triple("Prevent regression", "How can we prevent this issue from happening again in the future?", Icons.Default.Security to ChipSecurityAccent))
                                    list.add(Triple("Search web", "Search the web for known fixes and official documentation for this error.", Icons.Default.Language to ChipSearchAccent))
                                } else if (hasSteps || isExplanation) {
                                    list.add(Triple("Technical deep dive", "Can you explain the technical internals in deeper detail?", Icons.Default.Psychology to ChipExplainAccent))
                                    list.add(Triple("Key takeaways", "Summarize the key takeaways and actionable points in bullet format.", Icons.Default.CheckCircle to ChipTakeawayAccent))
                                    list.add(Triple("Concrete examples", "Can you provide concrete practical examples illustrating this?", Icons.Default.AutoAwesome to ChipSecurityAccent))
                                    list.add(Triple("Search web", "Search the web for the latest updates and real-time community discussions on this.", Icons.Default.Language to ChipSearchAccent))
                                } else {
                                    list.add(Triple("Explain in detail", "Please explain this step-by-step in detail.", Icons.Default.Psychology to ChipExplainAccent))
                                    list.add(Triple("Key takeaways", "What are the key takeaways from this?", Icons.Default.CheckCircle to ChipTakeawayAccent))
                                    list.add(Triple("Practical examples", "Can you provide concrete practical examples for this?", Icons.Default.AutoAwesome to ChipSecurityAccent))
                                    list.add(Triple("Search web", "Search the web for real-time live sources on this topic.", Icons.Default.Language to ChipSearchAccent))
                                }
                                list.take(4)
                            }

                            AnimatedVisibility(
                                visible = suggestions.isNotEmpty(),
                                enter = fadeIn(animationSpec = tween(320)) + slideInHorizontally(animationSpec = tween(320))
                            ) {
                                LazyRow(
                                    modifier = Modifier.padding(top = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(suggestions) { (label, prompt, iconAndAccent) ->
                                        val (catIcon, catAccent) = iconAndAccent
                                        val isDarkChip = MaterialTheme.colorScheme.background.red < 0.5f
                                        Surface(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onSendSuggestion(prompt)
                                            },
                                            shape = RoundedCornerShape(20.dp),
                                            color = if (isDarkChip) Color(0xFF1E1E26) else Color(0xFFFFFFFF),
                                            border = androidx.compose.foundation.BorderStroke(
                                                1.dp,
                                                catAccent.copy(alpha = if (isDarkChip) 0.65f else 0.45f)
                                            ),
                                            shadowElevation = 2.dp,
                                            modifier = Modifier.shadow(
                                                elevation = 2.dp,
                                                shape = RoundedCornerShape(20.dp),
                                                spotColor = catAccent.copy(alpha = 0.25f)
                                            )
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(18.dp)
                                                        .clip(CircleShape)
                                                        .background(catAccent.copy(alpha = 0.18f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = catIcon,
                                                        contentDescription = null,
                                                        tint = catAccent,
                                                        modifier = Modifier.size(11.dp)
                                                    )
                                                }
                                                Text(
                                                    text = label,
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 11.5.sp,
                                                        letterSpacing = 0.2.sp
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        else -> {
            // System message styled pill with retry capability
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                        .border(0.6.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    val isError = message.content.startsWith("⚠️")
                    Icon(
                        if (isError) Icons.Default.Warning else Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = message.content.removePrefix("⚠️").trim(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isError) {
                        Surface(
                            onClick = onRetry,
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(0.6.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                            modifier = Modifier.padding(start = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Retry",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = "Retry",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Animated Typing Bubble showing Next AI Avatar, 3 bouncing hopping dots, and "Thinking…" text.
 */
@Composable
fun ClaudeTypingBubble() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Circular Logo Avatar matching Assistant messages
        NextAiLogo(
            size = 30.dp,
            showBackground = true
        )

        Spacer(Modifier.width(12.dp))

        val infiniteTransition = rememberInfiniteTransition(label = "dots")
        val offset1 by infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = -4f,
            animationSpec = infiniteRepeatable(
                keyframes { durationMillis = 1000; 0f at 0; -4f at 250; 0f at 500; 0f at 1000 },
                RepeatMode.Restart
            ), label = "o1"
        )
        val offset2 by infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = -4f,
            animationSpec = infiniteRepeatable(
                keyframes { durationMillis = 1000; 0f at 150; -4f at 400; 0f at 650; 0f at 1000 },
                RepeatMode.Restart
            ), label = "o2"
        )
        val offset3 by infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = -4f,
            animationSpec = infiniteRepeatable(
                keyframes { durationMillis = 1000; 0f at 300; -4f at 550; 0f at 800; 0f at 1000 },
                RepeatMode.Restart
            ), label = "o3"
        )
        val textAlpha by infiniteTransition.animateFloat(
            initialValue = 0.45f, targetValue = 0.9f,
            animationSpec = infiniteRepeatable(
                keyframes { durationMillis = 1200; 0.45f at 0; 0.9f at 600; 0.45f at 1200 },
                RepeatMode.Restart
            ), label = "textAlpha"
        )

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .border(0.6.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(offset1, offset2, offset3).forEach { offset ->
                Box(
                    modifier = Modifier
                        .offset(y = offset.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(ClaudeTerracotta)
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Thinking…",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = textAlpha)
            )
        }
    }
}

/**
 * Animated Terracotta Cursor Blink for streaming markdown response flow.
 */
@Composable
fun StreamingCursorBlink() {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 550, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )
    Box(
        modifier = Modifier
            .size(width = 2.5.dp, height = 15.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(ClaudeTerracotta.copy(alpha = alpha))
    )
}
