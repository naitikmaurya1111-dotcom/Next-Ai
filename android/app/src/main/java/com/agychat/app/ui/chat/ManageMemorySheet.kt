package com.agychat.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agychat.app.data.local.MemoryEntity
import com.agychat.app.domain.model.MemoryCategory
import com.agychat.app.ui.theme.ClaudeTerracotta
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageMemorySheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val memories by viewModel.memories.collectAsState(initial = emptyList())
    val isMemoryEnabled by viewModel.isMemoryEnabled.collectAsState()
    val isAutoMemoryEnabled by viewModel.isAutoMemoryEnabled.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingMemory by remember { mutableStateOf<MemoryEntity?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val categories = listOf("All", "project", "preference", "personal", "style", "general")

    val filteredMemories = remember(memories, searchQuery, selectedCategory) {
        memories.filter { mem ->
            val matchesQuery = searchQuery.isBlank() || mem.content.contains(searchQuery, ignoreCase = true)
            val matchesCategory = selectedCategory == "All" || mem.category.equals(selectedCategory, ignoreCase = true)
            matchesQuery && matchesCategory
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(ClaudeTerracotta.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Psychology,
                            contentDescription = null,
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Memory",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = ClaudeTerracotta.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = "${memories.count { it.isEnabled }} active",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ClaudeTerracotta,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "Next AI remembers details across all chats",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            val json = viewModel.exportMemoriesJson()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Next AI Memories", json))
                            Toast.makeText(context, "Memories exported to clipboard!", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Export Memories",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = { showImportDialog = true }
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "Import Memories",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = { showAddDialog = true }
                    ) {
                        Icon(
                            Icons.Default.AddCircle,
                            contentDescription = "Add Memory",
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Master Toggle & Auto-Extract Controls
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Persistent Memory Active",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (isMemoryEnabled) "Injecting memories into relevant prompts" else "Memory reference is paused",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isMemoryEnabled,
                            onCheckedChange = { viewModel.setMemoryEnabled(it) }
                        )
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        thickness = 0.6.dp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Autonomous Memory Extraction",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Automatically learns preferences as you chat",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isAutoMemoryEnabled,
                            onCheckedChange = { viewModel.setAutoMemoryEnabled(it) },
                            modifier = Modifier.scale(0.85f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search memories by text or topic...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))

            // Category Filter Row with Counters
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(categories) { cat ->
                    val isSelected = selectedCategory == cat
                    val count = if (cat == "All") memories.size else memories.count { it.category.equals(cat, ignoreCase = true) }
                    val label = when (cat) {
                        "All" -> "All ($count)"
                        "project" -> "🎯 Project ($count)"
                        "preference" -> "⚙️ Preference ($count)"
                        "personal" -> "👤 Personal ($count)"
                        "style" -> "🎨 Style ($count)"
                        "general" -> "💡 General ($count)"
                        else -> "$cat ($count)"
                    }
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCategory = cat },
                        label = {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            )
                        }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Memories List
            if (filteredMemories.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = if (searchQuery.isBlank()) "No memories in this category yet." else "No memories matching \"$searchQuery\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Tap + to add facts or type \"/remember <fact>\" in chat",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 360.dp)
                ) {
                    items(filteredMemories, key = { it.id }) { mem ->
                        MemoryItemCard(
                            memory = mem,
                            onToggle = { viewModel.toggleMemory(mem.id, it) },
                            onEdit = { editingMemory = mem },
                            onDelete = { viewModel.deleteMemory(mem.id) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Bottom Actions: Clear All & Summary
            if (memories.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${memories.size} total memories saved",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = { showClearConfirm = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Clear All", fontSize = 12.sp)
                    }
                }
            }
        }
    }
    }

    // Add Memory Dialog with Inspiration Templates
    if (showAddDialog) {
        var memoryText by remember { mutableStateOf("") }
        var category by remember { mutableStateOf("preference") }

        val templateChips = listOf(
            "Prefer Jetpack Compose",
            "Write clean architecture",
            "Always include type hints",
            "Be direct and concise",
            "Target Android SDK 34"
        )

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.AddCircle, contentDescription = null, tint = ClaudeTerracotta)
                    Text("Add New Memory")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Tell Next AI something to remember about you, your tech stack, or coding conventions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Quick inspiration chips
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(templateChips) { template ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.clickable { memoryText = template }
                            ) {
                                Text(
                                    text = template,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = memoryText,
                        onValueChange = { memoryText = it },
                        placeholder = { Text("e.g. Always write Jetpack Compose code with ViewModel and StateFlow") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5,
                        shape = RoundedCornerShape(10.dp)
                    )

                    Text("Category:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf("project", "preference", "personal", "style", "general").forEach { cat ->
                            FilterChip(
                                selected = category == cat,
                                onClick = { category = cat },
                                label = { Text(cat.replaceFirstChar { it.uppercase() }, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (memoryText.isNotBlank()) {
                            viewModel.addMemory(memoryText.trim(), category)
                            showAddDialog = false
                        }
                    },
                    enabled = memoryText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Text("Save Memory")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Edit Memory Dialog
    editingMemory?.let { mem ->
        var editContent by remember(mem) { mutableStateOf(mem.content) }
        var editCategory by remember(mem) { mutableStateOf(mem.category) }

        AlertDialog(
            onDismissRequest = { editingMemory = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = ClaudeTerracotta)
                    Text("Edit Memory")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editContent,
                        onValueChange = { editContent = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5,
                        shape = RoundedCornerShape(10.dp)
                    )

                    Text("Category:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf("project", "preference", "personal", "style", "general").forEach { cat ->
                            FilterChip(
                                selected = editCategory.equals(cat, ignoreCase = true),
                                onClick = { editCategory = cat },
                                label = { Text(cat.replaceFirstChar { it.uppercase() }, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editContent.isNotBlank()) {
                            viewModel.editMemory(mem.id, editContent.trim(), editCategory)
                            editingMemory = null
                        }
                    },
                    enabled = editContent.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Text("Update")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingMemory = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Import Memories Dialog
    if (showImportDialog) {
        var importJson by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import Memories JSON") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Paste a JSON array of memories to import into your local SQLite storage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = importJson,
                        onValueChange = { importJson = it },
                        placeholder = { Text("[{\"content\": \"User prefers Kotlin\", \"category\": \"preference\"}]") },
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val success = viewModel.importMemoriesJson(importJson)
                        if (success) {
                            Toast.makeText(context, "Memories imported successfully!", Toast.LENGTH_SHORT).show()
                            showImportDialog = false
                        } else {
                            Toast.makeText(context, "Invalid JSON format", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = importJson.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Clear All Confirmation Dialog
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear All Memories?") },
            text = {
                Text("This will permanently delete all ${memories.size} saved memories across all chats. Next AI will forget all saved preferences.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllMemories()
                        showClearConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete Everything")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun MemoryItemCard(
    memory: MemoryEntity,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = remember(memory.updatedAt) {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(memory.updatedAt))
    }

    val (categoryLabel, categoryColor) = when (memory.category.lowercase()) {
        "project" -> "🎯 Project" to Color(0xFF1976D2)
        "preference" -> "⚙️ Preference" to Color(0xFFE65100)
        "personal" -> "👤 Personal" to Color(0xFF7B1FA2)
        "style" -> "🎨 Style" to Color(0xFF00897B)
        else -> "💡 General" to Color(0xFF455A64)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
            .border(
                1.dp,
                if (memory.isEnabled) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                RoundedCornerShape(12.dp)
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (memory.isEnabled) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Category Chip
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = categoryColor.copy(alpha = 0.12f),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, categoryColor.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = categoryLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = categoryColor,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = dateStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Switch(
                        checked = memory.isEnabled,
                        onCheckedChange = onToggle,
                        modifier = Modifier.scale(0.75f)
                    )
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Text(
                text = memory.content,
                style = MaterialTheme.typography.bodyMedium,
                color = if (memory.isEnabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}
