package com.agychat.app.data.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GistUrlResolver — Zero-touch Dynamic Colab Bridge URL Discovery
 *
 * Resolves the active WebSocket URL published by the Colab bridge server
 * to a permanent GitHub Gist. This eliminates the need for the user to ever
 * copy and paste tunnel URLs manually when Colab restarts.
 */
@Singleton
class GistUrlResolver @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "GistUrlResolver"
        const val DEFAULT_GIST_ID = "93a5f994e43134016362692fe4bfc510"
        private const val GIST_API_BASE = "https://api.github.com/gists"
        private const val RAW_GIST_BASE = "https://gist.githubusercontent.com/naitikmaurya1111-dotcom"
    }

    data class ResolvedUrlResult(
        val wsUrl: String? = null,
        val httpUrl: String? = null,
        val updatedAt: String? = null,
        val isSuccess: Boolean = false,
        val errorMessage: String? = null
    )

    private val fastClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    suspend fun resolveLiveUrl(customGistId: String? = null): ResolvedUrlResult = withContext(Dispatchers.IO) {
        val targetGistId = customGistId?.trim()?.ifBlank { null } ?: DEFAULT_GIST_ID

        // 1. Try official GitHub API endpoint first (real-time, zero-cache)
        try {
            val request = Request.Builder()
                .url("$GIST_API_BASE/$targetGistId")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "NextAI-Android-App")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()

            fastClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string()
                    if (!bodyString.isNullOrBlank()) {
                        val root = JSONObject(bodyString)
                        val files = root.optJSONObject("files")
                        val liveUrlFile = files?.optJSONObject("nextai_live_url.json")
                        val contentStr = liveUrlFile?.optString("content")
                        if (!contentStr.isNullOrBlank()) {
                            val parsed = parsePayload(contentStr)
                            if (parsed.isSuccess) {
                                Log.i(TAG, "Successfully resolved live URL via GitHub API: ${parsed.wsUrl}")
                                return@withContext parsed
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "GitHub API returned code ${response.code}, trying raw Gist CDN")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "GitHub API query failed: ${e.message}, falling back to raw URL")
        }

        // 2. Fallback to raw Gist CDN URL with cache-busting timestamp
        try {
            val timestamp = System.currentTimeMillis()
            val rawUrl = "$RAW_GIST_BASE/$targetGistId/raw/nextai_live_url.json?t=$timestamp"
            val request = Request.Builder()
                .url(rawUrl)
                .header("User-Agent", "NextAI-Android-App")
                .header("Cache-Control", "no-cache")
                .build()

            fastClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val contentStr = response.body?.string()
                    if (!contentStr.isNullOrBlank()) {
                        val parsed = parsePayload(contentStr)
                        if (parsed.isSuccess) {
                            Log.i(TAG, "Successfully resolved live URL via raw Gist CDN: ${parsed.wsUrl}")
                            return@withContext parsed
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Raw Gist query failed", e)
        }

        ResolvedUrlResult(
            isSuccess = false,
            errorMessage = "Could not resolve live URL from Gist $targetGistId"
        )
    }

    private fun parsePayload(jsonString: String): ResolvedUrlResult {
        return try {
            val obj = JSONObject(jsonString)
            val rawWs = obj.optString("ws_url").ifBlank { null }
            val cleanWs = UrlSanitizer.normalizeWebSocketUrl(rawWs)
            val rawHttp = obj.optString("http_url").ifBlank { null }
            val updatedAt = obj.optString("updated_at").ifBlank { null }

            if (cleanWs != null) {
                ResolvedUrlResult(
                    wsUrl = cleanWs,
                    httpUrl = rawHttp,
                    updatedAt = updatedAt,
                    isSuccess = true
                )
            } else {
                ResolvedUrlResult(isSuccess = false, errorMessage = "Invalid ws_url in payload")
            }
        } catch (e: Exception) {
            ResolvedUrlResult(isSuccess = false, errorMessage = e.message)
        }
    }
}
