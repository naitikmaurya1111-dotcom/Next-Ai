package com.agychat.app.ui.settings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.agychat.app.domain.model.AiModel
import com.agychat.app.domain.model.ConnectionState
import com.agychat.app.domain.model.ModelRegistry
import com.agychat.app.domain.model.ThinkingLevel
import com.agychat.app.ui.chat.ChatViewModel
import com.agychat.app.ui.chat.ManageMemorySheet
import com.agychat.app.ui.theme.ClaudeTerracotta
import com.agychat.app.ui.theme.ThemeState
import com.agychat.app.data.network.UrlSanitizer
import android.os.Build
import androidx.compose.foundation.BorderStroke

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    chatViewModel: ChatViewModel = hiltViewModel(),
    onNavigateToPersonalization: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE) }
    val connectionState by chatViewModel.connectionState.collectAsState()
    val selectedModel by chatViewModel.selectedModel.collectAsState()
    val isMemoryEnabled by chatViewModel.isMemoryEnabled.collectAsState()
    val isAutoMemoryEnabled by chatViewModel.isAutoMemoryEnabled.collectAsState()
    val customInstructions by chatViewModel.customInstructions.collectAsState()
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
    var mathCacheCount by remember { mutableIntStateOf(com.agychat.app.ui.chat.getKaTeXCacheCount()) }
    var hapticsEnabled by remember { mutableStateOf(prefs.getBoolean("haptics_enabled", true)) }
    var showRestoreConfirmDialog by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

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

    var showMemorySheet by remember { mutableStateOf(false) }
    var showCustomInstructionsSheet by remember { mutableStateOf(false) }

    var serverUrl by remember {
        mutableStateOf(
            prefs.getString("server_url", "wss://andy-viruses-she-performs.trycloudflare.com/ws") ?: "wss://andy-viruses-she-performs.trycloudflare.com/ws"
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
                    IconButton(onClick = onBack) {
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
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
            Spacer(Modifier.height(4.dp))

            // ── Section 1: Colab Bridge Connection ────────────────────────
            SettingsCard(
                title = "Colab Bridge Server",
                icon = Icons.Default.Wifi,
                badge = when (connectionState) {
                    ConnectionState.CONNECTED -> "ONLINE" to Color(0xFF4CAF50)
                    ConnectionState.CONNECTING -> "CONNECTING" to Color(0xFFFFB300)
                    ConnectionState.ERROR -> "ERROR" to Color(0xFFEF5350)
                    ConnectionState.DISCONNECTED -> "OFFLINE" to Color(0xFF9E9E9E)
                }
            ) {
                Text(
                    text = "Enter the secure WebSocket URL generated by your Colab terminal bridge (Cloudflare or ngrok tunnel).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )

                Spacer(Modifier.height(10.dp))

                val isUrlValid = remember(serverUrl) {
                    serverUrl.isBlank() || UrlSanitizer.isValidWebSocketUrl(serverUrl)
                }

                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("WebSocket URL") },
                    placeholder = { Text("wss://xxxx.trycloudflare.com/ws") },
                    singleLine = true,
                    isError = serverUrl.isNotBlank() && !isUrlValid,
                    supportingText = {
                        if (serverUrl.isNotBlank()) {
                            if (isUrlValid) {
                                Text("✓ Valid WebSocket endpoint", color = Color(0xFF4CAF50), fontSize = 11.sp)
                            } else {
                                Text("⚠️ Format: wss://xxxx.trycloudflare.com/ws", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
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
                                Icon(Icons.Default.ContentPaste, contentDescription = "Paste from clipboard", modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(Modifier.height(10.dp))

                Button(
                    onClick = {
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
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Connect to CLI")
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
                            text = "Automatically detects and updates the Colab tunnel URL on runtime restart without manual copy-pasting.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoResolveGist,
                        onCheckedChange = {
                            autoResolveGist = it
                            prefs.edit().putBoolean("auto_resolve_gist_url", it).apply()
                        }
                    )
                }

                if (autoResolveGist) {
                    Spacer(Modifier.height(8.dp))
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
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Sync Live URL from Gist Now")
                        }
                    }
                }

                if (connectionState == ConnectionState.CONNECTED) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val dotColor = when {
                                    latencyMs == null -> Color.Gray
                                    latencyMs!! < 120 -> Color(0xFF4CAF50)
                                    latencyMs!! < 300 -> Color(0xFFFFB300)
                                    else -> Color(0xFFEF5350)
                                }
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (latencyMs != null) "Ping: ${latencyMs}ms" else "WebSocket Active",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            FilledTonalButton(
                                onClick = { chatViewModel.pingBridge() },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Ping Test", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // ── Section 2: AI Model Selection & Reasoning ─────────────────
            SettingsCard(
                title = "AI Model & Reasoning",
                icon = Icons.Default.Psychology
            ) {
                Text(
                    text = "Select active Antigravity CLI intelligence and configure reasoning effort.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(10.dp))

                Text(
                    text = "Active CLI Model:",
                    style = MaterialTheme.typography.titleSmall
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
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Text(
                                                text = selectedModel.badge,
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
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
                                                color = if (selectedModel.id == model.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
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
                    style = MaterialTheme.typography.titleSmall
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
                                    reasoningEffort = level.id
                                    chatViewModel.setReasoningEffort(level.id)
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) ClaudeTerracotta.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
                                border = androidx.compose.foundation.BorderStroke(
                                    if (isSelected) 1.5.dp else 0.6.dp,
                                    if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant
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
                        color = MaterialTheme.colorScheme.surfaceVariant,
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
                                text = "${selectedModel.name} thinking level is fixed to default in Antigravity CLI and cannot be modified.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ── Section 3: Appearance & Theme ─────────────────────────────
            SettingsCard(
                title = "Appearance & Theme",
                icon = Icons.Default.Palette
            ) {
                Text(
                    text = "Choose how Next AI looks on your device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))

                // Theme Mode Selector: System / Light / Dark
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
                                text = "Harmonize app colors with your device wallpaper",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = ThemeState.dynamicColor,
                            onCheckedChange = { ThemeState.setDynamicColor(context, it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = ClaudeTerracotta
                            )
                        )
                    }
                }
            }

            // ── Section 4: Chat Experience & Interaction ──────────────────
            SettingsCard(
                title = "Chat Experience & Interaction",
                icon = Icons.Default.Tune,
                badge = if (compactMessageDensity) "COMPACT" to ClaudeTerracotta else "COMFORTABLE" to MaterialTheme.colorScheme.outline
            ) {
                Text(
                    text = "Customize message rendering, streaming animation, smart follow-up suggestions, and layout density.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )

                Spacer(Modifier.height(14.dp))

                // Follow-up suggestions toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Domain Follow-Up Suggestions",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Context-aware quick action chips (bugs, tests, explanations) after assistant responses",
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

                Spacer(Modifier.height(12.dp))

                // Streaming cursor toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Pulsating Streaming Cursor",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "ChatGPT-style inline blinking cursor ( ▍) while text is actively streaming",
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

                Spacer(Modifier.height(12.dp))

                // Memory activity badges toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Memory Activity Badges",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Display visual indicators when memories are updated or referenced in responses",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = showMemoryActivityBadges,
                        onCheckedChange = { chatViewModel.setShowMemoryActivityBadges(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = ClaudeTerracotta
                        )
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Message Density: Comfortable vs Compact
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Compact Message Density",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = if (compactMessageDensity) "Compact spacing (8.dp) to see more conversation content" else "Comfortable spacing (16.dp) with relaxed layout",
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

                Spacer(Modifier.height(12.dp))

                // Tactile Haptic Feedback
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Tactile Haptic Feedback",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Subtle vibration feedback on button presses, copying, and prompt actions",
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

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(12.dp))

                // Hardware Math Equation Bitmap Cache
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Hardware Math Cache (120 FPS)",
                            style = MaterialTheme.typography.titleSmall
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
                        Text("Clear Cache", fontSize = 12.sp)
                    }
                }
            }

            // ── Section 5: Personalization & Memory (ChatGPT Style) ───────
            SettingsCard(
                title = "Personalization & Memory",
                icon = Icons.Default.Psychology,
                badge = if (isMemoryEnabled) "ACTIVE ($enabledMemoriesCount)" to ClaudeTerracotta else "DISABLED" to Color.Gray
            ) {
                Text(
                    text = "Next AI remembers details across all conversations and tailors responses according to your profile and preferences.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )

                Spacer(Modifier.height(14.dp))

                // Memory & Personalization Hub Full Page Entry Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToPersonalization() },
                    colors = CardDefaults.cardColors(
                        containerColor = ClaudeTerracotta.copy(alpha = 0.10f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.35f)),
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
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "Personalization & Memory Hub",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
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
                            onClick = onNavigateToPersonalization,
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

                // Persistent Memory Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Persistent Memory",
                            style = MaterialTheme.typography.titleSmall
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

                // Autonomous Memory Extraction Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Autonomous Memory Extraction",
                            style = MaterialTheme.typography.titleSmall
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

                Button(
                    onClick = { showMemorySheet = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Manage Memories (${allMemories.size})")
                }
            }

            // ── Section 6: Google Drive & Cloud Backup ─────────────────────
            SettingsCard(
                title = "Google Drive Sync & Cloud Backup",
                icon = Icons.Default.CloudSync,
                badge = if (connectionState == ConnectionState.CONNECTED) "DRIVE LINKED" to Color(0xFF4CAF50) else "OFFLINE" to Color.Gray
            ) {
                Text(
                    text = "Sync all conversations, messages, memories, custom instructions, and settings directly with your Google Drive (/MyDrive/NextAI_Backup). Updating or reinstalling the app will never lose your data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )

                Spacer(Modifier.height(12.dp))

                // Cloud Sync Status Pill
                val syncStatus = cloudSyncStatus
                if (syncStatus != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = ClaudeTerracotta.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = ClaudeTerracotta
                                )
                            } else {
                                Icon(Icons.Default.CloudDone, null, modifier = Modifier.size(16.dp), tint = ClaudeTerracotta)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = syncStatus,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = ClaudeTerracotta
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // Cloud Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { chatViewModel.syncToGoogleDrive() },
                        enabled = !isSyncing,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                    ) {
                        Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isSyncing) "Syncing..." else "Sync to Drive")
                    }

                    OutlinedButton(
                        onClick = { showRestoreConfirmDialog = true },
                        enabled = !isSyncing,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Restore Drive")
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Auto-save Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Auto-Sync to Google Drive", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Automatically backs up chats & memories after every response",
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

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))

                Text(
                    text = "Local Device Backup (Zero-Loss Offline Safety)",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )

                Spacer(Modifier.height(6.dp))

                // Local Export & Import Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
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
                        onClick = { jsonFilePickerLauncher.launch("application/json") },
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

            // ── Section 7: About & System ─────────────────────────────────
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
                        Text("v3.0.1 (Build 13)", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = ClaudeTerracotta)
                    }
                    Text(
                        text = "Next AI",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(8.dp))

                val features = listOf(
                    "⚡ v3.0.1 Redesigned Ultra-Fluid Chat UI & UX",
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
        com.agychat.app.ui.settings.CustomInstructionsSheet(
            viewModel = chatViewModel,
            onDismiss = { showCustomInstructionsSheet = false }
        )
    }

    if (showRestoreConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirmDialog = false },
            title = { Text("Restore from Google Drive?") },
            text = {
                Text("This will restore your conversations, messages, memories, and personal settings from your Google Drive cloud backup. Current messages will be preserved and merged.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestoreConfirmDialog = false
                        chatViewModel.restoreFromGoogleDrive()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Text("Restore Now")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
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
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
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
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
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
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
