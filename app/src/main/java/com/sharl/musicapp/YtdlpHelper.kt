package com.sharl.musicapp

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SearchResult(
    val title: String,
    val videoId: String,
    val url: String
)

object YtdlpHelper {

    @Volatile
    private var initialized = false

    private fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            YoutubeDL.getInstance().init(context.applicationContext)
            try {
                YoutubeDL.getInstance().updateYoutubeDL(context, UpdateChannel.NIGHTLY)
                Log.d("YTDL", "yt-dlp updated successfully")
            } catch (e: Exception) {
                Log.e("YTDL", "yt-dlp update failed", e)
            }
            initialized = true
        }
    }

    suspend fun extractAudioUrl(context: Context, videoUrl: String): String? =
        withContext(Dispatchers.IO) {
            ensureInitialized(context)

            val request = YoutubeDLRequest(videoUrl).apply {
                addOption("-f", "bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio/best")
                addOption("-g")
                addOption("--extractor-args", "youtube:player_client=ios,tv,mweb,android_vr,web_embedded,tv_embedded")
                addOption("--no-update")
                addOption("--no-warnings")
            }

            try {
                val response = YoutubeDL.getInstance().execute(request)
                val url = response.out
                    .lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() }

                if (url == null) {
                    Log.e("YTDL", "empty URL returned — SABR may be blocking. See Phase 2b.")
                }
                url
            } catch (e: Exception) {
                Log.e("YTDL", "Extraction failed for $videoUrl: ${e.message}")
                val diagRequest = YoutubeDLRequest(videoUrl).apply {
                    addOption("--list-formats")
                    addOption("--no-update")
                    addOption("--no-warnings")
                }
                try {
                    val diagResponse = YoutubeDL.getInstance().execute(diagRequest)
                    Log.e("YTDL", "Available formats:\n${diagResponse.out}")
                } catch (diagErr: Exception) {
                    Log.e("YTDL", "Could not list formats: ${diagErr.message}")
                }
                null
            }
        }

    suspend fun searchYouTube(
        context: Context,
        query: String,
        limit: Int = 5
    ): List<SearchResult> =
        withContext(Dispatchers.IO) {
            ensureInitialized(context)

            // Uses yt-dlp's own printf()-style output template rather than --dump-json:
            // one flat "title|id|url" line per entry, no JSON parsing and no new
            // dependency. Literal pipe/percent chars are escaped at template level.
            val request = YoutubeDLRequest("ytsearch$limit:$query").apply {
                addOption(
                    "--print",
                    "%(title)s|%(id)s|%(webpage_url)s"
                )
                addOption("--flat-playlist")
                addOption("--no-update")
                addOption("--no-warnings")
            }

            val response = YoutubeDL.getInstance().execute(request)

            Log.d("YTDL", "search raw out:\n${response.out}")
            Log.d("YTDL", "search raw err:\n${response.err}")

            response.out
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { line ->
                    val parts = line.split("|", limit = 3)
                    if (parts.size < 3) return@mapNotNull null
                    val videoId = parts[1].trim()
                    if (videoId.isEmpty()) return@mapNotNull null
                    SearchResult(
                        title = parts[0].trim(),
                        videoId = videoId,
                        url = parts[2].trim()
                    )
                }
                .take(limit)
                .toList()
        }

    suspend fun extractAudioUrlForId(context: Context, videoId: String): String? =
        extractAudioUrl(context, "https://www.youtube.com/watch?v=$videoId")
}
