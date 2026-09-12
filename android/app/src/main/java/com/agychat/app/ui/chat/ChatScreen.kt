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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
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
import com.agychat.app.ui.common.NextAiLogo
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

/** Format a timestamp as a relative human-readable string: "just now", "2m ago", "3h ago", "Yesterday", "Sep 11" */
fun formatRelativeTime(epochMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - epochMs
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        diff < 172_800_000 -> "Yesterday"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMs))
    }
}

/** Format epoch millis to a short time string for inline display: e.g. "3:42 PM" */
fun formatMessageTime(epochMs: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(epochMs))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
    pluginManager: PluginManager,
    onNavigateToSettings: () -> Unit,
    onNavigateToPersonalization: () -> Unit = {}
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val currentStatus by viewModel.currentStatus.collectAsState()
    val conversations by viewModel.conversations.collectAsState(initial = emptyList())
    val selectedAttachment by viewModel.selectedAttachment.collectAsState()
    val selectedAttachments by viewModel.selectedAttachments.collectAsState()
    val reasoningEffort by viewModel.reasoningEffort.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var showPluginBottomSheet by remember { mutableStateOf(false) }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var showEffortMenu by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showMemorySheet by remember { mutableStateOf(false) }
    var showCustomInstructionsSheet by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    val enabledMemoriesCount by viewModel.enabledMemoriesCount.collectAsState(initial = 0)
    val isTemporaryChat by viewModel.isTemporaryChat.collectAsState()
    val customInstructions by viewModel.customInstructions.collectAsState()
    val replyToMessage by viewModel.replyToMessage.collectAsState()
    val sessionFiles by viewModel.sessionFiles.collectAsState()
    var showArtifactsSheet by remember { mutableStateOf(false) }
    var speakingMessageId by remember { mutableStateOf<String?>(null) }
    var tts: TextToSpeech? by remember { mutableStateOf(null) }

    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    // Initialize TextToSpeech engine safely
    DisposableEffect(context) {
        var textToSpeech: TextToSpeech? = null
        try {
            textToSpeech = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    try {
                        tts?.language = Locale.US
                    } catch (_: Throwable) {}
                }
            }
            tts = textToSpeech
        } catch (t: Throwable) {
            android.util.Log.w("ChatScreen", "TTS initialization failed, voice readout disabled", t)
        }
        onDispose {
            try {
                textToSpeech?.stop()
                textToSpeech?.shutdown()
            } catch (_: Throwable) {}
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
                viewModel.addAttachment(
                    AttachmentItem(
                        uri = Uri.fromFile(photoFile).toString(),
                        name = photoFile.name,
                        isImage = true,
                        mimeType = "image/jpeg"
                    )
                )
                Toast.makeText(context, "Photo attached!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to capture photo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Gallery / Multi-Photo picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            val items = uris.map { uri ->
                val fileName = queryFileName(context, uri)
                AttachmentItem(
                    uri = uri.toString(),
                    name = fileName,
                    isImage = true,
                    mimeType = context.contentResolver.getType(uri) ?: "image/*"
                )
            }
            viewModel.addAttachments(items)
            val msg = if (items.size == 1) "Photo attached: ${items[0].name}" else "${items.size} photos attached!"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // General Multi-File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            val items = uris.map { uri ->
                val fileName = queryFileName(context, uri)
                val mimeType = context.contentResolver.getType(uri) ?: ""
                val isImg = mimeType.startsWith("image/") ||
                        fileName.lowercase().endsWith(".png") ||
                        fileName.lowercase().endsWith(".jpg") ||
                        fileName.lowercase().endsWith(".jpeg") ||
                        fileName.lowercase().endsWith(".webp")
                AttachmentItem(
                    uri = uri.toString(),
                    name = fileName,
                    isImage = isImg,
                    mimeType = mimeType
                )
            }
            viewModel.addAttachments(items)
            val msg = if (items.size == 1) "File attached: ${items[0].name}" else "${items.size} files attached!"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
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

    val showPinnedOnly by viewModel.showPinnedOnly.collectAsState()
    var isInChatSearchOpen by remember { mutableStateOf(false) }
    var inChatSearchQuery by remember { mutableStateOf("") }
    var currentSearchMatchIndex by remember { mutableIntStateOf(0) }

    val pinnedCount = remember(messages) { messages.count { it.isPinned } }
    val displayedMessages = remember(messages, showPinnedOnly) {
        if (showPinnedOnly) messages.filter { it.isPinned } else messages
    }

    val matchingIndices = remember(displayedMessages, inChatSearchQuery) {
        if (inChatSearchQuery.isBlank()) emptyList()
        else displayedMessages.mapIndexedNotNull { index, msg ->
            if (msg.content.contains(inChatSearchQuery, ignoreCase = true) ||
                (msg.thinking?.contains(inChatSearchQuery, ignoreCase = true) == true)
            ) index else null
        }
    }

    val navigateSearchMatch: (Boolean) -> Unit = { forward ->
        if (matchingIndices.isNotEmpty()) {
            val nextIdx = if (forward) {
                (currentSearchMatchIndex + 1) % matchingIndices.size
            } else {
                (currentSearchMatchIndex - 1 + matchingIndices.size) % matchingIndices.size
            }
            currentSearchMatchIndex = nextIdx
            scope.launch {
                listState.animateScrollToItem(matchingIndices[nextIdx])
            }
        }
    }

    LaunchedEffect(inChatSearchQuery) {
        currentSearchMatchIndex = 0
        if (matchingIndices.isNotEmpty()) {
            listState.animateScrollToItem(matchingIndices[0])
        }
    }

    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val isTablet = screenWidthDp >= 600
    var isTabletSidebarExpanded by remember { mutableStateOf(screenWidthDp >= 720) }

    val chatScaffoldContent = @Composable {
        Scaffold(
            topBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        TopAppBar(
                            navigationIcon = {
                                IconButton(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (isTablet) {
                                        isTabletSidebarExpanded = !isTabletSidebarExpanded
                                    } else {
                                        scope.launch { drawerState.open() }
                                    }
                                }) {
                                    Icon(
                                        Icons.Default.Menu,
                                        contentDescription = if (isTablet) "Toggle Sidebar" else "History Drawer",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            },
                            title = {
                                Surface(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        showModelSheet = true
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                    border = androidx.compose.foundation.BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val statusColor = when (connectionState) {
                                            ConnectionState.CONNECTED -> Color(0xFF10A37F)
                                            ConnectionState.CONNECTING -> Color(0xFFF59E0B)
                                            ConnectionState.ERROR -> Color(0xFFEF5350)
                                            ConnectionState.DISCONNECTED -> Color(0xFF9E9E9E)
                                        }
                                        Box(
                                            modifier = Modifier
                                                .size(6.5.dp)
                                                .clip(CircleShape)
                                                .background(statusColor)
                                        )
                                        Spacer(Modifier.width(7.dp))
                                        val shortModelName = when {
                                            selectedModel.id.contains("3.8") -> "Gemini 3.8 Flash"
                                            selectedModel.id.contains("3.7") -> "Gemini 3.7 Flash"
                                            selectedModel.id.contains("3.6") -> "Gemini 3.6 Flash"
                                            selectedModel.id.contains("3.1") -> "Gemini 3.1 Pro"
                                            selectedModel.id.contains("opus") -> "Claude Opus 4.6"
                                            selectedModel.id.contains("sonnet") -> "Claude Sonnet 4.6"
                                            selectedModel.id.contains("gpt-oss") -> "GPT-OSS 120B"
                                            else -> selectedModel.name
                                        }
                                        Text(
                                            text = shortModelName,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.width(3.dp))
                                        Icon(
                                            Icons.Default.KeyboardArrowDown,
                                            contentDescription = "Select Model",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            actions = {
                                if (isTemporaryChat) {
                                    Surface(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.toggleTemporaryChat()
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = ClaudeTerracotta.copy(alpha = 0.12f),
                                        border = androidx.compose.foundation.BorderStroke(0.6.dp, ClaudeTerracotta.copy(alpha = 0.4f)),
                                        modifier = Modifier.padding(end = 2.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.VisibilityOff,
                                                contentDescription = "Temporary Chat Active",
                                                modifier = Modifier.size(13.dp),
                                                tint = ClaudeTerracotta
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

                                if (sessionFiles.isNotEmpty()) {
                                    IconButton(onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        showArtifactsSheet = true
                                    }) {
                                        BadgedBox(badge = {
                                            Badge(containerColor = ClaudeTerracotta) {
                                                Text("${sessionFiles.size}")
                                            }
                                        }) {
                                            Icon(
                                                Icons.Default.FolderOpen,
                                                contentDescription = "Session Artifacts",
                                                tint = ClaudeTerracotta
                                            )
                                        }
                                    }
                                }

                                IconButton(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    isInChatSearchOpen = !isInChatSearchOpen
                                    if (!isInChatSearchOpen) inChatSearchQuery = ""
                                }) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = "Search in conversation",
                                        tint = if (isInChatSearchOpen) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Box {
                                    IconButton(onClick = { showMoreMenu = true }) {
                                        Icon(
                                            Icons.Default.MoreVert,
                                            contentDescription = "More options",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = showMoreMenu,
                                        onDismissRequest = { showMoreMenu = false }
                                    ) {
                                        if (selectedModel.supportsEffort) {
                                            DropdownMenuItem(
                                                leadingIcon = {
                                                    Icon(Icons.Default.ElectricBolt, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.size(18.dp))
                                                },
                                                text = {
                                                    Column {
                                                        Text("Thinking Effort", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                                        Text(reasoningEffort.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall, color = ClaudeTerracotta)
                                                    }
                                                },
                                                onClick = {
                                                    showMoreMenu = false
                                                    showEffortMenu = true
                                                }
                                            )
                                        }

                                        DropdownMenuItem(
                                            leadingIcon = {
                                                Icon(Icons.Default.Psychology, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.size(18.dp))
                                            },
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text("Memory", style = MaterialTheme.typography.bodyMedium)
                                                    if (enabledMemoriesCount > 0) {
                                                        Spacer(Modifier.width(6.dp))
                                                        Surface(
                                                            shape = RoundedCornerShape(8.dp),
                                                            color = ClaudeTerracotta.copy(alpha = 0.15f)
                                                        ) {
                                                            Text(
                                                                "$enabledMemoriesCount",
                                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                                                color = ClaudeTerracotta,
                                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            },
                                            onClick = {
                                                showMoreMenu = false
                                                showMemorySheet = true
                                            }
                                        )

                                        DropdownMenuItem(
                                            leadingIcon = {
                                                Icon(Icons.Default.Person, contentDescription = null, tint = ChatGptPurple, modifier = Modifier.size(18.dp))
                                            },
                                            text = {
                                                Text(
                                                    if (customInstructions.isEnabled && (customInstructions.aboutUser.isNotBlank() || customInstructions.responsePreferences.isNotBlank()))
                                                        "Custom Instructions (Active)" else "Custom Instructions",
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            },
                                            onClick = {
                                                showMoreMenu = false
                                                showCustomInstructionsSheet = true
                                            }
                                        )

                                        DropdownMenuItem(
                                            leadingIcon = {
                                                Icon(
                                                    if (isTemporaryChat) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                    contentDescription = null,
                                                    tint = if (isTemporaryChat) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            },
                                            text = {
                                                Text(
                                                    if (isTemporaryChat) "Exit Temporary Chat" else "Temporary Chat",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = if (isTemporaryChat) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                                                )
                                            },
                                            onClick = {
                                                showMoreMenu = false
                                                viewModel.toggleTemporaryChat()
                                            }
                                        )

                                        DropdownMenuItem(
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.PushPin,
                                                    contentDescription = null,
                                                    tint = if (showPinnedOnly) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            },
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        if (showPinnedOnly) "Show All Messages" else "Pinned Messages",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = if (showPinnedOnly) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurface
                                                    )
                                                    if (pinnedCount > 0) {
                                                        Spacer(Modifier.width(6.dp))
                                                        Surface(
                                                            shape = RoundedCornerShape(8.dp),
                                                            color = Color(0xFFFFB300).copy(alpha = 0.15f)
                                                        ) {
                                                            Text(
                                                                "$pinnedCount",
                                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                                                color = Color(0xFFFFB300),
                                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            },
                                            onClick = {
                                                showMoreMenu = false
                                                viewModel.toggleShowPinnedOnly()
                                            }
                                        )

                                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                        DropdownMenuItem(
                                            leadingIcon = {
                                                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.size(18.dp))
                                            },
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text("Artifacts & Files", style = MaterialTheme.typography.bodyMedium)
                                                    if (sessionFiles.isNotEmpty()) {
                                                        Spacer(Modifier.width(6.dp))
                                                        Surface(
                                                            shape = RoundedCornerShape(8.dp),
                                                            color = ClaudeTerracotta.copy(alpha = 0.15f)
                                                        ) {
                                                            Text(
                                                                "${sessionFiles.size}",
                                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                                                color = ClaudeTerracotta,
                                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            },
                                            onClick = {
                                                showMoreMenu = false
                                                showArtifactsSheet = true
                                            }
                                        )

                                        DropdownMenuItem(
                                            leadingIcon = {
                                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                            },
                                            text = { Text("Share / Export Chat", style = MaterialTheme.typography.bodyMedium) },
                                            onClick = {
                                                showMoreMenu = false
                                                val md = viewModel.exportConversationToMarkdown()
                                                val sendIntent = Intent().apply {
                                                    action = Intent.ACTION_SEND
                                                    putExtra(Intent.EXTRA_TEXT, md)
                                                    type = "text/plain"
                                                }
                                                context.startActivity(Intent.createChooser(sendIntent, "Export Chat"))
                                            }
                                        )

                                        DropdownMenuItem(
                                            leadingIcon = {
                                                Icon(Icons.Default.Download, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.size(18.dp))
                                            },
                                            text = { Text("Save Chat to Phone (.md)", style = MaterialTheme.typography.bodyMedium) },
                                            onClick = {
                                                showMoreMenu = false
                                                viewModel.saveExportToDownloads { success, msg ->
                                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        )

                                        DropdownMenuItem(
                                            leadingIcon = {
                                                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                                            },
                                            text = { Text("Personalization & Memory", style = MaterialTheme.typography.bodyMedium) },
                                            onClick = {
                                                showMoreMenu = false
                                                onNavigateToPersonalization()
                                            }
                                        )
                                        DropdownMenuItem(
                                            leadingIcon = {
                                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                                            },
                                            text = { Text("Settings & Bridge", style = MaterialTheme.typography.bodyMedium) },
                                            onClick = {
                                                showMoreMenu = false
                                                onNavigateToSettings()
                                            }
                                        )
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        )

                        // Connection Resilience Banner
                        AnimatedVisibility(
                            visible = connectionState == ConnectionState.ERROR || connectionState == ConnectionState.DISCONNECTED,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Icon(
                                            Icons.Default.CloudOff,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = if (connectionState == ConnectionState.ERROR) "Disconnected from Colab Bridge" else "Bridge Offline",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                    TextButton(
                                        onClick = { viewModel.reconnect() },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            "Reconnect",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                            }
                        }

                        // Incognito Mode Banner
                        AnimatedVisibility(
                            visible = isTemporaryChat,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Surface(
                                color = Color(0xFF2E1065).copy(alpha = 0.92f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.VisibilityOff,
                                            contentDescription = null,
                                            tint = Color(0xFFD8B4FE),
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "Incognito Mode • History & memories are not saved",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                            color = Color(0xFFF3E8FF)
                                        )
                                    }
                                    Text(
                                        text = "Exit",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = Color(0xFFD8B4FE),
                                        modifier = Modifier.clickable { viewModel.toggleTemporaryChat() }
                                    )
                                }
                            }
                        }

                        // Expandable In-Chat Search Bar (Ctrl+F for mobile)
                        AnimatedVisibility(
                            visible = isInChatSearchOpen,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = null,
                                        tint = ClaudeTerracotta,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedTextField(
                                        value = inChatSearchQuery,
                                        onValueChange = { inChatSearchQuery = it },
                                        placeholder = {
                                            Text(
                                                "Find in conversation...",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            )
                                        },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color.Transparent,
                                            unfocusedBorderColor = Color.Transparent,
                                            focusedContainerColor = Color.Transparent,
                                            unfocusedContainerColor = Color.Transparent
                                        ),
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
                                        modifier = Modifier.weight(1f)
                                    )

                                    if (inChatSearchQuery.isNotBlank()) {
                                        val matchCount = matchingIndices.size
                                        Text(
                                            text = if (matchCount > 0) "${currentSearchMatchIndex + 1}/$matchCount" else "0/0",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
                                            color = if (matchCount > 0) ClaudeTerracotta else MaterialTheme.colorScheme.error,
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        )

                                        IconButton(
                                            onClick = { navigateSearchMatch(false) },
                                            enabled = matchCount > 1,
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.KeyboardArrowUp,
                                                contentDescription = "Previous match",
                                                tint = if (matchCount > 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = { navigateSearchMatch(true) },
                                            enabled = matchCount > 1,
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.KeyboardArrowDown,
                                                contentDescription = "Next match",
                                                tint = if (matchCount > 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = {
                                            isInChatSearchOpen = false
                                            inChatSearchQuery = ""
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Close search",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Pinned Filter Active Notice Banner
                        AnimatedVisibility(
                            visible = showPinnedOnly,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Surface(
                                color = Color(0xFFFFB300).copy(alpha = 0.12f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.PushPin,
                                        contentDescription = null,
                                        tint = Color(0xFFFFB300),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "Viewing $pinnedCount pinned message${if (pinnedCount == 1) "" else "s"}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = Color(0xFFFFB300),
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = { viewModel.toggleShowPinnedOnly() },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            "Show All",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = Color(0xFFFFB300)
                                        )
                                    }
                                }
                            }
                        }

                        // If effort dropdown is opened from More menu
                        if (selectedModel.supportsEffort) {
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

                        HorizontalDivider(
                            thickness = 0.8.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )

                        // Subtle Status Strip / Connection Notice
                        if (!currentStatus.isNullOrBlank() || connectionState != ConnectionState.CONNECTED) {
                            Surface(
                                color = if (connectionState == ConnectionState.CONNECTED) ClaudeTerracotta.copy(alpha = 0.08f)
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        if (connectionState != ConnectionState.CONNECTED) {
                                            val statusText = when (connectionState) {
                                                ConnectionState.CONNECTING -> "Connecting to Colab bridge…"
                                                ConnectionState.ERROR -> "Bridge disconnected"
                                                ConnectionState.DISCONNECTED -> "Bridge offline"
                                                ConnectionState.CONNECTED -> ""
                                            }
                                            Text(
                                                text = statusText,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (connectionState == ConnectionState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        } else {
                                            Text(
                                                text = currentStatus ?: "",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                                color = ClaudeTerracotta,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    if (connectionState == ConnectionState.DISCONNECTED || connectionState == ConnectionState.ERROR) {
                                        Text(
                                            text = "Reconnect",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = ClaudeTerracotta,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .clickable { viewModel.reconnect() }
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Temporary Chat Banner
                        if (isTemporaryChat) {
                            Surface(
                                color = ClaudeTerracotta.copy(alpha = 0.10f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.VisibilityOff,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = ClaudeTerracotta
                                    )
                                    Text(
                                        text = "Temporary Chat · Memory is paused and won't be saved",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ClaudeTerracotta,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            },
            bottomBar = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .navigationBarsPadding()
                        .imePadding(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 840.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp)
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

                    // Quick-Action Tool & Slash Command Chips (Shown on empty canvas, tucks away during chat)
                    AnimatedVisibility(
                        visible = !isSlashActive && messages.isEmpty(),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
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
                            },
                            onOpenAllTools = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showPluginBottomSheet = true
                            }
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    // Modern ChatGPT Floating Composer with File/Image Attachment & Web Search Toggle
                    ClaudeFloatingInputBar(
                        text = inputText,
                        onTextChange = { inputText = it },
                        onSend = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.sendMessage(inputText)
                            inputText = ""
                        },
                        onStop = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.stopGenerating()
                        },
                        onOpenPlugins = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showPluginBottomSheet = true
                        },
                        onAttachFile = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showAttachmentMenu = true
                        },
                        onToggleWebSearch = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (inputText.startsWith("/browser ")) {
                                inputText = inputText.removePrefix("/browser ")
                            } else if (inputText.startsWith("/browser")) {
                                inputText = inputText.removePrefix("/browser").trimStart()
                            } else {
                                inputText = if (inputText.isBlank()) "/browser " else "/browser $inputText"
                            }
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
                        attachments = selectedAttachments,
                        onRemoveAttachment = { item ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.removeAttachment(item)
                        },
                        onAddMoreAttachments = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showAttachmentMenu = true
                        },
                        replyToMessage = replyToMessage,
                        onCancelReply = { viewModel.setReplyToMessage(null) },
                        isConnected = connectionState == ConnectionState.CONNECTED,
                        isLoading = isLoading
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = 840.dp)
                    .fillMaxWidth()
            ) {
                if (displayedMessages.isEmpty()) {
                    if (showPinnedOnly) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.PushPin,
                                    contentDescription = null,
                                    tint = Color(0xFFFFB300).copy(alpha = 0.5f),
                                    modifier = Modifier.size(52.dp)
                                )
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    "No pinned messages in this chat",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Tap the pin icon on any message to keep it bookmarked here.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                Spacer(Modifier.height(16.dp))
                                OutlinedButton(onClick = { viewModel.toggleShowPinnedOnly() }) {
                                    Text("Show All Messages")
                                }
                            }
                        }
                    } else {
                        EmptyChatGreeting(
                            plugins = pluginManager.plugins,
                            onPromptCardClick = { prompt ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.sendMessage(prompt)
                            },
                            selectedModelName = selectedModel.name,
                            memoriesCount = enabledMemoriesCount,
                            hasCustomInstructions = customInstructions.isEnabled && (customInstructions.aboutUser.isNotBlank() || customInstructions.responsePreferences.isNotBlank()),
                            onOpenMemorySheet = { showMemorySheet = true },
                            onOpenCustomInstructions = { showCustomInstructionsSheet = true }
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 28.dp)
                    ) {
                        items(displayedMessages, key = { it.id }) { msg ->
                            val isLastAssistant = msg.id == displayedMessages.lastOrNull { it.role == "assistant" }?.id
                            val isSpeaking = speakingMessageId == msg.id
                            val isSearchMatch = inChatSearchQuery.isNotBlank() && (
                                msg.content.contains(inChatSearchQuery, ignoreCase = true) ||
                                (msg.thinking?.contains(inChatSearchQuery, ignoreCase = true) == true)
                            )
                            MessageItem(
                                message = msg,
                                isSpeaking = isSpeaking,
                                isLastAssistant = isLastAssistant,
                                isSearchMatch = isSearchMatch,
                                onOpenMemory = { showMemorySheet = true },
                                onTogglePin = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.togglePinMessage(msg.id)
                                },
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
                                },
                                onOpenFile = { filePath ->
                                    viewModel.fetchAndOpenFile(filePath)
                                },
                                onReply = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.setReplyToMessage(msg)
                                },
                                onSendSuggestion = { prompt ->
                                    viewModel.sendMessage(prompt)
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
    }

    if (isTablet) {
        Row(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = isTabletSidebarExpanded,
                enter = expandHorizontally() + fadeIn(),
                exit = shrinkHorizontally() + fadeOut()
            ) {
                Row(modifier = Modifier.fillMaxHeight()) {
                    Surface(
                        modifier = Modifier
                            .width(280.dp)
                            .fillMaxHeight(),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        HistoryDrawerContent(
                            conversations = conversations,
                            activeId = viewModel.currentConversationId,
                            onSelectConversation = { id ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.loadConversation(id)
                            },
                            onNewChat = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.startNewConversation()
                            },
                            onDelete = { id ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.deleteConversation(id)
                            },
                            onOpenSettings = onNavigateToSettings,
                            onOpenMemory = { showMemorySheet = true },
                            onOpenCustomInstructions = { showCustomInstructionsSheet = true }
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                chatScaffoldContent()
            }
        }
    } else {
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
                    },
                    onOpenMemory = {
                        scope.launch { drawerState.close() }
                        showMemorySheet = true
                    },
                    onOpenCustomInstructions = {
                        scope.launch { drawerState.close() }
                        showCustomInstructionsSheet = true
                    }
                )
            }
        ) {
            chatScaffoldContent()
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
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 640.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Tools & Attachments",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Text(
                    text = "MEDIA & FILES",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AttachmentOptionCard(
                        icon = Icons.Default.PhotoCamera,
                        title = "Camera",
                        subtitle = "Take photo",
                        accentColor = ClaudeTerracotta,
                        modifier = Modifier.weight(1f),
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
                        accentColor = ClaudeTerracotta,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            showAttachmentMenu = false
                            imagePickerLauncher.launch("image/*")
                        }
                    )

                    AttachmentOptionCard(
                        icon = Icons.Default.Description,
                        title = "Document",
                        subtitle = "Pick file",
                        accentColor = ClaudeTerracotta,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            showAttachmentMenu = false
                            filePickerLauncher.launch("*/*")
                        }
                    )
                }

                HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.outlineVariant)

                Text(
                    text = "AI MODES & AGENT CAPABILITIES",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AttachmentOptionCard(
                        icon = Icons.Default.Language,
                        title = "Web Search",
                        subtitle = "Live web search",
                        accentColor = ChatGptBlue,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            showAttachmentMenu = false
                            inputText = if (inputText.startsWith("/browser")) inputText else "/browser $inputText"
                        }
                    )

                    AttachmentOptionCard(
                        icon = Icons.Default.AutoAwesome,
                        title = "Deep Think",
                        subtitle = "Multi-step logic",
                        accentColor = ChatGptPurple,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            showAttachmentMenu = false
                            inputText = if (inputText.startsWith("/boost")) inputText else "/boost $inputText"
                        }
                    )

                    AttachmentOptionCard(
                        icon = Icons.Default.Assignment,
                        title = "Plan Mode",
                        subtitle = "Phased roadmap",
                        accentColor = ClaudeTerracotta,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            showAttachmentMenu = false
                            inputText = if (inputText.startsWith("/plan")) inputText else "/plan $inputText"
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AttachmentOptionCard(
                        icon = Icons.Default.RocketLaunch,
                        title = "Auto Goal",
                        subtitle = "Loop till solved",
                        accentColor = ChatGptEmerald,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            showAttachmentMenu = false
                            inputText = if (inputText.startsWith("/goal")) inputText else "/goal $inputText"
                        }
                    )

                    AttachmentOptionCard(
                        icon = Icons.Default.Psychology,
                        title = "Memories",
                        subtitle = "Saved facts",
                        accentColor = ClaudeTerracotta,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            showAttachmentMenu = false
                            showMemorySheet = true
                        }
                    )

                    AttachmentOptionCard(
                        icon = Icons.Default.Person,
                        title = "Profile",
                        subtitle = "Instructions",
                        accentColor = ChatGptPurple,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            showAttachmentMenu = false
                            showCustomInstructionsSheet = true
                        }
                    )
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }
    }

    if (showModelSheet) {
        ModelBottomSheet(
            selectedModel = selectedModel,
            reasoningEffort = reasoningEffort,
            onSelectEffort = { effort ->
                viewModel.setReasoningEffort(effort)
            },
            onSelectModel = { model ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.selectModel(model)
                showModelSheet = false
                Toast.makeText(context, "Active model: ${model.name}", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showModelSheet = false }
        )
    }

    if (showMemorySheet) {
        ManageMemorySheet(
            viewModel = viewModel,
            onDismiss = { showMemorySheet = false }
        )
    }

    if (showCustomInstructionsSheet) {
        com.agychat.app.ui.settings.CustomInstructionsSheet(
            viewModel = viewModel,
            onDismiss = { showCustomInstructionsSheet = false }
        )
    }

    val activeFileViewer by viewModel.activeFileViewer.collectAsState()
    if (activeFileViewer != null) {
        val fileData = activeFileViewer!!
        FileViewerBottomSheet(
            fileData = fileData,
            onClose = { viewModel.closeFileViewer() },
            onSaveToPhone = {
                viewModel.saveActiveFileToPhone { success, msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            },
            onCopy = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(fileData.filename, fileData.content))
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            },
            onShare = {
                try {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, fileData.filename)
                        putExtra(Intent.EXTRA_TEXT, fileData.content)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share ${fileData.filename}"))
                } catch (t: Throwable) {
                    Toast.makeText(context, "Share failed: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showArtifactsSheet) {
        SessionArtifactsBottomSheet(
            sessionFiles = sessionFiles,
            onClose = { showArtifactsSheet = false },
            onOpenFile = { path ->
                showArtifactsSheet = false
                viewModel.fetchAndOpenFile(path)
            },
            onCopyPath = { path ->
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("File Path", path))
                Toast.makeText(context, "Copied file path to clipboard", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
fun AttachmentOptionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color = ClaudeTerracotta,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
    isSearchMatch: Boolean = false,
    onToggleThinking: () -> Unit,
    onToggleTools: () -> Unit = {},
    onRetry: () -> Unit = {},
    onEditMessage: (String) -> Unit = {},
    onDeleteMessage: () -> Unit = {},
    onToggleSpeak: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onFeedback: (String) -> Unit = {},
    onOpenMemory: () -> Unit = {},
    onOpenFile: (String) -> Unit = {},
    onReply: () -> Unit = {},
    onSendSuggestion: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var isCopied by remember { mutableStateOf(false) }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            kotlinx.coroutines.delay(2000)
            isCopied = false
        }
    }

    when (message.role) {
        "user" -> {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End
            ) {
                SelectionContainer {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 320.dp)
                            .clip(RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                width = if (isSearchMatch) 2.dp else 1.dp,
                                color = if (isSearchMatch) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Column {
                            if (!message.replyToContent.isNullOrBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                    border = androidx.compose.foundation.BorderStroke(0.6.dp, ClaudeTerracotta.copy(alpha = 0.35f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(3.dp)
                                                .height(24.dp)
                                                .background(ClaudeTerracotta, RoundedCornerShape(2.dp))
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Column {
                                            Text(
                                                text = "Quoting ${if (message.replyToRole == "user") "User" else "Next AI"}",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.5.sp),
                                                color = ClaudeTerracotta
                                            )
                                            Text(
                                                text = message.replyToContent,
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            if (message.isPinned) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.PushPin,
                                        contentDescription = "Pinned",
                                        tint = Color(0xFFFFB300),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(Modifier.width(3.dp))
                                    Text(
                                        "Pinned",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                        color = Color(0xFFFFB300)
                                    )
                                }
                            }
                            // Display attached images and files (multi-attachment support)
                            val allAtts = message.allAttachments
                            if (allAtts.isNotEmpty()) {
                                val images = allAtts.filter { it.isImage }
                                val files = allAtts.filter { !it.isImage }

                                if (images.isNotEmpty()) {
                                    if (images.size == 1) {
                                        AsyncImage(
                                            model = images[0].uri,
                                            contentDescription = "Attached image",
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 240.dp)
                                                .clip(RoundedCornerShape(14.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                        Spacer(Modifier.height(8.dp))
                                    } else {
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            images.chunked(2).forEach { rowImages ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    rowImages.forEach { img ->
                                                        AsyncImage(
                                                            model = img.uri,
                                                            contentDescription = img.name,
                                                            modifier = Modifier
                                                                .weight(1f)
                                                                .height(115.dp)
                                                                .clip(RoundedCornerShape(12.dp)),
                                                            contentScale = ContentScale.Crop
                                                        )
                                                    }
                                                    if (rowImages.size == 1) {
                                                        Spacer(Modifier.weight(1f))
                                                    }
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(8.dp))
                                    }
                                }

                                if (files.isNotEmpty()) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        files.forEach { file ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(MaterialTheme.colorScheme.surface)
                                                    .border(0.6.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                                                    .padding(horizontal = 10.dp, vertical = 7.dp),
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
                                                    text = file.name,
                                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }
                            }

                            if (message.content.isNotBlank() && !message.content.startsWith("Sent an attachment:") && !message.content.startsWith("Sent ")) {
                                MarkdownContent(
                                    text = message.content,
                                    textColor = MaterialTheme.colorScheme.onSurface,
                                    onLinkClick = onOpenFile
                                )
                            }
                        }
                    }
                }

                // Interactive Quick Actions for User Message
                Row(
                    modifier = Modifier.padding(top = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = formatMessageTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(end = 4.dp)
                    )

                    IconButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("User Prompt", message.content))
                            Toast.makeText(context, "Copied prompt", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                        )
                    }

                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onReply()
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Reply,
                            contentDescription = "Reply / Quote",
                            modifier = Modifier.size(13.dp),
                            tint = ClaudeTerracotta.copy(alpha = 0.8f)
                        )
                    }

                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onEditMessage(message.content)
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit",
                            modifier = Modifier.size(13.dp),
                            tint = ClaudeTerracotta.copy(alpha = 0.75f)
                        )
                    }

                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onTogglePin()
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = if (message.isPinned) "Unpin" else "Pin",
                            modifier = Modifier.size(13.dp),
                            tint = if (message.isPinned) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                        )
                    }

                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDeleteMessage()
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Delete",
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.65f)
                        )
                    }
                }
            }
        }
        "assistant" -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                // Next AI Continuum Avatar
                NextAiLogo(
                    size = 30.dp,
                    showBackground = true
                )

                Spacer(Modifier.width(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (isSearchMatch) Modifier
                                .border(1.5.dp, ClaudeTerracotta.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                                .padding(6.dp)
                            else Modifier
                        )
                ) {
                    if (message.isPinned) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFFFFB300).copy(alpha = 0.12f),
                            border = androidx.compose.foundation.BorderStroke(0.6.dp, Color(0xFFFFB300).copy(alpha = 0.35f)),
                            modifier = Modifier.padding(bottom = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.PushPin,
                                    contentDescription = null,
                                    tint = Color(0xFFFFB300),
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "Pinned Answer",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                                    color = Color(0xFFFFB300)
                                )
                            }
                        }
                    }

                    // 1. Thinking Process Card (Claude 3.7 / Gemini Thinking Style)
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
                            onToggle = onToggleTools,
                            onOpenFile = onOpenFile
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    // 2b. Generated Files Artifact Cards (Derivations, markdown notes, code files created by agent)
                    val generatedFiles = remember(message.toolExecutions) {
                        message.toolExecutions
                            .filter { (it.toolName == "write_to_file" || it.toolName == "replace_file_content") && !it.targetFile.isNullOrBlank() }
                            .mapNotNull { it.targetFile }
                            .distinct()
                    }
                    if (generatedFiles.isNotEmpty()) {
                        Column(
                            modifier = Modifier.padding(bottom = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            generatedFiles.forEach { filePath ->
                                val fileName = filePath.substringAfterLast("/").ifBlank { "file" }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.4f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenFile(filePath) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(ClaudeTerracotta.copy(alpha = 0.15f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    Icons.Default.Description,
                                                    contentDescription = null,
                                                    tint = ClaudeTerracotta,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            Spacer(Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    text = fileName,
                                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "Artifact created • Tap to view & save to phone",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                )
                                            }
                                        }
                                        Button(
                                            onClick = { onOpenFile(filePath) },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(30.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                                        ) {
                                            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(13.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Open", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Autonomous Memory Update Banner (ChatGPT Style)
                    if (message.memoryUpdates.isNotEmpty()) {
                        Column(
                            modifier = Modifier.padding(bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            message.memoryUpdates.forEach { memUpdate ->
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = ClaudeTerracotta.copy(alpha = 0.10f),
                                    border = androidx.compose.foundation.BorderStroke(0.6.dp, ClaudeTerracotta.copy(alpha = 0.35f)),
                                    modifier = Modifier.clickable { onOpenMemory() }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text("✨", fontSize = 11.sp)
                                        Text(
                                            text = "Memory updated: \"$memUpdate\"",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                            color = ClaudeTerracotta,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Icon(
                                            Icons.Default.ChevronRight,
                                            contentDescription = null,
                                            tint = ClaudeTerracotta,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. Main Message Markdown Content with streaming cursor (Borderless Canvas Flow)
                    if (message.content.isNotBlank()) {
                        SelectionContainer {
                            MarkdownContent(
                                text = message.content,
                                onLinkClick = onOpenFile
                            )
                        }
                    }
                    // Streaming blinking cursor
                    if (message.isStreaming && message.content.isNotBlank()) {
                        StreamingCursorBlink()
                    }

                    // 4. Subtle Action Bar below assistant message (ChatGPT & Gemini style)
                    if (!message.isStreaming) {
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val wordCount = remember(message.content) {
                                if (message.content.isBlank()) 0
                                else message.content.trim().split(Regex("\\s+")).count { it.isNotBlank() }
                            }
                            val estTokens = (wordCount * 1.33).toInt()

                            // Timestamp inline
                            Text(
                                text = formatRelativeTime(message.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            if (wordCount > 0) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "• $wordCount w (~$estTokens t)",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                )
                            }
                            Spacer(Modifier.width(6.dp))

                            // Pin / Star
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onTogglePin()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.PushPin,
                                    contentDescription = if (message.isPinned) "Unpin message" else "Pin message",
                                    modifier = Modifier.size(15.dp),
                                    tint = if (message.isPinned) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                            Spacer(Modifier.width(2.dp))

                            // Copy with checkmark animation
                            IconButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Response", message.content))
                                    isCopied = true
                                    Toast.makeText(context, "Copied response to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                                    contentDescription = "Copy message",
                                    modifier = Modifier.size(15.dp),
                                    tint = if (isCopied) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
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

                            // Reply / Quote
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onReply()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Reply,
                                    contentDescription = "Reply to message",
                                    modifier = Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            Spacer(Modifier.width(2.dp))

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

                        // 5. Contextual Quick Follow-Up Suggestion Chips
                        if (isLastAssistant && !message.isStreaming && message.content.isNotBlank()) {
                            val suggestions = remember(message.content) {
                                val list = mutableListOf<Pair<String, String>>()
                                val cLower = message.content.lowercase()
                                if (cLower.contains("formula") || cLower.contains("```") || cLower.contains("1.") || cLower.contains("step") || message.content.length > 200) {
                                    list.add("📄 Make a file of this" to "Please create a formatted file of this complete content using the write_to_file tool.")
                                }
                                list.add("🔍 Explain in detail" to "Please explain this step-by-step in detail.")
                                list.add("⚡ Key takeaways" to "What are the key takeaways from this?")
                                list.add("🧪 Give examples" to "Can you provide concrete practical examples for this?")
                                list.take(3)
                            }

                            LazyRow(
                                modifier = Modifier.padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(suggestions) { (label, prompt) ->
                                    Surface(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onSendSuggestion(prompt)
                                        },
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                                        border = androidx.compose.foundation.BorderStroke(0.7.dp, ClaudeTerracotta.copy(alpha = 0.35f))
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        else -> {
            // System message styled pill
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                        .border(0.6.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    val isError = message.content.startsWith("⚠️")
                    Icon(
                        if (isError) Icons.Default.Warning else Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = message.content.removePrefix("⚠️").trim(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3
                    )
                }
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
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
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
                    modifier = Modifier.size(15.dp),
                    tint = textColor
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Thinking Process",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .heightIn(min = 24.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(textColor.copy(alpha = 0.45f))
                )
                Spacer(Modifier.width(10.dp))
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(
                        text = thinkingText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            lineHeight = 19.sp,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        ),
                        color = textColor.copy(alpha = 0.9f)
                    )
                }
            }
        }
    }
}

@Composable
fun AgyTerminalExecutionCard(
    toolItems: List<ToolExecutionItem>,
    singleStatus: String?,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onOpenFile: ((String) -> Unit)? = null
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
                            isDark = isDark,
                            onOpenFile = onOpenFile
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
    isDark: Boolean,
    onOpenFile: ((String) -> Unit)? = null
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

            if (!item.targetFile.isNullOrBlank() && onOpenFile != null) {
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = ClaudeTerracotta.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(0.6.dp, ClaudeTerracotta.copy(alpha = 0.4f)),
                    modifier = Modifier.clickable { onOpenFile(item.targetFile) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(Icons.Default.Visibility, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.size(11.dp))
                        Text(
                            text = "View",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                            color = ClaudeTerracotta
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
            }

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
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Circular Logo Avatar matching Assistant messages
        NextAiLogo(
            size = 30.dp,
            showBackground = true
        )

        Spacer(Modifier.width(12.dp))

        val infiniteTransition = rememberInfiniteTransition(label = "dots")
        val offset1 by infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = -4f,
            animationSpec = infiniteRepeatable(
                keyframes { durationMillis = 1000; 0f at 0; -4f at 250; 0f at 500; 0f at 1000 },
                RepeatMode.Restart
            ), label = "o1"
        )
        val offset2 by infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = -4f,
            animationSpec = infiniteRepeatable(
                keyframes { durationMillis = 1000; 0f at 150; -4f at 400; 0f at 650; 0f at 1000 },
                RepeatMode.Restart
            ), label = "o2"
        )
        val offset3 by infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = -4f,
            animationSpec = infiniteRepeatable(
                keyframes { durationMillis = 1000; 0f at 300; -4f at 550; 0f at 800; 0f at 1000 },
                RepeatMode.Restart
            ), label = "o3"
        )
        val textAlpha by infiniteTransition.animateFloat(
            initialValue = 0.45f, targetValue = 0.9f,
            animationSpec = infiniteRepeatable(
                keyframes { durationMillis = 1200; 0.45f at 0; 0.9f at 600; 0.45f at 1200 },
                RepeatMode.Restart
            ), label = "textAlpha"
        )

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .border(0.6.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(offset1, offset2, offset3).forEach { offset ->
                Box(
                    modifier = Modifier
                        .offset(y = offset.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(ClaudeTerracotta)
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Thinking…",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = textAlpha)
            )
        }
    }
}

@Composable
fun StreamingCursorBlink() {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 900
                1f at 0
                1f at 400
                0f at 500
                0f at 900
            },
            repeatMode = RepeatMode.Restart
        ), label = "cursorAlpha"
    )
    Box(
        modifier = Modifier
            .size(width = 2.dp, height = 16.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(ClaudeTerracotta.copy(alpha = alpha))
    )
}


@Composable
fun QuickSlashChipsRow(
    plugins: List<PluginItem>,
    onChipClick: (PluginItem) -> Unit,
    onOpenAllTools: () -> Unit = {}
) {
    val curatedChips = remember {
        listOf(
            Triple("🌐 Search Web", "/browser ", ChatGptBlue),
            Triple("⚡ Deep Think", "/boost ", ChatGptPurple),
            Triple("📋 Plan", "/plan ", ClaudeTerracotta),
            Triple("🎯 Auto Goal", "/goal ", ChatGptEmerald),
            Triple("🧠 Remember", "/remember ", ClaudeTerracottaDark)
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        curatedChips.forEach { (label, commandPrefix, accentColor) ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(0.8.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                    .clickable {
                        val matching = plugins.firstOrNull { it.prefix == commandPrefix.trim() }
                        if (matching != null) {
                            onChipClick(matching)
                        }
                    }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(ClaudeTerracotta.copy(alpha = 0.12f))
                .border(0.8.dp, ClaudeTerracotta.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                .clickable { onOpenAllTools() }
                .padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Tune,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = ClaudeTerracotta
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "All Tools ✦",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = ClaudeTerracotta
            )
        }
    }
}

enum class ActionButtonState { STOP, SEND, MIC }

data class ActiveSlashMode(
    val prefix: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val color: Color
)

@Composable
fun ClaudeFloatingInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit = {},
    onOpenPlugins: () -> Unit,
    onAttachFile: () -> Unit,
    onToggleWebSearch: () -> Unit = {},
    onVoiceInput: () -> Unit = {},
    attachments: List<AttachmentItem> = emptyList(),
    attachment: AttachmentItem? = null,
    onRemoveAttachment: (AttachmentItem) -> Unit = {},
    onAddMoreAttachments: () -> Unit = onAttachFile,
    replyToMessage: Message? = null,
    onCancelReply: () -> Unit = {},
    isConnected: Boolean,
    isLoading: Boolean
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val activeAttachments = if (attachments.isNotEmpty()) attachments else if (attachment != null) listOf(attachment) else emptyList()
    val canSend = (text.isNotBlank() || activeAttachments.isNotEmpty()) && isConnected

    val recognizedModes = remember {
        listOf(
            ActiveSlashMode("/browser", "Web Search", Icons.Default.Language, ChatGptBlue),
            ActiveSlashMode("/boost", "Deep Think", Icons.Default.AutoAwesome, ChatGptPurple),
            ActiveSlashMode("/plan", "Plan Mode", Icons.Default.Assignment, Color(0xFF0284C7)),
            ActiveSlashMode("/goal", "Autonomous Goal", Icons.Default.RocketLaunch, Color(0xFF10B981)),
            ActiveSlashMode("/remember", "Memory", Icons.Default.Psychology, Color(0xFFF59E0B)),
            ActiveSlashMode("/schedule", "Scheduled", Icons.Default.Schedule, Color(0xFF8B5CF6)),
            ActiveSlashMode("/grill-me", "Interview", Icons.Default.QuestionAnswer, ClaudeTerracotta),
            ActiveSlashMode("/teamwork-preview", "Multi-Agent", Icons.Default.Groups, Color(0xFF06B6D4))
        )
    }
    val activeMode = recognizedModes.firstOrNull { text.startsWith(it.prefix) }
    val isWebSearchActive = activeMode?.prefix == "/browser"

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .shadow(3.dp, RoundedCornerShape(26.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(26.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            // Quoted Reply Preview Bar
            AnimatedVisibility(visible = replyToMessage != null) {
                if (replyToMessage != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(26.dp)
                                    .background(ClaudeTerracotta, RoundedCornerShape(2.dp))
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Replying to ${if (replyToMessage.isUser) "You" else "Assistant"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ClaudeTerracotta,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = replyToMessage.content.lines().firstOrNull()?.take(80) ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = onCancelReply,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel reply",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Multi-Attachment Preview Bar (ChatGPT modern carousel / row of attached files/images)
            AnimatedVisibility(visible = activeAttachments.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(activeAttachments, key = { it.uri }) { att ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = androidx.compose.foundation.BorderStroke(
                                0.8.dp,
                                MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier.widthIn(max = 200.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (att.isImage) {
                                    AsyncImage(
                                        model = att.uri,
                                        contentDescription = "Attached image",
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
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

                                Spacer(Modifier.width(8.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = att.name,
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (att.isImage) "Photo" else "Document",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                IconButton(
                                    onClick = { onRemoveAttachment(att) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove attachment",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Surface(
                            onClick = onAddMoreAttachments,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = androidx.compose.foundation.BorderStroke(
                                0.8.dp,
                                MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "Add more files",
                                    tint = ClaudeTerracotta,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Active Slash Mode Badge Pill (Dismissible)
            AnimatedVisibility(
                visible = activeMode != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                if (activeMode != null) {
                    Row(
                        modifier = Modifier
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(activeMode.color.copy(alpha = 0.12f))
                            .border(0.8.dp, activeMode.color.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            activeMode.icon,
                            contentDescription = null,
                            tint = activeMode.color,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = activeMode.label,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                            color = activeMode.color
                        )
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .clickable {
                                    val newText = if (text.startsWith("${activeMode.prefix} ")) {
                                        text.removePrefix("${activeMode.prefix} ")
                                    } else {
                                        text.removePrefix(activeMode.prefix).trimStart()
                                    }
                                    onTextChange(newText)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove mode",
                                tint = activeMode.color.copy(alpha = 0.8f),
                                modifier = Modifier.size(12.dp)
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
                // Plus button (+) for Attachments, Camera & Tools
                IconButton(
                    onClick = onAttachFile,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Add attachment or tool",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(Modifier.width(4.dp))

                // Quick Web Search Toggle Pill
                Surface(
                    onClick = onToggleWebSearch,
                    shape = RoundedCornerShape(16.dp),
                    color = if (isWebSearchActive) ChatGptBlue.copy(alpha = 0.15f) else Color.Transparent,
                    border = androidx.compose.foundation.BorderStroke(
                        0.8.dp,
                        if (isWebSearchActive) ChatGptBlue else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    ),
                    modifier = Modifier.padding(bottom = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Language,
                            contentDescription = "Toggle Web Search",
                            modifier = Modifier.size(14.dp),
                            tint = if (isWebSearchActive) ChatGptBlue else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isWebSearchActive) {
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "Search",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                                color = ChatGptBlue
                            )
                        }
                    }
                }

                Spacer(Modifier.width(4.dp))

                // Multi-line Text input
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 2.dp),
                    placeholder = {
                        Text(
                            if (isConnected) "Message Next AI..." else "Connect in Settings to chat...",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
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

                // Live Word & Token Counter in Composer
                if (text.isNotBlank()) {
                    val words = remember(text) { text.trim().split(Regex("\\s+")).count { it.isNotBlank() } }
                    val estTokens = (words * 1.33).toInt()
                    Text(
                        text = if (words >= 15) "$words w · ~$estTokens t" else "${text.length}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = if (text.length > 4000) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(end = 2.dp, bottom = 8.dp)
                    )
                }

                // Clear (✕) Button if text non-empty
                if (text.isNotBlank()) {
                    IconButton(
                        onClick = { onTextChange("") },
                        modifier = Modifier.size(32.dp).padding(bottom = 2.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Clear input",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(Modifier.width(2.dp))

                // Modern ChatGPT Dynamic Send / Stop / Mic Button with Smooth AnimatedContent Transitions
                val actionButtonState = when {
                    isLoading -> ActionButtonState.STOP
                    canSend -> ActionButtonState.SEND
                    else -> ActionButtonState.MIC
                }

                AnimatedContent(
                    targetState = actionButtonState,
                    transitionSpec = {
                        (scaleIn(animationSpec = tween(180)) + fadeIn(animationSpec = tween(180)))
                            .togetherWith(scaleOut(animationSpec = tween(150)) + fadeOut(animationSpec = tween(150)))
                    },
                    label = "composerActionButton"
                ) { state ->
                    when (state) {
                        ActionButtonState.STOP -> {
                            FilledIconButton(
                                onClick = onStop,
                                modifier = Modifier.size(36.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.onSurface,
                                    contentColor = MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Icon(
                                    Icons.Default.Stop,
                                    contentDescription = "Stop Generating",
                                    tint = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        ActionButtonState.SEND -> {
                            FilledIconButton(
                                onClick = onSend,
                                modifier = Modifier.size(36.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = if (isDark) Color.White else Color(0xFF0D0D0D),
                                    contentColor = if (isDark) Color.Black else Color.White
                                )
                            ) {
                                Icon(
                                    Icons.Default.ArrowUpward,
                                    contentDescription = "Send",
                                    tint = if (isDark) Color.Black else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        ActionButtonState.MIC -> {
                            IconButton(
                                onClick = onVoiceInput,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Icon(
                                    Icons.Default.Mic,
                                    contentDescription = "Voice dictation",
                                    tint = ClaudeTerracotta,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyChatGreeting(
    plugins: List<PluginItem>,
    onPromptCardClick: (String) -> Unit,
    selectedModelName: String = "Next AI",
    memoriesCount: Int = 0,
    hasCustomInstructions: Boolean = false,
    onOpenMemorySheet: () -> Unit = {},
    onOpenCustomInstructions: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Next AI Masterpiece Emblem
        NextAiLogo(
            size = 72.dp,
            showBackground = true
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "What can I help with?",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp,
                fontSize = 25.sp
            ),
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(Modifier.height(8.dp))

        // Context Status Badges (Model, Memories, Custom Instructions)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = androidx.compose.foundation.BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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

            if (memoriesCount > 0) {
                Surface(
                    onClick = onOpenMemorySheet,
                    shape = RoundedCornerShape(12.dp),
                    color = ClaudeTerracotta.copy(alpha = 0.12f),
                    border = androidx.compose.foundation.BorderStroke(0.6.dp, ClaudeTerracotta.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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
                Surface(
                    onClick = onOpenCustomInstructions,
                    shape = RoundedCornerShape(12.dp),
                    color = ChatGptPurple.copy(alpha = 0.12f),
                    border = androidx.compose.foundation.BorderStroke(0.6.dp, ChatGptPurple.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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

        Spacer(Modifier.height(28.dp))

        val isTablet = LocalConfiguration.current.screenWidthDp >= 600

        val starterPrompts = listOf(
            Triple("🌐 Real-time Web Search", "Search latest docs, news and live internet facts", "/browser search latest AI news"),
            Triple("💻 Write & Debug Code", "Analyze codebase architecture, find bugs and optimize", "/boost inspect code architecture and suggest improvements"),
            Triple("📋 Phased Roadmap", "Design step-by-step implementation milestones", "/plan create phased roadmap for new features"),
            Triple("🎯 Autonomous Goal", "Continuous agent loop until objective is fully solved", "/goal review test coverage and implement missing tests")
        )

        if (isTablet) {
            // Modern 2x2 Action Starter Grid for Tablets
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                starterPrompts.chunked(2).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowItems.forEach { (title, subtitle, prompt) ->
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { onPromptCardClick(prompt) },
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = androidx.compose.foundation.BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Icon(
                                        Icons.Default.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = ClaudeTerracotta
                                    )
                                }
                            }
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
                starterPrompts.forEach { (title, subtitle, prompt) ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onPromptCardClick(prompt) },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                Icons.Default.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = ClaudeTerracotta
                            )
                        }
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
    onOpenSettings: () -> Unit,
    onOpenMemory: () -> Unit = {},
    onOpenCustomInstructions: () -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredConversations = remember(conversations, searchQuery) {
        if (searchQuery.isBlank()) conversations
        else conversations.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    ModalDrawerSheet(
        modifier = Modifier.width(310.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
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

            Spacer(Modifier.height(12.dp))

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

            Spacer(Modifier.height(12.dp))

            // Real-time Chat Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        "Search chats...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Search",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedBorderColor = ClaudeTerracotta,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )

            Spacer(Modifier.height(12.dp))

            // Group conversations by date (ChatGPT 5-tier grouping)
            val now = System.currentTimeMillis()
            val todayStart = now - (now % 86_400_000)
            val yesterdayStart = todayStart - 86_400_000
            val sevenDaysAgo = todayStart - (7 * 86_400_000)
            val thirtyDaysAgo = todayStart - (30 * 86_400_000)

            val grouped = filteredConversations.groupBy { conv ->
                when {
                    conv.updatedAt >= todayStart -> "Today"
                    conv.updatedAt >= yesterdayStart -> "Yesterday"
                    conv.updatedAt >= sevenDaysAgo -> "Previous 7 Days"
                    conv.updatedAt >= thirtyDaysAgo -> "Previous 30 Days"
                    else -> "Older"
                }
            }
            val groupOrder = listOf("Today", "Yesterday", "Previous 7 Days", "Previous 30 Days", "Older")

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                groupOrder.forEach { groupName ->
                    val groupConvs = grouped[groupName] ?: return@forEach
                    item(key = "header_$groupName") {
                        Text(
                            text = groupName,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)
                        )
                    }
                    items(groupConvs, key = { it.id }) { conv ->
                        val isActive = conv.id == activeId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isActive) ClaudeTerracotta.copy(alpha = 0.12f)
                                    else Color.Transparent
                                )
                                .clickable { onSelectConversation(conv.id) }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(if (isActive) 8.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isActive) ClaudeTerracotta
                                        else MaterialTheme.colorScheme.outlineVariant
                                    )
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = conv.title.ifBlank { "Untitled Chat" },
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (isActive) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = formatRelativeTime(conv.updatedAt),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
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
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                thickness = 0.8.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // Personalization & Memory Shortcut
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenMemory)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = null,
                    tint = ClaudeTerracotta,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Memory & Personalization",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Custom Instructions Shortcut
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenCustomInstructions)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = ChatGptPurple,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Custom Instructions",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Settings Shortcut
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenSettings)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Settings & Bridge",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
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
    reasoningEffort: String = "high",
    onSelectEffort: (String) -> Unit = {},
    onSelectModel: (AiModel) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
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
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
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
                        text = "Model & Intelligence",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Choose model & configure reasoning depth",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Reasoning Effort Segmented Card (ChatGPT / Claude 3.7 Thinking Control)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = androidx.compose.foundation.BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Psychology,
                                contentDescription = null,
                                tint = ClaudeTerracotta,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Reasoning Depth",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (!selectedModel.supportsEffort) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = ClaudeTerracotta.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "Model Locked",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                    color = ClaudeTerracotta,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    val effortOptions = listOf(
                        Triple("low", "Fast", "🚀"),
                        Triple("medium", "Balanced", "⚡"),
                        Triple("high", "Deep", "🧠")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        effortOptions.forEach { (effortKey, label, emoji) ->
                            val isEffortSelected = reasoningEffort.equals(effortKey, ignoreCase = true)
                            Surface(
                                onClick = {
                                    if (selectedModel.supportsEffort) {
                                        onSelectEffort(effortKey)
                                    }
                                },
                                enabled = selectedModel.supportsEffort,
                                shape = RoundedCornerShape(10.dp),
                                color = if (isEffortSelected) ClaudeTerracotta else MaterialTheme.colorScheme.surface,
                                border = androidx.compose.foundation.BorderStroke(
                                    0.8.dp,
                                    if (isEffortSelected) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .then(if (selectedModel.supportsEffort) Modifier else Modifier.padding(0.dp))
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "$emoji $label",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = if (isEffortSelected) FontWeight.Bold else FontWeight.Medium
                                        ),
                                        color = if (isEffortSelected) Color.White else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = when (effortKey) {
                                            "low" -> "Speed"
                                            "medium" -> "Normal"
                                            else -> "Maximum"
                                        },
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                        color = if (isEffortSelected) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
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
                            color = if (isSelected) ClaudeTerracotta.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant,
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
                                // Left Icon / Spark
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (isSelected) ClaudeTerracotta.copy(alpha = 0.18f)
                                            else MaterialTheme.colorScheme.surface
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val iconText = when {
                                        model.provider == "Google" -> "✦"
                                        model.provider == "Anthropic" -> "✻"
                                        else -> "⚡"
                                    }
                                    Text(
                                        text = iconText,
                                        color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp
                                    )
                                }

                                Spacer(Modifier.width(12.dp))

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
                                            text = if (model.supportsEffort) "Reasoning effort: Low · Medium · High" else "Thinking: Fixed to CLI default",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = if (model.supportsEffort) MaterialTheme.colorScheme.outline else ClaudeTerracotta
                                        )
                                    }
                                }

                                Spacer(Modifier.width(8.dp))

                                if (isSelected) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Selected model",
                                        tint = ClaudeTerracotta,
                                        modifier = Modifier.size(22.dp)
                                    )
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
}

/**
 * Modern Full-Screen File Viewer & Downloader Modal.
 * Opens files created by Antigravity CLI agents (derivations, markdown notes, code, etc.)
 * Allows user to view with rich Markdown/LaTeX typography, copy, share, and save to phone Downloads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerBottomSheet(
    fileData: FileViewerData,
    onClose: () -> Unit,
    onSaveToPhone: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ClaudeTerracotta.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = fileData.filename,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val sizeStr = if (fileData.size > 0) {
                            val kb = fileData.size / 1024.0
                            String.format(java.util.Locale.US, "%.1f KB", kb)
                        } else if (fileData.content.isNotBlank()) {
                            val kb = fileData.content.toByteArray(Charsets.UTF_8).size / 1024.0
                            String.format(java.util.Locale.US, "%.1f KB", kb)
                        } else ""
                        Text(
                            text = listOfNotNull(sizeStr.takeIf { it.isNotBlank() }, fileData.path.takeIf { it.isNotBlank() }).joinToString(" • "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(Modifier.height(10.dp))

            // Quick Actions Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSaveToPhone,
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save to Phone", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                }

                OutlinedButton(
                    onClick = onCopy,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(0.7f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Copy", style = MaterialTheme.typography.labelMedium)
                }

                OutlinedButton(
                    onClick = onShare,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(0.7f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Share", style = MaterialTheme.typography.labelMedium)
                }
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(thickness = 0.8.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))

            // File Content Display Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when {
                    fileData.isLoading -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = ClaudeTerracotta, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Loading file from Colab server...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    fileData.error != null -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = fileData.error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    else -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 24.dp)
                        ) {
                            SelectionContainer {
                                MarkdownContent(
                                    text = fileData.content,
                                    textColor = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Modern Session Artifacts & Files Bottom Sheet.
 * Displays all files generated or edited in the current chat session
 * (physics formula sheets, markdown notes, source code files, analysis reports, etc.)
 * with quick direct viewing and phone Downloads export.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionArtifactsBottomSheet(
    sessionFiles: List<String>,
    onClose: () -> Unit,
    onOpenFile: (String) -> Unit,
    onCopyPath: (String) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .padding(horizontal = 16.dp, vertical = 6.dp)
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
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ClaudeTerracotta.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Session Artifacts",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = ClaudeTerracotta.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "${sessionFiles.size} files",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ClaudeTerracotta,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "Files & notes generated by Next AI in this chat",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(Modifier.height(14.dp))

            if (sessionFiles.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "No Artifacts Yet",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "When you ask Next AI to create scripts, derivations, notes, or physics formula sheets, they will appear here for instant preview and download.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(sessionFiles) { filePath ->
                        val fileName = filePath.substringAfterLast('/')
                        val ext = fileName.substringAfterLast('.', "")
                        val icon = when (ext.lowercase()) {
                            "md", "txt" -> Icons.Default.Description
                            "py", "kt", "js", "html", "sh" -> Icons.Default.Code
                            else -> Icons.Default.Description
                        }
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = androidx.compose.foundation.BorderStroke(
                                0.8.dp,
                                MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(ClaudeTerracotta.copy(alpha = 0.12f)),
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
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = fileName,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = filePath,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                IconButton(
                                    onClick = { onCopyPath(filePath) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy Path",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Button(
                                    onClick = { onOpenFile(filePath) },
                                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("Open", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
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
}
