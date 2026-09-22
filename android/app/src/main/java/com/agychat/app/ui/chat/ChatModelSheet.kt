package com.agychat.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agychat.app.domain.model.AiModel
import com.agychat.app.domain.model.ModelRegistry
import com.agychat.app.ui.theme.ClaudeTerracotta
import kotlin.math.roundToInt

data class ModelCapabilities(
    val badges: List<Pair<String, String>>, // (label, emoji)
    val contextWindow: String,
    val providerColor: Color
)

private fun getModelCapabilities(model: AiModel): ModelCapabilities {
    val id = model.id.lowercase()
    return when {
        id.contains("gemini-3.8") -> ModelCapabilities(
            badges = listOf("Multimodal" to "🖼️", "Fast" to "⚡", "Tool Calling" to "🛠️"),
            contextWindow = "1M tokens",
            providerColor = Color(0xFF4285F4)
        )
        id.contains("gemini-3.7") -> ModelCapabilities(
            badges = listOf("Deep Reasoning" to "🧠", "Multimodal" to "🖼️", "Tool Calling" to "🛠️"),
            contextWindow = "1M tokens",
            providerColor = Color(0xFF4285F4)
        )
        id.contains("gemini-3.6") -> ModelCapabilities(
            badges = listOf("Fast" to "⚡", "Multimodal" to "🖼️"),
            contextWindow = "1M tokens",
            providerColor = Color(0xFF4285F4)
        )
        id.contains("gemini-3.1-pro") -> ModelCapabilities(
            badges = listOf("Deep Reasoning" to "🧠", "Multimodal" to "🖼️", "Pro Math" to "📐"),
            contextWindow = "2M tokens",
            providerColor = Color(0xFF4285F4)
        )
        id.contains("claude-sonnet") -> ModelCapabilities(
            badges = listOf("Deep Reasoning" to "🧠", "Tool Calling" to "🛠️", "Coding SOTA" to "💻"),
            contextWindow = "200k tokens",
            providerColor = ClaudeTerracotta
        )
        id.contains("claude-opus") -> ModelCapabilities(
            badges = listOf("Deep Reasoning" to "🧠", "Max Depth" to "🌊", "Tool Calling" to "🛠️"),
            contextWindow = "200k tokens",
            providerColor = ClaudeTerracotta
        )
        id.contains("gpt-oss") -> ModelCapabilities(
            badges = listOf("Tool Calling" to "🛠️", "Fast" to "⚡", "Open Weights" to "🔓"),
            contextWindow = "128k tokens",
            providerColor = Color(0xFF10A37F)
        )
        else -> ModelCapabilities(
            badges = listOf("Multimodal" to "🖼️", "Tool Calling" to "🛠️"),
            contextWindow = "1M tokens",
            providerColor = Color(0xFF8E24AA)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelBottomSheet(
    selectedModel: AiModel,
    reasoningEffort: String = "high",
    onSelectEffort: (String) -> Unit = {},
    onSelectModel: (AiModel) -> Unit,
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 700.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .navigationBarsPadding()
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(ClaudeTerracotta.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "✦",
                                color = ClaudeTerracotta,
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp
                            )
                        }
                        Column {
                            Text(
                                text = "Model & Intelligence",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Flagship CLI models & cognitive reasoning depth",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Interactive Thinking Effort Slider Card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(
                                    Icons.Default.Psychology,
                                    contentDescription = null,
                                    tint = ClaudeTerracotta,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Cognitive Reasoning Depth",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            if (!selectedModel.supportsEffort) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = ClaudeTerracotta.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "Fixed in Model",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                        color = ClaudeTerracotta,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        val effortKeys = listOf("low", "medium", "high")
                        val sliderPosition = when (reasoningEffort.lowercase()) {
                            "low" -> 0f
                            "medium" -> 1f
                            else -> 2f
                        }

                        if (selectedModel.supportsEffort) {
                            // Interactive Thinking Effort Slider
                            Slider(
                                value = sliderPosition,
                                onValueChange = { newPos ->
                                    val targetIndex = newPos.roundToInt().coerceIn(0, 2)
                                    val targetKey = effortKeys[targetIndex]
                                    if (!targetKey.equals(reasoningEffort, ignoreCase = true)) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSelectEffort(targetKey)
                                    }
                                },
                                steps = 1,
                                valueRange = 0f..2f,
                                colors = SliderDefaults.colors(
                                    thumbColor = ClaudeTerracotta,
                                    activeTrackColor = ClaudeTerracotta,
                                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // 3 Preset Options Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val effortOptions = listOf(
                                Triple("low", "Low", "🚀 Fast"),
                                Triple("medium", "Medium", "⚡ Balanced"),
                                Triple("high", "High", "🧠 Deep")
                            )

                            effortOptions.forEach { (effortKey, label, badge) ->
                                val isEffortSelected = reasoningEffort.equals(effortKey, ignoreCase = true)
                                Surface(
                                    onClick = {
                                        if (selectedModel.supportsEffort) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onSelectEffort(effortKey)
                                        }
                                    },
                                    enabled = selectedModel.supportsEffort,
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isEffortSelected) ClaudeTerracotta.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(
                                        if (isEffortSelected) 1.5.dp else 0.8.dp,
                                        if (isEffortSelected) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    ),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = badge,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = if (isEffortSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = "AVAILABLE CLI MODELS",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
                    color = MaterialTheme.colorScheme.outline
                )

                Spacer(Modifier.height(8.dp))

                // Models List with Capability Badges & Context Window
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 440.dp)
                ) {
                    items(ModelRegistry.ALL_MODELS, key = { it.id }) { model ->
                        val isSelected = selectedModel.id == model.id
                        val caps = getModelCapabilities(model)

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .border(
                                    width = if (isSelected) 1.5.dp else 0.8.dp,
                                    color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSelectModel(model)
                                    onDismiss()
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) ClaudeTerracotta.copy(alpha = 0.10f)
                                else MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            text = model.name,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                                        )

                                        // Context Window Badge (1M tokens, 200k tokens)
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = caps.providerColor.copy(alpha = 0.12f)
                                        ) {
                                            Text(
                                                text = caps.contextWindow,
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                                color = caps.providerColor,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = "Active",
                                            tint = ClaudeTerracotta,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                Text(
                                    text = model.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                // Capability Badges Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    caps.badges.forEach { (badgeTitle, badgeEmoji) ->
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                                            ) {
                                                Text(badgeEmoji, fontSize = 10.sp)
                                                Text(
                                                    text = badgeTitle,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
