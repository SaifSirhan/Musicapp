package com.sharl.musicapp.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object InnerTubeHelper {
    // NOTE TO DEV: These constants are what YouTube changes. If InnerTube starts failing,
    // update the clientVersion or User-Agent here and push an OTA update.
    private const val CLIENT_VERSION = "1.56.21"
    private const val USER_AGENT = "com.google.android.apps.youtube.vr.oculus/1.56.21 (Linux; U; Android 12; en_US; Quest 3) gzip"
    private const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8" // Standard web key, usually works for VR client

    // ANDROID_VR has been answering LOGIN_REQUIRED ("confirm you're not a bot")
    // since 2026-10, so the same endpoint is retried with the iOS client, which
    // still hands out direct audio URLs.
    private const val IOS_CLIENT_VERSION = "20.10.4"
    private const val IOS_USER_AGENT =
        "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"

    suspend fun getStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        fetchBestAudioUrl(videoId, "ANDROID_VR", CLIENT_VERSION, USER_AGENT)
            ?: fetchBestAudioUrl(videoId, "IOS", IOS_CLIENT_VERSION, IOS_USER_AGENT)
    }

    private fun fetchBestAudioUrl(
        videoId: String,
        clientName: String,
        clientVersion: String,
        userAgent: String
    ): String? {
        try {
            val url = URL("https://www.youtube.com/youtubei/v1/player?key=$API_KEY")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", userAgent)
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val jsonBody = """
                {
                  "context": {
                    "client": {
                      "clientName": "$clientName",
                      "clientVersion": "$clientVersion",
                      "androidSdkVersion": 32,
                      "osName": "Android",
                      "osVersion": "12"
                    }
                  },
                  "videoId": "$videoId",
                  "playbackContext": {
                    "contentPlaybackContext": { "html5Preference": "HTML5_PREF_WANTS" }
                  }
                }
            """.trimIndent()

            conn.doOutput = true
            conn.outputStream.write(jsonBody.toByteArray())

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val streamingData = json.optJSONObject("streamingData") ?: return null
                val formats = streamingData.optJSONArray("adaptiveFormats") ?: return null

                // Find highest bitrate audio (m4a/webm)
                var bestUrl: String? = null
                var maxBitrate = 0
                for (i in 0 until formats.length()) {
                    val format = formats.getJSONObject(i)
                    val mime = format.optString("mimeType", "")
                    if (mime.contains("audio")) {
                        val bitrate = format.optInt("bitrate", 0)
                        val url = format.optString("url", "")
                        if (url.isNotEmpty() && bitrate > maxBitrate) {
                            maxBitrate = bitrate
                            bestUrl = url
                        }
                    }
                }
                return bestUrl
            }
        } catch (e: Exception) {
            Log.e("InnerTube", "Fast extraction failed ($clientName)", e)
        }
        return null
    }
}
