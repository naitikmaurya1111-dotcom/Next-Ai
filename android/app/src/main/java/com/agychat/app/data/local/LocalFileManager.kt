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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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

        // High-performance static extension-to-MIME lookup table (O(1) resolution)
        private val EXTENSION_TO_MIME = HashMap<String, String>(128).apply {
            // Text & Markdown
            put("txt", "text/plain")
            put("text", "text/plain")
            put("log", "text/plain")
            put("md", "text/markdown")
            put("markdown", "text/markdown")

            // Programming & Scripts
            put("py", "text/x-python")
            put("kt", "text/x-kotlin")
            put("kts", "text/x-kotlin")
            put("java", "text/x-java-source")
            put("js", "application/javascript")
            put("jsx", "application/javascript")
            put("mjs", "application/javascript")
            put("cjs", "application/javascript")
            put("ts", "application/typescript")
            put("tsx", "application/typescript")
            put("c", "text/x-c")
            put("cpp", "text/x-c++")
            put("cc", "text/x-c++")
            put("cxx", "text/x-c++")
            put("h", "text/x-c-header")
            put("hpp", "text/x-c++-header")
            put("cs", "text/x-csharp")
            put("go", "text/x-go")
            put("rs", "text/x-rust")
            put("rb", "text/x-ruby")
            put("php", "application/x-httpd-php")
            put("swift", "text/x-swift")
            put("sh", "application/x-sh")
            put("bash", "application/x-sh")
            put("zsh", "application/x-sh")
            put("sql", "application/sql")
            put("r", "text/x-r")
            put("dart", "text/x-dart")
            put("scala", "text/x-scala")
            put("lua", "text/x-lua")

            // Web & Markup & Configs
            put("html", "text/html")
            put("htm", "text/html")
            put("css", "text/css")
            put("scss", "text/x-scss")
            put("sass", "text/x-sass")
            put("less", "text/x-less")
            put("json", "application/json")
            put("xml", "application/xml")
            put("yaml", "text/yaml")
            put("yml", "text/yaml")
            put("toml", "application/toml")
            put("ini", "text/plain")
            put("properties", "text/plain")
            put("gradle", "text/x-groovy")
            put("env", "text/plain")

            // Images
            put("jpg", "image/jpeg")
            put("jpeg", "image/jpeg")
            put("png", "image/png")
            put("webp", "image/webp")
            put("gif", "image/gif")
            put("svg", "image/svg+xml")
            put("bmp", "image/bmp")
            put("ico", "image/x-icon")
            put("heic", "image/heic")
            put("heif", "image/heif")
            put("avif", "image/avif")
            put("tiff", "image/tiff")
            put("tif", "image/tiff")

            // Documents
            put("pdf", "application/pdf")
            put("doc", "application/msword")
            put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
            put("xls", "application/vnd.ms-excel")
            put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            put("ppt", "application/vnd.ms-powerpoint")
            put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation")
            put("csv", "text/csv")
            put("tsv", "text/tab-separated-values")
            put("rtf", "application/rtf")
            put("epub", "application/epub+zip")

            // Archives
            put("zip", "application/zip")
            put("tar", "application/x-tar")
            put("gz", "application/gzip")
            put("7z", "application/x-7z-compressed")
            put("rar", "application/vnd.rar")
            put("bz2", "application/x-bzip2")
            put("xz", "application/x-xz")

            // Audio
            put("mp3", "audio/mpeg")
            put("wav", "audio/wav")
            put("ogg", "audio/ogg")
            put("m4a", "audio/mp4")
            put("flac", "audio/flac")
            put("aac", "audio/aac")
            put("opus", "audio/opus")

            // Video
            put("mp4", "video/mp4")
            put("mkv", "video/x-matroska")
            put("webm", "video/webm")
            put("mov", "video/quicktime")
            put("avi", "video/x-msvideo")
            put("flv", "video/x-flv")

            // Binaries & Executables
            put("wasm", "application/wasm")
            put("bin", "application/octet-stream")
            put("exe", "application/octet-stream")
            put("so", "application/octet-stream")
            put("dylib", "application/octet-stream")
            put("apk", "application/vnd.android.package-archive")
        }

        private val BINARY_EXTENSIONS = setOf(
            "pdf", "png", "jpg", "jpeg", "webp", "gif", "bmp", "ico", "heic", "heif", "avif", "tiff", "tif",
            "docx", "doc", "xlsx", "xls", "pptx", "ppt", "odt", "ods", "odp",
            "zip", "tar", "gz", "7z", "rar", "bz2", "xz", "zst", "tgz",
            "mp3", "wav", "ogg", "m4a", "flac", "aac", "opus", "wma",
            "mp4", "mov", "avi", "mkv", "webm", "flv", "wmv", "3gp",
            "bin", "wasm", "exe", "so", "dylib", "apk", "aab", "aar", "jar", "class", "pyc", "dex", "o"
        )

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
            val cleanName = filename.substringBefore("?").substringBefore("#")
            val ext = cleanName.substringAfterLast(".", "").lowercase()
            return ext in BINARY_EXTENSIONS
        }

        fun detectMimeType(filename: String): String {
            val cleanName = filename.substringBefore("?").substringBefore("#")
            val ext = cleanName.substringAfterLast(".", "").lowercase()
            if (ext.isEmpty()) return "application/octet-stream"

            // 1. Fast O(1) table lookup
            val known = EXTENSION_TO_MIME[ext]
            if (known != null) return known

            // 2. Android system MimeTypeMap fallback
            val systemMime = try {
                android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            } catch (_: Throwable) { null }

            return systemMime?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        }
    }

    // In-memory hot cache for zero-lag instant file retrieval
    private val memoryCache = ConcurrentHashMap<String, LocalFileEntity>()

    private fun putInMemoryCache(key: String, entity: LocalFileEntity) {
        if (key.isNotBlank()) {
            if (memoryCache.size > 128) {
                // Drop a random entry to maintain bounded memory footprint
                val iter = memoryCache.keys().iterator()
                if (iter.hasNext()) memoryCache.remove(iter.next())
            }
            memoryCache[key] = entity
        }
    }

    /**
     * Robust directory creation with automatic parent verification and fallback.
     */
    private fun ensureDirectory(dir: File): File {
        if (!dir.exists()) {
            try {
                val created = dir.mkdirs()
                if (!created && !dir.exists()) {
                    dir.mkdir()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed ensuring directory: ${dir.absolutePath}", t)
            }
        }
        return dir
    }

    fun getSavedFilesDir(): File {
        val primary = File(context.filesDir, "saved_files")
        ensureDirectory(primary)
        if (!primary.exists() || !primary.canWrite()) {
            val fallback = File(context.cacheDir, "saved_files")
            ensureDirectory(fallback)
            return fallback
        }
        return primary
    }

    fun getAttachmentsDir(): File {
        val primary = File(context.filesDir, "attachments")
        ensureDirectory(primary)
        if (!primary.exists() || !primary.canWrite()) {
            val fallback = File(context.cacheDir, "attachments")
            ensureDirectory(fallback)
            return fallback
        }
        return primary
    }


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
     * Atomic file writing with safe temp file swap and file descriptor sync.
     */
    private fun safeWriteFile(targetFile: File, writeAction: (File) -> Unit): Boolean {
        val parentDir = targetFile.parentFile ?: return false
        ensureDirectory(parentDir)
        val tempFile = File(parentDir, "${targetFile.name}.tmp_${System.nanoTime()}")
        return try {
            writeAction(tempFile)
            if (tempFile.exists() && tempFile.length() > 0) {
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                val renamed = tempFile.renameTo(targetFile)
                if (!renamed) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
                true
            } else {
                tempFile.delete()
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed safe write for ${targetFile.absolutePath}", t)
            try { if (tempFile.exists()) tempFile.delete() } catch (_: Throwable) {}
            false
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
        val localDiskFile = File(getSavedFilesDir(), safeDiskName)

        safeWriteFile(localDiskFile) { tmp ->
            FileOutputStream(tmp).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.fd.sync()
            }
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

        putInMemoryCache(normPath, entity)
        putInMemoryCache(resolvedName, entity)

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
        val localDiskFile = File(getSavedFilesDir(), safeDiskName)

        safeWriteFile(localDiskFile) { tmp ->
            FileOutputStream(tmp).use { fos ->
                fos.write(bytes)
                fos.fd.sync()
            }
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

        putInMemoryCache(normPath, entity)
        putInMemoryCache(resolvedName, entity)

        try {
            fileDao.insertFile(entity)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to insert cached binary file entity in Room", t)
        }

        entity
    }

    /**
     * Retrieve cached file from phone storage without needing any network or internet connection.
     * Uses tiered lookup: In-Memory Hot Cache -> Room DB Index -> Disk Scan with Self-Healing.
     */
    suspend fun getCachedFile(pathOrName: String): LocalFileEntity? = withContext(Dispatchers.IO) {
        val norm = normalizeFileId(pathOrName)
        val fname = getFileName(norm)

        // Tier 0: In-Memory Hot Cache (0ms latency)
        memoryCache[norm]?.let { return@withContext it }
        memoryCache[fname]?.let { return@withContext it }
        memoryCache[pathOrName]?.let { return@withContext it }

        // Tier 1: Primary lookup in Room DB
        val fromDb = try {
            fileDao.findFileWithFallback(norm, pathOrName, fname) ?: fileDao.findFile(norm, pathOrName)
        } catch (t: Throwable) {
            Log.w(TAG, "Error querying fileDao for $pathOrName", t)
            null
        }

        if (fromDb != null) {
            val diskFile = File(fromDb.localPath)
            val isBin = isBinaryFile(fromDb.mimeType, fromDb.filename)

            if (diskFile.exists() && diskFile.length() > 0) {
                if (!isBin && fromDb.content.isBlank() && diskFile.length() <= MAX_TEXT_FILE_READ_BYTES) {
                    val readContent = try { diskFile.readText(Charsets.UTF_8) } catch (_: Throwable) { "" }
                    val hydrated = fromDb.copy(content = readContent)
                    putInMemoryCache(norm, hydrated)
                    return@withContext hydrated
                }
                putInMemoryCache(norm, fromDb)
                return@withContext fromDb
            } else if (fromDb.content.isNotBlank()) {
                // Self-healing: Restore disk file from DB content if it was deleted
                safeWriteFile(diskFile) { tmp ->
                    FileOutputStream(tmp).use { fos ->
                        fos.write(fromDb.content.toByteArray(Charsets.UTF_8))
                        fos.fd.sync()
                    }
                }
                putInMemoryCache(norm, fromDb)
                return@withContext fromDb
            }
        }

        // Tier 2: Secondary fallback: Scan saved_files and attachments directories for matching filename
        val diskCandidate = listOf(getSavedFilesDir(), getAttachmentsDir()).asSequence()
            .mapNotNull { dir ->
                dir.listFiles()?.firstOrNull { f ->
                    (f.name.endsWith("_$fname") || f.name == fname || f.name.contains(fname)) && f.length() > 0
                }
            }.firstOrNull()

        if (diskCandidate != null && diskCandidate.exists() && diskCandidate.length() > 0) {
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
            // Self-healing: index discovered disk file into Room DB
            try { fileDao.insertFile(entity) } catch (_: Throwable) {}
            putInMemoryCache(norm, entity)
            return@withContext entity
        }

        null
    }

    /**
     * Stream download large files (PDFs, binaries, outputs) directly to disk cache.
     * Uses constant small buffer to prevent OOM on large files, with automatic retry.
     */
    suspend fun downloadAndCacheRemoteFile(
        downloadUrl: String,
        remotePath: String,
        conversationId: String
    ): LocalFileEntity? = withContext(Dispatchers.IO) {
        val normPath = normalizeFileId(remotePath)
        val resolvedName = getFileName(normPath)
        val safeDiskName = "${normPath.hashCode().toString().replace("-", "n")}_$resolvedName"
        val savedDir = getSavedFilesDir()
        val localDiskFile = File(savedDir, safeDiskName)
        val tempFile = File(savedDir, "${safeDiskName}.tmp_${System.nanoTime()}")

        try {
            var response: okhttp3.Response? = null
            var attempts = 0
            while (attempts < 3) {
                attempts++
                try {
                    val request = Request.Builder().url(downloadUrl).build()
                    val callResp = downloadHttpClient.newCall(request).execute()
                    if (callResp.isSuccessful) {
                        response = callResp
                        break
                    } else {
                        callResp.close()
                    }
                } catch (e: Throwable) {
                    if (attempts >= 3) throw e
                    delay(attempts * 1000L)
                }
            }

            val resp = response ?: return@withContext null
            resp.use { r ->
                if (!r.isSuccessful) {
                    Log.w(TAG, "Download failed for $downloadUrl: HTTP ${r.code}")
                    return@withContext null
                }

                val body = r.body ?: return@withContext null
                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
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

            putInMemoryCache(normPath, entity)
            putInMemoryCache(resolvedName, entity)

            try {
                fileDao.insertFile(entity)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to insert downloaded file entity into Room", t)
            }

            entity
        } catch (t: Throwable) {
            Log.e(TAG, "Error streaming download from $downloadUrl", t)
            null
        } finally {
            try { if (tempFile.exists()) tempFile.delete() } catch (_: Throwable) {}
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
     * Uploads large attachments directly to Colab bridge via HTTP multipart.
     * Prevents Base64 payload bloat, WebSocket buffer overflow, and frame rejection.
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
                    output.fd.sync()
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
            val targetFile = File(getAttachmentsDir(), "${UUID.randomUUID()}_$safeName")

            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
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
                putInMemoryCache(targetFile.absolutePath, entity)
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
     * Export file directly to device's public Downloads/NextAI directory.
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
                val targetDir = File(downloadsDir, "NextAI")
                ensureDirectory(targetDir)
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
                val targetDir = File(downloadsDir, "NextAI")
                ensureDirectory(targetDir)
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
     * Export a local disk file directly to device's public Downloads/NextAI directory
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
                val targetDir = File(downloadsDir, "NextAI")
                ensureDirectory(targetDir)
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
     * Open any file in an external viewer app installed on device.
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
     * Delete cached files from local disk, clear memory cache, and remove records from Room DB.
     */
    suspend fun deleteCachedFiles(pathsOrIds: List<String>): Int = withContext(Dispatchers.IO) {
        var count = 0
        val idsToDelete = mutableListOf<String>()
        val savedDir = getSavedFilesDir()

        for (raw in pathsOrIds) {
            try {
                val norm = normalizeFileId(raw)
                val fn = getFileName(norm)
                val safeDiskName = "${norm.hashCode().toString().replace("-", "n")}_$fn"
                File(savedDir, safeDiskName).takeIf { it.exists() }?.delete()
                File(savedDir, fn).takeIf { it.exists() }?.delete()
                File(savedDir, norm).takeIf { it.exists() }?.delete()

                memoryCache.remove(raw)
                memoryCache.remove(norm)
                memoryCache.remove(fn)

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
        val savedDir = getSavedFilesDir()

        val exactFile = File(savedDir, safeDiskName)
        if (exactFile.exists() && exactFile.length() > 0) return exactFile
        val byName = File(savedDir, fn)
        if (byName.exists() && byName.length() > 0) return byName
        val byNorm = File(savedDir, norm)
        if (byNorm.exists() && byNorm.length() > 0) return byNorm
        val direct = File(pathOrName)
        if (direct.exists() && direct.length() > 0) return direct

        return listOf(getSavedFilesDir(), getAttachmentsDir()).asSequence()
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

    fun clearMemoryCache() {
        memoryCache.clear()
    }

    fun getMemoryCacheSize(): Int = memoryCache.size
}
