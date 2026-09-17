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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agychat.app.domain.model.Personalization
import com.agychat.app.ui.chat.ChatViewModel
import com.agychat.app.ui.theme.ClaudeTerracotta

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomInstructionsSheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit
) {
    val currentPersonalization by viewModel.personalization.collectAsState()
    var draft by remember(currentPersonalization) { mutableStateOf(currentPersonalization) }
    var selectedSection by remember { mutableIntStateOf(0) }
    val sectionTabs = listOf("👤 Profile", "🎛️ Behavior", "📋 Context")

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
                    .padding(bottom = 32.dp)
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
                                text = "Personalization & Custom Persona",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "ChatGPT-grade behavioral instructions across all chats",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Master Persona Switch Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (draft.isEnabled) ClaudeTerracotta.copy(alpha = 0.10f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        0.8.dp,
                        if (draft.isEnabled) ClaudeTerracotta.copy(alpha = 0.4f)
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
                                text = "Enable Personalization",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (draft.isEnabled) "AI actively follows your profile and behavioral directives"
                                else "AI uses generic default persona without personal context",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = draft.isEnabled,
                            onCheckedChange = { draft = draft.copy(isEnabled = it) }
                        )
                    }
                }

                // Section Navigation Tabs
                TabRow(
                    selectedTabIndex = selectedSection,
                    containerColor = Color.Transparent,
                    contentColor = ClaudeTerracotta
                ) {
                    sectionTabs.forEachIndexed { idx, title ->
                        Tab(
                            selected = selectedSection == idx,
                            onClick = { selectedSection = idx },
                            text = {
                                Text(
                                    title,
                                    fontWeight = if (selectedSection == idx) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp
                                )
                            }
                        )
                    }
                }

                // Tab Content
                when (selectedSection) {
                    0 -> {
                        // Section: Who You Are
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = draft.name,
                                onValueChange = { draft = draft.copy(name = it) },
                                label = { Text("Your Name") },
                                placeholder = { Text("e.g. Naitik") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                            OutlinedTextField(
                                value = draft.occupation,
                                onValueChange = { draft = draft.copy(occupation = it) },
                                label = { Text("Occupation / Role") },
                                placeholder = { Text("e.g. Senior Android Developer, ML Researcher") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                            OutlinedTextField(
                                value = draft.expertise,
                                onValueChange = { draft = draft.copy(expertise = it) },
                                label = { Text("Skills & Expertise") },
                                placeholder = { Text("e.g. Kotlin, Compose, Python, PyTorch, Clean Architecture") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = draft.country,
                                    onValueChange = { draft = draft.copy(country = it) },
                                    label = { Text("Country") },
                                    placeholder = { Text("India") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                OutlinedTextField(
                                    value = draft.age,
                                    onValueChange = { draft = draft.copy(age = it) },
                                    label = { Text("Age") },
                                    placeholder = { Text("22") },
                                    singleLine = true,
                                    modifier = Modifier.weight(0.7f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        }
                    }
                    1 -> {
                        // Section: Tone & Response Behavior
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Tone Style
                            Text("Tone Style", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val tones = listOf("Direct", "Formal", "Casual", "Socratic", "Empathetic")
                                items(tones) { tone ->
                                    FilterChip(
                                        selected = draft.toneStyle == tone,
                                        onClick = { draft = draft.copy(toneStyle = tone) },
                                        label = { Text(tone) }
                                    )
                                }
                            }

                            // Knowledge Depth
                            Text("Knowledge Depth", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val depths = listOf("Beginner", "Intermediate", "Expert", "Research")
                                items(depths) { depth ->
                                    FilterChip(
                                        selected = draft.depthLevel == depth,
                                        onClick = { draft = draft.copy(depthLevel = depth) },
                                        label = { Text(depth) }
                                    )
                                }
                            }

                            // Response Length
                            Text("Response Length", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val lengths = listOf("Concise", "Balanced", "Detailed", "Adaptive")
                                items(lengths) { len ->
                                    FilterChip(
                                        selected = draft.responseLength == len,
                                        onClick = { draft = draft.copy(responseLength = len) },
                                        label = { Text(len) }
                                    )
                                }
                            }

                            // Toggles
                            BehaviorToggleRow(
                                title = "Code Examples",
                                subtitle = "Proactively provide code implementations",
                                checked = draft.enableExamples,
                                onCheckedChange = { draft = draft.copy(enableExamples = it) }
                            )
                            BehaviorToggleRow(
                                title = "Proactive Insights",
                                subtitle = "Flag blind spots & volunteer relevant info unasked",
                                checked = draft.enableProactiveInsights,
                                onCheckedChange = { draft = draft.copy(enableProactiveInsights = it) }
                            )
                            BehaviorToggleRow(
                                title = "Critical Feedback (No Sugarcoating)",
                                subtitle = "Directly point out suboptimal design or code flaws",
                                checked = draft.enableCriticalFeedback,
                                onCheckedChange = { draft = draft.copy(enableCriticalFeedback = it) }
                            )
                            BehaviorToggleRow(
                                title = "Use Emoji in Responses",
                                subtitle = "Allow emoji structure in explanations",
                                checked = draft.enableEmoji,
                                onCheckedChange = { draft = draft.copy(enableEmoji = it) }
                            )
                        }
                    }
                    2 -> {
                        // Section: Context & Directives
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = draft.customContext,
                                onValueChange = { draft = draft.copy(customContext = it) },
                                label = { Text("Current Project & Work Context") },
                                placeholder = { Text("e.g. Next AI app: Jetpack Compose, Room, Hilt, FastAPI, Colab Antigravity bridge.") },
                                minLines = 3,
                                maxLines = 5,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                            OutlinedTextField(
                                value = draft.avoidTopics,
                                onValueChange = { draft = draft.copy(avoidTopics = it) },
                                label = { Text("Topics to Avoid") },
                                placeholder = { Text("e.g. Fluff, obvious beginner tutorials, conversational filler") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                            OutlinedTextField(
                                value = draft.extraInstructions,
                                onValueChange = { draft = draft.copy(extraInstructions = it) },
                                label = { Text("Extra Directives") },
                                placeholder = { Text("e.g. Always write complete compile-ready code without placeholders.") },
                                minLines = 2,
                                maxLines = 4,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            draft = Personalization()
                            viewModel.savePersonalization(draft)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Reset")
                    }

                    Button(
                        onClick = {
                            viewModel.savePersonalization(draft)
                            onDismiss()
                        },
                        modifier = Modifier.weight(2f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Save Persona")
                    }
                }
            }
        }
    }
}

@Composable
private fun BehaviorToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = ClaudeTerracotta
            )
        )
    }
}
