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
import com.agychat.app.data.local.MemoryDao
import com.agychat.app.data.local.MemoryEntity
import com.agychat.app.domain.model.ThinkingLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val webSocketClient: AgyWebSocketClient,
    private val chatDao: ChatDao,
    private val memoryDao: MemoryDao
) : ViewModel() {

    // ChatGPT-Style Persistent Memory System
    val memories = memoryDao.getAllMemoriesFlow()
    val enabledMemoriesCount = memoryDao.getEnabledCountFlow()

    private val _isMemoryEnabled = MutableStateFlow(true)
    val isMemoryEnabled: StateFlow<Boolean> = _isMemoryEnabled.asStateFlow()

    fun setMemoryEnabled(enabled: Boolean) {
        _isMemoryEnabled.value = enabled
        val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("memory_enabled", enabled).apply()
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

    private val _selectedAttachment = MutableStateFlow<AttachmentItem?>(null)
    val selectedAttachment: StateFlow<AttachmentItem?> = _selectedAttachment.asStateFlow()

    fun setAttachment(item: AttachmentItem?) {
        _selectedAttachment.value = item
    }

    fun clearAttachment() {
        _selectedAttachment.value = null
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
    val conversations = chatDao.getAllConversations()

    init {
        // Load saved model preference
        val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
        val savedModelId = prefs.getString("selected_model", ModelRegistry.DEFAULT_MODEL.id)
        _selectedModel.value = ModelRegistry.findById(savedModelId)

        val savedEffort = prefs.getString("reasoning_effort", "high") ?: "high"
        _reasoningEffort.value = savedEffort

        val savedMemoryEnabled = prefs.getBoolean("memory_enabled", true)
        _isMemoryEnabled.value = savedMemoryEnabled

        val savedUrl = prefs.getString("server_url", "wss://fell-worldwide-mistakes-asks.trycloudflare.com/ws")
        if (!savedUrl.isNullOrBlank()) {
            connectToServer(savedUrl)
        }
    }

    fun connectToServer(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return

        // Persist URL so it survives app restarts
        val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("server_url", trimmed).apply()

        _serverUrl.value = trimmed
        _connectionState.value = ConnectionState.CONNECTING
        _currentStatus.value = "Connecting to Colab bridge..."

        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            webSocketClient.connect(trimmed).collectLatest { event ->
                when (event) {
                    is WsEvent.Connected -> {
                        _connectionState.value = ConnectionState.CONNECTED
                        _currentStatus.value = null
                    }
                    is WsEvent.Message -> handleIncomingMessage(event.text)
                    is WsEvent.Error -> {
                        _connectionState.value = ConnectionState.ERROR
                        _isLoading.value = false
                        _currentStatus.value = "Disconnected. Tap to retry."
                    }
                    is WsEvent.Closed -> {
                        _connectionState.value = ConnectionState.DISCONNECTED
                        _isLoading.value = false
                        _currentStatus.value = null
                    }
                }
            }
        }
    }

    fun reconnect() {
        val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
        val url = _serverUrl.value.ifBlank { prefs.getString("server_url", "") ?: "" }
        if (url.isNotBlank()) {
            connectToServer(url)
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
            val resolvedContent = if (!finalContent.isNullOrBlank()) sanitizeChunk(finalContent) else cur.content
            val finalized = cur.copy(
                content = resolvedContent,
                isStreaming = false,
                isThinking = false
            )
            list[idx] = finalized
            _messages.value = list
            saveMessageToDb(finalized)
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
        val attachment = _selectedAttachment.value
        if (trimmed.isBlank() && attachment == null) return

        if (_connectionState.value != ConnectionState.CONNECTED) {
            appendSystemMessage("⚠️ Not connected to Colab Bridge. Tap reconnect or open Settings.")
            return
        }

        // Automatic Memory Detection: If user starts with /remember
        if (trimmed.startsWith("/remember", ignoreCase = true)) {
            val memContent = trimmed.removePrefix("/remember").removePrefix(":").trim()
            if (memContent.isNotBlank()) {
                addMemory(memContent, "preference")
                appendSystemMessage("🧠 Saved to Memory: \"$memContent\"")
            }
        }

        val userMessage = Message(
            id = UUID.randomUUID().toString(),
            role = "user",
            content = trimmed.ifBlank { "Sent an attachment: ${attachment?.name}" },
            timestamp = System.currentTimeMillis(),
            attachmentUri = attachment?.uri,
            attachmentName = attachment?.name,
            attachmentIsImage = attachment?.isImage ?: false
        )
        _selectedAttachment.value = null
        _messages.value = _messages.value + userMessage
        saveMessageToDb(userMessage)

        var fileBase64: String? = null
        if (attachment != null) {
            try {
                val uri = Uri.parse(attachment.uri)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    if (bytes.size <= 5 * 1024 * 1024) {
                        fileBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    }
                }
            } catch (e: Exception) {
                // Ignore read errors
            }
        }

        viewModelScope.launch {
            val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
            val effort = prefs.getString("reasoning_effort", "high") ?: "high"

            // Collect enabled memories to include in context
            val memoryList = if (_isMemoryEnabled.value) {
                try {
                    memoryDao.getAllEnabledMemories().map { it.content }
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            val payload = JSONObject().apply {
                put("message", trimmed.ifBlank { "Please inspect the attached file: ${attachment?.name}" })
                put("conversation_id", currentConversationId)
                put("effort", effort)
                put("model", _selectedModel.value.id)
                if (memoryList.isNotEmpty()) {
                    val memArray = org.json.JSONArray()
                    memoryList.forEach { memArray.put(it) }
                    put("memories", memArray)
                }
                if (attachment != null) {
                    put("file_name", attachment.name)
                    put("file_is_image", attachment.isImage)
                    if (fileBase64 != null) {
                        put("file_data", fileBase64)
                    }
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

    private fun saveMessageToDb(message: Message) {
        viewModelScope.launch {
            val firstUserPrompt = _messages.value.firstOrNull { it.role == "user" }?.content?.take(45) ?: "New Chat"
            chatDao.insertConversation(
                ConversationEntity(
                    id = currentConversationId,
                    title = firstUserPrompt,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            chatDao.insertMessage(
                MessageEntity(
                    id = message.id,
                    conversationId = currentConversationId,
                    role = message.role,
                    content = message.content,
                    timestamp = message.timestamp,
                    attachmentUri = message.attachmentUri,
                    attachmentName = message.attachmentName,
                    attachmentIsImage = message.attachmentIsImage,
                    feedback = message.feedback
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
        
        // Resend the last user message text
        val effort = _reasoningEffort.value

        val payload = JSONObject().apply {
            put("message", lastUserMsg.content)
            put("conversation_id", currentConversationId)
            put("effort", effort)
            put("model", _selectedModel.value.id)
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
        _selectedAttachment.value = null
        streamingMessageId = null
        currentConversationId = UUID.randomUUID().toString()
        _currentStatus.value = null
        _isLoading.value = false
    }

    fun loadConversation(conversationId: String) {
        currentConversationId = conversationId
        streamingMessageId = null
        _selectedAttachment.value = null
        _isLoading.value = false
        _currentStatus.value = null

        viewModelScope.launch {
            chatDao.getMessagesForConversation(conversationId).collectLatest { entities ->
                _messages.value = entities.map {
                    Message(
                        id = it.id,
                        role = it.role,
                        content = it.content,
                        timestamp = it.timestamp,
                        attachmentUri = it.attachmentUri,
                        attachmentName = it.attachmentName,
                        attachmentIsImage = it.attachmentIsImage,
                        feedback = it.feedback
                    )
                }
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
    }
}
