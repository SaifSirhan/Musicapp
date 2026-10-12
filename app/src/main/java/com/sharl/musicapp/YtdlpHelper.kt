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

data class SongMetadata(
    val title: String,
    val artist: String,
    val album: String?,
    val thumbnailUrl: String?
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
                addOption("--remote-components", "ejs:github")
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

    suspend fun extractUrlAndMetadata(
        context: Context,
        videoId: String
    ): Pair<String, SongMetadata>? = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        val videoUrl = "https://www.youtube.com/watch?v=$videoId"

        val request = YoutubeDLRequest(videoUrl).apply {
            addOption("-f", "bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio/best")
            addOption("--skip-download")
            addOption("--print",
                "%(url)s|%(artist)s|%(album)s|%(uploader)s|%(track)s|%(title)s|%(thumbnail)s")
            addOption("--extractor-args",
                "youtube:player_client=ios,tv,mweb,android_vr,web_embedded,tv_embedded")
            addOption("--remote-components", "ejs:github")
            addOption("--no-update")
            addOption("--no-warnings")
        }

        try {
            val response = YoutubeDL.getInstance().execute(request)
            val line = response.out.lineSequence()
                .firstOrNull { it.isNotBlank() && it.contains("|") }
                ?: return@withContext null

            val parts = line.split("|", limit = 7)
            if (parts.size < 6) {
                Log.e("YTDL", "metadata parse failed: $line")
                return@withContext null
            }

            val url = parts[0].trim()
            val rawArtist = parts[1].trim()
            val rawAlbum = parts[2].trim()
            val rawUploader = parts[3].trim()
            val rawTrack = parts[4].trim()
            val rawTitle = parts[5].trim()
            val rawThumbnail = parts.getOrNull(6)?.trim()

            fun clean(v: String): String? =
                v.takeIf { it.isNotEmpty() && it != "NA" && it != "null" }

            // Artist priority: artist field > uploader (with " - Topic" stripped) > fallback
            val artist = clean(rawArtist)
                ?: clean(rawUploader)?.removeSuffix(" - Topic")?.trim()
                ?: "Unknown Artist"

            // Title priority: track field > title field
            val title = clean(rawTrack) ?: clean(rawTitle) ?: "Unknown Title"

            val album = clean(rawAlbum)
            val thumbnailUrl = rawThumbnail?.let { clean(it) }

            url to SongMetadata(
                title = title,
                artist = artist,
                album = album,
                thumbnailUrl = thumbnailUrl
            )
        } catch (e: Exception) {
            Log.e("YTDL", "extractUrlAndMetadata failed: ${e.message}", e)
            null
        }
    }

    suspend fun extractVideoUrl(
        context: Context,
        videoId: String,
        maxHeight: Int = 720
    ): String? = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        val url = "https://www.youtube.com/watch?v=$videoId"
        val request = YoutubeDLRequest(url).apply {
            // Single progressive file only — a separate video+audio split would
            // need a Muxer to merge, which ExoPlayer can't do here.
            addOption("-f", "best[height<=$maxHeight][ext=mp4]/best[height<=$maxHeight]/best")
            addOption("-g")
            addOption("--extractor-args",
                "youtube:player_client=ios,tv,mweb,android_vr,web_embedded,tv_embedded")
            addOption("--remote-components", "ejs:github")
            addOption("--no-update")
            addOption("--no-warnings")
        }
        try {
            val response = YoutubeDL.getInstance().execute(request)
            response.out.lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n")
        } catch (e: Exception) {
            Log.e("YTDL", "extractVideoUrl failed: ${e.message}", e)
            null
        }
    }
}
