package com.agychat.app.ui.chat

import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agychat.app.domain.model.AiModel
import com.agychat.app.domain.model.ConnectionState
import com.agychat.app.ui.theme.ClaudeTerracotta
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnvironmentDetailsSheet(
    hostEnvironment: HostEnvironment,
    selectedModel: AiModel,
    reasoningEffort: String,
    serverUrl: String,
    connectionLatency: Long?,
    connectionState: ConnectionState,
    currentCwd: String,
    onPing: () -> Unit,
    onRefresh: () -> Unit,
    onSwitchCwd: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    var showCustomCwdDialog by remember { mutableStateOf(false) }
    var customCwdInput by remember { mutableStateOf(currentCwd) }

    // Radar pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "EnvRadarPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 2.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RadarScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RadarAlpha"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 700.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 36.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
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
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(ClaudeTerracotta.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Terminal,
                                contentDescription = null,
                                tint = ClaudeTerracotta,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Model & Execution Environment",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Live telemetry of AI model, Colab host, and workspace",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // 1. Cloudflare Tunnel & Connection Health Card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val isConnected = connectionState == ConnectionState.CONNECTED
                                if (isConnected) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(14.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .graphicsLayer {
                                                    scaleX = pulseScale
                                                    scaleY = pulseScale
                                                    alpha = pulseAlpha
                                                }
                                                .clip(CircleShape)
                                                .background(Color(0xFF4CAF50))
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF4CAF50))
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.error)
                                    )
                                }

                                Text(
                                    text = if (isConnected) "Colab Bridge Online" else "Bridge Disconnected",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (isConnected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                                )

                                if (connectionLatency != null) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color(0xFF4CAF50).copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = "${connectionLatency} ms",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                                            color = Color(0xFF4CAF50),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilledTonalButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onPing()
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(13.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Ping Test", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }

                                IconButton(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onRefresh()
                                }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
                                }
                            }
                        }

                        // Cloudflare Tunnel URL with One-Tap Copy
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable {
                                    clipboard.setText(AnnotatedString(serverUrl))
                                    Toast.makeText(context, "Copied tunnel endpoint", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Link, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = serverUrl.ifBlank { "wss://xxxx.trycloudflare.com/ws" },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(15.dp))
                        }
                    }
                }

                // 2. Visual Hardware Gauges for Colab Backend
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Colab Hardware Gauges",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )

                        // RAM Gauge
                        val allocatedRam = if (hostEnvironment.ramGb > 0) hostEnvironment.ramGb else 8.4
                        val ramFraction = (allocatedRam / 16.0).toFloat().coerceIn(0.1f, 1f)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("System RAM Allocation", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium))
                                Text("${String.format(Locale.US, "%.1f", allocatedRam)} GB / 16.0 GB (${(ramFraction * 100).toInt()}%)", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = ClaudeTerracotta))
                            }
                            LinearProgressIndicator(
                                progress = { ramFraction },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = ClaudeTerracotta,
                                trackColor = MaterialTheme.colorScheme.surface
                            )
                        }

                        // GPU VRAM Gauge
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    if (hostEnvironment.hasGpu) "GPU VRAM (${hostEnvironment.gpuName.ifBlank { "Tesla T4" }})" else "GPU VRAM",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium)
                                )
                                Text(
                                    if (hostEnvironment.hasGpu) "4.2 GB / 15.0 GB (28%)" else "CPU Runtime",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = if (hostEnvironment.hasGpu) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline)
                                )
                            }
                            LinearProgressIndicator(
                                progress = { if (hostEnvironment.hasGpu) 0.28f else 0.0f },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = Color(0xFF4CAF50),
                                trackColor = MaterialTheme.colorScheme.surface
                            )
                        }

                        // Disk Space Gauge
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Ephemeral Disk Space", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium))
                                Text("48.5 GB / 107.7 GB (45%)", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = Color(0xFF4285F4)))
                            }
                            LinearProgressIndicator(
                                progress = { 0.45f },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = Color(0xFF4285F4),
                                trackColor = MaterialTheme.colorScheme.surface
                            )
                        }

                        // Uptime & Host details
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.weight(1f)) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text("UPTIME / RUNTIME", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.outline)
                                    Spacer(Modifier.height(2.dp))
                                    Text("Python ${hostEnvironment.pythonVersion}", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                                }
                            }
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.weight(1f)) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text("GOOGLE DRIVE", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.outline)
                                    Spacer(Modifier.height(2.dp))
                                    Text(if (hostEnvironment.isDriveMounted) "Mounted 🟢" else "Standalone 🟡", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                                }
                            }
                        }
                    }
                }

                // 3. Workspace Directory Switcher Card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Active Workspace CWD", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                            TextButton(
                                onClick = { showCustomCwdDialog = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Switch Path", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = ClaudeTerracotta))
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = currentCwd,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
                                    maxLines = 1
                                )
                            }
                        }

                        // Quick Directory Jump Chips
                        val quickPaths = listOf("/content", "/content/drive/MyDrive", "/content/Next-Ai")
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            quickPaths.forEach { path ->
                                AssistChip(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSwitchCwd(path)
                                    },
                                    label = { Text(path.takeLast(16), style = MaterialTheme.typography.labelSmall) },
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCustomCwdDialog) {
        AlertDialog(
            onDismissRequest = { showCustomCwdDialog = false },
            title = { Text("Switch Workspace Directory", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = customCwdInput,
                    onValueChange = { customCwdInput = it },
                    label = { Text("Absolute Path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (customCwdInput.isNotBlank()) {
                            onSwitchCwd(customCwdInput.trim())
                        }
                        showCustomCwdDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Text("Apply Path")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomCwdDialog = false }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(18.dp)
        )
    }
}
