package com.agychat.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val attachmentUri: String? = null,
    val attachmentName: String? = null,
    val attachmentIsImage: Boolean = false,
    val attachmentsJson: String? = null,
    val feedback: String? = null
)

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey val id: String,
    val content: String,
    val category: String = "general",
    val isEnabled: Boolean = true,
    val importance: Int = 5,           // 1-10, higher = more important (shown first)
    val lastAccessedAt: Long = System.currentTimeMillis(),
    val accessCount: Int = 0,          // how many times this was included in context
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
