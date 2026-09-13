package com.agychat.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_files")
data class LocalFileEntity(
    @PrimaryKey val id: String, // Normalized path or unique hash
    val conversationId: String,
    val filename: String,
    val remotePath: String,
    val localPath: String,
    val content: String,
    val size: Long,
    val mimeType: String,
    val cachedAt: Long = System.currentTimeMillis()
)
