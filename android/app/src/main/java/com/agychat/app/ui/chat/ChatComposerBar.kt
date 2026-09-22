package com.agychat.app.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.agychat.app.domain.PluginItem
import com.agychat.app.domain.model.AttachmentItem
import com.agychat.app.domain.model.Message
import com.agychat.app.ui.theme.*
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Action button states for the composer send/mic/stop button.
 */
enum class ActionButtonState { STOP, SEND, MIC }

/**
 * Recognized active slash command mode representations for banner pills.
 */
data class ActiveSlashMode(
    val prefix: String,
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val description: String = ""
)

/**
 * Format raw byte size into clean human-readable string.
 */
fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return ""
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.1f MB", mb)
}

/**
 * Query file size safely from Android ContentResolver or File URI.
 */
fun queryFileSizeSafe(context: Context, uriString: String, fallbackSize: Long = 0L): String {
    if (fallbackSize > 0) return formatFileSize(fallbackSize)
    try {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") {
            val f = File(uri.path ?: "")
            if (f.exists()) return formatFileSize(f.length())
        }
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex != -1 && cursor.moveToFirst()) {
                val size = cursor.getLong(sizeIndex)
                if (size > 0) return formatFileSize(size)
            }
        }
    } catch (_: Exception) {}
    return ""
}

/**
 * 2X Superior Floating Input Composer with Frosted Glass Styling, Animated Focus Glow,
 * Multi-Attachment Carousel, Voice Visualizer Integration, and Quick Actions.
 */
@Composable
fun ClaudeFloatingInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit = {},
    onOpenPlugins: () -> Unit,
    onAttachFile: () -> Unit,
    onToggleWebSearch: () -> Unit = {},
    onVoiceInput: () -> Unit = {},
    attachments: List<AttachmentItem> = emptyList(),
    attachment: AttachmentItem? = null,
    onRemoveAttachment: (AttachmentItem) -> Unit = {},
    onAddMoreAttachments: () -> Unit = onAttachFile,
    replyToMessage: Message? = null,
    onCancelReply: () -> Unit = {},
    isConnected: Boolean,
    isLoading: Boolean,
    selectedModelName: String? = null,
    onOpenModelSelector: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    var isFocused by remember { mutableStateOf(false) }
    var isInlineVoiceRecording by remember { mutableStateOf(false) }

    val activeAttachments = remember(attachments, attachment) {
        if (attachments.isNotEmpty()) attachments
        else if (attachment != null) listOf(attachment)
        else emptyList()
    }
    val canSend = (text.isNotBlank() || activeAttachments.isNotEmpty()) && isConnected

    // Recognized command modes with specialized color themes
    val recognizedModes = remember {
        listOf(
            ActiveSlashMode("/browser", "Web Search", Icons.Default.Language, ChatGptBlue, "Live web browsing and citation analysis"),
            ActiveSlashMode("/boost", "Deep Think", Icons.Default.AutoAwesome, ChatGptPurple, "Extended chain-of-thought reasoning"),
            ActiveSlashMode("/plan", "Plan Mode", Icons.Default.Assignment, Color(0xFF0284C7), "Structured step-by-step roadmap"),
            ActiveSlashMode("/goal", "Autonomous Goal", Icons.Default.RocketLaunch, Color(0xFF10B981), "Multi-step autonomous agent loop"),
            ActiveSlashMode("/learn", "Memory", Icons.Default.Psychology, Color(0xFFF59E0B), "Persistent memory fact learning"),
            ActiveSlashMode("/remember", "Memory", Icons.Default.Psychology, Color(0xFFF59E0B), "Persistent memory fact learning"),
            ActiveSlashMode("/schedule", "Scheduled", Icons.Default.Schedule, Color(0xFF8B5CF6), "Automated recurring background tasks"),
            ActiveSlashMode("/grill-me", "Interview", Icons.Default.QuestionAnswer, ClaudeTerracotta, "Rigorous mock interview and assessment"),
            ActiveSlashMode("/teamwork-preview", "Multi-Agent", Icons.Default.Groups, Color(0xFF06B6D4), "Collaborative multi-agent swarm execution"),
            ActiveSlashMode("/model", "Switch Model", Icons.Default.Tune, Color(0xFF1976D2), "Switch active AI intelligence model")
        )
    }
    val activeMode = recognizedModes.firstOrNull { text.startsWith(it.prefix) }
    val isWebSearchActive = activeMode?.prefix == "/browser"

    val isComposerElevated = isFocused || text.isNotBlank() || activeAttachments.isNotEmpty() || isInlineVoiceRecording

    // Dynamic specular border & glow transitions
    val composerElevation by animateDpAsState(
        targetValue = if (isComposerElevated) 7.5.dp else 2.5.dp,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "composerElevation"
    )
    val inputBorderColor by animateColorAsState(
        targetValue = when {
            isInlineVoiceRecording -> ClaudeTerracotta
            isWebSearchActive -> ChatGptBlue.copy(alpha = 0.90f)
            activeMode != null -> activeMode.color.copy(alpha = 0.85f)
            isComposerElevated -> ClaudeTerracotta.copy(alpha = 0.70f)
            isDark -> GlassComposerDarkBorder
            else -> GlassComposerLightBorder
        },
        animationSpec = tween(durationMillis = 220),
        label = "inputBorderColor"
    )
    val composerBg by animateColorAsState(
        targetValue = if (isDark) GlassComposerDarkBg else GlassComposerLightBg,
        animationSpec = tween(durationMillis = 220),
        label = "composerBg"
    )

    val composerShape = RoundedCornerShape(26.dp)

    Surface(
        shape = composerShape,
        color = composerBg,
        tonalElevation = if (isDark) 4.dp else 1.dp,
        shadowElevation = composerElevation,
        border = BorderStroke(
            width = if (isComposerElevated) 1.3.dp else 1.dp,
            color = inputBorderColor
        ),
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = composerElevation,
                shape = composerShape,
                spotColor = when {
                    isInlineVoiceRecording -> ClaudeTerracotta.copy(alpha = 0.35f)
                    isWebSearchActive -> ChatGptBlue.copy(alpha = 0.30f)
                    activeMode != null -> activeMode.color.copy(alpha = 0.25f)
                    isComposerElevated -> ClaudeTerracotta.copy(alpha = 0.22f)
                    else -> Color.Black.copy(alpha = 0.08f)
                },
                ambientColor = Color.Black.copy(alpha = 0.06f)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // ── Quoted Reply Preview Bar ──
            AnimatedVisibility(
                visible = replyToMessage != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                if (replyToMessage != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(26.dp)
                                    .background(ClaudeTerracotta, RoundedCornerShape(2.dp))
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Replying to ${if (replyToMessage.role == "user") "You" else "Assistant"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ClaudeTerracotta,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = replyToMessage.content.lines().firstOrNull()?.take(80) ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = onCancelReply,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel reply",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }

            // ── Multi-Attachment Horizontal Carousel Preview ──
            AnimatedVisibility(
                visible = activeAttachments.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                AttachmentCarouselPreview(
                    attachments = activeAttachments,
                    onRemove = onRemoveAttachment,
                    onAddMore = onAddMoreAttachments
                )
            }

            // ── Active Slash Mode Badge Pill (Dismissible) ──
            AnimatedVisibility(
                visible = activeMode != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                if (activeMode != null) {
                    Row(
                        modifier = Modifier
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(activeMode.color.copy(alpha = 0.12f))
                            .border(0.8.dp, activeMode.color.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            activeMode.icon,
                            contentDescription = null,
                            tint = activeMode.color,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = activeMode.label,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                            color = activeMode.color
                        )
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .clickable {
                                    val newText = if (text.startsWith("${activeMode.prefix} ")) {
                                        text.removePrefix("${activeMode.prefix} ")
                                    } else {
                                        text.removePrefix(activeMode.prefix).trimStart()
                                    }
                                    onTextChange(newText)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove mode",
                                tint = activeMode.color.copy(alpha = 0.8f),
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }

            // ── Main Content Area: Switch between Voice Waveform Visualizer & Text Input ──
            AnimatedContent(
                targetState = isInlineVoiceRecording,
                transitionSpec = {
                    (fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.95f))
                        .togetherWith(fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.95f))
                },
                label = "composerModeSwitch"
            ) { recordingActive ->
                if (recordingActive) {
                    VoiceRecordingVisualizerBar(
                        onCancel = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            isInlineVoiceRecording = false
                        },
                        onFinish = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            isInlineVoiceRecording = false
                            onVoiceInput()
                        }
                    )
                } else {
                    // Standard Expansive Full-Width Text Input
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = onTextChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { isFocused = it.isFocused },
                            placeholder = {
                                Text(
                                    text = if (isConnected) "Message Next AI or type / for tools..." else "Connect in Settings to chat...",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.5.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                )
                            },
                            maxLines = 6,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Default
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                cursorColor = ClaudeTerracotta
                            ),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 15.5.sp,
                                lineHeight = 22.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )

                        // Word / Token Count Badge for extended prompts
                        if (text.length > 60) {
                            val words = remember(text) { text.trim().split(Regex("\\s+")).count { it.isNotBlank() } }
                            val estTokens = (words * 1.33).toInt()
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 8.dp, end = 6.dp)
                            ) {
                                Text(
                                    text = "$words w · ~$estTokens t",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Medium),
                                    color = if (text.length > 4000) MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── Tier 2: Refined Action Toolbar (Left Tools & Right Send/Stop/Mic) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left Toolbar cluster: Attachments (+), Tools (✦), Web Search pill, Model chip
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Plus button (+) for Attachments with badge count indicator
                    Box {
                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onAttachFile()
                            },
                            shape = CircleShape,
                            color = if (activeAttachments.isNotEmpty()) ClaudeTerracotta.copy(alpha = 0.18f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                            border = BorderStroke(
                                0.8.dp,
                                if (activeAttachments.isNotEmpty()) ClaudeTerracotta.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "Add attachment or file",
                                    tint = if (activeAttachments.isNotEmpty()) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Badge count dot if attachments exist
                        if (activeAttachments.isNotEmpty()) {
                            Surface(
                                shape = CircleShape,
                                color = ClaudeTerracotta,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(16.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "${activeAttachments.size}",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // Slash Commands & Tools Button (✦)
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenPlugins()
                        },
                        shape = RoundedCornerShape(18.dp),
                        color = ClaudeTerracotta.copy(alpha = 0.12f),
                        border = BorderStroke(0.8.dp, ClaudeTerracotta.copy(alpha = 0.35f)),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = "Slash Commands and Tools",
                                tint = ClaudeTerracotta,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Tools",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.5.sp
                                ),
                                color = ClaudeTerracotta
                            )
                        }
                    }

                    // Quick Web Search Toggle Pill (🌐) with active state glow
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleWebSearch()
                        },
                        shape = RoundedCornerShape(18.dp),
                        color = if (isWebSearchActive) ChatGptBlue.copy(alpha = 0.18f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        border = BorderStroke(
                            0.9.dp,
                            if (isWebSearchActive) ChatGptBlue.copy(alpha = 0.75f)
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                Icons.Default.Language,
                                contentDescription = "Toggle Web Search",
                                modifier = Modifier.size(15.dp),
                                tint = if (isWebSearchActive) ChatGptBlue else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (isWebSearchActive) "Search ON" else "Search",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isWebSearchActive) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 11.5.sp
                                ),
                                color = if (isWebSearchActive) ChatGptBlue else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (isWebSearchActive) {
                                val infinitePulse = rememberInfiniteTransition(label = "searchPulse")
                                val pulseAlpha by infinitePulse.animateFloat(
                                    initialValue = 0.5f,
                                    targetValue = 1f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(800, easing = FastOutSlowInEasing),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "pulseAlpha"
                                )
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(ChatGptBlue.copy(alpha = pulseAlpha))
                                )
                            }
                        }
                    }

                    // Optional Model Quick Switcher Chip
                    if (!selectedModelName.isNullOrBlank() && onOpenModelSelector != null) {
                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onOpenModelSelector()
                            },
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = selectedModelName.take(12),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "Switch Model",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }

                // Right Action cluster: Clear text (✕) & Action Button (Send / Stop / Mic)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Clear (✕) Button when text is present
                    AnimatedVisibility(
                        visible = text.isNotBlank(),
                        enter = scaleIn(tween(150)) + fadeIn(tween(150)),
                        exit = scaleOut(tween(120)) + fadeOut(tween(120))
                    ) {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onTextChange("")
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear input",
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }

                    // Dynamic Send / Stop / Mic Action Button
                    val hasInput = text.isNotBlank() || activeAttachments.isNotEmpty()
                    val actionButtonState = when {
                        isLoading -> ActionButtonState.STOP
                        hasInput -> ActionButtonState.SEND
                        else -> ActionButtonState.MIC
                    }

                    AnimatedContent(
                        targetState = actionButtonState,
                        transitionSpec = {
                            (scaleIn(animationSpec = tween(180)) + fadeIn(animationSpec = tween(180)))
                                .togetherWith(scaleOut(animationSpec = tween(150)) + fadeOut(animationSpec = tween(150)))
                        },
                        label = "composerActionButton"
                    ) { state ->
                        when (state) {
                            ActionButtonState.STOP -> {
                                FilledIconButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onStop()
                                    },
                                    modifier = Modifier.size(40.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.Stop,
                                        contentDescription = "Stop Generating",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            ActionButtonState.SEND -> {
                                FilledIconButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        if (canSend) {
                                            onSend()
                                        } else {
                                            Toast.makeText(context, "Bridge offline. Tap Reconnect above.", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.size(40.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = if (canSend) ClaudeTerracotta
                                                         else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.ArrowUpward,
                                        contentDescription = "Send",
                                        tint = if (canSend) Color.White else MaterialTheme.colorScheme.surface,
                                        modifier = Modifier.size(21.dp)
                                    )
                                }
                            }
                            ActionButtonState.MIC -> {
                                Surface(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        // Start inline interactive waveform visualizer or launch recognizer
                                        isInlineVoiceRecording = true
                                    },
                                    shape = CircleShape,
                                    color = ClaudeTerracotta.copy(alpha = 0.14f),
                                    border = BorderStroke(0.9.dp, ClaudeTerracotta.copy(alpha = 0.4f)),
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Mic,
                                            contentDescription = "Voice dictation",
                                            tint = ClaudeTerracotta,
                                            modifier = Modifier.size(20.dp)
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

/**
 * Modern Multi-Attachment Horizontal Carousel Preview with thumbnail badges,
 * file names, file sizes, dismiss actions, and quick add (+) button.
 */
@Composable
fun AttachmentCarouselPreview(
    attachments: List<AttachmentItem>,
    onRemove: (AttachmentItem) -> Unit,
    onAddMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(attachments, key = { it.uri }) { att ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                shadowElevation = 1.dp,
                modifier = Modifier.widthIn(min = 160.dp, max = 220.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Thumbnail or File Type Badge
                    if (att.isImage) {
                        AsyncImage(
                            model = att.uri,
                            contentDescription = att.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(9.dp))
                        )
                    } else {
                        val ext = remember(att.name) { att.name.substringAfterLast(".", "").lowercase() }
                        val (docIcon, docColor, docType) = when {
                            ext == "pdf" || att.mimeType == "application/pdf" ->
                                Triple(Icons.Default.PictureAsPdf, Color(0xFFEF4444), "PDF")
                            ext in setOf("py", "kt", "js", "ts", "java", "html", "css", "cpp", "c", "rs", "go") ->
                                Triple(Icons.Default.Code, ChatGptBlue, "CODE")
                            ext in setOf("json", "csv", "tsv", "sql", "xml") ->
                                Triple(Icons.Default.TableChart, ChatGptEmerald, "DATA")
                            ext in setOf("zip", "tar", "gz", "rar", "7z") ->
                                Triple(Icons.Default.Archive, ChatGptAmber, "ARCHIVE")
                            ext in setOf("mp3", "wav", "m4a", "ogg") ->
                                Triple(Icons.Default.VolumeUp, ChatGptPurple, "AUDIO")
                            else ->
                                Triple(Icons.Default.InsertDriveFile, ClaudeTerracotta, "DOC")
                        }

                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(docColor.copy(alpha = 0.14f))
                                .border(0.5.dp, docColor.copy(alpha = 0.35f), RoundedCornerShape(9.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = docIcon,
                                contentDescription = docType,
                                modifier = Modifier.size(20.dp),
                                tint = docColor
                            )
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    // Title and Size
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = att.name,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val sizeStr = remember(att.uri, att.size) {
                            queryFileSizeSafe(context, att.uri, att.size)
                        }
                        Text(
                            text = listOfNotNull(
                                if (att.isImage) "Photo" else "Document",
                                sizeStr.ifBlank { null }
                            ).joinToString(" • "),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Remove (✕) Button
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onRemove(att)
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove attachment",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }

        // Quick (+) Button to add more attachments
        item {
            Surface(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onAddMore()
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Add more files",
                        tint = ClaudeTerracotta,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * 2X High-End Audio & Voice Recording Visualizer with pulsing wave bars,
 * recording duration counter, and clean tactile stop / cancel controls.
 */
@Composable
fun VoiceRecordingVisualizerBar(
    onCancel: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var secondsElapsed by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            secondsElapsed++
        }
    }

    val formattedTime = remember(secondsElapsed) {
        val mins = secondsElapsed / 60
        val secs = secondsElapsed % 60
        String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "recordingWaveform")
    val recDotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recDotAlpha"
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
        border = BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.35f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Cancel Button
            FilledTonalIconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onCancel()
                },
                modifier = Modifier.size(34.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Cancel recording",
                    modifier = Modifier.size(16.dp)
                )
            }

            // Center: Live REC badge + 14-Bar Waveform + Timer
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Red Recording Dot
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color.Red.copy(alpha = recDotAlpha))
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "REC",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = Color.Red.copy(alpha = recDotAlpha)
                        )
                    )
                }

                // 12 Harmonic Waveform Bars
                Row(
                    modifier = Modifier.height(28.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val barOffsets = listOf(0, 120, 240, 80, 300, 180, 360, 100, 260, 140, 320, 200)
                    barOffsets.forEachIndexed { index, delayMs ->
                        val barHeight by infiniteTransition.animateFloat(
                            initialValue = 4f,
                            targetValue = 26f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(
                                    durationMillis = 480 + (index * 40),
                                    delayMillis = delayMs % 150,
                                    easing = FastOutSlowInEasing
                                ),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "barHeight$index"
                        )

                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(barHeight.dp)
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(ClaudeTerracottaLight, ClaudeTerracotta)
                                    )
                                )
                        )
                    }
                }

                // Timer String
                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
            }

            // Right: Finish / Commit Button
            FilledIconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onFinish()
                },
                modifier = Modifier.size(34.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = ClaudeTerracotta,
                    contentColor = Color.White
                )
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Finish voice input",
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

/**
 * 2X Enhanced Autocomplete Slash Command Popup with Category Filter Badges,
 * Rich Item Icons, and Touch/Keyboard Selection.
 */
@Composable
fun SlashCommandAutocompletePopup(
    query: String,
    commands: List<PluginItem>,
    onSelect: (PluginItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var selectedCategory by remember { mutableStateOf("ALL") }

    val categories = remember(commands) {
        listOf("ALL") + commands.map { it.tag }.distinct().sorted()
    }

    val filteredCommands = remember(commands, selectedCategory) {
        if (selectedCategory == "ALL") commands
        else commands.filter { it.tag.equals(selectedCategory, ignoreCase = true) }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .shadow(10.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            // Header Bar with Title and Available Count
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "COMMANDS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${filteredCommands.size} available",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // Category Filter Badges Row
            if (categories.size > 2) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    categories.forEach { cat ->
                        val isSelected = cat == selectedCategory
                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedCategory = cat
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) ClaudeTerracotta.copy(alpha = 0.18f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(
                                0.7.dp,
                                if (isSelected) ClaudeTerracotta.copy(alpha = 0.6f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                            )
                        ) {
                            Text(
                                text = cat,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 10.sp,
                                    color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(
                thickness = 0.6.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp)
            )

            // Scrollable List of Filtered Commands
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
            ) {
                items(filteredCommands, key = { it.prefix }) { cmd ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSelect(cmd)
                            }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(Color(cmd.badgeColor).copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = cmd.icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = Color(cmd.badgeColor)
                            )
                        }

                        Spacer(Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = cmd.prefix,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = ClaudeTerracotta
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = cmd.title,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = cmd.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(Modifier.width(6.dp))

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(cmd.badgeColor).copy(alpha = 0.12f),
                            border = BorderStroke(0.6.dp, Color(cmd.badgeColor).copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = cmd.tag,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Color(cmd.badgeColor),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Modern Quick Slash Chips Row shown on empty canvas with curated commands & tools shortcut.
 */
@Composable
fun QuickSlashChipsRow(
    plugins: List<PluginItem>,
    onChipClick: (PluginItem) -> Unit,
    onOpenAllTools: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val curatedChips = remember {
        listOf(
            Triple("Search Web", "/browser ", Icons.Default.Language to ChatGptBlue),
            Triple("Deep Think", "/boost ", Icons.Default.AutoAwesome to ChatGptPurple),
            Triple("Plan", "/plan ", Icons.Default.Assignment to ClaudeTerracotta),
            Triple("Auto Goal", "/goal ", Icons.Default.RocketLaunch to ChatGptEmerald),
            Triple("Remember", "/remember ", Icons.Default.Psychology to ClaudeTerracottaDark),
            Triple("Switch Model", "/model ", Icons.Default.Tune to Color(0xFF1976D2))
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        curatedChips.forEach { (label, commandPrefix, iconAndColor) ->
            val (icon, accentColor) = iconAndColor
            val isDark = MaterialTheme.colorScheme.background.red < 0.5f
            Surface(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val trimmedPrefix = commandPrefix.trim()
                    val matching = plugins.firstOrNull { it.prefix == trimmedPrefix }
                        ?: if (trimmedPrefix == "/remember") plugins.firstOrNull { it.prefix == "/learn" } else null
                    if (matching != null) {
                        onChipClick(matching)
                    } else {
                        onChipClick(
                            PluginItem(
                                name = trimmedPrefix.removePrefix("/"),
                                title = label,
                                description = label,
                                icon = icon,
                                prefix = trimmedPrefix,
                                tag = "TOOL",
                                examplePrompt = "$trimmedPrefix ",
                                badgeColor = 0xFFD4704B
                            )
                        )
                    }
                },
                shape = RoundedCornerShape(18.dp),
                color = if (isDark) Color(0xFF1C1C24) else Color(0xFFFFFFFF),
                border = BorderStroke(
                    1.dp,
                    accentColor.copy(alpha = if (isDark) 0.55f else 0.4f)
                ),
                shadowElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = accentColor
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Surface(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onOpenAllTools()
            },
            shape = RoundedCornerShape(18.dp),
            color = ClaudeTerracotta.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.45f)),
            shadowElevation = 1.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = ClaudeTerracotta
                )
                Text(
                    text = "All Tools ✦",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.5.sp),
                    color = ClaudeTerracotta
                )
            }
        }
    }
}

/**
 * Direct alias for ClaudeFloatingInputBar matching standard ChatComposerBar terminology.
 */
@Composable
fun ChatComposerBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit = {},
    onOpenPlugins: () -> Unit,
    onAttachFile: () -> Unit,
    onToggleWebSearch: () -> Unit = {},
    onVoiceInput: () -> Unit = {},
    attachments: List<AttachmentItem> = emptyList(),
    attachment: AttachmentItem? = null,
    onRemoveAttachment: (AttachmentItem) -> Unit = {},
    onAddMoreAttachments: () -> Unit = onAttachFile,
    replyToMessage: Message? = null,
    onCancelReply: () -> Unit = {},
    isConnected: Boolean,
    isLoading: Boolean,
    selectedModelName: String? = null,
    onOpenModelSelector: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) = ClaudeFloatingInputBar(
    text = text,
    onTextChange = onTextChange,
    onSend = onSend,
    onStop = onStop,
    onOpenPlugins = onOpenPlugins,
    onAttachFile = onAttachFile,
    onToggleWebSearch = onToggleWebSearch,
    onVoiceInput = onVoiceInput,
    attachments = attachments,
    attachment = attachment,
    onRemoveAttachment = onRemoveAttachment,
    onAddMoreAttachments = onAddMoreAttachments,
    replyToMessage = replyToMessage,
    onCancelReply = onCancelReply,
    isConnected = isConnected,
    isLoading = isLoading,
    selectedModelName = selectedModelName,
    onOpenModelSelector = onOpenModelSelector,
    modifier = modifier
)
