package com.sharl.musicapp

import android.content.Context
import android.util.Log
import com.sharl.musicapp.data.DiagnosticsManager
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

data class AlbumResult(
    val title: String,
    val videoId: String,
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

    private const val MIN_DURATION_MS = 45_000L
    private const val MIN_ALBUM_TRACK_MS = 20_000L
    private const val MAX_TRACK_MS = 10L * 60 * 1000

    // Upload noise that should not distinguish two copies of the same song.
    private val UPLOAD_NOISE = Regex(
        "(?i)\\b(?:official\\s+(?:music\\s+)?video|official\\s+audio|lyric\\s+video|" +
            "lyrics?|audio|visuali[sz]er|full\\s+album|hd|hq|4k)\\b"
    )
    private val BRACKETED_NOISE = Regex(
        "(?i)[(\\[{][^)\\]}]*(?:official|lyrics?|audio|video|visuali[sz]er|" +
            "full album|hd|hq|4k)[^)\\]}]*[)\\]}]"
    )

    fun normalizeTitle(raw: String, artist: String? = null): String {
        var t = raw
        if (!artist.isNullOrBlank()) {
            t = t.replace(
                Regex("^\\s*${Regex.escape(artist)}\\s*[-–—:]\\s*", RegexOption.IGNORE_CASE),
                ""
            )
        }
        t = t.replace(BRACKETED_NOISE, " ")
        t = t.replace(UPLOAD_NOISE, " ")
        t = t.replace(Regex("(?i)\\s*[-–—]\\s*(?:topic|album|single)\\s*$"), "")
        t = t.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().lowercase()
        return t.ifBlank { raw.trim().lowercase() }
    }

    // Topic channels are label-generated uploads of the clean album cut.
    private fun qualityRank(title: String, channel: String?): Int {
        val t = title.lowercase()
        val c = channel?.lowercase().orEmpty()
        return when {
            c.contains(" - topic") -> 100
            "official audio" in t -> 80
            "audio" in t -> 60
            "lyric video" in t || "lyrics" in t -> 40
            "official video" in t || "music video" in t -> 20
            else -> 0
        }
    }

    /**
     * Phase 42: Local-first - yt-dlp only, no InnerTube. Search takes ~3-5s.
     */
    suspend fun searchYouTube(
        context: Context,
        query: String,
        limit: Int = 10
    ): List<SearchResult> {
        val started = System.currentTimeMillis()
        Log.d("SEARCH", "'$query' searching via yt-dlp")
        
        return withContext(Dispatchers.IO) {
            try {
                val result = searchWithYtdlp(context, query, limit)
                val elapsed = System.currentTimeMillis() - started
                if (result.isNotEmpty()) {
                    DiagnosticsManager.recordSearch(
                        true, elapsed,
                        "${result.size} results via yt-dlp"
                    )
                    Log.d("SEARCH", "'$query' found ${result.size} results in ${elapsed}ms")
                } else {
                    DiagnosticsManager.recordSearch(false, elapsed, "No results")
                    Log.w("SEARCH", "'$query' returned no results")
                }
                result
            } catch (e: Exception) {
                DiagnosticsManager.recordSearch(false, System.currentTimeMillis() - started, e.message ?: "Unknown error")
                Log.e("SEARCH", "'$query' failed: ${e.message}", e)
                emptyList()
            }
        }
    }

    // Duration/junk filters and title dedup, shared by every search path.
    private data class Raw(
        val videoId: String,
        val title: String,
        val durationMs: Long?,
        val channel: String?,
        val quality: Int
    )

    // Collapse alternate uploads of one song onto its normalized title, keeping
    // the highest-quality copy at the position where the title first appeared.
    private fun dedupeByTitle(raw: List<Raw>, artist: String? = null): List<Raw> {
        val unique = LinkedHashMap<String, Raw>()
        for (r in raw) {
            val key = normalizeTitle(r.title, artist)
            val prev = unique[key]
            if (prev == null || r.quality > prev.quality) unique[key] = r
        }
        return unique.values.toList()
    }

    private fun scoreResults(raw: List<Raw>, limit: Int): List<SearchResult> =
        dedupeByTitle(raw)
            .filter { it.durationMs == null || it.durationMs in MIN_DURATION_MS..MAX_TRACK_MS }
            .filter { r -> SEARCH_JUNK.none { r.title.contains(it, ignoreCase = true) } }
            .sortedByDescending { it.quality }
            .distinctBy { it.videoId }
            .take(limit.coerceAtMost(10))
            .map { r -> SearchResult(title = r.title, videoId = r.videoId, url = "https://www.youtube.com/watch?v=${r.videoId}") }

    private suspend fun searchWithYtdlp(
        context: Context,
        query: String,
        limit: Int
    ): List<SearchResult> {
        // --flat-playlist pulls metadata for each entry without visiting its
        // page (~2s vs ~11s), and --dump-single-json gives one parseable object.
        val raw = fetchSearchEntries(context, "ytsearch20:$query")
        return scoreResults(raw, limit)
    }

    private data class Listing(val title: String?, val entries: List<Raw>)

    private suspend fun fetchSearchEntries(
        context: Context,
        url: String
    ): List<Raw> = fetchListing(context, url).entries

    private suspend fun fetchListing(
        context: Context,
        url: String
    ): Listing = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        val request = YoutubeDLRequest(url).apply {
            addOption("--flat-playlist")
            addOption("--dump-single-json")
            addOption("--no-update")
            addOption("--no-warnings")
        }

        val response = YoutubeDL.getInstance().execute(request)
        Log.d("YTDL", "search raw out:\n${response.out}")
        Log.d("YTDL", "search raw err:\n${response.err}")

        var title: String? = null
        val entries = try {
            val start = response.out.indexOf('{')
            val root = org.json.JSONObject(response.out.substring(start.coerceAtLeast(0)))
            title = root.optString("title").takeIf { it.isNotBlank() }
            root.optJSONArray("entries") ?: org.json.JSONArray()
        } catch (e: Exception) {
            Log.e("YTDL", "search JSON parse failed: ${e.message}")
            org.json.JSONArray()
        }

        val raw = ArrayList<Raw>(entries.length())
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val url2 = e.optString("url")
            val videoId = e.optString("id").ifBlank {
                Regex("[?&]v=([A-Za-z0-9_-]{11})").find(url2)?.groupValues?.get(1).orEmpty()
            }
            if (videoId.isBlank()) continue
            val durationMs = if (e.has("duration") && !e.isNull("duration")) {
                (e.optDouble("duration", -1.0) * 1000).toLong()
            } else null
            val title2 = e.optString("title")
            val channel = e.optString("channel").ifBlank {
                e.optString("uploader").ifBlank { e.optString("artists") }
            }.takeIf { it.isNotBlank() && it != "NA" }
            raw += Raw(videoId, title2, durationMs, channel, qualityRank(title2, channel))
        }
        Listing(title, raw)
    }

    // The bundled yt-dlp dropped the ytmsearch: prefix; music.youtube.com search
    // URLs are the supported equivalent. Album results carry a playlist id whose
    // flat listing is the official, ordered tracklist.
    private const val YTM_SEARCH_URL = "https://music.youtube.com/search?q="

    private fun musicSearchUrl(query: String, section: String? = null): String {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        return buildString {
            append(YTM_SEARCH_URL).append(encoded)
            if (section != null) append('#').append(section)
        }
    }

    private fun albumBrowseUrls(entries: List<Raw>): List<String> =
        entries.take(3).map { "https://music.youtube.com/browse/${it.videoId}" }

    private fun albumTracksFrom(
        raw: List<Raw>,
        artist: String,
        limit: Int,
        enforceMaxDuration: Boolean = true
    ): List<SearchResult> =
        dedupeByTitle(
            raw.filter { r ->
                val d = r.durationMs
                val max = if (enforceMaxDuration) MAX_TRACK_MS else 30L * 60 * 1000
                (d == null || d in MIN_ALBUM_TRACK_MS..max) &&
                    !r.title.contains("full album", ignoreCase = true)
            },
            artist
        )
            .take(limit)
            .map {
                SearchResult(
                    title = it.title,
                    videoId = it.videoId,
                    url = "https://www.youtube.com/watch?v=${it.videoId}"
                )
            }

    suspend fun searchAlbumTracks(
        context: Context,
        artist: String,
        albumTitle: String,
        limit: Int = 20
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        val query = "$artist $albumTitle".trim()
        try {
            var tracks = emptyList<SearchResult>()

            // Album cards are browse ids, so probe the top results until one's
            // title matches; its flat listing is the ordered official tracklist.
            val albumCards = runCatching { fetchSearchEntries(context, musicSearchUrl(query, "albums")) }
                .onFailure { Log.w("ALBUM", "YouTube Music album search failed: ${it.message}") }
                .getOrDefault(emptyList())
            val target = normalizeTitle(albumTitle).trim()
            for (playlistUrl in albumBrowseUrls(albumCards)) {
                val listing = runCatching { fetchListing(context, playlistUrl) }
                    .onFailure { Log.w("ALBUM", "album probe failed for $playlistUrl: ${it.message}") }
                    .getOrNull() ?: continue
                val name = listing.title?.substringAfter("Album - ", listing.title).orEmpty()
                val normalizedName = normalizeTitle(name).trim()
                Log.d("ALBUM", "probe $playlistUrl -> '$name' (${listing.entries.size} tracks)")
                val matches = target.isNotBlank() && normalizedName.isNotBlank() &&
                    (normalizedName.contains(target) || target.contains(normalizedName))
                if (matches) {
                    tracks = albumTracksFrom(listing.entries, artist, limit, enforceMaxDuration = false)
                    if (tracks.isNotEmpty()) {
                        Log.d("ALBUM", "using official album '$name'")
                        break
                    }
                }
            }

            if (tracks.isEmpty()) {
                Log.w("ALBUM", "No matching album playlist for '$query'; trying YouTube Music songs")
                val songs = runCatching { fetchSearchEntries(context, musicSearchUrl(query, "songs")) }
                    .onFailure { Log.w("ALBUM", "YouTube Music songs search failed: ${it.message}") }
                    .getOrDefault(emptyList())
                tracks = albumTracksFrom(songs, artist, limit)
            }

            if (tracks.isEmpty()) {
                Log.w("ALBUM", "YouTube Music yielded nothing for '$query'; falling back to ytsearch")
                val raw = runCatching { fetchSearchEntries(context, "ytsearch20:$query") }
                    .onFailure { Log.w("ALBUM", "ytsearch fallback failed: ${it.message}") }
                    .getOrDefault(emptyList())
                tracks = albumTracksFrom(raw, artist, limit)
            }

            Log.d("ALBUM", "'$query' -> ${tracks.map { it.title }}")
            tracks
        } catch (e: Exception) {
            Log.e("ALBUM", "Album track search failed for '$artist / $albumTitle': ${e.message}", e)
            emptyList()
        }
    }

    // Album uploads legitimately carry "full album" in the title, so they get a
    // narrower junk list than track search.
    private val ALBUM_JUNK = listOf(
        "mix", "playlist", "livestream", "podcast", "1 hour", "10 hours"
    )

    // Phase 47: album discovery for the artist discography section. YouTube
    // "full album" uploads are the most reliable public source of an artist's
    // album list, so we search for those and clean the titles down to names.
    suspend fun searchAlbums(
        context: Context,
        artist: String,
        limit: Int = 10
    ): List<AlbumResult> = withContext(Dispatchers.IO) {
        try {
            ensureInitialized(context)
            val request = YoutubeDLRequest("ytsearch20:$artist full album").apply {
                addOption("--flat-playlist")
                addOption("--dump-single-json")
                addOption("--no-update")
                addOption("--no-warnings")
            }
            val response = YoutubeDL.getInstance().execute(request)
            val start = response.out.indexOf('{')
            val root = org.json.JSONObject(response.out.substring(start.coerceAtLeast(0)))
            val entries = root.optJSONArray("entries") ?: org.json.JSONArray()

            val artistLower = artist.lowercase()
            val seen = HashSet<String>()
            val albums = ArrayList<AlbumResult>()

            for (i in 0 until entries.length()) {
                val e = entries.optJSONObject(i) ?: continue
                val videoId = e.optString("id").ifBlank { e.optString("url") }
                if (videoId.isBlank()) continue
                val raw = e.optString("title")
                if (raw.isBlank()) continue
                if (ALBUM_JUNK.any { raw.contains(it, ignoreCase = true) }) continue
                val name = cleanAlbumTitle(raw, artist)
                if (name.isBlank() || !seen.add(name.lowercase())) continue

                val thumbnail = e.optJSONArray("thumbnails")?.let { arr ->
                    val last = arr.optJSONObject(arr.length() - 1)
                    last?.optString("url")?.takeIf { it.isNotBlank() }
                } ?: e.optString("thumbnail").takeIf { it.isNotBlank() }

                albums += AlbumResult(name, videoId, thumbnail)
                if (albums.size >= limit) break
            }
            Log.d("ARTIST", "Albums for '$artist': ${albums.map { it.title }}")
            albums
        } catch (e: Exception) {
            Log.e("ARTIST", "Album search failed for '$artist': ${e.message}", e)
            emptyList()
        }
    }

    private fun cleanAlbumTitle(raw: String, artist: String): String {        var t = raw
        // Drop a leading "<artist> - ".
        val prefix = "$artist - "
        if (t.startsWith(prefix, ignoreCase = true)) t = t.substring(prefix.length)
        val suffixes = listOf(
            " (full album)", " - full album", " full album",
            " (official album)", " - official album",
            " (album)", " - album"
        )
        var changed = true
        while (changed) {
            changed = false
            for (s in suffixes) {
                if (t.endsWith(s, ignoreCase = true)) {
                    t = t.dropLast(s.length)
                    changed = true
                }
            }
        }
        return t.trim().trim('-', ':', '|').trim()
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
