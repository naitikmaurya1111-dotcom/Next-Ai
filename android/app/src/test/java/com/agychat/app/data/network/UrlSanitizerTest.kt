package com.agychat.app.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlSanitizerTest {

    @Test
    fun normalizeWebSocketUrl_validWss_returnsNormalized() {
        val input = "wss://my-tunnel.trycloudflare.com/ws"
        val result = UrlSanitizer.normalizeWebSocketUrl(input)
        assertEquals("wss://my-tunnel.trycloudflare.com/ws", result)
        assertTrue(UrlSanitizer.isValidWebSocketUrl(input))
    }

    @Test
    fun normalizeWebSocketUrl_httpsScheme_convertsToWss() {
        val input = "https://my-tunnel.trycloudflare.com/ws"
        val result = UrlSanitizer.normalizeWebSocketUrl(input)
        assertEquals("wss://my-tunnel.trycloudflare.com/ws", result)
    }

    @Test
    fun normalizeWebSocketUrl_missingWsPath_appendsWs() {
        val input = "https://my-tunnel.trycloudflare.com"
        val result = UrlSanitizer.normalizeWebSocketUrl(input)
        assertEquals("wss://my-tunnel.trycloudflare.com/ws", result)
    }

    @Test
    fun normalizeWebSocketUrl_missingScheme_defaultsToWss() {
        val input = "my-tunnel.trycloudflare.com"
        val result = UrlSanitizer.normalizeWebSocketUrl(input)
        assertEquals("wss://my-tunnel.trycloudflare.com/ws", result)
    }

    @Test
    fun normalizeWebSocketUrl_withMarkdownWrapper_extractsUrl() {
        val input = "[Tunnel](https://my-tunnel.trycloudflare.com/ws)"
        val result = UrlSanitizer.normalizeWebSocketUrl(input)
        assertEquals("wss://my-tunnel.trycloudflare.com/ws", result)
    }

    @Test
    fun normalizeWebSocketUrl_withQuotesAndWhitespace_cleansProperly() {
        val input = "  \"wss://my-tunnel.trycloudflare.com/ws\"  "
        val result = UrlSanitizer.normalizeWebSocketUrl(input)
        assertEquals("wss://my-tunnel.trycloudflare.com/ws", result)
    }

    @Test
    fun normalizeWebSocketUrl_invalidInput_returnsNull() {
        assertNull(UrlSanitizer.normalizeWebSocketUrl(null))
        assertNull(UrlSanitizer.normalizeWebSocketUrl(""))
        assertNull(UrlSanitizer.normalizeWebSocketUrl("    "))
        assertNull(UrlSanitizer.normalizeWebSocketUrl("not a url at all"))
        assertFalse(UrlSanitizer.isValidWebSocketUrl("   "))
    }
}
