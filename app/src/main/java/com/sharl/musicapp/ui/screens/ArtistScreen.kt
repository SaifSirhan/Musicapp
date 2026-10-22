package com.sharl.musicapp.ui.screens

import android.content.Context
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sharl.musicapp.AlbumResult
import com.sharl.musicapp.QueueItem
import com.sharl.musicapp.SearchResult
import com.sharl.musicapp.YtdlpHelper
import com.sharl.musicapp.data.Song
import com.sharl.musicapp.ui.components.Thumbnail
import com.sharl.musicapp.ui.util.youtubeThumb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun ArtistScreen(
    artistName: String,
    localSongs: List<Song>,
    context: Context,
    scope: CoroutineScope,
    onPlay: (List<QueueItem>, Int) -> Unit,
    // videoId, title, artist, album, track number
    onDownload: (String, String, String, String?, Int?) -> Unit,
    downloadedIds: Set<String>,
    onDownloadDiscography: (String) -> Unit,
    onBack: () -> Unit
) {
    var webResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var albums by remember { mutableStateOf<List<AlbumResult>>(emptyList()) }
    var albumsLoading by remember { mutableStateOf(false) }
    var selectedAlbum by remember { mutableStateOf<AlbumResult?>(null) }
    var albumTracks by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var albumTracksLoading by remember { mutableStateOf(false) }

    LaunchedEffect(artistName) {
        if (artistName.isBlank()) return@LaunchedEffect
        isLoading = true
        try {
            val results = YtdlpHelper.searchYouTube(context, artistName, limit = 20)
            // Filter out already downloaded songs
            webResults = results.filter { it.videoId !in downloadedIds }
            Log.d("ARTIST", "Found ${webResults.size} web results for $artistName")
        } catch (e: Exception) {
            Log.e("ARTIST", "Search failed for $artistName", e)
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(artistName) {
        if (artistName.isBlank()) return@LaunchedEffect
        albumsLoading = true
        albums = YtdlpHelper.searchAlbums(context, artistName)
        albumsLoading = false
    }

    LaunchedEffect(selectedAlbum) {
        val album = selectedAlbum ?: return@LaunchedEffect
        albumTracksLoading = true
        albumTracks = YtdlpHelper.searchAlbumTracks(
            context, artistName, album.title, limit = 20
        )
        if (albumTracks.isEmpty()) {
            // Some album titles are too noisy to find individual tracks.
            albumTracks = YtdlpHelper.searchYouTube(context, "$artistName songs", limit = 20)
        }
        albumTracksLoading = false
    }

    val localAlbums = remember(localSongs) {
        localSongs.mapNotNull { it.album?.trim()?.takeIf { a -> a.isNotEmpty() } }
            .distinctBy { it.lowercase() }
    }
    val chipAlbums = remember(localAlbums, albums) {
        (localAlbums + albums.map { it.title }).distinctBy { it.lowercase() }
    }

    val album = selectedAlbum
    if (album != null) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selectedAlbum = null; albumTracks = emptyList() }) {
                    Text("←", style = MaterialTheme.typography.headlineSmall)
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        album.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        artistName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider()

            val localAlbumSongs = remember(localSongs, albumTracks, album.title) {
                localSongs.filter { song ->
                    song.album?.equals(album.title, ignoreCase = true) == true ||
                        albumTracks.any { it.videoId == song.videoId }
                }.distinctBy { it.videoId }
            }
            // Prefer the remote album search order: it follows the tracklist.
            // Local-only rows fall back to their persisted track numbers.
            val orderedLocalSongs = remember(localAlbumSongs, albumTracks) {
                if (albumTracks.isNotEmpty()) {
                    val byRemoteOrder = albumTracks.mapNotNull { track ->
                        localAlbumSongs.firstOrNull { it.videoId == track.videoId }
                    }
                    val extra = localAlbumSongs
                        .filter { song -> albumTracks.none { it.videoId == song.videoId } }
                        .sortedBy { it.trackNumber ?: Int.MAX_VALUE }
                    (byRemoteOrder + extra).distinctBy { it.videoId }
                } else {
                    localAlbumSongs.sortedBy { it.trackNumber ?: Int.MAX_VALUE }
                }
            }
            val missingTracks = albumTracks.filter { track ->
                localAlbumSongs.none { it.videoId == track.videoId }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    val downloadable = albumTracks.filter { it.videoId !in downloadedIds }
                    val allDownloaded = downloadable.isEmpty() && orderedLocalSongs.isNotEmpty()
                    Button(
                        onClick = {
                            albumTracks.forEachIndexed { idx, t ->
                                if (t.videoId !in downloadedIds) {
                                    onDownload(t.videoId, t.title, artistName, album.title, idx + 1)
                                }
                            }
                        },
                        enabled = downloadable.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            when {
                                allDownloaded -> "Album downloaded"
                                albumTracks.isEmpty() -> "Download Album"
                                else -> "Download Album (${downloadable.size})"
                            }
                        )
                    }
                }
                if (orderedLocalSongs.isNotEmpty()) {
                    item {
                        Button(
                            onClick = {
                                onPlay(
                                    orderedLocalSongs.map {
                                        QueueItem(it.videoId, it.title, it.artist)
                                    },
                                    0
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Text("▶  Play Album (${orderedLocalSongs.size})")
                        }
                    }
                    itemsIndexed(orderedLocalSongs, key = { _, song -> song.videoId }) { idx, song ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPlay(
                                        orderedLocalSongs.map {
                                            QueueItem(it.videoId, it.title, it.artist)
                                        },
                                        idx
                                    )
                                }
                                .padding(vertical = 6.dp, horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${song.trackNumber ?: (idx + 1)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(24.dp)
                            )
                            Thumbnail(song.thumbnailUrl ?: youtubeThumb(song.videoId), 48.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                song.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                        HorizontalDivider()
                    }
                }
                if (albumTracksLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (missingTracks.isNotEmpty()) {
                    if (orderedLocalSongs.isNotEmpty()) {
                        item {
                            Text(
                                "Not downloaded",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                    itemsIndexed(missingTracks, key = { _, result -> result.videoId }) { idx, result ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp, horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Thumbnail(youtubeThumb(result.videoId), 48.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                result.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    val trackNumber =
                                        albumTracks.indexOfFirst { it.videoId == result.videoId } + 1
                                    onDownload(
                                        result.videoId,
                                        result.title,
                                        artistName,
                                        album.title,
                                        trackNumber.takeIf { it > 0 }
                                    )
                                }
                            ) {
                                Text("⬇", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                        HorizontalDivider()
                    }
                }
                if (!albumTracksLoading && albumTracks.isEmpty() && orderedLocalSongs.isEmpty()) {
                    item {
                        Text(
                            "No tracks found for this album.",
                            modifier = Modifier.padding(32.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Text("←", style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                artistName.ifBlank { "Unknown Artist" },
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
        }

        HorizontalDivider()

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // Phase 47: prominent bulk download of the artist's whole catalogue.
            item {
                Button(
                    onClick = { onDownloadDiscography(artistName) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                ) {
                    Text("⬇  Download All")
                }
            }

            if (chipAlbums.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        Text(
                            "Albums",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                FilterChip(
                                    selected = true,
                                    onClick = {},
                                    label = { Text("All") }
                                )
                            }
                            items(chipAlbums, key = { it }) { title ->
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        val remote = albums.firstOrNull {
                                            it.title.equals(title, ignoreCase = true)
                                        }
                                        selectedAlbum = remote
                                            ?: AlbumResult(title = title, videoId = "", thumbnailUrl = null)
                                    },
                                    label = {
                                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Local Songs Section
            if (localSongs.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "In Your Library (${localSongs.size})",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val items = localSongs.map {
                                    QueueItem(it.videoId, it.title, it.artist)
                                }
                                onPlay(items, 0)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Play All")
                        }
                    }
                }

                items(localSongs, key = { it.videoId }) { song ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPlay(listOf(QueueItem(song.videoId, song.title, song.artist)), 0)
                            }
                            .padding(vertical = 6.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Thumbnail(song.thumbnailUrl ?: youtubeThumb(song.videoId), 48.dp)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                song.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                song.artist,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text("✓", color = MaterialTheme.colorScheme.primary)
                    }
                    HorizontalDivider()
                }
            }

            // Discography Section
            if (albumsLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (albums.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Discography (${albums.size})",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Tap an album to see its tracklist",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(albums.chunked(3)) { rowAlbums ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowAlbums.forEach { a ->
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { selectedAlbum = a }
                                    .padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Thumbnail(a.thumbnailUrl ?: youtubeThumb(a.videoId), 72.dp)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    a.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        repeat(3 - rowAlbums.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            // Discovery Section
            item {
                Column(modifier = Modifier.padding(16.dp)) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Discover More",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                items(webResults, key = { it.videoId }) { result ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Thumbnail(youtubeThumb(result.videoId), 48.dp)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                result.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                artistName,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = {
                                onDownload(result.videoId, result.title, artistName, null, null)
                            }
                        ) {
                            Text("⬇", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    HorizontalDivider()
                }
            }

            if (webResults.isEmpty() && !isLoading && localSongs.isEmpty()) {
                item {
                    Text(
                        "No songs found for this artist.",
                        modifier = Modifier.padding(32.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}
