package com.sharl.musicapp.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.sharl.musicapp.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * YouTube Data API v3 reader used by the account importer, plus the browser
 * OAuth flow that issues its token.
 *
 * The import uses the browser (not the Play Services consent sheet) because the
 * native sheet only lists accounts that already granted this scope, which hides
 * accounts the user has never consented with. [prompt]=select_account forces a
 * full account chooser on every connect.
 */
object YtmImporter {

    const val YOUTUBE_SCOPE = "https://www.googleapis.com/auth/youtube"
    private const val API = "https://www.googleapis.com/youtube/v3"

    // Where Google sends the browser back. Must match the redirect URI registered
    // on the OAuth client (Settings → GOOGLE_OAUTH_REDIRECT_URI).
    private const val DEFAULT_REDIRECT_URI = "com.sharl.musicapp://callback"
    private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val PREFS = "ytm_oauth"
    private const val KEY_VERIFIER = "code_verifier"
    private const val KEY_STATE = "state"

    private fun redirectUri(): String =
        BuildConfig.GOOGLE_OAUTH_REDIRECT_URI.trim().ifBlank { DEFAULT_REDIRECT_URI }

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun clearError() {
        _error.value = null
    }

    /**
     * Opens the Google consent page in the default browser. Returns false (with
     * [error] set) when the client isn't configured for a code exchange.
     */
    fun authorize(context: Context): Boolean {
        val clientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        if (clientId.isBlank()) {
            _error.value = "YouTube import isn't configured: missing GOOGLE_WEB_CLIENT_ID."
            return false
        }
        val redirect = redirectUri()
        val verifier = base64Url(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val state = UUID.randomUUID().toString()
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_VERIFIER, verifier)
            .putString(KEY_STATE, state)
            .apply()
        val url = AUTH_URL +
            "?client_id=" + enc(clientId) +
            "&redirect_uri=" + enc(redirect) +
            "&response_type=code" +
            "&scope=" + enc(YOUTUBE_SCOPE) +
            // Forced every time: a previously-consented account would otherwise be
            // approved silently and the user could never pick another one.
            "&prompt=select_account" +
            "&access_type=online" +
            "&include_granted_scopes=true" +
            "&code_challenge=" + enc(sha256Base64Url(verifier)) +
            "&code_challenge_method=S256" +
            "&state=" + enc(state)
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            _error.value = "No browser available to open the Google sign-in page."
            return false
        }
        _connecting.value = true
        _error.value = null
        return true
    }

    /**
     * Exchanges the code from `com.sharl.musicapp://callback?code=…&state=…`.
     * Google requires a client secret on this endpoint; mobile clients normally
     * pass their own (same DEX) — see the setup hint in [exchangeCode].
     */
    /** True when [uri]'s state belongs to a pending Google sign-in, not Spotify's. */
    fun matchesPendingState(context: Context, uri: Uri): Boolean {
        val pending = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATE, null) ?: return false
        return uri.getQueryParameter("state") == pending
    }

    fun handleRedirect(context: Context, uri: Uri) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val expectedState = prefs.getString(KEY_STATE, null)
        val state = uri.getQueryParameter("state")
        if (expectedState != null && state != expectedState) {
            fail("Sign-in was interrupted (state mismatch). Try again.")
            return
        }
        val oauthError = uri.getQueryParameter("error")
        if (!oauthError.isNullOrBlank()) {
            val detail = uri.getQueryParameter("error_description") ?: oauthError
            fail("Google sign-in failed: $detail")
            return
        }
        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            fail("Google sign-in returned no authorization code.")
            return
        }
        val verifier = prefs.getString(KEY_VERIFIER, null)
        prefs.edit().remove(KEY_VERIFIER).remove(KEY_STATE).apply()
        exchangeCode(code, verifier)
    }

    private fun exchangeCode(code: String, verifier: String?) {
        val body = buildString {
            append("grant_type=authorization_code")
            append("&code=").append(enc(code))
            append("&client_id=").append(enc(BuildConfig.GOOGLE_WEB_CLIENT_ID))
            append("&redirect_uri=").append(enc(redirectUri()))
            verifier?.let { append("&code_verifier=").append(enc(it)) }
            if (BuildConfig.GOOGLE_OAUTH_CLIENT_SECRET.isNotBlank()) {
                append("&client_secret=").append(enc(BuildConfig.GOOGLE_OAUTH_CLIENT_SECRET))
            }
        }
        val conn = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val ok = conn.responseCode in 200..299
            val text = (if (ok) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (!ok) {
                Log.e("YTM", "token exchange HTTP ${conn.responseCode}: ${text.take(300)}")
                fail(describeTokenError(conn.responseCode, text))
                return
            }
            val token = JSONObject(text).optString("access_token")
            if (token.isBlank()) {
                fail("Google returned no access token.")
                return
            }
            _tokenResult.value = token
            _error.value = null
        } catch (e: Exception) {
            Log.e("YTM", "token exchange failed", e)
            fail("Could not finish Google sign-in: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
            _connecting.value = false
        }
    }

    // The raw Google error text names the misconfiguration ("invalid_client",
    // "redirect_uri_mismatch"); keep it visible instead of a generic failure.
    private fun describeTokenError(code: Int, text: String): String {
        val json = runCatching { JSONObject(text) }.getOrNull()
        val error = json?.optString("error").orEmpty()
        val desc = json?.optString("error_description").orEmpty().trim()
        val hint = when {
            error == "invalid_client" -> " — Check GOOGLE_OAUTH_CLIENT_SECRET in secrets.properties."
            error == "redirect_uri_mismatch" -> " — Register '${redirectUri()}' on the OAuth client."
            else -> ""
        }
        val tail = if (desc.isBlank()) hint else " — ${desc.take(200)}$hint"
        return "Google token exchange failed (HTTP $code${if (error.isBlank()) "" else ", $error"})$tail"
    }

    private fun fail(message: String) {
        _error.value = message
        _connecting.value = false
    }

    /**
     * Access token from the most recent successful exchange. The UI consumes it
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

    private fun sha256Base64Url(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return base64Url(digest)
    }

    private fun base64Url(bytes: ByteArray): String =
        android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

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
