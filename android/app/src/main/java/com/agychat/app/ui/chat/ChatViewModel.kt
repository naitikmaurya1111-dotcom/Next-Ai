package com.agychat.app.ui.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agychat.app.data.local.ChatDao
import com.agychat.app.data.local.ConversationEntity
import com.agychat.app.data.local.MessageEntity
import com.agychat.app.data.network.AgyWebSocketClient
import android.net.Uri
import android.util.Base64
import com.agychat.app.domain.model.AiModel
import com.agychat.app.domain.model.AttachmentItem
import com.agychat.app.domain.model.ConnectionState
import com.agychat.app.domain.model.Message
import com.agychat.app.domain.model.ModelRegistry
import com.agychat.app.domain.model.SlashCommand
import com.agychat.app.domain.model.ToolExecutionItem
import com.agychat.app.domain.model.WsEvent
import com.agychat.app.domain.model.CustomInstructions
import com.agychat.app.domain.model.Personalization
import com.agychat.app.data.local.MemoryDao
import com.agychat.app.data.local.MemoryEntity
import com.agychat.app.domain.model.ThinkingLevel
import com.agychat.app.data.network.UrlSanitizer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject

data class FileViewerData(
    val filename: String,
    val path: String,
    val size: Long = 0L,
    val content: String = "",
    val error: String? = null,
    val isLoading: Boolean = false
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val webSocketClient: AgyWebSocketClient,
    private val chatDao: ChatDao,
    private val memoryDao: MemoryDao,
    val driveManager: com.agychat.app.data.drive.GoogleDriveManager
) : ViewModel() {

    // Remote Colab File Viewer State
    private val _activeFileViewer = MutableStateFlow<FileViewerData?>(null)
    val activeFileViewer: StateFlow<FileViewerData?> = _activeFileViewer.asStateFlow()

    fun closeFileViewer() {
        _activeFileViewer.value = null
    }

    // Reply / Quote state
    private val _replyToMessage = MutableStateFlow<Message?>(null)
    val replyToMessage: StateFlow<Message?> = _replyToMessage.asStateFlow()

    fun setReplyToMessage(msg: Message?) {
        _replyToMessage.value = msg
    }

    // Session Artifacts / Files tracking
    private val _sessionFiles = MutableStateFlow<List<String>>(emptyList())
    val sessionFiles: StateFlow<List<String>> = _sessionFiles.asStateFlow()

    // ChatGPT-Style Persistent Memory System
    val memories = memoryDao.getAllMemoriesFlow().catch { t ->
        Log.e("ChatViewModel", "Error in memories flow", t)
        emit(emptyList())
    }
    val enabledMemoriesCount = memoryDao.getEnabledCountFlow().catch { t ->
        Log.e("ChatViewModel", "Error in enabledMemoriesCount flow", t)
        emit(0)
    }
    private var currentMemoriesList: List<MemoryEntity> = emptyList()

    private val _isMemoryEnabled = MutableStateFlow(true)
    val isMemoryEnabled: StateFlow<Boolean> = _isMemoryEnabled.asStateFlow()

    private val _isAutoMemoryEnabled = MutableStateFlow(true)
    val isAutoMemoryEnabled: StateFlow<Boolean> = _isAutoMemoryEnabled.asStateFlow()

    private val _customInstructions = MutableStateFlow(CustomInstructions())
    val customInstructions: StateFlow<CustomInstructions> = _customInstructions.asStateFlow()

    // Full Personalization profile
    private val _personalization = MutableStateFlow(Personalization())
    val personalization: StateFlow<Personalization> = _personalization.asStateFlow()

    private val _isTemporaryChat = MutableStateFlow(false)
    val isTemporaryChat: StateFlow<Boolean> = _isTemporaryChat.asStateFlow()

    private val _showPinnedOnly = MutableStateFlow(false)
    val showPinnedOnly: StateFlow<Boolean> = _showPinnedOnly.asStateFlow()

    fun toggleShowPinnedOnly() {
        _showPinnedOnly.value = !_showPinnedOnly.value
    }

    fun setMemoryEnabled(enabled: Boolean) {
        _isMemoryEnabled.value = enabled
        val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("memory_enabled", enabled).apply()
    }

    fun setAutoMemoryEnabled(enabled: Boolean) {
        _isAutoMemoryEnabled.value = enabled
        val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("auto_memory_enabled", enabled).apply()
    }

    fun saveCustomInstructions(instructions: CustomInstructions) {
        _customInstructions.value = instructions
        val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("custom_about_user", instructions.aboutUser)
            .putString("custom_response_prefs", instructions.responsePreferences)
            .putString("custom_tone_preset", instructions.tonePreset)
            .putBoolean("custom_instructions_enabled", instructions.isEnabled)
            .apply()
    }

    fun savePersonalization(p: Personalization) {
        _personalization.value = p
        // Also sync memory/auto-memory toggles from personalization
        _isMemoryEnabled.value = p.memoryEnabled
        _isAutoMemoryEnabled.value = p.autoMemoryEnabled
        _customInstructions.value = CustomInstructions(
            aboutUser = listOfNotNull(
                if (p.name.isNotBlank()) "Name: ${p.name}" else null,
                if (p.occupation.isNotBlank()) "Role: ${p.occupation}" else null,
                if (p.expertise.isNotBlank()) "Stack: ${p.expertise}" else null,
                if (p.customContext.isNotBlank()) p.customContext else null
            ).joinToString("\n"),
            responsePreferences = listOfNotNull(
                "Tone: ${p.toneStyle}",
                "Depth: ${p.depthLevel}",
                "Length: ${p.responseLength}",
                if (p.codeLanguage.isNotBlank()) "Code Language: ${p.codeLanguage}" else null,
                if (p.extraInstructions.isNotBlank()) p.extraInstructions else null
            ).joinToString("\n"),
            tonePreset = p.toneStyle,
            isEnabled = p.isEnabled
        )
        val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("memory_enabled", p.memoryEnabled)
            .putBoolean("auto_memory_enabled", p.autoMemoryEnabled)
            .putString("p_name", p.name)
            .putString("p_occupation", p.occupation)
            .putString("p_expertise", p.expertise)
            .putString("p_country", p.country)
            .putString("p_age", p.age)
            .putString("p_response_length", p.responseLength)
            .putString("p_response_format", p.responseFormat)
            .putString("p_tone_style", p.toneStyle)
            .putString("p_depth_level", p.depthLevel)
            .putString("p_code_lang", p.codeLanguage)
            .putBoolean("p_examples", p.enableExamples)
            .putBoolean("p_proactive", p.enableProactiveInsights)
            .putBoolean("p_critical", p.enableCriticalFeedback)
            .putBoolean("p_emoji", p.enableEmoji)
            .putString("p_avoid", p.avoidTopics)
            .putString("p_context", p.customContext)
            .putString("p_extra", p.extraInstructions)
            .putBoolean("p_enabled", p.isEnabled)
            .apply()
    }

    fun toggleTemporaryChat() {
        _isTemporaryChat.value = !_isTemporaryChat.value
        startNewConversation()
    }

    fun setTemporaryChat(enabled: Boolean) {
        if (_isTemporaryChat.value != enabled) {
            _isTemporaryChat.value = enabled
            startNewConversation()
        }
    }

    fun addMemory(content: String, category: String = "general") {
        viewModelScope.launch {
            val memory = MemoryEntity(
                id = UUID.randomUUID().toString(),
                content = content.trim(),
                category = category,
                isEnabled = true,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            memoryDao.insertMemory(memory)
        }
    }

    fun toggleMemory(id: String, isEnabled: Boolean) {
        viewModelScope.launch {
            memoryDao.updateMemoryEnabled(id, isEnabled)
        }
    }

    fun deleteMemory(id: String) {
        viewModelScope.launch {
            memoryDao.deleteMemoryById(id)
        }
    }

    fun clearAllMemories() {
        viewModelScope.launch {
            memoryDao.clearAllMemories()
        }
    }

    fun editMemory(id: String, content: String, category: String) {
        viewModelScope.launch {
            memoryDao.updateMemoryContent(id, content.trim(), category, updatedAt = System.currentTimeMillis())
        }
    }

    fun updateMemoryImportance(id: String, importance: Int) {
        viewModelScope.launch {
            memoryDao.updateImportance(id, importance)
        }
    }

    fun exportMemoriesJson(): String {
        return try {
            val list = currentMemoriesList
            val array = org.json.JSONArray()
            list.forEach { mem ->
                val obj = JSONObject().apply {
                    put("content", mem.content)
                    put("category", mem.category)
                    put("isEnabled", mem.isEnabled)
                    put("createdAt", mem.createdAt)
                }
                array.put(obj)
            }
            array.toString(2)
        } catch (e: Exception) {
            "[]"
        }
    }

    fun importMemoriesJson(jsonStr: String): Boolean {
        return try {
            val array = org.json.JSONArray(jsonStr.trim())
            val entities = mutableListOf<MemoryEntity>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val content = obj.optString("content", "")
                if (content.isNotBlank()) {
                    entities.add(
                        MemoryEntity(
                            id = UUID.randomUUID().toString(),
                            content = content.trim(),
                            category = obj.optString("category", "preference"),
                            isEnabled = obj.optBoolean("isEnabled", true),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
            if (entities.isNotEmpty()) {
                viewModelScope.launch {
                    memoryDao.insertAll(entities)
                }
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    fun forgetMemory(query: String) {
        viewModelScope.launch {
            val count = memoryDao.deleteMemoriesMatching(query.trim())
            if (count > 0) {
                appendSystemMessage("🗑️ Forgot $count memory/memories matching \"$query\"")
            } else {
                appendSystemMessage("ℹ️ No memories found matching \"$query\"")
            }
        }
    }

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _currentStatus = MutableStateFlow<String?>(null)
    val currentStatus: StateFlow<String?> = _currentStatus.asStateFlow()

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _selectedAttachments = MutableStateFlow<List<AttachmentItem>>(emptyList())
    val selectedAttachments: StateFlow<List<AttachmentItem>> = _selectedAttachments.asStateFlow()

    private val _selectedAttachment = MutableStateFlow<AttachmentItem?>(null)
    val selectedAttachment: StateFlow<AttachmentItem?> = _selectedAttachment.asStateFlow()

    fun addAttachment(item: AttachmentItem) {
        if (_selectedAttachments.value.none { it.uri == item.uri }) {
            val updated = _selectedAttachments.value + item
            _selectedAttachments.value = updated
            _selectedAttachment.value = updated.firstOrNull()
        }
    }

    fun addAttachments(items: List<AttachmentItem>) {
        val currentUris = _selectedAttachments.value.map { it.uri }.toSet()
        val newItems = items.filterNot { it.uri in currentUris }
        if (newItems.isNotEmpty()) {
            val updated = _selectedAttachments.value + newItems
            _selectedAttachments.value = updated
            _selectedAttachment.value = updated.firstOrNull()
        }
    }

    fun setAttachment(item: AttachmentItem?) {
        val list = if (item != null) listOf(item) else emptyList()
        _selectedAttachments.value = list
        _selectedAttachment.value = item
    }

    fun removeAttachment(item: AttachmentItem) {
        val updated = _selectedAttachments.value.filterNot { it.uri == item.uri }
        _selectedAttachments.value = updated
        _selectedAttachment.value = updated.firstOrNull()
    }

    fun clearAttachment() {
        _selectedAttachments.value = emptyList()
        _selectedAttachment.value = null
    }

    fun clearAttachments() {
        clearAttachment()
    }

    // Google Drive & Cloud Sync State
    private val _cloudSyncStatus = MutableStateFlow<String?>(null)
    val cloudSyncStatus: StateFlow<String?> = _cloudSyncStatus.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    fun syncToGoogleDrive() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            _cloudSyncStatus.value = "Backing up to Google Drive..."
            try {
                // Save local safety backup
                driveManager.exportToLocalFile()

                if (_connectionState.value == ConnectionState.CONNECTED) {
                    val fullJson = driveManager.createFullBackupJson()
                    val syncPayload = JSONObject().apply {
                        put("action", "cloud_sync_backup")
                        put("backup_data", JSONObject(fullJson))
                        put("timestamp", System.currentTimeMillis())
                    }
                    webSocketClient.sendMessage(syncPayload.toString())
                    _cloudSyncStatus.value = "Synced with Google Drive"
                    driveManager.setLastSyncTimestamp(System.currentTimeMillis())
                } else {
                    _cloudSyncStatus.value = "Saved local backup (Connect Colab for Drive sync)"
                }
            } catch (e: Exception) {
                _cloudSyncStatus.value = "Sync error: ${e.localizedMessage}"
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun restoreFromGoogleDrive() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            _cloudSyncStatus.value = "Requesting backup from Google Drive..."
            try {
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    val restoreReq = JSONObject().apply {
                        put("action", "cloud_sync_restore")
                    }
                    webSocketClient.sendMessage(restoreReq.toString())
                } else {
                    val backupDir = java.io.File(context.filesDir, "backups")
                    val latestFile = java.io.File(backupDir, "nextai_backup_latest.json")
                    if (latestFile.exists()) {
                        val res = driveManager.importFromLocalFile(latestFile)
                        _cloudSyncStatus.value = res.message
                        loadConversations()
                    } else {
                        _cloudSyncStatus.value = "Colab offline and no local backup found"
                    }
                    _isSyncing.value = false
                }
            } catch (e: Exception) {
                _cloudSyncStatus.value = "Restore failed: ${e.localizedMessage}"
                _isSyncing.value = false
            }
        }
    }

    private val _selectedModel = MutableStateFlow<AiModel>(ModelRegistry.DEFAULT_MODEL)
    val selectedModel: StateFlow<AiModel> = _selectedModel.asStateFlow()

    fun selectModel(model: AiModel) {
        _selectedModel.value = model
        val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("selected_model", model.id).apply()
        // If the chosen model has fixed thinking (e.g. Claude or GPT-OSS), update effort to default
        if (!model.supportsEffort) {
            _reasoningEffort.value = model.defaultEffort
        }
    }

    private val _reasoningEffort = MutableStateFlow("high")
    val reasoningEffort: StateFlow<String> = _reasoningEffort.asStateFlow()

    fun setReasoningEffort(effort: String) {
        _reasoningEffort.value = effort
        val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("reasoning_effort", effort).apply()
    }

    // Active conversation ID
    var currentConversationId: String = UUID.randomUUID().toString()
        private set

    // Stream tracking
    private var streamingMessageId: String? = null
    private var connectionJob: Job? = null

    // Real-time conversations list from Room DB
    val conversations = chatDao.getAllConversations().catch { t ->
        Log.e("ChatViewModel", "Error in conversations flow", t)
        emit(emptyList())
    }

    init {
        try {
            // Load saved model preference
            val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
            val savedModelId = prefs.getString("selected_model", ModelRegistry.DEFAULT_MODEL.id)
            _selectedModel.value = ModelRegistry.findById(savedModelId)

            val savedEffort = prefs.getString("reasoning_effort", "high") ?: "high"
            _reasoningEffort.value = savedEffort

            val savedMemoryEnabled = prefs.getBoolean("memory_enabled", true)
            _isMemoryEnabled.value = savedMemoryEnabled

            val savedAutoMemory = prefs.getBoolean("auto_memory_enabled", true)
            _isAutoMemoryEnabled.value = savedAutoMemory

            val savedCustomEnabled = prefs.getBoolean("custom_instructions_enabled", true)
            val savedAbout = prefs.getString("custom_about_user", "") ?: ""
            val savedResp = prefs.getString("custom_response_prefs", "") ?: ""
            val savedTone = prefs.getString("custom_tone_preset", "Balanced") ?: "Balanced"
            _customInstructions.value = CustomInstructions(
                aboutUser = savedAbout,
                responsePreferences = savedResp,
                tonePreset = savedTone,
                isEnabled = savedCustomEnabled
            )

            // Load full Personalization profile
            _personalization.value = Personalization(
                name = prefs.getString("p_name", "") ?: "",
                occupation = prefs.getString("p_occupation", "") ?: "",
                expertise = prefs.getString("p_expertise", "") ?: "",
                country = prefs.getString("p_country", "") ?: "",
                age = prefs.getString("p_age", "") ?: "",
                responseLength = prefs.getString("p_response_length", "Adaptive") ?: "Adaptive",
                responseFormat = prefs.getString("p_response_format", "Auto") ?: "Auto",
                toneStyle = prefs.getString("p_tone_style", "Direct") ?: "Direct",
                depthLevel = prefs.getString("p_depth_level", "Expert") ?: "Expert",
                codeLanguage = prefs.getString("p_code_lang", "Kotlin") ?: "Kotlin",
                enableExamples = prefs.getBoolean("p_examples", true),
                enableProactiveInsights = prefs.getBoolean("p_proactive", true),
                enableCriticalFeedback = prefs.getBoolean("p_critical", true),
                enableEmoji = prefs.getBoolean("p_emoji", false),
                avoidTopics = prefs.getString("p_avoid", "") ?: "",
                customContext = prefs.getString("p_context", "") ?: "",
                extraInstructions = prefs.getString("p_extra", "") ?: "",
                isEnabled = prefs.getBoolean("p_enabled", true),
                memoryEnabled = prefs.getBoolean("memory_enabled", true),
                autoMemoryEnabled = prefs.getBoolean("auto_memory_enabled", true)
            )

            viewModelScope.launch {
                try {
                    memories.collectLatest { currentMemoriesList = it }
                } catch (t: Throwable) {
                    Log.e("ChatViewModel", "Error collecting memories flow in init", t)
                }
            }

            val rawSavedUrl = prefs.getString("server_url", "wss://olympic-understood-heater-angel.trycloudflare.com/ws")
            val validUrl = UrlSanitizer.normalizeWebSocketUrl(rawSavedUrl)
                ?: UrlSanitizer.normalizeWebSocketUrl("wss://olympic-understood-heater-angel.trycloudflare.com/ws")
            if (!validUrl.isNullOrBlank()) {
                connectToServer(validUrl)
            }
        } catch (t: Throwable) {
            Log.e("ChatViewModel", "Fatal error during ChatViewModel init, safely caught", t)
        }
    }

    fun fetchAndOpenFile(rawPathOrUrl: String) {
        val cleanPath = cleanFilePathOrUrl(rawPathOrUrl)
        val filename = cleanPath.substringAfterLast("/").ifBlank { "file.txt" }

        _activeFileViewer.value = FileViewerData(
            filename = filename,
            path = cleanPath,
            size = 0L,
            content = "",
            isLoading = true
        )

        // 1. Send WebSocket request
        try {
            val json = JSONObject().apply {
                put("action", "get_file")
                put("path", cleanPath)
            }
            webSocketClient.sendMessage(json.toString())
        } catch (t: Throwable) {
            Log.e("ChatViewModel", "Failed to send WS get_file", t)
        }

        // 2. OkHttp fallback in background for resilience
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
                val base = _serverUrl.value.ifBlank {
                    prefs.getString("server_url", "") ?: ""
                }
                if (base.isNotBlank()) {
                    val httpUrl = when {
                        base.startsWith("ws://") -> base.replace("ws://", "http://")
                        base.startsWith("wss://") -> base.replace("wss://", "https://")
                        !base.startsWith("http") -> "https://$base"
                        else -> base
                    }
                    val cleanBase = httpUrl.removeSuffix("/").removeSuffix("/ws")
                    val fullUrl = "$cleanBase/api/file?path=${Uri.encode(cleanPath)}"

                    val request = okhttp3.Request.Builder().url(fullUrl).build()
                    val response = okhttp3.OkHttpClient().newCall(request).execute()
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val respJson = JSONObject(body)
                        if (respJson.optString("status") == "ok") {
                            val fName = respJson.optString("filename", filename)
                            val fPath = respJson.optString("path", cleanPath)
                            val fSize = respJson.optLong("size", 0L)
                            val fContent = respJson.optString("content", "")
                            _activeFileViewer.value = FileViewerData(
                                filename = fName,
                                path = fPath,
                                size = fSize,
                                content = fContent,
                                isLoading = false
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w("ChatViewModel", "OkHttp fallback get_file failed: ${t.message}")
            }
        }
    }

    private fun cleanFilePathOrUrl(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("file://")) s = s.removePrefix("file://")
        if (s.contains("?path=")) s = s.substringAfter("?path=").substringBefore("&")
        return s.trim()
    }

    fun saveActiveFileToPhone(onResult: (Boolean, String) -> Unit) {
        val file = _activeFileViewer.value ?: return
        if (file.content.isBlank()) {
            onResult(false, "File content is empty")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val filename = file.filename
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, if (filename.endsWith(".md")) "text/markdown" else "text/plain")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { out ->
                            out.write(file.content.toByteArray(Charsets.UTF_8))
                        }
                        withContext(Dispatchers.Main) {
                            onResult(true, "Saved $filename to Downloads")
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            onResult(false, "Failed to create file in Downloads")
                        }
                    }
                } else {
                    val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    val target = java.io.File(dir, filename)
                    target.writeText(file.content, Charsets.UTF_8)
                    withContext(Dispatchers.Main) {
                        onResult(true, "Saved $filename to Downloads")
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error saving file to Downloads", e)
                withContext(Dispatchers.Main) {
                    onResult(false, "Save error: ${e.message}")
                }
            }
        }
    }

    private val connectionExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("ChatViewModel", "WebSocket connection coroutine caught error", throwable)
        _connectionState.value = ConnectionState.ERROR
        _isLoading.value = false
        _currentStatus.value = "Connection error: ${throwable.message ?: "Check server URL"}"
        scheduleAutoReconnect()
    }

    fun connectToServer(url: String) {
        val cleanUrl = UrlSanitizer.normalizeWebSocketUrl(url)
        if (cleanUrl == null) {
            Log.w("ChatViewModel", "connectToServer called with invalid URL: '$url'")
            _connectionState.value = ConnectionState.ERROR
            _isLoading.value = false
            _currentStatus.value = "Invalid WebSocket URL. Please check Settings."
            return
        }

        // Persist ONLY validated, normalized URL so it survives app restarts safely
        val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("server_url", cleanUrl).apply()

        _serverUrl.value = cleanUrl
        _connectionState.value = ConnectionState.CONNECTING
        _currentStatus.value = "Connecting to Colab bridge..."

        connectionJob?.cancel()
        connectionJob = viewModelScope.launch(Dispatchers.IO + connectionExceptionHandler) {
            try {
                webSocketClient.connect(cleanUrl).collectLatest { event ->
                    when (event) {
                        is WsEvent.Connected -> {
                            _connectionState.value = ConnectionState.CONNECTED
                            _currentStatus.value = null
                            reconnectAttempts = 0
                            reconnectJob?.cancel()
                        }
                        is WsEvent.Message -> handleIncomingMessage(event.text)
                        is WsEvent.Error -> {
                            _connectionState.value = ConnectionState.ERROR
                            _isLoading.value = false
                            _currentStatus.value = "Connection lost. Retrying..."
                            scheduleAutoReconnect()
                        }
                        is WsEvent.Closed -> {
                            _connectionState.value = ConnectionState.DISCONNECTED
                            _isLoading.value = false
                            _currentStatus.value = null
                            scheduleAutoReconnect()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("ChatViewModel", "Exception in WebSocket event stream", t)
                _connectionState.value = ConnectionState.ERROR
                _isLoading.value = false
                _currentStatus.value = "Connection failed: ${t.message ?: "Retrying..."}"
                scheduleAutoReconnect()
            }
        }
    }

    fun reconnect() {
        val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
        val rawUrl = _serverUrl.value.ifBlank { prefs.getString("server_url", "") ?: "" }
        val cleanUrl = UrlSanitizer.normalizeWebSocketUrl(rawUrl)
        if (cleanUrl != null) {
            connectToServer(cleanUrl)
        } else {
            _connectionState.value = ConnectionState.ERROR
            _currentStatus.value = "No valid URL configured"
        }
    }

    // Auto-reconnect with exponential backoff
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0

    private fun scheduleAutoReconnect() {
        reconnectJob?.cancel()
        if (reconnectAttempts >= 5) {
            _currentStatus.value = "Connection failed. Tap to retry manually."
            reconnectAttempts = 0
            return
        }
        val delayMs = minOf(2000L * (1 shl reconnectAttempts), 30_000L) // 2s, 4s, 8s, 16s, 30s
        reconnectAttempts++
        reconnectJob = viewModelScope.launch(Dispatchers.IO + connectionExceptionHandler) {
            _currentStatus.value = "Reconnecting in ${delayMs / 1000}s... (attempt $reconnectAttempts)"
            delay(delayMs)
            if (_connectionState.value != ConnectionState.CONNECTED) {
                reconnect()
            }
        }
    }

    private fun sanitizeChunk(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(Regex("\u001B\\[[;?0-9]*[a-zA-Z]"), "")
            .replace(Regex("//#\\][^\r\n]*"), "")
            .replace(Regex("\\[\\?[0-9;]*[a-zA-Z]"), "")
            .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F]"), "")
    }

    private fun handleIncomingMessage(rawText: String) {
        try {
            val json = JSONObject(rawText)
            val type = json.optString("type", "chunk")
            val content = json.optString("content", "")

            when (type) {
                "ping" -> {
                    // Server keep-alive heartbeat to prevent Cloudflare 100s timeout
                }
                "connected" -> {
                    _connectionState.value = ConnectionState.CONNECTED
                    _currentStatus.value = null
                }
                "info" -> {
                    _currentStatus.value = sanitizeChunk(content)
                }
                "thinking" -> {
                    appendThinkingChunk(content)
                }
                "tool_event" -> {
                    handleToolEvent(json)
                }
                "tool" -> {
                    if (json.has("tool_name")) {
                        handleToolEvent(json)
                    } else {
                        updateToolStatus(content)
                    }
                }
                "memory_updated" -> {
                    val action = json.optString("action", "add")
                    val memContent = json.optString("content", "")
                    val category = json.optString("category", "general")
                    if (memContent.isNotBlank()) {
                        handleAutonomousMemoryUpdate(action, memContent, category)
                    }
                }
                "cloud_sync_result" -> {
                    val msg = json.optString("message", "Google Drive sync completed")
                    _cloudSyncStatus.value = "✅ $msg"
                    driveManager.setLastSyncTimestamp(System.currentTimeMillis())
                    _isSyncing.value = false
                }
                "cloud_restore_data" -> {
                    val backupData = json.optJSONObject("data") ?: json.optJSONObject("backup_data")
                    if (backupData != null) {
                        viewModelScope.launch {
                            val res = driveManager.restoreFullBackupJson(backupData.toString())
                            _cloudSyncStatus.value = "✅ ${res.message}"
                            _isSyncing.value = false
                            loadConversations()
                        }
                    } else {
                        _cloudSyncStatus.value = "⚠️ No backup found on Google Drive"
                        _isSyncing.value = false
                    }
                }
                "file_data" -> {
                    val status = json.optString("status", "ok")
                    if (status == "ok" || status == "success") {
                        val fn = json.optString("filename", "file.txt")
                        val fp = json.optString("path", "")
                        val fsize = json.optLong("size", 0L)
                        val fcontent = json.optString("content", "")
                        _activeFileViewer.value = FileViewerData(
                            filename = fn,
                            path = fp,
                            size = fsize,
                            content = fcontent,
                            isLoading = false
                        )
                    } else {
                        val errMsg = json.optString("error", "File not found on Colab server")
                        _activeFileViewer.value = _activeFileViewer.value?.copy(
                            error = errMsg,
                            isLoading = false
                        ) ?: FileViewerData(
                            filename = "Error",
                            path = "",
                            error = errMsg,
                            isLoading = false
                        )
                    }
                }
                "chunk" -> {
                    appendContentChunk(content)
                }
                "done" -> {
                    finalizeStreamingMessage(content)
                    _isLoading.value = false
                    _currentStatus.value = null
                }
                "error" -> {
                    finalizeStreamingMessage()
                    appendSystemMessage("⚠️ $content")
                    _isLoading.value = false
                    _currentStatus.value = null
                }
                else -> {
                    // Ignore unrecognized event types to prevent distorted syntax leakage
                }
            }
        } catch (e: Exception) {
            // Never append unparsed JSON or raw control text directly into message content
        }
    }

    private fun handleAutonomousMemoryUpdate(action: String, content: String, category: String) {
        if (!_isAutoMemoryEnabled.value || _isTemporaryChat.value) return
        viewModelScope.launch {
            if (action == "add") {
                val cleanContent = content.trim()
                val existing = memoryDao.findMemoryByExactContent(cleanContent)
                if (existing == null) {
                    // Smart Conflict Resolution & Deduplication:
                    // Check if an existing memory in the same category covers this topic/preference
                    val existingMemories = memoryDao.getAllMemoriesList()
                        .filter { it.category.equals(category, ignoreCase = true) }
                    val keywords = cleanContent.lowercase()
                        .split(" ")
                        .filter { it.length > 3 && it !in setOf("user", "prefers", "likes", "always", "never", "with", "from") }
                        .toSet()

                    val existingMatch = existingMemories.firstOrNull { mem ->
                        val memKeywords = mem.content.lowercase()
                            .split(" ")
                            .filter { it.length > 3 && it !in setOf("user", "prefers", "likes", "always", "never", "with", "from") }
                            .toSet()
                        val overlap = keywords.intersect(memKeywords).size
                        (keywords.isNotEmpty() && overlap >= 2) || (keywords.size <= 2 && overlap >= 1)
                    }

                    if (existingMatch != null) {
                        // Update existing memory in place to avoid duplicate contradictions
                        memoryDao.updateMemoryContent(
                            existingMatch.id, cleanContent, category,
                            importance = existingMatch.importance,
                            updatedAt = System.currentTimeMillis()
                        )
                    } else {
                        val importance = when (category.lowercase()) {
                            "facts", "personal" -> 8
                            "prefs", "preferences" -> 7
                            "skills" -> 6
                            "project" -> 7
                            "goals" -> 8
                            else -> 5
                        }
                        memoryDao.insertMemory(
                            MemoryEntity(
                                id = UUID.randomUUID().toString(),
                                content = cleanContent,
                                category = category,
                                isEnabled = true,
                                importance = importance,
                                lastAccessedAt = System.currentTimeMillis(),
                                accessCount = 0,
                                createdAt = System.currentTimeMillis(),
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
                // Attach memory update tag to the currently active assistant message
                val currentMessages = _messages.value.toMutableList()
                val targetIndex = currentMessages.indexOfLast { it.role == "assistant" }
                if (targetIndex != -1) {
                    val target = currentMessages[targetIndex]
                    if (!target.memoryUpdates.contains(content)) {
                        currentMessages[targetIndex] = target.copy(memoryUpdates = target.memoryUpdates + content)
                        _messages.value = currentMessages
                    }
                }
            } else if (action == "delete") {
                memoryDao.deleteMemoriesMatching(content)
                val currentMessages = _messages.value.toMutableList()
                val targetIndex = currentMessages.indexOfLast { it.role == "assistant" }
                if (targetIndex != -1) {
                    val target = currentMessages[targetIndex]
                    currentMessages[targetIndex] = target.copy(memoryUpdates = target.memoryUpdates + "Removed: $content")
                    _messages.value = currentMessages
                }
            }
        }
    }

    private fun handleToolEvent(json: JSONObject) {
        val toolName = json.optString("tool_name", json.optString("content", "tool"))
        val toolState = json.optString("tool_state", "DONE")
        val paramsObj = json.optJSONObject("tool_params")
        val output = sanitizeChunk(json.optString("tool_output", ""))
        val duration = json.optDouble("duration", 0.0)

        val command = paramsObj?.optString("CommandLine")?.takeIf { it.isNotBlank() }
            ?: paramsObj?.optString("command")?.takeIf { it.isNotBlank() }
        val targetFile = paramsObj?.optString("TargetFile")?.takeIf { it.isNotBlank() }
            ?: paramsObj?.optString("AbsolutePath")?.takeIf { it.isNotBlank() }
            ?: paramsObj?.optString("SearchPath")?.takeIf { it.isNotBlank() }
            ?: paramsObj?.optString("path")?.takeIf { it.isNotBlank() }
        val summary = when {
            paramsObj?.has("Query") == true -> "Query: \"" + paramsObj.optString("Query") + "\""
            paramsObj?.has("Pattern") == true -> "Pattern: \"" + paramsObj.optString("Pattern") + "\""
            paramsObj?.has("Instruction") == true -> paramsObj.optString("Instruction")
            else -> null
        }

        if (!targetFile.isNullOrBlank() && (toolName == "write_to_file" || toolName == "replace_file_content" || toolName.contains("file"))) {
            if (!_sessionFiles.value.contains(targetFile)) {
                _sessionFiles.value = _sessionFiles.value + targetFile
            }
        }

        val list = _messages.value.toMutableList()
        val idx = list.indexOfFirst { it.id == streamingMessageId }

        val statusText = if (toolState == "ACTIVE") "Running $toolName..." else "Completed $toolName"
        _currentStatus.value = statusText

        if (idx >= 0) {
            val cur = list[idx]
            val toolList = cur.toolExecutions.toMutableList()
            val existingActiveIdx = toolList.indexOfLast { it.toolName == toolName && it.state == "ACTIVE" }
            if (existingActiveIdx >= 0 && toolState != "ACTIVE") {
                val existing = toolList[existingActiveIdx]
                toolList[existingActiveIdx] = existing.copy(
                    state = toolState,
                    command = command ?: existing.command,
                    targetFile = targetFile ?: existing.targetFile,
                    parametersSummary = summary ?: existing.parametersSummary,
                    output = if (output.isNotBlank()) output else existing.output,
                    durationSeconds = if (duration > 0) duration else existing.durationSeconds
                )
            } else {
                toolList.add(
                    ToolExecutionItem(
                        id = UUID.randomUUID().toString(),
                        toolName = toolName,
                        state = toolState,
                        command = command,
                        targetFile = targetFile,
                        parametersSummary = summary,
                        output = output.takeIf { it.isNotBlank() },
                        durationSeconds = duration
                    )
                )
            }
            list[idx] = cur.copy(
                toolExecution = statusText,
                toolExecutions = toolList,
                isStreaming = true
            )
            _messages.value = list
        } else {
            val newId = UUID.randomUUID().toString()
            streamingMessageId = newId
            val newTool = ToolExecutionItem(
                id = UUID.randomUUID().toString(),
                toolName = toolName,
                state = toolState,
                command = command,
                targetFile = targetFile,
                parametersSummary = summary,
                output = output.takeIf { it.isNotBlank() },
                durationSeconds = duration
            )
            list.add(
                Message(
                    id = newId,
                    role = "assistant",
                    content = "",
                    toolExecution = statusText,
                    toolExecutions = listOf(newTool),
                    timestamp = System.currentTimeMillis(),
                    isStreaming = true
                )
            )
            _messages.value = list
        }
        _isLoading.value = true
    }

    private fun appendContentChunk(chunk: String) {
        val cleanChunk = sanitizeChunk(chunk)
        if (cleanChunk.isEmpty()) return

        val list = _messages.value.toMutableList()
        val idx = list.indexOfFirst { it.id == streamingMessageId }

        if (idx >= 0) {
            val cur = list[idx]
            list[idx] = cur.copy(
                content = cur.content + cleanChunk,
                isStreaming = true,
                isThinking = false
            )
        } else {
            val newId = UUID.randomUUID().toString()
            streamingMessageId = newId
            list.add(
                Message(
                    id = newId,
                    role = "assistant",
                    content = cleanChunk,
                    timestamp = System.currentTimeMillis(),
                    isStreaming = true,
                    isThinking = false
                )
            )
        }
        _messages.value = list
        _isLoading.value = true
    }

    private fun appendThinkingChunk(chunk: String) {
        val cleanChunk = sanitizeChunk(chunk)
        if (cleanChunk.isEmpty()) return

        val list = _messages.value.toMutableList()
        val idx = list.indexOfFirst { it.id == streamingMessageId }

        if (idx >= 0) {
            val cur = list[idx]
            val prevThinking = cur.thinking ?: ""
            list[idx] = cur.copy(
                thinking = prevThinking + cleanChunk,
                isStreaming = true,
                isThinking = true
            )
        } else {
            val newId = UUID.randomUUID().toString()
            streamingMessageId = newId
            list.add(
                Message(
                    id = newId,
                    role = "assistant",
                    content = "",
                    thinking = cleanChunk,
                    timestamp = System.currentTimeMillis(),
                    isStreaming = true,
                    isThinking = true
                )
            )
        }
        _messages.value = list
        _isLoading.value = true
    }

    private fun updateToolStatus(toolText: String) {
        val cleanStatus = sanitizeChunk(toolText)
        val list = _messages.value.toMutableList()
        val idx = list.indexOfFirst { it.id == streamingMessageId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(toolExecution = cleanStatus)
            _messages.value = list
        }
    }

    private fun finalizeStreamingMessage(finalContent: String? = null) {
        val id = streamingMessageId ?: return
        val list = _messages.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val cur = list[idx]
            val resolvedContent = if (!finalContent.isNullOrBlank() && !finalContent.contains("Generation stopped by user")) sanitizeChunk(finalContent) else cur.content
            val finalized = cur.copy(
                content = resolvedContent,
                isStreaming = false,
                isThinking = false
            )
            list[idx] = finalized
            _messages.value = list
            saveMessageToDb(finalized)

            // Auto-backup to Google Drive if enabled and connected
            val autoBackup = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
                .getBoolean("drive_auto_backup", true)
            if (autoBackup && _connectionState.value == ConnectionState.CONNECTED && !_isTemporaryChat.value) {
                viewModelScope.launch {
                    try {
                        val fullJson = driveManager.createFullBackupJson()
                        val syncPayload = JSONObject().apply {
                            put("action", "cloud_sync_backup")
                            put("backup_data", JSONObject(fullJson))
                            put("timestamp", System.currentTimeMillis())
                        }
                        webSocketClient.sendMessage(syncPayload.toString())
                        driveManager.setLastSyncTimestamp(System.currentTimeMillis())
                    } catch (_: Exception) {}
                }
            }
        }
        streamingMessageId = null
    }

    fun toggleThinkingExpanded(messageId: String) {
        val list = _messages.value.toMutableList()
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            val cur = list[idx]
            list[idx] = cur.copy(isThinkingExpanded = !cur.isThinkingExpanded)
            _messages.value = list
        }
    }

    fun toggleToolsExpanded(messageId: String) {
        val list = _messages.value.toMutableList()
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            val cur = list[idx]
            list[idx] = cur.copy(isToolsExpanded = !cur.isToolsExpanded)
            _messages.value = list
        }
    }

    fun stopGenerating() {
        if (!_isLoading.value) return
        viewModelScope.launch {
            val cancelPayload = JSONObject().apply {
                put("type", "cancel")
                put("conversation_id", currentConversationId)
            }
            webSocketClient.sendMessage(cancelPayload.toString())
            finalizeStreamingMessage()
            _isLoading.value = false
            _currentStatus.value = "Generation stopped"
            delay(1200)
            _currentStatus.value = null
        }
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        val attachments = _selectedAttachments.value
        if (trimmed.isBlank() && attachments.isEmpty()) return

        if (_connectionState.value != ConnectionState.CONNECTED) {
            appendSystemMessage("⚠️ Not connected to Colab Bridge. Tap reconnect or open Settings.")
            return
        }

        // Natural language & slash commands for memory management
        if (trimmed.startsWith("/remember", ignoreCase = true)) {
            val memContent = trimmed.removePrefix("/remember").removePrefix(":").trim()
            if (memContent.isNotBlank()) {
                addMemory(memContent, "preference")
                appendSystemMessage("🧠 Saved to Memory: \"$memContent\"")
            }
        } else if (trimmed.startsWith("/forget", ignoreCase = true)) {
            val query = trimmed.removePrefix("/forget").removePrefix(":").trim()
            if (query.isNotBlank()) {
                forgetMemory(query)
                return
            }
        }

        val firstAtt = attachments.firstOrNull()
        val summaryContent = if (trimmed.isNotBlank()) {
            trimmed
        } else if (attachments.size == 1) {
            "Sent an attachment: ${firstAtt?.name}"
        } else {
            "Sent ${attachments.size} attachments (${attachments.count { it.isImage }} photos, ${attachments.count { !it.isImage }} files)"
        }

        val reply = _replyToMessage.value
        _replyToMessage.value = null

        val userMessage = Message(
            id = UUID.randomUUID().toString(),
            role = "user",
            content = summaryContent,
            timestamp = System.currentTimeMillis(),
            attachmentUri = firstAtt?.uri,
            attachmentName = firstAtt?.name,
            attachmentIsImage = firstAtt?.isImage ?: false,
            attachments = attachments,
            replyToContent = reply?.content?.take(250),
            replyToRole = reply?.role
        )
        _selectedAttachments.value = emptyList()
        _selectedAttachment.value = null
        _messages.value = _messages.value + userMessage
        if (!_isTemporaryChat.value) {
            saveMessageToDb(userMessage)
        }

        // Encode files to base64
        val filesArray = org.json.JSONArray()
        for (att in attachments) {
            try {
                val uri = Uri.parse(att.uri)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    if (bytes.size <= 10 * 1024 * 1024) {
                        val fileB64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        val fObj = JSONObject().apply {
                            put("name", att.name)
                            put("is_image", att.isImage)
                            put("mime_type", att.mimeType ?: if (att.isImage) "image/jpeg" else "application/octet-stream")
                            put("data", fileB64)
                        }
                        filesArray.put(fObj)
                    }
                }
            } catch (_: Exception) {
                // Ignore single file read error
            }
        }

        viewModelScope.launch {
            val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
            val effort = prefs.getString("reasoning_effort", "high") ?: "high"

            val basePrompt = if (trimmed.isNotBlank()) {
                trimmed
            } else if (attachments.size == 1) {
                "Please inspect the attached file: ${firstAtt?.name}"
            } else {
                "Please inspect the ${attachments.size} attached files: ${attachments.joinToString(", ") { it.name }}"
            }

            val promptText = if (reply != null) {
                val who = if (reply.role == "user") "User" else "Next AI"
                "> Quoting $who: \"${reply.content.take(300).replace("\n", " ")}\"\n\n$basePrompt"
            } else {
                basePrompt
            }

            // Dynamic Hybrid Relevance Retrieval (ChatGPT-grade)
            val memoryList = if (_isMemoryEnabled.value && !_isTemporaryChat.value) {
                try {
                    val allEnabled = memoryDao.getAllEnabledMemories()
                    if (allEnabled.isEmpty()) {
                        emptyList()
                    } else {
                        val stopWords = setOf(
                            "the", "and", "that", "this", "with", "from", "for", "are", "was", "were",
                            "what", "how", "when", "where", "which", "who", "why", "can", "could", "would",
                            "should", "please", "make", "help", "want", "like", "need", "about", "your"
                        )
                        val queryTokens = promptText.lowercase()
                            .split(Regex("[^a-zA-Z0-9_]+"))
                            .filter { it.length >= 3 && it !in stopWords }
                            .toSet()

                        val selected = allEnabled.sortedByDescending { mem ->
                            val memLower = mem.content.lowercase()
                            val keywordMatches = queryTokens.count { token -> memLower.contains(token) }
                            val categoryWeight = when (mem.category.lowercase()) {
                                "facts", "personal" -> 16 // Always maintain core user background in context
                                "goals", "project" -> 10
                                "prefs", "preferences" -> 8
                                "skills" -> 8
                                else -> 2
                            }
                            (keywordMatches * 25) + categoryWeight + (mem.importance * 3) + (mem.accessCount.coerceAtMost(8))
                        }.take(40)

                        viewModelScope.launch {
                            selected.forEach { memoryDao.incrementAccessCount(it.id) }
                        }

                        selected.map {
                            JSONObject().apply {
                                put("content", it.content)
                                put("category", it.category)
                                put("importance", it.importance)
                            }
                        }
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            // Multi-turn conversation turns for complete contextual memory across turns
            val historyArray = org.json.JSONArray()
            val priorTurns = _messages.value
                .filter { (it.role == "user" || it.role == "assistant") && it.content.isNotBlank() && it.id != userMessage.id }
                .takeLast(20)
            for (m in priorTurns) {
                val item = JSONObject().apply {
                    put("role", m.role)
                    put("content", m.content.take(3000))
                }
                historyArray.put(item)
            }

            val p = _personalization.value
            val payload = JSONObject().apply {
                put("message", promptText)
                put("conversation_id", currentConversationId)
                put("effort", effort)
                put("model", _selectedModel.value.id)
                if (historyArray.length() > 0) {
                    put("history", historyArray)
                }
                if (memoryList.isNotEmpty()) {
                    val memArray = org.json.JSONArray()
                    memoryList.forEach { memArray.put(it) }
                    put("memories", memArray)
                }
                // Send full personalization profile to bridge
                if (!_isTemporaryChat.value && p.isEnabled) {
                    put("personalization", JSONObject().apply {
                        if (p.name.isNotBlank()) put("name", p.name)
                        if (p.occupation.isNotBlank()) put("occupation", p.occupation)
                        if (p.expertise.isNotBlank()) put("expertise", p.expertise)
                        if (p.country.isNotBlank()) put("country", p.country)
                        if (p.age.isNotBlank()) put("age", p.age)
                        put("response_length", p.responseLength)
                        put("response_format", p.responseFormat)
                        put("tone_style", p.toneStyle)
                        put("depth_level", p.depthLevel)
                        put("code_language", p.codeLanguage)
                        put("enable_examples", p.enableExamples)
                        put("enable_proactive", p.enableProactiveInsights)
                        put("enable_critical", p.enableCriticalFeedback)
                        put("enable_emoji", p.enableEmoji)
                        if (p.avoidTopics.isNotBlank()) put("avoid_topics", p.avoidTopics)
                        if (p.customContext.isNotBlank()) put("custom_context", p.customContext)
                        if (p.extraInstructions.isNotBlank()) put("extra_instructions", p.extraInstructions)
                    })
                }
                // Legacy custom_instructions for backward compatibility
                if (!_isTemporaryChat.value && _customInstructions.value.isEnabled) {
                    put("custom_instructions", JSONObject().apply {
                        put("about_user", _customInstructions.value.aboutUser)
                        put("response_preferences", _customInstructions.value.responsePreferences)
                        put("tone_preset", _customInstructions.value.tonePreset)
                        put("is_enabled", _customInstructions.value.isEnabled)
                    })
                }
                put("auto_memory", _isAutoMemoryEnabled.value && !_isTemporaryChat.value)
                put("is_temporary", _isTemporaryChat.value)

                // Multi-files payload
                if (filesArray.length() > 0) {
                    put("files", filesArray)
                    val firstObj = filesArray.getJSONObject(0)
                    put("file_name", firstObj.getString("name"))
                    put("file_is_image", firstObj.getBoolean("is_image"))
                    put("file_data", firstObj.getString("data"))
                }
            }.toString()

            webSocketClient.sendMessage(payload)
            _isLoading.value = true
            _currentStatus.value = "Next AI thinking..."
        }
    }

    fun sendSlashCommand(command: SlashCommand, extraPrompt: String = "") {
        val fullPrompt = if (extraPrompt.isNotBlank()) {
            "${command.prefix} $extraPrompt"
        } else {
            command.prefix
        }
        sendMessage(fullPrompt)
    }

    private fun appendSystemMessage(text: String) {
        val msg = Message(
            id = UUID.randomUUID().toString(),
            role = "system",
            content = text,
            timestamp = System.currentTimeMillis()
        )
        _messages.value = _messages.value + msg
    }

    private fun generateTitle(messages: List<Message>): String {
        val firstUserMsg = messages.firstOrNull { it.role == "user" }?.content ?: return "New Chat"
        val cleaned = firstUserMsg
            .removePrefix("/boost").removePrefix("/goal").removePrefix("/plan")
            .removePrefix("/browser").removePrefix("/learn").removePrefix("/grill-me")
            .removePrefix("/schedule").removePrefix("/teamwork-preview").removePrefix("/remember")
            .trim()
        if (cleaned.isBlank()) return "New Chat"
        // Use first sentence or first 50 chars
        val firstSentence = cleaned.split(Regex("[.!?\\n]")).firstOrNull { it.trim().length > 3 }?.trim() ?: cleaned
        return firstSentence.take(50).trimEnd().let { if (it.length < firstSentence.length) "$it…" else it }
    }

    private fun saveMessageToDb(message: Message) {
        viewModelScope.launch {
            val title = generateTitle(_messages.value)
            chatDao.insertConversation(
                ConversationEntity(
                    id = currentConversationId,
                    title = title,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )

            val attachmentsJson = if (message.allAttachments.isNotEmpty()) {
                val arr = org.json.JSONArray()
                message.allAttachments.forEach { att ->
                    arr.put(JSONObject().apply {
                        put("uri", att.uri)
                        put("name", att.name)
                        put("size", att.size)
                        put("isImage", att.isImage)
                        put("mimeType", att.mimeType)
                    })
                }
                arr.toString()
            } else null

            val firstAtt = message.allAttachments.firstOrNull()

            chatDao.insertMessage(
                MessageEntity(
                    id = message.id,
                    conversationId = currentConversationId,
                    role = message.role,
                    content = message.content,
                    timestamp = message.timestamp,
                    attachmentUri = message.attachmentUri ?: firstAtt?.uri,
                    attachmentName = message.attachmentName ?: firstAtt?.name,
                    attachmentIsImage = message.attachmentIsImage || (firstAtt?.isImage ?: false),
                    attachmentsJson = attachmentsJson,
                    feedback = message.feedback,
                    isPinned = message.isPinned
                )
            )
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            _messages.value = _messages.value.filterNot { it.id == messageId }
            chatDao.deleteMessage(messageId)
        }
    }

    fun togglePinMessage(messageId: String) {
        val list = _messages.value.toMutableList()
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            val cur = list[idx]
            val updated = cur.copy(isPinned = !cur.isPinned)
            list[idx] = updated
            _messages.value = list
            saveMessageToDb(updated)
        }
    }

    fun toggleFeedback(messageId: String, feedbackType: String) {
        val list = _messages.value.toMutableList()
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            val cur = list[idx]
            val newFeedback = if (cur.feedback == feedbackType) null else feedbackType
            val updated = cur.copy(feedback = newFeedback)
            list[idx] = updated
            _messages.value = list
            saveMessageToDb(updated)
        }
    }

    fun regenerateLastResponse() {
        val msgs = _messages.value
        val lastUserMsg = msgs.lastOrNull { it.role == "user" } ?: return
        
        // Remove the last assistant response if exists
        val lastMsg = msgs.lastOrNull()
        if (lastMsg?.role == "assistant") {
            val filtered = msgs.filterNot { it.id == lastMsg.id }
            _messages.value = filtered
            viewModelScope.launch {
                chatDao.deleteMessage(lastMsg.id)
            }
        }
        
        // Resend the last user message text with previous conversation history
        val effort = _reasoningEffort.value

        val historyArray = org.json.JSONArray()
        val priorTurns = msgs
            .filter { (it.role == "user" || it.role == "assistant") && it.content.isNotBlank() && it.id != lastUserMsg.id && it.id != lastMsg?.id }
            .takeLast(20)
        for (m in priorTurns) {
            val item = JSONObject().apply {
                put("role", m.role)
                put("content", m.content.take(3000))
            }
            historyArray.put(item)
        }

        val payload = JSONObject().apply {
            put("message", lastUserMsg.content)
            put("conversation_id", currentConversationId)
            put("effort", effort)
            put("model", _selectedModel.value.id)
            if (historyArray.length() > 0) {
                put("history", historyArray)
            }
        }.toString()

        webSocketClient.sendMessage(payload)
        _isLoading.value = true
        _currentStatus.value = "Regenerating response..."
    }

    fun renameConversation(conversationId: String, newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            chatDao.updateConversationTitle(conversationId, trimmed)
        }
    }

    fun clearAllConversations() {
        viewModelScope.launch {
            chatDao.clearAllConversations()
            chatDao.clearAllMessages()
            startNewConversation()
        }
    }

    fun clearChat() {
        startNewConversation()
    }

    fun startNewConversation() {
        _messages.value = emptyList()
        _selectedAttachments.value = emptyList()
        _selectedAttachment.value = null
        _replyToMessage.value = null
        _sessionFiles.value = emptyList()
        streamingMessageId = null
        currentConversationId = UUID.randomUUID().toString()
        _currentStatus.value = null
        _isLoading.value = false
    }

    fun loadConversations() {
        loadConversation(currentConversationId)
    }

    fun loadConversation(conversationId: String) {
        currentConversationId = conversationId
        streamingMessageId = null
        _selectedAttachments.value = emptyList()
        _selectedAttachment.value = null
        _replyToMessage.value = null
        _isLoading.value = false
        _currentStatus.value = null

        viewModelScope.launch {
            chatDao.getMessagesForConversation(conversationId).collectLatest { entities ->
                val loadedMessages = entities.map { entity ->
                    val parsedAttachments = if (!entity.attachmentsJson.isNullOrBlank()) {
                        try {
                            val arr = org.json.JSONArray(entity.attachmentsJson)
                            val list = mutableListOf<AttachmentItem>()
                            for (i in 0 until arr.length()) {
                                val o = arr.getJSONObject(i)
                                list.add(
                                    AttachmentItem(
                                        uri = o.getString("uri"),
                                        name = o.getString("name"),
                                        size = o.optLong("size", 0L),
                                        isImage = o.optBoolean("isImage", false),
                                        mimeType = if (o.isNull("mimeType")) null else o.optString("mimeType")
                                    )
                                )
                            }
                            list
                        } catch (_: Exception) {
                            emptyList()
                        }
                    } else if (!entity.attachmentUri.isNullOrBlank()) {
                        listOf(
                            AttachmentItem(
                                uri = entity.attachmentUri,
                                name = entity.attachmentName ?: "Attachment",
                                size = 0L,
                                isImage = entity.attachmentIsImage
                            )
                        )
                    } else {
                        emptyList()
                    }

                    Message(
                        id = entity.id,
                        role = entity.role,
                        content = entity.content,
                        timestamp = entity.timestamp,
                        attachmentUri = entity.attachmentUri,
                        attachmentName = entity.attachmentName,
                        attachmentIsImage = entity.attachmentIsImage,
                        attachments = parsedAttachments,
                        feedback = entity.feedback,
                        isPinned = entity.isPinned
                    )
                }
                _messages.value = loadedMessages

                // Extract all file references generated in this conversation
                val files = loadedMessages.flatMap { msg ->
                    msg.toolExecutions.mapNotNull { it.targetFile }
                }.filter { it.isNotBlank() }.distinct()
                _sessionFiles.value = files
            }
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            chatDao.deleteConversation(conversationId)
            if (currentConversationId == conversationId) {
                startNewConversation()
            }
        }
    }

    fun saveExportToDownloads(onResult: (Boolean, String) -> Unit) {
        val md = exportConversationToMarkdown()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val filename = "NextAI_Chat_${System.currentTimeMillis()}.md"
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/markdown")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/NextAI")
                    }
                    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { stream ->
                            stream.write(md.toByteArray(Charsets.UTF_8))
                        }
                        onResult(true, "Saved to Downloads/NextAI/$filename")
                    } else {
                        onResult(false, "Could not create file in Downloads")
                    }
                } else {
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    val nextAiDir = java.io.File(downloadsDir, "NextAI").apply { mkdirs() }
                    val targetFile = java.io.File(nextAiDir, filename)
                    targetFile.writeText(md, Charsets.UTF_8)
                    onResult(true, "Saved to Downloads/NextAI/$filename")
                }
            } catch (e: Exception) {
                onResult(false, "Save failed: ${e.message}")
            }
        }
    }

    fun exportConversationToMarkdown(): String {
        val sb = StringBuilder()
        sb.append("# Next AI Chat Export\n\n")
        sb.append("*Generated: ${java.util.Date()}*\n\n---\n\n")
        for (m in _messages.value) {
            when (m.role) {
                "user" -> sb.append("### 👤 You\n${m.content}\n\n")
                "assistant" -> {
                    sb.append("### 🤖 Next AI\n")
                    if (!m.thinking.isNullOrBlank()) {
                        sb.append("> **Thinking Process:**\n> ${m.thinking.replace("\n", "\n> ")}\n\n")
                    }
                    sb.append("${m.content}\n\n")
                }
                "system" -> sb.append("*System: ${m.content}*\n\n")
            }
            sb.append("---\n\n")
        }
        return sb.toString()
    }

    override fun onCleared() {
        super.onCleared()
        webSocketClient.disconnect()
        connectionJob?.cancel()
        reconnectJob?.cancel()
    }
}
