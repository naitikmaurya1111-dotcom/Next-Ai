package com.agychat.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val id: String,
    val role: String, // "user", "assistant"
    val content: String,
    val timestamp: Long,
    val isStreaming: Boolean = false
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val messages: List<Message>,
    val createdAt: Long,
    val updatedAt: Long
)

data class SlashCommand(
    val name: String,
    val description: String,
    val icon: Int, // Drawable resource
    val prefix: String
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
