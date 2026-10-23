package com.sharl.musicapp

import android.content.Context
import com.sharl.musicapp.data.album.AlbumCandidate
import com.sharl.musicapp.data.album.AlbumCard
import com.sharl.musicapp.data.album.AlbumCatalog
import com.sharl.musicapp.data.album.AlbumListing

/** Binds the album resolver to the app's yt-dlp-backed catalog. */
internal class YtdlpAlbumCatalog(private val context: Context) : AlbumCatalog {
    override suspend fun searchAlbumCards(query: String): List<AlbumCard> =
        YtdlpHelper.albumCards(context, query)

    override suspend fun loadAlbumPlaylist(browseId: String): AlbumListing? =
        YtdlpHelper.albumListing(context, browseId)

    override suspend fun searchSongCandidates(query: String, limit: Int): List<AlbumCandidate> =
        YtdlpHelper.songCandidates(context, query, limit)

    override suspend fun searchYouTubeCandidates(query: String, limit: Int): List<AlbumCandidate> =
        YtdlpHelper.youtubeCandidates(context, query, limit)
}
