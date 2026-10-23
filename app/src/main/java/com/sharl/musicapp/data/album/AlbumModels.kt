package com.sharl.musicapp.data.album

data class AlbumCard(
    val browseId: String,
    val title: String
)

data class AlbumCandidate(
    val videoId: String,
    val title: String,
    val artist: String? = null,
    val durationMs: Long? = null,
    val isSongResult: Boolean = false,
    val albumTitle: String? = null,
    val trackNumber: Int? = null
)

data class AlbumListing(
    val albumTitle: String,
    val tracks: List<AlbumCandidate>
)

data class AlbumTarget(
    val title: String,
    val artist: String? = null,
    val durationMs: Long? = null,
    val trackNumber: Int? = null
)

interface AlbumCatalog {
    suspend fun searchAlbumCards(query: String): List<AlbumCard>

    suspend fun loadAlbumPlaylist(browseId: String): AlbumListing?

    suspend fun searchSongCandidates(query: String, limit: Int): List<AlbumCandidate>

    suspend fun searchYouTubeCandidates(query: String, limit: Int): List<AlbumCandidate>
}

interface TracklistProvider {
    suspend fun tracklist(artist: String, albumTitle: String): List<AlbumTarget>
}

enum class AlbumResolutionSource {
    OFFICIAL_PLAYLIST,
    TRACKLIST_MATCHED,
    SEARCH_FALLBACK,
    NONE
}

data class ResolvedAlbumTrack(
    val trackNumber: Int,
    val title: String,
    val videoId: String,
    val durationMs: Long?,
    val artist: String? = null
)

data class AlbumResolution(
    val artist: String,
    val albumTitle: String,
    val source: AlbumResolutionSource,
    val tracks: List<ResolvedAlbumTrack>,
    val needsReview: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

interface AlbumResolver {
    /** [albumTitle] accepts a name or a bare OLAK5uy / MPREb identifier, never a URL. */
    suspend fun resolve(artist: String, albumTitle: String, limit: Int = 50): AlbumResolution
}

/** Only validated identifiers can become executable yt-dlp playlist URLs. */
object AlbumInput {
    fun isId(value: String): Boolean = Regex("(?:OLAK5uy|MPREb)[A-Za-z0-9_-]+").matches(value)

    fun playlistUrl(value: String): String? {
        val id = value.trim()
        if (!isId(id)) return null
        return if (id.startsWith("OLAK5uy")) "https://music.youtube.com/playlist?list=$id"
        else "https://music.youtube.com/browse/$id"
    }
}

/** Presentation cleanup only: meaningful and unrecognized version labels survive. */
internal object AlbumTrackMetadata {
    private val decoration = Regex(
        "\\s*[\\(\\[](?:official audio|official video|official music video|lyrics?|lyric video|visualizer|visualiser|hd|hq|4k)[\\)\\]]",
        RegexOption.IGNORE_CASE
    )
    fun title(raw: String): String = raw.replace(decoration, " ").replace(Regex("\\s+"), " ").trim()
    fun artist(raw: String?): String? = raw?.trim()
        ?.replace(Regex("\\s+-\\s+Topic$", RegexOption.IGNORE_CASE), "")?.trim()?.takeIf { it.isNotBlank() }

    fun sameRecording(a: AlbumCandidate, b: AlbumCandidate): Boolean {
        val ad = a.durationMs?.takeIf { it > 0 } ?: return false
        val bd = b.durationMs?.takeIf { it > 0 } ?: return false
        if (kotlin.math.abs(ad - bd) > 3_000) return false
        val aa = artist(a.artist) ?: return false
        val ba = artist(b.artist) ?: return false
        if (com.sharl.musicapp.data.matching.TitleNormalizer.simple(aa) !=
            com.sharl.musicapp.data.matching.TitleNormalizer.simple(ba)) return false
        val at = title(a.title)
        val bt = title(b.title)
        // Version words can appear anywhere, not just in brackets or at the end.
        val variant = Regex("""\b(?:live|session|version|edition|take|mix|remix|edit|demo|acoustic|unplugged|instrumental|karaoke|cover|remaster(?:ed)?|extended|slowed|sped|nightcore)\b""", RegexOption.IGNORE_CASE)
        if (variant.containsMatchIn(at) || variant.containsMatchIn(bt)) return false
        // Even identical live/session labels may represent different performances.
        if (at.any { it in "()[]{}" } || bt.any { it in "()[]{}" }) return false
        val an = com.sharl.musicapp.data.matching.TitleNormalizer.normalize(at, aa)
        val bn = com.sharl.musicapp.data.matching.TitleNormalizer.normalize(bt, ba)
        return an.core.isNotBlank() && an == bn && an.variants.isEmpty()
    }
}

/** Unlike runCatching, cancellation must cross every fallback boundary. */
internal inline fun <T> albumAttempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}
