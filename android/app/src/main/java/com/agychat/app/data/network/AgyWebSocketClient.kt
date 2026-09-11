package com.agychat.app.data.network

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
    private var webSocket: WebSocket? = null

    fun connect(url: String): Flow<WsEvent> = callbackFlow {
        val request = Request.Builder().url(url).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                trySend(WsEvent.Connected)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                trySend(WsEvent.Message(text))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                trySend(WsEvent.Closed)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                trySend(WsEvent.Error(t))
            }
        }

        webSocket = client.newWebSocket(request, listener)

        awaitClose {
            webSocket?.close(1000, "User closed connection")
            webSocket = null
        }
    }

    fun sendMessage(text: String) {
        webSocket?.send(text)
    }

    fun disconnect() {
        webSocket?.close(1000, "Disconnect requested")
        webSocket = null
    }
}
