package com.sharl.musicapp.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sharl.musicapp.data.Playlist
import com.sharl.musicapp.data.PlaylistRepository
import com.sharl.musicapp.data.PlaylistSong
import com.sharl.musicapp.data.Song
import kotlinx.coroutines.flow.first

@Composable
fun PlaylistsScreen(
    playlists: List<Playlist>,
    openPlaylistId: Long?,
    playlistRepo: PlaylistRepository,
    onBack: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onPlayFromPlaylist: (List<PlaylistSong>, Int) -> Unit,
    onCreatePlaylist: () -> Unit,
    playlistName: String,
    playlistCreatedAt: Long,
    onRequestRename: () -> Unit,
    onRequestDelete: () -> Unit,
    onSongMenu: (PlaylistSong) -> Unit,
    onPlaylistMenu: (Playlist) -> Unit = {},
    loadingVideoId: String? = null,
    onPreExtract: (String) -> Unit = {}
) {
    if (openPlaylistId != null) {
        val playlistSongs by remember(openPlaylistId) {
            playlistRepo.songs(openPlaylistId)
        }.collectAsState(initial = emptyList())

        LaunchedEffect(openPlaylistId) {
            val list = playlistRepo.songs(openPlaylistId).first()
            Log.d("PLAYLIST", "loaded ${list.size} songs for playlist $openPlaylistId")
        }

        LaunchedEffect(openPlaylistId, playlistSongs.firstOrNull()?.videoId) {
            playlistSongs.firstOrNull()?.videoId?.let { onPreExtract(it) }
        }

        PlaylistSongsContent(
            playlistSongs = playlistSongs,
            playlistName = playlistName,
            playlistCreatedAt = playlistCreatedAt,
            onBack = onBack,
            onPlayFromPlaylist = onPlayFromPlaylist,
            onRequestRename = onRequestRename,
            onRequestDelete = onRequestDelete,
            onSongMenu = onSongMenu,
            loadingVideoId = loadingVideoId
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCreatePlaylist() }
                .padding(vertical = 14.dp, horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "+",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "New playlist",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        HorizontalDivider()

        if (playlists.isEmpty()) {
            Text("No playlists yet.", modifier = Modifier.padding(top = 8.dp))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(playlists, key = { it.id }) { pl ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenPlaylist(pl.id) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = pl.name,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            IconButton(onClick = { onPlaylistMenu(pl) }) {
                                Text(
                                    "⋮",
                                    style = MaterialTheme.typography.titleLarge
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
