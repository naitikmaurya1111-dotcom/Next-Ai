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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

