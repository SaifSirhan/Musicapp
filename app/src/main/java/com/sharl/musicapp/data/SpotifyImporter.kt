package com.sharl.musicapp.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.sharl.musicapp.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Spotify Authorization Code flow (no SDK). The redirect lands on
 * com.sharl.musicapp://callback, which MainActivity hands back to
 * [handleRedirect]. Tokens live in a private SharedPreferences file.
 */
object SpotifyImporter {

    private const val PREFS = "spotify_auth"
    private const val REDIRECT_URI = "com.sharl.musicapp://callback"
    private const val SCOPES = "user-library-read playlist-read-private playlist-read-collaborative"
    private const val AUTH_URL = "https://accounts.spotify.com/authorize"
    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
    private const val API = "https://api.spotify.com/v1"
    private const val MAX_ITEMS = 10_000

    data class Playlist(val id: String, val name: String, val trackCount: Int)

    val configured: Boolean
        get() = BuildConfig.SPOTIFY_CLIENT_ID.isNotBlank() &&
            BuildConfig.SPOTIFY_CLIENT_SECRET.isNotBlank()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        _connected.value = prefs(context).getString("refresh_token", null) != null
    }

    fun clearError() {
        _error.value = null
    }

    /** Opens the Spotify consent page in the user's browser. */
    fun authorize(context: Context): Boolean {
        if (!configured) {
            _error.value = "Spotify isn't configured (missing SPOTIFY_CLIENT_ID / SPOTIFY_CLIENT_SECRET)."
            return false
        }
        val state = UUID.randomUUID().toString()
        prefs(context).edit().putString("oauth_state", state).apply()
        val url = AUTH_URL +
            "?client_id=" + enc(BuildConfig.SPOTIFY_CLIENT_ID) +
            "&response_type=code" +
            "&redirect_uri=" + enc(REDIRECT_URI) +
            "&scope=" + enc(SCOPES) +
            "&state=" + enc(state)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }

    fun handleRedirect(context: Context, uri: Uri) {
        val error = uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            _error.value = "Spotify authorization failed: $error"
            return
        }
        val code = uri.getQueryParameter("code") ?: return
        val expected = prefs(context).getString("oauth_state", null)
        val state = uri.getQueryParameter("state")
        if (!expected.isNullOrBlank() && state != expected) {
            _error.value = "Spotify authorization failed: state mismatch."
            return
        }
        scope.launch {
            try {
                exchangeCode(context, code)
                _connected.value = true
                _error.value = null
            } catch (e: Exception) {
                Log.e("SPOTIFY", "token exchange failed", e)
                _error.value = e.message ?: "Could not connect Spotify."
            }
        }
    }

    private fun exchangeCode(context: Context, code: String) {
        val body = "grant_type=authorization_code" +
            "&code=" + enc(code) +
            "&redirect_uri=" + enc(REDIRECT_URI)
        val json = postForm(TOKEN_URL, body, basicAuth())
        storeTokens(context, json, previousRefresh = null)
    }

    fun disconnect(context: Context) {
        prefs(context).edit().clear().apply()
        _connected.value = false
    }

    suspend fun fetchPlaylists(context: Context): List<Playlist> =
        fetchAllItems(context, "/me/playlists?limit=50").mapNotNull { o ->
            val id = o.optString("id", "")
            if (id.isBlank()) return@mapNotNull null
            Playlist(
                id = id,
                name = o.optString("name", "").ifBlank { "Playlist" },
                trackCount = o.optJSONObject("tracks")?.optInt("total", 0) ?: 0
            )
        }

    /** Liked Songs as "Artist - Title" lines, ready for ytsearch1 resolution. */
    suspend fun fetchLikedTracks(context: Context): List<String> =
        fetchAllItems(context, "/me/tracks?limit=50").mapNotNull { o ->
            o.optJSONObject("track")?.let { trackQuery(it) }
        }

    suspend fun fetchPlaylistTracks(context: Context, playlistId: String): List<String> =
        fetchAllItems(context, "/playlists/$playlistId/tracks?limit=100").mapNotNull { o ->
            o.optJSONObject("track")?.let { trackQuery(it) }
        }

    private fun trackQuery(track: JSONObject): String? {
        val name = track.optString("name", "").trim()
        if (name.isEmpty()) return null
        val artists = track.optJSONArray("artists") ?: JSONArray()
        val names = (0 until artists.length()).mapNotNull {
            artists.optJSONObject(it)?.optString("name", "")?.trim()?.ifBlank { null }
        }
        return if (names.isEmpty()) name else "${names.joinToString(", ")} - $name"
    }

    // ---- HTTP ----------------------------------------------------------------

    private suspend fun fetchAllItems(context: Context, path: String): List<JSONObject> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<JSONObject>()
            var next: String? = if (path.startsWith("http")) path else API + path
            while (next != null && out.size < MAX_ITEMS) {
                val page = apiGet(context, next)
                val items = page.optJSONArray("items") ?: JSONArray()
                for (i in 0 until items.length()) {
                    items.optJSONObject(i)?.let { out.add(it) }
                }
                next = page.optString("next", "").ifBlank { null }
            }
            out
        }

    private fun apiGet(context: Context, url: String): JSONObject {
        var token = accessToken(context) ?: throw IllegalStateException("Connect Spotify first.")
        var response = httpGet(url, token)
        if (response.code == 401) {
            token = refreshAccessToken(context) ?: throw IllegalStateException("Spotify session expired.")
            response = httpGet(url, token)
        }
        if (response.code !in 200..299) {
            throw IllegalStateException("Spotify error ${response.code}: ${response.body.take(120)}")
        }
        return JSONObject(response.body)
    }

    private data class HttpResult(val code: Int, val body: String)

    private fun httpGet(url: String, token: String): HttpResult {
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
            HttpResult(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    private fun postForm(url: String, body: String, authorization: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Authorization", authorization)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("Spotify token error $code: ${text.take(160)}")
            }
            JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun storeTokens(context: Context, json: JSONObject, previousRefresh: String?) {
        val access = json.optString("access_token", "")
        if (access.isBlank()) throw IllegalStateException("Spotify returned no access token.")
        val refresh = json.optString("refresh_token", "")
            .ifBlank { previousRefresh ?: prefs(context).getString("refresh_token", null) ?: "" }
        val expiresIn = json.optLong("expires_in", 3600L)
        prefs(context).edit()
            .putString("access_token", access)
            .putString("refresh_token", refresh)
            .putLong("expires_at", System.currentTimeMillis() + expiresIn * 1000L)
            .apply()
    }

    private fun accessToken(context: Context): String? {
        val p = prefs(context)
        val token = p.getString("access_token", null)
        val expiresAt = p.getLong("expires_at", 0L)
        if (!token.isNullOrBlank() && expiresAt > System.currentTimeMillis() + 30_000L) {
            return token
        }
        return refreshAccessToken(context)
    }

    private fun refreshAccessToken(context: Context): String? {
        val refresh = prefs(context).getString("refresh_token", null) ?: return null
        return try {
            val json = postForm(
                TOKEN_URL,
                "grant_type=refresh_token&refresh_token=" + enc(refresh),
                basicAuth()
            )
            storeTokens(context, json, previousRefresh = refresh)
            json.optString("access_token", "").ifBlank { null }
        } catch (e: Exception) {
            Log.e("SPOTIFY", "token refresh failed", e)
            null
        }
    }

    private fun basicAuth(): String {
        val raw = "${BuildConfig.SPOTIFY_CLIENT_ID}:${BuildConfig.SPOTIFY_CLIENT_SECRET}"
        return "Basic " + Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
