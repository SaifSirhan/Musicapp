package com.sharl.musicapp.data.album

import com.sharl.musicapp.data.matching.TitleNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Minimal text HTTP surface so the MusicBrainz provider is testable offline. */
interface HttpTextFetcher {
    suspend fun get(url: String, headers: Map<String, String>): String?
}

/**
 * Fetches an album's official tracklist from MusicBrainz. Release selection is
 * deterministic and refuses to guess when the top candidates are equally plausible;
 * an empty list simply lets the resolver try the next strategy.
 */
class MusicBrainzTracklistProvider(
    private val fetcher: HttpTextFetcher,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val spacingMs: Long = 1_100L,
    private val clock: () -> Long = System::currentTimeMillis
) : TracklistProvider {

    companion object {
        private const val BASE = "https://musicbrainz.org/ws/2"
        private const val DEFAULT_USER_AGENT = "MusicApp/1.0 (album-tracklist)"
        private const val RESULT_LIMIT = 10
        private const val AMBIGUITY_MARGIN = 0.05

        data class MbRelease(
            val id: String,
            val title: String,
            val artist: String?,
            val primaryType: String?,
            val secondaryTypes: List<String>,
            val status: String?,
            val date: String?,
            val trackCount: Int?
        )

        fun parseReleaseSearch(json: String?): List<MbRelease> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val root = JSONObject(json.substring(json.indexOf('{')))
                val releases = root.optJSONArray("releases") ?: JSONArray()
                (0 until releases.length()).mapNotNull { i ->
                    val release = releases.optJSONObject(i) ?: return@mapNotNull null
                    val id = release.optString("id")
                    val title = release.optString("title")
                    if (id.isBlank() || title.isBlank()) return@mapNotNull null
                    // MusicBrainz nests type and secondary types under release-group.
                    val group = release.optJSONObject("release-group")
                    val secondary = group?.optJSONArray("secondary-types")
                        ?: release.optJSONArray("secondary-types")
                    val secondaryTypes = if (secondary == null) {
                        emptyList()
                    } else {
                        (0 until secondary.length())
                            .mapNotNull { secondary.optString(it).takeIf(String::isNotBlank) }
                    }
                    MbRelease(
                        id = id,
                        title = title,
                        artist = release.optString("artist-credit-phrase")
                            .takeIf { it.isNotBlank() }
                            ?: release.optJSONArray("artist-credit")?.optJSONObject(0)
                                ?.optString("name")?.takeIf { it.isNotBlank() }
                            ?: release.optJSONArray("artist-credit")?.optJSONObject(0)
                                ?.optJSONObject("artist")?.optString("name")
                                ?.takeIf { it.isNotBlank() },
                        primaryType = (group?.optString("primary-type")
                            ?: release.optString("primary-type")).takeIf { it.isNotBlank() },
                        secondaryTypes = secondaryTypes,
                        status = release.optString("status").takeIf { it.isNotBlank() },
                        date = release.optString("date").takeIf { it.isNotBlank() },
                        trackCount = release.optInt("track-count", -1).takeIf { it >= 0 }
                    )
                }
            }.getOrDefault(emptyList())
        }

        /** Earliest-dated best match, or null when the best candidates are tied. */
        fun selectRelease(releases: List<MbRelease>, artist: String, albumTitle: String): MbRelease? {
            val targetAlbum = TitleNormalizer.simple(albumTitle)
            val targetArtist = TitleNormalizer.simple(artist)
            if (targetAlbum.isBlank()) return null

            val scored = releases.mapNotNull { release ->
                val album = TitleNormalizer.simple(release.title)
                if (album.isBlank()) return@mapNotNull null

                var score = when {
                    album == targetAlbum -> 3.0
                    album.contains(targetAlbum) || targetAlbum.contains(album) -> 2.0
                    else -> return@mapNotNull null
                }
                val releaseArtist = release.artist?.let(TitleNormalizer::simple).orEmpty()
                if (releaseArtist.isNotBlank() && targetArtist.isNotBlank() &&
                    !releaseArtist.contains(targetArtist) && !targetArtist.contains(releaseArtist)
                ) {
                    // A same-titled album by another artist is never the target.
                    return@mapNotNull null
                }
                score += when {
                    releaseArtist.isBlank() || targetArtist.isBlank() -> 0.0
                    releaseArtist == targetArtist -> 1.5
                    else -> 1.0
                }
                score += when (release.primaryType?.lowercase(Locale.ROOT)) {
                    "album" -> 1.0
                    "ep" -> 0.4
                    "single" -> 0.2
                    else -> 0.0
                }
                if (release.status.equals("Official", true)) score += 0.3
                if (release.secondaryTypes.any { it.equals("Compilation", true) }) score -= 1.0
                if (release.secondaryTypes.any { it.equals("Live", true) }) score -= 0.5
                if (release.trackCount != null && release.trackCount > 0) score += 0.1
                ScoredRelease(release, score)
            }.sortedWith(
                compareByDescending<ScoredRelease> { it.score }
                    .thenBy { it.release.date.orEmpty() }
                    .thenBy { it.release.id }
            )

            val best = scored.firstOrNull() ?: return null
            if (best.score < 2.5) return null

            // Equivalent editions are safe to collapse deterministically (earliest
            // date). Different track counts mean a genuinely different album, which
            // must not be guessed.
            val tied = scored.filter { best.score - it.score < AMBIGUITY_MARGIN }
            val distinctCounts = tied.mapNotNull { it.release.trackCount }.distinct()
            if (distinctCounts.size > 1) return null
            return best.release
        }

        fun parseReleaseTracks(json: String?): List<AlbumTarget> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val root = JSONObject(json.substring(json.indexOf('{')))
                val media = root.optJSONArray("media") ?: JSONArray()
                val tracks = ArrayList<AlbumTarget>()
                var position = 0
                val multiDisc = media.length() > 1
                for (m in 0 until media.length()) {
                    val medium = media.optJSONObject(m) ?: continue
                    val list = medium.optJSONArray("tracks") ?: continue
                    for (t in 0 until list.length()) {
                        val track = list.optJSONObject(t) ?: continue
                        val title = track.optString("title")
                        if (title.isBlank()) continue
                        position += 1
                        val length = track.optLong("length", -1L).takeIf { it > 0 }
                        val artist = track.optString("artist-credit-phrase")
                            .takeIf { it.isNotBlank() }
                            ?: track.optJSONArray("artist-credit")?.optJSONObject(0)
                                ?.optJSONObject("artist")?.optString("name")
                                ?.takeIf { it.isNotBlank() }
                        // Single-disc releases keep the printed track number; multi-disc
                        // releases use a global position so disc 2 does not restart at 1.
                        val number = if (multiDisc) {
                            position
                        } else {
                            track.optInt("number", -1).takeIf { it > 0 } ?: position
                        }
                        tracks.add(AlbumTarget(title, artist, length, number))
                    }
                }
                tracks
            }.getOrDefault(emptyList())
        }

        private data class ScoredRelease(val release: MbRelease, val score: Double)
    }

    private var lastRequestAt = 0L

    override suspend fun tracklist(artist: String, albumTitle: String): List<AlbumTarget> {
        val query = buildString {
            append("release:\"").append(escape(albumTitle)).append("\"")
            if (artist.isNotBlank()) append(" AND artist:\"").append(escape(artist)).append("\"")
        }
        val url = "$BASE/release?query=${URLEncoder.encode(query, "UTF-8")}&fmt=json&limit=$RESULT_LIMIT"
        val searchJson = request(url) ?: return emptyList()
        val release = selectRelease(parseReleaseSearch(searchJson), artist, albumTitle)
            ?: return emptyList()
        val releaseJson = request("$BASE/release/${release.id}?inc=recordings+artist-credits&fmt=json")
            ?: return emptyList()
        return parseReleaseTracks(releaseJson)
    }

    private suspend fun request(url: String): String? {
        val elapsed = clock() - lastRequestAt
        if (lastRequestAt > 0 && spacingMs > elapsed) delay(spacingMs - elapsed)
        lastRequestAt = clock()
        return albumAttempt {
            fetcher.get(url, mapOf("User-Agent" to userAgent, "Accept" to "application/json"))
        }.getOrNull()
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}

/** Default production fetcher: plain GET with timeouts, null outside 2xx. */
class UrlConnectionTextFetcher : HttpTextFetcher {
    override suspend fun get(url: String, headers: Map<String, String>): String? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.requestMethod = "GET"
                headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                if (connection.responseCode !in 200..299) return@withContext null
                connection.inputStream.bufferedReader().use { it.readText() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } finally {
                connection?.disconnect()
            }
        }
}
