package com.agychat.app.data.network

import android.util.Log
import com.agychat.app.domain.model.WsEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgyWebSocketClient @Inject constructor(
    private val client: OkHttpClient
) {
    companion object {
        private const val TAG = "AgyWebSocketClient"
        private const val MAX_PAYLOAD_CHARS = 35 * 1024 * 1024 // 35MB safeguard matching backend uploads ceiling
    }

    private var webSocket: WebSocket? = null

    fun connect(url: String): Flow<WsEvent> = callbackFlow {
        val cleanUrl = UrlSanitizer.normalizeWebSocketUrl(url)
        if (cleanUrl == null) {
            val err = IllegalArgumentException("Malformed or invalid WebSocket URL: '$url'")
            Log.e(TAG, "Cannot connect: Invalid URL: '$url'")
            trySend(WsEvent.Error(err))
            close()
            return@callbackFlow
        }

        val request = try {
            Request.Builder().url(cleanUrl).build()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to build OkHttp Request for URL: $cleanUrl", t)
            trySend(WsEvent.Error(t))
            close()
            return@callbackFlow
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected successfully to $cleanUrl")
                trySend(WsEvent.Connected)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                trySend(WsEvent.Message(text))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code - $reason")
                trySend(WsEvent.Closed)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket failure: ${t.message}")
                trySend(WsEvent.Error(t))
            }
        }

        try {
            webSocket = client.newWebSocket(request, listener)
        } catch (t: Throwable) {
            Log.e(TAG, "client.newWebSocket threw exception for $cleanUrl", t)
            trySend(WsEvent.Error(t))
            close()
            return@callbackFlow
        }

        awaitClose {
            try {
                webSocket?.close(1000, "User closed connection")
            } catch (_: Throwable) {}
            webSocket = null
        }
    }

    fun sendMessage(text: String): Boolean {
        if (text.length > MAX_PAYLOAD_CHARS) {
            Log.e(TAG, "Payload too large for WebSocket (${text.length} chars > $MAX_PAYLOAD_CHARS), rejecting to prevent OOM/buffer overflow")
            return false
        }
        return try {
            webSocket?.send(text) ?: false
        } catch (t: Throwable) {
            Log.e(TAG, "Error sending message over WebSocket", t)
            false
        }
    }

    fun disconnect() {
        try {
            webSocket?.close(1000, "Disconnect requested")
        } catch (_: Throwable) {}
        webSocket = null
    }
}
