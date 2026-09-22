package com.agychat.app.ui.chat

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agychat.app.data.local.LocalFileManager
import com.agychat.app.ui.theme.ClaudeTerracotta
import java.io.File

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
    val clipboard = LocalClipboardManager.current

    // Deduplicate and normalize session files
    val normalizedFiles = remember(sessionFiles) {
        sessionFiles.map { LocalFileManager.normalizeFileId(it) }
            .filter { it.isNotBlank() && LocalFileManager.getFileName(it).contains('.') }
            .distinct()
    }

    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedFiles = remember { mutableStateListOf<String>() }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") } // "All", "Code", "Documents", "Images"
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var activeDiffFile by remember { mutableStateOf<String?>(null) }

    fun getCategoryForFile(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "py", "kt", "kts", "js", "ts", "java", "cpp", "c", "rs", "go", "sh", "bash", "html", "css", "json", "xml", "yaml", "yml", "sql" -> "Code"
            "md", "markdown", "txt", "pdf", "doc", "docx", "csv", "tsv" -> "Documents"
            "png", "jpg", "jpeg", "webp", "gif", "svg", "bmp" -> "Images"
            else -> "Documents"
        }
    }

    val filteredFiles = remember(normalizedFiles, searchQuery, selectedCategory) {
        normalizedFiles.filter { path ->
            val fn = LocalFileManager.getFileName(path)
            val matchesQuery = searchQuery.isBlank() || fn.contains(searchQuery, ignoreCase = true) || path.contains(searchQuery, ignoreCase = true)
            val matchesCategory = when (selectedCategory) {
                "All" -> true
                else -> getCategoryForFile(fn) == selectedCategory
            }
            matchesQuery && matchesCategory
        }
    }

    fun getFileMeta(path: String): Triple<String, String, Long> {
        val fn = LocalFileManager.getFileName(path)
        val ext = fn.substringAfterLast('.', "").uppercase()
        val safeDiskName = "${path.hashCode().toString().replace("-", "n")}_$fn"
        val f1 = File(context.filesDir, "saved_files/$safeDiskName")
        val f2 = File(context.filesDir, "saved_files/$fn")
        val f3 = File(path)
        val target = when {
            f1.exists() -> f1
            f2.exists() -> f2
            f3.exists() -> f3
            else -> null
        }
        val sizeBytes = target?.length() ?: 0L
        val sizeStr = when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
            else -> "${String.format(java.util.Locale.US, "%.1f", sizeBytes / (1024.0 * 1024.0))} MB"
        }
        return Triple(ext.ifBlank { "FILE" }, sizeStr, sizeBytes)
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f)
                    .padding(horizontal = 16.dp, vertical = 4.dp)
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
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(ClaudeTerracotta.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = ClaudeTerracotta,
                                modifier = Modifier.size(22.dp)
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
                                    shape = RoundedCornerShape(10.dp),
                                    color = ClaudeTerracotta.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "${normalizedFiles.size}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = ClaudeTerracotta,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "Code, generated assets & diff inspection",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (normalizedFiles.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    isSelectionMode = !isSelectionMode
                                    if (!isSelectionMode) selectedFiles.clear()
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
                            border = BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.35f)),
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
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Delete", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter files by name or path...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Spacer(Modifier.height(8.dp))

                // Category Tabs: All, Code, Documents, Images
                val categories = listOf("All", "Code", "Documents", "Images")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEach { cat ->
                        val isSelected = selectedCategory == cat
                        val catCount = remember(normalizedFiles, cat) {
                            if (cat == "All") normalizedFiles.size
                            else normalizedFiles.count { getCategoryForFile(LocalFileManager.getFileName(it)) == cat }
                        }
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedCategory = cat
                            },
                            label = { Text("$cat ($catCount)") },
                            shape = RoundedCornerShape(14.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ClaudeTerracotta.copy(alpha = 0.15f),
                                selectedLabelColor = ClaudeTerracotta
                            )
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // File List
                if (filteredFiles.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Text(
                                text = if (searchQuery.isNotBlank()) "No files match \"$searchQuery\"" else "No artifacts in this category",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(filteredFiles, key = { it }) { filePath ->
                            val fn = LocalFileManager.getFileName(filePath)
                            val (syntaxBadge, sizeStr, _) = getFileMeta(filePath)
                            val isSelected = filePath in selectedFiles

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(
                                        width = if (isSelected) 1.5.dp else 0.8.dp,
                                        color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .clickable {
                                        if (isSelectionMode) {
                                            if (isSelected) selectedFiles.remove(filePath)
                                            else selectedFiles.add(filePath)
                                        } else {
                                            onOpenFile(filePath)
                                        }
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) ClaudeTerracotta.copy(alpha = 0.10f)
                                    else MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isSelectionMode) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = {
                                                if (isSelected) selectedFiles.remove(filePath)
                                                else selectedFiles.add(filePath)
                                            },
                                            colors = CheckboxDefaults.colors(checkedColor = ClaudeTerracotta)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }

                                    // Syntax Badge Pill
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = ClaudeTerracotta.copy(alpha = 0.15f),
                                        modifier = Modifier.size(42.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = syntaxBadge.take(4),
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 10.sp
                                                ),
                                                color = ClaudeTerracotta
                                            )
                                        }
                                    }

                                    Spacer(Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = fn,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = sizeStr,
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text("·", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                            Text(
                                                text = filePath.takeLast(32),
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontSize = 10.sp,
                                                    fontFamily = FontFamily.Monospace
                                                ),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    // Action buttons for single file
                                    if (!isSelectionMode) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            // Diff viewer button for code/markdown/documents
                                            IconButton(onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                activeDiffFile = filePath
                                            }) {
                                                Icon(
                                                    Icons.Default.Difference,
                                                    contentDescription = "Inspect Diff",
                                                    tint = ClaudeTerracotta,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }

                                            // Copy path button
                                            IconButton(onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                clipboard.setText(AnnotatedString(filePath))
                                                onCopyPath(filePath)
                                                Toast.makeText(context, "Copied file path", Toast.LENGTH_SHORT).show()
                                            }) {
                                                Icon(
                                                    Icons.Default.ContentCopy,
                                                    contentDescription = "Copy Path",
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
            }
        }
    }

    // Interactive Diff Viewer Sheet
    if (activeDiffFile != null) {
        val diffPath = activeDiffFile!!
        val fn = LocalFileManager.getFileName(diffPath)

        AlertDialog(
            onDismissRequest = { activeDiffFile = null },
            modifier = Modifier.fillMaxWidth(0.95f),
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = fn,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Interactive Diff Inspector",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF2E7D32).copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "+ Added  - Removed",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                            color = Color(0xFF2E7D32),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            },
            text = {
                val mockDiffLines = listOf(
                    "@@ -1,8 +1,12 @@",
                    " package com.agychat.app",
                    "-import android.util.Log",
                    "+import kotlinx.coroutines.flow.StateFlow",
                    "+import kotlinx.coroutines.flow.asStateFlow",
                    " ",
                    " class AppRuntimeController {",
                    "-    fun execute() {",
                    "-        println(\"legacy\")",
                    "+    fun execute(scope: CoroutineScope) {",
                    "+        scope.launch {",
                    "+            emitState(RuntimeState.Ready)",
                    "+        }",
                    "     }",
                    " }"
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                    ) {
                        mockDiffLines.forEach { line ->
                            val isAddition = line.startsWith("+")
                            val isRemoval = line.startsWith("-")
                            val isHeader = line.startsWith("@@")

                            val bgColor = when {
                                isAddition -> Color(0xFF2E7D32).copy(alpha = 0.12f)
                                isRemoval -> Color(0xFFC62828).copy(alpha = 0.12f)
                                isHeader -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else -> Color.Transparent
                            }

                            val textColor = when {
                                isAddition -> Color(0xFF2E7D32)
                                isRemoval -> Color(0xFFC62828)
                                isHeader -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurface
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(bgColor, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        fontWeight = if (isAddition || isRemoval || isHeader) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    color = textColor
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        activeDiffFile = null
                        onOpenFile(diffPath)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Text("Open Full File")
                }
            },
            dismissButton = {
                TextButton(onClick = { activeDiffFile = null }) {
                    Text("Close")
                }
            },
            shape = RoundedCornerShape(18.dp)
        )
    }

    // Delete confirmation dialog for multi-select
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete ${selectedFiles.size} Artifacts?", fontWeight = FontWeight.Bold) },
            text = {
                Text("Are you sure you want to permanently remove these files from the current session?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteFiles(selectedFiles.toList())
                        selectedFiles.clear()
                        isSelectionMode = false
                        showDeleteConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(18.dp)
        )
    }
}
