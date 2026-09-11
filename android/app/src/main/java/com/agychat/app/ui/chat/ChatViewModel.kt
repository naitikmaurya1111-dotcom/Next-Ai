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

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private var currentConversationId: String = UUID.randomUUID().toString()

    // Tracks the ID of the current streaming message being assembled
    private var streamingMessageId: String? = null

    fun connectToServer(url: String) {
        if (url.isBlank()) return
        _serverUrl.value = url
        _connectionState.value = ConnectionState.CONNECTING
        viewModelScope.launch {
            webSocketClient.connect(url).collectLatest { event ->
                when (event) {
                    is WsEvent.Connected -> {
                        _connectionState.value = ConnectionState.CONNECTED
                    }
                    is WsEvent.Message -> handleIncomingMessage(event.text)
                    is WsEvent.Error -> {
                        _connectionState.value = ConnectionState.ERROR
                        _isLoading.value = false
                        appendSystemMessage("Connection error: ${event.error.message}")
                    }
                    is WsEvent.Closed -> {
                        _connectionState.value = ConnectionState.DISCONNECTED
                        _isLoading.value = false
                    }
                }
            }
        }
    }

    /**
     * Handle JSON-framed events from the bridge server.
     * Protocol: {"type": "chunk"|"done"|"error"|"info", "content": "...", "timestamp": ...}
     */
    private fun handleIncomingMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "chunk")
            val content = json.optString("content", "")

            when (type) {
                "connected" -> {
                    // Bridge handshake — do nothing or show connected banner
                }
                "info" -> {
                    // Informational status, show as subtle system message
                    appendSystemMessage(content)
                }
                "chunk" -> {
                    // Streaming chunk — append to current streaming message
                    appendChunkToStreaming(content)
                }
                "done" -> {
                    // Response complete — finalize the streaming message
                    finalizeStreamingMessage()
                    _isLoading.value = false
                }
                "error" -> {
                    finalizeStreamingMessage()
                    appendSystemMessage("⚠️ $content")
                    _isLoading.value = false
                }
                else -> {
                    // Fallback: treat as plain text chunk
                    appendChunkToStreaming(text)
                }
            }
        } catch (e: Exception) {
            // Not JSON — treat as plain text chunk
            appendChunkToStreaming(text)
        }
    }

    private fun appendChunkToStreaming(chunk: String) {
        val currentMessages = _messages.value.toMutableList()
        val existingIdx = currentMessages.indexOfFirst { it.id == streamingMessageId }

        if (existingIdx >= 0) {
            val existing = currentMessages[existingIdx]
            currentMessages[existingIdx] = existing.copy(
                content = existing.content + "\n" + chunk,
                isStreaming = true
            )
        } else {
            // Start a new streaming message
            val newId = UUID.randomUUID().toString()
            streamingMessageId = newId
            currentMessages.add(
                Message(
                    id = newId,
                    role = "assistant",
                    content = chunk,
                    timestamp = System.currentTimeMillis(),
                    isStreaming = true
                )
            )
        }
        _messages.value = currentMessages
        _isLoading.value = true
    }

    private fun finalizeStreamingMessage() {
        val id = streamingMessageId ?: return
        val currentMessages = _messages.value.toMutableList()
        val idx = currentMessages.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val finalized = currentMessages[idx].copy(isStreaming = false)
            currentMessages[idx] = finalized
            _messages.value = currentMessages
            saveMessageToDb(finalized)
        }
        streamingMessageId = null
    }

    private fun appendSystemMessage(content: String) {
        val msg = Message(
            id = UUID.randomUUID().toString(),
            role = "system",
            content = content,
            timestamp = System.currentTimeMillis(),
            isStreaming = false
        )
        _messages.value = _messages.value + msg
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        if (_connectionState.value != ConnectionState.CONNECTED) {
            appendSystemMessage("⚠️ Not connected. Go to Settings and enter your server URL.")
            return
        }

        val message = Message(
            id = UUID.randomUUID().toString(),
            role = "user",
            content = text,
            timestamp = System.currentTimeMillis()
        )
        _messages.value = _messages.value + message
        saveMessageToDb(message)

        // Send JSON-framed message to bridge
        val payload = JSONObject().apply {
            put("message", text)
            put("conversation_id", currentConversationId)
        }.toString()
        webSocketClient.sendMessage(payload)
        _isLoading.value = true
    }

    fun sendSlashCommand(command: SlashCommand) {
        sendMessage(command.prefix)
    }

    private fun saveMessageToDb(message: Message) {
        viewModelScope.launch {
            chatDao.insertConversation(
                ConversationEntity(
                    id = currentConversationId,
                    title = _messages.value.firstOrNull { it.role == "user" }?.content?.take(40) ?: "New Chat",
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

    fun clearConversation() {
        _messages.value = emptyList()
        streamingMessageId = null
        currentConversationId = UUID.randomUUID().toString()
    }

    fun backupToDrive() {
        viewModelScope.launch {
            // TODO: integrate GoogleDriveManager here
            appendSystemMessage("ℹ️ Drive backup triggered (configure Google Sign-In in Settings).")
        }
    }

    override fun onCleared() {
        super.onCleared()
        webSocketClient.disconnect()
    }
}
