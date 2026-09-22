package com.agychat.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.agychat.app.data.network.UrlSanitizer
import com.agychat.app.domain.model.AiModel
import com.agychat.app.domain.model.ConnectionState
import com.agychat.app.domain.model.ModelRegistry
import com.agychat.app.domain.model.ThinkingLevel
import com.agychat.app.ui.chat.ChatViewModel
import com.agychat.app.ui.chat.ManageMemorySheet
import com.agychat.app.ui.theme.ClaudeTerracotta
import com.agychat.app.ui.theme.ThemeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return "Never"
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000L -> "Just now"
        diff < 3_600_000L -> "${(diff / 60_000L).coerceAtLeast(1)}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
        else -> SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(timestamp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    chatViewModel: ChatViewModel = hiltViewModel(),
    onNavigateToPersonalization: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val prefs = remember { context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE) }
    val coroutineScope = rememberCoroutineScope()

    // ViewModel State Observers
    val connectionState by chatViewModel.connectionState.collectAsState()
    val selectedModel by chatViewModel.selectedModel.collectAsState()
    val isMemoryEnabled by chatViewModel.isMemoryEnabled.collectAsState()
    val isAutoMemoryEnabled by chatViewModel.isAutoMemoryEnabled.collectAsState()
    val personalization by chatViewModel.personalization.collectAsState()
    val enabledMemoriesCount by chatViewModel.enabledMemoriesCount.collectAsState(initial = 0)
    val allMemories by chatViewModel.memories.collectAsState(initial = emptyList())
    val cloudSyncStatus by chatViewModel.cloudSyncStatus.collectAsState()
    val isSyncing by chatViewModel.isSyncing.collectAsState()
    val showFollowupSuggestions by chatViewModel.showFollowupSuggestions.collectAsState()
    val showStreamingCursor by chatViewModel.showStreamingCursor.collectAsState()
    val compactMessageDensity by chatViewModel.compactMessageDensity.collectAsState()
    val showMemoryActivityBadges by chatViewModel.showMemoryActivityBadges.collectAsState()
    val latencyMs by chatViewModel.connectionLatencyMs.collectAsState()
    val conversations by chatViewModel.conversations.collectAsState(initial = emptyList())
    val hostEnv by chatViewModel.hostEnvironment.collectAsState()

    // Radar pulse animation for online server status
    val infiniteTransition = rememberInfiniteTransition(label = "RadarPulse")
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

    // Local Mutable UI States
    var mathCacheCount by remember { mutableIntStateOf(com.agychat.app.ui.chat.getKaTeXCacheCount()) }
    var hapticsEnabled by remember { mutableStateOf(prefs.getBoolean("haptics_enabled", true)) }
    var showRestoreConfirmDialog by remember { mutableStateOf(false) }
    var showMemorySheet by remember { mutableStateOf(false) }
    var showCustomInstructionsSheet by remember { mutableStateOf(false) }

    var serverUrl by remember {
        mutableStateOf(
            prefs.getString("server_url", "wss://andy-viruses-she-performs.trycloudflare.com/ws")
                ?: "wss://andy-viruses-she-performs.trycloudflare.com/ws"
        )
    }
    var autoResolveGist by remember {
        mutableStateOf(prefs.getBoolean("auto_resolve_gist_url", true))
    }
    var gistId by remember {
        mutableStateOf(
            prefs.getString("gist_id", com.agychat.app.data.network.GistUrlResolver.DEFAULT_GIST_ID)
                ?: com.agychat.app.data.network.GistUrlResolver.DEFAULT_GIST_ID
        )
    }
    var isSyncingGist by remember { mutableStateOf(false) }
    var driveAutoBackup by remember {
        mutableStateOf(prefs.getBoolean("drive_auto_backup", true))
    }
    var reasoningEffort by remember {
        mutableStateOf(prefs.getString("reasoning_effort", "high") ?: "high")
    }

    // Dynamic Backup File Size calculation
    val latestBackupFile = remember { File(context.filesDir, "backups/nextai_backup_latest.json") }
    val backupFileSizeStr = remember(cloudSyncStatus, isSyncing) {
        if (latestBackupFile.exists() && latestBackupFile.length() > 0) {
            val bytes = latestBackupFile.length()
            when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            }
        } else {
            val approxBytes = (conversations.size * 2500L).coerceAtLeast(14000L)
            "${approxBytes / 1024} KB (Estimated)"
        }
    }

    // JSON Activity Result Launchers
    val jsonFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    val jsonText = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }
                    if (!jsonText.isNullOrBlank()) {
                        val res = chatViewModel.driveManager.restoreFullBackupJson(jsonText)
                        Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
                        chatViewModel.loadConversations()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Restore failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val jsonFileExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    val fullJson = withContext(Dispatchers.IO) {
                        chatViewModel.driveManager.createFullBackupJson()
                    }
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(fullJson.toByteArray())
                        }
                    }
                    Toast.makeText(context, "Backup exported successfully!", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Export failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 700.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(Modifier.height(4.dp))

                // ─────────────────────────────────────────────────────────────
                // Section 1: Server Connection (Real-Time Health & Radar Pulse)
                // ─────────────────────────────────────────────────────────────
                SettingsCard(
                    title = "Server Connection",
                    icon = Icons.Default.Wifi,
                    badge = when (connectionState) {
                        ConnectionState.CONNECTED -> "CONNECTED" to Color(0xFF4CAF50)
                        ConnectionState.CONNECTING -> "CONNECTING" to Color(0xFFFFB300)
                        ConnectionState.ERROR -> "ERROR" to Color(0xFFEF5350)
                        ConnectionState.DISCONNECTED -> "DISCONNECTED" to Color(0xFF9E9E9E)
                    }
                ) {
                    // Real-Time Server Health Card
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(
                            1.dp,
                            when (connectionState) {
                                ConnectionState.CONNECTED -> Color(0xFF4CAF50).copy(alpha = 0.4f)
                                ConnectionState.CONNECTING -> Color(0xFFFFB300).copy(alpha = 0.4f)
                                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                // Status Badge with Radar Pulse Indicator
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val (statusText, statusColor) = when (connectionState) {
                                        ConnectionState.CONNECTED -> "Connected" to Color(0xFF4CAF50)
                                        ConnectionState.CONNECTING -> "Connecting" to Color(0xFFFFB300)
                                        ConnectionState.ERROR -> "Connection Error" to Color(0xFFEF5350)
                                        ConnectionState.DISCONNECTED -> "Disconnected" to Color(0xFF9E9E9E)
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = statusColor.copy(alpha = 0.15f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            // Radar Pulse indicator
                                            if (connectionState == ConnectionState.CONNECTED) {
                                                Box(
                                                    contentAlignment = Alignment.Center,
                                                    modifier = Modifier.size(14.dp)
                                                ) {
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
                                                        .background(statusColor)
                                                )
                                            }

                                            Text(
                                                text = statusText,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp
                                                ),
                                                color = statusColor
                                            )
                                        }
                                    }

                                    // Live Latency in ms Badge
                                    if (connectionState == ConnectionState.CONNECTED) {
                                        val latency = latencyMs
                                        val latColor = when {
                                            latency == null -> Color.Gray
                                            latency < 120 -> Color(0xFF4CAF50)
                                            latency < 300 -> Color(0xFFFFB300)
                                            else -> Color(0xFFEF5350)
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = latColor.copy(alpha = 0.12f)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(7.dp)
                                                        .clip(CircleShape)
                                                        .background(latColor)
                                                )
                                                Text(
                                                    text = if (latency != null) "${latency} ms" else "Active",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 11.sp
                                                    ),
                                                    color = latColor
                                                )
                                            }
                                        }
                                    }
                                }

                                // Ping Test Button
                                FilledTonalButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        chatViewModel.pingBridge()
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Ping Test", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }

                            Spacer(Modifier.height(10.dp))

                            // Live WebSocket URL Display
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Link,
                                    contentDescription = null,
                                    tint = ClaudeTerracotta,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = serverUrl.ifBlank { "No bridge URL configured" },
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                    color = if (serverUrl.isNotBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    val isUrlValid = remember(serverUrl) {
                        serverUrl.isBlank() || UrlSanitizer.isValidWebSocketUrl(serverUrl)
                    }

                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("WebSocket Tunnel URL") },
                        placeholder = { Text("wss://xxxx.trycloudflare.com/ws") },
                        singleLine = true,
                        isError = serverUrl.isNotBlank() && !isUrlValid,
                        supportingText = {
                            if (serverUrl.isNotBlank()) {
                                if (isUrlValid) {
                                    Text("✓ Valid secure WebSocket endpoint", color = Color(0xFF4CAF50), fontSize = 11.sp)
                                } else {
                                    Text("⚠️ Must match wss://xxxx.trycloudflare.com/ws", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                                }
                            }
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (serverUrl.isNotBlank()) {
                                    IconButton(onClick = { serverUrl = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                                    }
                                }
                                IconButton(onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                    val clip = cm?.primaryClip
                                    if (clip != null && clip.itemCount > 0) {
                                        val rawClip = clip.getItemAt(0).text?.toString() ?: ""
                                        val cleaned = UrlSanitizer.normalizeWebSocketUrl(rawClip) ?: rawClip.trim()
                                        serverUrl = cleaned
                                        Toast.makeText(context, "Pasted and sanitized URL", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                    }
                                }) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste", modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val cleanUrl = UrlSanitizer.normalizeWebSocketUrl(serverUrl)
                            if (cleanUrl != null) {
                                serverUrl = cleanUrl
                                prefs.edit().putString("server_url", cleanUrl).apply()
                                chatViewModel.connectToServer(cleanUrl)
                                Toast.makeText(context, "Connecting to bridge...", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Please enter a valid WebSocket URL (e.g. wss://xxxx.trycloudflare.com/ws)", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Connect / Reconnect to Bridge", fontWeight = FontWeight.SemiBold)
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-Sync URL via GitHub Gist",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Pulls new tunnel URL automatically on Colab runtime disconnect",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoResolveGist,
                            onCheckedChange = {
                                autoResolveGist = it
                                prefs.edit().putBoolean("auto_resolve_gist_url", it).apply()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = ClaudeTerracotta
                            )
                        )
                    }

                    if (autoResolveGist) {
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                isSyncingGist = true
                                chatViewModel.syncUrlFromGist(gistId) { success, result ->
                                    isSyncingGist = false
                                    if (success) {
                                        serverUrl = result
                                        Toast.makeText(context, "Synced Live URL from Gist!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Sync failed: $result", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !isSyncingGist
                        ) {
                            if (isSyncingGist) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Syncing from Gist...")
                            } else {
                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Sync Live URL from Gist Now")
                            }
                        }
                    }
                }

                // ─────────────────────────────────────────────────────────────
                // Section 2: Colab System Telemetry Card
                // ─────────────────────────────────────────────────────────────
                SettingsCard(
                    title = "Colab Host Telemetry",
                    icon = Icons.Default.Dns,
                    badge = if (hostEnv.hasGpu) "GPU ACCELERATED" to Color(0xFF4CAF50) else "CPU RUNTIME" to Color.Gray
                ) {
                    Text(
                        text = "Live hardware diagnostics from the remote execution bridge running in Google Colab.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(12.dp))

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // RAM Gauge
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "System RAM Gauge",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    val allocatedRam = if (hostEnv.ramGb > 0) hostEnv.ramGb else 8.6
                                    Text(
                                        text = "${String.format(Locale.US, "%.1f", allocatedRam)} GB / 16.0 GB",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = ClaudeTerracotta
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                val ramFraction = ((if (hostEnv.ramGb > 0) hostEnv.ramGb else 8.6) / 16.0).toFloat().coerceIn(0.1f, 1.0f)
                                LinearProgressIndicator(
                                    progress = { ramFraction },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = ClaudeTerracotta,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                            // Telemetry Grid
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.weight(1f)) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text("CPU / OS", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.outline)
                                        Spacer(Modifier.height(2.dp))
                                        Text("${hostEnv.cpuCount} vCPUs (${hostEnv.os})", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                                    }
                                }
                                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.weight(1f)) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text("GPU ACCELERATION", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.outline)
                                        Spacer(Modifier.height(2.dp))
                                        Text(if (hostEnv.hasGpu) hostEnv.gpuName.ifBlank { "Tesla T4 (15GB)" } else "CPU Only", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                                    }
                                }
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.weight(1f)) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text("WORKSPACE CWD", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.outline)
                                        Spacer(Modifier.height(2.dp))
                                        Text(hostEnv.cwd, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), maxLines = 1)
                                    }
                                }
                                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.weight(1f)) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text("SERVER UPTIME", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.outline)
                                        Spacer(Modifier.height(2.dp))
                                        Text("Python ${hostEnv.pythonVersion} Active", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }

                // ─────────────────────────────────────────────────────────────
                // Section 3: AI Model & Thinking Effort
                // ─────────────────────────────────────────────────────────────
                SettingsCard(
                    title = "AI Model & Thinking Effort",
                    icon = Icons.Default.Psychology
                ) {
                    Text(
                        text = "Choose the active model and tune the thinking effort for reasoning-supported models.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(10.dp))

                    Text(
                        text = "Active Intelligence Model:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(Modifier.height(6.dp))

                    var showModelDropdown by remember { mutableStateOf(false) }

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedCard(
                            onClick = { showModelDropdown = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = selectedModel.name,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (selectedModel.badge.isNotBlank()) {
                                            Spacer(Modifier.width(8.dp))
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = ClaudeTerracotta.copy(alpha = 0.15f)
                                            ) {
                                                Text(
                                                    text = selectedModel.badge,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                    color = ClaudeTerracotta,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = "${selectedModel.provider} · ${selectedModel.description}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            }
                        }

                        DropdownMenu(
                            expanded = showModelDropdown,
                            onDismissRequest = { showModelDropdown = false },
                            modifier = Modifier.fillMaxWidth(0.88f)
                        ) {
                            ModelRegistry.ALL_MODELS.forEach { model ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = model.name,
                                                    fontWeight = if (selectedModel.id == model.id) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (selectedModel.id == model.id) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = "(${model.provider})",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                            }
                                            Text(
                                                text = model.description,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        chatViewModel.selectModel(model)
                                        showModelDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = "Thinking Level (Reasoning Effort):",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (selectedModel.supportsEffort) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ThinkingLevel.entries.forEach { level ->
                                val isSelected = reasoningEffort.equals(level.id, ignoreCase = true)
                                Surface(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        reasoningEffort = level.id
                                        chatViewModel.setReasoningEffort(level.id)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) ClaudeTerracotta.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    border = BorderStroke(
                                        if (isSelected) 1.5.dp else 0.6.dp,
                                        if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = level.displayName,
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                    color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = level.badge,
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                            Text(
                                                text = level.description,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (isSelected) {
                                            Icon(
                                                Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = ClaudeTerracotta,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "${selectedModel.name} thinking level is fixed to default and cannot be modified.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // ─────────────────────────────────────────────────────────────
                // Section 4: Memory & Personalization
                // ─────────────────────────────────────────────────────────────
                SettingsCard(
                    title = "Memory & Personalization",
                    icon = Icons.Default.Psychology,
                    badge = if (isMemoryEnabled) "ACTIVE ($enabledMemoriesCount)" to ClaudeTerracotta else "DISABLED" to Color.Gray
                ) {
                    Text(
                        text = "Next AI remembers details across all conversations and adapts responses to your profile.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )

                    Spacer(Modifier.height(12.dp))

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onNavigateToPersonalization()
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = ClaudeTerracotta.copy(alpha = 0.10f)
                        ),
                        border = BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.35f)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(ClaudeTerracotta.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = ClaudeTerracotta,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Personalization & Memory Hub",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (personalization.isEnabled) {
                                        val id = if (personalization.name.isNotBlank()) "${personalization.name} · " else ""
                                        "${id}${personalization.toneStyle} tone · ${personalization.depthLevel} depth"
                                    } else "Personalization is OFF",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            FilledTonalButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onNavigateToPersonalization()
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Open", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(2.dp))
                                Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Persistent Memory",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (isMemoryEnabled) "Enabled · $enabledMemoriesCount active memories stored" else "Disabled · Past memories won't be referenced",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isMemoryEnabled,
                            onCheckedChange = {
                                chatViewModel.setMemoryEnabled(it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = ClaudeTerracotta
                            )
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Autonomous Memory Extraction",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (isAutoMemoryEnabled) "Automatically learns preferences as you chat" else "Manual only (via /remember)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isAutoMemoryEnabled,
                            onCheckedChange = {
                                chatViewModel.setAutoMemoryEnabled(it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = ClaudeTerracotta
                            )
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                showMemorySheet = true
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                        ) {
                            Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Vault (${allMemories.size})")
                        }

                        OutlinedButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                showCustomInstructionsSheet = true
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Instructions")
                        }
                    }
                }

                // ─────────────────────────────────────────────────────────────
                // Section 5: UI & Appearance
                // ─────────────────────────────────────────────────────────────
                SettingsCard(
                    title = "UI & Appearance",
                    icon = Icons.Default.Palette
                ) {
                    Text(
                        text = "Customize themes, message density, streaming cursor, and tactile haptics.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val currentMode = ThemeState.themeMode
                        val modes = listOf(
                            Triple("system", "System", Icons.Default.BrightnessAuto),
                            Triple("light", "Light", Icons.Default.LightMode),
                            Triple("dark", "Dark", Icons.Default.DarkMode)
                        )

                        modes.forEach { (modeKey, label, icon) ->
                            val isSelected = currentMode == modeKey
                            Surface(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    ThemeState.setTheme(context, modeKey)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) ClaudeTerracotta.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(
                                    width = if (isSelected) 1.5.dp else 0.6.dp,
                                    color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = label,
                                        tint = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        ),
                                        color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Spacer(Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Material You Dynamic Colors",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Harmonize app colors with wallpaper theme",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = ThemeState.dynamicColor,
                                onCheckedChange = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    ThemeState.setDynamicColor(context, it)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = ClaudeTerracotta
                                )
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Compact Message Density",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (compactMessageDensity) "Compact (8.dp) spacing to see more chat content" else "Comfortable (16.dp) spacing with relaxed view",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = compactMessageDensity,
                            onCheckedChange = { chatViewModel.setCompactMessageDensity(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = ClaudeTerracotta
                            )
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Pulsating Streaming Cursor",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Inline blinking cursor ( ▍) while text streams",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = showStreamingCursor,
                            onCheckedChange = { chatViewModel.setShowStreamingCursor(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = ClaudeTerracotta
                            )
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Domain Follow-Up Suggestions",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Context-aware action chips after responses",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = showFollowupSuggestions,
                            onCheckedChange = { chatViewModel.setShowFollowupSuggestions(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = ClaudeTerracotta
                            )
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Tactile Haptic Feedback",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Vibration response on clicks, copies & switches",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = hapticsEnabled,
                            onCheckedChange = {
                                hapticsEnabled = it
                                prefs.edit().putBoolean("haptics_enabled", it).apply()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = ClaudeTerracotta
                            )
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Hardware Math Cache",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "$mathCacheCount pre-rendered LaTeX formula bitmaps in memory",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                val cleared = com.agychat.app.ui.chat.clearKaTeXCache()
                                mathCacheCount = 0
                                Toast.makeText(context, "Cleared $cleared formula bitmaps from memory", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Clear", fontSize = 12.sp)
                        }
                    }
                }

                // ─────────────────────────────────────────────────────────────
                // Section 6: Data & Backup (Cloud Sync Card & Local Backups)
                // ─────────────────────────────────────────────────────────────
                SettingsCard(
                    title = "Data & Backup",
                    icon = Icons.Default.CloudSync,
                    badge = if (connectionState == ConnectionState.CONNECTED) "CLOUD LINKED" to Color(0xFF4CAF50) else "LOCAL ONLY" to Color.Gray
                ) {
                    Text(
                        text = "Seamlessly back up chats, memories, directives, and settings to your Google Drive and local storage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )

                    Spacer(Modifier.height(12.dp))

                    // Cloud Sync Card (Expressive Container)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(
                            1.dp,
                            if (connectionState == ConnectionState.CONNECTED) Color(0xFF4CAF50).copy(alpha = 0.35f)
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            // Header row with Google Drive badge
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Cloud,
                                        contentDescription = null,
                                        tint = if (connectionState == ConnectionState.CONNECTED) Color(0xFF4CAF50) else ClaudeTerracotta,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "Google Drive Sync",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (connectionState == ConnectionState.CONNECTED) Color(0xFF4CAF50).copy(alpha = 0.15f)
                                    else ClaudeTerracotta.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = if (connectionState == ConnectionState.CONNECTED) "Linked 🟢" else "Local Standby 🟡",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = if (connectionState == ConnectionState.CONNECTED) Color(0xFF4CAF50) else ClaudeTerracotta,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            // Sync Status & Metrics Grid
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Last Backup Timestamp Card
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            text = "LAST SYNC",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 0.5.sp
                                            ),
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        val lastSyncTs = chatViewModel.driveManager.getLastSyncTimestamp()
                                        Text(
                                            text = formatTimestamp(lastSyncTs),
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1
                                        )
                                    }
                                }

                                // Backup Size Card
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            text = "BACKUP SIZE",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 0.5.sp
                                            ),
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = backupFileSizeStr,
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }

                            // Dynamic Cloud Sync Status Pill (if status is present)
                            val syncStatus = cloudSyncStatus
                            if (syncStatus != null) {
                                Spacer(Modifier.height(10.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = ClaudeTerracotta.copy(alpha = 0.12f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (isSyncing) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(13.dp),
                                                strokeWidth = 2.dp,
                                                color = ClaudeTerracotta
                                            )
                                        } else {
                                            Icon(Icons.Default.CloudDone, null, modifier = Modifier.size(13.dp), tint = ClaudeTerracotta)
                                        }
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = syncStatus,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = ClaudeTerracotta
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            // One-Tap Manual Sync Button with Spinning Indicator
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    chatViewModel.syncToGoogleDrive()
                                },
                                enabled = !isSyncing,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                            ) {
                                if (isSyncing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Syncing with Drive...", fontWeight = FontWeight.SemiBold)
                                } else {
                                    Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Sync Now to Google Drive", fontWeight = FontWeight.SemiBold)
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            // Restore from Drive Button
                            OutlinedButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    showRestoreConfirmDialog = true
                                },
                                enabled = !isSyncing,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Restore from Google Drive")
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Auto-Sync to Google Drive", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "Automatically updates cloud backup after every response",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = driveAutoBackup,
                            onCheckedChange = {
                                driveAutoBackup = it
                                prefs.edit().putBoolean("drive_auto_backup", it).apply()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = ClaudeTerracotta
                            )
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 12.dp))

                    Text(
                        text = "Local Device Backup (Zero-Loss Offline Safety)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                jsonFileExportLauncher.launch("NextAI_Backup_$timestamp.json")
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.UploadFile, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Export JSON")
                        }

                        OutlinedButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                jsonFilePickerLauncher.launch("application/json")
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.FileOpen, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Import JSON")
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val md = chatViewModel.exportConversationToMarkdown()
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, md)
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Export Chat as Markdown"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Download, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Export All Chats (Markdown)")
                    }
                }

                // ─────────────────────────────────────────────────────────────
                // Section 7: About Next AI
                // ─────────────────────────────────────────────────────────────
                SettingsCard(
                    title = "About Next AI",
                    icon = Icons.Default.Info
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(ClaudeTerracotta.copy(alpha = 0.12f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("v3.5.0 (Build 14)", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = ClaudeTerracotta)
                        }
                        Text(
                            text = "Next AI",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    val features = listOf(
                        "⚡ v3.5.0 Redesigned Ultra-Fluid Chat UI & UX",
                        "🏎️ Zero-Lag 120 FPS KaTeX Bitmap Hardware Caching",
                        "🤖 Antigravity CLI Integration (stream-json & WebSocket)",
                        "🧠 Autonomous In-Conversation Memory & Personalization",
                        "🎛️ Dynamic Reasoning Effort (Low, Medium, High)",
                        "🛑 Instant Red Subprocess Cancellation",
                        "🌐 8 Autonomous Slash Commands (/goal, /plan, /boost...)",
                        "☁️ Auto-Syncing Google Drive Cloud Persistence"
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        features.forEach { feature ->
                            Text(
                                text = feature,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showMemorySheet) {
        ManageMemorySheet(
            viewModel = chatViewModel,
            onDismiss = { showMemorySheet = false }
        )
    }

    if (showCustomInstructionsSheet) {
        CustomInstructionsSheet(
            viewModel = chatViewModel,
            onDismiss = { showCustomInstructionsSheet = false }
        )
    }

    if (showRestoreConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirmDialog = false },
            title = { Text("Restore from Google Drive?", fontWeight = FontWeight.Bold) },
            text = {
                Text("This will restore your conversations, messages, memories, and personal settings from your Google Drive cloud backup. Current messages will be preserved and merged.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showRestoreConfirmDialog = false
                        chatViewModel.restoreFromGoogleDrive()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Restore Now")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirmDialog = false }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(18.dp)
        )
    }
}

@Composable
fun SettingsCard(
    title: String,
    icon: ImageVector,
    badge: Pair<String, Color>? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(ClaudeTerracotta.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (badge != null) {
                    val (label, bColor) = badge
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = bColor,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(bColor.copy(alpha = 0.15f))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}
