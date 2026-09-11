package com.agychat.app.data.drive

import javax.inject.Inject
import javax.inject.Singleton
import com.agychat.app.domain.model.Conversation

@Singleton
class GoogleDriveManager @Inject constructor() {
    // Stub implementation due to complexity of actual Google Sign-In setup without keys
    fun signIn() {}
    fun signOut() {}
    fun backupConversation(conversation: Conversation) {}
    fun backupAllData() {}
    fun listBackups(): List<Any> = emptyList()
    fun restoreConversation(fileId: String) {}
}
