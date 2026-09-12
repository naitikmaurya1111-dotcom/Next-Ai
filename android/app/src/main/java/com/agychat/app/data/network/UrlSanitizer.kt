package com.agychat.app.data.network

import android.util.Log
import okhttp3.HttpUrl

/**
 * Industrial-grade URL sanitizer and validator for WebSocket endpoints.
 * Guarantees that any malformed, incomplete, or user-pasted URL is safely cleaned
 * or rejected WITHOUT throwing unhandled exceptions that could crash the application.
 */
object UrlSanitizer {
    private const val TAG = "UrlSanitizer"

    /**
     * Sanitizes and normalizes any user-entered WebSocket or HTTP URL.
     * Handles:
     * - Leading/trailing whitespaces, newlines, tabs
     * - Zero-width spaces (\u200B-\u200D, \uFEFF)
     * - Quotes: ", ', `, “, ”
     * - Markdown link wrapper: [link](url)
     * - HTML tag wrapper: <url>
     * - Scheme correction: http:// -> ws://, https:// -> wss://
     * - Missing scheme: my-tunnel.trycloudflare.com -> wss://my-tunnel.trycloudflare.com/ws
     * - Missing trailing /ws: wss://my-tunnel.trycloudflare.com -> wss://my-tunnel.trycloudflare.com/ws
     * - Validates using OkHttp's HttpUrl parser
     *
     * @param raw The raw input string from clipboard or user input.
     * @return Normalized, safe WebSocket URL string (starting with ws:// or wss://), or null if invalid.
     */
    fun normalizeWebSocketUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        try {
            var s = raw.trim()

            // Strip invisible zero-width spaces, BOM, control characters
            s = s.replace(Regex("[\\u200B-\\u200D\\uFEFF\\u0000-\\u001F\\u007F-\\u009F]"), "").trim()
            if (s.isBlank()) return null

            // Extract URL if wrapped in markdown link [text](url)
            val mdMatch = Regex("""\((https?://[^\s)]+|wss?://[^\s)]+)\)""").find(s)
            if (mdMatch != null) {
                s = mdMatch.groupValues[1].trim()
            }

            // Strip surrounding quotes, backticks, or angle brackets
            s = s.trim('"', '\'', '`', '“', '”', '<', '>', '(', ')')

            // Strip leading prompt prefixes (e.g. "URL: wss://...", "Endpoint: ...")
            val prefixMatch = Regex("""^(?:url|ws|endpoint|server):\s*""", RegexOption.IGNORE_CASE).find(s)
            if (prefixMatch != null) {
                s = s.substring(prefixMatch.range.last + 1).trim()
            }

            // Scheme normalization
            if (s.startsWith("http://", ignoreCase = true)) {
                s = "ws://" + s.substring(7)
            } else if (s.startsWith("https://", ignoreCase = true)) {
                s = "wss://" + s.substring(8)
            } else if (!s.startsWith("ws://", ignoreCase = true) && !s.startsWith("wss://", ignoreCase = true)) {
                // Remove any accidental leading slashes
                s = s.trimStart('/')
                s = "wss://$s"
            }

            // If scheme is present, ensure host and path are clean
            val parts = s.split("://", limit = 2)
            if (parts.size != 2 || parts[1].isBlank()) return null

            val scheme = parts[0].lowercase()
            if (scheme != "ws" && scheme != "wss") return null

            var pathPart = parts[1].trim()

            // If path is missing, automatically append /ws (standard for Colab bridge tunnels)
            if (!pathPart.contains("/")) {
                pathPart = "$pathPart/ws"
            }

            // Assemble clean candidate URL
            val candidate = "$scheme://$pathPart"

            // Validate syntax strictly with OkHttp HttpUrl
            val httpEquivalent = if (scheme == "ws") "http://$pathPart" else "https://$pathPart"
            val parsed = HttpUrl.parse(httpEquivalent) ?: return null

            if (parsed.host().isBlank() || parsed.host().contains(" ") || parsed.host().length < 3) {
                return null
            }

            return candidate
        } catch (t: Throwable) {
            Log.e(TAG, "Error normalizing URL: $raw", t)
            return null
        }
    }

    /**
     * Checks if a raw string is or can be normalized to a valid WebSocket URL.
     */
    fun isValidWebSocketUrl(raw: String?): Boolean {
        return normalizeWebSocketUrl(raw) != null
    }
}
