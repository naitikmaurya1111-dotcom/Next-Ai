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
import com.agychat.app.domain.model.AttachmentItem
import com.agychat.app.domain.model.ConnectionState
import com.agychat.app.domain.model.Message
import com.agychat.app.domain.model.SlashCommand
import com.agychat.app.domain.model.WsEvent
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
    private val chatDao: ChatDao
) : ViewModel() {

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
        // Auto-connect to last saved URL on app launch, or fall back to default bridge URL
        val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
        val savedEffort = prefs.getString("reasoning_effort", "high") ?: "high"
        _reasoningEffort.value = savedEffort

        val savedUrl = prefs.getString("server_url", "wss://english-memories-opens-judicial.trycloudflare.com/ws")
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
                    _currentStatus.value = content
                }
                "thinking" -> {
                    appendThinkingChunk(content)
                }
                "tool" -> {
                    updateToolStatus(content)
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
                    appendContentChunk(rawText)
                }
            }
        } catch (e: Exception) {
            appendContentChunk(rawText)
        }
    }

    private fun appendContentChunk(chunk: String) {
        val list = _messages.value.toMutableList()
        val idx = list.indexOfFirst { it.id == streamingMessageId }

        if (idx >= 0) {
            val cur = list[idx]
            list[idx] = cur.copy(
                content = cur.content + chunk,
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
                    content = chunk,
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
        val list = _messages.value.toMutableList()
        val idx = list.indexOfFirst { it.id == streamingMessageId }

        if (idx >= 0) {
            val cur = list[idx]
            val prevThinking = cur.thinking ?: ""
            list[idx] = cur.copy(
                thinking = prevThinking + chunk,
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
                    thinking = chunk,
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
        val list = _messages.value.toMutableList()
        val idx = list.indexOfFirst { it.id == streamingMessageId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(toolExecution = toolText)
            _messages.value = list
        }
    }

    private fun finalizeStreamingMessage(finalContent: String? = null) {
        val id = streamingMessageId ?: return
        val list = _messages.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val cur = list[idx]
            val resolvedContent = if (!finalContent.isNullOrBlank()) finalContent else cur.content
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

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        val attachment = _selectedAttachment.value
        if (trimmed.isBlank() && attachment == null) return

        if (_connectionState.value != ConnectionState.CONNECTED) {
            appendSystemMessage("⚠️ Not connected to Colab Bridge. Tap reconnect or open Settings.")
            return
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

        val prefs = context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)
        val effort = prefs.getString("reasoning_effort", "high") ?: "high"

        val payload = JSONObject().apply {
            put("message", trimmed.ifBlank { "Please inspect the attached file: ${attachment?.name}" })
            put("conversation_id", currentConversationId)
            put("effort", effort)
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
        _currentStatus.value = "Sending to AGY..."
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
