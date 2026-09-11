package com.agychat.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.FileOutputStream
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.agychat.app.data.local.ConversationEntity
import com.agychat.app.domain.PluginItem
import com.agychat.app.domain.PluginManager
import com.agychat.app.domain.model.AiModel
import com.agychat.app.domain.model.AttachmentItem
import com.agychat.app.domain.model.ConnectionState
import com.agychat.app.domain.model.Message
import com.agychat.app.domain.model.ModelRegistry
import com.agychat.app.domain.model.ToolExecutionItem
import com.agychat.app.ui.plugin.PluginDrawer
import com.agychat.app.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

fun queryFileName(context: Context, uri: Uri): String {
    var name = "attachment"
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex) ?: name
            }
        }
    } catch (e: Exception) {
        name = uri.lastPathSegment ?: "attachment"
    }
    return name
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
    pluginManager: PluginManager,
    onNavigateToSettings: () -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val currentStatus by viewModel.currentStatus.collectAsState()
    val conversations by viewModel.conversations.collectAsState(initial = emptyList())
    val selectedAttachment by viewModel.selectedAttachment.collectAsState()
    val reasoningEffort by viewModel.reasoningEffort.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var showPluginBottomSheet by remember { mutableStateOf(false) }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var showEffortMenu by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var speakingMessageId by remember { mutableStateOf<String?>(null) }
    var tts: TextToSpeech? by remember { mutableStateOf(null) }

    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    // Initialize TextToSpeech engine
    DisposableEffect(context) {
        val textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            }
        }
        tts = textToSpeech
        onDispose {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
    }

    // Toggle speech for a message
    val toggleSpeak: (String, String) -> Unit = { id, text ->
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (speakingMessageId == id) {
            tts?.stop()
            speakingMessageId = null
        } else {
            tts?.stop()
            val cleanText = text.replace(Regex("[#*`_~>\\[\\]]"), " ").trim()
            tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, id)
            speakingMessageId = id
        }
    }

    // Voice Dictation / Speech-to-Text launcher
    val speechRecognizerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spokenMatches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = spokenMatches?.firstOrNull()
            if (!text.isNullOrBlank()) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                inputText = if (inputText.isBlank()) text else "$inputText $text"
                Toast.makeText(context, "Transcribed speech", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Camera launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            try {
                val photoFile = File(context.cacheDir, "camera_photo_${System.currentTimeMillis()}.jpg")
                FileOutputStream(photoFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                viewModel.setAttachment(
                    AttachmentItem(
                        uri = Uri.fromFile(photoFile).toString(),
                        name = photoFile.name,
                        isImage = true
                    )
                )
                Toast.makeText(context, "Photo attached!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to capture photo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Gallery / Photo picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            val fileName = queryFileName(context, uri)
            viewModel.setAttachment(
                AttachmentItem(
                    uri = uri.toString(),
                    name = fileName,
                    isImage = true
                )
            )
            Toast.makeText(context, "Image attached: $fileName", Toast.LENGTH_SHORT).show()
        }
    }

    // General File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            val fileName = queryFileName(context, uri)
            val mimeType = context.contentResolver.getType(uri) ?: ""
            val isImg = mimeType.startsWith("image/") ||
                    fileName.lowercase().endsWith(".png") ||
                    fileName.lowercase().endsWith(".jpg") ||
                    fileName.lowercase().endsWith(".jpeg") ||
                    fileName.lowercase().endsWith(".webp")

            viewModel.setAttachment(
                AttachmentItem(
                    uri = uri.toString(),
                    name = fileName,
                    isImage = isImg
                )
            )
            Toast.makeText(context, "File attached: $fileName", Toast.LENGTH_SHORT).show()
        }
    }

    // Detect if user has scrolled up to show "Scroll to Bottom" FAB
    val showScrollToBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 1 || listState.firstVisibleItemScrollOffset > 300
        }
    }

    // Auto-scroll on new messages
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            HistoryDrawerContent(
                conversations = conversations,
                activeId = viewModel.currentConversationId,
                onSelectConversation = { id ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.loadConversation(id)
                    scope.launch { drawerState.close() }
                },
                onNewChat = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.startNewConversation()
                    scope.launch { drawerState.close() }
                },
                onDelete = { id ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.deleteConversation(id)
                },
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    onNavigateToSettings()
                }
            )
        }
    ) {
        Scaffold(
            topBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        TopAppBar(
                            title = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = 2.dp)
                                ) {
                                    // Claude Terracotta Asterisk Badge
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(ClaudeTerracotta.copy(alpha = 0.14f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "✦",
                                            color = ClaudeTerracotta,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 17.sp
                                        )
                                    }

                                    Spacer(Modifier.width(10.dp))

                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "Next AI",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = (-0.3).sp
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            ConnectionStatusBadge(
                                                state = connectionState,
                                                onClick = {
                                                    if (connectionState == ConnectionState.DISCONNECTED || connectionState == ConnectionState.ERROR) {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        Toast.makeText(context, "Reconnecting to Colab bridge...", Toast.LENGTH_SHORT).show()
                                                        viewModel.reconnect()
                                                    } else {
                                                        onNavigateToSettings()
                                                    }
                                                }
                                            )
                                        }

                                        if (!currentStatus.isNullOrBlank()) {
                                            Text(
                                                text = currentStatus ?: "",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = ClaudeTerracotta,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    scope.launch { drawerState.open() }
                                }) {
                                    Icon(
                                        Icons.Default.Menu,
                                        contentDescription = "History Drawer",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            },
                            actions = {
                                // Antigravity AI Model Selector Pill
                                Surface(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        showModelSheet = true
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    border = androidx.compose.foundation.BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "✦",
                                            color = ClaudeTerracotta,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp
                                        )
                                        Spacer(Modifier.width(3.dp))
                                        val shortModelName = when {
                                            selectedModel.id.contains("3.8") -> "3.8 Flash"
                                            selectedModel.id.contains("3.7") -> "3.7 Flash"
                                            selectedModel.id.contains("3.6") -> "3.6 Flash"
                                            selectedModel.id.contains("3.1") -> "3.1 Pro"
                                            selectedModel.id.contains("opus") -> "Opus 4.6"
                                            selectedModel.id.contains("sonnet") -> "Sonnet 4.6"
                                            selectedModel.id.contains("gpt-oss") -> "GPT-OSS"
                                            else -> selectedModel.name
                                        }
                                        Text(
                                            text = shortModelName,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1
                                        )
                                        Spacer(Modifier.width(2.dp))
                                        Icon(
                                            Icons.Default.ArrowDropDown,
                                            contentDescription = "Select Model",
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Spacer(Modifier.width(4.dp))

                                // Thinking Effort Selector (interactive for Gemini, locked indicator for Claude/GPT-OSS)
                                if (selectedModel.supportsEffort) {
                                    Box {
                                        Surface(
                                            onClick = { showEffortMenu = true },
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            border = androidx.compose.foundation.BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Default.ElectricBolt,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(13.dp),
                                                    tint = ClaudeTerracotta
                                                )
                                                Spacer(Modifier.width(3.dp))
                                                Text(
                                                    text = reasoningEffort.replaceFirstChar { it.uppercase() },
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                        DropdownMenu(
                                            expanded = showEffortMenu,
                                            onDismissRequest = { showEffortMenu = false }
                                        ) {
                                            listOf("high", "medium", "low").forEach { effortOption ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Text(
                                                                text = effortOption.replaceFirstChar { it.uppercase() },
                                                                fontWeight = if (reasoningEffort == effortOption) FontWeight.Bold else FontWeight.Normal,
                                                                color = if (reasoningEffort == effortOption) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                                                            )
                                                            if (reasoningEffort == effortOption) {
                                                                Spacer(Modifier.width(8.dp))
                                                                Icon(Icons.Default.Check, null, modifier = Modifier.size(15.dp), tint = ClaudeTerracotta)
                                                            }
                                                        }
                                                    },
                                                    onClick = {
                                                        viewModel.setReasoningEffort(effortOption)
                                                        showEffortMenu = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    // Non-configurable thinking indicator for Claude / GPT-OSS
                                    Surface(
                                        onClick = {
                                            Toast.makeText(
                                                context,
                                                "${selectedModel.name} thinking level is fixed to default in Antigravity CLI",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = ClaudeTerracotta.copy(alpha = 0.12f),
                                        border = androidx.compose.foundation.BorderStroke(0.6.dp, ClaudeTerracotta.copy(alpha = 0.3f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.Lock,
                                                contentDescription = null,
                                                modifier = Modifier.size(11.dp),
                                                tint = ClaudeTerracotta
                                            )
                                            Spacer(Modifier.width(3.dp))
                                            Text(
                                                text = "Thinking",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                                color = ClaudeTerracotta
                                            )
                                        }
                                    }
                                }

                                IconButton(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.startNewConversation()
                                }) {
                                    Icon(
                                        Icons.Default.AddComment,
                                        contentDescription = "New Chat",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = {
                                    val md = viewModel.exportConversationToMarkdown()
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, md)
                                        type = "text/plain"
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Export Chat"))
                                }) {
                                    Icon(
                                        Icons.Default.Share,
                                        contentDescription = "Share/Export Chat",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = onNavigateToSettings) {
                                    Icon(
                                        Icons.Default.Settings,
                                        contentDescription = "Settings",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                        HorizontalDivider(
                            thickness = 0.8.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .navigationBarsPadding()
                        .imePadding()
                ) {
                    // Slash Command Autocomplete Suggestions Popup (shown when typing /)
                    val isSlashActive = inputText.startsWith("/")
                    val matchingSlashCommands = remember(inputText) {
                        if (isSlashActive) pluginManager.filterCommands(inputText) else emptyList()
                    }

                    AnimatedVisibility(
                        visible = isSlashActive && matchingSlashCommands.isNotEmpty(),
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
                    ) {
                        SlashCommandAutocompletePopup(
                            query = inputText,
                            commands = matchingSlashCommands,
                            onSelect = { plugin ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (plugin.prefix == "/clear") {
                                    viewModel.clearChat()
                                    inputText = ""
                                    Toast.makeText(context, "Chat cleared", Toast.LENGTH_SHORT).show()
                                } else if (plugin.prefix == "/model") {
                                    showModelSheet = true
                                    inputText = ""
                                } else if (plugin.prefix == "/effort") {
                                    if (selectedModel.supportsEffort) {
                                        showEffortMenu = true
                                    } else {
                                        Toast.makeText(context, "${selectedModel.name} thinking is fixed to default", Toast.LENGTH_SHORT).show()
                                    }
                                    inputText = ""
                                } else {
                                    inputText = "${plugin.prefix} "
                                }
                            }
                        )
                    }

                    if (!isSlashActive) {
                        // Quick-Action Slash Command Chips
                        QuickSlashChipsRow(
                            plugins = pluginManager.plugins,
                            onChipClick = { plugin ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (plugin.prefix == "/clear") {
                                    viewModel.clearChat()
                                    Toast.makeText(context, "Chat cleared", Toast.LENGTH_SHORT).show()
                                } else if (plugin.prefix == "/model") {
                                    showModelSheet = true
                                } else if (plugin.prefix == "/effort") {
                                    if (selectedModel.supportsEffort) showEffortMenu = true
                                    else Toast.makeText(context, "${selectedModel.name} thinking is fixed to default", Toast.LENGTH_SHORT).show()
                                } else {
                                    inputText = "${plugin.prefix} "
                                }
                            }
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    // Claude Web Floating Input Box with File/Image Attachment & Speech Input
                    ClaudeFloatingInputBar(
                        text = inputText,
                        onTextChange = { inputText = it },
                        onSend = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.sendMessage(inputText)
                            inputText = ""
                        },
                        onOpenPlugins = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showPluginBottomSheet = true
                        },
                        onAttachFile = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showAttachmentMenu = true
                        },
                        onVoiceInput = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Next AI...")
                            }
                            try {
                                speechRecognizerLauncher.launch(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Voice input not available", Toast.LENGTH_SHORT).show()
                            }
                        },
                        attachment = selectedAttachment,
                        onRemoveAttachment = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.clearAttachment()
                        },
                        isConnected = connectionState == ConnectionState.CONNECTED,
                        isLoading = isLoading
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (messages.isEmpty()) {
                    EmptyChatGreeting(
                        plugins = pluginManager.plugins,
                        onPromptCardClick = { prompt ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.sendMessage(prompt)
                        }
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 28.dp)
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            val isLastAssistant = msg.id == messages.lastOrNull { it.role == "assistant" }?.id
                            val isSpeaking = speakingMessageId == msg.id
                            MessageItem(
                                message = msg,
                                isSpeaking = isSpeaking,
                                isLastAssistant = isLastAssistant,
                                onToggleThinking = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.toggleThinkingExpanded(msg.id)
                                },
                                onToggleTools = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.toggleToolsExpanded(msg.id)
                                },
                                onRetry = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.regenerateLastResponse()
                                },
                                onEditMessage = { text ->
                                    inputText = text
                                    Toast.makeText(context, "Editing prompt...", Toast.LENGTH_SHORT).show()
                                },
                                onDeleteMessage = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.deleteMessage(msg.id)
                                    Toast.makeText(context, "Message deleted", Toast.LENGTH_SHORT).show()
                                },
                                onToggleSpeak = {
                                    toggleSpeak(msg.id, msg.content)
                                },
                                onFeedback = { type ->
                                    viewModel.toggleFeedback(msg.id, type)
                                }
                            )
                        }

                        if (isLoading && messages.lastOrNull()?.isStreaming != true) {
                            item {
                                ClaudeTypingBubble()
                            }
                        }
                    }

                    // Floating "Scroll to Bottom" button
                    AnimatedVisibility(
                        visible = showScrollToBottom,
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut(),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 12.dp)
                    ) {
                        FilledIconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                scope.launch {
                                    listState.animateScrollToItem(messages.size - 1)
                                }
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .shadow(4.dp, CircleShape),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = ClaudeTerracotta
                            )
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Scroll to bottom",
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPluginBottomSheet) {
        PluginDrawer(
            pluginManager = pluginManager,
            onCommandSelected = { cmd ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.sendSlashCommand(cmd)
                showPluginBottomSheet = false
            },
            onDismiss = { showPluginBottomSheet = false }
        )
    }

    if (showAttachmentMenu) {
        ModalBottomSheet(
            onDismissRequest = { showAttachmentMenu = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 14.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Add Attachment",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    AttachmentOptionCard(
                        icon = Icons.Default.PhotoCamera,
                        title = "Camera",
                        subtitle = "Take photo",
                        onClick = {
                            showAttachmentMenu = false
                            try {
                                cameraLauncher.launch(null)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Camera not available", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )

                    AttachmentOptionCard(
                        icon = Icons.Default.PhotoLibrary,
                        title = "Gallery",
                        subtitle = "Choose photo",
                        onClick = {
                            showAttachmentMenu = false
                            imagePickerLauncher.launch("image/*")
                        }
                    )

                    AttachmentOptionCard(
                        icon = Icons.Default.Description,
                        title = "Document",
                        subtitle = "Pick file",
                        onClick = {
                            showAttachmentMenu = false
                            filePickerLauncher.launch("*/*")
                        }
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (showModelSheet) {
        ModelBottomSheet(
            selectedModel = selectedModel,
            onSelectModel = { model ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.selectModel(model)
                showModelSheet = false
                Toast.makeText(context, "Active model: ${model.name}", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showModelSheet = false }
        )
    }
}

@Composable
fun AttachmentOptionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.width(96.dp)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 16.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(ClaudeTerracotta.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = title,
                    tint = ClaudeTerracotta,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ConnectionStatusBadge(state: ConnectionState, onClick: () -> Unit) {
    val (color, text) = when (state) {
        ConnectionState.CONNECTED -> Color(0xFF4CAF50) to "Online"
        ConnectionState.CONNECTING -> Color(0xFFFFB300) to "Connecting"
        ConnectionState.ERROR -> Color(0xFFEF5350) to "Retry"
        ConnectionState.DISCONNECTED -> Color(0xFF9E9E9E) to "Offline"
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.12f))
            .border(0.6.dp, color.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun MessageItem(
    message: Message,
    isSpeaking: Boolean = false,
    isLastAssistant: Boolean = false,
    onToggleThinking: () -> Unit,
    onToggleTools: () -> Unit = {},
    onRetry: () -> Unit = {},
    onEditMessage: (String) -> Unit = {},
    onDeleteMessage: () -> Unit = {},
    onToggleSpeak: () -> Unit = {},
    onFeedback: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var showUserActions by remember { mutableStateOf(false) }

    when (message.role) {
        "user" -> {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 310.dp)
                        .clip(RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp))
                        .clickable { showUserActions = !showUserActions }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Column {
                        // Display attached image if present
                        if (!message.attachmentUri.isNullOrBlank()) {
                            if (message.attachmentIsImage) {
                                AsyncImage(
                                    model = message.attachmentUri,
                                    contentDescription = "Attached image",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 200.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(Modifier.height(8.dp))
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .border(0.6.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.InsertDriveFile,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = ClaudeTerracotta
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = message.attachmentName ?: "Attached file",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }

                        if (message.content.isNotBlank() && !message.content.startsWith("Sent an attachment:")) {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 15.sp,
                                    lineHeight = 23.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // Interactive Quick Actions for User Message
                AnimatedVisibility(visible = showUserActions) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp, end = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TextButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onEditMessage(message.content)
                                showUserActions = false
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(13.dp), tint = ClaudeTerracotta)
                            Spacer(Modifier.width(4.dp))
                            Text("Edit", style = MaterialTheme.typography.labelSmall, color = ClaudeTerracotta)
                        }

                        TextButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("User Prompt", message.content))
                                Toast.makeText(context, "Copied prompt", Toast.LENGTH_SHORT).show()
                                showUserActions = false
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(4.dp))
                            Text("Copy", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        TextButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDeleteMessage()
                                showUserActions = false
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(4.dp))
                            Text("Delete", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        "assistant" -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                // Claude Avatar with Terracotta Sparkle
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ClaudeTerracotta),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✦",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // 1. Thinking Process Card (Claude 3.7 Style)
                    if (!message.thinking.isNullOrBlank()) {
                        ThinkingAccordionCard(
                            thinkingText = message.thinking,
                            isExpanded = message.isThinkingExpanded,
                            onToggle = onToggleThinking
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    // 2. AGY Terminal Execution Card (bash commands, file edits, views, searches)
                    if (message.toolExecutions.isNotEmpty() || !message.toolExecution.isNullOrBlank()) {
                        AgyTerminalExecutionCard(
                            toolItems = message.toolExecutions,
                            singleStatus = message.toolExecution,
                            isExpanded = message.isToolsExpanded,
                            onToggle = onToggleTools
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    // 3. Main Message Markdown Content
                    if (message.content.isNotBlank()) {
                        MarkdownContent(text = message.content)
                    }

                    // 4. Subtle Action Bar below assistant message (ChatGPT & Gemini style)
                    if (!message.isStreaming) {
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Copy
                            IconButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Response", message.content))
                                    Toast.makeText(context, "Copied response to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "Copy message",
                                    modifier = Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            Spacer(Modifier.width(2.dp))

                            // Read Aloud / Stop (TTS)
                            IconButton(
                                onClick = onToggleSpeak,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    if (isSpeaking) Icons.Default.Stop else Icons.Default.VolumeUp,
                                    contentDescription = if (isSpeaking) "Stop reading" else "Read aloud",
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isSpeaking) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            Spacer(Modifier.width(2.dp))

                            // Share
                            IconButton(
                                onClick = {
                                    val intent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, message.content)
                                        type = "text/plain"
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Share response"))
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = "Share",
                                    modifier = Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            Spacer(Modifier.width(2.dp))

                            // Thumbs Up (Feedback)
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onFeedback("like")
                                    Toast.makeText(context, if (message.feedback == "like") "Feedback removed" else "Thanks for the feedback!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.ThumbUp,
                                    contentDescription = "Good response",
                                    modifier = Modifier.size(15.dp),
                                    tint = if (message.feedback == "like") ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                            Spacer(Modifier.width(2.dp))

                            // Thumbs Down (Feedback)
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onFeedback("dislike")
                                    Toast.makeText(context, if (message.feedback == "dislike") "Feedback removed" else "Feedback recorded", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.ThumbDown,
                                    contentDescription = "Bad response",
                                    modifier = Modifier.size(15.dp),
                                    tint = if (message.feedback == "dislike") ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                            Spacer(Modifier.width(2.dp))

                            // Regenerate / Retry (if last assistant response)
                            if (isLastAssistant) {
                                IconButton(
                                    onClick = onRetry,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = "Regenerate response",
                                        modifier = Modifier.size(15.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                                Spacer(Modifier.width(2.dp))
                            }

                            // Delete message
                            IconButton(
                                onClick = onDeleteMessage,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Delete message",
                                    modifier = Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        }
        else -> {
            // System message pill
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
fun ThinkingAccordionCard(
    thinkingText: String,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val bgColor = if (isDark) ThinkingPurpleBgDark else ThinkingPurpleBgLight
    val borderColor = if (isDark) ThinkingPurpleBorderDark else ThinkingPurpleBorderLight
    val textColor = if (isDark) ThinkingPurpleTextDark else ThinkingPurpleTextLight

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = textColor
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Thinking Process",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
            }
            Icon(
                if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = textColor
            )
        }

        AnimatedVisibility(visible = isExpanded) {
            Text(
                text = thinkingText,
                style = MaterialTheme.typography.bodySmall.copy(
                    lineHeight = 18.sp,
                    fontFamily = FontFamily.Monospace
                ),
                color = textColor.copy(alpha = 0.9f),
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

@Composable
fun AgyTerminalExecutionCard(
    toolItems: List<ToolExecutionItem>,
    singleStatus: String?,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f

    val terminalCardBg = if (isDark) Color(0xFF16171D) else Color(0xFFF6F5F2)
    val terminalBorder = if (isDark) Color(0xFF282932) else Color(0xFFE2DFD8)
    val terminalHeaderBg = if (isDark) Color(0xFF1E2028) else Color(0xFFECEAE3)
    val terminalCodeBg = if (isDark) Color(0xFF0F1014) else Color(0xFFFFFFFF)
    val terminalCodeBorder = if (isDark) Color(0xFF252630) else Color(0xFFDDD9D0)
    val greenAccent = Color(0xFF10B981)
    val amberAccent = Color(0xFFF59E0B)

    val anyRunning = toolItems.any { it.state == "ACTIVE" }
    val totalToolsCount = maxOf(toolItems.size, if (!singleStatus.isNullOrBlank()) 1 else 0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(terminalCardBg)
            .border(1.dp, terminalBorder, RoundedCornerShape(12.dp))
    ) {
        // Terminal Card Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(terminalHeaderBg)
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (anyRunning) amberAccent.copy(alpha = 0.2f) else greenAccent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = if (anyRunning) amberAccent else greenAccent
                    )
                }

                Spacer(Modifier.width(9.dp))

                Text(
                    text = "AGY Execution",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.width(8.dp))

                // Pill counter
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (anyRunning) amberAccent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (anyRunning) "Running..." else "$totalToolsCount step${if (totalToolsCount != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = if (anyRunning) amberAccent else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Icon(
                if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Body
        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (toolItems.isEmpty() && !singleStatus.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(terminalCodeBg)
                            .border(0.6.dp, terminalCodeBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = singleStatus,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                } else {
                    for (item in toolItems) {
                        ToolExecutionItemRow(
                            item = item,
                            codeBg = terminalCodeBg,
                            borderColor = terminalCodeBorder,
                            isDark = isDark
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ToolExecutionItemRow(
    item: ToolExecutionItem,
    codeBg: Color,
    borderColor: Color,
    isDark: Boolean
) {
    val context = LocalContext.current
    var isOutputExpanded by remember { mutableStateOf(false) }

    val (icon, iconTint, toolTitle) = when (item.toolName) {
        "run_command" -> Triple(
            Icons.Default.Terminal,
            Color(0xFF10B981),
            item.command ?: "bash command"
        )
        "replace_file_content", "write_to_file", "sed_file" -> Triple(
            Icons.Default.Edit,
            Color(0xFFF59E0B),
            item.targetFile ?: "File Edit"
        )
        "view_file", "read_resource", "read_url_content" -> Triple(
            Icons.Default.Description,
            Color(0xFF3B82F6),
            item.targetFile ?: "View File"
        )
        "grep_search", "find_by_name", "search_web" -> Triple(
            Icons.Default.Search,
            Color(0xFF8B5CF6),
            item.parametersSummary ?: item.toolName
        )
        else -> Triple(
            Icons.Default.Code,
            ClaudeTerracotta,
            item.toolName
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(codeBg)
            .border(0.7.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(9.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = iconTint
                )

                Spacer(Modifier.width(7.dp))

                Text(
                    text = toolTitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.width(6.dp))

            // Duration or Active status pill
            if (item.state == "ACTIVE") {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFF59E0B).copy(alpha = 0.2f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "ACTIVE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color(0xFFF59E0B)
                    )
                }
            } else if (item.durationSeconds > 0) {
                Text(
                    text = String.format(Locale.US, "%.2fs", item.durationSeconds),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }

        // Show command line if tool is run_command and title was different
        if (item.toolName == "run_command" && !item.command.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "\$ ",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF10B981)
                    )
                )
                Text(
                    text = item.command,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }

        // Output toggle & Console
        if (!item.output.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { isOutputExpanded = !isOutputExpanded }
                    .padding(vertical = 2.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isOutputExpanded) "▼ Hide output" else "▶ Show output",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = ClaudeTerracotta
                )
            }

            AnimatedVisibility(visible = isOutputExpanded) {
                Column(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isDark) Color(0xFF090A0D) else Color(0xFF202124))
                        .padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Command Output", item.output))
                                Toast.makeText(context, "Output copied", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy output",
                                modifier = Modifier.size(12.dp),
                                tint = Color(0xFFA6A6B0)
                            )
                        }
                    }

                    Text(
                        text = item.output,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        ),
                        color = Color(0xFFEDEDF0),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}

@Composable
fun ClaudeTypingBubble() {
    Row(
        modifier = Modifier.padding(start = 44.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "dots")
        val alpha1 by infiniteTransition.animateFloat(
            initialValue = 0.3f, targetValue = 1f,
            animationSpec = infiniteRepeatable(keyframes { durationMillis = 900; 0.3f at 0; 1f at 300; 0.3f at 600 }, RepeatMode.Restart), label = "a1"
        )
        val alpha2 by infiniteTransition.animateFloat(
            initialValue = 0.3f, targetValue = 1f,
            animationSpec = infiniteRepeatable(keyframes { durationMillis = 900; 0.3f at 150; 1f at 450; 0.3f at 750 }, RepeatMode.Restart), label = "a2"
        )
        val alpha3 by infiniteTransition.animateFloat(
            initialValue = 0.3f, targetValue = 1f,
            animationSpec = infiniteRepeatable(keyframes { durationMillis = 900; 0.3f at 300; 1f at 600; 0.3f at 900 }, RepeatMode.Restart), label = "a3"
        )

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(0.6.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(alpha1, alpha2, alpha3).forEach { a ->
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(ClaudeTerracotta.copy(alpha = a))
                )
            }
        }
    }
}

@Composable
fun QuickSlashChipsRow(
    plugins: List<PluginItem>,
    onChipClick: (PluginItem) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (p in plugins) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(0.8.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                    .clickable { onChipClick(p) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    p.icon,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = ClaudeTerracotta
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = p.prefix,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun ClaudeFloatingInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onOpenPlugins: () -> Unit,
    onAttachFile: () -> Unit,
    onVoiceInput: () -> Unit = {},
    attachment: AttachmentItem?,
    onRemoveAttachment: () -> Unit,
    isConnected: Boolean,
    isLoading: Boolean
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(24.dp))
            .border(1.2.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            // Attachment Preview Bar
            AnimatedVisibility(visible = attachment != null) {
                if (attachment != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(0.8.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (attachment.isImage) {
                            AsyncImage(
                                model = attachment.uri,
                                contentDescription = "Attached image",
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(ClaudeTerracotta.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.InsertDriveFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = ClaudeTerracotta
                                )
                            }
                        }

                        Spacer(Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = attachment.name,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (attachment.isImage) "Image ready to send" else "File ready to send",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = onRemoveAttachment,
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove attachment",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

            // Main Input Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                // Plugins drawer button (+)
                IconButton(
                    onClick = onOpenPlugins,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Slash Plugins",
                        tint = ClaudeTerracotta,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(Modifier.width(4.dp))

                // Attach File / Image Button (Paperclip)
                IconButton(
                    onClick = onAttachFile,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = "Attach file or image",
                        tint = if (attachment != null) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(Modifier.width(6.dp))

                // Multi-line Text input
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 2.dp),
                    placeholder = {
                        Text(
                            if (isConnected) "Message Next AI or type /..." else "Connect in Settings to chat...",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    },
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Default
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        cursorColor = ClaudeTerracotta
                    )
                )

                // Voice Dictation (Mic) or Clear (✕)
                if (text.isNotBlank()) {
                    IconButton(
                        onClick = { onTextChange("") },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Clear input",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    IconButton(
                        onClick = onVoiceInput,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "Voice dictation",
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(Modifier.width(4.dp))

                // Claude Circular Send Button
                val canSend = (text.isNotBlank() || attachment != null) && isConnected && !isLoading
                FilledIconButton(
                    onClick = onSend,
                    enabled = canSend,
                    modifier = Modifier.size(38.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = ClaudeTerracotta,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = ClaudeTerracotta
                        )
                    } else {
                        Icon(
                            Icons.Default.ArrowUpward,
                            contentDescription = "Send",
                            tint = if (canSend) Color.White else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyChatGreeting(
    plugins: List<PluginItem>,
    onPromptCardClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Claude Terracotta Star Halo
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(ClaudeTerracotta.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "✦",
                color = ClaudeTerracotta,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = "How can I help you today?",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.4).sp
            ),
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = "Connected to Antigravity CLI in Google Colab",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(26.dp))

        // ChatGPT / Gemini Style Suggestion Cards
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val starterPrompts = listOf(
                Triple("💡 Brainstorm Ideas", "Explore creative concepts and solutions", "/boost brainstorm creative project ideas"),
                Triple("💻 Write & Debug Code", "Inspect codebase, find bugs, or optimize", "/boost analyze codebase architecture and improve it"),
                Triple("📋 Phased Roadmap", "Plan feature delivery with step-by-step phases", "/plan design next sprint features step by step"),
                Triple("🌐 Real-time Web Search", "Search latest docs, releases, and information", "/browser search for latest Jetpack Compose features")
            )

            for ((title, subtitle, prompt) in starterPrompts) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onPromptCardClick(prompt) },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.Default.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = ClaudeTerracotta
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryDrawerContent(
    conversations: List<ConversationEntity>,
    activeId: String,
    onSelectConversation: (String) -> Unit,
    onNewChat: () -> Unit,
    onDelete: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

    ModalDrawerSheet(
        modifier = Modifier.width(310.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Conversations",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp
                )
            }

            Spacer(Modifier.height(14.dp))

            Button(
                onClick = onNewChat,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
            ) {
                Icon(Icons.Default.Add, null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text("Start New Chat", color = Color.White, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(conversations, key = { it.id }) { conv ->
                    val isActive = conv.id == activeId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isActive) ClaudeTerracotta.copy(alpha = 0.12f)
                                else Color.Transparent
                            )
                            .clickable { onSelectConversation(conv.id) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.ChatBubbleOutline,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                            tint = if (isActive) ClaudeTerracotta else MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = conv.title.ifBlank { "Untitled Chat" },
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (isActive) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = dateFormat.format(Date(conv.updatedAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        IconButton(
                            onClick = { onDelete(conv.id) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Delete",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                thickness = 0.8.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenSettings)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Settings & Colab Tunnel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun SlashCommandAutocompletePopup(
    query: String,
    commands: List<PluginItem>,
    onSelect: (PluginItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .shadow(8.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "COMMANDS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${commands.size} available",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            HorizontalDivider(
                thickness = 0.6.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
            ) {
                items(commands, key = { it.prefix }) { cmd ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(cmd) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(cmd.badgeColor).copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = cmd.icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = Color(cmd.badgeColor)
                            )
                        }

                        Spacer(Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = cmd.prefix,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = ClaudeTerracotta
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = cmd.title,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = cmd.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(Modifier.width(6.dp))

                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(cmd.badgeColor).copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = cmd.tag,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Color(cmd.badgeColor),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelBottomSheet(
    selectedModel: AiModel,
    onSelectModel: (AiModel) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ClaudeTerracotta.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✦",
                        color = ClaudeTerracotta,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column {
                    Text(
                        text = "Antigravity Models",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Choose CLI intelligence running on Colab",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(thickness = 0.8.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val groupedModels = ModelRegistry.ALL_MODELS.groupBy { it.provider }

                groupedModels.forEach { (provider, models) ->
                    item {
                        Text(
                            text = provider.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            ),
                            color = ClaudeTerracotta,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    items(models, key = { it.id }) { model ->
                        val isSelected = model.id == selectedModel.id
                        Surface(
                            onClick = { onSelectModel(model) },
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) ClaudeTerracotta.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant,
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (isSelected) 1.5.dp else 0.8.dp,
                                color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { onSelectModel(model) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = ClaudeTerracotta
                                    )
                                )

                                Spacer(Modifier.width(8.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = model.name,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (model.badge.isNotBlank()) {
                                            Spacer(Modifier.width(8.dp))
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (isSelected) ClaudeTerracotta.copy(alpha = 0.2f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                            ) {
                                                Text(
                                                    text = model.badge,
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    ),
                                                    color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(3.dp))

                                    Text(
                                        text = model.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Spacer(Modifier.height(4.dp))

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (model.supportsEffort) Icons.Default.Tune else Icons.Default.Lock,
                                            contentDescription = null,
                                            modifier = Modifier.size(12.dp),
                                            tint = if (model.supportsEffort) MaterialTheme.colorScheme.outline else ClaudeTerracotta
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = if (model.supportsEffort) "Reasoning effort: Low · Medium · High" else "Thinking: Fixed to CLI default (cannot change)",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = if (model.supportsEffort) MaterialTheme.colorScheme.outline else ClaudeTerracotta
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}
