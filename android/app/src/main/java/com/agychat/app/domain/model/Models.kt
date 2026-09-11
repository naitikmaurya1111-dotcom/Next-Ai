package com.agychat.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ToolExecutionItem(
    val id: String = "",
    val toolName: String,
    val state: String = "DONE", // "ACTIVE", "DONE", "ERROR"
    val command: String? = null,
    val targetFile: String? = null,
    val parametersSummary: String? = null,
    val output: String? = null,
    val durationSeconds: Double = 0.0
)

@Serializable
data class Message(
    val id: String,
    val role: String, // "user", "assistant", "system"
    val content: String = "",
    val thinking: String? = null,
    val toolExecution: String? = null,
    val toolExecutions: List<ToolExecutionItem> = emptyList(),
    val isToolsExpanded: Boolean = true,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val isThinking: Boolean = false,
    val isThinkingExpanded: Boolean = false,
    val attachmentUri: String? = null,
    val attachmentName: String? = null,
    val attachmentIsImage: Boolean = false,
    val feedback: String? = null // "like", "dislike", null
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

@Serializable
data class AiModel(
    val id: String,
    val name: String,
    val provider: String, // "Google", "Anthropic", "Open Source"
    val description: String,
    val supportsEffort: Boolean = true,
    val defaultEffort: String = "high",
    val badge: String = ""
)

object ModelRegistry {
    val ALL_MODELS = listOf(
        AiModel(
            id = "gemini-3.8-flash-high",
            name = "Gemini 3.8 Flash",
            provider = "Google",
            description = "Latest flagship multimodal & ultra-fast coding",
            supportsEffort = true,
            defaultEffort = "high",
            badge = "Default · High Speed"
        ),
        AiModel(
            id = "gemini-3.7-flash-high",
            name = "Gemini 3.7 Flash",
            provider = "Google",
            description = "Hybrid reasoning & deep code analysis",
            supportsEffort = true,
            defaultEffort = "high",
            badge = "Hybrid Reasoning"
        ),
        AiModel(
            id = "gemini-3.6-flash-high",
            name = "Gemini 3.6 Flash",
            provider = "Google",
            description = "Lightweight, ultra-low latency response",
            supportsEffort = true,
            defaultEffort = "high",
            badge = "Lightweight"
        ),
        AiModel(
            id = "gemini-3.1-pro-high",
            name = "Gemini 3.1 Pro",
            provider = "Google",
            description = "Complex architecture, deep refactoring & math",
            supportsEffort = true,
            defaultEffort = "high",
            badge = "Pro Architecture"
        ),
        AiModel(
            id = "claude-sonnet-4-6",
            name = "Claude Sonnet 4.6",
            provider = "Anthropic",
            description = "State-of-the-art coding, system design & review",
            supportsEffort = false,
            defaultEffort = "default",
            badge = "Thinking Default"
        ),
        AiModel(
            id = "claude-opus-4-6-thinking",
            name = "Claude Opus 4.6",
            provider = "Anthropic",
            description = "Maximum cognitive depth & autonomous workflows",
            supportsEffort = false,
            defaultEffort = "default",
            badge = "Maximum Reasoning"
        ),
        AiModel(
            id = "gpt-oss-120b-medium",
            name = "GPT-OSS 120B",
            provider = "Open Source",
            description = "High-capacity open-weights model for general code",
            supportsEffort = false,
            defaultEffort = "medium",
            badge = "Open Weights"
        )
    )

    val DEFAULT_MODEL: AiModel = ALL_MODELS[0]

    fun findById(id: String?): AiModel {
        if (id.isNullOrBlank()) return DEFAULT_MODEL
        return ALL_MODELS.find { it.id.equals(id, ignoreCase = true) || id.contains(it.name, ignoreCase = true) } ?: DEFAULT_MODEL
    }
}

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
