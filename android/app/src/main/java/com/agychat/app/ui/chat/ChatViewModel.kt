package com.agychat.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agychat.app.data.local.ChatDao
import com.agychat.app.data.local.ConversationEntity
import com.agychat.app.data.local.MessageEntity
import com.agychat.app.data.network.AgyWebSocketClient
import com.agychat.app.domain.model.ConnectionState
import com.agychat.app.domain.model.Message
import com.agychat.app.domain.model.SlashCommand
import com.agychat.app.domain.model.WsEvent
import dagger.hilt.android.lifecycle.HiltViewModel
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

    // Active conversation ID
    var currentConversationId: String = UUID.randomUUID().toString()
        private set

    // Stream tracking
    private var streamingMessageId: String? = null
    private var connectionJob: Job? = null

    // Real-time conversations list from Room DB
    val conversations = chatDao.getAllConversations()

    fun connectToServer(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        _serverUrl.value = trimmed
        _connectionState.value = ConnectionState.CONNECTING

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
                        _currentStatus.value = "Connection error: ${event.error.localizedMessage ?: "Failed"}"
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
        if (trimmed.isBlank()) return

        if (_connectionState.value != ConnectionState.CONNECTED) {
            appendSystemMessage("⚠️ Not connected to Colab Bridge. Open Settings to connect.")
            return
        }

        val userMessage = Message(
            id = UUID.randomUUID().toString(),
            role = "user",
            content = trimmed,
            timestamp = System.currentTimeMillis()
        )
        _messages.value = _messages.value + userMessage
        saveMessageToDb(userMessage)

        // Frame and send JSON payload
        val payload = JSONObject().apply {
            put("message", trimmed)
            put("conversation_id", currentConversationId)
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
                    timestamp = message.timestamp
                )
            )
        }
    }

    fun startNewConversation() {
        _messages.value = emptyList()
        streamingMessageId = null
        currentConversationId = UUID.randomUUID().toString()
        _currentStatus.value = null
        _isLoading.value = false
    }

    fun loadConversation(conversationId: String) {
        currentConversationId = conversationId
        streamingMessageId = null
        _isLoading.value = false
        _currentStatus.value = null

        viewModelScope.launch {
            chatDao.getMessagesForConversation(conversationId).collectLatest { entities ->
                _messages.value = entities.map {
                    Message(
                        id = it.id,
                        role = it.role,
                        content = it.content,
                        timestamp = it.timestamp
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
        sb.append("# AGY Chat Export\n\n")
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
