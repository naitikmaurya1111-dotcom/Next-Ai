package com.agychat.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.agychat.app.domain.model.CustomInstructions
import com.agychat.app.domain.model.Personalization
import com.agychat.app.ui.chat.ChatViewModel
import com.agychat.app.ui.theme.ClaudeTerracotta
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class InstructionPreset(
    val name: String,
    val icon: String,
    val instruction: String,
    val summary: String
)

private val RESPONSE_PRESETS = listOf(
    InstructionPreset(
        name = "Direct & Concise",
        icon = "⚡",
        instruction = "Be direct, concise, and eliminate unnecessary conversational filler. Answer promptly with high information density, clear structure, and minimal preamble.",
        summary = "No fluff, straight to answers"
    ),
    InstructionPreset(
        name = "Educational",
        icon = "🎓",
        instruction = "Explain concepts clearly using step-by-step breakdowns, intuitive real-world analogies, and beginner-to-expert scaffolding. Encourage deeper understanding.",
        summary = "Step-by-step & intuitive analogies"
    ),
    InstructionPreset(
        name = "Technical",
        icon = "💻",
        instruction = "Provide production-grade, highly optimized code with rigorous error handling, typed architectures, and idiomatic conventions. Never omit details or use placeholders.",
        summary = "Production code, zero placeholders"
    ),
    InstructionPreset(
        name = "Warm & Conversational",
        icon = "🤝",
        instruction = "Adopt an encouraging, engaging, and collaborative tone. Be thoughtful, supportive, and provide empathetic context alongside technical explanations.",
        summary = "Friendly, supportive & collaborative"
    )
)

private val ABOUT_YOU_TEMPLATES = listOf(
    "Senior Android & Kotlin Developer working with Jetpack Compose, Room, Hilt, and Coroutines.",
    "Machine Learning Researcher focusing on LLM reasoning architectures, Python, PyTorch, and CUDA.",
    "Full-Stack Software Engineer building modern reactive web applications with TypeScript and Go.",
    "Computer Science Student learning algorithms, system design, and software engineering principles."
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomInstructionsSheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    val currentInstructions by viewModel.customInstructions.collectAsState()
    val currentPersonalization by viewModel.personalization.collectAsState()

    var isEnabled by remember(currentInstructions) { mutableStateOf(currentInstructions.isEnabled) }
    var aboutUserText by remember(currentInstructions, currentPersonalization) {
        mutableStateOf(
            if (currentInstructions.aboutUser.isNotBlank()) currentInstructions.aboutUser
            else currentPersonalization.aboutUser
        )
    }
    var responsePreferencesText by remember(currentInstructions, currentPersonalization) {
        mutableStateOf(
            if (currentInstructions.responsePreferences.isNotBlank()) currentInstructions.responsePreferences
            else currentPersonalization.responsePreferences
        )
    }
    var selectedPresetName by remember(currentInstructions) {
        mutableStateOf(currentInstructions.tonePreset)
    }

    var selectedPart by remember { mutableIntStateOf(0) }
    var saveSuccessMessage by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 36.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(ClaudeTerracotta.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Tune,
                                contentDescription = null,
                                tint = ClaudeTerracotta,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Custom Instructions",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "ChatGPT-grade instructions applied to every conversation",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Master Toggle Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isEnabled) ClaudeTerracotta.copy(alpha = 0.10f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    border = BorderStroke(
                        0.8.dp,
                        if (isEnabled) ClaudeTerracotta.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Enable Custom Instructions",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (isEnabled) "AI actively follows your profile & response preferences"
                                else "AI uses standard default persona without personalization",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                isEnabled = it
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = ClaudeTerracotta
                            )
                        )
                    }
                }

                // Two-Part Tab Navigation
                TabRow(
                    selectedTabIndex = selectedPart,
                    containerColor = Color.Transparent,
                    contentColor = ClaudeTerracotta
                ) {
                    Tab(
                        selected = selectedPart == 0,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedPart = 0
                        },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    "1. About You",
                                    fontWeight = if (selectedPart == 0) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 13.sp
                                )
                                if (aboutUserText.isNotBlank()) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(ClaudeTerracotta)
                                    )
                                }
                            }
                        }
                    )
                    Tab(
                        selected = selectedPart == 1,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedPart = 1
                        },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    "2. Response Preferences",
                                    fontWeight = if (selectedPart == 1) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 13.sp
                                )
                                if (responsePreferencesText.isNotBlank()) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(ClaudeTerracotta)
                                    )
                                }
                            }
                        }
                    )
                }

                // Part 1: What would you like Next AI to know about you?
                if (selectedPart == 0) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "What would you like Next AI to know about you to provide better responses?",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Include your profession, tech stack, interests, coding style, or project background.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Quick Starter Templates Chips
                        Text(
                            text = "Quick Starter Profiles:",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.outline
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(ABOUT_YOU_TEMPLATES) { template ->
                                AssistChip(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        aboutUserText = if (aboutUserText.isBlank()) template
                                        else "$aboutUserText\n$template"
                                    },
                                    label = {
                                        Text(
                                            text = template.take(28) + "...",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                    },
                                    shape = RoundedCornerShape(10.dp)
                                )
                            }
                        }

                        OutlinedTextField(
                            value = aboutUserText,
                            onValueChange = { aboutUserText = it },
                            placeholder = {
                                Text("e.g. I am a senior Android engineer building Next AI. I prefer idiomatic Kotlin, Jetpack Compose, clean architecture, and concise explanations.")
                            },
                            minLines = 6,
                            maxLines = 10,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            supportingText = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Shared across all conversations")
                                    Text("${aboutUserText.length} characters")
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ClaudeTerracotta,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                            )
                        )
                    }
                }

                // Part 2: How would you like Next AI to respond?
                if (selectedPart == 1) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "How would you like Next AI to respond?",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Choose a preset style or write specific rules for tone, length, depth, and formatting.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Preset Style Chips (Direct & Concise, Educational, Technical, Warm & Conversational)
                        Text(
                            text = "Response Style Presets:",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.outline
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            RESPONSE_PRESETS.take(2).forEach { preset ->
                                val isSelected = selectedPresetName == preset.name
                                Surface(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        selectedPresetName = preset.name
                                        responsePreferencesText = preset.instruction
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) ClaudeTerracotta.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    border = BorderStroke(
                                        if (isSelected) 1.5.dp else 0.6.dp,
                                        if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(preset.icon, fontSize = 13.sp)
                                            Text(
                                                text = preset.name,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                                                ),
                                                color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        Text(
                                            text = preset.summary,
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            RESPONSE_PRESETS.drop(2).forEach { preset ->
                                val isSelected = selectedPresetName == preset.name
                                Surface(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        selectedPresetName = preset.name
                                        responsePreferencesText = preset.instruction
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) ClaudeTerracotta.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    border = BorderStroke(
                                        if (isSelected) 1.5.dp else 0.6.dp,
                                        if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(preset.icon, fontSize = 13.sp)
                                            Text(
                                                text = preset.name,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                                                ),
                                                color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        Text(
                                            text = preset.summary,
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        OutlinedTextField(
                            value = responsePreferencesText,
                            onValueChange = {
                                responsePreferencesText = it
                                selectedPresetName = "Custom"
                            },
                            placeholder = {
                                Text("e.g. Always write complete compile-ready code without placeholders. Provide critical code review without unnecessary praise.")
                            },
                            minLines = 6,
                            maxLines = 10,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            supportingText = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Preset: $selectedPresetName")
                                    Text("${responsePreferencesText.length} characters")
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ClaudeTerracotta,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                            )
                        )
                    }
                }

                // Save Feedback Banner
                AnimatedVisibility(
                    visible = saveSuccessMessage != null,
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically()
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF4CAF50).copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = saveSuccessMessage ?: "",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = Color(0xFF2E7D32)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            aboutUserText = ""
                            responsePreferencesText = ""
                            selectedPresetName = "Balanced"
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Clear All")
                    }

                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            // Save to CustomInstructions
                            val updatedInstructions = CustomInstructions(
                                aboutUser = aboutUserText.trim(),
                                responsePreferences = responsePreferencesText.trim(),
                                tonePreset = selectedPresetName,
                                isEnabled = isEnabled
                            )
                            viewModel.saveCustomInstructions(updatedInstructions)

                            // Also synchronize with Personalization
                            val currentP = viewModel.personalization.value
                            val updatedPersonalization = currentP.copy(
                                aboutUser = aboutUserText.trim(),
                                responsePreferences = responsePreferencesText.trim(),
                                toneStyle = selectedPresetName,
                                isEnabled = isEnabled
                            )
                            viewModel.savePersonalization(updatedPersonalization)

                            // Visual Save Feedback
                            saveSuccessMessage = "Instructions saved & applied to active chat!"
                            coroutineScope.launch {
                                delay(1200)
                                onDismiss()
                            }
                        },
                        modifier = Modifier.weight(2f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Save Instructions", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
