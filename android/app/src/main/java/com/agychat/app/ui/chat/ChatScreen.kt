package com.agychat.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asImageBitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
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

fun queryFileSize(context: Context, uri: Uri): String {
    try {
        if (uri.scheme == "file") {
            val f = File(uri.path ?: "")
            if (f.exists()) return formatBytes(f.length())
        }
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex != -1 && cursor.moveToFirst()) {
                val size = cursor.getLong(sizeIndex)
                if (size > 0) return formatBytes(size)
            }
        }
    } catch (_: Exception) {}
    return ""
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return ""
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.1f MB", mb)
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
    val connectionLatency by viewModel.connectionLatencyMs.collectAsState()
    val hostEnvironment by viewModel.hostEnvironment.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var showPluginBottomSheet by remember { mutableStateOf(false) }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var showEffortMenu by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showMemorySheet by remember { mutableStateOf(false) }
    var showCustomInstructionsSheet by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showTextSizeSheet by remember { mutableStateOf(false) }
    var showEnvironmentSheet by remember { mutableStateOf(false) }
    val enabledMemoriesCount by viewModel.enabledMemoriesCount.collectAsState(initial = 0)
    val isTemporaryChat by viewModel.isTemporaryChat.collectAsState()
    val currentCwd by viewModel.currentCwd.collectAsState()
    val activeTasksCount by viewModel.activeTasksCount.collectAsState()
    val customInstructions by viewModel.customInstructions.collectAsState()
    val personalization by viewModel.personalization.collectAsState()
    val chatTextSizeScale by viewModel.chatTextSizeScale.collectAsState()
    val showFollowupSuggestions by viewModel.showFollowupSuggestions.collectAsState()
    val showStreamingCursor by viewModel.showStreamingCursor.collectAsState()
    val compactMessageDensity by viewModel.compactMessageDensity.collectAsState()
    val showMemoryActivityBadges by viewModel.showMemoryActivityBadges.collectAsState()
    val replyToMessage by viewModel.replyToMessage.collectAsState()
    val sessionFiles by viewModel.sessionFiles.collectAsState()
    var showArtifactsSheet by remember { mutableStateOf(false) }
    var speakingMessageId by remember { mutableStateOf<String?>(null) }
    var tts: TextToSpeech? by remember { mutableStateOf(null) }
    var editingMessage by remember { mutableStateOf<Message?>(null) }
    var editingMessageText by remember { mutableStateOf("") }

    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val uriHandler = LocalUriHandler.current

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

    // Android Runtime Storage Permission launcher and state
    var showStoragePermissionDialog by remember { mutableStateOf(false) }
    var pendingStorageAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        val anyGranted = permissionsMap.values.any { it }
        if (anyGranted) {
            Toast.makeText(context, "Storage permission granted", Toast.LENGTH_SHORT).show()
            pendingStorageAction?.invoke()
        } else {
            Toast.makeText(
                context,
                "Storage permission denied. Files are saved in Next AI offline storage.",
                Toast.LENGTH_LONG
            ).show()
        }
        pendingStorageAction = null
        showStoragePermissionDialog = false
    }

    val showPinnedOnly by viewModel.showPinnedOnly.collectAsState()
    var isInChatSearchOpen by remember { mutableStateOf(false) }
    var inChatSearchQuery by remember { mutableStateOf("") }
    var currentSearchMatchIndex by remember { mutableIntStateOf(0) }

    val pinnedCount = remember(messages) { messages.count { it.isPinned } }
    val displayedMessages = remember(messages, showPinnedOnly) {
        if (showPinnedOnly) messages.filter { it.isPinned } else messages
    }

    // Determine if user is currently at the bottom of the conversation list
    var isAutoFollowActive by remember { mutableStateOf(true) }

    val isLastItemVisible by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) return@derivedStateOf true
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            lastVisible.index >= totalItems - 1
        }
    }

    var unreadNewMessagesCount by remember { mutableStateOf(0) }
    var previousMessagesCount by remember { mutableStateOf(displayedMessages.size) }

    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()

    // Detect user manual scroll gesture: only pause auto-follow when user physically drags upward
    LaunchedEffect(isUserDragging, listState.isScrollInProgress) {
        if (isUserDragging && listState.isScrollInProgress) {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
            if (lastVisible != null && totalItems > 0) {
                if (lastVisible.index < totalItems - 1) {
                    isAutoFollowActive = false
                } else if (lastVisible.index >= totalItems - 1 && (lastVisible.offset + lastVisible.size <= layoutInfo.viewportEndOffset + 80)) {
                    isAutoFollowActive = true
                    unreadNewMessagesCount = 0
                }
            }
        } else if (!isUserDragging) {
            // User finished physical drag: if settled back at bottom, resume auto-follow
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
            if (lastVisible != null && totalItems > 0 && lastVisible.index >= totalItems - 1 &&
                (lastVisible.offset + lastVisible.size <= layoutInfo.viewportEndOffset + 80)
            ) {
                isAutoFollowActive = true
                unreadNewMessagesCount = 0
            }
        }
    }

    // Show "Scroll to Bottom" FAB only when user has scrolled up away from bottom and auto-follow is paused
    val showScrollToBottom by remember {
        derivedStateOf {
            !isAutoFollowActive && displayedMessages.size > 1
        }
    }

    LaunchedEffect(isAutoFollowActive) {
        if (isAutoFollowActive) {
            unreadNewMessagesCount = 0
        }
    }

    // Smart auto-scroll: pins to bottom when auto-follow is active, badges new messages when scrolled away
    LaunchedEffect(displayedMessages.size) {
        val countDiff = displayedMessages.size - previousMessagesCount
        previousMessagesCount = displayedMessages.size

        if (displayedMessages.isNotEmpty()) {
            if (isAutoFollowActive) {
                val count = listState.layoutInfo.totalItemsCount
                if (count > 0) {
                    listState.scrollToItem(count - 1, 100000)
                }
                unreadNewMessagesCount = 0
            } else if (countDiff > 0) {
                unreadNewMessagesCount += countDiff
            }
        }
    }

    // Anchor scroll to bottom when soft keyboard opens
    val imeBottom = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && isAutoFollowActive && displayedMessages.isNotEmpty()) {
            kotlinx.coroutines.delay(60)
            val count = listState.layoutInfo.totalItemsCount
            if (count > 0) {
                listState.scrollToItem(count - 1, 100000)
            }
        }
    }

    // Fluid follow-scroll during reasoning and streaming output (pinned to bottom like ChatGPT, Claude, and Gemini)
    val lastStreamingMsg = displayedMessages.lastOrNull()
    val isStreamingActive = lastStreamingMsg?.isStreaming == true
    val streamContentLength = (lastStreamingMsg?.content?.length ?: 0) + (lastStreamingMsg?.thinking?.length ?: 0)

    LaunchedEffect(isStreamingActive) {
        if (isStreamingActive) {
            isAutoFollowActive = true
        }
    }

    LaunchedEffect(streamContentLength, isStreamingActive) {
        if (isStreamingActive && isAutoFollowActive && displayedMessages.isNotEmpty()) {
            val count = listState.layoutInfo.totalItemsCount
            if (count > 0) {
                listState.scrollToItem(count - 1, 100000)
            }
        }
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
                                        val pulseTransition = rememberInfiniteTransition(label = "modelPulse")
                                        val pulseAlpha by pulseTransition.animateFloat(
                                            initialValue = 0.35f,
                                            targetValue = 1.0f,
                                            animationSpec = infiniteRepeatable(
                                                animation = tween(650, easing = LinearEasing),
                                                repeatMode = RepeatMode.Reverse
                                            ),
                                            label = "dotPulse"
                                        )
                                        val statusColor = when (connectionState) {
                                            ConnectionState.CONNECTED -> Color(0xFF10A37F)
                                            ConnectionState.CONNECTING -> Color(0xFFF59E0B)
                                            ConnectionState.ERROR -> Color(0xFFEF5350)
                                            ConnectionState.DISCONNECTED -> Color(0xFF9E9E9E)
                                        }
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(statusColor.copy(alpha = if (isLoading) pulseAlpha else 1f))
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
                                        val effortSuffix = if (selectedModel.supportsEffort && reasoningEffort.isNotBlank()) " · ${reasoningEffort.replaceFirstChar { it.uppercase() }}" else ""
                                        Text(
                                            text = "$shortModelName$effortSuffix",
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 13.5.sp),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (connectionState == ConnectionState.CONNECTED && connectionLatency != null) {
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = "${connectionLatency}ms",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                                color = if (connectionLatency!! < 120) Color(0xFF10A37F) else Color(0xFFF59E0B)
                                            )
                                        }
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
                                                Icon(Icons.Default.FormatSize, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.size(18.dp))
                                            },
                                            text = {
                                                Column {
                                                    Text("Text Size & Display", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                                    Text("${(chatTextSizeScale * 100).toInt()}% • Tap to customize", style = MaterialTheme.typography.labelSmall, color = ClaudeTerracotta)
                                                }
                                            },
                                            onClick = {
                                                showMoreMenu = false
                                                showTextSizeSheet = true
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

                        // Discrete, Elegant Connection Status Banner
                        AnimatedVisibility(
                            visible = connectionState != ConnectionState.CONNECTED,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            val isError = connectionState == ConnectionState.ERROR || connectionState == ConnectionState.DISCONNECTED
                            val bannerBg = if (isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f)
                                           else ClaudeTerracotta.copy(alpha = 0.12f)
                            val bannerTextColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else ClaudeTerracotta
                            val bannerBorderColor = if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.25f) else ClaudeTerracotta.copy(alpha = 0.25f)

                            Surface(
                                color = bannerBg,
                                border = androidx.compose.foundation.BorderStroke(0.6.dp, bannerBorderColor),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        if (connectionState == ConnectionState.CONNECTING) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(13.dp),
                                                strokeWidth = 2.dp,
                                                color = bannerTextColor
                                            )
                                        } else {
                                            Icon(
                                                Icons.Default.CloudOff,
                                                contentDescription = null,
                                                tint = bannerTextColor,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                        Spacer(Modifier.width(9.dp))
                                        val statusText = when (connectionState) {
                                            ConnectionState.CONNECTING -> "Connecting to Colab Bridge…"
                                            ConnectionState.ERROR -> "Disconnected from Colab Bridge"
                                            ConnectionState.DISCONNECTED -> "Bridge Offline"
                                            ConnectionState.CONNECTED -> ""
                                        }
                                        Text(
                                            text = statusText,
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                                            color = bannerTextColor
                                        )
                                    }

                                    if (isError) {
                                        Surface(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                viewModel.reconnect()
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            color = bannerTextColor.copy(alpha = 0.15f)
                                        ) {
                                            Text(
                                                text = "Reconnect",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                                                color = bannerTextColor,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                            )
                                        }
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

                        // Interactive Terminal Workspace & Model Connection Bar
                        AnimatedVisibility(
                            visible = connectionState == ConnectionState.CONNECTED && !isTemporaryChat,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Surface(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.pingBridge()
                                    viewModel.requestEnvironmentRefresh()
                                    showEnvironmentSheet = true
                                },
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = androidx.compose.foundation.BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f, fill = false)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(ChatGptEmerald)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Icon(
                                            Icons.Default.Terminal,
                                            contentDescription = "Workspace CWD",
                                            tint = ChatGptEmerald,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        val cwdDisplay = remember(currentCwd) {
                                            if (currentCwd.startsWith("/content/Next-Ai")) "Next-Ai"
                                            else if (currentCwd == "/content") "content"
                                            else currentCwd.substringAfterLast('/').ifBlank { currentCwd }
                                        }
                                        Text(
                                            text = cwdDisplay,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (hostEnvironment.gitBranch != null) {
                                            Spacer(Modifier.width(5.dp))
                                            Text(
                                                text = "(${hostEnvironment.gitBranch})",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 10.sp
                                                ),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                maxLines = 1
                                            )
                                        }
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        if (hostEnvironment.skills.isNotEmpty()) {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = ChatGptPurple.copy(alpha = 0.12f)
                                            ) {
                                                Text(
                                                    text = "High-Thinking",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
                                                    color = ChatGptPurple,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }

                                        if (connectionLatency != null) {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = (if (connectionLatency!! < 100) ChatGptEmerald else Color(0xFFF59E0B)).copy(alpha = 0.12f)
                                            ) {
                                                Text(
                                                    text = "${connectionLatency}ms",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
                                                    color = if (connectionLatency!! < 100) ChatGptEmerald else Color(0xFFF59E0B),
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                )
                                            }
                                        }

                                        Icon(
                                            Icons.Default.Tune,
                                            contentDescription = "Environment Details",
                                            modifier = Modifier.size(13.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
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

                        // Subtle Status Strip (only when actively performing action while connected)
                        if (!currentStatus.isNullOrBlank() && connectionState == ConnectionState.CONNECTED) {
                            Surface(
                                color = ClaudeTerracotta.copy(alpha = 0.08f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = currentStatus ?: "",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                        color = ClaudeTerracotta,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
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
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (connectionState != ConnectionState.CONNECTED) {
                                Toast.makeText(context, "Not connected. Reconnecting to Colab Bridge...", Toast.LENGTH_SHORT).show()
                                viewModel.reconnect()
                                return@ClaudeFloatingInputBar
                            }
                            isAutoFollowActive = true
                            viewModel.sendMessage(inputText)
                            inputText = ""
                        },
                        onStop = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
            val currentDensity = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    density = currentDensity.density,
                    fontScale = currentDensity.fontScale * chatTextSizeScale
                )
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
                                isAutoFollowActive = true
                                viewModel.sendMessage(prompt)
                            },
                            selectedModelName = selectedModel.name,
                            memoriesCount = enabledMemoriesCount,
                            hasCustomInstructions = customInstructions.isEnabled && (customInstructions.aboutUser.isNotBlank() || customInstructions.responsePreferences.isNotBlank()),
                            userName = personalization.name,
                            userOccupation = personalization.occupation,
                            depthLevel = personalization.depthLevel,
                            showMemoryBadge = showMemoryActivityBadges,
                            hostEnvironment = hostEnvironment,
                            onOpenEnvironmentSheet = { showEnvironmentSheet = true },
                            onOpenMemorySheet = { showMemorySheet = true },
                            onOpenCustomInstructions = { showCustomInstructionsSheet = true }
                        )
                    }
                } else {
                    val lastAssistantId = remember(displayedMessages) { displayedMessages.lastOrNull { it.role == "assistant" }?.id }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(if (compactMessageDensity) 8.dp else 16.dp),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 28.dp)
                    ) {
                        items(displayedMessages, key = { it.id }) { msg ->
                            val isLastAssistant = msg.id == lastAssistantId
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
                                showFollowupSuggestions = showFollowupSuggestions,
                                showStreamingCursor = showStreamingCursor,
                                showMemoryActivityBadges = showMemoryActivityBadges,
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
                                    editingMessage = msg
                                    editingMessageText = text
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
                                onOpenFile = { target ->
                                    val trimmed = target.trim()
                                    if ((trimmed.startsWith("http://") || trimmed.startsWith("https://")) && !trimmed.contains("/api/file")) {
                                        try {
                                            uriHandler.openUri(trimmed)
                                        } catch (t: Throwable) {
                                            viewModel.fetchAndOpenFile(trimmed)
                                        }
                                    } else {
                                        viewModel.fetchAndOpenFile(trimmed)
                                    }
                                },
                                onReply = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.setReplyToMessage(msg)
                                },
                                onSendSuggestion = { prompt ->
                                    isAutoFollowActive = true
                                    viewModel.sendMessage(prompt)
                                },
                                onSwitchBranch = { branchGroupId, branchIndex ->
                                    viewModel.switchMessageBranch(branchGroupId, branchIndex)
                                },
                                onContinueGenerating = {
                                    isAutoFollowActive = true
                                    viewModel.continueGenerating()
                                }
                            )
                        }

                        if (isLoading && messages.lastOrNull()?.isStreaming != true) {
                            item(key = "typing_bubble") {
                                ClaudeTypingBubble()
                            }
                        }

                        // Bottom anchor item for stable, bottom-aligned scrolling during streaming
                        item(key = "bottom_anchor") {
                            Spacer(Modifier.height(4.dp))
                        }
                    }

                    // Floating "Scroll to Bottom" button with unread count pill badge
                    AnimatedVisibility(
                        visible = showScrollToBottom,
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut(),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 12.dp)
                    ) {
                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                unreadNewMessagesCount = 0
                                isAutoFollowActive = true
                                scope.launch {
                                    val count = listState.layoutInfo.totalItemsCount
                                    if (count > 0) {
                                        listState.animateScrollToItem(count - 1, 100000)
                                    }
                                }
                            },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 6.dp,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        ) {
                            Row(
                                modifier = Modifier.padding(
                                    horizontal = if (unreadNewMessagesCount > 0) 14.dp else 10.dp,
                                    vertical = 9.dp
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Scroll to bottom",
                                    modifier = Modifier.size(18.dp),
                                    tint = ClaudeTerracotta
                                )
                                if (unreadNewMessagesCount > 0) {
                                    Text(
                                        text = "$unreadNewMessagesCount new message${if (unreadNewMessagesCount > 1) "s" else ""}",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp),
                                        color = ClaudeTerracotta
                                    )
                                }
                            }
                        }
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
                            onPin = { id ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggleConversationPinned(id)
                            },
                            onRename = { id, newTitle ->
                                viewModel.renameConversation(id, newTitle)
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
                    onPin = { id ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.toggleConversationPinned(id)
                    },
                    onRename = { id, newTitle ->
                        viewModel.renameConversation(id, newTitle)
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
                            if (!com.agychat.app.data.local.StoragePermissions.hasReadPermission(context)) {
                                pendingStorageAction = {
                                    imagePickerLauncher.launch("image/*")
                                }
                                showStoragePermissionDialog = true
                            } else {
                                imagePickerLauncher.launch("image/*")
                            }
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
                            if (!com.agychat.app.data.local.StoragePermissions.hasReadPermission(context)) {
                                pendingStorageAction = {
                                    filePickerLauncher.launch("*/*")
                                }
                                showStoragePermissionDialog = true
                            } else {
                                filePickerLauncher.launch("*/*")
                            }
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

    if (showTextSizeSheet) {
        ChatTextSizeSheet(
            currentScale = chatTextSizeScale,
            compactDensity = compactMessageDensity,
            onScaleChange = { viewModel.setChatTextSizeScale(it) },
            onCompactDensityToggle = { viewModel.setCompactMessageDensity(!compactMessageDensity) },
            onDismiss = { showTextSizeSheet = false }
        )
    }

    if (showEnvironmentSheet) {
        EnvironmentDetailsSheet(
            hostEnvironment = hostEnvironment,
            selectedModel = selectedModel,
            reasoningEffort = reasoningEffort,
            serverUrl = viewModel.serverUrl.collectAsState().value,
            connectionLatency = connectionLatency,
            connectionState = connectionState,
            currentCwd = currentCwd,
            onPing = { viewModel.pingBridge() },
            onRefresh = { viewModel.requestEnvironmentRefresh() },
            onSwitchCwd = { newCwd -> viewModel.updateCwd(newCwd) },
            onDismiss = { showEnvironmentSheet = false }
        )
    }

    if (editingMessage != null) {
        AlertDialog(
            onDismissRequest = { editingMessage = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        tint = ClaudeTerracotta,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Edit Message",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Editing this turn will branch the conversation and regenerate the AI response from this point onward.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editingMessageText,
                        onValueChange = { editingMessageText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 220.dp),
                        shape = RoundedCornerShape(12.dp),
                        placeholder = { Text("Edit prompt...") }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = editingMessage
                        val text = editingMessageText.trim()
                        editingMessage = null
                        if (target != null && text.isNotBlank()) {
                            viewModel.editAndResendMessage(target.id, text)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Save & Submit")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { editingMessage = null }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    val activeFileViewer by viewModel.activeFileViewer.collectAsState()
    if (activeFileViewer != null) {
        val fileData = activeFileViewer!!
        FileViewerBottomSheet(
            fileData = fileData,
            onClose = { viewModel.closeFileViewer() },
            onRetry = { viewModel.fetchAndOpenFile(fileData.path) },
            onSaveToPhone = {
                val hasPerm = com.agychat.app.data.local.StoragePermissions.hasWritePermission(context)
                if (!hasPerm) {
                    pendingStorageAction = {
                        viewModel.saveActiveFileToPhone { success, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                    showStoragePermissionDialog = true
                } else {
                    viewModel.saveActiveFileToPhone { success, msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onOpenExternal = {
                viewModel.openActiveFileInExternalApp(context) { success, msg ->
                    if (!success) {
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onCopy = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(fileData.filename, fileData.content))
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            },
            onShare = {
                try {
                    val shareIntent = if (fileData.isBinary && fileData.localDiskFile != null && fileData.localDiskFile.exists()) {
                        val uri = viewModel.localFileManager.getUriForFile(fileData.localDiskFile)
                        Intent(Intent.ACTION_SEND).apply {
                            type = fileData.mimeType.ifBlank { "application/octet-stream" }
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    } else {
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, fileData.filename)
                            putExtra(Intent.EXTRA_TEXT, fileData.content)
                        }
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share ${fileData.filename}"))
                } catch (t: Throwable) {
                    Toast.makeText(context, "Share failed: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showStoragePermissionDialog) {
        StoragePermissionDialog(
            onDismiss = {
                showStoragePermissionDialog = false
                pendingStorageAction = null
            },
            onGrant = {
                storagePermissionLauncher.launch(com.agychat.app.data.local.StoragePermissions.getPermissions())
            },
            onOpenSettings = {
                showStoragePermissionDialog = false
                com.agychat.app.data.local.StoragePermissions.openAppSettings(context)
                pendingStorageAction = null
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
            },
            onDeleteFiles = { paths ->
                viewModel.deleteArtifactFiles(paths) { count ->
                    val msg = if (count == 1) "Deleted 1 file" else "Deleted $count files"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            },
            onDownloadFiles = { paths ->
                val hasPerm = com.agychat.app.data.local.StoragePermissions.hasWritePermission(context)
                if (!hasPerm) {
                    pendingStorageAction = {
                        viewModel.downloadArtifactFiles(paths) { _, _, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                    showStoragePermissionDialog = true
                } else {
                    viewModel.downloadArtifactFiles(paths) { _, _, msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
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
    showFollowupSuggestions: Boolean = true,
    showStreamingCursor: Boolean = true,
    showMemoryActivityBadges: Boolean = true,
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
    onSendSuggestion: (String) -> Unit = {},
    onSwitchBranch: (String, Int) -> Unit = { _, _ -> },
    onContinueGenerating: () -> Unit = {}
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
            val isDark = MaterialTheme.colorScheme.background.red < 0.5f
            val userBubbleShape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.End
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(min = 48.dp, max = 580.dp)
                        .shadow(
                            elevation = 2.5.dp,
                            shape = userBubbleShape,
                            ambientColor = if (isDark) Color.Black.copy(alpha = 0.5f) else Color(0x18000000),
                            spotColor = if (isDark) Color.Black.copy(alpha = 0.4f) else Color(0x10000000)
                        )
                        .clip(userBubbleShape)
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                colors = if (isDark)
                                    listOf(UserBubbleDarkBg1, UserBubbleDarkBg2)
                                else
                                    listOf(UserBubbleLightBg1, UserBubbleLightBg2),
                                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                                end = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                            )
                        )
                        .then(
                            if (isSearchMatch)
                                Modifier.border(2.dp, ClaudeTerracotta, userBubbleShape)
                            else
                                Modifier.border(
                                    width = 1.dp,
                                    color = if (isDark) UserBubbleDarkBorder else UserBubbleLightBorder,
                                    shape = userBubbleShape
                                )
                        )
                        .padding(horizontal = 18.dp, vertical = 13.dp)
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
                                                                .clip(RoundedCornerShape(12.dp))
                                                                .clickable { onOpenFile(img.uri) },
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
                                                    .clickable { onOpenFile(file.uri) }
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
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = file.name,
                                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = "Tap to view file",
                                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                    )
                                                }
                                                Icon(
                                                    Icons.Default.OpenInNew,
                                                    contentDescription = "Open file",
                                                    tint = ClaudeTerracotta,
                                                    modifier = Modifier.size(14.dp)
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

                // Interactive Quick Actions for User Message
                Row(
                    modifier = Modifier.padding(top = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Branch Navigation: < 1 / 2 >
                    if (message.totalBranches > 1) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            border = androidx.compose.foundation.BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(1.dp),
                                modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        if (message.branchIndex > 0) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onSwitchBranch(message.parentMessageId ?: message.id, message.branchIndex - 1)
                                        }
                                    },
                                    enabled = message.branchIndex > 0,
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ChevronLeft,
                                        contentDescription = "Previous edit",
                                        modifier = Modifier.size(14.dp),
                                        tint = if (message.branchIndex > 0) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                    )
                                }

                                Text(
                                    text = "${message.branchIndex + 1}/${message.totalBranches}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                IconButton(
                                    onClick = {
                                        if (message.branchIndex < message.totalBranches - 1) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onSwitchBranch(message.parentMessageId ?: message.id, message.branchIndex + 1)
                                        }
                                    },
                                    enabled = message.branchIndex < message.totalBranches - 1,
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ChevronRight,
                                        contentDescription = "Next edit",
                                        modifier = Modifier.size(14.dp),
                                        tint = if (message.branchIndex < message.totalBranches - 1) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                    )
                                }
                            }
                        }
                    }

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
            val isDark = MaterialTheme.colorScheme.background.red < 0.5f
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                // Header: Avatar + Model name inline
                Row(
                    modifier = Modifier.padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Next AI Continuum Avatar with subtle warm aura glow
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .shadow(2.dp, CircleShape, spotColor = ClaudeTerracotta.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center
                    ) {
                        NextAiLogo(
                            size = 28.dp,
                            showBackground = true
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Next AI",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            letterSpacing = 0.2.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                    )
                    if (message.isStreaming) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(ClaudeTerracotta.copy(alpha = 0.15f))
                                .border(0.6.dp, ClaudeTerracotta.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "generating...",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold),
                                color = ClaudeTerracotta
                            )
                        }
                    }
                }

                // Main content column — no visible bubble, canvas-style like ChatGPT
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isSearchMatch) Modifier
                                .border(1.5.dp, ClaudeTerracotta.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                                .padding(8.dp)
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
                            isExpanded = message.isThinkingExpanded ?: (message.isStreaming && message.isThinking),
                            isActivelyThinking = message.isStreaming && message.isThinking,
                            thinkingDurationMs = message.thinkingDurationMs,
                            onToggle = onToggleThinking,
                            onOpenFile = onOpenFile
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    // 2. AGY Terminal Execution Card (bash commands, file edits, views, searches)
                    if (message.toolExecutions.isNotEmpty() || !message.toolExecution.isNullOrBlank()) {
                        val anyRunning = message.toolExecutions.any { it.state == "ACTIVE" }
                        val isToolsCardExpanded = message.isToolsExpanded ?: (anyRunning && message.isStreaming)
                        AgyTerminalExecutionCard(
                            toolItems = message.toolExecutions,
                            singleStatus = message.toolExecution,
                            isExpanded = isToolsCardExpanded,
                            onToggle = onToggleTools,
                            onOpenFile = onOpenFile
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    // 2b. Generated Files Artifact Cards (Derivations, markdown notes, code files, PDFs created or linked by agent)
                    val generatedFiles = remember(message.toolExecutions, message.content) {
                        val list = mutableListOf<String>()
                        // 1. Tool executions (strictly creation/modification tools)
                        message.toolExecutions.forEach { t ->
                            val tName = t.toolName.lowercase()
                            val isCreation = tName in setOf("write_to_file", "replace_file_content", "create_file", "generate_image") ||
                                (t.targetFile?.contains("/brain/") == true)
                            if (isCreation) {
                                t.targetFile?.takeIf { it.isNotBlank() }?.let { raw ->
                                    val clean = com.agychat.app.data.local.LocalFileManager.normalizeFileId(raw)
                                    val fn = com.agychat.app.data.local.LocalFileManager.getFileName(clean)
                                    if (fn.contains('.') && fn.substringAfterLast('.').length in 1..8) list.add(clean)
                                }
                            }
                        }
                        // 2. Markdown links: [label](file:///path), [label](/content/path)
                        val linkRegex = Regex("""\[([^\]]+)\]\(((?:file://|/content/|/root/|/tmp/|https?://[^\s\)]+/api/file|[a-zA-Z0-9_\-./]+\.(?:pdf|md|txt|py|kt|java|json|csv|png|jpg|jpeg|webp|html|svg|sh))[^\s\)]*)\)""")
                        for (m in linkRegex.findAll(message.content)) {
                            val raw = m.groupValues[2].trim()
                            if (raw.isNotBlank() && (!raw.startsWith("http") || raw.contains("/api/file"))) {
                                val clean = com.agychat.app.data.local.LocalFileManager.normalizeFileId(raw)
                                val fn = com.agychat.app.data.local.LocalFileManager.getFileName(clean)
                                if (fn.contains('.') && fn.substringAfterLast('.').length in 1..8) list.add(clean)
                            }
                        }
                        // 3. Explicit file paths mentioned in text
                        val pathRegex = Regex("""(?:file://|/content/|/root/|/tmp/)[a-zA-Z0-9_\-./]+\.(?:pdf|md|txt|py|kt|java|json|csv|png|jpg|jpeg|webp)""")
                        for (m in pathRegex.findAll(message.content)) {
                            val clean = com.agychat.app.data.local.LocalFileManager.normalizeFileId(m.value.trim())
                            val fn = com.agychat.app.data.local.LocalFileManager.getFileName(clean)
                            if (fn.contains('.') && fn.substringAfterLast('.').length in 1..8) list.add(clean)
                        }
                        list.distinctBy { com.agychat.app.data.local.LocalFileManager.normalizeFileId(it) }
                    }
                    if (generatedFiles.isNotEmpty()) {
                        Column(
                            modifier = Modifier.padding(bottom = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            generatedFiles.forEach { rawFilePath ->
                                val cleanPath = remember(rawFilePath) {
                                    var p = rawFilePath.trim()
                                    if (p.startsWith("[") && p.contains("](") && p.endsWith(")")) {
                                        p = p.substringAfter("](").removeSuffix(")")
                                    }
                                    if (p.startsWith("file://")) p = p.removePrefix("file://")
                                    if (p.contains("#")) p = p.substringBefore("#")
                                    p.trim()
                                }
                                val fileName = remember(cleanPath) { cleanPath.substringAfterLast("/").ifBlank { "Artifact" } }
                                val ext = remember(fileName) { fileName.substringAfterLast(".", "").lowercase() }
                                val (icon, tint, typeBadge) = when {
                                    ext == "pdf" -> Triple(Icons.Default.PictureAsPdf, Color(0xFFE53935), "PDF")
                                    ext in setOf("png", "jpg", "jpeg", "webp") -> Triple(Icons.Default.Image, Color(0xFF1E88E5), "IMAGE")
                                    ext in setOf("md", "txt") -> Triple(Icons.Default.Article, ClaudeTerracotta, "NOTES")
                                    ext in setOf("py", "kt", "js", "ts", "java", "cpp", "c", "rs", "go") -> Triple(Icons.Default.Code, ChatGptBlue, ext.uppercase())
                                    ext in setOf("json", "csv", "tsv", "sql") -> Triple(Icons.Default.TableChart, ChatGptEmerald, "DATA")
                                    else -> Triple(Icons.Default.Description, ClaudeTerracotta, if (ext.isNotBlank()) ext.uppercase() else "DOC")
                                }

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenFile(cleanPath) }
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
                                                    .size(34.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(tint.copy(alpha = 0.14f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = icon,
                                                    contentDescription = null,
                                                    tint = tint,
                                                    modifier = Modifier.size(19.dp)
                                                )
                                            }
                                            Spacer(Modifier.width(10.dp))
                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = fileName,
                                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Spacer(Modifier.width(6.dp))
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = tint.copy(alpha = 0.12f)
                                                    ) {
                                                        Text(
                                                            text = typeBadge,
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                fontFamily = FontFamily.Monospace,
                                                                fontWeight = FontWeight.Bold,
                                                                fontSize = 8.5.sp
                                                            ),
                                                            color = tint,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = "Artifact • Tap to view & save to phone",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                )
                                            }
                                        }
                                        Button(
                                            onClick = { onOpenFile(cleanPath) },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(30.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = tint)
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
                    if (showMemoryActivityBadges && message.memoryUpdates.isNotEmpty()) {
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

                    // 3. Main Message Markdown Content with inline streaming cursor (Borderless Canvas Flow)
                    if (message.content.isNotBlank()) {
                        MarkdownContent(
                            text = message.content,
                            isStreaming = showStreamingCursor && message.isStreaming,
                            onLinkClick = onOpenFile
                        )
                    }

                    // Initial streaming state before first token arrives
                    if (message.isStreaming && message.content.isBlank() && message.thinking.isNullOrBlank()) {
                        val isDarkWaiting = MaterialTheme.colorScheme.background.red < 0.5f
                        val dotAccent = if (isDarkWaiting) Color(0xFFA78BFA) else Color(0xFF7C3AED)

                        val dotTransition = rememberInfiniteTransition(label = "typingDots")
                        val dot1Scale by dotTransition.animateFloat(
                            initialValue = 0.4f, targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = keyframes {
                                    durationMillis = 1200
                                    0.4f at 0 with FastOutSlowInEasing
                                    1f at 200 with FastOutSlowInEasing
                                    0.4f at 500
                                    0.4f at 1200
                                }
                            ), label = "dot1"
                        )
                        val dot2Scale by dotTransition.animateFloat(
                            initialValue = 0.4f, targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = keyframes {
                                    durationMillis = 1200
                                    0.4f at 150 with FastOutSlowInEasing
                                    1f at 350 with FastOutSlowInEasing
                                    0.4f at 650
                                    0.4f at 1200
                                }
                            ), label = "dot2"
                        )
                        val dot3Scale by dotTransition.animateFloat(
                            initialValue = 0.4f, targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = keyframes {
                                    durationMillis = 1200
                                    0.4f at 300 with FastOutSlowInEasing
                                    1f at 500 with FastOutSlowInEasing
                                    0.4f at 800
                                    0.4f at 1200
                                }
                            ), label = "dot3"
                        )

                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    if (isDarkWaiting) Color(0xFF1E1B2E) else Color(0xFFF5F3FF)
                                )
                                .border(
                                    0.8.dp,
                                    if (isDarkWaiting) Color(0xFF3D3560) else Color(0xFFDDD8F8),
                                    RoundedCornerShape(20.dp)
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(dot1Scale, dot2Scale, dot3Scale).forEach { scale ->
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .graphicsLayer { scaleX = scale; scaleY = scale }
                                        .clip(CircleShape)
                                        .background(dotAccent.copy(alpha = 0.5f + 0.5f * scale))
                                )
                            }
                        }
                    }


                    // 4. Subtle Action Bar below assistant message (ChatGPT & Gemini style)
                    if (!message.isStreaming) {
                        val wordCount = remember(message.content) {
                            if (message.content.isBlank()) 0
                            else message.content.trim().split(Regex("\\s+")).count { it.isNotBlank() }
                        }
                        val estTokens = (wordCount * 1.33).toInt()
                        val isDarkAction = MaterialTheme.colorScheme.background.red < 0.5f
                        val pillBg = if (isDarkAction) Color(0xFF1E1E22) else Color(0xFFF4F4F6)
                        val pillBorder = if (isDarkAction) Color(0xFF2E2E34) else Color(0xFFE2E2E8)
                        val iconTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)

                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Meta row: timestamp + word/token count
                            Row(
                                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = formatRelativeTime(message.timestamp),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                )
                                if (wordCount > 0) {
                                    Text(
                                        text = "· $wordCount w · ~$estTokens tk",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                    )
                                }
                                if (message.tokensPerSecond != null && message.tokensPerSecond > 0.0) {
                                    val speedStr = String.format(java.util.Locale.US, "%.1f", message.tokensPerSecond)
                                    val durStr = if (message.durationSeconds != null && message.durationSeconds > 0.0)
                                        " · ${String.format(java.util.Locale.US, "%.1fs", message.durationSeconds)}" else ""
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                        border = androidx.compose.foundation.BorderStroke(
                                            0.5.dp,
                                            ClaudeTerracotta.copy(alpha = 0.3f)
                                        ),
                                        modifier = Modifier.padding(start = 4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Bolt,
                                                contentDescription = null,
                                                modifier = Modifier.size(10.dp),
                                                tint = ClaudeTerracotta
                                            )
                                            Text(
                                                text = "$speedStr t/s$durStr",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                ),
                                                color = ClaudeTerracotta
                                            )
                                        }
                                    }
                                }
                            }

                            // Action pills row (scrollable)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // ── Pill 1: Core actions (Copy, Pin, Speak, Share) ──
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(pillBg)
                                        .border(0.6.dp, pillBorder, RoundedCornerShape(14.dp))
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Copy
                                    IconButton(
                                        onClick = {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            cm.setPrimaryClip(ClipData.newPlainText("Response", message.content))
                                            isCopied = true
                                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                                            contentDescription = "Copy",
                                            modifier = Modifier.size(15.dp),
                                            tint = if (isCopied) Color(0xFF4CAF50) else iconTint
                                        )
                                    }
                                    // Pin
                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onTogglePin()
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.PushPin,
                                            contentDescription = if (message.isPinned) "Unpin" else "Pin",
                                            modifier = Modifier.size(14.dp),
                                            tint = if (message.isPinned) Color(0xFFFFB300) else iconTint
                                        )
                                    }
                                    // Speak
                                    IconButton(
                                        onClick = onToggleSpeak,
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            if (isSpeaking) Icons.Default.Stop else Icons.Default.VolumeUp,
                                            contentDescription = if (isSpeaking) "Stop" else "Read aloud",
                                            modifier = Modifier.size(15.dp),
                                            tint = if (isSpeaking) ClaudeTerracotta else iconTint
                                        )
                                    }
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
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Share,
                                            contentDescription = "Share",
                                            modifier = Modifier.size(14.dp),
                                            tint = iconTint
                                        )
                                    }
                                }

                                // ── Pill 2: Feedback thumbs ──
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(pillBg)
                                        .border(0.6.dp, pillBorder, RoundedCornerShape(14.dp))
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onFeedback("like")
                                            Toast.makeText(context, if (message.feedback == "like") "Removed" else "Thanks!", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.ThumbUp,
                                            contentDescription = "Good response",
                                            modifier = Modifier.size(14.dp),
                                            tint = if (message.feedback == "like") ClaudeTerracotta else iconTint
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onFeedback("dislike")
                                            Toast.makeText(context, if (message.feedback == "dislike") "Removed" else "Noted", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.ThumbDown,
                                            contentDescription = "Bad response",
                                            modifier = Modifier.size(14.dp),
                                            tint = if (message.feedback == "dislike") Color(0xFFEF5350) else iconTint
                                        )
                                    }
                                }

                                // ── Branch navigator (if applicable) ──
                                if (message.totalBranches > 1) {
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(pillBg)
                                            .border(0.6.dp, pillBorder, RoundedCornerShape(14.dp))
                                            .padding(horizontal = 4.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(
                                            onClick = {
                                                if (message.branchIndex > 0) {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    onSwitchBranch(message.parentMessageId ?: message.id, message.branchIndex - 1)
                                                }
                                            },
                                            enabled = message.branchIndex > 0,
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(Icons.Default.ChevronLeft, contentDescription = "Prev", modifier = Modifier.size(14.dp),
                                                tint = if (message.branchIndex > 0) ClaudeTerracotta else iconTint.copy(alpha = 0.3f))
                                        }
                                        Text(
                                            text = "${message.branchIndex + 1}/${message.totalBranches}",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.5.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        IconButton(
                                            onClick = {
                                                if (message.branchIndex < message.totalBranches - 1) {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    onSwitchBranch(message.parentMessageId ?: message.id, message.branchIndex + 1)
                                                }
                                            },
                                            enabled = message.branchIndex < message.totalBranches - 1,
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(Icons.Default.ChevronRight, contentDescription = "Next", modifier = Modifier.size(14.dp),
                                                tint = if (message.branchIndex < message.totalBranches - 1) ClaudeTerracotta else iconTint.copy(alpha = 0.3f))
                                        }
                                    }
                                }

                                // ── Continue generating pill ──
                                if (isLastAssistant && !message.isStreaming && message.content.isNotBlank()) {
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(ClaudeTerracotta.copy(alpha = 0.10f))
                                            .border(0.7.dp, ClaudeTerracotta.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                                            .clickable {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onContinueGenerating()
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null,
                                            modifier = Modifier.size(13.dp), tint = ClaudeTerracotta)
                                        Text("Continue", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
                                            color = ClaudeTerracotta)
                                    }
                                }

                                // ── Pill 3: Utility actions (Retry, Reply, Delete) ──
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(pillBg)
                                        .border(0.6.dp, pillBorder, RoundedCornerShape(14.dp))
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isLastAssistant) {
                                        IconButton(onClick = onRetry, modifier = Modifier.size(30.dp)) {
                                            Icon(Icons.Default.Refresh, contentDescription = "Regenerate",
                                                modifier = Modifier.size(14.dp), tint = iconTint)
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onReply()
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(Icons.Default.Reply, contentDescription = "Reply",
                                            modifier = Modifier.size(14.dp), tint = iconTint)
                                    }
                                    IconButton(onClick = onDeleteMessage, modifier = Modifier.size(30.dp)) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete",
                                            modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                                    }
                                }
                            }
                        }


                        // 5. Contextual Quick Follow-Up Suggestion Chips (2X Smart Chips with Category Icons & Entrance Animation)
                        if (showFollowupSuggestions && isLastAssistant && !message.isStreaming && message.content.isNotBlank()) {
                            val suggestions = remember(message.content) {
                                val list = mutableListOf<Triple<String, String, Pair<androidx.compose.ui.graphics.vector.ImageVector, Color>>>()
                                val cLower = message.content.lowercase()
                                val hasCode = cLower.contains("```")
                                val hasError = cLower.contains("error") || cLower.contains("exception") || cLower.contains("failed") || cLower.contains("fatal")
                                val isExplanation = cLower.contains("because") || cLower.contains("means") || cLower.contains("concept") || cLower.contains("overview")
                                val hasSteps = cLower.contains("1.") || cLower.contains("step 1") || cLower.contains("first,")

                                if (hasCode) {
                                    list.add(Triple("Multi-lens audit", "Perform an exhaustive 3-lens adversarial code review (correctness, security, maintainability) per flash38-swe-protocol.", Icons.Default.Security to ChipSecurityAccent))
                                    list.add(Triple("Edge-case tests", "Write comprehensive unit tests with edge cases (empty, boundary, negative, concurrent) for this code.", Icons.Default.Code to ChipCodeAccent))
                                    list.add(Triple("Optimize code", "How can this code be optimized for maximum speed and memory efficiency?", Icons.Default.Bolt to ChipTakeawayAccent))
                                    list.add(Triple("Save to file", "Please save this code to an appropriate workspace file using the write_to_file tool.", Icons.Default.Article to ChipSearchAccent))
                                } else if (hasError) {
                                    list.add(Triple("How to fix", "What are the exact step-by-step instructions to fix this error?", Icons.Default.Tune to ChipBugAccent))
                                    list.add(Triple("Root cause analysis", "Can you explain the deep root cause of why this error happens?", Icons.Default.Psychology to ChipExplainAccent))
                                    list.add(Triple("Prevent regression", "How can we prevent this issue from happening again in the future?", Icons.Default.Security to ChipSecurityAccent))
                                    list.add(Triple("Search web", "Search the web for known fixes and official documentation for this error.", Icons.Default.Language to ChipSearchAccent))
                                } else if (hasSteps || isExplanation) {
                                    list.add(Triple("Technical deep dive", "Can you explain the technical internals in deeper detail?", Icons.Default.Psychology to ChipExplainAccent))
                                    list.add(Triple("Key takeaways", "Summarize the key takeaways and actionable points in bullet format.", Icons.Default.CheckCircle to ChipTakeawayAccent))
                                    list.add(Triple("Concrete examples", "Can you provide concrete practical examples illustrating this?", Icons.Default.AutoAwesome to ChipSecurityAccent))
                                    list.add(Triple("Search web", "Search the web for the latest updates and real-time community discussions on this.", Icons.Default.Language to ChipSearchAccent))
                                } else {
                                    list.add(Triple("Explain in detail", "Please explain this step-by-step in detail.", Icons.Default.Psychology to ChipExplainAccent))
                                    list.add(Triple("Key takeaways", "What are the key takeaways from this?", Icons.Default.CheckCircle to ChipTakeawayAccent))
                                    list.add(Triple("Practical examples", "Can you provide concrete practical examples for this?", Icons.Default.AutoAwesome to ChipSecurityAccent))
                                    list.add(Triple("Search web", "Search the web for real-time live sources on this topic.", Icons.Default.Language to ChipSearchAccent))
                                }
                                list.take(4)
                            }

                            AnimatedVisibility(
                                visible = suggestions.isNotEmpty(),
                                enter = fadeIn(animationSpec = tween(320)) + slideInHorizontally(animationSpec = tween(320))
                            ) {
                                LazyRow(
                                    modifier = Modifier.padding(top = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(suggestions) { (label, prompt, iconAndAccent) ->
                                        val (catIcon, catAccent) = iconAndAccent
                                        val isDarkChip = MaterialTheme.colorScheme.background.red < 0.5f
                                        Surface(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onSendSuggestion(prompt)
                                            },
                                            shape = RoundedCornerShape(20.dp),
                                            color = if (isDarkChip) Color(0xFF1E1E26) else Color(0xFFFFFFFF),
                                            border = androidx.compose.foundation.BorderStroke(
                                                1.dp,
                                                catAccent.copy(alpha = if (isDarkChip) 0.65f else 0.45f)
                                            ),
                                            shadowElevation = 2.dp,
                                            modifier = Modifier.shadow(
                                                elevation = 2.dp,
                                                shape = RoundedCornerShape(20.dp),
                                                spotColor = catAccent.copy(alpha = 0.25f)
                                            )
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(18.dp)
                                                        .clip(CircleShape)
                                                        .background(catAccent.copy(alpha = 0.18f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = catIcon,
                                                        contentDescription = null,
                                                        tint = catAccent,
                                                        modifier = Modifier.size(11.dp)
                                                    )
                                                }
                                                Text(
                                                    text = label,
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 11.5.sp,
                                                        letterSpacing = 0.2.sp
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
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
                        maxLines = 3,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isError) {
                        Surface(
                            onClick = onRetry,
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(0.6.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                            modifier = Modifier.padding(start = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Retry",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = "Retry",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.error
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
fun ThinkingAccordionCard(
    thinkingText: String,
    isExpanded: Boolean,
    isActivelyThinking: Boolean = false,
    thinkingDurationMs: Long = 0L,
    onToggle: () -> Unit,
    onOpenFile: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f

    // Flagship AI color grading (ChatGPT o3, Claude 3.7, Antigravity Desktop)
    val bgColor = if (isDark) Color(0xFF14131C) else Color(0xFFF7F6FC)
    val baseBorderColor = if (isDark) Color(0xFF282638) else Color(0xFFE4E0F4)
    val accentColor = if (isDark) Color(0xFFA78BFA) else Color(0xFF6D28D9)
    val textPrimaryColor = if (isDark) Color(0xFFEDE9FE) else Color(0xFF332F4C)
    val textSecondaryColor = if (isDark) Color(0xFFA5A0BD) else Color(0xFF6B6684)

    var isCopied by remember { mutableStateOf(false) }
    LaunchedEffect(isCopied) {
        if (isCopied) {
            kotlinx.coroutines.delay(1800)
            isCopied = false
        }
    }

    var activeElapsedSeconds by remember { mutableStateOf(1) }
    LaunchedEffect(isActivelyThinking) {
        if (isActivelyThinking) {
            val startWallTime = System.currentTimeMillis() - thinkingDurationMs.coerceAtLeast(0L)
            while (true) {
                val elapsed = (System.currentTimeMillis() - startWallTime) / 1000L
                activeElapsedSeconds = elapsed.coerceAtLeast(1L).toInt()
                kotlinx.coroutines.delay(500L)
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "thinkingCardPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sparklePulse"
    )

    val borderColor = if (isActivelyThinking) {
        accentColor.copy(alpha = 0.35f + (0.35f * pulseAlpha))
    } else {
        baseBorderColor
    }

    val wordCount = remember(thinkingText) {
        thinkingText.trim().split(Regex("\\s+")).count { it.isNotBlank() }
    }

    val summaryLabel = remember(isActivelyThinking, activeElapsedSeconds, thinkingDurationMs, wordCount) {
        if (isActivelyThinking) {
            "Thinking (${activeElapsedSeconds}s)…"
        } else {
            val durationSec = if (thinkingDurationMs > 0L) {
                val s = thinkingDurationMs / 1000.0
                String.format(Locale.US, "%.1fs", s)
            } else {
                val estimatedS = (wordCount / 65.0).coerceAtLeast(1.0)
                String.format(Locale.US, "%.1fs", estimatedS)
            }
            if (wordCount > 0) "Thought for $durationSec (~$wordCount words)"
            else "Thought for $durationSec"
        }
    }

    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "chevronRotation"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle()
                }),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier
                        .size(15.dp)
                        .then(
                            if (isActivelyThinking) Modifier.alpha(pulseAlpha)
                            else Modifier
                        ),
                    tint = accentColor
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = summaryLabel,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp
                    ),
                    color = textPrimaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isExpanded) {
                    IconButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Thinking Process", thinkingText))
                            isCopied = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            Toast.makeText(context, "Copied thinking process", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = "Copy thinking",
                            modifier = Modifier.size(13.dp),
                            tint = if (isCopied) Color(0xFF10B981) else textSecondaryColor
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                }

                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(chevronRotation),
                    tint = textSecondaryColor
                )
            }
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(tween(180)) + expandVertically(tween(220)),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(180))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                HorizontalDivider(
                    color = if (isDark) Color(0xFF262438) else Color(0xFFEAE6F5),
                    thickness = 0.8.dp,
                    modifier = Modifier.padding(bottom = 10.dp)
                )

                Row(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .heightIn(min = 28.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(accentColor.copy(alpha = 0.55f))
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        MarkdownContent(
                            text = thinkingText,
                            textColor = if (isDark) Color(0xFFD4CFE6) else Color(0xFF4A4460),
                            isStreaming = isActivelyThinking,
                            onLinkClick = onOpenFile
                        )
                    }
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

            val terminalChevronRotation by animateFloatAsState(
                targetValue = if (isExpanded) 180f else 0f,
                animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                label = "terminalChevronRotation"
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                modifier = Modifier
                    .size(18.dp)
                    .rotate(terminalChevronRotation),
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
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 550, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )
    Box(
        modifier = Modifier
            .size(width = 2.5.dp, height = 15.dp)
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
    val haptic = LocalHapticFeedback.current
    val curatedChips = remember {
        listOf(
            Triple("Search Web", "/browser ", Icons.Default.Language to ChatGptBlue),
            Triple("Deep Think", "/boost ", Icons.Default.AutoAwesome to ChatGptPurple),
            Triple("Plan", "/plan ", Icons.Default.Assignment to ClaudeTerracotta),
            Triple("Auto Goal", "/goal ", Icons.Default.RocketLaunch to ChatGptEmerald),
            Triple("Remember", "/remember ", Icons.Default.Psychology to ClaudeTerracottaDark)
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        curatedChips.forEach { (label, commandPrefix, iconAndColor) ->
            val (icon, accentColor) = iconAndColor
            val isDark = MaterialTheme.colorScheme.background.red < 0.5f
            Surface(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val trimmedPrefix = commandPrefix.trim()
                    val matching = plugins.firstOrNull { it.prefix == trimmedPrefix }
                        ?: if (trimmedPrefix == "/remember") plugins.firstOrNull { it.prefix == "/learn" } else null
                    if (matching != null) {
                        onChipClick(matching)
                    } else {
                        onChipClick(
                            PluginItem(
                                name = trimmedPrefix.removePrefix("/"),
                                title = label,
                                description = label,
                                icon = icon,
                                prefix = trimmedPrefix,
                                tag = "TOOL",
                                examplePrompt = "$trimmedPrefix ",
                                badgeColor = 0xFFD4704B
                            )
                        )
                    }
                },
                shape = RoundedCornerShape(18.dp),
                color = if (isDark) Color(0xFF1C1C24) else Color(0xFFFFFFFF),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    accentColor.copy(alpha = if (isDark) 0.55f else 0.4f)
                ),
                shadowElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(11.dp)
                        )
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Surface(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onOpenAllTools()
            },
            shape = RoundedCornerShape(18.dp),
            color = ClaudeTerracotta.copy(alpha = 0.14f),
            border = androidx.compose.foundation.BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.45f)),
            shadowElevation = 1.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = ClaudeTerracotta
                )
                Text(
                    text = "All Tools ✦",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.5.sp),
                    color = ClaudeTerracotta
                )
            }
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
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    var isFocused by remember { mutableStateOf(false) }
    val activeAttachments = if (attachments.isNotEmpty()) attachments else if (attachment != null) listOf(attachment) else emptyList()
    val canSend = (text.isNotBlank() || activeAttachments.isNotEmpty()) && isConnected

    val recognizedModes = remember {
        listOf(
            ActiveSlashMode("/browser", "Web Search", Icons.Default.Language, ChatGptBlue),
            ActiveSlashMode("/boost", "Deep Think", Icons.Default.AutoAwesome, ChatGptPurple),
            ActiveSlashMode("/plan", "Plan Mode", Icons.Default.Assignment, Color(0xFF0284C7)),
            ActiveSlashMode("/goal", "Autonomous Goal", Icons.Default.RocketLaunch, Color(0xFF10B981)),
            ActiveSlashMode("/learn", "Memory", Icons.Default.Psychology, Color(0xFFF59E0B)),
            ActiveSlashMode("/remember", "Memory", Icons.Default.Psychology, Color(0xFFF59E0B)),
            ActiveSlashMode("/schedule", "Scheduled", Icons.Default.Schedule, Color(0xFF8B5CF6)),
            ActiveSlashMode("/grill-me", "Interview", Icons.Default.QuestionAnswer, ClaudeTerracotta),
            ActiveSlashMode("/teamwork-preview", "Multi-Agent", Icons.Default.Groups, Color(0xFF06B6D4))
        )
    }
    val activeMode = recognizedModes.firstOrNull { text.startsWith(it.prefix) }
    val isWebSearchActive = activeMode?.prefix == "/browser"

    val isComposerElevated = isFocused || text.isNotBlank() || activeAttachments.isNotEmpty()
    val composerElevation by animateDpAsState(
        targetValue = if (isComposerElevated) 7.dp else 2.5.dp,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "composerElevation"
    )
    val inputBorderColor by animateColorAsState(
        targetValue = when {
            isWebSearchActive -> ChatGptBlue.copy(alpha = 0.85f)
            isComposerElevated -> ClaudeTerracotta.copy(alpha = 0.65f)
            isDark -> GlassComposerDarkBorder
            else -> GlassComposerLightBorder
        },
        animationSpec = tween(durationMillis = 220),
        label = "inputBorderColor"
    )
    val composerBg by animateColorAsState(
        targetValue = if (isDark) GlassComposerDarkBg else GlassComposerLightBg,
        animationSpec = tween(durationMillis = 220),
        label = "composerBg"
    )

    val composerShape = RoundedCornerShape(26.dp)
    Surface(
        shape = composerShape,
        color = composerBg,
        tonalElevation = if (isDark) 3.dp else 1.dp,
        shadowElevation = composerElevation,
        border = androidx.compose.foundation.BorderStroke(
            width = if (isComposerElevated) 1.2.dp else 1.dp,
            color = inputBorderColor
        ),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = composerElevation,
                shape = composerShape,
                spotColor = if (isComposerElevated) ClaudeTerracotta.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.10f),
                ambientColor = Color.Black.copy(alpha = 0.06f)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
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
                                    text = "Replying to ${if (replyToMessage.role == "user") "You" else "Assistant"}",
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
                                    val sizeStr = remember(att.uri) { queryFileSize(context, Uri.parse(att.uri)) }
                                    Text(
                                        text = listOfNotNull(if (att.isImage) "Photo" else "Document", sizeStr.ifBlank { null }).joinToString(" • "),
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

            // ── Tier 1: Expansive Full-Width Text Input ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isFocused = it.isFocused },
                    placeholder = {
                        Text(
                            text = if (isConnected) "Message Next AI or type / for tools..." else "Connect in Settings to chat...",
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.5.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
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
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 15.5.sp,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )

                // Word / Token Count Badge if long prompt
                if (text.length > 80) {
                    val words = remember(text) { text.trim().split(Regex("\\s+")).count { it.isNotBlank() } }
                    val estTokens = (words * 1.33).toInt()
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = 6.dp)
                    ) {
                        Text(
                            text = "$words w · ~$estTokens t",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Medium),
                            color = if (text.length > 4000) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── Tier 2: Refined Action Toolbar (Left Tools & Right Send/Stop) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left Toolbar cluster: Attachments (+), Tools (✦), Web Search pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Plus button (+) for Attachments & Files
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onAttachFile()
                        },
                        shape = CircleShape,
                        color = if (activeAttachments.isNotEmpty()) ClaudeTerracotta.copy(alpha = 0.18f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                        border = androidx.compose.foundation.BorderStroke(
                            0.8.dp,
                            if (activeAttachments.isNotEmpty()) ClaudeTerracotta.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Add attachment or file",
                                tint = if (activeAttachments.isNotEmpty()) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Slash Commands & Tools Button (✦)
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenPlugins()
                        },
                        shape = RoundedCornerShape(18.dp),
                        color = ClaudeTerracotta.copy(alpha = 0.12f),
                        border = androidx.compose.foundation.BorderStroke(
                            0.8.dp,
                            ClaudeTerracotta.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = "Slash Commands and Tools",
                                tint = ClaudeTerracotta,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Tools",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.5.sp
                                ),
                                color = ClaudeTerracotta
                            )
                        }
                    }

                    // Quick Web Search Toggle Pill
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleWebSearch()
                        },
                        shape = RoundedCornerShape(18.dp),
                        color = if (isWebSearchActive) ChatGptBlue.copy(alpha = 0.18f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        border = androidx.compose.foundation.BorderStroke(
                            0.9.dp,
                            if (isWebSearchActive) ChatGptBlue.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                Icons.Default.Language,
                                contentDescription = "Toggle Web Search",
                                modifier = Modifier.size(15.dp),
                                tint = if (isWebSearchActive) ChatGptBlue else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (isWebSearchActive) "Search ON" else "Search",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isWebSearchActive) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 11.5.sp
                                ),
                                color = if (isWebSearchActive) ChatGptBlue else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (isWebSearchActive) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(ChatGptBlue)
                                )
                            }
                        }
                    }
                }

                // Right Action cluster: Clear text (✕) & Action Button (Send / Stop / Mic)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Clear (✕) Button if text non-empty
                    if (text.isNotBlank()) {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onTextChange("")
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear input",
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }

                    // Modern ChatGPT Dynamic Send / Stop / Mic Button with Smooth AnimatedContent Transitions
                    val hasInput = text.isNotBlank() || activeAttachments.isNotEmpty()
                    val actionButtonState = when {
                        isLoading -> ActionButtonState.STOP
                        hasInput -> ActionButtonState.SEND
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
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onStop()
                                    },
                                    modifier = Modifier.size(40.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.Stop,
                                        contentDescription = "Stop Generating",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            ActionButtonState.SEND -> {
                                FilledIconButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        if (canSend) {
                                            onSend()
                                        } else {
                                            Toast.makeText(context, "Bridge offline. Tap Reconnect above.", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.size(40.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = if (canSend) ClaudeTerracotta
                                                         else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.ArrowUpward,
                                        contentDescription = "Send",
                                        tint = if (canSend) Color.White else MaterialTheme.colorScheme.surface,
                                        modifier = Modifier.size(21.dp)
                                    )
                                }
                            }
                            ActionButtonState.MIC -> {
                                Surface(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onVoiceInput()
                                    },
                                    shape = CircleShape,
                                    color = ClaudeTerracotta.copy(alpha = 0.14f),
                                    border = androidx.compose.foundation.BorderStroke(
                                        0.9.dp,
                                        ClaudeTerracotta.copy(alpha = 0.4f)
                                    ),
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Mic,
                                            contentDescription = "Voice dictation",
                                            tint = ClaudeTerracotta,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
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
    userName: String = "",
    userOccupation: String = "",
    depthLevel: String = "Expert",
    showMemoryBadge: Boolean = true,
    hostEnvironment: HostEnvironment = HostEnvironment(),
    onOpenEnvironmentSheet: () -> Unit = {},
    onOpenMemorySheet: () -> Unit = {},
    onOpenCustomInstructions: () -> Unit = {}
) {
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
        else "Choose a prompt below or ask any question to get started"
    }

    val starterPrompts = remember(userOccupation, depthLevel) {
        val occLower = userOccupation.lowercase()
        val isDeep = depthLevel.equals("Expert", ignoreCase = true) || depthLevel.equals("Research", ignoreCase = true)
        when {
            occLower.contains("android") || occLower.contains("mobile") -> listOf(
                Triple("Compose Architecture", "Review state hoisting, recomposition & UI performance", "Analyze our Jetpack Compose UI architecture, state hoisting patterns, and recomposition performance"),
                Triple("Write & Debug Code", "Analyze codebase architecture, find bugs and optimize", "/boost inspect code architecture and suggest improvements"),
                Triple("Android & Kotlin Docs", "Search latest AndroidX releases and official guidance", "/browser search latest Android Jetpack libraries and Kotlin releases"),
                Triple("Autonomous Test Suite", "Run agentic loop until unit & UI tests are created", "/goal write comprehensive unit tests with edge cases")
            )
            occLower.contains("data") || occLower.contains("ml") || occLower.contains("ai") -> listOf(
                Triple("Data Pipeline Optimization", "Vectorize operations and profile memory usage", "Review data processing pipeline and suggest vectorized high-performance optimizations"),
                Triple("Model Evaluation & Benchmarks", "Design metrics framework with precision & recall", "Design an automated evaluation benchmark framework with comprehensive evaluation metrics"),
                Triple("AI Research Papers", "Search latest open-weights LLMs and arxiv papers", "/browser search latest open-weights LLMs and benchmark comparisons"),
                Triple("Phased Roadmap", "Design step-by-step implementation milestones", "/plan create phased roadmap for new features")
            )
            occLower.contains("student") || occLower.contains("learner") -> listOf(
                Triple("Socratic Concept Tutor", "Break down complex topics using intuitive analogies", "Explain distributed consensus and Raft algorithm using intuitive everyday analogies"),
                Triple("Code Walkthrough", "Analyze algorithms with time and space complexity", "Walk through this algorithm step-by-step with Big-O time and space complexity"),
                Triple("Learning Resources & Guides", "Find top-rated tutorials and documentation", "/browser search best practical guides and documentation for beginners"),
                Triple("Structured Study Roadmap", "Build a 4-week structured curriculum", "/plan create 4-week structured study roadmap")
            )
            isDeep -> listOf(
                Triple("Deep Architectural Analysis", "Holistic system review, edge cases & performance bottlenecks", "/boost analyze system architecture, concurrency invariants and bottlenecks"),
                Triple("Autonomous Goal Execution", "Continuous agent loop until objective is verified and complete", "/goal implement comprehensive automated verification and tests"),
                Triple("Real-time Internet Synthesis", "Live search for newest documentation, release notes & papers", "/browser search latest advances and official documentation"),
                Triple("Strategic Multi-step Plan", "Break down complex initiatives into verified milestones", "/plan design milestone-driven implementation roadmap")
            )
            else -> listOf(
                Triple("Real-time Web Search", "Search latest docs, news and live internet facts", "/browser search latest AI news"),
                Triple("Write & Debug Code", "Analyze codebase architecture, find bugs and optimize", "/boost inspect code architecture and suggest improvements"),
                Triple("Phased Roadmap", "Design step-by-step implementation milestones", "/plan create phased roadmap for new features"),
                Triple("Autonomous Goal", "Continuous agent loop until objective is fully solved", "/goal review test coverage and implement missing tests")
            )
        }
    }

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
            text = greetingTitle,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp,
                fontSize = 25.sp
            ),
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = greetingSubtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )

        Spacer(Modifier.height(14.dp))

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

            Spacer(Modifier.width(8.dp))

            // Colab Environment Badge
            Surface(
                onClick = onOpenEnvironmentSheet,
                shape = RoundedCornerShape(12.dp),
                color = ChatGptEmerald.copy(alpha = 0.12f),
                border = androidx.compose.foundation.BorderStroke(0.6.dp, ChatGptEmerald.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
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

            Spacer(Modifier.width(8.dp))

            if (showMemoryBadge && memoriesCount > 0) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryDrawerContent(
    conversations: List<ConversationEntity>,
    activeId: String,
    onSelectConversation: (String) -> Unit,
    onNewChat: () -> Unit,
    onDelete: (String) -> Unit,
    onPin: (String) -> Unit = {},
    onRename: (String, String) -> Unit = { _, _ -> },
    onOpenSettings: () -> Unit,
    onOpenMemory: () -> Unit = {},
    onOpenCustomInstructions: () -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedDrawerFilter by remember { mutableStateOf("all") }

    val now = System.currentTimeMillis()
    val cal = remember(now) {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }
    val todayStart = cal.timeInMillis
    val yesterdayStart = todayStart - 86_400_000L
    val sevenDaysAgo = todayStart - (7 * 86_400_000L)
    val thirtyDaysAgo = todayStart - (30 * 86_400_000L)

    val filteredConversations = remember(conversations, searchQuery, selectedDrawerFilter, sevenDaysAgo) {
        conversations.filter { conv ->
            val matchesSearch = searchQuery.isBlank() || conv.title.contains(searchQuery, ignoreCase = true)
            val matchesFilter = when (selectedDrawerFilter) {
                "pinned" -> conv.isPinned
                "recent" -> conv.updatedAt >= sevenDaysAgo
                else -> true
            }
            matchesSearch && matchesFilter
        }
    }

    ModalDrawerSheet(
        modifier = Modifier.width(320.dp),
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
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ) {
                    Text(
                        text = "${conversations.size}",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
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

            Spacer(Modifier.height(8.dp))

            // Quick Filter Chips Row (All, Pinned, Recent)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = { selectedDrawerFilter = "all" },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selectedDrawerFilter == "all") ClaudeTerracotta.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = androidx.compose.foundation.BorderStroke(0.6.dp, if (selectedDrawerFilter == "all") ClaudeTerracotta.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "All (${conversations.size})",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = if (selectedDrawerFilter == "all") FontWeight.Bold else FontWeight.Medium),
                        color = if (selectedDrawerFilter == "all") ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                val pinnedCount = remember(conversations) { conversations.count { it.isPinned } }
                Surface(
                    onClick = { selectedDrawerFilter = "pinned" },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selectedDrawerFilter == "pinned") Color(0xFFFFB300).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = androidx.compose.foundation.BorderStroke(0.6.dp, if (selectedDrawerFilter == "pinned") Color(0xFFFFB300).copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(Icons.Default.PushPin, contentDescription = null, modifier = Modifier.size(11.dp), tint = if (selectedDrawerFilter == "pinned") Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = "Pinned ($pinnedCount)",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = if (selectedDrawerFilter == "pinned") FontWeight.Bold else FontWeight.Medium),
                            color = if (selectedDrawerFilter == "pinned") Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    onClick = { selectedDrawerFilter = "recent" },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selectedDrawerFilter == "recent") ClaudeTerracotta.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = androidx.compose.foundation.BorderStroke(0.6.dp, if (selectedDrawerFilter == "recent") ClaudeTerracotta.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "Recent",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = if (selectedDrawerFilter == "recent") FontWeight.Bold else FontWeight.Medium),
                        color = if (selectedDrawerFilter == "recent") ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            val pinnedList = filteredConversations.filter { it.isPinned }
            val unpinnedList = filteredConversations.filter { !it.isPinned }

            val grouped = unpinnedList.groupBy { conv ->
                when {
                    conv.updatedAt >= todayStart -> "Today"
                    conv.updatedAt >= yesterdayStart -> "Yesterday"
                    conv.updatedAt >= sevenDaysAgo -> "Previous 7 Days"
                    conv.updatedAt >= thirtyDaysAgo -> "Previous 30 Days"
                    else -> "Older"
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (pinnedList.isNotEmpty()) {
                    stickyHeader(key = "header_pinned") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.PushPin,
                                    contentDescription = null,
                                    tint = Color(0xFFFFB300),
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "PINNED",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.8.sp
                                    ),
                                    color = Color(0xFFFFB300)
                                )
                            }
                        }
                    }
                    items(pinnedList, key = { it.id }) { conv ->
                        ConversationDrawerItem(
                            conv = conv,
                            isActive = conv.id == activeId,
                            onClick = { onSelectConversation(conv.id) },
                            onPin = { onPin(conv.id) },
                            onRename = { newTitle -> onRename(conv.id, newTitle) },
                            onDelete = { onDelete(conv.id) }
                        )
                    }
                }

                val groupOrder = listOf("Today", "Yesterday", "Previous 7 Days", "Previous 30 Days", "Older")
                groupOrder.forEach { groupName ->
                    val groupConvs = grouped[groupName] ?: return@forEach
                    stickyHeader(key = "header_$groupName") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Text(
                                text = groupName.uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
                            )
                        }
                    }
                    items(groupConvs, key = { it.id }) { conv ->
                        ConversationDrawerItem(
                            conv = conv,
                            isActive = conv.id == activeId,
                            onClick = { onSelectConversation(conv.id) },
                            onPin = { onPin(conv.id) },
                            onRename = { newTitle -> onRename(conv.id, newTitle) },
                            onDelete = { onDelete(conv.id) }
                        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationDrawerItem(
    conv: ConversationEntity,
    isActive: Boolean,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember(conv.title) { mutableStateOf(conv.title) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true }
            ),
        color = if (isActive) ClaudeTerracotta.copy(alpha = 0.12f) else Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (conv.isPinned) {
                Icon(
                    Icons.Default.PushPin,
                    contentDescription = "Pinned",
                    tint = Color(0xFFFFB300),
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(6.dp))
            } else {
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
            }

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
                val subtitle = if (conv.messageCount > 0) {
                    "${conv.messageCount} msgs • ${formatRelativeTime(conv.updatedAt)}"
                } else {
                    formatRelativeTime(conv.updatedAt)
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Options",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (conv.isPinned) "Unpin" else "Pin to top") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = null,
                                tint = if (conv.isPinned) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        onClick = {
                            showMenu = false
                            onPin()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        onClick = {
                            showMenu = false
                            showRenameDialog = true
                        }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        onClick = {
                            showMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Chat") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = renameText.trim()
                        if (trimmed.isNotBlank()) {
                            onRename(trimmed)
                        }
                        showRenameDialog = false
                    }
                ) {
                    Text("Save", color = ClaudeTerracotta)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
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
    onOpenExternal: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onRetry: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val ext = remember(fileData.filename) {
        fileData.filename.substringAfterLast(".", "").lowercase()
    }
    val isMarkdown = ext in setOf("md", "markdown", "txt")
    var viewMode by remember(fileData.filename) {
        mutableStateOf(if (isMarkdown) "rendered" else "code")
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .padding(horizontal = 16.dp, vertical = 4.dp)
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
                    val (headerIcon, iconBg, iconTint) = when {
                        fileData.isPdf -> Triple(Icons.Default.PictureAsPdf, Color(0xFFE53935).copy(alpha = 0.15f), Color(0xFFE53935))
                        fileData.isImage -> Triple(Icons.Default.Image, Color(0xFF1E88E5).copy(alpha = 0.15f), Color(0xFF1E88E5))
                        isMarkdown -> Triple(Icons.Default.Article, ClaudeTerracotta.copy(alpha = 0.15f), ClaudeTerracotta)
                        ext in setOf("py", "kt", "js", "ts", "java", "c", "cpp", "rs", "go", "html", "css") ->
                            Triple(Icons.Default.Code, ChatGptBlue.copy(alpha = 0.15f), ChatGptBlue)
                        ext in setOf("json", "csv", "tsv", "sql", "xml", "yaml", "yml") ->
                            Triple(Icons.Default.TableChart, ChatGptEmerald.copy(alpha = 0.15f), ChatGptEmerald)
                        ext in setOf("sh", "bash") ->
                            Triple(Icons.Default.Terminal, ChatGptEmerald.copy(alpha = 0.15f), ChatGptEmerald)
                        else -> Triple(Icons.Default.Description, ClaudeTerracotta.copy(alpha = 0.15f), ClaudeTerracotta)
                    }

                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(iconBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            headerIcon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = fileData.filename,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (ext.isNotBlank()) {
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = iconTint.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = ext.uppercase(),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp
                                        ),
                                        color = iconTint,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }

                        val sizeStr = if (fileData.size > 0) {
                            val kb = fileData.size / 1024.0
                            String.format(java.util.Locale.US, "%.1f KB", kb)
                        } else if (fileData.content.isNotBlank()) {
                            val kb = fileData.content.toByteArray(Charsets.UTF_8).size / 1024.0
                            String.format(java.util.Locale.US, "%.1f KB", kb)
                        } else ""
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (fileData.isOfflineCached) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color(0xFF10A37F).copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "💾 OFFLINE READY",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp
                                        ),
                                        color = Color(0xFF10A37F),
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = listOfNotNull(sizeStr.takeIf { it.isNotBlank() }, fileData.path.takeIf { it.isNotBlank() }).joinToString(" • "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isMarkdown && !fileData.isBinary) {
                        // Toggle between Rendered preview and Raw code
                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewMode = if (viewMode == "rendered") "code" else "rendered"
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = androidx.compose.foundation.BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Text(
                                text = if (viewMode == "rendered") "Raw" else "Preview",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                                color = ClaudeTerracotta,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }

                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Quick Actions Bar: Save, Open in App, Copy, Share
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSaveToPhone()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                }

                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onOpenExternal()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ChatGptEmerald),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1.15f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Open in App", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                }

                if (!fileData.isBinary) {
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onCopy()
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(0.75f),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("Copy", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                    }
                }

                OutlinedButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onShare()
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(0.75f),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Share", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
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
                                "Loading file from Colab bridge…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    fileData.error != null -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = "Unable to Load Artifact",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = fileData.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(20.dp))
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onRetry()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Retry Fetch", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                            }
                        }
                    }
                    fileData.isPdf -> {
                        InAppPdfViewer(
                            file = fileData.localDiskFile,
                            bytes = fileData.bytes,
                            onOpenExternal = onOpenExternal
                        )
                    }
                    fileData.isImage -> {
                        InAppImageViewer(
                            file = fileData.localDiskFile,
                            bytes = fileData.bytes
                        )
                    }
                    fileData.isBinary -> {
                        BinaryFileSummaryView(
                            fileData = fileData,
                            onOpenExternal = onOpenExternal,
                            onSaveToPhone = onSaveToPhone
                        )
                    }
                    else -> {
                        if (isMarkdown && viewMode == "rendered") {
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
                        } else {
                            // Syntax-highlighted code editor viewer with line numbers
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(bottom = 24.dp)
                            ) {
                                CodeBlockView(
                                    language = if (isMarkdown) "markdown" else ext,
                                    code = fileData.content,
                                    showLineNumbers = true
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
fun InAppPdfViewer(
    file: File?,
    bytes: ByteArray?,
    onOpenExternal: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var pageBitmaps by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var totalPageCount by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(file, bytes) {
        isLoading = true
        errorMessage = null
        withContext(Dispatchers.IO) {
            try {
                val targetFile: File? = file?.takeIf { it.exists() && it.length() > 0 } ?: run {
                    if (bytes != null && bytes.isNotEmpty()) {
                        val temp = File(context.cacheDir, "pdf_preview_${System.currentTimeMillis()}.pdf")
                        temp.writeBytes(bytes)
                        temp
                    } else null
                }

                if (targetFile == null || !targetFile.exists()) {
                    withContext(Dispatchers.Main) {
                        errorMessage = "PDF content is not available on local device"
                        isLoading = false
                    }
                    return@withContext
                }

                var pfd: ParcelFileDescriptor? = null
                var renderer: PdfRenderer? = null
                try {
                    pfd = ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    renderer = PdfRenderer(pfd)
                    val count = renderer.pageCount
                    val maxPages = minOf(count, 20)
                    val bitmaps = mutableListOf<Bitmap>()
                    val displayWidth = context.resources.displayMetrics.widthPixels

                    for (i in 0 until maxPages) {
                        val page = renderer.openPage(i)
                        val scale = (displayWidth.toFloat() / page.width.toFloat()).coerceIn(1.0f, 1.8f)
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                        val canvas = Canvas(bmp)
                        canvas.drawColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmaps.add(bmp)
                        page.close()
                    }

                    withContext(Dispatchers.Main) {
                        pageBitmaps = bitmaps
                        totalPageCount = count
                        isLoading = false
                    }
                } finally {
                    try { renderer?.close() } catch (_: Throwable) {}
                    try { pfd?.close() } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                Log.e("PdfViewer", "Failed to render PDF pages", t)
                withContext(Dispatchers.Main) {
                    errorMessage = "Could not render PDF in-app: ${t.localizedMessage ?: "File may be protected or unsupported"}"
                    isLoading = false
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            isLoading -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = ClaudeTerracotta, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Rendering PDF pages…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            errorMessage != null -> {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(errorMessage ?: "", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onOpenExternal,
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open in External Viewer")
                    }
                }
            }
            pageBitmaps.isEmpty() -> {
                Text("No pages found in PDF document", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 28.dp)
                ) {
                    items(pageBitmaps.size) { idx ->
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Image(
                                    bitmap = pageBitmaps[idx].asImageBitmap(),
                                    contentDescription = "Page ${idx + 1}",
                                    modifier = Modifier.fillMaxWidth(),
                                    contentScale = ContentScale.FillWidth
                                )
                                Surface(
                                    color = Color(0xFFEEEEEE),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Page ${idx + 1} of $totalPageCount",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = Color.DarkGray,
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    if (totalPageCount > pageBitmaps.size) {
                        item {
                            Card(
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Showing first ${pageBitmaps.size} of $totalPageCount pages",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Open in external app to view the rest of the document.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Button(
                                        onClick = onOpenExternal,
                                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                                    ) {
                                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Open", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InAppImageViewer(
    file: File?,
    bytes: ByteArray?,
    modifier: Modifier = Modifier
) {
    val bitmap = remember(file, bytes) {
        try {
            if (bytes != null && bytes.isNotEmpty()) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else if (file != null && file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.BrokenImage, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text("Could not preview image", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun BinaryFileSummaryView(
    fileData: FileViewerData,
    onOpenExternal: () -> Unit,
    onSaveToPhone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(ClaudeTerracotta.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.InsertDriveFile,
                        contentDescription = null,
                        tint = ClaudeTerracotta,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Text(
                    text = fileData.filename,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "${fileData.mimeType.ifBlank { "Binary File" }} • ${if (fileData.size > 0) String.format(java.util.Locale.US, "%.1f KB", fileData.size / 1024.0) else "Ready"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "This file type can be opened with dedicated apps on your device (e.g. Office, Docs, Media Player).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onOpenExternal,
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open in App")
                    }

                    OutlinedButton(
                        onClick = onSaveToPhone,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Download")
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
    onCopyPath: (String) -> Unit,
    onDeleteFiles: (List<String>) -> Unit = {},
    onDownloadFiles: (List<String>) -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    // Deduplicate and normalize session files
    val normalizedFiles = remember(sessionFiles) {
        sessionFiles.map { com.agychat.app.data.local.LocalFileManager.normalizeFileId(it) }
            .filter { it.isNotBlank() && com.agychat.app.data.local.LocalFileManager.getFileName(it).contains('.') }
            .distinct()
    }

    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedFiles = remember { mutableStateListOf<String>() }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var singleFileToDelete by remember { mutableStateOf<String?>(null) }

    fun getCategoryForFile(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "py", "kt", "kts", "js", "ts", "java", "cpp", "c", "rs", "go", "sh", "bash", "html", "css" -> "Code"
            "md", "markdown", "txt", "pdf", "doc", "docx" -> "Docs"
            "png", "jpg", "jpeg", "webp", "gif", "svg", "bmp" -> "Images"
            "json", "csv", "tsv", "sql", "xml", "yaml", "yml" -> "Data"
            else -> "Other"
        }
    }

    val filteredFiles = remember(normalizedFiles, searchQuery, selectedCategory) {
        normalizedFiles.filter { path ->
            val fn = com.agychat.app.data.local.LocalFileManager.getFileName(path)
            val matchesQuery = searchQuery.isBlank() || fn.contains(searchQuery, ignoreCase = true) || path.contains(searchQuery, ignoreCase = true)
            val matchesCategory = when (selectedCategory) {
                "All" -> true
                else -> getCategoryForFile(fn) == selectedCategory
            }
            matchesQuery && matchesCategory
        }
    }

    fun shareFile(filePath: String) {
        try {
            val norm = com.agychat.app.data.local.LocalFileManager.normalizeFileId(filePath)
            val fn = com.agychat.app.data.local.LocalFileManager.getFileName(norm)
            val safeDiskName = "${norm.hashCode().toString().replace("-", "n")}_$fn"
            val f1 = java.io.File(context.filesDir, "saved_files/$safeDiskName")
            val f2 = java.io.File(context.filesDir, "saved_files/$fn")
            val f3 = java.io.File(filePath)
            val target = when {
                f1.exists() && f1.length() > 0 -> f1
                f2.exists() && f2.length() > 0 -> f2
                f3.exists() && f3.length() > 0 -> f3
                else -> null
            }
            if (target != null) {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    target
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = com.agychat.app.data.local.LocalFileManager.detectMimeType(target.name)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share $fn"))
            } else {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, filePath)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share $fn"))
            }
        } catch (t: Throwable) {
            Toast.makeText(context, "Share failed: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 720.dp)
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
                                    text = "Artifacts & Files",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = ClaudeTerracotta.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "${normalizedFiles.size} total",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ClaudeTerracotta,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "Browse, preview, export, or remove files",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (normalizedFiles.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (isSelectionMode) {
                                        isSelectionMode = false
                                        selectedFiles.clear()
                                    } else {
                                        isSelectionMode = true
                                    }
                                }
                            ) {
                                Text(
                                    text = if (isSelectionMode) "Done" else "Select",
                                    color = ClaudeTerracotta,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                }

                // Batch Operations Action Bar (visible during selection mode)
                AnimatedVisibility(visible = isSelectionMode) {
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "${selectedFiles.size} selected",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                        color = ClaudeTerracotta
                                    )
                                    TextButton(
                                        onClick = {
                                            if (selectedFiles.size == filteredFiles.size) {
                                                selectedFiles.clear()
                                            } else {
                                                selectedFiles.clear()
                                                selectedFiles.addAll(filteredFiles)
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = if (selectedFiles.size == filteredFiles.size && filteredFiles.isNotEmpty()) "Deselect All" else "Select All",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(
                                        onClick = {
                                            if (selectedFiles.isNotEmpty()) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onDownloadFiles(selectedFiles.toList())
                                            }
                                        },
                                        enabled = selectedFiles.isNotEmpty(),
                                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Download", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                    }

                                    Button(
                                        onClick = {
                                            if (selectedFiles.isNotEmpty()) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                showDeleteConfirmDialog = true
                                            }
                                        },
                                        enabled = selectedFiles.isNotEmpty(),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Delete", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Search Bar & Filter Chips
                if (normalizedFiles.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = androidx.compose.foundation.BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            androidx.compose.foundation.text.BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                decorationBox = { innerTextField ->
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "Search artifacts by name or path...",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Category Chips
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val categories = listOf("All", "Code", "Docs", "Images", "Data")
                        categories.forEach { cat ->
                            val isSelected = selectedCategory == cat
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selectedCategory = cat
                                }
                            ) {
                                Text(
                                    text = cat,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal),
                                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Content List
                if (filteredFiles.isEmpty()) {
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
                            text = if (searchQuery.isNotBlank() || selectedCategory != "All") "No Matching Artifacts" else "No Artifacts Yet",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (searchQuery.isNotBlank() || selectedCategory != "All") "Try adjusting your search terms or filter selection." else "Files, derivations, notes, and code created by Next AI will appear here for preview and export.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredFiles, key = { it }) { filePath ->
                            val fileName = com.agychat.app.data.local.LocalFileManager.getFileName(filePath)
                            val ext = fileName.substringAfterLast('.', "").lowercase()
                            val (icon, tint) = when (ext) {
                                "md", "txt" -> Icons.Default.Article to ClaudeTerracotta
                                "py", "kt", "js", "ts", "java", "cpp", "c", "rs", "go" -> Icons.Default.Code to ChatGptBlue
                                "json", "csv", "tsv", "sql", "xml", "yaml", "yml" -> Icons.Default.TableChart to ChatGptEmerald
                                "sh", "bash" -> Icons.Default.Terminal to ChatGptEmerald
                                "png", "jpg", "jpeg", "webp", "gif", "svg" -> Icons.Default.Image to Color(0xFFF59E0B)
                                "pdf" -> Icons.Default.PictureAsPdf to Color(0xFFE53935)
                                else -> Icons.Default.Description to ClaudeTerracotta
                            }

                            val norm = remember(filePath) { com.agychat.app.data.local.LocalFileManager.normalizeFileId(filePath) }
                            val fn = remember(norm) { com.agychat.app.data.local.LocalFileManager.getFileName(norm) }
                            val safeDiskName = remember(norm, fn) { "${norm.hashCode().toString().replace("-", "n")}_$fn" }
                            val localCandidate = remember(safeDiskName, fn, filePath) {
                                val f1 = java.io.File(context.filesDir, "saved_files/$safeDiskName")
                                val f2 = java.io.File(context.filesDir, "saved_files/$fn")
                                val f3 = java.io.File(filePath)
                                when {
                                    f1.exists() && f1.length() > 0 -> f1
                                    f2.exists() && f2.length() > 0 -> f2
                                    f3.exists() && f3.length() > 0 -> f3
                                    else -> null
                                }
                            }
                            val isOfflineCached = localCandidate != null
                            val sizeStr = remember(localCandidate) {
                                if (localCandidate != null) {
                                    val kb = localCandidate.length() / 1024.0
                                    String.format(java.util.Locale.US, "%.1f KB", kb)
                                } else null
                            }
                            val isSelected = selectedFiles.contains(filePath)

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) ClaudeTerracotta.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                border = androidx.compose.foundation.BorderStroke(
                                    if (isSelected) 1.2.dp else 0.8.dp,
                                    if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isSelectionMode) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            if (isSelected) selectedFiles.remove(filePath) else selectedFiles.add(filePath)
                                        } else {
                                            onOpenFile(filePath)
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isSelectionMode) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                if (isSelected) selectedFiles.remove(filePath) else selectedFiles.add(filePath)
                                            },
                                            colors = CheckboxDefaults.colors(checkedColor = ClaudeTerracotta)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(tint.copy(alpha = 0.12f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = tint,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    Spacer(Modifier.width(10.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = fileName,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (ext.isNotBlank()) {
                                                Spacer(Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = tint.copy(alpha = 0.12f)
                                                ) {
                                                    Text(
                                                        text = ext.uppercase(),
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontFamily = FontFamily.Monospace,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 8.5.sp
                                                        ),
                                                        color = tint,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }

                                        val statusPrefix = if (isOfflineCached) "OFFLINE READY" else "ON COLAB"
                                        val pathSubText = listOfNotNull(statusPrefix, sizeStr, filePath).joinToString(" • ")
                                        Text(
                                            text = pathSubText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isOfflineCached) Color(0xFF10A37F) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    if (!isSelectionMode) {
                                        Spacer(Modifier.width(4.dp))

                                        IconButton(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onDownloadFiles(listOf(filePath))
                                            },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Download,
                                                contentDescription = "Download to phone",
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                shareFile(filePath)
                                            },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Share,
                                                contentDescription = "Share",
                                                modifier = Modifier.size(15.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                singleFileToDelete = filePath
                                            },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "Delete",
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onCopyPath(filePath)
                                            },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = "Copy Path",
                                                modifier = Modifier.size(15.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        Button(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onOpenFile(filePath)
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Visibility,
                                                contentDescription = null,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(Modifier.width(3.dp))
                                            Text("View", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
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

    // Batch Delete Confirmation Dialog
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete ${selectedFiles.size} Artifacts?") },
            text = { Text("Are you sure you want to delete ${selectedFiles.size} selected artifact(s)? Local files and cached data will be removed from your session.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteFiles(selectedFiles.toList())
                        selectedFiles.clear()
                        isSelectionMode = false
                        showDeleteConfirmDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Single File Delete Confirmation Dialog
    if (singleFileToDelete != null) {
        val fName = com.agychat.app.data.local.LocalFileManager.getFileName(singleFileToDelete!!)
        AlertDialog(
            onDismissRequest = { singleFileToDelete = null },
            title = { Text("Delete Artifact?") },
            text = { Text("Are you sure you want to delete \"$fName\" from session artifacts?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteFiles(listOf(singleFileToDelete!!))
                        singleFileToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { singleFileToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun StoragePermissionDialog(
    onDismiss: () -> Unit,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(ClaudeTerracotta.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = ClaudeTerracotta,
                    modifier = Modifier.size(26.dp)
                )
            }
        },
        title = {
            Text(
                text = "Storage Permission Required",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Next AI needs storage access to save and export files, attachments, and generated artifacts directly to your device's public Downloads folder.\n\nWithout this permission, your files remain safely accessible within the app's persistent offline storage.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Allow Access", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(
                    onClick = onOpenSettings,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Settings", color = MaterialTheme.colorScheme.primary)
                }
                TextButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

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
                val previewDensity = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
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
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f

    val cardBg = if (isDark) Color(0xFF1B1B1F) else Color(0xFFF7F7FA)
    val cardBorder = if (isDark) Color(0xFF2C2C32) else Color(0xFFE5E5EB)

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
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(ChatGptEmerald.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Terminal,
                            contentDescription = null,
                            tint = ChatGptEmerald,
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

            // 1. Live Connection Status Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = cardBg,
                border = androidx.compose.foundation.BorderStroke(0.8.dp, cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val isConnected = connectionState == ConnectionState.CONNECTED
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isConnected) ChatGptEmerald else MaterialTheme.colorScheme.error)
                            )
                            Text(
                                text = if (isConnected) "Colab Bridge Online" else "Disconnected",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = if (isConnected) ChatGptEmerald else MaterialTheme.colorScheme.error
                            )
                            if (connectionLatency != null) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = ChatGptEmerald.copy(alpha = 0.12f)
                                ) {
                                    Text(
                                        text = "${connectionLatency}ms",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                                        color = ChatGptEmerald,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        // Ping & Refresh Buttons
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Surface(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onPing()
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            ) {
                                Text(
                                    text = "Ping",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Surface(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onRefresh()
                                    Toast.makeText(context, "Refreshed environment", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Refresh",
                                    modifier = Modifier
                                        .padding(4.dp)
                                        .size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    if (serverUrl.isNotBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = serverUrl,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy URL",
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable {
                                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Server URL", serverUrl))
                                        Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
                                    },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 2. Active AI Model & Cognition Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = cardBg,
                border = androidx.compose.foundation.BorderStroke(0.8.dp, cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "AI MODEL & COGNITION",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, fontSize = 10.sp),
                        color = ClaudeTerracotta
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = selectedModel.name,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Provider: ${selectedModel.provider} · ${selectedModel.badge}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (selectedModel.supportsEffort) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = ClaudeTerracotta.copy(alpha = 0.12f),
                                border = androidx.compose.foundation.BorderStroke(0.6.dp, ClaudeTerracotta.copy(alpha = 0.35f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Default.ElectricBolt, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.size(12.dp))
                                    Text(
                                        text = reasoningEffort.replaceFirstChar { it.uppercase() },
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.5.sp),
                                        color = ClaudeTerracotta
                                    )
                                }
                            }
                        }
                    }

                    // Capability Pills Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("1M Context", "LaTeX Vinculum Math", "Tool Execution", "Web Search", "Drive Persistence").forEach { cap ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ) {
                                Text(
                                    text = cap,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 3. Colab Host & Runtime Specs Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = cardBg,
                border = androidx.compose.foundation.BorderStroke(0.8.dp, cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "COLAB RUNTIME & HARDWARE",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, fontSize = 10.sp),
                        color = ChatGptEmerald
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Host Platform", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(hostEnvironment.host, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("OS & Python", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Python ${hostEnvironment.pythonVersion}", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Hardware Resources", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${hostEnvironment.cpuCount} CPU · ${hostEnvironment.ramGb} GB RAM", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Google Drive", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (hostEnvironment.isDriveMounted) "Mounted (/MyDrive)" else "Not Mounted", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = if (hostEnvironment.isDriveMounted) ChatGptEmerald else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    if (hostEnvironment.gitRepo != null) {
                        HorizontalDivider(thickness = 0.6.dp, color = cardBorder)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.size(15.dp))
                            Column {
                                Text(
                                    text = "${hostEnvironment.gitRepo} (${hostEnvironment.gitBranch}) · ${hostEnvironment.gitCommit}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (!hostEnvironment.gitCommitMsg.isNullOrBlank()) {
                                    Text(
                                        text = hostEnvironment.gitCommitMsg!!,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    if (hostEnvironment.skills.isNotEmpty()) {
                        HorizontalDivider(thickness = 0.6.dp, color = cardBorder)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = ChatGptPurple, modifier = Modifier.size(15.dp))
                            Column {
                                Text(
                                    text = "Active Skills: ${hostEnvironment.skills.joinToString(", ")}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Adversarial self-review & execution verification enforced",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = ChatGptPurple
                                )
                            }
                        }
                    }
                }
            }

            // 4. Workspace Directory Switcher Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = cardBg,
                border = androidx.compose.foundation.BorderStroke(0.8.dp, cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "ACTIVE WORKSPACE CWD",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = currentCwd,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
                            color = ChatGptEmerald
                        )
                    }

                    Text(
                        text = "Commands, tests, and file modifications execute in this folder on the host:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Quick directory buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val targets = listOf("/content/Next-Ai", "/content", "/tmp")
                        targets.forEach { path ->
                            val isSelected = currentCwd == path
                            Surface(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSwitchCwd(path)
                                    Toast.makeText(context, "CWD switched to $path", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) ClaudeTerracotta.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = androidx.compose.foundation.BorderStroke(
                                    0.7.dp,
                                    if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = path.substringAfterLast('/').ifBlank { path },
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                                        color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = path,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

