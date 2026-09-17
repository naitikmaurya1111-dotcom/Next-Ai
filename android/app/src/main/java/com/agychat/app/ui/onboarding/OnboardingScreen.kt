package com.agychat.app.ui.onboarding

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agychat.app.domain.model.Personalization
import com.agychat.app.ui.chat.ChatViewModel
import com.agychat.app.ui.common.NextAiLogo
import com.agychat.app.ui.theme.ClaudeTerracotta

@Composable
fun OnboardingScreen(
    viewModel: ChatViewModel,
    onFinishOnboarding: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var step by remember { mutableIntStateOf(0) }

    var userName by remember { mutableStateOf("") }
    var occupation by remember { mutableStateOf("") }
    var expertise by remember { mutableStateOf("") }
    var selectedDepth by remember { mutableStateOf("Expert") }
    var selectedTone by remember { mutableStateOf("Direct") }

    fun completeOnboarding(skipPersonalization: Boolean = false) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("onboarding_completed", true).apply()

        if (!skipPersonalization && (userName.isNotBlank() || occupation.isNotBlank())) {
            val updated = Personalization(
                name = userName.trim(),
                occupation = occupation.trim(),
                expertise = expertise.trim(),
                depthLevel = selectedDepth,
                toneStyle = selectedTone,
                isEnabled = true
            )
            viewModel.savePersonalization(updated)
        }
        onFinishOnboarding()
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (step > 0) {
                    IconButton(onClick = { step-- }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                } else {
                    Spacer(Modifier.width(48.dp))
                }

                // Step Dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(3) { index ->
                        Box(
                            modifier = Modifier
                                .size(if (step == index) 18.dp else 8.dp, 8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (step == index) ClaudeTerracotta
                                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                        )
                    }
                }

                TextButton(onClick = { completeOnboarding(skipPersonalization = true) }) {
                    Text("Skip", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    ) { innerPadding ->
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInHorizontally(tween(350)) { it } + fadeIn(tween(350)))
                        .togetherWith(slideOutHorizontally(tween(350)) { -it } + fadeOut(tween(350)))
                } else {
                    (slideInHorizontally(tween(350)) { -it } + fadeIn(tween(350)))
                        .togetherWith(slideOutHorizontally(tween(350)) { it } + fadeOut(tween(350)))
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) { currentStep ->
            when (currentStep) {
                0 -> WelcomeStep(onNext = { step = 1 })
                1 -> ProfileStep(
                    userName = userName,
                    onNameChange = { userName = it },
                    occupation = occupation,
                    onOccupationChange = { occupation = it },
                    expertise = expertise,
                    onExpertiseChange = { expertise = it },
                    onNext = { step = 2 }
                )
                2 -> PreferencesStep(
                    selectedDepth = selectedDepth,
                    onDepthChange = { selectedDepth = it },
                    selectedTone = selectedTone,
                    onToneChange = { selectedTone = it },
                    onFinish = { completeOnboarding(skipPersonalization = false) }
                )
            }
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 24.dp)
        ) {
            NextAiLogo(size = 80.dp, showBackground = true)

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Welcome to Next AI",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp
                ),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Next-generation pair programming and conversational intelligence connected directly to Antigravity CLI.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(Modifier.height(32.dp))

            // Feature Highlights Cards
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureHighlightCard(
                    icon = Icons.Default.Terminal,
                    title = "Antigravity CLI Integration",
                    subtitle = "Seamless connection with remote models, executing tools, and terminal sessions."
                )
                FeatureHighlightCard(
                    icon = Icons.Default.Psychology,
                    title = "Autonomous Memory & Recall",
                    subtitle = "Next AI remembers your background, preferences, and project details across chats."
                )
                FeatureHighlightCard(
                    icon = Icons.Default.CallSplit,
                    title = "Non-Destructive Branching",
                    subtitle = "Edit prompts or regenerate answers without losing prior conversation paths."
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
        ) {
            Text("Get Started", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ProfileStep(
    userName: String,
    onNameChange: (String) -> Unit,
    occupation: String,
    onOccupationChange: (String) -> Unit,
    expertise: String,
    onExpertiseChange: (String) -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "Tell us about yourself",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = "Next AI personalizes its responses, code snippets, and explanations based on who you are.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )

            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = userName,
                onValueChange = onNameChange,
                label = { Text("What should Next AI call you?") },
                placeholder = { Text("e.g. Alex, Maya") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = ClaudeTerracotta) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = occupation,
                onValueChange = onOccupationChange,
                label = { Text("Your role / occupation") },
                placeholder = { Text("e.g. Android Developer, ML Researcher, Student") },
                leadingIcon = { Icon(Icons.Default.WorkOutline, contentDescription = null, tint = ClaudeTerracotta) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = expertise,
                onValueChange = onExpertiseChange,
                label = { Text("Primary tech stack or interests") },
                placeholder = { Text("e.g. Kotlin, Jetpack Compose, Python, Rust") },
                leadingIcon = { Icon(Icons.Default.Code, contentDescription = null, tint = ClaudeTerracotta) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
        ) {
            Text("Continue", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun PreferencesStep(
    selectedDepth: String,
    onDepthChange: (String) -> Unit,
    selectedTone: String,
    onToneChange: (String) -> Unit,
    onFinish: () -> Unit
) {
    val depthLevels = listOf("Beginner", "Intermediate", "Expert", "Research")
    val tones = listOf(
        "Direct" to "Fast, concise, zero fluff",
        "Detailed" to "Comprehensive with deep explanations",
        "Socratic" to "Guiding questions to help you think",
        "Casual" to "Friendly and conversational"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "Fine-tune your AI",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = "Customize reasoning depth and communication style to match your workflow.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))

            Text(
                text = "Reasoning & Knowledge Depth",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                depthLevels.forEach { depth ->
                    FilterChip(
                        selected = selectedDepth == depth,
                        onClick = { onDepthChange(depth) },
                        label = { Text(depth) },
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = "Response Tone Style",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )

            Spacer(Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tones.forEach { (tone, desc) ->
                    val isSelected = selectedTone == tone
                    Surface(
                        onClick = { onToneChange(tone) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) ClaudeTerracotta.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(
                            if (isSelected) 1.5.dp else 0.6.dp,
                            if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onToneChange(tone) },
                                colors = RadioButtonDefaults.colors(selectedColor = ClaudeTerracotta)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = tone,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onFinish,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Complete Setup", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

@Composable
private fun FeatureHighlightCard(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(ClaudeTerracotta.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = ClaudeTerracotta,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }
    }
}
