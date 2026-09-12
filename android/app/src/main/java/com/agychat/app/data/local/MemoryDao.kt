package com.agychat.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY importance DESC, updatedAt DESC")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY importance DESC, updatedAt DESC")
    suspend fun getAllMemoriesList(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE isEnabled = 1 ORDER BY importance DESC, lastAccessedAt DESC")
    suspend fun getAllEnabledMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE category = :category ORDER BY importance DESC, updatedAt DESC")
    fun getMemoriesByCategory(category: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE category = :category AND isEnabled = 1")
    suspend fun getEnabledMemoriesByCategory(category: String): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity)

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Query("UPDATE memories SET isEnabled = :isEnabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateMemoryEnabled(id: String, isEnabled: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE memories SET content = :content, category = :category, importance = :importance, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateMemoryContent(id: String, content: String, category: String, importance: Int = 5, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE memories SET accessCount = accessCount + 1, lastAccessedAt = :now WHERE id = :id")
    suspend fun incrementAccessCount(id: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE memories SET importance = :importance WHERE id = :id")
    suspend fun updateImportance(id: String, importance: Int)

    @Delete
    suspend fun deleteMemory(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemoryById(id: String)

    @Query("DELETE FROM memories")
    suspend fun clearAllMemories()

    @Query("SELECT * FROM memories WHERE content LIKE '%' || :query || '%' ORDER BY importance DESC, updatedAt DESC")
    fun searchMemories(query: String): Flow<List<MemoryEntity>>

    @Query("SELECT COUNT(*) FROM memories WHERE isEnabled = 1")
    fun getEnabledCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun getTotalCount(): Int

    @Query("SELECT * FROM memories WHERE LOWER(content) = LOWER(:content) LIMIT 1")
    suspend fun findMemoryByExactContent(content: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE LOWER(content) LIKE '%' || LOWER(:query) || '%'")
    suspend fun findMemoriesMatching(query: String): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(memories: List<MemoryEntity>)

    @Query("DELETE FROM memories WHERE LOWER(content) LIKE '%' || LOWER(:query) || '%'")
    suspend fun deleteMemoriesMatching(query: String): Int

    @Query("SELECT * FROM memories WHERE isEnabled = 1 ORDER BY importance DESC, accessCount DESC LIMIT :limit")
    suspend fun getTopMemories(limit: Int = 40): List<MemoryEntity>
}
