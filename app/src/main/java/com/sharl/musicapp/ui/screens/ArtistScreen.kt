package com.sharl.musicapp.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sharl.musicapp.YtdlpHelper
import com.sharl.musicapp.data.Song
import com.sharl.musicapp.ui.components.SongRow
import com.sharl.musicapp.ui.util.youtubeThumb

/**
 * Phase 24 (16a MVP): a lightweight artist page. There is no artist metadata
 * API in the app, so the "top songs" are a YouTube search for the artist name.
 * Tapping a song plays the whole result list as a queue — an artist page is a
 * container, so it never opens a Mix (Rule 1).
 */
@Composable
fun ArtistScreen(
    artistName: String,
    onBack: () -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onSongMenu: (Song) -> Unit = {},
    downloadIds: Collection<String> = emptySet(),
    loadingVideoId: String? = null
) {
    val context = LocalContext.current
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(artistName) {
        loading = true
        error = null
        songs = emptyList()
        val result = runCatching {
            YtdlpHelper.searchYouTube(context, artistName, 10)
        }.getOrElse { e ->
            error = e.message ?: "Search failed"
            emptyList()
        }
        songs = result
            .distinctBy { it.videoId }
            .map { r ->
                Song(
                    videoId = r.videoId,
                    title = r.title,
                    artist = artistName,
                    thumbnailUrl = youtubeThumb(r.videoId),
                    lastPlayedAt = 0L,
                    playCount = 0
                )
            }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = onBack) {
                    Text("←", style = MaterialTheme.typography.headlineMedium)
                }
            }
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    artistName,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (songs.isNotEmpty()) "Top songs" else "Artist",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = {
                    if (songs.isNotEmpty()) onPlaySongs(songs, 0)
                },
                enabled = songs.isNotEmpty()
            ) {
                Text("Play All")
            }
        }

        HorizontalDivider()

        when {
            loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Finding songs by $artistName…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            songs.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        error?.let { "Couldn't load $artistName: $it" }
                            ?: "No songs found for $artistName.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(songs, key = { it.videoId }) { song ->
                        val index = songs.indexOfFirst { it.videoId == song.videoId }
                        SongRow(
                            song = song,
                            onClick = { onPlaySongs(songs, index) },
                            onToggleFavorite = {},
                            onLongClick = { onSongMenu(song) },
                            showDownloaded = downloadIds.contains(song.videoId),
                            isLoading = loadingVideoId == song.videoId,
                            onMenuClick = { onSongMenu(song) }
                        )
                    }
                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
        }
    }
}
