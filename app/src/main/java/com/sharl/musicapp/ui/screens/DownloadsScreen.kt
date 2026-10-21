package com.sharl.musicapp.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sharl.musicapp.DownloadState
import com.sharl.musicapp.data.Download
import com.sharl.musicapp.data.Song
import com.sharl.musicapp.ui.components.SongActionsSheet
import com.sharl.musicapp.ui.components.Thumbnail
import com.sharl.musicapp.ui.util.formatBytes
import com.sharl.musicapp.ui.util.youtubeThumb

enum class DownloadMenuAction { PLAY_NEXT, ADD_TO_QUEUE, START_MIX, GO_TO_ARTIST }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    downloads: List<Download>,
    downloadStates: Map<String, DownloadState>,
    onPlay: (Int) -> Unit,
    onRemove: (Download) -> Unit,
    onRemoveAll: () -> Unit,
    onFindMusic: () -> Unit,
    onMenuAction: (Download, DownloadMenuAction) -> Unit = { _, _ -> }
) {
    var menuDownload by remember { mutableStateOf<Download?>(null) }
    if (downloads.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "✓",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Music and podcasts that you download will appear here.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onFindMusic) { Text("Find music") }
        }
        return
    }

    val totalBytes = downloads.sumOf { it.fileSizeBytes }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${downloads.size} songs · ${formatBytes(totalBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = onRemoveAll) {
                    Text(
                        "⋮",
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(downloads, key = { _, d -> d.videoId }) { idx, d ->
                val st = downloadStates[d.videoId]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlay(idx) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Thumbnail(d.thumbnailUrl ?: youtubeThumb(d.videoId), 56.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            d.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            d.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (st is DownloadState.Downloading) {
                        Text(
                            "${st.percent}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    } else {
                        Text(
                            "✓",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        IconButton(onClick = { menuDownload = d }) {
                            Text(
                                "⋮",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }

    val sel = menuDownload
    if (sel != null) {
        val song = Song(
            videoId = sel.videoId,
            title = sel.title,
            artist = sel.artist,
            thumbnailUrl = sel.thumbnailUrl,
            lastPlayedAt = 0L,
            playCount = 0
        )
        val artistKnown = sel.artist.isNotBlank() &&
            !sel.artist.equals("Unknown Artist", ignoreCase = true) &&
            !sel.artist.equals("YouTube", ignoreCase = true)
        SongActionsSheet(
            song = song,
            downloadState = downloadStates[sel.videoId],
            isDownloaded = true,
            onDismiss = { menuDownload = null },
            onPlayNext = {
                menuDownload = null
                onMenuAction(sel, DownloadMenuAction.PLAY_NEXT)
            },
            onAddToQueue = {
                menuDownload = null
                onMenuAction(sel, DownloadMenuAction.ADD_TO_QUEUE)
            },
            onStartMix = {
                menuDownload = null
                onMenuAction(sel, DownloadMenuAction.START_MIX)
            },
            onDownload = {},
            onRemoveDownload = {
                menuDownload = null
                onRemove(sel)
            },
            onGoToArtist = if (artistKnown) {
                {
                    menuDownload = null
                    onMenuAction(sel, DownloadMenuAction.GO_TO_ARTIST)
                }
            } else null
        )
    }
}
