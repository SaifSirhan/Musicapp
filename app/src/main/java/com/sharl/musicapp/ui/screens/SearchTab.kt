package com.sharl.musicapp.ui.screens

import android.content.Context
import android.util.Log
import androidx.compose.foundation.combinedClickable
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
    onToggleFavorite: (Song) -> Unit,
    onSongMenu: (Song) -> Unit,
    downloadIds: List<String>,
    loadingVideoId: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = { onQueryChange(it) },
            placeholder = { Text("Search songs, artists…") },
            singleLine = true,
            leadingIcon = {
                Text("🔍",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 12.dp))
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Text("✕", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    }
                }
            },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
                focusedContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }

    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            onResultsChange(emptyList())
            onSearchingChange(false)
            return@LaunchedEffect
        }
        if (trimmed.length < 2) return@LaunchedEffect
        kotlinx.coroutines.delay(400)
        onSearchingChange(true)
        try {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val found = YtdlpHelper.searchYouTube(context, trimmed)
            Log.d("YTDL", "search '${trimmed}' took ${android.os.SystemClock.elapsedRealtime() - t0}ms")
            if (query.trim() == trimmed) {
                onResultsChange(found)
                // Warm the first result so an immediate tap is instant.
                found.firstOrNull()?.videoId?.let { scope.launch { onPreExtract(it) } }
            }
        } catch (e: Exception) {
            Log.e("YTDL", "live search failed: ${e.message}")
        } finally {
            if (query.trim() == trimmed) onSearchingChange(false)
        }
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
                Text(
                    "Search YouTube for any song, or find it in your library.",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
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
            items(localMatches, key = { it.videoId }) { song ->
                SongRow(
                    song = song,
                    onClick = {
                        onPlayFromQueue(
                            localMatches.map { QueueItem(it.videoId, it.title) },
                            localMatches.indexOf(song)
                        )
                    },
                    onToggleFavorite = { onToggleFavorite(song) },
                    onLongClick = { onSongMenu(song) },
                    showDownloaded = downloadIds.contains(song.videoId),
                    isLoading = song.videoId == loadingVideoId
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
        val items = results.map { QueueItem(it.videoId, it.title) }
        itemsIndexed(results, key = { _, result -> result.videoId }) { idx, result ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onPlayFromQueue(items, idx) },
                        onLongClick = {
                            onSongMenu(Song(
                                videoId = result.videoId,
                                title = result.title,
                                artist = "YouTube",
                                thumbnailUrl = youtubeThumb(result.videoId),
                                lastPlayedAt = 0L,
                                playCount = 0,
                                isFavorite = false,
                                isHidden = false
                            ))
                        }
                    )
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = result.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (result.videoId == loadingVideoId) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
            HorizontalDivider()
        }
        if (!isSearching && query.trim().length >= 2 && localMatches.isEmpty() && results.isEmpty()) {
            item {
                Text(
                    "No results.",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}
