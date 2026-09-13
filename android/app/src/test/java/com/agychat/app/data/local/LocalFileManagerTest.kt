package com.agychat.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFileManagerTest {

    @Test
    fun normalizeFileId_stripsFilePrefixAndPunctuation() {
        val raw = "file:///content/Next-Ai/test.pdf."
        val normalized = LocalFileManager.normalizeFileId(raw)
        assertEquals("/content/Next-Ai/test.pdf", normalized)
    }

    @Test
    fun normalizeFileId_extractsPathQueryParam() {
        val raw = "/api/file?path=%2Fcontent%2FNext-Ai%2Fimage.png&other=1"
        val normalized = LocalFileManager.normalizeFileId(raw)
        assertEquals("/content/Next-Ai/image.png", normalized)
    }

    @Test
    fun normalizeFileId_handlesTrailingBracketsAndQuotes() {
        val raw = "(/content/test.py);"
        val normalized = LocalFileManager.normalizeFileId(raw)
        assertEquals("/content/test.py", normalized)
    }

    @Test
    fun getFileName_extractsBasenameCorrectly() {
        assertEquals("report.pdf", LocalFileManager.getFileName("/content/Next-Ai/docs/report.pdf"))
        assertEquals("script.py", LocalFileManager.getFileName("file:///content/script.py"))
        assertEquals("file.txt", LocalFileManager.getFileName(""))
    }

    @Test
    fun detectMimeType_resolvesKnownExtensions() {
        assertEquals("application/pdf", LocalFileManager.detectMimeType("document.pdf"))
        assertEquals("image/png", LocalFileManager.detectMimeType("photo.png"))
        assertEquals("image/jpeg", LocalFileManager.detectMimeType("photo.jpg"))
        assertEquals("text/markdown", LocalFileManager.detectMimeType("notes.md"))
        assertEquals("text/x-kotlin", LocalFileManager.detectMimeType("Code.kt"))
        assertEquals("text/x-python", LocalFileManager.detectMimeType("script.py"))
        assertEquals("application/json", LocalFileManager.detectMimeType("data.json"))
        assertEquals("application/octet-stream", LocalFileManager.detectMimeType("unknown.xyz"))
    }

    @Test
    fun isBinaryFile_classifiesCorrectly() {
        assertTrue(LocalFileManager.isBinaryFile("application/pdf", "doc.pdf"))
        assertTrue(LocalFileManager.isBinaryFile("image/png", "img.png"))
        assertTrue(LocalFileManager.isBinaryFile("application/octet-stream", "archive.zip"))
        assertFalse(LocalFileManager.isBinaryFile("text/markdown", "readme.md"))
        assertFalse(LocalFileManager.isBinaryFile("text/plain", "notes.txt"))
        assertFalse(LocalFileManager.isBinaryFile("text/x-kotlin", "Main.kt"))
    }
}
