package com.agychat.app.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.agychat.app.ui.chat.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("agy_settings", Context.MODE_PRIVATE) }

    var serverUrl by remember {
        mutableStateOf(prefs.getString("server_url", "wss://YOUR-NGROK-URL/ws") ?: "")
    }
    var driveBackupEnabled by remember {
        mutableStateOf(prefs.getBoolean("drive_backup", false))
    }
    var darkTheme by remember {
        mutableStateOf(prefs.getBoolean("dark_theme", false))
    }
    var saveSuccess by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ── Connection ──────────────────────────────────────────────
            SettingsSection(title = "Connection", icon = Icons.Default.Wifi) {
                Text(
                    text = "Enter the WebSocket URL from your Colab server.\nIt looks like: wss://abc123.ngrok-free.app/ws",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("WebSocket Server URL") },
                    placeholder = { Text("wss://xxxx.ngrok-free.app/ws") },
                    leadingIcon = { Icon(Icons.Default.Link, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            // Save & connect
                            prefs.edit().putString("server_url", serverUrl).apply()
                            chatViewModel.connectToServer(serverUrl)
                            saveSuccess = true
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Link, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Connect")
                    }
                }
                if (saveSuccess) {
                    Text(
                        "✓ Connected!",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // ── Google Drive Backup ─────────────────────────────────────
            SettingsSection(title = "Google Drive Backup", icon = Icons.Default.Backup) {
                Text(
                    text = "Automatically back up conversations, artifacts and files to Google Drive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Auto-backup", fontWeight = FontWeight.Medium)
                    Switch(
                        checked = driveBackupEnabled,
                        onCheckedChange = {
                            driveBackupEnabled = it
                            prefs.edit().putBoolean("drive_backup", it).apply()
                        }
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { chatViewModel.backupToDrive() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CloudUpload, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Backup Now")
                }
                OutlinedButton(
                    onClick = { /* Google Sign-In */ },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.AccountCircle, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Sign in with Google")
                }
            }

            // ── Appearance ──────────────────────────────────────────────
            SettingsSection(title = "Appearance", icon = Icons.Default.Palette) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Dark Theme", fontWeight = FontWeight.Medium)
                    Switch(
                        checked = darkTheme,
                        onCheckedChange = {
                            darkTheme = it
                            prefs.edit().putBoolean("dark_theme", it).apply()
                        }
                    )
                }
            }

            // ── About ───────────────────────────────────────────────────
            SettingsSection(title = "About", icon = Icons.Default.Info) {
                Text("AGY Chat v1.0.0", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "A Claude-like chat interface for Antigravity CLI.\nBuilt with Kotlin + Jetpack Compose.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}
