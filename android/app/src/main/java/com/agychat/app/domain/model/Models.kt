package com.agychat.app.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
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

@Immutable
@Serializable
data class AttachmentItem(
    val uri: String,
    val name: String,
    val size: Long = 0,
    val isImage: Boolean = false,
    val mimeType: String? = null
)

@Immutable
@Serializable
data class Message(
    val id: String,
    val role: String, // "user", "assistant", "system"
    val content: String = "",
    val thinking: String? = null,
    val thinkingDurationMs: Long = 0L,
    val toolExecution: String? = null,
    val toolExecutions: List<ToolExecutionItem> = emptyList(),
    val isToolsExpanded: Boolean = true,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val isThinking: Boolean = false,
    val isThinkingExpanded: Boolean? = null,
    val attachmentUri: String? = null,
    val attachmentName: String? = null,
    val attachmentIsImage: Boolean = false,
    val attachments: List<AttachmentItem> = emptyList(),
    val feedback: String? = null, // "like", "dislike", null
    val memoryUpdates: List<String> = emptyList(), // Autonomous memory facts saved/updated in this turn
    val isPinned: Boolean = false,
    val replyToContent: String? = null,
    val replyToRole: String? = null,
    val modelName: String? = null,
    val parentMessageId: String? = null,
    val branchIndex: Int = 0,
    val totalBranches: Int = 1,
    val tokensPerSecond: Double? = null,
    val durationSeconds: Double? = null
) {
    val isUser: Boolean get() = role == "user"
    val isAssistant: Boolean get() = role == "assistant"

    val allAttachments: List<AttachmentItem>
        get() = if (attachments.isNotEmpty()) {
            attachments
        } else if (!attachmentUri.isNullOrBlank()) {
            listOf(
                AttachmentItem(
                    uri = attachmentUri,
                    name = attachmentName ?: "Attachment",
                    size = 0,
                    isImage = attachmentIsImage
                )
            )
        } else {
            emptyList()
        }
}

@Immutable
@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val messages: List<Message> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class ConversationGroup(
    val label: String,
    val conversations: List<com.agychat.app.data.local.ConversationEntity>
)

data class WorkspaceState(
    val cwd: String = "/content",
    val activeTasksCount: Int = 0,
    val isConnected: Boolean = false
)

@Immutable
@Serializable
data class MemoryItem(
    val id: String,
    val content: String,
    val category: String = "general", // "preference", "project", "personal", "style", "general"
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class Personalization(
    // Identity & Background (who the user is)
    val name: String = "",                        // "Naitik"
    val occupation: String = "",                  // "Android developer"
    val expertise: String = "",                   // "Kotlin, Compose, ML"
    val country: String = "",                     // "India"
    val age: String = "",                         // "22"
    // How should AI respond
    val responseLength: String = "Adaptive",      // "Concise" | "Balanced" | "Detailed" | "Adaptive"
    val responseFormat: String = "Auto",          // "Auto" | "Always Markdown" | "Plain Text"
    val toneStyle: String = "Direct",             // "Direct" | "Formal" | "Casual" | "Socratic" | "Empathetic"
    val depthLevel: String = "Expert",            // "Beginner" | "Intermediate" | "Expert" | "Research"
    val codeLanguage: String = "Auto",            // preferred programming language ("Auto" | "Python" | "Kotlin" etc.)
    val enableExamples: Boolean = true,           // include code/concept examples by default
    val enableProactiveInsights: Boolean = true,  // volunteer useful related info unprompted
    val enableCriticalFeedback: Boolean = true,   // give honest critical analysis, no sugarcoating
    val enableEmoji: Boolean = false,             // use emoji in responses
    val avoidTopics: String = "",                 // topics to avoid/skip
    // Extra free-form instructions
    val customContext: String = "",               // background context about user's work/projects
    val extraInstructions: String = "",           // any free-form extra directives
    // System toggles
    val isEnabled: Boolean = true,
    val memoryEnabled: Boolean = true,
    val autoMemoryEnabled: Boolean = true
)

// Keep CustomInstructions for backward compatibility
@Serializable
data class CustomInstructions(
    val aboutUser: String = "",
    val responsePreferences: String = "",
    val tonePreset: String = "Balanced",
    val isEnabled: Boolean = true
)

object MemoryCategory {
    const val ALL = "All"
    const val FACTS = "facts"          // hard user facts: name, job, age
    const val PREFERENCES = "prefs"   // tech/style preferences
    const val PROJECT = "project"      // ongoing project context
    const val GOALS = "goals"          // user goals & milestones
    const val PERSONAL = "personal"   // personal details, relationships
    const val SKILLS = "skills"       // known skills, tech stack
    const val FEEDBACK = "feedback"   // how user reacted to answers
    const val GENERAL = "general"

    val ALL_CATEGORIES = listOf(ALL, FACTS, PREFERENCES, PROJECT, GOALS, PERSONAL, SKILLS, FEEDBACK, GENERAL)

    fun getDisplayName(category: String): String = when (category.lowercase()) {
        FACTS -> "📋 Facts"
        PREFERENCES -> "⚙️ Preferences"
        PROJECT -> "🎯 Projects"
        GOALS -> "🏆 Goals"
        PERSONAL -> "👤 Personal"
        SKILLS -> "💻 Skills"
        FEEDBACK -> "💬 Feedback"
        GENERAL -> "💡 General"
        else -> "📌 ${category.replaceFirstChar { it.uppercase() }}"
    }

    fun getIconEmoji(category: String): String = when (category.lowercase()) {
        FACTS -> "📋"
        PREFERENCES -> "⚙️"
        PROJECT -> "🎯"
        GOALS -> "🏆"
        PERSONAL -> "👤"
        SKILLS -> "💻"
        FEEDBACK -> "💬"
        else -> "💡"
    }

    fun getColor(category: String): String = when (category.lowercase()) {
        FACTS -> "#4FC3F7"
        PREFERENCES -> "#81C784"
        PROJECT -> "#FFB74D"
        GOALS -> "#F48FB1"
        PERSONAL -> "#CE93D8"
        SKILLS -> "#80CBC4"
        FEEDBACK -> "#FFCC02"
        else -> "#B0BEC5"
    }
}

enum class ThinkingLevel(
    val id: String,
    val displayName: String,
    val badge: String,
    val description: String
) {
    HIGH(
        id = "high",
        displayName = "High Thinking",
        badge = "🧠 Deep",
        description = "Maximum cognitive depth & comprehensive multi-step reasoning"
    ),
    MEDIUM(
        id = "medium",
        displayName = "Medium Thinking",
        badge = "⚡ Balanced",
        description = "Balanced speed and reasoning depth for standard coding"
    ),
    LOW(
        id = "low",
        displayName = "Low Thinking",
        badge = "🚀 Fast",
        description = "Minimal thinking latency for quick answers and brief edits"
    );

    companion object {
        fun fromId(id: String?): ThinkingLevel {
            return entries.find { it.id.equals(id, ignoreCase = true) } ?: HIGH
        }
    }
}

@Immutable
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
