package com.sharl.musicapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sharl.musicapp.data.Song
import com.sharl.musicapp.ui.components.Thumbnail
import com.sharl.musicapp.ui.util.formatDate
import com.sharl.musicapp.ui.util.youtubeThumb

@Composable
fun thumbCell(url: String?, modifier: Modifier) {
    if (url == null) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    } else {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    }
}

@Composable
fun PlaylistSongsContent(
    playlistSongs: List<Song>,
    playlistName: String,
    playlistCreatedAt: Long,
    onBack: () -> Unit,
    onPlayFromPlaylist: (List<Song>, Int) -> Unit,
    onRequestRename: () -> Unit,
    onRequestDelete: () -> Unit,
    onSongMenu: (Song) -> Unit,
    loadingVideoId: String? = null
) {
    val listState = rememberLazyListState()
    var showOverflow by remember(playlistName) { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        state = listState
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    IconButton(onClick = onBack) {
                        Text(
                            "←",
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    IconButton(onClick = { showOverflow = true }) {
                        Text(
                            "⋮",
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }
                    DropdownMenu(
                        expanded = showOverflow,
                        onDismissRequest = { showOverflow = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = { showOverflow = false; onRequestRename() }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = { showOverflow = false; onRequestDelete() }
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center
            ) {
                val thumbs = playlistSongs.take(4).map {
                    it.thumbnailUrl ?: youtubeThumb(it.videoId)
                }
                when {
                    thumbs.isEmpty() -> {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }
                    thumbs.size == 1 -> {
                        AsyncImage(
                            model = thumbs[0],
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    else -> {
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.weight(1f)) {
                                thumbCell(thumbs.getOrNull(0), Modifier.weight(1f))
                                thumbCell(thumbs.getOrNull(1), Modifier.weight(1f))
                            }
                            Row(Modifier.weight(1f)) {
                                thumbCell(thumbs.getOrNull(2), Modifier.weight(1f))
                                thumbCell(thumbs.getOrNull(3), Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            Text(
                text = playlistName,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Y",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "You",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Text(
                text = "${playlistSongs.size} songs · Created ${formatDate(playlistCreatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            CircleShape
                        )
                        .clickable {
                            if (playlistSongs.isNotEmpty()) {
                                onPlayFromPlaylist(playlistSongs, 0)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "▶",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    IconButton(onClick = { onRequestRename() }) {
                        Text(
                            "✏",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    IconButton(onClick = { onRequestDelete() }) {
                        Text(
                            "🗑",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
            }

            HorizontalDivider()
        }

        itemsIndexed(playlistSongs, key = { _, s -> s.videoId }) { idx, ps ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlayFromPlaylist(playlistSongs, idx) }
                    .padding(vertical = 8.dp, horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Thumbnail(ps.thumbnailUrl ?: youtubeThumb(ps.videoId), 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        ps.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        ps.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (ps.videoId == loadingVideoId) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        IconButton(onClick = { onSongMenu(ps) }) {
                            Text(
                                "⋮",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
            }
            HorizontalDivider()
        }

        item { Spacer(Modifier.height(96.dp)) }
    }
}
