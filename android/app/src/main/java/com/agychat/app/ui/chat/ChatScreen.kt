package com.agychat.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.agychat.app.domain.PluginManager
import com.agychat.app.domain.model.ConnectionState
import com.agychat.app.domain.model.Message
import com.agychat.app.ui.plugin.PluginDrawer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
    pluginManager: PluginManager,
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var showPluginDrawer by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "AGY Chat",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = when (connectionState) {
                                ConnectionState.CONNECTED -> "● Connected"
                                ConnectionState.CONNECTING -> "Connecting..."
                                ConnectionState.ERROR -> "⚠ Connection Error"
                                else -> "Disconnected"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when (connectionState) {
                                ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                                ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
                                ConnectionState.ERROR -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.outline
                            }
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clearConversation() }) {
                        Icon(Icons.Default.Create, contentDescription = "New Chat")
                    }
                    IconButton(onClick = { viewModel.backupToDrive() }) {
                        Icon(Icons.Default.Backup, contentDescription = "Backup to Drive")
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("History") },
                                leadingIcon = { Icon(Icons.Default.History, null) },
                                onClick = { showMenu = false; onNavigateToHistory() }
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                leadingIcon = { Icon(Icons.Default.Settings, null) },
                                onClick = { showMenu = false; onNavigateToSettings() }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            ChatInputBar(
                text = inputText,
                onTextChange = { inputText = it },
                onSend = {
                    viewModel.sendMessage(inputText)
                    inputText = ""
                    scope.launch {
                        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
                    }
                },
                onPluginClick = { showPluginDrawer = true },
                isConnected = connectionState == ConnectionState.CONNECTED
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (messages.isEmpty()) {
                EmptyState(
                    modifier = Modifier.align(Alignment.Center),
                    onPluginClick = { showPluginDrawer = true }
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(message = message)
                    }
                    if (isLoading && messages.lastOrNull()?.isStreaming != true) {
                        item {
                            TypingIndicator()
                        }
                    }
                }
            }
        }
    }

    // Plugin Drawer
    if (showPluginDrawer) {
        PluginDrawer(
            pluginManager = pluginManager,
            onCommandSelected = { command ->
                viewModel.sendSlashCommand(command)
                showPluginDrawer = false
            },
            onDismiss = { showPluginDrawer = false }
        )
    }
}

@Composable
fun MessageBubble(message: Message) {
    when (message.role) {
        "user" -> UserBubble(message)
        "assistant" -> AssistantBubble(message)
        "system" -> SystemMessage(message)
        else -> SystemMessage(message)
    }
}

@Composable
fun UserBubble(message: Message) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = message.content,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun AssistantBubble(message: Message) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        // AGY Avatar
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "A",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelMedium
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.widthIn(max = 280.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = message.content,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = if (message.content.contains("```")) FontFamily.Monospace else FontFamily.Default
                )
            }
            if (message.isStreaming) {
                Text(
                    text = "typing...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                )
            }
        }
    }
}

@Composable
fun SystemMessage(message: Message) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message.content,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = 32.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val dot1 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes { durationMillis = 900; 0f at 0; 1f at 300; 0f at 600 },
            repeatMode = RepeatMode.Restart
        ), label = "dot1"
    )
    val dot2 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes { durationMillis = 900; 0f at 100; 1f at 400; 0f at 700 },
            repeatMode = RepeatMode.Restart
        ), label = "dot2"
    )
    val dot3 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes { durationMillis = 900; 0f at 200; 1f at 500; 0f at 800 },
            repeatMode = RepeatMode.Restart
        ), label = "dot3"
    )

    Row(
        modifier = Modifier
            .padding(start = 36.dp, top = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf(dot1, dot2, dot3).forEach { alpha ->
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            )
        }
    }
}

@Composable
fun EmptyState(modifier: Modifier = Modifier, onPluginClick: () -> Unit) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "A",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "AGY Chat",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Connected to Antigravity CLI in Colab.\nAsk anything or tap the plugin button to use slash commands.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedButton(onClick = onPluginClick) {
            Icon(Icons.Default.Extension, null)
            Spacer(Modifier.width(8.dp))
            Text("Browse Plugins")
        }
    }
}

@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onPluginClick: () -> Unit,
    isConnected: Boolean
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalAlignment = Alignment.Bottom
        ) {
            // Plugin button
            IconButton(onClick = onPluginClick) {
                Icon(
                    Icons.Default.Extension,
                    contentDescription = "Plugins",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            // Text input
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message AGY...") },
                shape = RoundedCornerShape(24.dp),
                maxLines = 6,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Send button
            FilledIconButton(
                onClick = onSend,
                enabled = text.isNotBlank() && isConnected,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send")
            }
        }
    }
}
