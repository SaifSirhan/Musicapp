package com.sharl.musicapp.data

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Phase 36: Deezer API helper for search and track resolution.
 * 
 * Search uses the public API (no auth needed).
 * Stream URL resolution requires an ARL token passed in Cookie header.
 */
object DeezerHelper {
    private const val TAG = "DeezerHelper"
    
    // Public API - no authentication required
    private const val SEARCH_API_URL = "https://api.deezer.com/search"
    private const val TRACK_API_URL = "https://api.deezer.com/track"
    
    // Internal API - requires ARL cookie
    private const val GATEWAY_URL = "https://www.deezer.com/gateway/gw-light.php"
    
    private val json = Json { ignoreUnknownKeys = true }
    
    /**
     * Search for tracks using Deezer's public API.
     * Returns list of track IDs, titles, artists, and thumbnail URLs.
     */
    data class DeezerTrack(
        val id: String,
        val title: String,
        val artist: String,
        val album: String? = null,
        val thumbnailUrl: String? = null,
        val durationMs: Long? = null
    )
    
    /**
     * Stream URL info from Deezer's internal API.
     */
    data class StreamInfo(
        val mediaUrl: String,
        val md5Origin: String,
        val mediaVersion: String,
        val trackId: String,
        val quality: String = "MP3_128"
    )
    
    /**
     * Search Deezer for tracks matching the query.
     * Uses public API - no ARL token needed.
     */
    fun searchTracks(query: String, limit: Int = 20): List<DeezerTrack> {
        try {
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
            val url = URL("$SEARCH_API_URL?q=$encodedQuery&limit=$limit")
            
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Search failed: $responseCode")
                return emptyList()
            }
            
            val body = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            return parseSearchResults(body)
        } catch (e: Exception) {
            Log.e(TAG, "Search error", e)
            return emptyList()
        }
    }
    
    private fun parseSearchResults(jsonString: String): List<DeezerTrack> {
        try {
            val root = json.parseToJsonElement(jsonString).jsonObject
            val data = root["data"]?.jsonArray ?: return emptyList()
            
            return data.mapNotNull { element ->
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val title = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
                
                val artistObj = obj["artist"]?.jsonObject
                val artist = artistObj?.get("name")?.jsonPrimitive?.content ?: "Unknown Artist"
                
                val albumObj = obj["album"]?.jsonObject
                val album = albumObj?.get("title")?.jsonPrimitive?.content
                val cover = albumObj?.get("cover_medium")?.jsonPrimitive?.content
                
                val durationSecStr = obj["duration"]?.jsonPrimitive?.content
                val durationSec = durationSecStr?.toLongOrNull()
                val durationMs = durationSec?.times(1000)
                
                DeezerTrack(
                    id = id,
                    title = title,
                    artist = artist,
                    album = album,
                    thumbnailUrl = cover,
                    durationMs = durationMs
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse search results error", e)
            return emptyList()
        }
    }
    
    /**
     * Get stream URL for a specific track using Deezer's internal gateway API.
     * Requires a valid ARL token.
     * 
     * This endpoint returns encrypted stream URLs that need Blowfish decryption.
     */
    fun getStreamUrl(trackId: String, arlToken: String): StreamInfo? {
        try {
            val requestBody = """
                {
                    "api_version": "1.0",
                    "api_token": "",
                    "input": {
                        "SNG_ID": "$trackId"
                    },
                    "method": "song.getData"
                }
            """.trimIndent()
            
            val url = URL(GATEWAY_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("Cookie", "arl=$arlToken")
            connection.setRequestProperty("Content-Type", "application/json")
            
            // Write request body
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody)
                writer.flush()
            }
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Gateway request failed: $responseCode")
                return null
            }
            
            val body = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            return parseStreamResponse(body, trackId)
        } catch (e: Exception) {
            Log.e(TAG, "Get stream URL error", e)
            return null
        }
    }
    
    private fun parseStreamResponse(jsonString: String, trackId: String): StreamInfo? {
        try {
            val root = json.parseToJsonElement(jsonString).jsonObject
            val results = root["results"]?.jsonObject ?: return null
            
            // The response structure varies; extract key fields
            val mediaUrl = results["MEDIA_URL"]?.jsonPrimitive?.content
            val md5Origin = results["MD5_ORIGIN"]?.jsonPrimitive?.content
            val mediaVersion = results["MEDIA_VERSION"]?.jsonPrimitive?.content
            
            if (mediaUrl == null || md5Origin == null || mediaVersion == null) {
                Log.w(TAG, "Missing required fields in stream response")
                return null
            }
            
            return StreamInfo(
                mediaUrl = mediaUrl,
                md5Origin = md5Origin,
                mediaVersion = mediaVersion,
                trackId = trackId
            )
        } catch (e: Exception) {
            Log.e(TAG, "Parse stream response error", e)
            return null
        }
    }
}
