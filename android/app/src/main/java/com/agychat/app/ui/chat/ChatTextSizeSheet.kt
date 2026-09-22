package com.agychat.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agychat.app.ui.theme.ClaudeTerracotta

/**
 * Modern Chat Text Size & Typography Scale Bottom Sheet.
 * Allows users to dynamically scale chat text with instant live preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTextSizeSheet(
    currentScale: Float,
    compactDensity: Boolean,
    onScaleChange: (Float) -> Unit,
    onCompactDensityToggle: () -> Unit,
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f

    val presets = listOf(
        Triple("Small", 0.85f, "Compact"),
        Triple("Default", 1.0f, "Standard"),
        Triple("Comfort", 1.15f, "Balanced"),
        Triple("Large", 1.30f, "Reading"),
        Triple("Giant", 1.45f, "Maximum")
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
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
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(ClaudeTerracotta.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.FormatSize,
                            contentDescription = null,
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Chat Text Size & Display",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Scale chat message text and density for comfortable reading",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Quick Preset Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                presets.forEach { (label, scale, _) ->
                    val isSelected = kotlin.math.abs(currentScale - scale) < 0.04f
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onScaleChange(scale)
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) ClaudeTerracotta.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = androidx.compose.foundation.BorderStroke(
                            width = if (isSelected) 1.5.dp else 0.8.dp,
                            color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                ),
                                color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "${(scale * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Fine-tune Slider
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Fine-tune Scale: ${(currentScale * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    TextButton(onClick = { onScaleChange(1.0f) }) {
                        Text("Reset (100%)", style = MaterialTheme.typography.labelSmall, color = ClaudeTerracotta)
                    }
                }
                Slider(
                    value = currentScale,
                    onValueChange = { onScaleChange(it) },
                    valueRange = 0.80f..1.50f,
                    steps = 13,
                    colors = SliderDefaults.colors(
                        thumbColor = ClaudeTerracotta,
                        activeTrackColor = ClaudeTerracotta,
                        inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
            }

            // Live Preview Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isDark) Color(0xFF16161A) else Color(0xFFF6F5F0),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                val previewDensity = LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = previewDensity.density,
                        fontScale = previewDensity.fontScale * currentScale
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "LIVE PREVIEW",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp,
                                fontSize = 10.sp
                            ),
                            color = ClaudeTerracotta
                        )

                        // Sample User message
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Surface(
                                shape = RoundedCornerShape(16.dp, 16.dp, 3.dp, 16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = "Can you explain mass-energy equivalence?",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }

                        // Sample AI response with math
                        Surface(
                            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 3.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(
                                    text = "Einstein's principle establishes that mass and energy are interchangeable:",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "E = mc²",
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontFamily = FontFamily.Serif,
                                        fontStyle = FontStyle.Italic,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = ClaudeTerracotta
                                )
                            }
                        }
                    }
                }
            }

            // Compact Spacing Toggle Row
            Surface(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onCompactDensityToggle()
                },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Compact message density",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (compactDensity) "Reduced bubble spacing (8dp)" else "Standard spacious spacing (16dp)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = compactDensity,
                        onCheckedChange = { onCompactDensityToggle() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = ClaudeTerracotta
                        )
                    )
                }
            }
        }
    }
}
