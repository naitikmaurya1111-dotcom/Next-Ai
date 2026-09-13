package com.agychat.app.data.local

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.agychat.app.domain.model.AttachmentItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalFileManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileDao: FileDao
) {
    private val savedFilesDir = File(context.filesDir, "saved_files").apply { mkdirs() }
    private val attachmentsDir = File(context.filesDir, "attachments").apply { mkdirs() }

    fun normalizeFileId(rawPathOrUrl: String): String {
        var s = rawPathOrUrl.trim()
        if (s.startsWith("file://")) s = s.removePrefix("file://")
        if (s.contains("?path=")) s = s.substringAfter("?path=").substringBefore("&")
        return s.trim()
    }

    fun getFileName(pathOrUrl: String): String {
        val clean = normalizeFileId(pathOrUrl)
        return clean.substringAfterLast("/").ifBlank { "file.txt" }
    }

    private fun detectMimeType(filename: String): String {
        val ext = filename.substringAfterLast(".", "").lowercase()
        return when (ext) {
            "md", "markdown" -> "text/markdown"
            "txt" -> "text/plain"
            "py" -> "text/x-python"
            "kt", "kts" -> "text/x-kotlin"
            "java" -> "text/x-java-source"
            "js", "jsx" -> "application/javascript"
            "ts", "tsx" -> "application/typescript"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "json" -> "application/json"
            "sh", "bash" -> "application/x-sh"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            else -> "text/plain"
        }
    }

    /**
     * Cache or save a file generated or viewed by the AI into phone internal storage.
     * Stored both as a real file on disk and as an indexed entry in Room DB.
     */
    suspend fun cacheFile(
        conversationId: String,
        remotePath: String,
        filename: String? = null,
        content: String
    ): LocalFileEntity = withContext(Dispatchers.IO) {
        val normPath = normalizeFileId(remotePath)
        val resolvedName = filename?.takeIf { it.isNotBlank() } ?: getFileName(normPath)
        val safeDiskName = "${normPath.hashCode().toString().replace("-", "n")}_$resolvedName"
        val localDiskFile = File(savedFilesDir, safeDiskName)

        try {
            localDiskFile.writeText(content, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e("LocalFileManager", "Failed to write local disk file $safeDiskName", e)
        }

        val entity = LocalFileEntity(
            id = normPath,
            conversationId = conversationId,
            filename = resolvedName,
            remotePath = normPath,
            localPath = localDiskFile.absolutePath,
            content = content,
            size = content.toByteArray(Charsets.UTF_8).size.toLong(),
            mimeType = detectMimeType(resolvedName),
            cachedAt = System.currentTimeMillis()
        )

        try {
            fileDao.insertFile(entity)
        } catch (e: Exception) {
            Log.e("LocalFileManager", "Failed to insert cached file entity in Room", e)
        }

        entity
    }

    /**
     * Retrieve cached file from phone storage without needing any network or internet connection.
     */
    suspend fun getCachedFile(pathOrName: String): LocalFileEntity? = withContext(Dispatchers.IO) {
        val norm = normalizeFileId(pathOrName)
        val fromDb = fileDao.findFile(norm, pathOrName)
        if (fromDb != null) {
            val diskFile = File(fromDb.localPath)
            if (diskFile.exists() && fromDb.content.isBlank()) {
                val readContent = try { diskFile.readText(Charsets.UTF_8) } catch (_: Exception) { "" }
                return@withContext fromDb.copy(content = readContent)
            }
            return@withContext fromDb
        }

        // Fallback: check if local disk file matches by name
        val fname = getFileName(norm)
        val matchedFile = savedFilesDir.listFiles()?.firstOrNull { it.name.endsWith("_$fname") || it.name == fname }
        if (matchedFile != null && matchedFile.exists()) {
            val content = try { matchedFile.readText(Charsets.UTF_8) } catch (_: Exception) { "" }
            return@withContext LocalFileEntity(
                id = norm,
                conversationId = "",
                filename = fname,
                remotePath = norm,
                localPath = matchedFile.absolutePath,
                content = content,
                size = matchedFile.length(),
                mimeType = detectMimeType(fname),
                cachedAt = matchedFile.lastModified()
            )
        }

        null
    }

    /**
     * Permanently persist user picked attachments (images/docs) into app's private files
     * so they never expire and remain accessible offline.
     */
    suspend fun saveAttachmentLocally(sourceUri: Uri, originalName: String, isImage: Boolean, mimeType: String?): AttachmentItem = withContext(Dispatchers.IO) {
        try {
            val safeName = originalName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val targetFile = File(attachmentsDir, "${UUID.randomUUID()}_$safeName")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            AttachmentItem(
                uri = Uri.fromFile(targetFile).toString(),
                name = originalName,
                size = targetFile.length(),
                isImage = isImage,
                mimeType = mimeType ?: detectMimeType(originalName)
            )
        } catch (e: Exception) {
            Log.w("LocalFileManager", "Could not copy attachment locally, falling back to original URI", e)
            AttachmentItem(
                uri = sourceUri.toString(),
                name = originalName,
                size = 0L,
                isImage = isImage,
                mimeType = mimeType
            )
        }
    }

    /**
     * Export file directly to device's public Downloads/NextAI directory
     * so other apps (file manager, text editor) can access it directly.
     */
    suspend fun exportToPublicDownloads(filename: String, content: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, detectMimeType(filename))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NextAI")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(content.toByteArray(Charsets.UTF_8))
                    }
                    Pair(true, "Saved to Downloads/NextAI/$filename")
                } else {
                    Pair(false, "Could not create file in Downloads")
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(downloadsDir, "NextAI").apply { mkdirs() }
                val targetFile = File(targetDir, filename)
                targetFile.writeText(content, Charsets.UTF_8)
                Pair(true, "Saved to Downloads/NextAI/$filename")
            }
        } catch (e: Exception) {
            Log.e("LocalFileManager", "Error exporting to public downloads", e)
            Pair(false, "Export failed: ${e.message}")
        }
    }
}
