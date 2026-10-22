package com.sharl.musicapp.ui.screens

import android.content.Context
import android.util.Log
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sharl.musicapp.QueueItem
import com.sharl.musicapp.SearchResult
import com.sharl.musicapp.YtdlpHelper
import com.sharl.musicapp.data.Song
import com.sharl.musicapp.ui.components.SongRow
import com.sharl.musicapp.ui.util.youtubeThumb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Search tab (tab 1), extracted verbatim from PlayerScreen. All captured state
// arrives as parameters — no ViewModel, per the Phase 16 constraints. It is a
// ColumnScope extension because the results LazyColumn consumes weight(1f)
// from the PlayerScreen column.
@Composable
fun ColumnScope.SearchTab(
    context: Context,
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<SearchResult>,
    onResultsChange: (List<SearchResult>) -> Unit,
    isSearching: Boolean,
    onSearchingChange: (Boolean) -> Unit,
    localMatches: List<Song>,
    onLocalMatchesChange: (List<Song>) -> Unit,
    onSearchLocal: suspend (String) -> List<Song>,
    onPreExtract: suspend (String) -> Unit,
    scope: CoroutineScope,
    onPlayFromQueue: (List<QueueItem>, Int) -> Unit,
    onStartMix: (Song) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onSongMenu: (Song) -> Unit,
    downloadIds: List<String>,
    loadingVideoId: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = { onQueryChange(it) },
            placeholder = { 
                Text(
                    "Search songs, artists…",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge
                ) 
            },
            singleLine = true,
            leadingIcon = {
                Text(
                    "🔍",
                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 8.dp)
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Text(
                            "✕",
                            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = androidx.compose.material3.MaterialTheme.colorScheme.outline,
                focusedContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Track the current search job so we can cancel stale queries
    val currentSearchJob = androidx.compose.runtime.mutableStateOf<kotlinx.coroutines.Job?>(null)

    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            onResultsChange(emptyList())
            onSearchingChange(false)
            currentSearchJob.value?.cancel()
            currentSearchJob.value = null
            return@LaunchedEffect
        }
        if (trimmed.length < 2) return@LaunchedEffect
        
        kotlinx.coroutines.delay(400)
        
        // Cancel any previous search job
        currentSearchJob.value?.cancel()
        
        // Launch in the provided scope (survives tab switches)
        val job = scope.launch {
            onSearchingChange(true)
            try {
                val t0 = android.os.SystemClock.elapsedRealtime()
                // yt-dlp occasionally repeats an entry; the results list keys by
                // videoId, and duplicate keys crash Compose.
                val found = YtdlpHelper.searchYouTube(context, trimmed)
                    .distinctBy { it.videoId }
                Log.d("YTDL", "search '${trimmed}' took ${android.os.SystemClock.elapsedRealtime() - t0}ms")
                if (query.trim() == trimmed) {
                    onResultsChange(found)
                    // Warm the first result so an immediate tap is instant.
                    found.firstOrNull()?.videoId?.let { onPreExtract(it) }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("YTDL", "live search failed: ${e.message}")
            } finally {
                if (query.trim() == trimmed) onSearchingChange(false)
            }
        }
        currentSearchJob.value = job
    }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isBlank()) {
            onLocalMatchesChange(emptyList())
            return@LaunchedEffect
        }
        onLocalMatchesChange(onSearchLocal(q))
    }

    if (isSearching) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = androidx.compose.material3.MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Searching…",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(top = 8.dp)
    ) {
        if (query.isBlank()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.layout.Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "🔍",
                            style = androidx.compose.material3.MaterialTheme.typography.displaySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Text(
                            "Search for music",
                            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Find songs on YouTube or in your library",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
        if (localMatches.isNotEmpty()) {
            item {
                Text(
                    "In your library",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                )
            }
            items(localMatches, key = { "local:${it.videoId}" }) { song ->
                SongRow(
                    song = song,
                    onClick = { onStartMix(song) },
                    onToggleFavorite = { onToggleFavorite(song) },
                    onLongClick = { onSongMenu(song) },
                    showDownloaded = downloadIds.contains(song.videoId),
                    isLoading = song.videoId == loadingVideoId,
                    onMenuClick = { onSongMenu(song) }
                )
            }
            item { HorizontalDivider() }
            item {
                Text(
                    "From YouTube",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                )
            }
        }
        itemsIndexed(results, key = { _, result -> "yt:${result.videoId}" }) { _, result ->
            val displayTitle = result.title.ifBlank { "Unknown Track" }
            val song = Song(
                videoId = result.videoId,
                title = displayTitle,
                artist = "YouTube",
                thumbnailUrl = youtubeThumb(result.videoId),
                lastPlayedAt = 0L,
                playCount = 0,
                isFavorite = false,
                isHidden = false
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onStartMix(song) },
                        onLongClick = { onSongMenu(song) }
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                    ),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (result.videoId == loadingVideoId) {
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                    )
                } else {
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = { onSongMenu(song) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Text(
                            "⋮",
                            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
            )
        }
        if (!isSearching && query.trim().length >= 2 && localMatches.isEmpty() && results.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.layout.Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "😕",
                            style = androidx.compose.material3.MaterialTheme.typography.displaySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Text(
                            "No results found",
                            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Try a different search term",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}
