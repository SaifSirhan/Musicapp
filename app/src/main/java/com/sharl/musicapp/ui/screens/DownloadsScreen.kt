package com.sharl.musicapp.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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

enum class SortOption { DATE_ADDED, ARTIST_AZ, TITLE_AZ }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DownloadsScreen(
    downloads: List<Download>,
    downloadStates: Map<String, DownloadState>,
    onPlay: (Download) -> Unit,
    onRemove: (Download) -> Unit,
    onRemoveAll: () -> Unit,
    onFindMusic: () -> Unit,
    onMenuAction: (Download, DownloadMenuAction) -> Unit = { _, _ -> },
    onBulkDelete: (List<Download>) -> Unit = {},
    totalDownloads: Int = downloads.size,
    searchQuery: String = ""
) {
    var menuDownload by remember { mutableStateOf<Download?>(null) }
    
    // Phase 42: Multi-select mode
    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateOf<Set<String>>(emptySet()) }
    
    // Phase 42: Sorting
    var sortOption by remember { mutableStateOf(SortOption.DATE_ADDED) }
    var showSortMenu by remember { mutableStateOf(false) }
    
    val sortedDownloads = remember(downloads, sortOption) {
        when (sortOption) {
            SortOption.DATE_ADDED -> downloads
            SortOption.ARTIST_AZ -> downloads.sortedBy { it.artist.lowercase() }
            SortOption.TITLE_AZ -> downloads.sortedBy { it.title.lowercase() }
        }
    }
    
    if (totalDownloads == 0) {
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
    Column(modifier = Modifier.fillMaxSize()) {
        // Phase 42: Header with storage info, sort button, and multi-select toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (searchQuery.isBlank()) "${downloads.size} Songs · ${formatBytes(totalBytes)}"
                else "${downloads.size} of $totalDownloads Songs",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            
            // Sort button
            Box {
                IconButton(onClick = { showSortMenu = true }) {
                    Text("⇅", style = MaterialTheme.typography.titleLarge)
                }
                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false }
                ) {
                    SortOption.values().forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    when (option) {
                                        SortOption.DATE_ADDED -> "Date Added"
                                        SortOption.ARTIST_AZ -> "Artist A-Z"
                                        SortOption.TITLE_AZ -> "Title A-Z"
                                    }
                                )
                            },
                            onClick = {
                                sortOption = option
                                showSortMenu = false
                            },
                            trailingIcon = {
                                if (sortOption == option) {
                                    Text("✓", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        )
                    }
                }
            }
            
            // Multi-select toggle
            IconButton(onClick = { 
                isMultiSelectMode = !isMultiSelectMode
                if (!isMultiSelectMode) selectedIds.value = emptySet()
            }) {
                Text(if (isMultiSelectMode) "✓" else "☐", style = MaterialTheme.typography.titleLarge)
            }
        }
        
        HorizontalDivider()
        
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (sortedDownloads.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No songs match \"$searchQuery\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            itemsIndexed(sortedDownloads, key = { _, d -> d.videoId }) { _, d ->
                val st = downloadStates[d.videoId]
                val isSelected = selectedIds.value.contains(d.videoId)
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                if (isMultiSelectMode) {
                                    val current = selectedIds.value.toMutableSet()
                                    if (current.contains(d.videoId)) {
                                        current.remove(d.videoId)
                                    } else {
                                        current.add(d.videoId)
                                    }
                                    selectedIds.value = current
                                } else {
                                    onPlay(d)
                                }
                            },
                            onLongClick = {
                                if (!isMultiSelectMode) {
                                    isMultiSelectMode = true
                                    selectedIds.value = setOf(d.videoId)
                                }
                            }
                        )
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                            else androidx.compose.ui.graphics.Color.Transparent
                        )
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Checkbox in multi-select mode
                    if (isMultiSelectMode) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = {
                                val current = selectedIds.value.toMutableSet()
                                if (it) current.add(d.videoId) else current.remove(d.videoId)
                                selectedIds.value = current
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    
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
        
        // Phase 42: BottomAppBar for bulk actions in multi-select mode
        if (isMultiSelectMode && selectedIds.value.isNotEmpty()) {
            BottomAppBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    "${selectedIds.value.size} selected",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 16.dp)
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    val selectedDownloads = sortedDownloads.filter { it.videoId in selectedIds.value }
                    onBulkDelete(selectedDownloads)
                    isMultiSelectMode = false
                    selectedIds.value = emptySet()
                }) {
                    Text("🗑", style = MaterialTheme.typography.titleLarge)
                }
                IconButton(onClick = {
                    isMultiSelectMode = false
                    selectedIds.value = emptySet()
                }) {
                    Text("✕", style = MaterialTheme.typography.titleLarge)
                }
            }
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
