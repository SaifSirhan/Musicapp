package com.sharl.musicapp

import android.content.Context
import android.util.Log
import com.sharl.musicapp.data.DiagnosticsManager
import com.sharl.musicapp.data.album.AlbumCandidate
import com.sharl.musicapp.data.album.AlbumCard
import com.sharl.musicapp.data.album.AlbumListing
import com.sharl.musicapp.data.album.AlbumResolver
import com.sharl.musicapp.data.album.DefensiveAlbumResolver
import com.sharl.musicapp.data.album.MusicBrainzDiscographyProvider
import com.sharl.musicapp.data.album.MusicBrainzTracklistProvider
import com.sharl.musicapp.data.album.UrlConnectionTextFetcher
import com.sharl.musicapp.data.matching.TitleNormalizer
import com.sharl.musicapp.data.matching.UploadFilter
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SearchResult(
    val title: String,
    val videoId: String,
    val url: String,
    val durationMs: Long? = null,
    val channel: String? = null
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
    val thumbnailUrl: String?,
    val year: String? = null
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

    private const val MIN_DURATION_MS = 45_000L
    private const val MIN_ALBUM_TRACK_MS = 20_000L
    private const val MAX_TRACK_MS = 10L * 60 * 1000

    fun normalizeTitle(raw: String, artist: String? = null): String =
        TitleNormalizer.normalize(raw, artist).core

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
        val quality: Int,
        val playlistIndex: Int? = null
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

    private suspend fun searchWithYtdlp(
        context: Context,
        query: String,
        limit: Int
    ): List<SearchResult> {
        val raw = fetchSearchEntries(context, "ytsearch20:$query")
        return select(raw, query, limit, strictChannel = true).ifEmpty {
            Log.d("SEARCH", "'$query' had no trusted upload; using the ordered fallback")
            select(raw, query, limit, strictChannel = false)
        }
    }

    /**
     * Task 4: strict admission first, and when nothing qualifies fall back to the
     * standard listing with `- Topic` channels ahead of everything else.
     */
    private fun select(
        raw: List<Raw>,
        artist: String,
        limit: Int,
        strictChannel: Boolean
    ): List<SearchResult> =
        dedupeByTitle(raw, artist)
            .filter { it.durationMs == null || it.durationMs in MIN_DURATION_MS..MAX_TRACK_MS }
            .filter {
                UploadFilter.rejectionReason(
                    artist, it.channel, it.title, it.durationMs,
                    albumContext = false, strictChannel = strictChannel
                ) == null
            }
            .sortedWith(
                compareBy({ UploadFilter.priority(UploadFilter.trust(artist, it.channel)) }, { -it.quality })
            )
            .distinctBy { it.videoId }
            .take(limit.coerceAtMost(10))
            .map { r ->
                SearchResult(
                    title = r.title,
                    videoId = r.videoId,
                    url = "https://www.youtube.com/watch?v=${r.videoId}",
                    durationMs = r.durationMs,
                    channel = r.channel
                )
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
            val playlistIndex = e.optInt("playlist_index", -1).takeIf { it > 0 }
            raw += Raw(videoId, title2, durationMs, channel, qualityRank(title2, channel), playlistIndex)
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

    // Album cards are browse ids; their flat listing is the official, ordered tracklist.
    // Flat album cards only carry a browse id; the album name comes from the
    // playlist itself when it is probed.
    internal suspend fun albumCards(context: Context, query: String): List<AlbumCard> =
        fetchSearchEntries(context, musicSearchUrl(query, "albums"))
            .filter { it.videoId.isNotBlank() }
            .map { AlbumCard(browseId = it.videoId, title = it.title) }

    internal suspend fun albumListing(context: Context, browseId: String): AlbumListing? {
        val url = com.sharl.musicapp.data.album.AlbumInput.playlistUrl(browseId) ?: return null
        val listing = fetchListing(context, url)
        val albumName = listing.title?.let(::cleanAlbumListingTitle)?.takeIf { it.isNotBlank() }
            ?: return null
        val tracks = listing.entries.mapIndexed { index, entry ->
            AlbumCandidate(
                videoId = entry.videoId,
                title = entry.title,
                artist = entry.channel,
                durationMs = entry.durationMs,
                albumTitle = albumName,
                trackNumber = entry.playlistIndex ?: (index + 1)
            )
        }
        return AlbumListing(albumName, tracks)
    }

    internal suspend fun songCandidates(
        context: Context,
        query: String,
        limit: Int
    ): List<AlbumCandidate> =
        fetchSearchEntries(context, musicSearchUrl(query, "songs"))
            .take(limit.coerceIn(1, 20))
            .map {
                AlbumCandidate(
                    videoId = it.videoId,
                    title = it.title,
                    artist = it.channel,
                    durationMs = it.durationMs,
                    isSongResult = true
                )
            }

    internal suspend fun youtubeCandidates(
        context: Context,
        query: String,
        limit: Int
    ): List<AlbumCandidate> =
        fetchSearchEntries(context, "ytsearch20:$query")
            .filter { r ->
                val duration = r.durationMs
                duration == null || duration <= 0 || duration in MIN_ALBUM_TRACK_MS..MAX_TRACK_MS
            }
            .sortedWith(
                compareBy(
                    { UploadFilter.priority(UploadFilter.trust(query, it.channel)) },
                    { -it.quality }
                )
            )
            .take(limit.coerceIn(1, 20))
            .map {
                AlbumCandidate(
                    videoId = it.videoId,
                    title = it.title,
                    artist = it.channel,
                    durationMs = it.durationMs
                )
            }

    private fun cleanAlbumListingTitle(raw: String): String {
        var title = raw.trim()
        if (title.startsWith("Album - ", ignoreCase = true)) title = title.substring(8)
        if (title.endsWith(" - Album", ignoreCase = true)) title = title.dropLast(8)
        return title.trim()
    }

    @Volatile
    private var albumResolver: AlbumResolver? = null

    private fun resolverFor(context: Context): AlbumResolver =
        albumResolver ?: synchronized(this) {
            albumResolver ?: DefensiveAlbumResolver(
                catalog = YtdlpAlbumCatalog(context.applicationContext),
                tracklists = MusicBrainzTracklistProvider(UrlConnectionTextFetcher())
            ).also { albumResolver = it }
        }

    /** Resolves a name or bare OLAK5uy/MPREb ID; preserves source, order and review warnings. */
    suspend fun resolveAlbum(
        context: Context,
        albumNameOrId: String,
        artist: String = "",
        limit: Int = 50
    ): com.sharl.musicapp.data.album.AlbumResolution = withContext(Dispatchers.IO) {
        resolverFor(context).resolve(artist, albumNameOrId, limit)
    }

    suspend fun searchAlbumTracks(
        context: Context,
        artist: String,
        albumTitle: String,
        limit: Int = 20
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        val resolution = resolveAlbum(context, albumTitle, artist, limit)
        Log.d(
            "ALBUM",
            "'$artist / $albumTitle' -> ${resolution.source} (${resolution.tracks.size} tracks)"
        )
        resolution.warnings.forEach { Log.w("ALBUM", it) }
        resolution.tracks.map {
            SearchResult(
                title = it.title,
                videoId = it.videoId,
                url = "https://www.youtube.com/watch?v=${it.videoId}",
                durationMs = it.durationMs,
                channel = it.artist
            )
        }
    }

    @Volatile
    private var discography: MusicBrainzDiscographyProvider? = null

    private fun discographyFor(): MusicBrainzDiscographyProvider =
        discography ?: synchronized(this) {
            discography ?: MusicBrainzDiscographyProvider(
                UrlConnectionTextFetcher(),
                log = { Log.d("DISCOGRAPHY", it) }
            ).also { discography = it }
        }

    /**
     * Phase 51: the discography grid is the artist's official release list. YouTube
     * "full album" uploads are fan re-uploads, so they only serve as a fallback when
     * the catalog has nothing, and every fallback card passes [UploadFilter].
     */
    suspend fun searchAlbums(
        context: Context,
        artist: String,
        limit: Int = 10
    ): List<AlbumResult> = withContext(Dispatchers.IO) {
        val official = runCatching { discographyFor().studioAlbums(artist, limit) }
            .getOrElse {
                Log.w("ARTIST", "Official catalog unavailable for '$artist': ${it.message}")
                emptyList()
            }
        if (official.isNotEmpty()) {
            Log.d("ARTIST", "Catalog albums for '$artist': ${official.map { it.title }}")
            return@withContext official.map { AlbumResult(it.title, "", null, it.year) }
        }
        youtubeAlbumUploads(context, artist, limit)
    }

    private suspend fun youtubeAlbumUploads(
        context: Context,
        artist: String,
        limit: Int
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

            val parsed = ArrayList<Raw>()
            val thumbnails = HashMap<String, String>()
            for (i in 0 until entries.length()) {
                val e = entries.optJSONObject(i) ?: continue
                val videoId = e.optString("id").ifBlank { e.optString("url") }
                if (videoId.isBlank()) continue
                val raw = e.optString("title")
                if (raw.isBlank()) continue
                val durationMs = if (e.has("duration") && !e.isNull("duration")) {
                    (e.optDouble("duration", -1.0) * 1000).toLong().takeIf { it > 0 }
                } else null
                val channel = e.optString("channel").ifBlank { e.optString("uploader") }
                    .takeIf { it.isNotBlank() && it != "NA" }
                val thumbnail = e.optJSONArray("thumbnails")?.let { arr ->
                    arr.optJSONObject(arr.length() - 1)?.optString("url")?.takeIf { it.isNotBlank() }
                } ?: e.optString("thumbnail").takeIf { it.isNotBlank() }
                if (thumbnail != null) thumbnails[raw] = thumbnail
                parsed += Raw(videoId, raw, durationMs, channel, qualityRank(raw, channel))
            }

            val albums = admitAlbums(parsed, artist, limit) { thumbnails[it] }
            Log.d("ARTIST", "Fallback albums for '$artist': ${albums.map { it.title }}")
            albums
        } catch (e: Exception) {
            Log.e("ARTIST", "Album search failed for '$artist': ${e.message}", e)
            emptyList()
        }
    }

    /** Task 4: trusted uploads only first, then the standard listing with `- Topic` first. */
    private fun admitAlbums(
        raw: List<Raw>,
        artist: String,
        limit: Int,
        thumbnailOf: (String) -> String?
    ): List<AlbumResult> {
        for (strict in listOf(true, false)) {
            val albums = ArrayList<AlbumResult>()
            val seen = HashSet<String>()
            val ordered = raw.sortedWith(
                compareBy({ UploadFilter.priority(UploadFilter.trust(artist, it.channel)) }, { -it.quality })
            )
            for (entry in ordered) {
                val reason = UploadFilter.rejectionReason(
                    artist, entry.channel, entry.title, entry.durationMs,
                    albumContext = true, strictChannel = strict
                )
                if (reason != null) {
                    Log.d("ARTIST", "dropped '${entry.title}' ($reason)")
                    continue
                }
                val name = cleanAlbumTitle(entry.title, artist)
                if (name.isBlank() || !seen.add(name.lowercase())) continue
                albums += AlbumResult(name, entry.videoId, thumbnailOf(entry.title))
                if (albums.size >= limit) break
            }
            if (albums.isNotEmpty()) return albums
        }
        return emptyList()
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
