package com.agychat.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FileDao {
    @Query("SELECT * FROM local_files WHERE conversationId = :conversationId ORDER BY cachedAt DESC")
    fun getFilesForConversation(conversationId: String): Flow<List<LocalFileEntity>>

    @Query("SELECT * FROM local_files WHERE conversationId = :conversationId ORDER BY cachedAt DESC")
    suspend fun getFilesForConversationList(conversationId: String): List<LocalFileEntity>

    @Query("SELECT * FROM local_files WHERE id = :id OR remotePath = :path OR filename = :path ORDER BY cachedAt DESC LIMIT 1")
    suspend fun findFile(id: String, path: String): LocalFileEntity?

    @Query("SELECT * FROM local_files WHERE id = :id OR remotePath = :path OR filename = :path OR filename = :filename OR remotePath LIKE '%' || :filename ORDER BY cachedAt DESC LIMIT 1")
    suspend fun findFileWithFallback(id: String, path: String, filename: String): LocalFileEntity?

    @Query("SELECT * FROM local_files ORDER BY cachedAt DESC")
    fun getAllFiles(): Flow<List<LocalFileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(file: LocalFileEntity)

    @Query("DELETE FROM local_files WHERE id = :id")
    suspend fun deleteFile(id: String)

    @Query("DELETE FROM local_files WHERE conversationId = :conversationId")
    suspend fun deleteFilesForConversation(conversationId: String)

    @Query("DELETE FROM local_files")
    suspend fun clearAllFiles()
}
