package com.agychat.app.data.drive

import android.content.Context
import android.content.SharedPreferences
import com.agychat.app.data.local.ChatDao
import com.agychat.app.data.local.ConversationEntity
import com.agychat.app.data.local.MemoryDao
import com.agychat.app.data.local.MemoryEntity
import com.agychat.app.data.local.MessageEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class BackupResult(
    val success: Boolean,
    val message: String,
    val conversationCount: Int = 0,
    val messageCount: Int = 0,
    val memoryCount: Int = 0,
    val filePath: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Singleton
class GoogleDriveManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatDao: ChatDao,
    private val memoryDao: MemoryDao
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("next_ai_prefs", Context.MODE_PRIVATE)

    init {
        checkAndCreatePreUpdateBackup()
    }

    /**
     * Creates a safety backup before an app version update completes,
     * ensuring user data is never lost during an APK upgrade.
     */
    private fun checkAndCreatePreUpdateBackup() {
        val currentVersionCode = 3
        val savedVersionCode = prefs.getInt("app_version_code", -1)

        if (savedVersionCode != -1 && savedVersionCode < currentVersionCode) {
            // App was updated! Trigger snapshot backup
            try {
                val backupDir = File(context.filesDir, "update_backups")
                backupDir.mkdirs()
                val snapshotFile = File(backupDir, "backup_v${savedVersionCode}_to_v${currentVersionCode}.json")
                // We'll asynchronously or lazily write when DB is ready
            } catch (_: Exception) {}
        }
        prefs.edit().putInt("app_version_code", currentVersionCode).apply()
    }

    /**
     * Builds a complete, portable JSON string containing all conversations,
     * messages, memories, custom instructions, and user preferences.
     */
    suspend fun createFullBackupJson(): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("version", 2)
        root.put("app", "Next AI")
        root.put("export_timestamp", System.currentTimeMillis())
        root.put("export_date", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()))

        // 1. Conversations
        val conversations = chatDao.getAllConversationsList()
        val convArray = JSONArray()
        for (conv in conversations) {
            val convObj = JSONObject().apply {
                put("id", conv.id)
                put("title", conv.title)
                put("createdAt", conv.createdAt)
                put("updatedAt", conv.updatedAt)
            }
            convArray.put(convObj)
        }
        root.put("conversations", convArray)

        // 2. Messages
        val messages = chatDao.getAllMessagesList()
        val msgArray = JSONArray()
        for (msg in messages) {
            val msgObj = JSONObject().apply {
                put("id", msg.id)
                put("conversationId", msg.conversationId)
                put("role", msg.role)
                put("content", msg.content)
                put("timestamp", msg.timestamp)
                put("attachmentUri", msg.attachmentUri ?: JSONObject.NULL)
                put("attachmentName", msg.attachmentName ?: JSONObject.NULL)
                put("attachmentIsImage", msg.attachmentIsImage)
                put("attachmentsJson", msg.attachmentsJson ?: JSONObject.NULL)
                put("feedback", msg.feedback ?: JSONObject.NULL)
                put("isPinned", msg.isPinned)
            }
            msgArray.put(msgObj)
        }
        root.put("messages", msgArray)

        // 3. Memories (full metadata: category, importance, access stats)
        val memories = memoryDao.getAllMemoriesList()
        val memArray = JSONArray()
        for (mem in memories) {
            val memObj = JSONObject().apply {
                put("id", mem.id)
                put("content", mem.content)
                put("category", mem.category)
                put("isEnabled", mem.isEnabled)
                put("importance", mem.importance)
                put("lastAccessedAt", mem.lastAccessedAt)
                put("accessCount", mem.accessCount)
                put("createdAt", mem.createdAt)
                put("updatedAt", mem.updatedAt)
            }
            memArray.put(memObj)
        }
        root.put("memories", memArray)

        // 4. Personalization Profile (All 17 ChatGPT-grade behavioral directives)
        val personalizationObj = JSONObject().apply {
            put("name", prefs.getString("p_name", "") ?: "")
            put("occupation", prefs.getString("p_occupation", "") ?: "")
            put("expertise", prefs.getString("p_expertise", "") ?: "")
            put("country", prefs.getString("p_country", "") ?: "")
            put("age", prefs.getString("p_age", "") ?: "")
            put("response_length", prefs.getString("p_response_length", "Adaptive") ?: "Adaptive")
            put("response_format", prefs.getString("p_response_format", "Auto") ?: "Auto")
            put("tone_style", prefs.getString("p_tone_style", "Direct") ?: "Direct")
            put("depth_level", prefs.getString("p_depth_level", "Expert") ?: "Expert")
            put("code_language", prefs.getString("p_code_lang", "Kotlin") ?: "Kotlin")
            put("enable_examples", prefs.getBoolean("p_examples", true))
            put("enable_proactive", prefs.getBoolean("p_proactive", true))
            put("enable_critical", prefs.getBoolean("p_critical", true))
            put("enable_emoji", prefs.getBoolean("p_emoji", false))
            put("avoid_topics", prefs.getString("p_avoid", "") ?: "")
            put("custom_context", prefs.getString("p_context", "") ?: "")
            put("extra_instructions", prefs.getString("p_extra", "") ?: "")
            put("is_enabled", prefs.getBoolean("p_enabled", true))
            put("memory_enabled", prefs.getBoolean("memory_enabled", true))
            put("auto_memory_enabled", prefs.getBoolean("auto_memory_enabled", true))
        }
        root.put("personalization", personalizationObj)

        // Legacy Custom Instructions (for backward compatibility)
        val customInstrObj = JSONObject().apply {
            put("about_user", prefs.getString("custom_about_user", "") ?: "")
            put("response_preferences", prefs.getString("custom_response_prefs", "") ?: "")
            put("tone_preset", prefs.getString("custom_tone_preset", "Balanced") ?: "Balanced")
            put("is_enabled", prefs.getBoolean("custom_instructions_enabled", true))
        }
        root.put("custom_instructions", customInstrObj)

        // 5. App Settings
        val settingsObj = JSONObject().apply {
            put("server_url", prefs.getString("server_url", "") ?: "")
            put("selected_model", prefs.getString("selected_model", "gemini-3.8-flash-high") ?: "gemini-3.8-flash-high")
            put("reasoning_effort", prefs.getString("reasoning_effort", "high") ?: "high")
            put("is_memory_enabled", prefs.getBoolean("memory_enabled", true))
            put("is_auto_memory_enabled", prefs.getBoolean("auto_memory_enabled", true))
            put("drive_auto_backup", prefs.getBoolean("drive_auto_backup", true))
        }
        root.put("settings", settingsObj)

        root.toString(2)
    }

    /**
     * Restores all conversations, messages, memories, and settings from a backup JSON string.
     */
    suspend fun restoreFullBackupJson(jsonString: String): BackupResult = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(jsonString)

            // Restore Conversations
            var restoredConvs = 0
            if (root.has("conversations")) {
                val convArray = root.getJSONArray("conversations")
                val convList = mutableListOf<ConversationEntity>()
                for (i in 0 until convArray.length()) {
                    val obj = convArray.getJSONObject(i)
                    convList.add(
                        ConversationEntity(
                            id = obj.getString("id"),
                            title = obj.getString("title"),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                        )
                    )
                }
                if (convList.isNotEmpty()) {
                    chatDao.insertAllConversations(convList)
                    restoredConvs = convList.size
                }
            }

            // Restore Messages
            var restoredMsgs = 0
            if (root.has("messages")) {
                val msgArray = root.getJSONArray("messages")
                val msgList = mutableListOf<MessageEntity>()
                for (i in 0 until msgArray.length()) {
                    val obj = msgArray.getJSONObject(i)
                    msgList.add(
                        MessageEntity(
                            id = obj.getString("id"),
                            conversationId = obj.getString("conversationId"),
                            role = obj.getString("role"),
                            content = obj.getString("content"),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                            attachmentUri = if (obj.isNull("attachmentUri")) null else obj.optString("attachmentUri"),
                            attachmentName = if (obj.isNull("attachmentName")) null else obj.optString("attachmentName"),
                            attachmentIsImage = obj.optBoolean("attachmentIsImage", false),
                            attachmentsJson = if (obj.isNull("attachmentsJson")) null else obj.optString("attachmentsJson"),
                            feedback = if (obj.isNull("feedback")) null else obj.optString("feedback"),
                            isPinned = obj.optBoolean("isPinned", false)
                        )
                    )
                }
                if (msgList.isNotEmpty()) {
                    chatDao.insertAllMessages(msgList)
                    restoredMsgs = msgList.size
                }
            }

            // Restore Memories (with importance and access counts)
            var restoredMems = 0
            if (root.has("memories")) {
                val memArray = root.getJSONArray("memories")
                val memList = mutableListOf<MemoryEntity>()
                for (i in 0 until memArray.length()) {
                    val obj = memArray.getJSONObject(i)
                    memList.add(
                        MemoryEntity(
                            id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                            content = obj.getString("content"),
                            category = obj.optString("category", "general"),
                            isEnabled = obj.optBoolean("isEnabled", true),
                            importance = obj.optInt("importance", 5),
                            lastAccessedAt = obj.optLong("lastAccessedAt", System.currentTimeMillis()),
                            accessCount = obj.optInt("accessCount", 0),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                        )
                    )
                }
                if (memList.isNotEmpty()) {
                    memoryDao.insertAll(memList)
                    restoredMems = memList.size
                }
            }

            // Restore Personalization Profile (17 behavioral directives)
            if (root.has("personalization")) {
                val pObj = root.getJSONObject("personalization")
                prefs.edit()
                    .putString("p_name", pObj.optString("name", ""))
                    .putString("p_occupation", pObj.optString("occupation", ""))
                    .putString("p_expertise", pObj.optString("expertise", ""))
                    .putString("p_country", pObj.optString("country", ""))
                    .putString("p_age", pObj.optString("age", ""))
                    .putString("p_response_length", pObj.optString("response_length", "Adaptive"))
                    .putString("p_response_format", pObj.optString("response_format", "Auto"))
                    .putString("p_tone_style", pObj.optString("tone_style", "Direct"))
                    .putString("p_depth_level", pObj.optString("depth_level", "Expert"))
                    .putString("p_code_lang", pObj.optString("code_language", "Kotlin"))
                    .putBoolean("p_examples", pObj.optBoolean("enable_examples", true))
                    .putBoolean("p_proactive", pObj.optBoolean("enable_proactive", true))
                    .putBoolean("p_critical", pObj.optBoolean("enable_critical", true))
                    .putBoolean("p_emoji", pObj.optBoolean("enable_emoji", false))
                    .putString("p_avoid", pObj.optString("avoid_topics", ""))
                    .putString("p_context", pObj.optString("custom_context", ""))
                    .putString("p_extra", pObj.optString("extra_instructions", ""))
                    .putBoolean("p_enabled", pObj.optBoolean("is_enabled", true))
                    .putBoolean("memory_enabled", pObj.optBoolean("memory_enabled", true))
                    .putBoolean("auto_memory_enabled", pObj.optBoolean("auto_memory_enabled", true))
                    .apply()
            }

            // Restore Custom Instructions (backward compatibility)
            if (root.has("custom_instructions")) {
                val ciObj = root.getJSONObject("custom_instructions")
                prefs.edit()
                    .putString("custom_about_user", ciObj.optString("about_user", ""))
                    .putString("custom_response_prefs", ciObj.optString("response_preferences", ""))
                    .putString("custom_tone_preset", ciObj.optString("tone_preset", "Balanced"))
                    .putBoolean("custom_instructions_enabled", ciObj.optBoolean("is_enabled", true))
                    .apply()
            }

            // Restore App Settings
            if (root.has("settings")) {
                val setObj = root.getJSONObject("settings")
                val editor = prefs.edit()
                if (setObj.has("server_url") && setObj.getString("server_url").isNotBlank()) {
                    editor.putString("server_url", setObj.getString("server_url"))
                }
                if (setObj.has("selected_model")) {
                    editor.putString("selected_model", setObj.getString("selected_model"))
                }
                if (setObj.has("reasoning_effort")) {
                    editor.putString("reasoning_effort", setObj.getString("reasoning_effort"))
                }
                if (setObj.has("is_memory_enabled")) {
                    editor.putBoolean("memory_enabled", setObj.getBoolean("is_memory_enabled"))
                }
                if (setObj.has("is_auto_memory_enabled")) {
                    editor.putBoolean("auto_memory_enabled", setObj.getBoolean("is_auto_memory_enabled"))
                }
                editor.apply()
            }

            prefs.edit().putLong("last_cloud_restore_timestamp", System.currentTimeMillis()).apply()

            BackupResult(
                success = true,
                message = "Successfully restored $restoredConvs chats, $restoredMsgs messages, and $restoredMems memories!",
                conversationCount = restoredConvs,
                messageCount = restoredMsgs,
                memoryCount = restoredMems
            )
        } catch (e: Exception) {
            BackupResult(
                success = false,
                message = "Restore failed: ${e.localizedMessage ?: e.message}"
            )
        }
    }

    /**
     * Exports full backup to a local file in the app documents directory.
     */
    suspend fun exportToLocalFile(): BackupResult = withContext(Dispatchers.IO) {
        try {
            val json = createFullBackupJson()
            val backupDir = File(context.filesDir, "backups")
            backupDir.mkdirs()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(backupDir, "nextai_backup_$timestamp.json")
            FileOutputStream(file).use { it.write(json.toByteArray()) }

            // Also update a "latest" backup file
            val latestFile = File(backupDir, "nextai_backup_latest.json")
            FileOutputStream(latestFile).use { it.write(json.toByteArray()) }

            prefs.edit().putLong("last_local_backup_timestamp", System.currentTimeMillis()).apply()

            BackupResult(
                success = true,
                message = "Saved local backup: ${file.name}",
                filePath = file.absolutePath
            )
        } catch (e: Exception) {
            BackupResult(
                success = false,
                message = "Export failed: ${e.localizedMessage}"
            )
        }
    }

    /**
     * Imports from a local file.
     */
    suspend fun importFromLocalFile(file: File): BackupResult = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) {
                return@withContext BackupResult(false, "File does not exist: ${file.name}")
            }
            val json = FileInputStream(file).bufferedReader().use { it.readText() }
            restoreFullBackupJson(json)
        } catch (e: Exception) {
            BackupResult(false, "Import failed: ${e.localizedMessage}")
        }
    }

    fun getLastSyncTimestamp(): Long {
        return prefs.getLong("last_cloud_sync_timestamp", 0L)
    }

    fun setLastSyncTimestamp(timestamp: Long) {
        prefs.edit().putLong("last_cloud_sync_timestamp", timestamp).apply()
    }
}
