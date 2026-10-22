package com.sharl.musicapp.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * YouTube Data API v3 reader used by the account importer, implementing the
 * TVHTML5 Device Flow (RFC 8628). No redirect URI needed — user authorizes
 * via google.com/device with a short code.
 */
object YtmImporter {

    private const val TV_CLIENT_ID = "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com"

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun clearError() {
        _error.value = null
    }

    data class DeviceCodeResponse(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String
    )

    /**
     * Starts the TVHTML5 Device Flow. Returns codes the user must enter at
     * google.com/device to authorize the app.
     */
    suspend fun startDeviceFlow(): DeviceCodeResponse = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val url = URL("https://oauth2.googleapis.com/device/code")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.doOutput = true

        val body = "client_id=${URLEncoder.encode(TV_CLIENT_ID, "UTF-8")}" +
                   "&scope=${URLEncoder.encode("https://www.googleapis.com/auth/youtube", "UTF-8")}"
        conn.outputStream.write(body.toByteArray())

        if (conn.responseCode == 200) {
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            DeviceCodeResponse(
                deviceCode = json.getString("device_code"),
                userCode = json.getString("user_code"),
                verificationUrl = json.getString("verification_url")
            )
        } else {
            throw Exception("Failed to start device flow: ${conn.errorStream.bufferedReader().readText()}")
        }
    }

    /**
     * Polls for the access token after the user has authorized the device code.
     * Must delay 5 seconds before first poll per RFC 8628.
     */
    suspend fun pollForToken(deviceCode: String): String = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val url = URL("https://oauth2.googleapis.com/token")
        while (true) {
            delay(5000) // MUST delay 5 seconds before first poll
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.doOutput = true

            val body = "client_id=${URLEncoder.encode(TV_CLIENT_ID, "UTF-8")}" +
                       "&device_code=${URLEncoder.encode(deviceCode, "UTF-8")}" +
                       "&grant_type=${URLEncoder.encode("urn:ietf:params:oauth:grant-type:device_code", "UTF-8")}"
            conn.outputStream.write(body.toByteArray())

            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                return@withContext json.getString("access_token")
            } else {
                val errorText = conn.errorStream.bufferedReader().readText()
                Log.e("YTM_AUTH", "Poll error: $errorText")
                val errorJson = JSONObject(errorText)
                val error = errorJson.optString("error")
                if (error != "authorization_pending" && error != "slow_down") {
                    throw Exception("Token polling failed: $error")
                }
            }
        }
        @Suppress("UNREACHABLE_CODE") ""
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
