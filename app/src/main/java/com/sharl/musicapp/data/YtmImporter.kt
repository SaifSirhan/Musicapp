package com.sharl.musicapp.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * YouTube Data API v3 reader used by the account importer. The OAuth access
 * token is obtained with the Authorization API (scope below) and stored by the
 * UI in SettingsRepo; this object never owns the token.
 */
object YtmImporter {

    const val YOUTUBE_SCOPE = "https://www.googleapis.com/auth/youtube"
    private const val API = "https://www.googleapis.com/youtube/v3"

    data class Playlist(val id: String, val title: String, val itemCount: Int)
    data class Track(
        val videoId: String,
        val title: String,
        val artist: String,
        val thumbnailUrl: String?
    )

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
