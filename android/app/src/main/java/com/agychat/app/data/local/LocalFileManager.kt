package com.agychat.app.data.local

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.agychat.app.domain.model.AttachmentItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

object StoragePermissions {
    fun getPermissions(): Array<String> {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO,
                android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            )
            else -> arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
    }

    fun hasWritePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ uses Scoped Storage (MediaStore.Downloads) requiring no write permission
            true
        } else {
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasReadPermission(context: Context): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                val hasSelected = ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                ) == PackageManager.PERMISSION_GRANTED
                val hasImages = ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED
                val hasVideo = ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.READ_MEDIA_VIDEO
                ) == PackageManager.PERMISSION_GRANTED
                val hasAudio = ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.READ_MEDIA_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                hasSelected || hasImages || hasVideo || hasAudio
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                val hasImages = ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED
                val hasVideo = ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.READ_MEDIA_VIDEO
                ) == PackageManager.PERMISSION_GRANTED
                val hasAudio = ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.READ_MEDIA_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                hasImages || hasVideo || hasAudio
            }
            else -> {
                ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    fun hasPermission(context: Context): Boolean {
        return hasReadPermission(context) && hasWritePermission(context)
    }

    fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("StoragePermissions", "Failed to open app settings", e)
        }
    }
}

@Singleton
class LocalFileManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileDao: FileDao,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "LocalFileManager"
        private const val MAX_TEXT_FILE_READ_BYTES = 5 * 1024 * 1024 // 5MB limit for reading text into memory

        fun normalizeFileId(rawPathOrUrl: String): String {
            var s = rawPathOrUrl.trim()
            if (s.startsWith("[") && s.contains("](") && s.endsWith(")")) {
                s = s.substringAfter("](").removeSuffix(")")
            }
            if (s.contains("#")) {
                s = s.substringBefore("#")
            }
            if (s.contains("?path=")) {
                s = s.substringAfter("?path=").substringBefore("&")
            } else if (s.contains("?")) {
                s = s.substringBefore("?")
            }
            if (s.startsWith("file://")) s = s.removePrefix("file://")
            try {
                s = java.net.URLDecoder.decode(s, "UTF-8")
            } catch (_: Throwable) {}
            s = s.trim()
                .trimEnd('.', ',', ':', ';', ')', ']', '}', '\'', '"', '>', '`')
                .trimStart('(', '[', '{', '\'', '"', '<', '`')
            if (!s.startsWith("/") && !s.startsWith("http://") && !s.startsWith("https://") && !s.startsWith("content://")) {
                if (s.startsWith("content/") || s.startsWith("drive/") || s.startsWith("root/") || s.startsWith("tmp/")) {
                    s = "/$s"
                }
            }
            return s.trim()
        }

        fun getFileName(pathOrUrl: String): String {
            val clean = normalizeFileId(pathOrUrl)
            return clean.substringAfterLast("/").ifBlank { "file.txt" }
        }

        fun isBinaryFile(mimeType: String, filename: String): Boolean {
            if (mimeType == "application/pdf" ||
                mimeType.startsWith("image/") ||
                mimeType.startsWith("audio/") ||
                mimeType.startsWith("video/") ||
                mimeType.contains("openxmlformats") ||
                mimeType.contains("msword") ||
                mimeType.contains("zip") ||
                mimeType.contains("tar") ||
                mimeType.contains("gzip") ||
                mimeType == "application/octet-stream"
            ) {
                return true
            }
            val ext = filename.substringAfterLast(".", "").lowercase()
            return ext in setOf(
                "pdf", "png", "jpg", "jpeg", "webp", "gif", "bmp", "ico",
                "docx", "doc", "xlsx", "xls", "pptx", "ppt",
                "zip", "tar", "gz", "7z", "rar",
                "mp3", "wav", "ogg", "m4a", "mp4", "mov", "avi", "mkv",
                "bin", "wasm", "exe", "so", "dylib", "apk"
            )
        }

        fun detectMimeType(filename: String): String {
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
                "gif" -> "image/gif"
                "svg" -> "image/svg+xml"
                "pdf" -> "application/pdf"
                "doc" -> "application/msword"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "xls" -> "application/vnd.ms-excel"
                "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                "ppt" -> "application/vnd.ms-powerpoint"
                "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                "zip" -> "application/zip"
                "tar" -> "application/x-tar"
                "gz" -> "application/gzip"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "mp4" -> "video/mp4"
                else -> "application/octet-stream"
            }
        }
    }

    private val savedFilesDir = File(context.filesDir, "saved_files").apply { mkdirs() }
    private val attachmentsDir = File(context.filesDir, "attachments").apply { mkdirs() }

    private val downloadHttpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun normalizeFileId(rawPathOrUrl: String): String = Companion.normalizeFileId(rawPathOrUrl)
    fun getFileName(pathOrUrl: String): String = Companion.getFileName(pathOrUrl)
    fun isBinaryFile(mimeType: String, filename: String): Boolean = Companion.isBinaryFile(mimeType, filename)
    fun detectMimeType(filename: String): String = Companion.detectMimeType(filename)

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
            val tempFile = File(savedFilesDir, "${safeDiskName}.tmp")
            tempFile.writeText(content, Charsets.UTF_8)
            if (localDiskFile.exists()) localDiskFile.delete()
            if (!tempFile.renameTo(localDiskFile)) {
                tempFile.copyTo(localDiskFile, overwrite = true)
                tempFile.delete()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to write local disk file $safeDiskName", t)
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
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to insert cached file entity in Room", t)
        }

        entity
    }

    /**
     * Cache binary files (PDFs, images, archives) directly as bytes on phone internal storage.
     */
    suspend fun cacheBinaryFile(
        conversationId: String,
        remotePath: String,
        filename: String? = null,
        bytes: ByteArray
    ): LocalFileEntity = withContext(Dispatchers.IO) {
        val normPath = normalizeFileId(remotePath)
        val resolvedName = filename?.takeIf { it.isNotBlank() } ?: getFileName(normPath)
        val safeDiskName = "${normPath.hashCode().toString().replace("-", "n")}_$resolvedName"
        val localDiskFile = File(savedFilesDir, safeDiskName)

        try {
            val tempFile = File(savedFilesDir, "${safeDiskName}.tmp")
            tempFile.writeBytes(bytes)
            if (localDiskFile.exists()) localDiskFile.delete()
            if (!tempFile.renameTo(localDiskFile)) {
                tempFile.copyTo(localDiskFile, overwrite = true)
                tempFile.delete()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to write local binary disk file $safeDiskName", t)
        }

        val entity = LocalFileEntity(
            id = normPath,
            conversationId = conversationId,
            filename = resolvedName,
            remotePath = normPath,
            localPath = localDiskFile.absolutePath,
            content = "",
            size = bytes.size.toLong(),
            mimeType = detectMimeType(resolvedName),
            cachedAt = System.currentTimeMillis()
        )

        try {
            fileDao.insertFile(entity)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to insert cached binary file entity in Room", t)
        }

        entity
    }

    /**
     * Retrieve cached file from phone storage without needing any network or internet connection.
     * Searches Room DB index and disk files, guaranteeing instant offline retrieval.
     */
    suspend fun getCachedFile(pathOrName: String): LocalFileEntity? = withContext(Dispatchers.IO) {
        val norm = normalizeFileId(pathOrName)
        val fname = getFileName(norm)

        // 1. Primary lookup in Room DB
        val fromDb = try {
            fileDao.findFileWithFallback(norm, pathOrName, fname) ?: fileDao.findFile(norm, pathOrName)
        } catch (t: Throwable) {
            Log.w(TAG, "Error querying fileDao for $pathOrName", t)
            null
        }

        if (fromDb != null) {
            val diskFile = File(fromDb.localPath)
            val isBin = isBinaryFile(fromDb.mimeType, fromDb.filename)

            if (diskFile.exists()) {
                if (!isBin && fromDb.content.isBlank() && diskFile.length() <= MAX_TEXT_FILE_READ_BYTES) {
                    val readContent = try { diskFile.readText(Charsets.UTF_8) } catch (_: Throwable) { "" }
                    return@withContext fromDb.copy(content = readContent)
                }
                return@withContext fromDb
            } else if (fromDb.content.isNotBlank()) {
                // Restore disk file from DB content if it was deleted
                try {
                    diskFile.writeText(fromDb.content, Charsets.UTF_8)
                } catch (_: Throwable) {}
                return@withContext fromDb
            }
        }

        // 2. Secondary fallback: Scan saved_files and attachments directories for matching filename
        val diskCandidate = listOf(savedFilesDir, attachmentsDir).asSequence()
            .mapNotNull { dir ->
                dir.listFiles()?.firstOrNull { f ->
                    f.name.endsWith("_$fname") || f.name == fname || f.name.contains(fname)
                }
            }.firstOrNull()

        if (diskCandidate != null && diskCandidate.exists()) {
            val isBin = isBinaryFile(detectMimeType(fname), fname)
            val textContent = if (!isBin && diskCandidate.length() <= MAX_TEXT_FILE_READ_BYTES) {
                try { diskCandidate.readText(Charsets.UTF_8) } catch (_: Throwable) { "" }
            } else ""

            val entity = LocalFileEntity(
                id = norm,
                conversationId = "",
                filename = fname,
                remotePath = norm,
                localPath = diskCandidate.absolutePath,
                content = textContent,
                size = diskCandidate.length(),
                mimeType = detectMimeType(fname),
                cachedAt = diskCandidate.lastModified()
            )
            try { fileDao.insertFile(entity) } catch (_: Throwable) {}
            return@withContext entity
        }

        null
    }

    /**
     * Stream download large files (PDFs, binaries, outputs) directly to disk cache.
     * Uses constant small buffer to prevent OOM on large files.
     */
    suspend fun downloadAndCacheRemoteFile(
        downloadUrl: String,
        remotePath: String,
        conversationId: String
    ): LocalFileEntity? = withContext(Dispatchers.IO) {
        val normPath = normalizeFileId(remotePath)
        val resolvedName = getFileName(normPath)
        val safeDiskName = "${normPath.hashCode().toString().replace("-", "n")}_$resolvedName"
        val localDiskFile = File(savedFilesDir, safeDiskName)
        val tempFile = File(savedFilesDir, "${safeDiskName}.tmp")

        try {
            val request = Request.Builder().url(downloadUrl).build()
            val response = downloadHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Download failed for $downloadUrl: HTTP ${response.code}")
                return@withContext null
            }

            val body = response.body ?: return@withContext null
            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                }
            }

            if (localDiskFile.exists()) localDiskFile.delete()
            if (!tempFile.renameTo(localDiskFile)) {
                tempFile.copyTo(localDiskFile, overwrite = true)
                tempFile.delete()
            }

            val mime = detectMimeType(resolvedName)
            val isBin = isBinaryFile(mime, resolvedName)
            val textContent = if (!isBin && localDiskFile.length() <= MAX_TEXT_FILE_READ_BYTES) {
                try { localDiskFile.readText(Charsets.UTF_8) } catch (_: Throwable) { "" }
            } else ""

            val entity = LocalFileEntity(
                id = normPath,
                conversationId = conversationId,
                filename = resolvedName,
                remotePath = normPath,
                localPath = localDiskFile.absolutePath,
                content = textContent,
                size = localDiskFile.length(),
                mimeType = mime,
                cachedAt = System.currentTimeMillis()
            )

            try {
                fileDao.insertFile(entity)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to insert downloaded file entity into Room", t)
            }

            entity
        } catch (t: Throwable) {
            Log.e(TAG, "Error streaming download from $downloadUrl", t)
            if (tempFile.exists()) tempFile.delete()
            null
        }
    }

    /**
     * Fetch file from Colab bridge via HTTP API and cache it locally in persistent storage.
     * Delegates large files (> 2MB or marked 'too_large') to direct HTTP streaming download.
     */
    suspend fun fetchAndCacheColabFile(
        serverBaseUrl: String,
        remotePath: String,
        conversationId: String
    ): LocalFileEntity? = withContext(Dispatchers.IO) {
        val normPath = normalizeFileId(remotePath)
        val filename = getFileName(normPath)
        val cleanBase = serverBaseUrl.removeSuffix("/").removeSuffix("/ws")
        val fullUrl = "$cleanBase/api/file?path=${Uri.encode(normPath)}"

        try {
            val request = Request.Builder().url(fullUrl).build()
            val response = downloadHttpClient.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "HTTP /api/file failed with code ${resp.code} for $normPath")
                    return@withContext null
                }
                val bodyStr = resp.body?.string() ?: return@withContext null
                val json = JSONObject(bodyStr)
                val status = json.optString("status")

                if (status == "too_large") {
                    val serverDlUrl = json.optString("download_url", "")
                    val dlUrl = if (serverDlUrl.isNotBlank()) {
                        if (serverDlUrl.startsWith("http")) serverDlUrl else "$cleanBase$serverDlUrl"
                    } else {
                        "$cleanBase/api/file/download?path=${Uri.encode(normPath)}"
                    }
                    return@withContext downloadAndCacheRemoteFile(dlUrl, normPath, conversationId)
                } else if (status == "ok" || status == "success") {
                    val fName = json.optString("filename", filename)
                    val fPath = json.optString("path", normPath)
                    val isBinary = json.optBoolean("is_binary", false)

                    if (isBinary) {
                        val b64 = json.optString("base64_content", "")
                        val bytes = try { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) } catch (_: Throwable) { null }
                        if (bytes != null) {
                            return@withContext cacheBinaryFile(conversationId, fPath, fName, bytes)
                        }
                    } else {
                        val content = json.optString("content", "")
                        return@withContext cacheFile(conversationId, fPath, fName, content)
                    }
                }
            }
            null
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to fetch and cache Colab file $normPath: ${t.message}")
            null
        }
    }

    /**
     * Uploads large attachments (PDFs, docs, large files >1.5MB) directly to Colab bridge via HTTP multipart.
     * Prevents Base64 payload bloat, WebSocket buffer overflow, and frame rejection.
     * Returns server path (e.g. /tmp/uploads/1726..._doc.pdf) on success.
     */
    suspend fun uploadAttachmentToBridge(
        bridgeUrl: String,
        uri: Uri,
        fileName: String,
        mimeType: String?
    ): String? = withContext(Dispatchers.IO) {
        val base = bridgeUrl.trim().trimEnd('/')
        if (base.isBlank()) return@withContext null

        val httpUrl = when {
            base.startsWith("ws://") -> base.replace("ws://", "http://")
            base.startsWith("wss://") -> base.replace("wss://", "https://")
            !base.startsWith("http") -> "https://$base"
            else -> base
        }
        val uploadUrl = "$httpUrl/upload"

        val tempFile = File.createTempFile("up_", "_${fileName.take(30)}", context.cacheDir)
        try {
            val inputStream = try {
                context.contentResolver.openInputStream(uri)
            } catch (_: Throwable) {
                if (uri.scheme == "file") File(uri.path ?: "").inputStream() else null
            } ?: return@withContext null

            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            val mediaType = (mimeType ?: "application/octet-stream").toMediaTypeOrNull()
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    fileName,
                    tempFile.asRequestBody(mediaType)
                )
                .build()

            val request = Request.Builder()
                .url(uploadUrl)
                .post(requestBody)
                .build()

            val uploadClient = okHttpClient.newBuilder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()

            val response = uploadClient.newCall(request).execute()
            if (response.isSuccessful) {
                val respStr = response.body?.string() ?: ""
                val json = JSONObject(respStr)
                val serverPath = json.optString("server_path", json.optString("path", ""))
                Log.i(TAG, "Uploaded $fileName (${tempFile.length()} bytes) to Colab bridge: $serverPath")
                if (serverPath.isNotBlank()) serverPath else null
            } else {
                Log.w(TAG, "Upload failed for $fileName: HTTP ${response.code} ${response.message}")
                null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Exception uploading attachment $fileName to $uploadUrl", t)
            null
        } finally {
            try { tempFile.delete() } catch (_: Throwable) {}
        }
    }

    /**
     * Permanently persist user picked attachments (images/docs) into app's private files
     * and Room DB index so they never expire and remain accessible offline.
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

            val resolvedMime = mimeType ?: detectMimeType(originalName)

            // Register attachment in local_files Room DB for persistent offline tracking
            try {
                val entity = LocalFileEntity(
                    id = Uri.fromFile(targetFile).toString(),
                    conversationId = "",
                    filename = originalName,
                    remotePath = Uri.fromFile(targetFile).toString(),
                    localPath = targetFile.absolutePath,
                    content = "",
                    size = targetFile.length(),
                    mimeType = resolvedMime,
                    cachedAt = System.currentTimeMillis()
                )
                fileDao.insertFile(entity)
            } catch (t: Throwable) {
                Log.w(TAG, "Could not index attachment in Room DB", t)
            }

            AttachmentItem(
                uri = Uri.fromFile(targetFile).toString(),
                name = originalName,
                size = targetFile.length(),
                isImage = isImage,
                mimeType = resolvedMime
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Could not copy attachment locally, falling back to original URI", t)
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
        } catch (t: Throwable) {
            Log.e(TAG, "Error exporting to public downloads", t)
            Pair(false, "Export failed: ${t.message}")
        }
    }

    /**
     * Export binary data (PDF, images, etc.) directly to public Downloads/NextAI directory.
     */
    suspend fun exportToPublicDownloads(filename: String, bytes: ByteArray): Pair<Boolean, String> = withContext(Dispatchers.IO) {
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
                        out.write(bytes)
                    }
                    Pair(true, "Saved to Downloads/NextAI/$filename")
                } else {
                    Pair(false, "Could not create file in Downloads")
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(downloadsDir, "NextAI").apply { mkdirs() }
                val targetFile = File(targetDir, filename)
                targetFile.writeBytes(bytes)
                Pair(true, "Saved to Downloads/NextAI/$filename")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error exporting binary to public downloads", t)
            Pair(false, "Export failed: ${t.message}")
        }
    }

    /**
     * Export a local disk file (PDF, binary, image, text) directly to device's public Downloads/NextAI directory
     * using streaming copy to prevent any OutOfMemoryError.
     */
    suspend fun exportToPublicDownloads(sourceFile: File, filename: String = sourceFile.name): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            if (!sourceFile.exists()) {
                return@withContext Pair(false, "Source file does not exist on device")
            }
            val resolvedName = filename.ifBlank { sourceFile.name }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, resolvedName)
                    put(MediaStore.MediaColumns.MIME_TYPE, detectMimeType(resolvedName))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NextAI")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    try {
                        resolver.openOutputStream(uri)?.use { out ->
                            sourceFile.inputStream().use { input ->
                                input.copyTo(out)
                            }
                        }
                        Pair(true, "Saved to Downloads/NextAI/$resolvedName")
                    } catch (e: Throwable) {
                        try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
                        Pair(false, "Failed to write to Downloads: ${e.message}")
                    }
                } else {
                    Pair(false, "Could not create file in Downloads")
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(downloadsDir, "NextAI").apply { mkdirs() }
                val targetFile = File(targetDir, resolvedName)
                sourceFile.inputStream().use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Pair(true, "Saved to Downloads/NextAI/$resolvedName")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error exporting file to public downloads", t)
            Pair(false, "Export failed: ${t.message}")
        }
    }

    /**
     * Get a shareable content:// URI for a file using Android FileProvider.
     */
    fun getUriForFile(file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    /**
     * Open any file (PDF, docx, image, code) in an external viewer app installed on device.
     */
    fun openFileInExternalApp(context: Context, file: File): Pair<Boolean, String> {
        return try {
            val contentUri = getUriForFile(file)
            val mime = detectMimeType(file.name)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "Open ${file.name} with...").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            Pair(true, "Opened in viewer")
        } catch (t: Throwable) {
            Log.w(TAG, "No external app found to open file", t)
            Pair(false, "No app available to open this file type (${file.extension})")
        }
    }

    /**
     * Delete cached files from local disk and remove records from Room DB.
     */
    suspend fun deleteCachedFiles(pathsOrIds: List<String>): Int = withContext(Dispatchers.IO) {
        var count = 0
        val idsToDelete = mutableListOf<String>()
        for (raw in pathsOrIds) {
            try {
                val norm = normalizeFileId(raw)
                val fn = getFileName(norm)
                val safeDiskName = "${norm.hashCode().toString().replace("-", "n")}_$fn"
                File(savedFilesDir, safeDiskName).takeIf { it.exists() }?.delete()
                File(savedFilesDir, fn).takeIf { it.exists() }?.delete()
                File(savedFilesDir, norm).takeIf { it.exists() }?.delete()
                idsToDelete.add(raw)
                idsToDelete.add(norm)
                idsToDelete.add(fn)
                count++
            } catch (t: Throwable) {
                Log.w(TAG, "Error deleting local file for $raw", t)
            }
        }
        if (idsToDelete.isNotEmpty()) {
            try {
                fileDao.deleteFiles(idsToDelete.distinct())
            } catch (t: Throwable) {
                Log.w(TAG, "Error deleting records from fileDao", t)
            }
        }
        count
    }

    /**
     * Locate existing file on local phone disk from various path or name representations.
     */
    fun getLocalFileOnDisk(pathOrName: String): File? {
        val norm = normalizeFileId(pathOrName)
        val fn = getFileName(norm)
        val safeDiskName = "${norm.hashCode().toString().replace("-", "n")}_$fn"
        val exactFile = File(savedFilesDir, safeDiskName)
        if (exactFile.exists() && exactFile.length() > 0) return exactFile
        val byName = File(savedFilesDir, fn)
        if (byName.exists() && byName.length() > 0) return byName
        val byNorm = File(savedFilesDir, norm)
        if (byNorm.exists() && byNorm.length() > 0) return byNorm
        val direct = File(pathOrName)
        if (direct.exists() && direct.length() > 0) return direct
        return listOf(savedFilesDir, attachmentsDir).asSequence()
            .mapNotNull { dir ->
                dir.listFiles()?.firstOrNull { f ->
                    (f.name.endsWith("_$fn") || f.name == fn || f.name.contains(fn)) && f.length() > 0
                }
            }.firstOrNull()
    }

    /**
     * Batch export multiple files to the device's public Downloads directory.
     */
    suspend fun batchExportToDownloads(paths: List<String>): Pair<Int, String> = withContext(Dispatchers.IO) {
        var successCount = 0
        for (p in paths) {
            try {
                val norm = normalizeFileId(p)
                val fn = getFileName(norm)
                val candidate = getLocalFileOnDisk(p)
                if (candidate != null && candidate.exists() && candidate.length() > 0) {
                    val res = exportToPublicDownloads(candidate, fn)
                    if (res.first) successCount++
                } else {
                    val entity = getCachedFile(p)
                    if (entity != null) {
                        val diskFile = File(entity.localPath)
                        if (diskFile.exists() && diskFile.length() > 0) {
                            val res = exportToPublicDownloads(diskFile, fn)
                            if (res.first) successCount++
                        } else if (entity.content.isNotBlank()) {
                            val res = exportToPublicDownloads(fn, entity.content)
                            if (res.first) successCount++
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to batch export $p", t)
            }
        }
        val msg = if (successCount > 0) {
            "Exported $successCount of ${paths.size} files to Downloads/NextAI"
        } else {
            "No files could be exported"
        }
        Pair(successCount, msg)
    }
}
