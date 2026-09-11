package com.agychat.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val id: String,
    val role: String, // "user", "assistant", "system"
    val content: String = "",
    val thinking: String? = null,
    val toolExecution: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val isThinking: Boolean = false,
    val isThinkingExpanded: Boolean = false,
    val attachmentUri: String? = null,
    val attachmentName: String? = null,
    val attachmentIsImage: Boolean = false
)

data class AttachmentItem(
    val uri: String,
    val name: String,
    val size: Long = 0,
    val isImage: Boolean = false
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val messages: List<Message> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class SlashCommand(
    val name: String,
    val description: String,
    val icon: Int = 0,
    val prefix: String,
    val tag: String = "",
    val example: String = ""
)

sealed class WsEvent {
    object Connected : WsEvent()
    data class Message(val text: String) : WsEvent()
    data class Error(val error: Throwable) : WsEvent()
    object Closed : WsEvent()
}

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}
