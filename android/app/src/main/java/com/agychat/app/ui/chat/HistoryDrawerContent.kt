package com.agychat.app.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agychat.app.data.local.ConversationEntity
import com.agychat.app.ui.theme.ChatGptPurple
import com.agychat.app.ui.theme.ClaudeTerracotta
import java.util.Calendar

/**
 * Modern In-Chat Navigation Drawer for Browsing, Searching and Managing Conversations.
 */
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
