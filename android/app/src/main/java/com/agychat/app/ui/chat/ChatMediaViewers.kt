package com.agychat.app.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agychat.app.ui.theme.ChatGptBlue
import com.agychat.app.ui.theme.ChatGptEmerald
import com.agychat.app.ui.theme.ClaudeTerracotta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 2X Elevated In-App File & Media Viewers for Next AI.
 * Handles markdown documents, source code, images, multi-page PDFs, and binary file exports.
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
                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewMode = if (viewMode == "rendered") "code" else "rendered"
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
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
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
