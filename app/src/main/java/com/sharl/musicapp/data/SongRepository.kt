package com.sharl.musicapp.data

import android.content.Context
import com.sharl.musicapp.data.matching.TitleNormalizer

class SongRepository(context: Context) {
    private val dao = AppDatabase.get(context).songDao()

    companion object {
        const val DURATION_TOLERANCE_MS = 3_000L
    }
    
    // Phase 44d: Expose DAO for artist queries in LibraryTab
    fun getDao() = dao

    suspend fun recordPlay(
        videoId: String,
        title: String,
        artist: String,
        album: String? = null,
        thumbnailUrl: String? = null,
        durationMs: Long = 0L
    ) {
        val existing = dao.getById(videoId)
        val now = System.currentTimeMillis()
        dao.upsert(
            Song(
                videoId = videoId,
                title = title,
                artist = artist,
                album = album ?: existing?.album,
                trackNumber = existing?.trackNumber,
                thumbnailUrl = thumbnailUrl ?: existing?.thumbnailUrl,
                lastPlayedAt = now,
                playCount = (existing?.playCount ?: 0) + 1,
                isFavorite = existing?.isFavorite ?: false,
                isHidden = existing?.isHidden ?: false,
                durationMs = durationMs.takeIf { it > 0 } ?: existing?.durationMs ?: 0L,
                normalizedTitle = TitleNormalizer.normalizedCore(title, artist),
                normalizedTitleVersion = TitleNormalizer.NORMALIZATION_VERSION
            )
        )
    }

    suspend fun saveDownloadedSong(
        videoId: String,
        title: String,
        artist: String,
        album: String?,
        thumbnailUrl: String?,
        trackNumber: Int? = null,
        durationMs: Long = 0L
    ) {
        val existing = dao.getById(videoId)
        dao.upsert(
            Song(
                videoId = videoId,
                title = title,
                artist = artist,
                album = album ?: existing?.album,
                trackNumber = trackNumber ?: existing?.trackNumber,
                thumbnailUrl = thumbnailUrl ?: existing?.thumbnailUrl,
                lastPlayedAt = existing?.lastPlayedAt ?: 0L,
                playCount = existing?.playCount ?: 0,
                isFavorite = existing?.isFavorite ?: false,
                isHidden = existing?.isHidden ?: false,
                durationMs = durationMs.takeIf { it > 0 } ?: existing?.durationMs ?: 0L,
                normalizedTitle = TitleNormalizer.normalizedCore(title, artist),
                normalizedTitleVersion = TitleNormalizer.NORMALIZATION_VERSION
            )
        )
    }

    /**
     * One-to-one duplicate lookup: same artist and normalized title, with both
     * durations known and within [DURATION_TOLERANCE_MS].
     */
    suspend fun findNearDuplicate(artist: String, title: String, durationMs: Long): Song? {
        if (artist.isBlank() || durationMs <= 0) return null
        val normalized = TitleNormalizer.normalizedCore(title, artist)
        if (normalized.isBlank()) return null
        return dao.findNearDuplicate(artist, normalized, durationMs, DURATION_TOLERANCE_MS)
    }

    /**
     * Idempotent, resumable backfill. Rows are written current-version one batch
     * at a time, so cancellation leaves completed work committed.
     */
    suspend fun backfillNormalizedTitles(batchSize: Int = 200) {
        dao.backfillDurationsFromDownloads()
        val version = TitleNormalizer.NORMALIZATION_VERSION
        while (true) {
            val batch = dao.songsNeedingNormalization(version, batchSize)
            if (batch.isEmpty()) return
            for (song in batch) {
                dao.setNormalization(
                    song.videoId,
                    TitleNormalizer.normalizedCore(song.title, song.artist),
                    version
                )
            }
        }
    }

    fun recentlyPlayed() = dao.recentlyPlayed()

    suspend fun get(videoId: String): Song? = dao.getById(videoId)

    suspend fun toggleFavorite(song: Song) {
        val existing = dao.getById(song.videoId)
        if (existing == null) {
            dao.upsert(song.copy(isFavorite = !song.isFavorite))
        } else {
            dao.setFavorite(song.videoId, !existing.isFavorite)
        }
    }

    suspend fun hide(song: Song) {
        dao.hide(song.videoId)
    }

    fun favorites() = dao.favorites()

    fun recentSongs(limit: Int = 20) = dao.recentSongs(limit)
    fun mostPlayed(limit: Int = 20) = dao.mostPlayed(limit)
    fun songsByArtist(artist: String, limit: Int = 20) = dao.songsByArtist(artist, limit)
    fun allSongs() = dao.allSongs()
    fun favoriteSongs(limit: Int = 20) = dao.favoriteSongs(limit)
    fun topArtist() = dao.topArtist()
    suspend fun searchLocal(q: String) = dao.searchLocal(q)
}
