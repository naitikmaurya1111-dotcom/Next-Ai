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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgyWebSocketClient @Inject constructor(
    private val client: OkHttpClient
) {
    companion object {
        private const val TAG = "AgyWebSocketClient"
        private const val MAX_PAYLOAD_CHARS = 35 * 1024 * 1024 // 35MB safeguard matching backend uploads ceiling
        const val MAX_OFFLINE_QUEUE_SIZE = 128

        // Exponential backoff configuration (1s, 2s, 4s, 8s, up to 15s max with jitter)
        const val MIN_BACKOFF_MS = 1000L
        const val MAX_BACKOFF_MS = 15000L
        const val BACKOFF_MULTIPLIER = 2.0
        const val JITTER_RATIO = 0.20 // +/- 20% randomized jitter to prevent connection storms

        /**
         * Calculates smart exponential backoff delay with randomized jitter.
         * Progression: ~1s, ~2s, ~4s, ~8s, up to 15s max.
         * The randomized jitter prevents thundering herd / connection storms against Colab tunnels.
         */
        fun calculateBackoffWithJitter(
            attempt: Int,
            baseDelayMs: Long = MIN_BACKOFF_MS,
            maxDelayMs: Long = MAX_BACKOFF_MS,
            jitterRatio: Double = JITTER_RATIO
        ): Long {
            val safeAttempt = attempt.coerceAtLeast(0)
            val exponent = safeAttempt.coerceAtMost(5) // Prevent 2^N overflow
            val rawDelay = (baseDelayMs * Math.pow(BACKOFF_MULTIPLIER, exponent.toDouble())).toLong()
            val cappedDelay = minOf(rawDelay, maxDelayMs)
            val jitterRange = (cappedDelay * jitterRatio).toLong()
            val jitter = if (jitterRange > 0) {
                ThreadLocalRandom.current().nextLong(-jitterRange, jitterRange + 1)
            } else 0L
            return (cappedDelay + jitter).coerceIn(baseDelayMs, maxDelayMs)
        }
    }

    private var webSocket: WebSocket? = null
    private val isConnectedState = AtomicBoolean(false)
    private val isConnectingState = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)

    // Offline FIFO message queue: holds messages while reconnecting or recovering from a tunnel blip
    private val offlineQueue = ConcurrentLinkedQueue<String>()
    private val queueLock = Any()

    fun connect(url: String): Flow<WsEvent> = callbackFlow {
        val cleanUrl = UrlSanitizer.normalizeWebSocketUrl(url)
        if (cleanUrl == null) {
            val err = IllegalArgumentException("Malformed or invalid WebSocket URL: '$url'")
            Log.e(TAG, "Cannot connect: Invalid URL: '$url'")
            isConnectedState.set(false)
            isConnectingState.set(false)
            trySend(WsEvent.Error(err))
            close()
            return@callbackFlow
        }

        val request = try {
            Request.Builder().url(cleanUrl).build()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to build OkHttp Request for URL: $cleanUrl", t)
            isConnectedState.set(false)
            isConnectingState.set(false)
            trySend(WsEvent.Error(t))
            close()
            return@callbackFlow
        }

        isConnectingState.set(true)

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected successfully to $cleanUrl")
                this@AgyWebSocketClient.webSocket = ws
                isConnectedState.set(true)
                isConnectingState.set(false)
                resetReconnectAttempts()

                // Immediately flush offline message queue upon transitioning to CONNECTED
                flushOfflineQueue(ws)

                trySend(WsEvent.Connected)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                trySend(WsEvent.Message(text))
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code - $reason")
                isConnectedState.set(false)
                isConnectingState.set(false)
                trySend(WsEvent.Closed)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket failure: ${t.message}")
                isConnectedState.set(false)
                isConnectingState.set(false)
                trySend(WsEvent.Error(t))
            }
        }

        try {
            webSocket = client.newWebSocket(request, listener)
        } catch (t: Throwable) {
            Log.e(TAG, "client.newWebSocket threw exception for $cleanUrl", t)
            isConnectedState.set(false)
            isConnectingState.set(false)
            trySend(WsEvent.Error(t))
            close()
            return@callbackFlow
        }

        awaitClose {
            isConnectedState.set(false)
            isConnectingState.set(false)
            try {
                webSocket?.close(1000, "User closed connection")
            } catch (_: Throwable) {}
            webSocket = null
        }
    }

    /**
     * Sends a message over the WebSocket.
     * If the client is disconnected, reconnecting, or recovering from a tunnel blip,
     * the message is queued in the offline message queue and will be automatically flushed
     * immediately once connection transitions to CONNECTED.
     *
     * Returns true if sent directly or queued for auto-flush.
     */
    fun sendMessage(text: String): Boolean {
        if (text.length > MAX_PAYLOAD_CHARS) {
            Log.e(TAG, "Payload too large for WebSocket (${text.length} chars > $MAX_PAYLOAD_CHARS), rejecting to prevent OOM/buffer overflow")
            return false
        }

        synchronized(queueLock) {
            val ws = webSocket
            // Send directly only if connected, active socket exists, and offline queue has zero backlog
            if (isConnectedState.get() && ws != null && offlineQueue.isEmpty()) {
                val sent = try {
                    ws.send(text)
                } catch (t: Throwable) {
                    Log.e(TAG, "Direct WebSocket send threw exception, enqueuing message", t)
                    false
                }
                if (sent) {
                    return true
                }
                Log.w(TAG, "Direct send returned false; enqueuing in offline queue for auto-flush upon reconnect")
            }

            // Enqueue into offline message queue
            if (offlineQueue.size >= MAX_OFFLINE_QUEUE_SIZE) {
                val dropped = offlineQueue.poll()
                Log.w(TAG, "Offline queue at max capacity ($MAX_OFFLINE_QUEUE_SIZE); dropped oldest message (${dropped?.take(50)}...)")
            }

            val queued = offlineQueue.offer(text)
            if (queued) {
                Log.i(TAG, "Enqueued message in offline buffer (queue size: ${offlineQueue.size}). Will auto-flush upon reconnect.")
            }

            // If connected state occurred during enqueuing, flush immediately
            if (isConnectedState.get() && webSocket != null) {
                flushOfflineQueueLocked(webSocket)
            }

            return true // Accepted and queued for reliable delivery
        }
    }

    /**
     * Flushes all queued offline messages in FIFO order across the active WebSocket.
     * Returns the count of messages flushed successfully.
     */
    fun flushOfflineQueue(ws: WebSocket? = webSocket): Int {
        val targetWs = ws ?: webSocket ?: return 0
        if (!isConnectedState.get()) return 0

        synchronized(queueLock) {
            return flushOfflineQueueLocked(targetWs)
        }
    }

    private fun flushOfflineQueueLocked(ws: WebSocket?): Int {
        val targetWs = ws ?: webSocket ?: return 0
        var flushedCount = 0

        while (isConnectedState.get() && offlineQueue.isNotEmpty()) {
            val nextMsg = offlineQueue.peek() ?: break
            val sent = try {
                targetWs.send(nextMsg)
            } catch (t: Throwable) {
                Log.e(TAG, "Exception during offline queue flush", t)
                false
            }

            if (sent) {
                offlineQueue.poll() // Remove successfully sent message
                flushedCount++
            } else {
                Log.w(TAG, "Failed to send message during queue flush; keeping remaining ${offlineQueue.size} in queue")
                break
            }
        }

        if (flushedCount > 0) {
            Log.i(TAG, "Flushed $flushedCount offline message(s) to WebSocket. Remaining backlog: ${offlineQueue.size}")
        }
        return flushedCount
    }

    /**
     * Calculates the next exponential backoff delay with jitter (1s, 2s, 4s, up to 15s max)
     * and increments the internal attempt counter.
     */
    fun getNextReconnectDelayMs(): Long {
        val attempt = reconnectAttempts.getAndIncrement()
        return calculateBackoffWithJitter(attempt)
    }

    /**
     * Resets the reconnect attempt counter to 0.
     */
    fun resetReconnectAttempts() {
        reconnectAttempts.set(0)
    }

    /**
     * Returns current reconnect attempt count.
     */
    fun getReconnectAttempts(): Int = reconnectAttempts.get()

    /**
     * Returns number of messages currently held in offline queue.
     */
    fun getQueuedMessageCount(): Int = offlineQueue.size

    /**
     * Returns whether the WebSocket is currently connected.
     */
    fun isConnected(): Boolean = isConnectedState.get()

    /**
     * Returns whether the WebSocket is currently attempting to connect.
     */
    fun isConnecting(): Boolean = isConnectingState.get()

    /**
     * Clears all queued offline messages.
     */
    fun clearOfflineQueue() {
        synchronized(queueLock) {
            offlineQueue.clear()
        }
    }

    fun disconnect() {
        isConnectedState.set(false)
        isConnectingState.set(false)
        try {
            webSocket?.close(1000, "Disconnect requested")
        } catch (_: Throwable) {}
        webSocket = null
    }
}
