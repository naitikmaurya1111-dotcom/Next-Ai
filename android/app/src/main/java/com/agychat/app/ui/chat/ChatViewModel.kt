package com.agychat.app.ui.chat

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agychat.app.data.local.ChatDao
import com.agychat.app.data.local.ConversationEntity
import com.agychat.app.data.local.MessageEntity
import com.agychat.app.data.network.AgyWebSocketClient
import android.net.Uri
import android.util.Base64
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
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
import com.agychat.app.domain.model.MemoryCategory
import com.agychat.app.domain.model.ThinkingLevel
import com.agychat.app.data.network.UrlSanitizer
import java.util.Calendar
import java.util.Locale
import com.agychat.app.domain.model.ConversationGroup
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject

data class FileViewerData(
    val filename: String,
    val path: String,
    val size: Long = 0L,
    val content: String = "",
    val bytes: ByteArray? = null,
    val localDiskFile: File? = null,
    val isBinary: Boolean = false,
    val mimeType: String = "",
    val error: String? = null,
    val isLoading: Boolean = false,
    val isOfflineCached: Boolean = false
) {
    val isPdf: Boolean get() = filename.lowercase().endsWith(".pdf") || mimeType == "application/pdf"
    val isImage: Boolean get() = mimeType.startsWith("image/") || filename.lowercase().let {
        it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".webp") || it.endsWith(".gif")
    }
}

data class HostEnvironment(
    val host: String = "Google Colab",
    val os: String = "Linux Ubuntu",
    val pythonVersion: String = "3.12",
    val cpuCount: Int = 2,
    val ramGb: Double = 0.0,
    val hasGpu: Boolean = false,
    val gpuName: String = "",
    val isDriveMounted: Boolean = false,
    val driveBackupPath: String? = null,
    val cwd: String = "/content",
    val gitRepo: String? = null,
    val gitBranch: String? = null,
    val gitCommit: String? = null,
    val gitCommitMsg: String? = null,
    val skills: List<String> = emptyList(),
    val activeModelsCount: Int = 7,
    val isWebsearchAvailable: Boolean = true
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val webSocketClient: AgyWebSocketClient,
    private val chatDao: ChatDao,
    private val memoryDao: MemoryDao,
    val localFileManager: com.agychat.app.data.local.LocalFileManager,
    val fileDao: com.agychat.app.data.local.FileDao,
    val driveManager: com.agychat.app.data.drive.GoogleDriveManager,
    val gistUrlResolver: com.agychat.app.data.network.GistUrlResolver
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

    // Chat Experience & Interaction Settings
    private val _chatTextSizeScale = MutableStateFlow(1.0f)
    val chatTextSizeScale: StateFlow<Float> = _chatTextSizeScale.asStateFlow()

    private val _showFollowupSuggestions = MutableStateFlow(true)
    val showFollowupSuggestions: StateFlow<Boolean> = _showFollowupSuggestions.asStateFlow()

    private val _showStreamingCursor = MutableStateFlow(true)
    val showStreamingCursor: StateFlow<Boolean> = _showStreamingCursor.asStateFlow()

    private val _compactMessageDensity = MutableStateFlow(false)
    val compactMessageDensity: StateFlow<Boolean> = _compactMessageDensity.asStateFlow()

    private val _showMemoryActivityBadges = MutableStateFlow(true)
    val showMemoryActivityBadges: StateFlow<Boolean> = _showMemoryActivityBadges.asStateFlow()

    fun setChatTextSizeScale(scale: Float) {
        _chatTextSizeScale.value = scale
        val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
        prefs.edit().putFloat("chat_text_size_scale", scale).apply()
    }

    fun setShowFollowupSuggestions(enabled: Boolean) {
        _showFollowupSuggestions.value = enabled
        val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("show_followup_suggestions", enabled).apply()
    }

    fun setShowStreamingCursor(enabled: Boolean) {
        _showStreamingCursor.value = enabled
        val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("show_streaming_cursor", enabled).apply()
    }

    fun setCompactMessageDensity(enabled: Boolean) {
        _compactMessageDensity.value = enabled
        val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("compact_message_density", enabled).apply()
    }

    fun setShowMemoryActivityBadges(enabled: Boolean) {
        _showMemoryActivityBadges.value = enabled
        val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("show_memory_activity_badges", enabled).apply()
    }

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
        val resolvedAboutUser = if (p.aboutUser.isNotBlank()) p.aboutUser else listOfNotNull(
            if (p.name.isNotBlank()) "Name: ${p.name}" else null,
            if (p.occupation.isNotBlank()) "Role: ${p.occupation}" else null,
            if (p.expertise.isNotBlank()) "Stack: ${p.expertise}" else null,
            if (p.customContext.isNotBlank()) p.customContext else null
        ).joinToString("\n")
        val resolvedResponsePrefs = if (p.responsePreferences.isNotBlank()) p.responsePreferences else listOfNotNull(
            "Tone: ${p.toneStyle}",
            "Depth: ${p.depthLevel}",
            "Length: ${p.responseLength}",
            if (p.extraInstructions.isNotBlank()) p.extraInstructions else null
        ).joinToString("\n")
        _customInstructions.value = CustomInstructions(
            aboutUser = resolvedAboutUser,
            responsePreferences = resolvedResponsePrefs,
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
            .putString("p_about_user", p.aboutUser)
            .putString("p_resp_prefs", p.responsePreferences)
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

    fun addMemory(content: String, category: String = "general", importance: Int = 7) {
        viewModelScope.launch {
            val memory = MemoryEntity(
                id = UUID.randomUUID().toString(),
                content = content.trim(),
                category = category,
                isEnabled = true,
                importance = importance.coerceIn(1, 10),
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            memoryDao.insertMemory(memory)
        }
    }

    fun updateMemoryCategory(id: String, category: String) {
        viewModelScope.launch {
            val existing = memoryDao.getAllMemoriesList().firstOrNull { it.id == id }
            if (existing != null) {
                memoryDao.updateMemoryContent(id, existing.content, category, existing.importance, System.currentTimeMillis())
            }
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

    fun editMemory(id: String, content: String, category: String, importance: Int? = null) {
        viewModelScope.launch {
            val existing = memoryDao.getAllMemoriesList().firstOrNull { it.id == id }
            val imp = importance ?: existing?.importance ?: 5
            val normCat = if (category.equals("preference", ignoreCase = true) || category.equals("style", ignoreCase = true)) {
                MemoryCategory.PREFERENCES
            } else category
            memoryDao.updateMemoryContent(id, content.trim(), normCat, importance = imp, updatedAt = System.currentTimeMillis())
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

    /**
     * Autonomous Memory Intelligence: identifies redundant, duplicate, or subsumed memories,
     * consolidates their access metrics, removes obsolete duplicates, and purges empty entries.
     */
    fun consolidateMemories() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val all = memoryDao.getAllMemoriesList()
                if (all.isEmpty()) return@launch

                var prunedCount = 0
                var mergedCount = 0

                // 1. Remove any empty or blank memories
                all.filter { it.content.isBlank() }.forEach {
                    memoryDao.deleteMemoryById(it.id)
                    prunedCount++
                }

                val activeMemories = all.filter { it.content.isNotBlank() }
                val visited = mutableSetOf<String>()

                for (i in activeMemories.indices) {
                    val m1 = activeMemories[i]
                    if (visited.contains(m1.id)) continue

                    val cluster = mutableListOf<MemoryEntity>()
                    cluster.add(m1)

                    val norm1 = m1.content.trim().lowercase().trimEnd('.', ',', '!', ';', ':')

                    for (j in (i + 1) until activeMemories.size) {
                        val m2 = activeMemories[j]
                        if (visited.contains(m2.id)) continue

                        val norm2 = m2.content.trim().lowercase().trimEnd('.', ',', '!', ';', ':')

                        // Check exact normalized match OR subsumption within same category
                        val isDuplicate = norm1 == norm2
                        val isSubsumed = (m1.category.equals(m2.category, ignoreCase = true)) &&
                                (norm1.contains(norm2) || norm2.contains(norm1)) &&
                                kotlin.math.abs(norm1.length - norm2.length) < 40

                        if (isDuplicate || isSubsumed) {
                            cluster.add(m2)
                            visited.add(m2.id)
                        }
                    }

                    if (cluster.size > 1) {
                        // Pick the best memory: longest content, highest importance, or highest accessCount
                        val best = cluster.maxWithOrNull(
                            compareBy<MemoryEntity> { it.content.length }
                                .thenBy { it.importance }
                                .thenBy { it.accessCount }
                        ) ?: continue

                        val totalAccesses = cluster.sumOf { it.accessCount }
                        val maxImportance = cluster.maxOf { it.importance }

                        // Delete the others
                        cluster.filter { it.id != best.id }.forEach {
                            memoryDao.deleteMemoryById(it.id)
                            mergedCount++
                        }

                        // Update the best representative
                        val consolidated = best.copy(
                            accessCount = totalAccesses,
                            importance = maxImportance,
                            updatedAt = System.currentTimeMillis()
                        )
                        memoryDao.updateMemory(consolidated)
                    }
                }

                if (prunedCount > 0 || mergedCount > 0) {
                    Log.d("ChatViewModel", "Autonomous memory consolidation complete: $mergedCount merged, $prunedCount pruned.")
                }
            } catch (t: Throwable) {
                Log.w("ChatViewModel", "Memory consolidation encountered an exception", t)
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

    private var pingStartTime = 0L
    private val _connectionLatencyMs = MutableStateFlow<Long?>(null)
    val connectionLatencyMs: StateFlow<Long?> = _connectionLatencyMs.asStateFlow()

    private val _hostEnvironment = MutableStateFlow(HostEnvironment())
    val hostEnvironment: StateFlow<HostEnvironment> = _hostEnvironment.asStateFlow()

    fun updateHostEnvironment(envJson: JSONObject?) {
        if (envJson == null) return
        try {
            val gitObj = envJson.optJSONObject("git")
            val skillsArray = envJson.optJSONArray("skills")
            val skillsList = mutableListOf<String>()
            if (skillsArray != null) {
                for (i in 0 until skillsArray.length()) {
                    val s = skillsArray.optString(i)
                    if (s.isNotBlank()) skillsList.add(s)
                }
            }
            val serverCwd = envJson.optString("cwd", "")
            if (serverCwd.isNotBlank() && _currentCwd.value == "/content" && serverCwd != "/content") {
                _currentCwd.value = serverCwd
            }

            _hostEnvironment.value = HostEnvironment(
                host = envJson.optString("host", "Google Colab"),
                os = envJson.optString("os", "Linux Ubuntu"),
                pythonVersion = envJson.optString("python", "3.12"),
                cpuCount = envJson.optInt("cpu_count", 2),
                ramGb = envJson.optDouble("ram_gb", 0.0),
                hasGpu = envJson.optBoolean("has_gpu", false),
                gpuName = envJson.optString("gpu_name", ""),
                isDriveMounted = envJson.optBoolean("drive_mounted", false),
                driveBackupPath = envJson.optString("drive_backup_path", null),
                cwd = if (serverCwd.isNotBlank()) serverCwd else _currentCwd.value,
                gitRepo = gitObj?.optString("repo", null),
                gitBranch = gitObj?.optString("branch", null),
                gitCommit = gitObj?.optString("commit", null),
                gitCommitMsg = gitObj?.optString("commit_msg", null),
                skills = skillsList,
                activeModelsCount = envJson.optInt("active_models_count", 7),
                isWebsearchAvailable = envJson.optBoolean("websearch_available", true)
            )
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Error parsing host environment telemetry", e)
        }
    }

    fun pingBridge() {
        pingStartTime = System.currentTimeMillis()
        try {
            val pingObj = JSONObject().apply {
                put("action", "ping")
                put("client_timestamp", pingStartTime)
                put("cwd", _currentCwd.value)
            }
            webSocketClient.sendMessage(pingObj.toString())
        } catch (t: Throwable) {
            Log.w("ChatViewModel", "Failed to send ping", t)
        }
    }

    fun requestEnvironmentRefresh() {
        viewModelScope.launch {
            try {
                val payload = JSONObject().apply {
                    put("action", "get_environment")
                    put("cwd", _currentCwd.value)
                }
                webSocketClient.sendMessage(payload.toString())
            } catch (_: Throwable) {}
        }
    }

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
        viewModelScope.launch {
            try {
                chatDao.updateConversationModel(currentConversationId, model.id)
            } catch (_: Exception) {}
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

    private val _activeConversationId = MutableStateFlow<String>(currentConversationId)
    val activeConversationId: StateFlow<String> = _activeConversationId.asStateFlow()

    // Workspace & Terminal State
    private val _currentCwd = MutableStateFlow("/content")
    val currentCwd: StateFlow<String> = _currentCwd.asStateFlow()

    private val _activeTasksCount = MutableStateFlow(0)
    val activeTasksCount: StateFlow<Int> = _activeTasksCount.asStateFlow()

    fun updateCwd(newCwd: String) {
        val trimmed = newCwd.trim()
        if (trimmed.isNotBlank()) {
            _currentCwd.value = trimmed
            val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("current_cwd", trimmed).apply()
            viewModelScope.launch {
                try {
                    chatDao.updateConversationCwd(currentConversationId, trimmed)
                } catch (_: Exception) {}
            }
            requestEnvironmentRefresh()
        }
    }

    // Stream tracking
    private var streamingMessageId: String? = null
    private var streamingParentMessageId: String? = null
    private var streamingBranchIndex: Int = 0
    private var isGenerationCancelled = false
    private val pendingMemoryUpdates = mutableListOf<String>()
    private var connectionJob: Job? = null
    private var messagesCollectorJob: Job? = null

    // Message Branch Tracking: maps branchGroupId -> activeBranchIndex
    private val _activeBranchMap = MutableStateFlow<Map<String, Int>>(emptyMap())
    val activeBranchMap: StateFlow<Map<String, Int>> = _activeBranchMap.asStateFlow()

    fun switchMessageBranch(branchGroupId: String, branchIndex: Int) {
        _activeBranchMap.value = _activeBranchMap.value + (branchGroupId to branchIndex)
    }

    fun continueGenerating() {
        if (_isLoading.value) return
        val lastAssistant = _messages.value.lastOrNull { it.role == "assistant" } ?: return
        if (lastAssistant.isStreaming || lastAssistant.content.isBlank()) return

        sendMessage("Continue from where you left off. Do not repeat previous text, seamlessly continue the output.")
    }

    private fun buildClientMetadata(): JSONObject {
        val dm = context.resources.displayMetrics
        val isLandscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val isDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "v1.1.0"
        } catch (_: Exception) { "v1.1.0" }

        return JSONObject().apply {
            put("os", "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            put("device", "${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${android.os.Build.MODEL}")
            put("app_version", appVersion)
            put("screen", "${dm.widthPixels}x${dm.heightPixels} @ ${dm.densityDpi}dpi (${if (isLandscape) "Landscape" else "Portrait"})")
            put("theme", if (isDark) "Dark Theme" else "Light Theme")
            put("locale", Locale.getDefault().toLanguageTag())
        }
    }

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
            
            _chatTextSizeScale.value = prefs.getFloat("chat_text_size_scale", 1.0f)

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
                codeLanguage = prefs.getString("p_code_lang", "Auto") ?: "Auto",
                enableExamples = prefs.getBoolean("p_examples", true),
                enableProactiveInsights = prefs.getBoolean("p_proactive", true),
                enableCriticalFeedback = prefs.getBoolean("p_critical", true),
                enableEmoji = prefs.getBoolean("p_emoji", false),
                avoidTopics = prefs.getString("p_avoid", "") ?: "",
                aboutUser = prefs.getString("p_about_user", "") ?: savedAbout,
                responsePreferences = prefs.getString("p_resp_prefs", "") ?: savedResp,
                customContext = prefs.getString("p_context", "") ?: "",
                extraInstructions = prefs.getString("p_extra", "") ?: "",
                isEnabled = prefs.getBoolean("p_enabled", true),
                memoryEnabled = prefs.getBoolean("memory_enabled", true),
                autoMemoryEnabled = prefs.getBoolean("auto_memory_enabled", true)
            )

            // Chat Experience Preferences
            _showFollowupSuggestions.value = prefs.getBoolean("show_followup_suggestions", true)
            _showStreamingCursor.value = prefs.getBoolean("show_streaming_cursor", true)
            _compactMessageDensity.value = prefs.getBoolean("compact_message_density", false)
            _showMemoryActivityBadges.value = prefs.getBoolean("show_memory_activity_badges", true)

            viewModelScope.launch {
                try {
                    memories.collectLatest { currentMemoriesList = it }
                } catch (t: Throwable) {
                    Log.e("ChatViewModel", "Error collecting memories flow in init", t)
                }
            }

            // Autonomous Memory Intelligence & Consolidation on startup
            viewModelScope.launch(Dispatchers.IO) {
                consolidateMemories()
            }

            val autoResolveGist = prefs.getBoolean("auto_resolve_gist_url", true)
            val savedGistId = prefs.getString("gist_id", com.agychat.app.data.network.GistUrlResolver.DEFAULT_GIST_ID)
                ?: com.agychat.app.data.network.GistUrlResolver.DEFAULT_GIST_ID
            val rawSavedUrl = prefs.getString("server_url", "wss://andy-viruses-she-performs.trycloudflare.com/ws")
            val fallbackUrl = UrlSanitizer.normalizeWebSocketUrl(rawSavedUrl)

            if (autoResolveGist) {
                viewModelScope.launch(Dispatchers.IO) {
                    _currentStatus.value = "Auto-syncing Colab tunnel via Gist..."
                    val res = gistUrlResolver.resolveLiveUrl(savedGistId)
                    withContext(Dispatchers.Main) {
                        if (res.isSuccess && !res.wsUrl.isNullOrBlank()) {
                            Log.i("ChatViewModel", "Resolved live URL from Gist on startup: ${res.wsUrl}")
                            connectToServer(res.wsUrl)
                        } else if (!fallbackUrl.isNullOrBlank()) {
                            Log.i("ChatViewModel", "Gist resolve failed, connecting to fallback: $fallbackUrl")
                            connectToServer(fallbackUrl)
                        } else {
                            _connectionState.value = ConnectionState.DISCONNECTED
                            _currentStatus.value = "Colab URL not detected. Check Settings."
                        }
                    }
                }
            } else if (!fallbackUrl.isNullOrBlank()) {
                connectToServer(fallbackUrl)
            }

            // Restore last active conversation on launch
            val savedConvId = prefs.getString("last_active_conversation_id", null)
            if (!savedConvId.isNullOrBlank()) {
                currentConversationId = savedConvId
                loadConversation(savedConvId)
            } else {
                viewModelScope.launch {
                    try {
                        val allConvs = chatDao.getAllConversationsList()
                        val latest = allConvs.firstOrNull()
                        if (latest != null) {
                            currentConversationId = latest.id
                            loadConversation(latest.id)
                        } else {
                            loadConversation(currentConversationId)
                        }
                    } catch (e: Exception) {
                        Log.e("ChatViewModel", "Failed to restore latest conversation", e)
                        loadConversation(currentConversationId)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("ChatViewModel", "Fatal error during ChatViewModel init, safely caught", t)
        }
    }

    fun fetchAndOpenFile(rawPathOrUrl: String) {
        // -1. Check if rawPathOrUrl is a normal web URL (e.g. https://google.com, https://github.com)
        if (rawPathOrUrl.startsWith("http://", ignoreCase = true) || rawPathOrUrl.startsWith("https://", ignoreCase = true)) {
            val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
            val base = _serverUrl.value.ifBlank { prefs.getString("server_url", "") ?: "" }
            val isColabEndpoint = rawPathOrUrl.contains("/api/file") ||
                (base.isNotBlank() && rawPathOrUrl.contains(base.removePrefix("wss://").removePrefix("ws://").removeSuffix("/ws")))
            if (!isColabEndpoint) {
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(rawPathOrUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browserIntent)
                    return
                } catch (e: Exception) {
                    Log.w("ChatViewModel", "Could not open web link: $rawPathOrUrl", e)
                }
            }
        }

        val cleanPath = cleanFilePathOrUrl(rawPathOrUrl)
        val filename = cleanPath.substringAfterLast("/").ifBlank { "file.txt" }

        // 0. Check if rawPathOrUrl is a local content:// or file:// URI from user attachment
        if (rawPathOrUrl.startsWith("content://") || rawPathOrUrl.startsWith("file://")) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val uri = Uri.parse(rawPathOrUrl)
                    val mime = context.contentResolver.getType(uri) ?: localFileManager.detectMimeType(filename)
                    val isBin = localFileManager.isBinaryFile(mime, filename)
                    val bytes = try {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    } catch (_: Throwable) {
                        if (uri.scheme == "file") {
                            try { File(uri.path ?: "").readBytes() } catch (_: Throwable) { null }
                        } else null
                    }
                    if (bytes != null) {
                        val entity = localFileManager.cacheBinaryFile(currentConversationId, uri.toString(), filename, bytes)
                        val diskFile = File(entity.localPath)
                        withContext(Dispatchers.Main) {
                            _activeFileViewer.value = FileViewerData(
                                filename = filename,
                                path = uri.toString(),
                                size = bytes.size.toLong(),
                                content = if (isBin) "" else try { String(bytes, Charsets.UTF_8) } catch (_: Throwable) { "" },
                                bytes = bytes,
                                localDiskFile = diskFile,
                                isBinary = isBin,
                                mimeType = mime,
                                isLoading = false,
                                isOfflineCached = true
                            )
                        }
                        return@launch
                    }
                } catch (t: Throwable) {
                    Log.e("ChatViewModel", "Failed to read local attachment URI: $rawPathOrUrl", t)
                }
            }
        }

        // 1. Immediately check local phone storage cache (0ms, 100% offline!)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cached = localFileManager.getCachedFile(cleanPath)
                if (cached != null) {
                    val diskFile = File(cached.localPath)
                    val isBin = localFileManager.isBinaryFile(cached.mimeType, cached.filename)
                    // Safeguard: only load in-memory byte buffer for small binaries (<= 2MB) to prevent OOM
                    val bytes = if (isBin && diskFile.exists() && diskFile.length() <= 2 * 1024 * 1024) {
                        try { diskFile.readBytes() } catch (_: Throwable) { null }
                    } else null

                    withContext(Dispatchers.Main) {
                        _activeFileViewer.value = FileViewerData(
                            filename = cached.filename,
                            path = cached.remotePath,
                            size = cached.size,
                            content = cached.content,
                            bytes = bytes,
                            localDiskFile = if (diskFile.exists()) diskFile else null,
                            isBinary = isBin,
                            mimeType = cached.mimeType,
                            isLoading = false,
                            isOfflineCached = true
                        )
                    }
                    // Offline or cached: immediate display, zero network waiting
                    return@launch
                }

                // 2. Second check: Search active conversation messages for inline artifacts, tool outputs, or code blocks
                val foundInConversation = findArtifactInMessages(cleanPath, filename)
                if (foundInConversation != null) {
                    val entity = localFileManager.cacheFile(currentConversationId, cleanPath, filename, foundInConversation)
                    val diskFile = File(entity.localPath)
                    withContext(Dispatchers.Main) {
                        _activeFileViewer.value = FileViewerData(
                            filename = entity.filename,
                            path = entity.remotePath,
                            size = entity.size,
                            content = entity.content,
                            localDiskFile = diskFile,
                            isBinary = false,
                            mimeType = entity.mimeType,
                            isLoading = false,
                            isOfflineCached = true
                        )
                    }
                    return@launch
                }

                // Not in local cache
                withContext(Dispatchers.Main) {
                    _activeFileViewer.value = FileViewerData(
                        filename = filename,
                        path = cleanPath,
                        size = 0L,
                        content = "",
                        mimeType = localFileManager.detectMimeType(filename),
                        isLoading = true,
                        isOfflineCached = false
                    )
                }

                if (_connectionState.value != ConnectionState.CONNECTED) {
                    withContext(Dispatchers.Main) {
                        _activeFileViewer.value = FileViewerData(
                            filename = filename,
                            path = cleanPath,
                            mimeType = localFileManager.detectMimeType(filename),
                            error = "Offline: File is not yet cached in phone storage. Connect to Colab to fetch it.",
                            isLoading = false,
                            isOfflineCached = false
                        )
                    }
                    return@launch
                }

                // Online: fetch from Colab with timeout & streaming safeguards
                val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
                val base = _serverUrl.value.ifBlank { prefs.getString("server_url", "") ?: "" }
                var loadedSuccessfully = false

                if (base.isNotBlank()) {
                    val httpUrl = when {
                        base.startsWith("ws://") -> base.replace("ws://", "http://")
                        base.startsWith("wss://") -> base.replace("wss://", "https://")
                        !base.startsWith("http") -> "https://$base"
                        else -> base
                    }
                    try {
                        val entity = localFileManager.fetchAndCacheColabFile(httpUrl, cleanPath, currentConversationId)
                        if (entity != null) {
                            val diskFile = File(entity.localPath)
                            val isBin = localFileManager.isBinaryFile(entity.mimeType, entity.filename)
                            val bytes = if (isBin && diskFile.exists() && diskFile.length() <= 2 * 1024 * 1024) {
                                try { diskFile.readBytes() } catch (_: Throwable) { null }
                            } else null

                            withContext(Dispatchers.Main) {
                                _activeFileViewer.value = FileViewerData(
                                    filename = entity.filename,
                                    path = entity.remotePath,
                                    size = entity.size,
                                    content = entity.content,
                                    bytes = bytes,
                                    localDiskFile = if (diskFile.exists()) diskFile else null,
                                    isBinary = isBin,
                                    mimeType = entity.mimeType,
                                    isLoading = false,
                                    isOfflineCached = true
                                )
                            }
                            loadedSuccessfully = true
                        }
                    } catch (t: Throwable) {
                        Log.w("ChatViewModel", "HTTP fetchAndCacheColabFile failed: ${t.message}")
                    }
                }

                // If HTTP did not succeed, fallback to WebSocket get_file
                if (!loadedSuccessfully && _connectionState.value == ConnectionState.CONNECTED) {
                    try {
                        val json = JSONObject().apply {
                            put("action", "get_file")
                            put("path", cleanPath)
                        }
                        webSocketClient.sendMessage(json.toString())
                    } catch (t: Throwable) {
                        Log.e("ChatViewModel", "Failed to send WS get_file", t)
                    }
                }
            } catch (t: Throwable) {
                Log.e("ChatViewModel", "Unhandled error in fetchAndOpenFile", t)
                withContext(Dispatchers.Main) {
                    _activeFileViewer.value = FileViewerData(
                        filename = filename,
                        path = cleanPath,
                        error = "Could not load file: ${t.message}",
                        isLoading = false
                    )
                }
            }
        }
    }

    private fun cleanFilePathOrUrl(raw: String): String {
        var s = raw.trim()
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

    private fun hasFileExtension(path: String): Boolean {
        val fn = path.substringAfterLast('/')
        return fn.contains('.') && fn.substringAfterLast('.').length in 1..8 &&
            !fn.endsWith('.') && !fn.contains(' ')
    }

    private fun findArtifactInMessages(cleanPath: String, filename: String): String? {
        val currentMessages = _messages.value
        val targetFname = filename.lowercase()
        val targetId = cleanPath.lowercase().removePrefix("/").removePrefix("content/").removePrefix("root/")

        for (msg in currentMessages.asReversed()) {
            // A. Check tool executions (write_to_file, replace_file_content, etc.)
            for (tool in msg.toolExecutions) {
                val tFile = tool.targetFile ?: continue
                val tfName = tFile.substringAfterLast("/").lowercase()
                if (tFile.equals(cleanPath, ignoreCase = true) ||
                    tfName == targetFname ||
                    tFile.endsWith(filename, ignoreCase = true) ||
                    tfName.contains(targetId) ||
                    targetId.contains(tfName)
                ) {
                    val out = tool.output?.trim()
                    if (!out.isNullOrBlank() && !out.contains("File written") && !out.contains("Success") && out.length > 5) {
                        return out
                    }
                }
            }

            // B. Check <antArtifact> or <artifact> tags in message content
            val antRegex = Regex("""<(?:antArtifact|artifact)\s+([^>]+)>([\s\S]*?)</(?:antArtifact|artifact)>""")
            for (m in antRegex.findAll(msg.content)) {
                val tagAttrs = m.groupValues[1]
                val body = m.groupValues[2].trim()
                val id = Regex("""identifier=["']([^"']+)["']""").find(tagAttrs)?.groupValues?.get(1)?.lowercase() ?: ""
                val title = Regex("""title=["']([^"']+)["']""").find(tagAttrs)?.groupValues?.get(1)?.lowercase() ?: ""
                if (id == targetId || id == targetFname || title == targetId || title == targetFname ||
                    targetId.contains(id) || (id.isNotBlank() && targetId.endsWith(id)) ||
                    targetFname.contains(id) || (id.isNotBlank() && id.contains(targetFname))
                ) {
                    return body
                }
            }

            // C. Check markdown code blocks matching filename or extension
            val codeBlockRegex = Regex("""```([a-zA-Z0-9_-]+)?(?:\s+(?:file|filename)=["']?([^\s"']+)["']?)?\n([\s\S]*?)```""")
            for (m in codeBlockRegex.findAll(msg.content)) {
                val fenceFile = m.groupValues[2].trim().lowercase()
                val fenceCode = m.groupValues[3].trim()
                if (fenceFile.isNotBlank() && (fenceFile == targetFname || fenceFile.endsWith(targetFname))) {
                    return fenceCode
                }
            }
        }
        return null
    }

    fun saveActiveFileToPhone(onResult: (Boolean, String) -> Unit) {
        val file = _activeFileViewer.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val (success, msg) = when {
                file.localDiskFile != null && file.localDiskFile.exists() -> {
                    // Stream directly from disk file to Downloads without allocating entire file into RAM
                    localFileManager.exportToPublicDownloads(file.localDiskFile, file.filename)
                }
                file.bytes != null && file.bytes.isNotEmpty() -> {
                    localFileManager.exportToPublicDownloads(file.filename, file.bytes)
                }
                file.content.isNotBlank() -> {
                    localFileManager.exportToPublicDownloads(file.filename, file.content)
                }
                else -> {
                    Pair(false, "File content is empty or not yet loaded")
                }
            }
            withContext(Dispatchers.Main) {
                onResult(success, msg)
            }
        }
    }

    fun openActiveFileInExternalApp(context: Context, onResult: (Boolean, String) -> Unit) {
        val fileData = _activeFileViewer.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val diskFile: File? = fileData.localDiskFile?.takeIf { it.exists() } ?: run {
                val tempFile = File(context.cacheDir, fileData.filename)
                try {
                    if (fileData.bytes != null) {
                        tempFile.writeBytes(fileData.bytes)
                        tempFile
                    } else if (fileData.content.isNotBlank()) {
                        tempFile.writeText(fileData.content, Charsets.UTF_8)
                        tempFile
                    } else null
                } catch (e: Exception) {
                    null
                }
            }

            if (diskFile == null || !diskFile.exists()) {
                withContext(Dispatchers.Main) {
                    onResult(false, "File not available on local device storage")
                }
                return@launch
            }

            val res = localFileManager.openFileInExternalApp(context, diskFile)
            withContext(Dispatchers.Main) {
                onResult(res.first, res.second)
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
                webSocketClient.connect(cleanUrl).collect { event ->
                    when (event) {
                        is WsEvent.Connected -> {
                            _connectionState.value = ConnectionState.CONNECTED
                            _currentStatus.value = null
                            reconnectAttempts = 0
                            reconnectJob?.cancel()

                            // Automatically resume conversation stream if disconnected during active generation
                            if (_isLoading.value || streamingMessageId != null) {
                                try {
                                    val resumePayload = JSONObject().apply {
                                        put("action", "resume_conversation")
                                        put("conversation_id", currentConversationId)
                                        put("after_seq", lastReceivedSeq)
                                    }
                                    webSocketClient.sendMessage(resumePayload.toString())
                                    Log.i("ChatViewModel", "Sent resume_conversation after reconnect, after_seq=$lastReceivedSeq")
                                } catch (t: Throwable) {
                                    Log.e("ChatViewModel", "Failed to send resume_conversation", t)
                                }
                            }
                        }
                        is WsEvent.Message -> handleIncomingMessage(event.text)
                        is WsEvent.Error -> {
                            _connectionState.value = ConnectionState.ERROR
                            persistCurrentStreamingState()
                            _currentStatus.value = "Connection lost. Reconnecting..."
                            scheduleAutoReconnect()
                        }
                        is WsEvent.Closed -> {
                            _connectionState.value = ConnectionState.DISCONNECTED
                            persistCurrentStreamingState()
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
                persistCurrentStreamingState()
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

    // Auto-reconnect with exponential backoff and seamless resume
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var lastReceivedSeq = -1

    private fun scheduleAutoReconnect() {
        reconnectJob?.cancel()
        val delayMs = if (reconnectAttempts < 5) {
            minOf(1000L * (1 shl reconnectAttempts), 16_000L) // 1s, 2s, 4s, 8s, 16s
        } else {
            8_000L // Keep retrying every 8s so tunnel reconnects automatically
        }
        reconnectAttempts++
        reconnectJob = viewModelScope.launch(Dispatchers.IO + connectionExceptionHandler) {
            _currentStatus.value = "Reconnecting in ${delayMs / 1000}s... (attempt $reconnectAttempts)"
            delay(delayMs)
            if (_connectionState.value != ConnectionState.CONNECTED) {
                val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
                if (reconnectAttempts >= 2 && prefs.getBoolean("auto_resolve_gist_url", true)) {
                    val gid = prefs.getString("gist_id", com.agychat.app.data.network.GistUrlResolver.DEFAULT_GIST_ID)
                    val gistRes = gistUrlResolver.resolveLiveUrl(gid)
                    if (gistRes.isSuccess && !gistRes.wsUrl.isNullOrBlank() && gistRes.wsUrl != _serverUrl.value) {
                        Log.i("ChatViewModel", "Detected updated Colab tunnel URL in Gist: ${gistRes.wsUrl}")
                        withContext(Dispatchers.Main) {
                            connectToServer(gistRes.wsUrl)
                        }
                        return@launch
                    }
                }
                withContext(Dispatchers.Main) {
                    reconnect()
                }
            }
        }
    }

    fun syncUrlFromGist(customGistId: String? = null, onResult: ((Boolean, String) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
            val gid = customGistId?.trim()?.ifBlank { null }
                ?: prefs.getString("gist_id", com.agychat.app.data.network.GistUrlResolver.DEFAULT_GIST_ID)
                ?: com.agychat.app.data.network.GistUrlResolver.DEFAULT_GIST_ID
            _currentStatus.value = "Syncing live URL from GitHub Gist..."
            val result = gistUrlResolver.resolveLiveUrl(gid)
            withContext(Dispatchers.Main) {
                if (result.isSuccess && !result.wsUrl.isNullOrBlank()) {
                    prefs.edit().putString("server_url", result.wsUrl).apply()
                    connectToServer(result.wsUrl)
                    onResult?.invoke(true, result.wsUrl)
                } else {
                    val msg = result.errorMessage ?: "Failed to resolve live URL from Gist"
                    _currentStatus.value = msg
                    onResult?.invoke(false, msg)
                }
            }
        }
    }

    fun onAppResume() {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.i("ChatViewModel", "onAppResume: initiating tunnel reconnect")
            reconnectJob?.cancel()
            reconnectAttempts = 0
            reconnect()
        } else {
            // Verify socket responsiveness after being backgrounded
            try {
                webSocketClient.sendMessage(JSONObject().apply { put("action", "ping") }.toString())
            } catch (t: Throwable) {
                Log.w("ChatViewModel", "onAppResume: socket ping failed, reconnecting", t)
                reconnect()
            }
        }
    }

    fun persistCurrentStreamingState() {
        synchronized(streamBatchLock) {
            streamBatchJob?.cancel()
            streamBatchJob = null
            flushStreamingBatchesLocked(System.currentTimeMillis())
        }
        val id = streamingMessageId
        val msg = if (id != null) {
            _messages.value.find { it.id == id }
        } else {
            _messages.value.lastOrNull { it.role == "assistant" && it.isStreaming }
        } ?: return
        saveMessageToDb(msg)
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
            if (json.has("seq")) {
                val s = json.optInt("seq", -1)
                if (s >= 0) lastReceivedSeq = s
            }
            val type = json.optString("type", "chunk")
            val content = json.optString("content", "")

            when (type) {
                "ping" -> {
                    // Server keep-alive heartbeat to prevent Cloudflare 100s timeout
                }
                "pong" -> {
                    val clientTs = json.optLong("client_timestamp", 0L)
                    val latency = if (clientTs > 0L) {
                        (System.currentTimeMillis() - clientTs).coerceAtLeast(1L)
                    } else if (pingStartTime > 0L) {
                        (System.currentTimeMillis() - pingStartTime).coerceAtLeast(1L)
                    } else null
                    _connectionLatencyMs.value = latency
                    if (json.has("environment")) {
                        updateHostEnvironment(json.optJSONObject("environment"))
                    }
                }
                "connected" -> {
                    _connectionState.value = ConnectionState.CONNECTED
                    _currentStatus.value = null
                    if (json.has("environment")) {
                        updateHostEnvironment(json.optJSONObject("environment"))
                    }
                }
                "environment_info" -> {
                    if (json.has("environment")) {
                        updateHostEnvironment(json.optJSONObject("environment"))
                    }
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
                    val importance = json.optInt("importance", 7)
                    if (memContent.isNotBlank()) {
                        handleAutonomousMemoryUpdate(action, memContent, category, importance)
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
                    val fn = json.optString("filename", "file.txt")
                    val fp = json.optString("path", "")
                    val fsize = json.optLong("size", 0L)
                    val mime = localFileManager.detectMimeType(fn)

                    if (status == "too_large") {
                        val dlUrl = json.optString("download_url", "")
                        if (dlUrl.isNotBlank()) {
                            val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
                            val base = _serverUrl.value.ifBlank { prefs.getString("server_url", "") ?: "" }
                            val httpUrl = when {
                                base.startsWith("ws://") -> base.replace("ws://", "http://")
                                base.startsWith("wss://") -> base.replace("wss://", "https://")
                                !base.startsWith("http") -> "https://$base"
                                else -> base
                            }.removeSuffix("/").removeSuffix("/ws")
                            val fullDlUrl = if (dlUrl.startsWith("http")) dlUrl else "$httpUrl$dlUrl"

                            viewModelScope.launch(Dispatchers.IO) {
                                val entity = localFileManager.downloadAndCacheRemoteFile(fullDlUrl, fp, currentConversationId)
                                if (entity != null) {
                                    val diskFile = File(entity.localPath)
                                    val isBin = localFileManager.isBinaryFile(entity.mimeType, entity.filename)
                                    val bytes = if (isBin && diskFile.exists() && diskFile.length() <= 2 * 1024 * 1024) try { diskFile.readBytes() } catch (_: Throwable) { null } else null
                                    withContext(Dispatchers.Main) {
                                        _activeFileViewer.value = FileViewerData(
                                            filename = entity.filename,
                                            path = entity.remotePath,
                                            size = entity.size,
                                            content = entity.content,
                                            bytes = bytes,
                                            localDiskFile = if (diskFile.exists()) diskFile else null,
                                            isBinary = isBin,
                                            mimeType = entity.mimeType,
                                            isLoading = false,
                                            isOfflineCached = true
                                        )
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        _activeFileViewer.value = _activeFileViewer.value?.copy(
                                            error = "Failed to stream large file from Colab server",
                                            isLoading = false
                                        )
                                    }
                                }
                            }
                        }
                    } else if (status == "ok" || status == "success") {
                        val isBinary = json.optBoolean("is_binary", false)
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                if (isBinary) {
                                    val b64 = json.optString("base64_content", "")
                                    val bytes = try { Base64.decode(b64, Base64.DEFAULT) } catch (t: Throwable) { null }
                                    if (bytes != null) {
                                        val entity = localFileManager.cacheBinaryFile(currentConversationId, fp, fn, bytes)
                                        val diskFile = File(entity.localPath)
                                        val displayBytes = if (bytes.size <= 2 * 1024 * 1024) bytes else null
                                        withContext(Dispatchers.Main) {
                                            _activeFileViewer.value = FileViewerData(
                                                filename = fn,
                                                path = fp,
                                                size = fsize,
                                                bytes = displayBytes,
                                                localDiskFile = diskFile,
                                                isBinary = true,
                                                mimeType = mime,
                                                isLoading = false,
                                                isOfflineCached = true
                                            )
                                        }
                                    } else {
                                        withContext(Dispatchers.Main) {
                                            _activeFileViewer.value = _activeFileViewer.value?.copy(
                                                error = "Could not decode file content (out of memory)",
                                                isLoading = false
                                            )
                                        }
                                    }
                                } else {
                                    val fcontent = json.optString("content", "")
                                    val entity = localFileManager.cacheFile(currentConversationId, fp, fn, fcontent)
                                    val diskFile = File(entity.localPath)
                                    withContext(Dispatchers.Main) {
                                        _activeFileViewer.value = FileViewerData(
                                            filename = fn,
                                            path = fp,
                                            size = fsize,
                                            content = fcontent,
                                            localDiskFile = diskFile,
                                            isBinary = false,
                                            mimeType = mime,
                                            isLoading = false,
                                            isOfflineCached = true
                                        )
                                    }
                                }
                            } catch (t: Throwable) {
                                Log.e("ChatViewModel", "Error processing file_data event", t)
                                withContext(Dispatchers.Main) {
                                    _activeFileViewer.value = _activeFileViewer.value?.copy(
                                        error = "Error loading file: ${t.message}",
                                        isLoading = false
                                    )
                                }
                            }
                        }
                    } else {
                        val errMsg = json.optString("error", json.optString("message", "File not found on Colab server"))
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
                    val tokensPerSec = json.optDouble("tokens_per_second", 0.0).takeIf { it > 0.0 }
                    val durationSec = json.optDouble("duration_sec", 0.0).takeIf { it > 0.0 }
                    if (isGenerationCancelled) {
                        isGenerationCancelled = false
                        _isLoading.value = false
                        _currentStatus.value = null
                    } else {
                        finalizeStreamingMessage(content, tokensPerSec, durationSec)
                        _isLoading.value = false
                        _currentStatus.value = null
                    }
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
        } catch (t: Throwable) {
            Log.e("ChatViewModel", "Uncaught error in handleIncomingMessage", t)
        }
    }

    private fun handleAutonomousMemoryUpdate(action: String, content: String, category: String, importance: Int = 7) {
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
                        .filter { it.length > 3 && it !in setOf("user", "prefers", "likes", "always", "never", "with", "from", "that") }
                        .toSet()

                    val existingMatch = existingMemories.firstOrNull { mem ->
                        val memKeywords = mem.content.lowercase()
                            .split(" ")
                            .filter { it.length > 3 && it !in setOf("user", "prefers", "likes", "always", "never", "with", "from", "that") }
                            .toSet()
                        val overlap = keywords.intersect(memKeywords).size
                        (keywords.isNotEmpty() && overlap >= 2) || (keywords.size <= 2 && overlap >= 1)
                    }

                    if (existingMatch != null) {
                        // Update existing memory in place to avoid duplicate contradictions
                        val updatedImportance = maxOf(existingMatch.importance, importance)
                        memoryDao.updateMemoryContent(
                            existingMatch.id, cleanContent, category,
                            importance = updatedImportance,
                            updatedAt = System.currentTimeMillis()
                        )
                    } else {
                        val computedImportance = if (importance in 1..10) importance else when (category.lowercase()) {
                            "facts", "personal" -> 9
                            "prefs", "preferences" -> 8
                            "instructions" -> 9
                            "project" -> 8
                            "goals" -> 8
                            "skills" -> 7
                            else -> 6
                        }
                        memoryDao.insertMemory(
                            MemoryEntity(
                                id = UUID.randomUUID().toString(),
                                content = cleanContent,
                                category = category,
                                isEnabled = true,
                                importance = computedImportance,
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
                val targetIndex = if (streamingMessageId != null) {
                    currentMessages.indexOfFirst { it.id == streamingMessageId }
                } else {
                    currentMessages.indexOfLast { it.role == "assistant" }
                }
                if (targetIndex != -1) {
                    val target = currentMessages[targetIndex]
                    if (!target.memoryUpdates.contains(content)) {
                        currentMessages[targetIndex] = target.copy(memoryUpdates = target.memoryUpdates + content)
                        _messages.value = currentMessages
                    }
                } else {
                    synchronized(pendingMemoryUpdates) {
                        if (!pendingMemoryUpdates.contains(content)) {
                            pendingMemoryUpdates.add(content)
                        }
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

        val isArtifactCreationTool = toolName in setOf("write_to_file", "replace_file_content", "create_file", "generate_image") ||
            (targetFile != null && targetFile.contains("/brain/"))
        if (!targetFile.isNullOrBlank() && isArtifactCreationTool) {
            val cleanTarget = cleanFilePathOrUrl(targetFile)
            if (cleanTarget.isNotBlank() && hasFileExtension(cleanTarget)) {
                val currentNorm = _sessionFiles.value.map { cleanFilePathOrUrl(it) }
                if (!currentNorm.contains(cleanTarget)) {
                    _sessionFiles.value = (_sessionFiles.value + cleanTarget).distinctBy { cleanFilePathOrUrl(it) }
                }
            }
            val writtenContent = paramsObj?.optString("CodeContent")?.takeIf { it.isNotBlank() }
                ?: paramsObj?.optString("content")?.takeIf { it.isNotBlank() }
                ?: paramsObj?.optString("ReplacementContent")?.takeIf { it.isNotBlank() }
            if (!writtenContent.isNullOrBlank()) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        localFileManager.cacheFile(currentConversationId, cleanTarget, null, writtenContent)
                    } catch (_: Throwable) {}
                }
            }
        } else if (!targetFile.isNullOrBlank() && (toolName == "view_file" || toolName == "read_file") && output.isNotBlank()) {
            val cleanTarget = cleanFilePathOrUrl(targetFile)
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    localFileManager.cacheFile(currentConversationId, cleanTarget, null, output)
                } catch (_: Throwable) {}
            }
        }
        if (toolName == "generate_image") {
            val imgName = paramsObj?.optString("ImageName")
            val imgRegex = Regex("""(?:file://|/content/|/root/|/tmp/)[^\s\)\]'"]+\.(?:png|jpg|jpeg|webp)""")
            val imgPath = imgRegex.find(output)?.value ?: imgName?.let { "/tmp/$it.png" }
            if (!imgPath.isNullOrBlank()) {
                val cleanImg = cleanFilePathOrUrl(imgPath)
                val currentNorm = _sessionFiles.value.map { cleanFilePathOrUrl(it) }
                if (!currentNorm.contains(cleanImg)) {
                    _sessionFiles.value = (_sessionFiles.value + cleanImg).distinctBy { cleanFilePathOrUrl(it) }
                }
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        if (localFileManager.getCachedFile(cleanImg) == null) {
                            val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
                            val base = _serverUrl.value.ifBlank { prefs.getString("server_url", "") ?: "" }
                            if (base.isNotBlank() && _connectionState.value == ConnectionState.CONNECTED) {
                                val httpUrl = when {
                                    base.startsWith("ws://") -> base.replace("ws://", "http://")
                                    base.startsWith("wss://") -> base.replace("wss://", "https://")
                                    !base.startsWith("http") -> "https://$base"
                                    else -> base
                                }.removeSuffix("/").removeSuffix("/ws")
                                val dlUrl = "$httpUrl/api/file/download?path=${Uri.encode(cleanImg)}"
                                localFileManager.downloadAndCacheRemoteFile(dlUrl, cleanImg, currentConversationId)
                            }
                        }
                    } catch (_: Throwable) {}
                }
            }
        }

        synchronized(streamBatchLock) {
            streamBatchJob?.cancel()
            streamBatchJob = null
            flushStreamingBatchesLocked(System.currentTimeMillis())
        }

        val list = _messages.value.toMutableList()
        val lastUserIdx = list.indexOfLast { it.role == "user" }
        val lastAssistantIdx = list.indexOfLast { it.role == "assistant" }
        val isCurrentTurnAssistant = lastAssistantIdx >= 0 && lastAssistantIdx > lastUserIdx
        val idx = if (streamingMessageId != null) {
            list.indexOfFirst { it.id == streamingMessageId }
        } else if (isCurrentTurnAssistant) {
            streamingMessageId = list[lastAssistantIdx].id
            lastAssistantIdx
        } else {
            -1
        }

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
                    isStreaming = true,
                    parentMessageId = streamingParentMessageId,
                    branchIndex = streamingBranchIndex
                )
            )
            _messages.value = list
        }
        _isLoading.value = true
    }

    private val streamBatchLock = Any()
    private val pendingContentChunks = StringBuilder()
    private val pendingThinkingChunks = StringBuilder()
    private var lastStreamUiUpdateTime = 0L
    private var streamBatchJob: Job? = null
    private var streamingThinkingStartTime = 0L

    private fun flushStreamingBatchesLocked(now: Long) {
        val contentToAppend = pendingContentChunks.toString()
        pendingContentChunks.clear()
        val thinkingToAppend = pendingThinkingChunks.toString()
        pendingThinkingChunks.clear()
        lastStreamUiUpdateTime = now

        if (contentToAppend.isEmpty() && thinkingToAppend.isEmpty()) return

        val list = _messages.value.toMutableList()
        val lastUserIdx = list.indexOfLast { it.role == "user" }
        val lastAssistantIdx = list.indexOfLast { it.role == "assistant" }
        val isCurrentTurnAssistant = lastAssistantIdx >= 0 && lastAssistantIdx > lastUserIdx
        val idx = if (streamingMessageId != null) {
            list.indexOfFirst { it.id == streamingMessageId }
        } else if (isCurrentTurnAssistant && (list[lastAssistantIdx].isStreaming || list[lastAssistantIdx].content.isBlank() || list[lastAssistantIdx].thinking != null)) {
            streamingMessageId = list[lastAssistantIdx].id
            lastAssistantIdx
        } else {
            -1
        }

        val targetMsg: Message
        if (idx >= 0) {
            val cur = list[idx]
            val newContent = if (contentToAppend.isNotEmpty()) cur.content + contentToAppend else cur.content
            val newThinking = if (thinkingToAppend.isNotEmpty()) (cur.thinking ?: "") + thinkingToAppend else cur.thinking

            val updatedThinkingDuration = if (streamingThinkingStartTime > 0L) {
                if (contentToAppend.isNotEmpty() && cur.thinkingDurationMs == 0L) {
                    (now - streamingThinkingStartTime).coerceAtLeast(800L)
                } else if (cur.thinkingDurationMs > 0L) {
                    cur.thinkingDurationMs
                } else {
                    now - streamingThinkingStartTime
                }
            } else {
                cur.thinkingDurationMs
            }

            targetMsg = cur.copy(
                content = newContent,
                thinking = newThinking,
                thinkingDurationMs = updatedThinkingDuration,
                isStreaming = true,
                isThinking = thinkingToAppend.isNotEmpty() && contentToAppend.isEmpty()
            )
            list[idx] = targetMsg
        } else {
            val newId = UUID.randomUUID().toString()
            streamingMessageId = newId
            val initialUpdates = synchronized(pendingMemoryUpdates) {
                val copy = pendingMemoryUpdates.toList()
                pendingMemoryUpdates.clear()
                copy
            }
            if (thinkingToAppend.isNotEmpty() && streamingThinkingStartTime == 0L) {
                streamingThinkingStartTime = now
            }
            targetMsg = Message(
                id = newId,
                role = "assistant",
                content = contentToAppend,
                thinking = if (thinkingToAppend.isNotEmpty()) thinkingToAppend else null,
                thinkingDurationMs = if (streamingThinkingStartTime > 0L) now - streamingThinkingStartTime else 0L,
                memoryUpdates = initialUpdates,
                timestamp = System.currentTimeMillis(),
                isStreaming = true,
                isThinking = thinkingToAppend.isNotEmpty() && contentToAppend.isEmpty(),
                parentMessageId = streamingParentMessageId,
                branchIndex = streamingBranchIndex
            )
            list.add(targetMsg)
        }
        _messages.value = list
        _isLoading.value = true
    }

    private fun appendContentChunk(chunk: String) {
        if (isGenerationCancelled) return
        val cleanChunk = sanitizeChunk(chunk)
        if (cleanChunk.isEmpty()) return

        synchronized(streamBatchLock) {
            pendingContentChunks.append(cleanChunk)
            val now = System.currentTimeMillis()
            if (now - lastStreamUiUpdateTime >= 35L) {
                flushStreamingBatchesLocked(now)
            } else if (streamBatchJob == null || streamBatchJob?.isActive != true) {
                streamBatchJob = viewModelScope.launch {
                    delay(35L)
                    synchronized(streamBatchLock) {
                        flushStreamingBatchesLocked(System.currentTimeMillis())
                    }
                }
            }
        }
    }

    private fun appendThinkingChunk(chunk: String) {
        if (isGenerationCancelled) return
        val cleanChunk = sanitizeChunk(chunk)
        if (cleanChunk.isEmpty()) return

        synchronized(streamBatchLock) {
            if (streamingThinkingStartTime == 0L) {
                streamingThinkingStartTime = System.currentTimeMillis()
            }
            pendingThinkingChunks.append(cleanChunk)
            val now = System.currentTimeMillis()
            if (now - lastStreamUiUpdateTime >= 35L) {
                flushStreamingBatchesLocked(now)
            } else if (streamBatchJob == null || streamBatchJob?.isActive != true) {
                streamBatchJob = viewModelScope.launch {
                    delay(35L)
                    synchronized(streamBatchLock) {
                        flushStreamingBatchesLocked(System.currentTimeMillis())
                    }
                }
            }
        }
    }

    private fun updateToolStatus(toolText: String) {
        synchronized(streamBatchLock) {
            streamBatchJob?.cancel()
            streamBatchJob = null
            flushStreamingBatchesLocked(System.currentTimeMillis())
        }
        val cleanStatus = sanitizeChunk(toolText)
        val list = _messages.value.toMutableList()
        val idx = list.indexOfFirst { it.id == streamingMessageId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(toolExecution = cleanStatus)
            _messages.value = list
        }
    }

    private fun finalizeStreamingMessage(
        finalContent: String? = null,
        tokensPerSec: Double? = null,
        durationSec: Double? = null
    ) {
        synchronized(streamBatchLock) {
            streamBatchJob?.cancel()
            streamBatchJob = null
            flushStreamingBatchesLocked(System.currentTimeMillis())
            streamingThinkingStartTime = 0L
        }
        val id = streamingMessageId ?: return
        val list = _messages.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val cur = list[idx]
            val resolvedContent = if (!finalContent.isNullOrBlank() && !finalContent.contains("Generation stopped by user")) sanitizeChunk(finalContent) else cur.content
            val finalized = cur.copy(
                content = resolvedContent,
                isStreaming = false,
                isThinking = false,
                tokensPerSecond = tokensPerSec ?: cur.tokensPerSecond,
                durationSeconds = durationSec ?: cur.durationSeconds
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

            // Background pre-cache any referenced files or artifacts in the message for instant offline availability
            val fileRegex = Regex("""(?:file://|/content/|/root/|/tmp/)[a-zA-Z0-9_\-./]+\.(?:md|txt|py|kt|java|json|csv|pdf|png|jpg|jpeg|webp|html|svg|sh|cpp|c|rs|go)""")
            val matches = fileRegex.findAll(resolvedContent)
                .map { cleanFilePathOrUrl(it.value) }
                .filter { it.isNotBlank() && hasFileExtension(it) }
                .toSet()
            if (matches.isNotEmpty()) {
                val existingNorm = _sessionFiles.value.map { cleanFilePathOrUrl(it) }.toSet()
                val newUnique = matches.filter { it !in existingNorm }
                if (newUnique.isNotEmpty()) {
                    _sessionFiles.value = (_sessionFiles.value + newUnique).distinctBy { cleanFilePathOrUrl(it) }
                }

                viewModelScope.launch(Dispatchers.IO) {
                    for (fPath in matches) {
                        try {
                            val clean = cleanFilePathOrUrl(fPath)
                            if (localFileManager.getCachedFile(clean) == null) {
                                val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
                                val base = _serverUrl.value.ifBlank { prefs.getString("server_url", "") ?: "" }
                                if (base.isNotBlank() && _connectionState.value == ConnectionState.CONNECTED) {
                                    val httpUrl = when {
                                        base.startsWith("ws://") -> base.replace("ws://", "http://")
                                        base.startsWith("wss://") -> base.replace("wss://", "https://")
                                        !base.startsWith("http") -> "https://$base"
                                        else -> base
                                    }.removeSuffix("/").removeSuffix("/ws")
                                    val dlUrl = "$httpUrl/api/file/download?path=${Uri.encode(clean)}"
                                    localFileManager.downloadAndCacheRemoteFile(dlUrl, clean, currentConversationId)
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
        }
        streamingMessageId = null
        streamingParentMessageId = null
        streamingBranchIndex = 0
    }

    fun toggleThinkingExpanded(messageId: String) {
        val list = _messages.value.toMutableList()
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            val cur = list[idx]
            val currentlyExpanded = cur.isThinkingExpanded ?: (cur.isStreaming && cur.isThinking)
            list[idx] = cur.copy(isThinkingExpanded = !currentlyExpanded)
            _messages.value = list
        }
    }

    fun toggleToolsExpanded(messageId: String) {
        val list = _messages.value.toMutableList()
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            val cur = list[idx]
            val anyRunning = cur.toolExecutions.any { it.state == "ACTIVE" }
            val currentExpanded = cur.isToolsExpanded ?: anyRunning
            list[idx] = cur.copy(isToolsExpanded = !currentExpanded)
            _messages.value = list
        }
    }

    fun stopGenerating() {
        if (!_isLoading.value) return
        isGenerationCancelled = true
        synchronized(streamBatchLock) {
            streamBatchJob?.cancel()
            streamBatchJob = null
            pendingContentChunks.clear()
            pendingThinkingChunks.clear()
        }
        viewModelScope.launch {
            val cancelPayload = JSONObject().apply {
                put("type", "cancel")
                put("conversation_id", currentConversationId)
            }
            webSocketClient.sendMessage(cancelPayload.toString())
            lastReceivedSeq = -1
            finalizeStreamingMessage()
            _isLoading.value = false
            _currentStatus.value = "Generation stopped"
            delay(1200)
            _currentStatus.value = null
        }
    }

    private fun readRawBytes(uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream -> stream.readBytes() }
        } catch (_: Throwable) {
            if (uri.scheme == "file") {
                try { File(uri.path ?: "").readBytes() } catch (_: Throwable) { null }
            } else null
        }
    }

    /**
     * Efficiently prepares and uploads attachments for network transmission.
     * For PDFs, documents, or large files (>1.5MB), uploads directly to Colab bridge via HTTP multipart POST.
     * For photos, downsamples to max 2048px and compresses to 85% JPEG (~300KB-800KB).
     * Eliminates WebSocket frame limits, buffer overflow, and 413 Payload Too Large errors.
     */
    private suspend fun processAttachment(att: AttachmentItem): JSONObject? {
        val uri = Uri.parse(att.uri)
        val isImg = att.isImage || (att.mimeType?.startsWith("image/") == true)
        val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
        val bridgeUrl = _serverUrl.value.ifBlank { prefs.getString("server_url", "") ?: "" }

        val isPdfOrDoc = !isImg || att.mimeType?.contains("pdf") == true || att.name.endsWith(".pdf", ignoreCase = true)

        // Technique: Upload PDFs and non-image files via HTTP multipart directly to Colab bridge
        if (isPdfOrDoc && bridgeUrl.isNotBlank()) {
            _currentStatus.value = "Uploading ${att.name} to Colab..."
            val serverPath = localFileManager.uploadAttachmentToBridge(bridgeUrl, uri, att.name, att.mimeType)
            if (serverPath != null) {
                Log.i("ChatViewModel", "Attachment ${att.name} successfully uploaded via HTTP to $serverPath")
                return JSONObject().apply {
                    put("name", att.name)
                    put("is_image", isImg)
                    put("server_path", serverPath)
                    put("mime_type", att.mimeType ?: "application/pdf")
                }
            } else {
                Log.w("ChatViewModel", "HTTP upload failed for ${att.name}, trying fallback...")
            }
        }

        val bytes: ByteArray? = if (isImg) {
            try {
                // First read bounds safely without loading full bitmap into RAM
                val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, boundsOptions)
                }
                val rawW = boundsOptions.outWidth
                val rawH = boundsOptions.outHeight

                if (rawW > 0 && rawH > 0) {
                    var inSampleSize = 1
                    val maxDim = maxOf(rawW, rawH)
                    while (maxDim / inSampleSize > 2048) {
                        inSampleSize *= 2
                    }

                    val decodeOptions = BitmapFactory.Options().apply {
                        this.inSampleSize = inSampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream, null, decodeOptions)
                    }

                    if (bitmap != null) {
                        val finalBitmap = if (bitmap.width > 2048 || bitmap.height > 2048) {
                            val ratio = minOf(2048f / bitmap.width, 2048f / bitmap.height)
                            val scaledW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
                            val scaledH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
                            val scaled = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
                            if (scaled != bitmap) bitmap.recycle()
                            scaled
                        } else {
                            bitmap
                        }
                        val baos = ByteArrayOutputStream()
                        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                        finalBitmap.recycle()
                        baos.toByteArray()
                    } else {
                        readRawBytes(uri)
                    }
                } else {
                    readRawBytes(uri)
                }
            } catch (t: Throwable) {
                Log.w("ChatViewModel", "Image optimization failed for ${att.name}, using raw bytes: ${t.message}")
                readRawBytes(uri)
            }
        } else {
            readRawBytes(uri)
        }

        if (bytes == null) {
            Log.w("ChatViewModel", "Could not read attachment ${att.name}")
            return null
        }

        // If file is > 1.5MB and wasn't uploaded via HTTP yet, upload it now
        if (bytes.size > 1500 * 1024 && bridgeUrl.isNotBlank()) {
            _currentStatus.value = "Uploading ${att.name} (${bytes.size / (1024 * 1024)}MB)..."
            val serverPath = localFileManager.uploadAttachmentToBridge(bridgeUrl, uri, att.name, att.mimeType)
            if (serverPath != null) {
                return JSONObject().apply {
                    put("name", att.name)
                    put("is_image", isImg)
                    put("server_path", serverPath)
                    put("mime_type", att.mimeType ?: "application/octet-stream")
                }
            }
        }

        // Inline base64 safeguard: maximum 3MB for inline WebSocket payload to prevent frame drops
        if (bytes.size > 3 * 1024 * 1024) {
            val mb = bytes.size / (1024 * 1024)
            Log.w("ChatViewModel", "Attachment ${att.name} ($mb MB) exceeds inline limit and HTTP upload unavailable")
            appendSystemMessage("⚠️ File \"${att.name}\" ($mb MB) is too large for inline message. Please verify Colab bridge connection.")
            return null
        }

        val fileB64 = try {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (t: Throwable) {
            Log.e("ChatViewModel", "Base64 encode error for attachment ${att.name}", t)
            null
        } ?: return null

        return JSONObject().apply {
            put("name", att.name)
            put("is_image", isImg)
            put("mime_type", if (isImg) "image/jpeg" else (att.mimeType ?: "application/octet-stream"))
            put("data", fileB64)
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

        if (_isLoading.value) {
            finalizeStreamingMessage()
        }
        isGenerationCancelled = false

        // Natural language & slash commands for memory management
        if (trimmed.startsWith("/remember", ignoreCase = true)) {
            val memContent = trimmed.removePrefix("/remember").removePrefix(":").trim()
            if (memContent.isNotBlank()) {
                addMemory(memContent, "prefs")
                appendSystemMessage("🧠 Saved to Memory: \"$memContent\"")
            } else {
                appendSystemMessage("ℹ️ Usage: /remember <fact or preference to save>")
            }
            _selectedAttachments.value = emptyList()
            _selectedAttachment.value = null
            return
        } else if (trimmed.startsWith("/forget", ignoreCase = true)) {
            val query = trimmed.removePrefix("/forget").removePrefix(":").trim()
            if (query.isNotBlank()) {
                forgetMemory(query)
            } else {
                appendSystemMessage("ℹ️ Usage: /forget <keyword to remove>")
            }
            _selectedAttachments.value = emptyList()
            _selectedAttachment.value = null
            return
        } else if (trimmed.equals("/memory", ignoreCase = true) || trimmed.equals("/memories", ignoreCase = true)) {
            viewModelScope.launch {
                val count = memoryDao.getTotalCount()
                val enabled = memoryDao.getAllEnabledMemories()
                val summary = if (count == 0) {
                    "🧠 Memory is currently empty. Use `/remember <fact>` to save preferences."
                } else {
                    val preview = enabled.take(5).joinToString("\n") { "• [${it.category.uppercase()}] ${it.content}" }
                    "🧠 Active Memories: ${enabled.size}/$count\n$preview${if (enabled.size > 5) "\n...and ${enabled.size - 5} more." else ""}"
                }
                appendSystemMessage(summary)
            }
            _selectedAttachments.value = emptyList()
            _selectedAttachment.value = null
            return
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

        viewModelScope.launch(Dispatchers.IO) {
            // Encode and optimize files on Dispatchers.IO to prevent UI freeze and OOM
            val filesArray = org.json.JSONArray()
            for (att in attachments) {
                try {
                    val fObj = processAttachment(att)
                    if (fObj != null) {
                        filesArray.put(fObj)
                    }
                } catch (t: Throwable) {
                    Log.w("ChatViewModel", "Failed to process attachment ${att.name}: ${t.message}")
                }
            }

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

            sendTurnPayload(promptText, userMessage.id, filesArray)
        }
    }

    private fun shouldRetrieveMemories(prompt: String): Boolean {
        val lower = prompt.lowercase().trim()

        // Explicit personal memory queries
        val personalPhrases = listOf(
            "about me", "who am i", "my name", "my role", "my job", "my work",
            "my project", "my app", "my preferences", "remember", "recall", "memory",
            "what do you know", "what do you remember", "for me", "recommend me",
            "my stack", "my tech", "my country", "my age", "my background"
        )
        if (personalPhrases.any { lower.contains(it) }) return true

        // Direct first-person intent indicators
        val firstPersonTokens = setOf("i", "me", "my", "mine", "myself")
        val promptWords = lower.split(Regex("[^a-zA-Z0-9_]+")).filter { it.isNotBlank() }.toSet()
        val hasFirstPerson = firstPersonTokens.any { it in promptWords }

        // General queries without personal pronouns -> DO NOT retrieve memories
        val isGenericQuery = lower.matches(Regex("^(what is|how to|how do i|calculate|solve|explain|write a function|define|debug|error|why does|fix)\\s+.*"))
        if (isGenericQuery && !hasFirstPerson) {
            return false
        }

        return hasFirstPerson
    }

    private suspend fun retrieveRelevantMemories(promptText: String): List<JSONObject> {
        if (!_isMemoryEnabled.value || _isTemporaryChat.value) return emptyList()
        return try {
            val allEnabled = memoryDao.getAllEnabledMemories()
            if (allEnabled.isEmpty()) return emptyList()

            val stopWords = setOf(
                "the", "and", "that", "this", "with", "from", "for", "are", "was", "were",
                "what", "how", "when", "where", "which", "who", "why", "can", "could", "would",
                "should", "please", "make", "help", "want", "like", "need", "about", "your",
                "tell", "give", "show", "some", "more", "code", "file", "into", "onto"
            )
            val queryTokens = promptText.lowercase()
                .split(Regex("[^a-zA-Z0-9_]+"))
                .filter { it.length >= 3 && it !in stopWords }
                .toSet()

            val isPersonalIntent = shouldRetrieveMemories(promptText)

            val matchingMemories = allEnabled.mapNotNull { mem ->
                val memLower = mem.content.lowercase()
                val keywordMatches = queryTokens.count { token -> memLower.contains(token) }
                if (keywordMatches > 0) {
                    val score = (keywordMatches * 30) + (mem.importance * 3) + mem.accessCount.coerceAtMost(5)
                    mem to score
                } else if (isPersonalIntent && (mem.category.equals("facts", ignoreCase = true) || mem.category.equals("personal", ignoreCase = true))) {
                    val score = (mem.importance * 2)
                    mem to score
                } else {
                    null
                }
            }

            if (matchingMemories.isEmpty()) {
                emptyList()
            } else {
                val selected = matchingMemories
                    .sortedByDescending { it.second }
                    .take(5) // ChatGPT standard: strictly 1-5 top relevant memories, never 40!
                    .map { it.first }

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
    }

    private suspend fun sendTurnPayload(
        promptText: String,
        excludeMessageId: String,
        filesArray: org.json.JSONArray = org.json.JSONArray()
    ) {
        val effort = _reasoningEffort.value
        val memoryList = retrieveRelevantMemories(promptText)

        // Verbatim multi-turn conversation history with dynamic 60,000-char context window budget
        val historyArray = org.json.JSONArray()
        val eligibleTurns = _messages.value
            .filter { (it.role == "user" || it.role == "assistant") && it.content.isNotBlank() && it.id != excludeMessageId }
            .takeLast(30)
            .reversed()

        var totalBudgetChars = 0
        val budgetedTurns = mutableListOf<Message>()
        for (m in eligibleTurns) {
            val contentSnippet = m.content.take(4000)
            if (totalBudgetChars + contentSnippet.length > 60_000) break
            budgetedTurns.add(m)
            totalBudgetChars += contentSnippet.length
        }
        budgetedTurns.reversed().forEach { m ->
            historyArray.put(JSONObject().apply {
                put("role", m.role)
                put("content", m.content.take(4000))
            })
        }

        val p = _personalization.value
        val payload = JSONObject().apply {
            put("message", promptText)
            put("conversation_id", currentConversationId)
            put("workspace_id", currentConversationId)
            put("cwd", _currentCwd.value)
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
                    if (p.codeLanguage.isNotBlank() && p.codeLanguage != "Auto") put("code_language", p.codeLanguage)
                    put("enable_examples", p.enableExamples)
                    put("enable_proactive", p.enableProactiveInsights)
                    put("enable_critical", p.enableCriticalFeedback)
                    put("enable_emoji", p.enableEmoji)
                    if (p.avoidTopics.isNotBlank()) put("avoid_topics", p.avoidTopics)
                    if (p.aboutUser.isNotBlank()) put("about_user", p.aboutUser)
                    if (p.responsePreferences.isNotBlank()) put("response_preferences", p.responsePreferences)
                    if (p.customContext.isNotBlank()) put("custom_context", p.customContext)
                    if (p.extraInstructions.isNotBlank()) put("extra_instructions", p.extraInstructions)
                })
            }
            // Custom instructions payload
            val resolvedAbout = p.aboutUser.ifBlank { _customInstructions.value.aboutUser }
            val resolvedResp = p.responsePreferences.ifBlank { _customInstructions.value.responsePreferences }
            if (!_isTemporaryChat.value && (resolvedAbout.isNotBlank() || resolvedResp.isNotBlank())) {
                put("custom_instructions", JSONObject().apply {
                    put("about_user", resolvedAbout)
                    put("response_preferences", resolvedResp)
                    put("tone_preset", p.toneStyle)
                    put("is_enabled", true)
                })
            }
            put("auto_memory", _isAutoMemoryEnabled.value && !_isTemporaryChat.value)
            put("is_temporary", _isTemporaryChat.value)
            put("client_metadata", buildClientMetadata())

            // Multi-files payload
            if (filesArray.length() > 0) {
                put("files", filesArray)
                val firstObj = filesArray.getJSONObject(0)
                put("file_name", firstObj.getString("name"))
                put("file_is_image", firstObj.getBoolean("is_image"))
                put("file_data", firstObj.getString("data"))
            }
        }.toString()

        lastReceivedSeq = -1
        val sent = webSocketClient.sendMessage(payload)
        if (sent) {
            _isLoading.value = true
            _currentStatus.value = "Next AI thinking..."
        } else {
            appendSystemMessage("⚠️ Message could not be sent. Payload may exceed safe limit or WebSocket is disconnected.")
            _isLoading.value = false
        }
    }

    fun editAndResendMessage(messageId: String, newContent: String) {
        val trimmed = newContent.trim()
        if (trimmed.isEmpty()) return

        val currentList = _messages.value
        val targetIndex = currentList.indexOfFirst { it.id == messageId }
        if (targetIndex < 0) return
        val targetMsg = currentList[targetIndex]
        if (targetMsg.role != "user") return

        if (_isLoading.value) {
            stopGenerating()
        }

        val branchGroupId = targetMsg.parentMessageId?.takeIf { it.isNotBlank() && it != "null" } ?: targetMsg.id

        viewModelScope.launch {
            val currentBranchCount = chatDao.getBranchCountForMessage(currentConversationId, branchGroupId)
            val newBranchIndex = if (currentBranchCount == 0) 1 else currentBranchCount

            // If original wasn't tagged with branchGroupId, update it in DB
            if (targetMsg.parentMessageId.isNullOrBlank() || targetMsg.parentMessageId == "null") {
                val updatedOriginal = targetMsg.copy(parentMessageId = branchGroupId, branchIndex = 0)
                saveMessageToDb(updatedOriginal)
            }

            // Create new branched user message
            val newMsg = targetMsg.copy(
                id = UUID.randomUUID().toString(),
                content = trimmed,
                timestamp = System.currentTimeMillis(),
                parentMessageId = branchGroupId,
                branchIndex = newBranchIndex,
                totalBranches = newBranchIndex + 1
            )
            saveMessageToDb(newMsg)

            // Select this new branch
            _activeBranchMap.value = _activeBranchMap.value + (branchGroupId to newBranchIndex)

            // In-memory messages truncation: keep only up to targetIndex and append new branch
            val truncatedMessages = currentList.subList(0, targetIndex).toMutableList()
            truncatedMessages.add(newMsg)
            _messages.value = truncatedMessages

            // Re-send to bridge
            sendTurnPayload(trimmed, newMsg.id)
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
        var cleaned = firstUserMsg
            .removePrefix("/boost").removePrefix("/goal").removePrefix("/plan")
            .removePrefix("/browser").removePrefix("/learn").removePrefix("/grill-me")
            .removePrefix("/schedule").removePrefix("/teamwork-preview").removePrefix("/remember")
            .trim()
        if (cleaned.isBlank()) return "New Chat"

        // Strip common conversational preamble (ChatGPT-style clean topic extraction)
        val preambles = listOf(
            "can you please help me with", "can you help me with", "could you help me with",
            "how do i", "how can i", "how to", "please write a", "please write", "write a", "write",
            "can you tell me about", "tell me about", "what is the best way to", "what is", "explain how to",
            "explain", "show me how to", "show me", "i want to know about", "i want to"
        )
        val lowerCleaned = cleaned.lowercase()
        for (p in preambles) {
            if (lowerCleaned.startsWith(p)) {
                cleaned = cleaned.substring(p.length).trim()
                break
            }
        }

        val firstSentence = cleaned.split(Regex("[.!?\\n]")).firstOrNull { it.trim().length > 2 }?.trim() ?: cleaned
        val words = firstSentence.split(Regex("\\s+")).filter { it.isNotBlank() }
        val topic = words.take(6).joinToString(" ")
        val finalTitle = topic.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        return finalTitle.take(36).trimEnd().let { if (it.length < finalTitle.length) "$it…" else it }
    }

    private fun saveMessageToDb(message: Message) {
        viewModelScope.launch {
            val existingConv = chatDao.getConversationById(currentConversationId)
            val createdAt = existingConv?.createdAt ?: System.currentTimeMillis()
            val modelId = existingConv?.modelId ?: _selectedModel.value.id
            val isPinned = existingConv?.isPinned ?: false
            val customTitle = existingConv?.customTitle ?: false
            val currentTitle = if (customTitle && !existingConv?.title.isNullOrBlank()) {
                existingConv.title
            } else {
                generateTitle(_messages.value)
            }

            chatDao.insertConversation(
                ConversationEntity(
                    id = currentConversationId,
                    title = currentTitle,
                    createdAt = createdAt,
                    updatedAt = System.currentTimeMillis(),
                    modelId = modelId,
                    isPinned = isPinned,
                    messageCount = existingConv?.messageCount ?: 0,
                    customTitle = customTitle,
                    lastKnownCwd = _currentCwd.value,
                    agySessionId = existingConv?.agySessionId
                )
            )

            if (!customTitle) {
                chatDao.autoUpdateConversationTitle(currentConversationId, currentTitle)
            }
            chatDao.refreshMessageCount(currentConversationId)

            // Save last active conversation ID to SharedPreferences for instant app restore
            val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("last_active_conversation_id", currentConversationId).apply()

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

            val toolExecutionsJson = if (message.toolExecutions.isNotEmpty() || message.tokensPerSecond != null || message.durationSeconds != null) {
                val envelope = org.json.JSONObject()
                if (message.tokensPerSecond != null) envelope.put("tokensPerSecond", message.tokensPerSecond)
                if (message.durationSeconds != null) envelope.put("durationSeconds", message.durationSeconds)
                val arr = org.json.JSONArray()
                message.toolExecutions.forEach { t ->
                    arr.put(JSONObject().apply {
                        put("id", t.id)
                        put("toolName", t.toolName)
                        put("state", t.state)
                        put("command", t.command ?: JSONObject.NULL)
                        put("targetFile", t.targetFile ?: JSONObject.NULL)
                        put("parametersSummary", t.parametersSummary ?: JSONObject.NULL)
                        put("output", t.output ?: JSONObject.NULL)
                        put("durationSeconds", t.durationSeconds)
                    })
                }
                envelope.put("tools", arr)
                envelope.toString()
            } else null

            val memoryUpdatesJson = if (message.memoryUpdates.isNotEmpty()) {
                val arr = org.json.JSONArray()
                message.memoryUpdates.forEach { arr.put(it) }
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
                    isPinned = message.isPinned,
                    thinking = message.thinking,
                    toolExecutionsJson = toolExecutionsJson,
                    modelName = message.modelName ?: _selectedModel.value.name,
                    replyToContent = message.replyToContent,
                    replyToRole = message.replyToRole,
                    memoryUpdatesJson = memoryUpdatesJson,
                    parentMessageId = message.parentMessageId,
                    branchIndex = message.branchIndex
                )
            )
            chatDao.refreshMessageCount(currentConversationId)
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

            // Send feedback telemetry event to AGY bridge backend if connected
            if (_connectionState.value == ConnectionState.CONNECTED) {
                try {
                    val feedbackPayload = JSONObject().apply {
                        put("action", "message_feedback")
                        put("message_id", messageId)
                        put("conversation_id", currentConversationId)
                        put("feedback", newFeedback ?: "neutral")
                        put("timestamp", System.currentTimeMillis())
                    }
                    webSocketClient.sendMessage(feedbackPayload.toString())
                } catch (t: Throwable) {
                    Log.w("ChatViewModel", "Failed to dispatch feedback to bridge", t)
                }
            }
        }
    }

    fun regenerateLastResponse() {
        val msgs = _messages.value
        val lastUserIdx = msgs.indexOfLast { it.role == "user" }
        if (lastUserIdx < 0) return
        val lastUserMsg = msgs[lastUserIdx]
        val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
        val isAssistantForCurrentTurn = lastAssistantIdx >= 0 && lastAssistantIdx > lastUserIdx
        val lastAssistant = if (isAssistantForCurrentTurn) msgs[lastAssistantIdx] else null

        if (_isLoading.value) {
            stopGenerating()
        }

        viewModelScope.launch {
            val newId = UUID.randomUUID().toString()

            if (lastAssistant != null) {
                val branchGroupId = lastAssistant.parentMessageId?.takeIf { it.isNotBlank() && it != "null" } ?: lastAssistant.id
                val currentBranchCount = chatDao.getBranchCountForMessage(currentConversationId, branchGroupId)
                val newBranchIndex = if (currentBranchCount == 0) 1 else currentBranchCount

                // Preserve original assistant response as branch 0 in DB
                if (lastAssistant.parentMessageId.isNullOrBlank() || lastAssistant.parentMessageId == "null") {
                    val updatedOriginal = lastAssistant.copy(parentMessageId = branchGroupId, branchIndex = 0)
                    saveMessageToDb(updatedOriginal)
                }

                streamingParentMessageId = branchGroupId
                streamingBranchIndex = newBranchIndex
                _activeBranchMap.value = _activeBranchMap.value + (branchGroupId to newBranchIndex)

                // Replace old assistant message in-place with a new streaming placeholder
                val updatedList = msgs.toMutableList()
                val placeholder = Message(
                    id = newId,
                    role = "assistant",
                    content = "",
                    timestamp = System.currentTimeMillis(),
                    isStreaming = true,
                    isThinking = false,
                    parentMessageId = streamingParentMessageId,
                    branchIndex = streamingBranchIndex
                )
                val replaceIdx = updatedList.indexOfFirst { it.id == lastAssistant.id }
                if (replaceIdx >= 0) {
                    updatedList[replaceIdx] = placeholder
                } else {
                    updatedList.add(placeholder)
                }
                streamingMessageId = newId
                _messages.value = updatedList
            } else {
                // There is NO assistant response for lastUserMsg (e.g. previous send failed or error)
                // Remove any system error message badges shown after lastUserIdx
                val cleanedList = msgs.filterIndexed { index, msg ->
                    !(index > lastUserIdx && msg.role == "system" && msg.content.startsWith("⚠️"))
                }.toMutableList()

                streamingParentMessageId = null
                streamingBranchIndex = 0

                val placeholder = Message(
                    id = newId,
                    role = "assistant",
                    content = "",
                    timestamp = System.currentTimeMillis(),
                    isStreaming = true,
                    isThinking = false
                )
                cleanedList.add(placeholder)
                streamingMessageId = newId
                _messages.value = cleanedList
            }

            val filesArray = org.json.JSONArray()
            if (lastUserMsg.attachments.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    for (att in lastUserMsg.attachments) {
                        try {
                            val fObj = processAttachment(att)
                            if (fObj != null) filesArray.put(fObj)
                        } catch (t: Throwable) {
                            Log.w("ChatViewModel", "Failed to re-process attachment ${att.name}: ${t.message}")
                        }
                    }
                }
            }

            _currentStatus.value = "Regenerating response..."
            sendTurnPayload(lastUserMsg.content, lastUserMsg.id, filesArray)
        }
    }

    fun renameConversation(conversationId: String, newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            try {
                chatDao.manualRenameConversation(conversationId, trimmed)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to rename conversation $conversationId", e)
            }
        }
    }

    fun toggleConversationPinned(conversationId: String) {
        viewModelScope.launch {
            try {
                val conv = chatDao.getConversationById(conversationId) ?: return@launch
                chatDao.updateConversationPinned(conversationId, !conv.isPinned)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to toggle pin for $conversationId", e)
            }
        }
    }

    fun groupConversationsByDate(convs: List<ConversationEntity>): List<ConversationGroup> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStart = cal.timeInMillis
        val yesterdayStart = todayStart - 86_400_000L
        val sevenDaysAgo = todayStart - (7 * 86_400_000L)
        val thirtyDaysAgo = todayStart - (30 * 86_400_000L)

        val pinned = convs.filter { it.isPinned }
        val unpinned = convs.filter { !it.isPinned }

        return buildList {
            if (pinned.isNotEmpty()) {
                add(ConversationGroup("📌 Pinned", pinned))
            }
            val today = unpinned.filter { it.updatedAt >= todayStart }
            if (today.isNotEmpty()) add(ConversationGroup("Today", today))

            val yesterday = unpinned.filter { it.updatedAt in yesterdayStart until todayStart }
            if (yesterday.isNotEmpty()) add(ConversationGroup("Yesterday", yesterday))

            val prev7 = unpinned.filter { it.updatedAt in sevenDaysAgo until yesterdayStart }
            if (prev7.isNotEmpty()) add(ConversationGroup("Previous 7 Days", prev7))

            val prev30 = unpinned.filter { it.updatedAt in thirtyDaysAgo until sevenDaysAgo }
            if (prev30.isNotEmpty()) add(ConversationGroup("Previous 30 Days", prev30))

            val older = unpinned.filter { it.updatedAt < thirtyDaysAgo }
            if (older.isNotEmpty()) add(ConversationGroup("Older", older))
        }
    }

    fun clearAllConversations() {
        viewModelScope.launch {
            try {
                val convs = chatDao.getAllConversationsList()
                convs.forEach { conv ->
                    fileDao.deleteFilesForConversation(conv.id)
                }
                fileDao.deleteFilesForConversation(currentConversationId)
                fileDao.clearAllFiles()
                chatDao.clearAllConversations()
                chatDao.clearAllMessages()
                startNewConversation()
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to clear all conversations", e)
            }
        }
    }

    fun clearChat() {
        startNewConversation()
    }

    fun startNewConversation() {
        val newId = UUID.randomUUID().toString()
        _messages.value = emptyList()
        _selectedAttachments.value = emptyList()
        _selectedAttachment.value = null
        _replyToMessage.value = null
        _sessionFiles.value = emptyList()
        _activeBranchMap.value = emptyMap()
        streamingMessageId = null
        _currentStatus.value = null
        _isLoading.value = false
        lastReceivedSeq = -1
        loadConversation(newId)
    }

    fun loadConversations() {
        loadConversation(currentConversationId)
    }

    fun loadConversation(conversationId: String) {
        val isSwitching = conversationId != currentConversationId
        currentConversationId = conversationId
        _activeConversationId.value = conversationId
        if (isSwitching) {
            streamingMessageId = null
            _isLoading.value = false
            _currentStatus.value = null
            lastReceivedSeq = -1
        }
        _selectedAttachments.value = emptyList()
        _selectedAttachment.value = null
        _replyToMessage.value = null
        _activeBranchMap.value = emptyMap()
        _sessionFiles.value = emptyList()

        val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("last_active_conversation_id", conversationId).apply()

        viewModelScope.launch {
            try {
                val conv = chatDao.getConversationById(conversationId)
                if (conv?.modelId != null) {
                    _selectedModel.value = ModelRegistry.findById(conv.modelId)
                }
                if (!conv?.lastKnownCwd.isNullOrBlank()) {
                    _currentCwd.value = conv.lastKnownCwd
                }
            } catch (_: Exception) {}
        }

        messagesCollectorJob?.cancel()
        messagesCollectorJob = viewModelScope.launch {
            combine(
                chatDao.getMessagesForConversation(conversationId),
                _activeBranchMap
            ) { entities, branchMap ->
                val branchGroups = entities.groupBy { it.parentMessageId?.takeIf { p -> p.isNotBlank() && p != "null" } ?: it.id }
                branchGroups.values.map { groupEntities ->
                    val totalBranches = groupEntities.size
                    val branchGroupId = groupEntities.first().parentMessageId?.takeIf { p -> p.isNotBlank() && p != "null" } ?: groupEntities.first().id
                    val activeIndex = branchMap[branchGroupId] ?: (totalBranches - 1)
                    val chosenEntity = groupEntities.find { it.branchIndex == activeIndex } ?: groupEntities.last()
                    val minTimestamp = groupEntities.minOf { it.timestamp }
                    entityToMessage(
                        chosenEntity,
                        totalBranches = totalBranches,
                        activeBranchIndex = chosenEntity.branchIndex
                    ) to minTimestamp
                }.sortedBy { it.second }.map { it.first }
            }.collectLatest { loadedMessages ->
                val currentList = _messages.value
                val activeStreamId = streamingMessageId ?: currentList.lastOrNull { it.role == "assistant" && (it.isStreaming || it.isThinking) }?.id
                val resolvedMessages = if (activeStreamId != null) {
                    val liveStreamingMsg = currentList.find { it.id == activeStreamId }
                    if (liveStreamingMsg != null) {
                        val hasActive = loadedMessages.any { it.id == activeStreamId }
                        val updatedLoaded = loadedMessages.map { msg ->
                            if (msg.id == activeStreamId) {
                                msg.copy(
                                    content = if (liveStreamingMsg.content.length > msg.content.length) liveStreamingMsg.content else msg.content,
                                    thinking = if ((liveStreamingMsg.thinking?.length ?: 0) > (msg.thinking?.length ?: 0)) liveStreamingMsg.thinking else msg.thinking,
                                    thinkingDurationMs = if (liveStreamingMsg.thinkingDurationMs > 0L) liveStreamingMsg.thinkingDurationMs else msg.thinkingDurationMs,
                                    isStreaming = liveStreamingMsg.isStreaming || msg.isStreaming,
                                    isThinking = liveStreamingMsg.isThinking || msg.isThinking,
                                    isThinkingExpanded = liveStreamingMsg.isThinkingExpanded,
                                    toolExecutions = if (liveStreamingMsg.toolExecutions.isNotEmpty()) liveStreamingMsg.toolExecutions else msg.toolExecutions,
                                    toolExecution = liveStreamingMsg.toolExecution ?: msg.toolExecution
                                )
                            } else msg
                        }
                        if (hasActive) updatedLoaded else updatedLoaded + liveStreamingMsg
                    } else loadedMessages
                } else loadedMessages

                _messages.value = resolvedMessages

                // Extract all file references generated in this conversation from tools, cached files, and inline artifacts
                val toolFiles = resolvedMessages.flatMap { msg ->
                    msg.toolExecutions.filter { t ->
                        val tName = t.toolName.lowercase()
                        tName in setOf("write_to_file", "replace_file_content", "create_file", "generate_image") ||
                            (t.targetFile?.contains("/brain/") == true)
                    }.mapNotNull { it.targetFile }
                }.map { cleanFilePathOrUrl(it) }.filter { it.isNotBlank() && hasFileExtension(it) }

                val cachedFiles = try {
                    fileDao.getFilesForConversationList(conversationId)
                        .map { cleanFilePathOrUrl(it.remotePath) }
                        .filter { it.isNotBlank() && hasFileExtension(it) }
                } catch (_: Exception) { emptyList() }

                val inlineArtifacts = resolvedMessages.flatMap { msg ->
                    val list = mutableListOf<String>()
                    val antRegex = Regex("""<(?:antArtifact|artifact)\s+([^>]+)>""")
                    for (m in antRegex.findAll(msg.content)) {
                        val attrs = m.groupValues[1]
                        val id = Regex("""identifier=["']([^"']+)["']""").find(attrs)?.groupValues?.get(1)
                        val title = Regex("""title=["']([^"']+)["']""").find(attrs)?.groupValues?.get(1)
                        val best = id ?: title
                        if (!best.isNullOrBlank()) {
                            val clean = cleanFilePathOrUrl(best)
                            if (hasFileExtension(clean)) list.add(clean)
                        }
                    }
                    val linkRegex = Regex("""\[([^\]]+)\]\(([^)]+\.(?:md|txt|py|kt|java|json|csv|pdf|html|svg|sh|png|jpg|jpeg|webp|cpp|c|rs|go))\)""")
                    for (m in linkRegex.findAll(msg.content)) {
                        val fPath = cleanFilePathOrUrl(m.groupValues[2].trim())
                        if (fPath.isNotBlank() && !fPath.startsWith("http://") && !fPath.startsWith("https://") && hasFileExtension(fPath)) {
                            list.add(fPath)
                        }
                    }
                    list
                }

                val allFiles = (toolFiles + cachedFiles + inlineArtifacts).distinctBy { cleanFilePathOrUrl(it) }
                _sessionFiles.value = allFiles
            }
        }
    }

    /**
     * Batch delete artifacts from local storage, Room DB, and active session files list.
     */
    fun deleteArtifactFiles(paths: List<String>, onComplete: ((deletedCount: Int) -> Unit)? = null) {
        if (paths.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val normalizedToDelete = paths.map { cleanFilePathOrUrl(it) }.toSet()
            val deletedCount = localFileManager.deleteCachedFiles(paths)
            val updated = _sessionFiles.value.filter {
                cleanFilePathOrUrl(it) !in normalizedToDelete
            }
            _sessionFiles.value = updated
            withContext(Dispatchers.Main) {
                onComplete?.invoke(deletedCount)
            }
        }
    }

    /**
     * Batch download/export selected artifact files to the device's public Downloads directory.
     */
    fun downloadArtifactFiles(paths: List<String>, onComplete: (successCount: Int, total: Int, message: String) -> Unit) {
        if (paths.isEmpty()) {
            onComplete(0, 0, "No files selected")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = localFileManager.batchExportToDownloads(paths)
            withContext(Dispatchers.Main) {
                onComplete(result.first, paths.size, result.second)
            }
        }
    }

    private fun entityToMessage(entity: MessageEntity, totalBranches: Int = 1, activeBranchIndex: Int = 0): Message {
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

        var extractedTokensPerSec: Double? = null
        var extractedDurationSec: Double? = null

        val parsedToolExecutions = if (!entity.toolExecutionsJson.isNullOrBlank()) {
            try {
                val trimmed = entity.toolExecutionsJson.trim()
                val arr: org.json.JSONArray
                if (trimmed.startsWith("{")) {
                    val envelope = org.json.JSONObject(trimmed)
                    extractedTokensPerSec = if (envelope.has("tokensPerSecond")) envelope.optDouble("tokensPerSecond", 0.0).takeIf { it > 0.0 } else null
                    extractedDurationSec = if (envelope.has("durationSeconds")) envelope.optDouble("durationSeconds", 0.0).takeIf { it > 0.0 } else null
                    arr = envelope.optJSONArray("tools") ?: org.json.JSONArray()
                } else {
                    arr = org.json.JSONArray(trimmed)
                }
                val list = mutableListOf<ToolExecutionItem>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        ToolExecutionItem(
                            id = o.optString("id", UUID.randomUUID().toString()),
                            toolName = o.getString("toolName"),
                            state = o.optString("state", "DONE"),
                            command = if (o.isNull("command")) null else o.optString("command"),
                            targetFile = if (o.isNull("targetFile")) null else o.optString("targetFile"),
                            parametersSummary = if (o.isNull("parametersSummary")) null else o.optString("parametersSummary"),
                            output = if (o.isNull("output")) null else o.optString("output"),
                            durationSeconds = o.optDouble("durationSeconds", 0.0)
                        )
                    )
                }
                list
            } catch (_: Exception) {
                emptyList()
            }
        } else emptyList()

        val parsedMemoryUpdates = if (!entity.memoryUpdatesJson.isNullOrBlank()) {
            try {
                val arr = org.json.JSONArray(entity.memoryUpdatesJson)
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    list.add(arr.getString(i))
                }
                list
            } catch (_: Exception) {
                emptyList()
            }
        } else emptyList()

        return Message(
            id = entity.id,
            role = entity.role,
            content = entity.content,
            timestamp = entity.timestamp,
            attachmentUri = entity.attachmentUri,
            attachmentName = entity.attachmentName,
            attachmentIsImage = entity.attachmentIsImage,
            attachments = parsedAttachments,
            feedback = entity.feedback,
            isPinned = entity.isPinned,
            thinking = entity.thinking,
            toolExecutions = parsedToolExecutions,
            toolExecution = if (parsedToolExecutions.isNotEmpty()) "Completed ${parsedToolExecutions.last().toolName}" else null,
            modelName = entity.modelName,
            replyToContent = entity.replyToContent,
            replyToRole = entity.replyToRole,
            memoryUpdates = parsedMemoryUpdates,
            parentMessageId = entity.parentMessageId,
            branchIndex = activeBranchIndex,
            totalBranches = totalBranches,
            tokensPerSecond = extractedTokensPerSec,
            durationSeconds = extractedDurationSec
        )
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            try {
                chatDao.deleteMessagesForConversation(conversationId)
                chatDao.deleteConversation(conversationId)
                fileDao.deleteFilesForConversation(conversationId)
                if (currentConversationId == conversationId) {
                    startNewConversation()
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to delete conversation $conversationId", e)
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
