package com.agychat.app.ui.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.agychat.app.data.local.ConversationEntity
import com.agychat.app.domain.model.AiModel
import com.agychat.app.domain.model.ModelRegistry
import com.agychat.app.ui.chat.ChatViewModel
import com.agychat.app.ui.theme.ClaudeTerracotta
import java.text.SimpleDateFormat
import java.util.*

enum class HistoryFilter { ALL, PINNED, RECENT }

private fun formatRelativeTime(updatedAt: Long): String {
    val now = System.currentTimeMillis()
    val diff = (now - updatedAt).coerceAtLeast(0L)
    return when {
        diff < 60_000L -> "Just now"
        diff < 3_600_000L -> "${(diff / 60_000L).coerceAtLeast(1)}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
        diff < 172_800_000L -> "Yesterday"
        diff < 7 * 86_400_000L -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(updatedAt))
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(updatedAt))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onRestoreConversation: (String) -> Unit = {},
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    val haptic = LocalHapticFeedback.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(HistoryFilter.ALL) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    val conversations by chatViewModel.conversations.collectAsState(initial = emptyList())
    val activeConversationId by chatViewModel.activeConversationId.collectAsState()

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

    val filteredConversations = remember(conversations, searchQuery, selectedFilter, sevenDaysAgo) {
        conversations.filter { conv ->
            val matchesSearch = searchQuery.isBlank() ||
                conv.title.contains(searchQuery, ignoreCase = true) ||
                (conv.modelId != null && conv.modelId.contains(searchQuery, ignoreCase = true))
            val matchesFilter = when (selectedFilter) {
                HistoryFilter.ALL -> true
                HistoryFilter.PINNED -> conv.isPinned
                HistoryFilter.RECENT -> conv.updatedAt >= sevenDaysAgo
            }
            matchesSearch && matchesFilter
        }
    }

    val pinnedList = remember(filteredConversations) { filteredConversations.filter { it.isPinned } }
    val unpinnedList = remember(filteredConversations) { filteredConversations.filter { !it.isPinned } }

    val grouped = remember(unpinnedList, todayStart, yesterdayStart, sevenDaysAgo) {
        unpinnedList.groupBy { conv ->
            when {
                conv.updatedAt >= todayStart -> "Today"
                conv.updatedAt >= yesterdayStart -> "Yesterday"
                conv.updatedAt >= sevenDaysAgo -> "Previous 7 Days"
                else -> "Older"
            }
        }
    }
    val groupOrder = listOf("Today", "Yesterday", "Previous 7 Days", "Older")

    var conversationToRename by remember { mutableStateOf<ConversationEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var conversationToDelete by remember { mutableStateOf<ConversationEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Chat History",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        if (conversations.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = ClaudeTerracotta.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = "${conversations.size}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = ClaudeTerracotta,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        chatViewModel.startNewConversation()
                        onBack()
                    }) {
                        Icon(Icons.Default.AddComment, contentDescription = "New Chat", tint = ClaudeTerracotta)
                    }
                    if (conversations.isNotEmpty()) {
                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showClearAllDialog = true
                        }) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = "Clear All Chats",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        // Clear All Confirmation Dialog
        if (showClearAllDialog) {
            AlertDialog(
                onDismissRequest = { showClearAllDialog = false },
                icon = {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                },
                title = {
                    Text(
                        "Clear All History?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        "This will permanently delete all ${conversations.size} conversations and cached messages from this device. This action cannot be undone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            chatViewModel.clearAllConversations()
                            showClearAllDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Delete All", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearAllDialog = false }) {
                        Text("Cancel")
                    }
                },
                shape = RoundedCornerShape(18.dp)
            )
        }

        // Rename Dialog
        if (conversationToRename != null) {
            AlertDialog(
                onDismissRequest = { conversationToRename = null },
                title = { Text("Rename Chat", fontWeight = FontWeight.Bold) },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text("Chat Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            conversationToRename?.let {
                                chatViewModel.renameConversation(it.id, renameText.trim())
                            }
                            conversationToRename = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { conversationToRename = null }) {
                        Text("Cancel")
                    }
                },
                shape = RoundedCornerShape(18.dp)
            )
        }

        // Single Conversation Delete Modal
        if (conversationToDelete != null) {
            AlertDialog(
                onDismissRequest = { conversationToDelete = null },
                icon = {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                },
                title = {
                    Text(
                        "Delete Conversation?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        "Are you sure you want to delete \"${conversationToDelete?.title}\"? All associated messages will be permanently removed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            conversationToDelete?.let {
                                chatViewModel.deleteConversation(it.id)
                            }
                            conversationToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Delete", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { conversationToDelete = null }) {
                        Text("Cancel")
                    }
                },
                shape = RoundedCornerShape(18.dp)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxSize()
            ) {
                // Search Input Field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search conversations & models...") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Search",
                            tint = if (searchQuery.isNotBlank()) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        AnimatedVisibility(
                            visible = searchQuery.isNotEmpty(),
                            enter = fadeIn() + scaleIn(),
                            exit = fadeOut() + scaleOut()
                        ) {
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                searchQuery = ""
                            }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Clear search",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ClaudeTerracotta,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                    )
                )

                // Quick Filter Chips Row (All, Pinned, Recent)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // ALL Filter Chip
                    FilterChip(
                        selected = selectedFilter == HistoryFilter.ALL,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedFilter = HistoryFilter.ALL
                        },
                        label = { Text("All (${conversations.size})") },
                        shape = RoundedCornerShape(16.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ClaudeTerracotta.copy(alpha = 0.15f),
                            selectedLabelColor = ClaudeTerracotta
                        )
                    )

                    // PINNED Filter Chip
                    val pinnedCount = remember(conversations) { conversations.count { it.isPinned } }
                    FilterChip(
                        selected = selectedFilter == HistoryFilter.PINNED,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedFilter = HistoryFilter.PINNED
                        },
                        label = { Text("Pinned ($pinnedCount)") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = if (selectedFilter == HistoryFilter.PINNED) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFFB300).copy(alpha = 0.15f),
                            selectedLabelColor = Color(0xFFFFB300)
                        )
                    )

                    // RECENT Filter Chip (7d)
                    val recentCount = remember(conversations, sevenDaysAgo) {
                        conversations.count { it.updatedAt >= sevenDaysAgo }
                    }
                    FilterChip(
                        selected = selectedFilter == HistoryFilter.RECENT,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedFilter = HistoryFilter.RECENT
                        },
                        label = { Text("Recent ($recentCount)") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = if (selectedFilter == HistoryFilter.RECENT) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ClaudeTerracotta.copy(alpha = 0.15f),
                            selectedLabelColor = ClaudeTerracotta
                        )
                    )
                }

                // Empty State View
                if (filteredConversations.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(horizontal = 32.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(72.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (searchQuery.isNotBlank() || selectedFilter != HistoryFilter.ALL)
                                            Icons.Default.SearchOff
                                        else
                                            Icons.Default.History,
                                        contentDescription = null,
                                        modifier = Modifier.size(34.dp),
                                        tint = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                            Text(
                                text = if (searchQuery.isNotBlank()) "No matching conversations"
                                else if (selectedFilter == HistoryFilter.PINNED) "No pinned conversations"
                                else if (selectedFilter == HistoryFilter.RECENT) "No recent chats in 7 days"
                                else "No conversation history yet",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (searchQuery.isNotBlank() || selectedFilter != HistoryFilter.ALL)
                                    "Try adjusting your search terms or filter selection."
                                else
                                    "Your chats with Next AI will be securely saved and listed here.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            if (searchQuery.isNotBlank() || selectedFilter != HistoryFilter.ALL) {
                                OutlinedButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        searchQuery = ""
                                        selectedFilter = HistoryFilter.ALL
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Reset Filters")
                                }
                            } else {
                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        chatViewModel.startNewConversation()
                                        onBack()
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Start a New Chat")
                                }
                            }
                        }
                    }
                } else {
                    // Conversation List grouped chronologically into Today, Yesterday, Previous 7 Days, Older
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Section: PINNED
                        if (selectedFilter != HistoryFilter.PINNED && pinnedList.isNotEmpty()) {
                            item(key = "h_pinned") {
                                SectionHeader(
                                    title = "PINNED",
                                    count = pinnedList.size,
                                    icon = Icons.Default.PushPin,
                                    tint = Color(0xFFFFB300)
                                )
                            }
                            items(pinnedList, key = { it.id }) { conv ->
                                ConversationCard(
                                    conversation = conv,
                                    isActive = conv.id == activeConversationId,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        chatViewModel.loadConversation(conv.id)
                                        onRestoreConversation(conv.id)
                                        onBack()
                                    },
                                    onPin = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        chatViewModel.toggleConversationPinned(conv.id)
                                    },
                                    onRename = {
                                        renameText = conv.title
                                        conversationToRename = conv
                                    },
                                    onDelete = {
                                        conversationToDelete = conv
                                    }
                                )
                            }
                        }

                        if (selectedFilter == HistoryFilter.PINNED) {
                            items(pinnedList, key = { it.id }) { conv ->
                                ConversationCard(
                                    conversation = conv,
                                    isActive = conv.id == activeConversationId,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        chatViewModel.loadConversation(conv.id)
                                        onRestoreConversation(conv.id)
                                        onBack()
                                    },
                                    onPin = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        chatViewModel.toggleConversationPinned(conv.id)
                                    },
                                    onRename = {
                                        renameText = conv.title
                                        conversationToRename = conv
                                    },
                                    onDelete = {
                                        conversationToDelete = conv
                                    }
                                )
                            }
                        } else {
                            // Render distinct visual sections: Today, Yesterday, Previous 7 Days, Older
                            groupOrder.forEach { groupName ->
                                val groupConvs = grouped[groupName] ?: return@forEach
                                if (groupConvs.isNotEmpty()) {
                                    item(key = "h_$groupName") {
                                        val sectionIcon = when (groupName) {
                                            "Today" -> Icons.Default.Today
                                            "Yesterday" -> Icons.Default.Schedule
                                            "Previous 7 Days" -> Icons.Default.DateRange
                                            else -> Icons.Default.Archive
                                        }
                                        SectionHeader(
                                            title = groupName.uppercase(),
                                            count = groupConvs.size,
                                            icon = sectionIcon,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                        )
                                    }
                                    items(groupConvs, key = { it.id }) { conv ->
                                        ConversationCard(
                                            conversation = conv,
                                            isActive = conv.id == activeConversationId,
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                chatViewModel.loadConversation(conv.id)
                                                onRestoreConversation(conv.id)
                                                onBack()
                                            },
                                            onPin = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                chatViewModel.toggleConversationPinned(conv.id)
                                            },
                                            onRename = {
                                                renameText = conv.title
                                                conversationToRename = conv
                                            },
                                            onDelete = {
                                                conversationToDelete = conv
                                            }
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
private fun SectionHeader(
    title: String,
    count: Int,
    icon: ImageVector,
    tint: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(13.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            ),
            color = tint
        )
        Spacer(Modifier.width(6.dp))
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = tint.copy(alpha = 0.12f)
        ) {
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                color = tint,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    }
}

@Composable
fun ConversationCard(
    conversation: ConversationEntity,
    isActive: Boolean = false,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val resolvedModel: AiModel = remember(conversation.modelId) {
        ModelRegistry.findById(conversation.modelId)
    }

    val modelBadgeInfo = remember(resolvedModel) {
        when {
            resolvedModel.id.contains("gemini", ignoreCase = true) -> Triple(
                Icons.Default.AutoAwesome,
                resolvedModel.name.replace("Gemini ", ""),
                Color(0xFF4285F4)
            )
            resolvedModel.id.contains("claude", ignoreCase = true) -> Triple(
                Icons.Default.Psychology,
                resolvedModel.name.replace("Claude ", ""),
                ClaudeTerracotta
            )
            resolvedModel.id.contains("gpt", ignoreCase = true) -> Triple(
                Icons.Default.Memory,
                "GPT-OSS",
                Color(0xFF10A37F)
            )
            else -> Triple(
                Icons.Default.SmartToy,
                resolvedModel.name.take(12),
                Color(0xFF8E24AA)
            )
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(
                width = if (isActive) 1.5.dp else if (conversation.isPinned) 1.dp else 0.8.dp,
                color = when {
                    isActive -> ClaudeTerracotta.copy(alpha = 0.8f)
                    conversation.isPinned -> Color(0xFFFFB300).copy(alpha = 0.6f)
                    else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                },
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isActive -> ClaudeTerracotta.copy(alpha = 0.08f)
                conversation.isPinned -> Color(0xFFFFB300).copy(alpha = 0.04f)
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Accent Pill Indicator for Active Conversation
            if (isActive) {
                Box(
                    modifier = Modifier
                        .width(3.5.dp)
                        .height(38.dp)
                        .background(ClaudeTerracotta, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(10.dp))
            }

            // Chat / Pin Icon Container
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isActive -> ClaudeTerracotta.copy(alpha = 0.15f)
                            conversation.isPinned -> Color(0xFFFFB300).copy(alpha = 0.15f)
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        conversation.isPinned -> Icons.Default.PushPin
                        isActive -> Icons.Default.ChatBubble
                        else -> Icons.Default.ChatBubbleOutline
                    },
                    contentDescription = null,
                    tint = when {
                        conversation.isPinned -> Color(0xFFFFB300)
                        isActive -> ClaudeTerracotta
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(19.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Title and Status Badges
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = conversation.title.ifBlank { "Untitled Conversation" },
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    // Active Badge
                    if (isActive) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = ClaudeTerracotta.copy(alpha = 0.18f)
                        ) {
                            Text(
                                text = "ACTIVE",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                ),
                                color = ClaudeTerracotta,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }

                    // Gold Pin Ribbon Badge for Bookmarked Chats
                    if (conversation.isPinned) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFFFFB300).copy(alpha = 0.18f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Icon(
                                    Icons.Default.PushPin,
                                    contentDescription = "Pinned",
                                    tint = Color(0xFFFFB300),
                                    modifier = Modifier.size(10.dp)
                                )
                                Text(
                                    text = "PINNED",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.4.sp
                                    ),
                                    color = Color(0xFFFFB300)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(5.dp))

                // Metadata Row: Model Badge + Message Count Pill + Relative Timestamp
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Model Icon Badge
                    val (mIcon, mLabel, mColor) = modelBadgeInfo
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = mColor.copy(alpha = 0.12f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = mIcon,
                                contentDescription = null,
                                tint = mColor,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = mLabel,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                color = mColor
                            )
                        }
                    }

                    // Message Count Pill
                    if (conversation.messageCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Forum,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                    modifier = Modifier.size(10.dp)
                                )
                                Text(
                                    text = "${conversation.messageCount} msgs",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }

                    Text(
                        text = "·",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )

                    // Relative Timestamp ('2h ago', 'Yesterday', etc.)
                    Text(
                        text = formatRelativeTime(conversation.updatedAt),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }
            }

            // Overflow Menu Button (MoreVert)
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Conversation options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (conversation.isPinned) "Unpin" else "Pin to Top") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = null,
                                tint = if (conversation.isPinned) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurface
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
                            Icon(Icons.Default.Edit, contentDescription = null)
                        },
                        onClick = {
                            showMenu = false
                            onRename()
                        }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
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
}
