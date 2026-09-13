package com.agychat.app.data.local

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePermissionsTest {

    @Test
    fun getPermissions_returnsNonEmptyArray() {
        val permissions = StoragePermissions.getPermissions()
        assertNotNull("Permissions array should not be null", permissions)
        assertTrue("Permissions array should not be empty", permissions.isNotEmpty())
    }

    @Test
    fun getPermissions_containsValidAndroidPermissionStrings() {
        val permissions = StoragePermissions.getPermissions()
        for (perm in permissions) {
            assertTrue("Permission string should start with android.permission", perm.startsWith("android.permission."))
        }
    }

    @Test
    fun getPermissions_containsNoDuplicates() {
        val permissions = StoragePermissions.getPermissions()
        val distinct = permissions.distinct()
        assertTrue("Permissions array should contain unique entries", permissions.size == distinct.size)
    }

    @Test
    fun getPermissions_containsMediaOrStoragePermissions() {
        val permissions = StoragePermissions.getPermissions()
        val hasRelevantPermission = permissions.any {
            it.contains("READ_MEDIA_") || it.contains("READ_EXTERNAL_STORAGE") || it.contains("WRITE_EXTERNAL_STORAGE")
        }
        assertTrue("Permissions must include media or external storage permissions", hasRelevantPermission)
    }
}
