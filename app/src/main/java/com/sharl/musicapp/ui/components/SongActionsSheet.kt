package com.sharl.musicapp.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sharl.musicapp.DownloadState
import com.sharl.musicapp.data.Song

/**
 * One action sheet for every song surface (search, playlists, downloads,
 * favorites, menus). Keeping it in one place guarantees the five standard
 * actions stay consistent across the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongActionsSheet(
    song: Song,
    downloadState: DownloadState?,
    isDownloaded: Boolean,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onStartMix: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onGoToArtist: (() -> Unit)? = null,
    extraActions: (@Composable () -> Unit)? = null
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
            HorizontalDivider()

            MenuItem("Play next") { onPlayNext() }
            MenuItem("Add to queue") { onAddToQueue() }
            MenuItem("Start Mix", icon = "📻") { onStartMix() }

            when {
                downloadState is DownloadState.Downloading ->
                    MenuItem("Downloading… ${downloadState.percent}%") {}
                downloadState is DownloadState.Queued -> MenuItem("Queued…") {}
                isDownloaded -> MenuItem("Remove download") { onRemoveDownload() }
                downloadState is DownloadState.Failed -> MenuItem("Retry download") {
                    onDownload()
                }
                else -> MenuItem("Download") { onDownload() }
            }

            if (onGoToArtist != null) {
                MenuItem("Go to Artist") { onGoToArtist() }
            }
            extraActions?.invoke()
        }
    }
}
