package com.sharl.musicapp.data.album

import com.sharl.musicapp.data.matching.CandidateRanker
import com.sharl.musicapp.data.matching.TitleNormalizer
import com.sharl.musicapp.data.matching.TrackCandidate
import com.sharl.musicapp.data.matching.TrackTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min

/**
 * Resolves an album to concrete videos with three ordered strategies:
 * official YouTube Music album playlist, an external tracklist matched with
 * [CandidateRanker], then a conservative search fallback. Results are validated
 * and cached; failures degrade to an empty resolution instead of throwing.
 */
class DefensiveAlbumResolver(
    private val catalog: AlbumCatalog,
    private val tracklists: TracklistProvider? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val searchSpacingMs: Long = 1_100L,
    private val cacheTtlMs: Long = 6 * 60 * 60 * 1000L,
    private val cacheMaxEntries: Int = 40
) : AlbumResolver {

    private companion object {
        const val MIN_TRACK_MS = 20_000L
        const val MAX_TRACK_MS = 30L * 60 * 1000
        const val MAX_ALBUM_PROBES = 3
        val SEARCH_JUNK = listOf("full album", "livestream")
    }

    private data class CacheEntry(val at: Long, val value: AlbumResolution)

    private val cache = LinkedHashMap<String, CacheEntry>()
    private val cacheLock = Mutex()

    override suspend fun resolve(
        artist: String,
        albumTitle: String,
        limit: Int
    ): AlbumResolution {
        val cappedLimit = limit.coerceIn(1, 100)
        val input = albumTitle.trim()
        if (input.isBlank() || input.contains("://") || input.startsWith("//") ||
            ((input.startsWith("OLAK5uy") || input.startsWith("MPREb")) && !AlbumInput.isId(input))) {
            return empty(artist, input, listOf("Expected an album name or bare album identifier"))
        }
        // Identifiers are case-sensitive; never fold them in the cache.
        val key = "${artist.trim()}|$input|$cappedLimit"

        cacheLock.withLock {
            val cached = cache[key]
            if (cached != null) {
                if (clock() - cached.at <= cacheTtlMs) return cached.value
                cache.remove(key)
            }
        }

        val result = resolveUncached(artist, input, cappedLimit)
        if (result.tracks.isNotEmpty()) {
            cacheLock.withLock {
                cache[key] = CacheEntry(clock(), result)
                while (cache.size > cacheMaxEntries) {
                    val oldest = cache.keys.firstOrNull() ?: break
                    cache.remove(oldest)
                }
            }
        }
        return result
    }

    private suspend fun resolveUncached(
        artist: String,
        albumTitle: String,
        limit: Int
    ): AlbumResolution {
        val warnings = mutableListOf<String>()

        val official = resolveOfficial(artist, albumTitle, limit, warnings)
        if (official.tracks.isNotEmpty() || AlbumInput.isId(albumTitle)) return official

        val matched = resolveFromTracklist(artist, albumTitle, limit, warnings)
        if (matched.tracks.isNotEmpty()) return matched

        return resolveSearchFallback(artist, albumTitle, limit, warnings)
    }

    private suspend fun resolveOfficial(
        artist: String,
        albumTitle: String,
        limit: Int,
        warnings: MutableList<String>
    ): AlbumResolution {
        val query = "$artist $albumTitle".trim()
        val directId = AlbumInput.isId(albumTitle)
        val cards = if (directId) listOf(AlbumCard(albumTitle, "")) else albumAttempt { catalog.searchAlbumCards(query) }
            .getOrElse {
                warnings.add("YouTube Music album search failed")
                emptyList()
            }
        val target = TitleNormalizer.normalize(albumTitle).core

        for (card in cards.take(MAX_ALBUM_PROBES)) {
            val listing = albumAttempt { catalog.loadAlbumPlaylist(card.browseId) }.getOrNull()
                ?: continue
            if (listing.albumTitle.isBlank() || (!directId && !titlesMatch(target, listing.albumTitle))) continue

            val valid = listing.tracks
                .filter(::isValidCandidate)
                .distinctBy { it.videoId }
            val dropped = listing.tracks.size - valid.size
            if (dropped > 0) {
                warnings.add("Dropped $dropped invalid entries from '${listing.albumTitle}'")
            }

            val tracks = valid.take(limit).mapIndexed { index, candidate ->
                ResolvedAlbumTrack(
                    trackNumber = candidate.trackNumber ?: (index + 1),
                    title = AlbumTrackMetadata.title(candidate.title),
                    videoId = candidate.videoId,
                    durationMs = candidate.durationMs?.takeIf { it > 0 },
                    artist = AlbumTrackMetadata.artist(candidate.artist)
                )
            }
            if (tracks.isNotEmpty()) {
                if (tracks.size == 1) {
                    warnings.add("Official album resolved a single track")
                }
                return AlbumResolution(
                    artist = artist,
                    albumTitle = if (directId) listing.albumTitle else albumTitle,
                    source = AlbumResolutionSource.OFFICIAL_PLAYLIST,
                    tracks = tracks,
                    warnings = warnings.toList()
                )
            }
        }

        if (cards.isNotEmpty()) warnings.add("No official album playlist matched")
        return AlbumResolution(
            artist = artist,
            albumTitle = albumTitle,
            source = AlbumResolutionSource.NONE,
            tracks = emptyList(),
            warnings = warnings.toList()
        )
    }

    private suspend fun resolveFromTracklist(
        artist: String,
        albumTitle: String,
        limit: Int,
        warnings: MutableList<String>
    ): AlbumResolution {
        val provider = tracklists ?: return empty(artist, albumTitle, warnings)
        val targets = albumAttempt { provider.tracklist(artist, albumTitle) }
            .getOrElse {
                warnings.add("External tracklist unavailable")
                emptyList()
            }
            .filter { it.title.isNotBlank() }
            .take(limit)
        if (targets.isEmpty()) return empty(artist, albumTitle, warnings)

        val candidates = ArrayList<AlbumCandidate>()
        var failedSearches = 0
        targets.forEachIndexed { index, target ->
            val query = listOfNotNull(
                target.artist?.takeIf { it.isNotBlank() } ?: artist.takeIf { it.isNotBlank() },
                target.title
            ).joinToString(" ")
            // YouTube search entries carry durations and channel names, which the
            // ranker needs to corroborate title matches; YTM song entries do not.
            albumAttempt { catalog.searchYouTubeCandidates(query, 5) }
                .onSuccess { candidates.addAll(it) }
                .onFailure { failedSearches++ }
            if (index < targets.lastIndex && searchSpacingMs > 0) delay(searchSpacingMs)
        }
        if (failedSearches > 0) {
            warnings.add("Track search failed for $failedSearches of ${targets.size} tracks")
        }

        val rankedTargets = targets.map { target ->
            TrackTarget(
                title = target.title,
                artist = target.artist ?: artist,
                durationMs = target.durationMs,
                albumTitle = albumTitle,
                trackNumber = target.trackNumber
            )
        }
        val rankedCandidates = candidates.map { candidate ->
            TrackCandidate(
                videoId = candidate.videoId,
                rawTitle = candidate.title,
                channel = candidate.artist.orEmpty(),
                durationMs = candidate.durationMs,
                isSongResult = candidate.isSongResult,
                albumTitle = candidate.albumTitle,
                trackNumber = candidate.trackNumber
            )
        }
        val assignment = CandidateRanker.assign(rankedTargets, rankedCandidates)
        val byTarget = assignment.matches.associateBy { it.targetIndex }

        val tracks = targets.mapIndexedNotNull { index, target ->
            val match = byTarget[index] ?: return@mapIndexedNotNull null
            ResolvedAlbumTrack(
                trackNumber = target.trackNumber ?: (index + 1),
                title = AlbumTrackMetadata.title(target.title),
                videoId = match.candidate.videoId,
                durationMs = match.candidate.durationMs?.takeIf { it > 0 },
                artist = AlbumTrackMetadata.artist(target.artist)
            )
        }
        if (tracks.isEmpty()) {
            warnings.add("Tracklist found but no candidates passed validation")
            return empty(artist, albumTitle, warnings)
        }

        val needsReview = assignment.needsReviewTargets.map { targets[it].title }
        if (tracks.size < targets.size) {
            warnings.add("Matched ${tracks.size} of ${targets.size} tracklist entries")
        }
        if (needsReview.isNotEmpty()) {
            warnings.add("Needs review: ${needsReview.joinToString("; ")}")
        }
        return AlbumResolution(
            artist = artist,
            albumTitle = albumTitle,
            source = AlbumResolutionSource.TRACKLIST_MATCHED,
            tracks = tracks,
            needsReview = needsReview,
            warnings = warnings.toList()
        )
    }

    private suspend fun resolveSearchFallback(
        artist: String,
        albumTitle: String,
        limit: Int,
        warnings: MutableList<String>
    ): AlbumResolution {
        val query = "$artist $albumTitle".trim()
        // Prefer YouTube search: its entries include durations, which the
        // validation below uses to drop album-length uploads.
        val youtube = albumAttempt { catalog.searchYouTubeCandidates(query, limit) }
            .getOrDefault(emptyList())
        val raw = if (youtube.isNotEmpty()) {
            youtube
        } else {
            albumAttempt { catalog.searchSongCandidates(query, limit) }
                .getOrDefault(emptyList())
        }

        val unique = ArrayList<AlbumCandidate>()
        val seenIds = HashSet<String>()
        for (candidate in raw) {
            if (!isValidCandidate(candidate)) continue
            if (SEARCH_JUNK.any { candidate.title.contains(it, ignoreCase = true) }) continue
            if (!seenIds.add(candidate.videoId)) continue
            if (unique.any { AlbumTrackMetadata.sameRecording(it, candidate) }) continue
            unique.add(candidate)
        }

        val tracks = unique.take(limit).mapIndexed { index, candidate ->
            ResolvedAlbumTrack(
                trackNumber = index + 1,
                title = AlbumTrackMetadata.title(candidate.title),
                videoId = candidate.videoId,
                durationMs = candidate.durationMs?.takeIf { it > 0 },
                artist = AlbumTrackMetadata.artist(candidate.artist)
            )
        }
        if (tracks.isEmpty()) {
            warnings.add("No album tracks found")
            return AlbumResolution(
                artist = artist,
                albumTitle = albumTitle,
                source = AlbumResolutionSource.NONE,
                tracks = emptyList(),
                warnings = warnings.toList()
            )
        }

        warnings.add("Using search results; track order may be incomplete")
        return AlbumResolution(
            artist = artist,
            albumTitle = albumTitle,
            source = AlbumResolutionSource.SEARCH_FALLBACK,
            tracks = tracks,
            warnings = warnings.toList()
        )
    }

    private fun empty(
        artist: String,
        albumTitle: String,
        warnings: List<String>
    ) = AlbumResolution(
        artist = artist,
        albumTitle = albumTitle,
        source = AlbumResolutionSource.NONE,
        tracks = emptyList(),
        warnings = warnings.toList()
    )

    private fun isValidCandidate(candidate: AlbumCandidate): Boolean {
        if (candidate.videoId.isBlank() || candidate.title.isBlank()) return false
        if (candidate.title.contains("full album", ignoreCase = true)) return false
        val duration = candidate.durationMs?.takeIf { it > 0 } ?: return true
        return duration in MIN_TRACK_MS..MAX_TRACK_MS
    }

    private fun titlesMatch(targetCore: String, listingTitle: String): Boolean {
        if (targetCore.isBlank()) return false
        val listing = TitleNormalizer.normalize(listingTitle).core
        if (listing.isBlank()) return false
        if (listing == targetCore) return true
        val shortest = min(listing.split(' ').size, targetCore.split(' ').size)
        return shortest >= 2 && (listing.contains(targetCore) || targetCore.contains(listing))
    }
}
