package com.agychat.app.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.agychat.app.ui.theme.ClaudeTerracotta
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.agychat.app.data.local.ConversationEntity
import com.agychat.app.ui.chat.ChatViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onRestoreConversation: (String) -> Unit = {},
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    var searchQuery by remember { mutableStateOf("") }
    var showClearAllDialog by remember { mutableStateOf(false) }
    val conversations by chatViewModel.conversations.collectAsState(initial = emptyList())

    val filteredConversations = remember(conversations, searchQuery) {
        if (searchQuery.isBlank()) conversations
        else conversations.filter { it.title.contains(searchQuery, ignoreCase = true) }
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
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear All Chats", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { padding ->
        var conversationToRename by remember { mutableStateOf<ConversationEntity?>(null) }
        var renameText by remember { mutableStateOf("") }
        var conversationToDelete by remember { mutableStateOf<ConversationEntity?>(null) }

        // Clear All Dialog
        if (showClearAllDialog) {
            AlertDialog(
                onDismissRequest = { showClearAllDialog = false },
                title = { Text("Clear All History?") },
                text = { Text("This will permanently delete all conversation history. This action cannot be undone.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            chatViewModel.clearAllConversations()
                            showClearAllDialog = false
                        }
                    ) {
                        Text("Delete All", color = MaterialTheme.colorScheme.error)
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
                        Text("Delete", color = MaterialTheme.colorScheme.error)
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
            // Search input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search past conversations...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, null)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(14.dp),
                singleLine = true
            )

            if (filteredConversations.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = null,
                            modifier = Modifier.size(54.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = if (searchQuery.isBlank()) "No conversation history" else "No matching chats",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
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
                    if (pinnedList.isNotEmpty()) {
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

@Composable
fun ConversationCard(
    conversation: ConversationEntity,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(
                1.dp,
                if (conversation.isPinned) Color(0xFFFFB300).copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (conversation.isPinned)
                Color(0xFFFFB300).copy(alpha = 0.04f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (conversation.isPinned) Icons.Default.PushPin else Icons.Default.ChatBubbleOutline,
                contentDescription = null,
                tint = if (conversation.isPinned) Color(0xFFFFB300) else ClaudeTerracotta,
                modifier = Modifier.size(20.dp)
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.title.ifBlank { "Untitled Conversation" },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = if (conversation.messageCount > 0) {
                    "${conversation.messageCount} msgs • ${dateFormat.format(Date(conversation.updatedAt))}"
                } else {
                    dateFormat.format(Date(conversation.updatedAt))
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            IconButton(
                onClick = onPin,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.PushPin,
                    contentDescription = if (conversation.isPinned) "Unpin" else "Pin",
                    tint = if (conversation.isPinned) Color(0xFFFFB300) else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(Modifier.width(2.dp))

            IconButton(
                onClick = onRename,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Rename",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(Modifier.width(2.dp))

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
