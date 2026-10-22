package com.sharl.musicapp.data

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.sharl.musicapp.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * YouTube Data API v3 reader used by the account importer, plus the native
 * authorization flow that requests the YouTube scope via Google Identity APIs.
 *
 * Uses Android Client ID (not Web Client) so no redirect_uri is needed. Google
 * routes back to the app automatically via package name + SHA-1 fingerprint.
 */
object YtmImporter {

    const val YOUTUBE_SCOPE = "https://www.googleapis.com/auth/youtube"

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun clearError() {
        _error.value = null
    }

    /**
     * Requests YouTube authorization using the native Google Identity API with
     * Android Client ID. Returns false (with [error] set) when the client isn't
     * configured.
     *
     * This flow does NOT open a browser; it uses the device's Google Play Services
     * to request consent and obtain an access token directly.
     */
    suspend fun authorize(context: Context): Boolean {
        val clientId = BuildConfig.GOOGLE_ANDROID_CLIENT_ID
        if (clientId.isBlank()) {
            _error.value = "YouTube import isn't configured: missing GOOGLE_ANDROID_CLIENT_ID."
            return false
        }

        try {
            _connecting.value = true
            _error.value = null

            // Get available Google accounts on the device
            val accounts = getGoogleAccounts(context)
            if (accounts.isEmpty()) {
                fail("No Google accounts found on this device.")
                return false
            }

            // Use the first account for now (could be enhanced to show a chooser)
            val account = accounts.first()
            Log.d("YTM", "Requesting YouTube scope for account: ${account.name}")

            // Request YouTube authorization via Google Identity API
            val result = requestAuthorization(context, account)
            
            val accessToken = result.accessToken
            if (accessToken.isNullOrBlank()) {
                fail("Google returned no access token.")
                return false
            }
            Log.d("YTM", "Successfully obtained YouTube access token")
            _tokenResult.value = accessToken
            return true
        } catch (e: Exception) {
            Log.e("YTM", "Authorization failed", e)
            fail("Could not authorize YouTube: ${e.message ?: e.javaClass.simpleName}")
            return false
        } finally {
            _connecting.value = false
        }
    }

    /**
     * Retrieves Google accounts from the device AccountManager.
     */
    private fun getGoogleAccounts(context: Context): List<Account> {
        val manager = AccountManager.get(context.applicationContext)
        @Suppress("DEPRECATION")
        val accounts = manager.getAccountsByType(GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE)
        return accounts.filter {
            runCatching { GoogleAuthUtil.getAccountId(context, it.name) }.isSuccess
        }
    }

    /**
     * Requests YouTube authorization for a specific account using Google Identity API.
     */
    private suspend fun requestAuthorization(
        context: Context,
        account: Account
    ): AuthorizationResult = suspendCancellableCoroutine { continuation ->
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(YOUTUBE_SCOPE)))
            .setAccount(account)
            .build()

        val client = Identity.getAuthorizationClient(context)
        val task = client.authorize(request)
        task.addOnSuccessListener { result ->
            if (!continuation.isCompleted) {
                continuation.resume(result)
            }
        }.addOnFailureListener { exception ->
            if (!continuation.isCompleted) {
                continuation.resumeWithException(exception)
            }
        }

        // Note: Google Play Services Tasks cannot be cancelled externally,
        // but the coroutine cancellation will still propagate correctly.
    }

    /**
     * Access token from the most recent successful authorization. The UI consumes it
     * (stores it in SettingsRepo and clears it here) because the token belongs
     * to the screen's session, not to this object.
     */
    private val _tokenResult = MutableStateFlow<String?>(null)
    val tokenResult: StateFlow<String?> = _tokenResult

    fun consumeToken(): String? {
        val token = _tokenResult.value
        _tokenResult.value = null
        return token
    }

    private fun fail(message: String) {
        _error.value = message
        _connecting.value = false
    }

    // Stub methods for backward compatibility with MainActivity redirect handling.
    // These are no-ops in the native flow but keep the API stable.
    fun matchesPendingState(context: Context, uri: android.net.Uri): Boolean = false
    fun handleRedirect(context: Context, uri: android.net.Uri) {
        Log.w("YTM", "handleRedirect called but native flow doesn't use browser redirects")
    }

    data class Playlist(val id: String, val title: String, val itemCount: Int)
    data class Track(
        val videoId: String,
        val title: String,
        val artist: String,
        val thumbnailUrl: String?
    )

    private const val API = "https://www.googleapis.com/youtube/v3"

    suspend fun fetchPlaylists(token: String): List<Playlist> {
        val out = ArrayList<Playlist>()
        var pageToken: String? = null
        do {
            val url = buildUrl(
                "$API/playlists",
                mapOf(
                    "part" to "snippet,contentDetails",
                    "mine" to "true",
                    "maxResults" to "50",
                    "pageToken" to pageToken
                )
            )
            val page = getJson(url, token)
            val items = page.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isBlank()) continue
                val snippet = o.optJSONObject("snippet")
                out.add(
                    Playlist(
                        id = id,
                        title = snippet?.optString("title", "")?.ifBlank { null } ?: "Playlist",
                        itemCount = o.optJSONObject("contentDetails")?.optInt("itemCount", 0) ?: 0
                    )
                )
            }
            pageToken = page.optString("nextPageToken", "").ifBlank { null }
        } while (pageToken != null)
        return out
    }

    /** Follows nextPageToken so playlists longer than 50 items import fully. */
    suspend fun fetchPlaylistTracks(token: String, playlistId: String): List<Track> {
        val out = ArrayList<Track>()
        var pageToken: String? = null
        do {
            val url = buildUrl(
                "$API/playlistItems",
                mapOf(
                    "part" to "snippet,contentDetails",
                    "maxResults" to "50",
                    "playlistId" to playlistId,
                    "pageToken" to pageToken
                )
            )
            val page = getJson(url, token)
            val items = page.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val details = o.optJSONObject("contentDetails") ?: continue
                val videoId = details.optString("videoId", "")
                if (videoId.isBlank()) continue
                val snippet = o.optJSONObject("snippet")
                val title = snippet?.optString("title", "")?.ifBlank { null }
                    ?: details.optString("title", "").ifBlank { videoId }
                val artist = snippet?.optString("videoOwnerChannelTitle", "")
                    ?.ifBlank { null }
                    ?: snippet?.optString("channelTitle", "").orEmpty()
                val thumb = snippet?.optJSONObject("thumbnails")
                    ?.optJSONObject("default")
                    ?.optString("url", "")
                    ?.ifBlank { null }
                out.add(Track(videoId, title, artist, thumb))
            }
            pageToken = page.optString("nextPageToken", "").ifBlank { null }
        } while (pageToken != null)
        return out
    }

    private fun buildUrl(base: String, params: Map<String, String?>): String {
        val query = params.entries
            .filter { !it.value.isNullOrBlank() }
            .joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        return "$base?$query"
    }

    private fun getJson(url: String, token: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 401) {
                throw IllegalStateException("YouTube session expired — connect again.")
            }
            if (code !in 200..299) {
                Log.e("YTM", "API error $code: ${text.take(160)}")
                throw IllegalStateException("YouTube Music error $code: ${text.take(120)}")
            }
            JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
