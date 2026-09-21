package com.agychat.app.ui.history

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
import com.agychat.app.ui.theme.ClaudeTerracotta
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.agychat.app.data.local.ConversationEntity
import com.agychat.app.ui.chat.ChatViewModel
import java.text.SimpleDateFormat
import java.util.*

enum class HistoryFilter { ALL, PINNED, RECENT }

private fun formatRelativeTime(updatedAt: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - updatedAt
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
    val thirtyDaysAgo = todayStart - (30 * 86_400_000L)

    val filteredConversations = remember(conversations, searchQuery, selectedFilter, sevenDaysAgo) {
        conversations.filter { conv ->
            val matchesSearch = searchQuery.isBlank() || conv.title.contains(searchQuery, ignoreCase = true)
            val matchesFilter = when (selectedFilter) {
                HistoryFilter.ALL -> true
                HistoryFilter.PINNED -> conv.isPinned
                HistoryFilter.RECENT -> conv.updatedAt >= sevenDaysAgo
            }
            matchesSearch && matchesFilter
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Chat History",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        chatViewModel.startNewConversation()
                        onBack()
                    }) {
                        Icon(Icons.Default.AddComment, contentDescription = "New Chat")
                    }
                    if (conversations.isNotEmpty()) {
                        IconButton(onClick = { showClearAllDialog = true }) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = "Clear All Chats",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        var conversationToRename by remember { mutableStateOf<ConversationEntity?>(null) }
        var renameText by remember { mutableStateOf("") }
        var conversationToDelete by remember { mutableStateOf<ConversationEntity?>(null) }

        // Clear All Confirmation Dialog
        if (showClearAllDialog) {
            AlertDialog(
                onDismissRequest = { showClearAllDialog = false },
                icon = {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(32.dp)
                    )
                },
                title = { Text("Clear All History?") },
                text = { Text("This will permanently delete all conversations and cached messages. This action cannot be undone.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            chatViewModel.clearAllConversations()
                            showClearAllDialog = false
                        }
                    ) {
                        Text("Delete All", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearAllDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Rename Dialog
        if (conversationToRename != null) {
            AlertDialog(
                onDismissRequest = { conversationToRename = null },
                title = { Text("Rename Chat") },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            conversationToRename?.let {
                                chatViewModel.renameConversation(it.id, renameText)
                            }
                            conversationToRename = null
                        }
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { conversationToRename = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Delete Single Confirmation Dialog
        if (conversationToDelete != null) {
            AlertDialog(
                onDismissRequest = { conversationToDelete = null },
                icon = {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp)
                    )
                },
                title = { Text("Delete Conversation?") },
                text = { Text("Are you sure you want to delete \"${conversationToDelete?.title}\"?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            conversationToDelete?.let {
                                chatViewModel.deleteConversation(it.id)
                            }
                            conversationToDelete = null
                        }
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { conversationToDelete = null }) {
                        Text("Cancel")
                    }
                }
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
                    placeholder = { Text("Search past conversations...") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
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
                    singleLine = true
                )

                // Quick Filter Chips Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = selectedFilter == HistoryFilter.ALL,
                        onClick = { selectedFilter = HistoryFilter.ALL },
                        label = { Text("All (${conversations.size})") },
                        shape = RoundedCornerShape(16.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ClaudeTerracotta.copy(alpha = 0.15f),
                            selectedLabelColor = ClaudeTerracotta
                        )
                    )
                    val pinnedCount = remember(conversations) { conversations.count { it.isPinned } }
                    FilterChip(
                        selected = selectedFilter == HistoryFilter.PINNED,
                        onClick = { selectedFilter = HistoryFilter.PINNED },
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
                    FilterChip(
                        selected = selectedFilter == HistoryFilter.RECENT,
                        onClick = { selectedFilter = HistoryFilter.RECENT },
                        label = { Text("Recent (7d)") },
                        shape = RoundedCornerShape(16.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ClaudeTerracotta.copy(alpha = 0.15f),
                            selectedLabelColor = ClaudeTerracotta
                        )
                    )
                }

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
                                       else if (selectedFilter == HistoryFilter.RECENT) "No recent chats"
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
                    val groupOrder = listOf("Today", "Yesterday", "Previous 7 Days", "Previous 30 Days", "Older")

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (selectedFilter != HistoryFilter.PINNED && pinnedList.isNotEmpty()) {
                            item(key = "h_pinned") {
                                Row(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.PushPin,
                                        contentDescription = null,
                                        tint = Color(0xFFFFB300),
                                        modifier = Modifier.size(14.dp)
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
                            items(pinnedList, key = { it.id }) { conv ->
                                ConversationCard(
                                    conversation = conv,
                                    isActive = conv.id == activeConversationId,
                                    onClick = {
                                        chatViewModel.loadConversation(conv.id)
                                        onRestoreConversation(conv.id)
                                        onBack()
                                    },
                                    onPin = { chatViewModel.toggleConversationPinned(conv.id) },
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
                                        chatViewModel.loadConversation(conv.id)
                                        onRestoreConversation(conv.id)
                                        onBack()
                                    },
                                    onPin = { chatViewModel.toggleConversationPinned(conv.id) },
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
                            groupOrder.forEach { groupName ->
                                val groupConvs = grouped[groupName] ?: return@forEach
                                item(key = "h_$groupName") {
                                    Text(
                                        text = groupName.uppercase(),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.8.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                    )
                                }
                                items(groupConvs, key = { it.id }) { conv ->
                                    ConversationCard(
                                        conversation = conv,
                                        isActive = conv.id == activeConversationId,
                                        onClick = {
                                            chatViewModel.loadConversation(conv.id)
                                            onRestoreConversation(conv.id)
                                            onBack()
                                        },
                                        onPin = { chatViewModel.toggleConversationPinned(conv.id) },
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(
                width = if (isActive) 1.2.dp else 0.8.dp,
                color = when {
                    isActive -> ClaudeTerracotta.copy(alpha = 0.7f)
                    conversation.isPinned -> Color(0xFFFFB300).copy(alpha = 0.4f)
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
                        .height(36.dp)
                        .background(ClaudeTerracotta, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(10.dp))
            }

            // Chat / Pin Icon
            Box(
                modifier = Modifier
                    .size(38.dp)
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
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
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
                }

                Spacer(Modifier.height(3.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val timeStr = formatRelativeTime(conversation.updatedAt)
                    Text(
                        text = if (conversation.messageCount > 0) "${conversation.messageCount} messages · $timeStr" else timeStr,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }
            }

            // Single Sleek Overflow Menu Button (MoreVert)
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
