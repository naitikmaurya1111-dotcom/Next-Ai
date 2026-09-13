package com.agychat.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun getMessageCount(conversationId: String): Int

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String)

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :conversationId")
    suspend fun updateConversationTitle(conversationId: String, title: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :conversationId AND customTitle = 0")
    suspend fun autoUpdateConversationTitle(conversationId: String, title: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt, customTitle = 1 WHERE id = :conversationId")
    suspend fun manualRenameConversation(conversationId: String, title: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE conversations SET isPinned = :pinned WHERE id = :conversationId")
    suspend fun updateConversationPinned(conversationId: String, pinned: Boolean)

    @Query("UPDATE conversations SET messageCount = (SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND role != 'system') WHERE id = :conversationId")
    suspend fun refreshMessageCount(conversationId: String)

    @Query("UPDATE conversations SET lastKnownCwd = :cwd WHERE id = :conversationId")
    suspend fun updateConversationCwd(conversationId: String, cwd: String)

    @Query("DELETE FROM conversations")
    suspend fun clearAllConversations()

    @Query("DELETE FROM messages")
    suspend fun clearAllMessages()

    @Query("SELECT * FROM conversations ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun getAllConversationsList(): List<ConversationEntity>

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    suspend fun getAllMessagesList(): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllConversations(conversations: List<ConversationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllMessages(messages: List<MessageEntity>)

    @Query("UPDATE messages SET isPinned = :isPinned WHERE id = :messageId")
    suspend fun updateMessagePinned(messageId: String, isPinned: Boolean)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND isPinned = 1 ORDER BY timestamp ASC")
    fun getPinnedMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("UPDATE conversations SET modelId = :modelId WHERE id = :conversationId")
    suspend fun updateConversationModel(conversationId: String, modelId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND timestamp > :timestamp")
    suspend fun deleteMessagesAfter(conversationId: String, timestamp: Long)

    @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
    suspend fun getConversationById(conversationId: String): ConversationEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND parentMessageId = :parentId AND branchIndex = :branchIndex LIMIT 1")
    suspend fun getMessageAtBranch(conversationId: String, parentId: String, branchIndex: Int): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND parentMessageId = :parentId")
    suspend fun getBranchCountForMessage(conversationId: String, parentId: String): Int
}
