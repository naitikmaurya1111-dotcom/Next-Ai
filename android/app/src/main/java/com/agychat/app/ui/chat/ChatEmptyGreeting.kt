package com.agychat.app.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agychat.app.domain.model.HostEnvironment
import com.agychat.app.domain.model.PluginItem
import com.agychat.app.ui.common.NextAiLogo
import com.agychat.app.ui.theme.ChatGptBlue
import com.agychat.app.ui.theme.ChatGptEmerald
import com.agychat.app.ui.theme.ChatGptPurple
import com.agychat.app.ui.theme.ClaudeTerracotta

/**
 * 2X Elevated Empty State & Welcome Hero for Next AI.
 * Features dynamic time-of-day greeting, role-aware prompt suggestions,
 * interactive category filter pills, and live environment/profile pills.
 */
@Composable
fun EmptyChatGreeting(
    plugins: List<PluginItem>,
    onPromptCardClick: (String) -> Unit,
    selectedModelName: String = "Next AI",
    memoriesCount: Int = 0,
    hasCustomInstructions: Boolean = false,
    userName: String = "",
    userOccupation: String = "",
    depthLevel: String = "Expert",
    showMemoryBadge: Boolean = true,
    hostEnvironment: HostEnvironment = HostEnvironment(),
    onOpenEnvironmentSheet: () -> Unit = {},
    onOpenMemorySheet: () -> Unit = {},
    onOpenCustomInstructions: () -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f

    val greetingTitle = remember(userName) {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val timeGreeting = when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Good night"
        }
        if (userName.isNotBlank()) "$timeGreeting, $userName" else "What can I help with?"
    }

    val greetingSubtitle = remember(userOccupation) {
        if (userOccupation.isNotBlank()) "Ready to assist with your $userOccupation workflow"
        else "Choose a quick starter below or type a request to get started"
    }

    var activeCategory by remember { mutableStateOf("All") }
    val categories = remember {
        listOf("All", "Coding 💻", "Research 🌐", "Architecture 🏛️", "Autonomous 🚀")
    }

    val starterPrompts = remember(userOccupation, depthLevel, activeCategory) {
        val allPrompts = listOf(
            PromptStarter(
                title = "Deep Architectural Review",
                subtitle = "Analyze state hoisting, concurrency & performance",
                prompt = "/boost analyze system architecture, state hoisting patterns, and performance bottlenecks",
                category = "Architecture 🏛️",
                icon = Icons.Default.AccountTree,
                accentColor = Color(0xFF0284C7)
            ),
            PromptStarter(
                title = "Autonomous Goal Execution",
                subtitle = "Run continuous loop until objectives are solved",
                prompt = "/goal review codebase and build automated tests for edge cases",
                category = "Autonomous 🚀",
                icon = Icons.Default.RocketLaunch,
                accentColor = ChatGptEmerald
            ),
            PromptStarter(
                title = "Real-time Internet Research",
                subtitle = "Live search for newest docs, benchmarks & papers",
                prompt = "/browser search latest AI engineering breakthroughs and official documentation",
                category = "Research 🌐",
                icon = Icons.Default.Language,
                accentColor = ChatGptBlue
            ),
            PromptStarter(
                title = "Write & Refactor Code",
                subtitle = "Strict SWE protocol with multi-pass adversarial review",
                prompt = "/boost write a clean, high-performance module with comprehensive edge-case handling",
                category = "Coding 💻",
                icon = Icons.Default.Code,
                accentColor = ClaudeTerracotta
            ),
            PromptStarter(
                title = "Structured Roadmap Plan",
                subtitle = "Break down complex milestones into verified steps",
                prompt = "/plan design step-by-step implementation milestones with verification gates",
                category = "Architecture 🏛️",
                icon = Icons.Default.Assignment,
                accentColor = ChatGptPurple
            ),
            PromptStarter(
                title = "Mock Technical Interview",
                subtitle = "Rigorous interactive drill down on system design",
                prompt = "/grill-me conduct a rigorous senior software engineering system design interview",
                category = "Coding 💻",
                icon = Icons.Default.Psychology,
                accentColor = Color(0xFFF59E0B)
            )
        )

        if (activeCategory == "All") allPrompts
        else allPrompts.filter { it.category == activeCategory }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Next AI Masterpiece Emblem with ambient radial glow
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                ClaudeTerracotta.copy(alpha = if (isDark) 0.22f else 0.14f),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )
            NextAiLogo(
                size = 72.dp,
                showBackground = true
            )
        }

        Spacer(Modifier.height(14.dp))

        Text(
            text = greetingTitle,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp,
                fontSize = 25.sp
            ),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = greetingSubtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(16.dp))

        // Context Status Badges (Model, Colab Environment, Memories, Custom Instructions)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Model Badge
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "✦",
                        color = ClaudeTerracotta,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = selectedModelName,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Colab Environment Badge
            Surface(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onOpenEnvironmentSheet()
                },
                shape = RoundedCornerShape(12.dp),
                color = ChatGptEmerald.copy(alpha = 0.12f),
                border = BorderStroke(0.6.dp, ChatGptEmerald.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(ChatGptEmerald)
                    )
                    Text(
                        text = "Colab · ${hostEnvironment.gitRepo ?: "Workspace"}",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
                        color = ChatGptEmerald
                    )
                }
            }

            if (showMemoryBadge && memoriesCount > 0) {
                Spacer(Modifier.width(8.dp))
                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onOpenMemorySheet()
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = ClaudeTerracotta.copy(alpha = 0.12f),
                    border = BorderStroke(0.6.dp, ClaudeTerracotta.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🧠 $memoriesCount memories",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = ClaudeTerracotta
                        )
                    }
                }
            }

            if (hasCustomInstructions) {
                Spacer(Modifier.width(8.dp))
                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onOpenCustomInstructions()
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = ChatGptPurple.copy(alpha = 0.12f),
                    border = BorderStroke(0.6.dp, ChatGptPurple.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "👤 Profile Active",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = ChatGptPurple
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Interactive Category Filter Pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            categories.forEach { category ->
                val isSelected = activeCategory == category
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isSelected) ClaudeTerracotta.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    border = BorderStroke(
                        width = if (isSelected) 1.2.dp else 0.6.dp,
                        color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        activeCategory = category
                    }
                ) {
                    Text(
                        text = category,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        val isTablet = LocalConfiguration.current.screenWidthDp >= 600

        AnimatedContent(
            targetState = starterPrompts,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "starterPromptsTransition"
        ) { prompts ->
            if (isTablet) {
                // Modern 2x2 Action Starter Grid for Tablets
                Column(
                    modifier = Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    prompts.chunked(2).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            rowItems.forEach { item ->
                                StarterPromptCard(
                                    item = item,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onPromptCardClick(item.prompt)
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            } else {
                // Single Column Stack for Phones
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    prompts.forEach { item ->
                        StarterPromptCard(
                            item = item,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onPromptCardClick(item.prompt)
                            }
                        )
                    }
                }
            }
        }
    }
}

private data class PromptStarter(
    val title: String,
    val subtitle: String,
    val prompt: String,
    val category: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val accentColor: Color
)

@Composable
private fun StarterPromptCard(
    item: PromptStarter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        border = BorderStroke(
            0.8.dp,
            if (isDark) Color(0xFF282830) else Color(0xFFE4E4EB)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(item.accentColor.copy(alpha = if (isDark) 0.18f else 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    item.icon,
                    contentDescription = null,
                    tint = item.accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))

            Icon(
                Icons.Default.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = item.accentColor.copy(alpha = 0.85f)
            )
        }
    }
}
