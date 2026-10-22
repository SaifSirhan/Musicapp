package com.sharl.musicapp.ui.screens

import android.content.Context
import android.util.Log
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    onDownload: (String, String, String) -> Unit, // videoId, title, artist
    downloadedIds: Set<String>,
    onBack: () -> Unit
) {
    var webResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    
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
                                onDownload(result.videoId, result.title, artistName)
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
