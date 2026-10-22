package com.sharl.musicapp

import android.content.Context
import android.util.Log
import com.sharl.musicapp.data.DiagnosticsManager
import com.sharl.musicapp.data.InnerTubeHelper
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
                addOption("--extractor-args", "youtube:player_client=ios,tv,mweb,android_vr,web_embedded,tv_embedded,android_sdkless")
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

    // Junk titles that are almost never a single song.
    private val SEARCH_JUNK = listOf(
        "mix", "playlist", "compilation", "1 hour", "10 hours",
        "full album", "livestream", "podcast"
    )

    // Titles containing one of these are promoted to the top (not required).
    private val SEARCH_PREFERRED = listOf(
        "official audio", "official video", "lyrics", "audio", "music video"
    )

    private const val MIN_DURATION_MS = 45_000L
    private const val MAX_DURATION_MS = 12L * 60 * 1000

    /**
     * InnerTube search first (one HTTP round-trip, ~200-600ms), yt-dlp only when
     * it returns nothing (offline, or the search layout changed shape). The
     * fallback is ~3-5s because it spawns a process, so it is always logged.
     */
    suspend fun searchYouTube(
        context: Context,
        query: String,
        limit: Int = 10
    ): List<SearchResult> {
        val started = System.currentTimeMillis()
        val fast = runCatching { InnerTubeHelper.searchVideos(query, limit = 20) }.getOrDefault(emptyList())
        if (fast.isNotEmpty()) {
            val elapsed = System.currentTimeMillis() - started
            Log.d("SEARCH", "'$query' InnerTube ${fast.size} results in ${elapsed}ms")
            DiagnosticsManager.recordSearch(true, elapsed, "${fast.size} results via InnerTube")
            return scoreResults(
                fast.map { Raw(it.videoId, it.title, it.durationMs) },
                limit
            )
        }
        val slowStarted = System.currentTimeMillis()
        Log.w("SEARCH", "InnerTube returned nothing for '$query'; falling back to yt-dlp")
        val result = runCatching { searchWithYtdlp(context, query, limit) }
            .onFailure {
                DiagnosticsManager.recordSearch(
                    false, System.currentTimeMillis() - started,
                    "InnerTube empty, yt-dlp failed: ${it.message}"
                )
            }
            .getOrDefault(emptyList())
        if (result.isNotEmpty()) {
            DiagnosticsManager.recordSearch(
                true, System.currentTimeMillis() - started,
                "${result.size} results via yt-dlp fallback (${System.currentTimeMillis() - slowStarted}ms)"
            )
        }
        return result
    }

    // Duration/junk filters and the "official audio" boost, shared by both paths.
    private data class Raw(val videoId: String, val title: String, val durationMs: Long?)

    private fun scoreResults(raw: List<Raw>, limit: Int): List<SearchResult> =
        raw
            .filter { it.durationMs == null || it.durationMs in MIN_DURATION_MS..MAX_DURATION_MS }
            .filter { r -> SEARCH_JUNK.none { r.title.contains(it, ignoreCase = true) } }
            .sortedByDescending { r ->
                if (SEARCH_PREFERRED.any { r.title.contains(it, ignoreCase = true) }) 1 else 0
            }
            .distinctBy { it.videoId }
            .take(limit.coerceAtMost(10))
            .map { r -> SearchResult(title = r.title, videoId = r.videoId, url = "https://www.youtube.com/watch?v=${r.videoId}") }

    private suspend fun searchWithYtdlp(
        context: Context,
        query: String,
        limit: Int
    ): List<SearchResult> =
        withContext(Dispatchers.IO) {
            ensureInitialized(context)

            // --flat-playlist pulls metadata for each entry without visiting its
            // page (~2s vs ~11s), and --dump-single-json gives one parseable object.
            // We pull 20 and filter down client-side.
            val request = YoutubeDLRequest("ytsearch20:$query").apply {
                addOption("--flat-playlist")
                addOption("--dump-single-json")
                addOption("--no-update")
                addOption("--no-warnings")
            }

            val response = YoutubeDL.getInstance().execute(request)
            Log.d("YTDL", "search raw out:\n${response.out}")
            Log.d("YTDL", "search raw err:\n${response.err}")

            val entries = try {
                val start = response.out.indexOf('{')
                val root = org.json.JSONObject(response.out.substring(start.coerceAtLeast(0)))
                root.optJSONArray("entries") ?: org.json.JSONArray()
            } catch (e: Exception) {
                Log.e("YTDL", "search JSON parse failed: ${e.message}")
                org.json.JSONArray()
            }

            val raw = ArrayList<Raw>(entries.length())
            for (i in 0 until entries.length()) {
                val e = entries.optJSONObject(i) ?: continue
                val videoId = e.optString("id").ifBlank { e.optString("url") }
                if (videoId.isBlank()) continue
                val durationMs = if (e.has("duration") && !e.isNull("duration")) {
                    (e.optDouble("duration", -1.0) * 1000).toLong()
                } else null
                raw += Raw(videoId, e.optString("title"), durationMs)
            }
            scoreResults(raw, limit)
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
