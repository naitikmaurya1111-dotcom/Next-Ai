package com.agychat.app.ui.settings

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agychat.app.domain.model.CustomInstructions
import com.agychat.app.ui.chat.ChatViewModel
import com.agychat.app.ui.theme.ClaudeTerracotta

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomInstructionsSheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit
) {
    val currentInstructions by viewModel.customInstructions.collectAsState()

    var aboutUser by remember(currentInstructions) { mutableStateOf(currentInstructions.aboutUser) }
    var responsePreferences by remember(currentInstructions) { mutableStateOf(currentInstructions.responsePreferences) }
    var tonePreset by remember(currentInstructions) { mutableStateOf(currentInstructions.tonePreset) }
    var isEnabled by remember(currentInstructions) { mutableStateOf(currentInstructions.isEnabled) }

    val toneOptions = listOf(
        "Default" to "Standard helpful assistant tone",
        "Direct & Concise" to "Fast, straightforward, no pleasantries or fluff",
        "Technical & Robust" to "Architecture-first, strict typing, edge cases",
        "Educational" to "In-depth explanations with step-by-step guidance",
        "Warm & Collaborative" to "Friendly, encouraging pair programmer"
    )

    val aboutChips = listOf(
        "💻 Full-Stack Developer",
        "📱 Android & Jetpack Compose",
        "🐍 Python & FastAPI Backend",
        "📍 Based in India",
        "🎯 Building Next AI App",
        "🚀 Open-Source Contributor"
    )

    val styleChips = listOf(
        "⚡ Direct to code, no fluff",
        "🏛️ Clean Architecture & SOLID",
        "✨ Production-ready code",
        "📝 Explain design decisions",
        "🚫 No unnecessary comments"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
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
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Personalize how Next AI responds across all chats",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Master Switch
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable for New Chats",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (isEnabled) "Instructions are injected into every prompt" else "Custom instructions are paused",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { isEnabled = it }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Response Tone Selector
            Text(
                text = "Response Tone & Style Preset",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(toneOptions) { (option, _) ->
                    val isSelected = tonePreset == option
                    FilterChip(
                        selected = isSelected,
                        onClick = { tonePreset = option },
                        label = {
                            Text(
                                text = option,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            )
                        }
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // Section 1: What would you like Next AI to know about you?
            Text(
                text = "What would you like Next AI to know about you?",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Share your background, current tech stack, role, or what you're working on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            // Suggestion chips for Section 1
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(aboutChips) { chip ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.clickable {
                            val cleanText = chip.substringAfter(" ").trim()
                            if (!aboutUser.contains(cleanText, ignoreCase = true)) {
                                aboutUser = if (aboutUser.isBlank()) cleanText else "$aboutUser\n• $cleanText"
                            }
                        }
                    ) {
                        Text(
                            text = chip,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = aboutUser,
                onValueChange = { aboutUser = it },
                placeholder = {
                    Text(
                        "e.g. Senior Android & Full-Stack engineer building Next AI. Proficient in Kotlin, Jetpack Compose, Python FastAPI, and Antigravity CLI.",
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(Modifier.height(18.dp))

            // Section 2: How would you like Next AI to respond?
            Text(
                text = "How would you like Next AI to respond?",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Specify code formatting, brevity, architectural expectations, or explanation style.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            // Suggestion chips for Section 2
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(styleChips) { chip ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.clickable {
                            val cleanText = chip.substringAfter(" ").trim()
                            if (!responsePreferences.contains(cleanText, ignoreCase = true)) {
                                responsePreferences = if (responsePreferences.isBlank()) cleanText else "$responsePreferences\n• $cleanText"
                            }
                        }
                    ) {
                        Text(
                            text = chip,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = responsePreferences,
                onValueChange = { responsePreferences = it },
                placeholder = {
                    Text(
                        "e.g. Be concise and direct. Provide complete, runnable code without placeholders. Prioritize clean architecture, testability, and error handling.",
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(Modifier.height(24.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        aboutUser = ""
                        responsePreferences = ""
                        tonePreset = "Default"
                        viewModel.saveCustomInstructions(
                            CustomInstructions(
                                aboutUser = "",
                                responsePreferences = "",
                                tonePreset = "Default",
                                isEnabled = isEnabled
                            )
                        )
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Clear")
                }

                Button(
                    onClick = {
                        viewModel.saveCustomInstructions(
                            CustomInstructions(
                                aboutUser = aboutUser.trim(),
                                responsePreferences = responsePreferences.trim(),
                                tonePreset = tonePreset,
                                isEnabled = isEnabled
                            )
                        )
                        onDismiss()
                    },
                    modifier = Modifier.weight(2f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save Instructions")
                }
            }
        }
    }
}
