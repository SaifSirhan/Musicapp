package com.sharl.musicapp

import android.Manifest
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.sharl.musicapp.data.Song
import com.sharl.musicapp.data.SongRepository
import com.sharl.musicapp.ui.theme.MusicAppTheme
import kotlinx.coroutines.launch

private const val TEST_VIDEO_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

data class QueueItem(val videoId: String, val title: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MusicAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PlayerScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun PlayerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { SongRepository(context) }
    val recent by repo.recentlyPlayed().collectAsState(initial = emptyList())
    val favorites by repo.favorites().collectAsState(initial = emptyList())
    var tab by remember { mutableStateOf(0) }  // 0 = Home, 1 = Search, 2 = Favorites

    var status by remember { mutableStateOf("Connecting...") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var connected by remember { mutableStateOf(false) }

    var queue by remember { mutableStateOf<List<QueueItem>>(emptyList()) }
    var queueIndex by remember { mutableStateOf(-1) }
    var nowPlayingTitle by remember { mutableStateOf<String?>(null) }
    var nowPlayingArtist by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        Log.d("CTRL", "Connecting to PlaybackService...")
        val token = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try {
                controller = future.get()
                connected = true
                status = "Ready"
                Log.d("CTRL", "MediaController connected")
            } catch (e: Exception) {
                status = "Controller error: ${e.message}"
                Log.e("CTRL", "MediaController connection failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            MediaController.releaseFuture(future)
            Log.d("CTRL", "MediaController released")
        }
    }

    var notifPermissionGranted by remember { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notifPermissionGranted = granted
        if (!granted) {
            status = "Notification permission denied — controls hidden"
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val already = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            notifPermissionGranted = already
            if (!already) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            notifPermissionGranted = true  // not required pre-33
        }
    }

    fun playFromQueue(items: List<QueueItem>, index: Int) {
        if (controller == null) {
            status = "Connecting..."
            return
        }
        if (index !in items.indices) return
        queue = items
        queueIndex = index
        val item = items[index]
        nowPlayingTitle = item.title
        nowPlayingArtist = "YouTube"
        status = "Extracting..."
        scope.launch {
            try {
                val url = YtdlpHelper.extractAudioUrlForId(context, item.videoId)
                if (url == null) {
                    status = "Error: extraction returned null"
                    return@launch
                }
                repo.recordPlay(item.videoId, item.title, "YouTube")
                controller!!.setMediaItem(
                    MediaItem.Builder()
                        .setUri(url)
                        .setMediaMetadata(
                            androidx.media3.common.MediaMetadata.Builder()
                                .setTitle(item.title)
                                .setArtist("YouTube")
                                .build()
                        )
                        .build()
                )
                controller!!.prepare()
                controller!!.play()
                isPlaying = true
                status = "Playing: ${item.title}"
            } catch (e: Exception) {
                val msg = e.message ?: "unknown error"
                Log.e("YTDL", "Failed: $msg")
                status = "Error: $msg"
            }
        }
    }

    fun playNext() {
        if (queueIndex < 0 || queueIndex >= queue.size - 1) {
            status = "End of queue"
            return
        }
        playFromQueue(queue, queueIndex + 1)
    }

    fun playPrev() {
        if (queueIndex <= 0) {
            status = "Start of queue"
            return
        }
        playFromQueue(queue, queueIndex - 1)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        androidx.compose.material3.TabRow(selectedTabIndex = tab) {
            androidx.compose.material3.Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text("Home") }
            )
            androidx.compose.material3.Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text("Search") }
            )
            androidx.compose.material3.Tab(
                selected = tab == 2,
                onClick = { tab = 2 },
                text = { Text("Favorites") }
            )
        }

        when (tab) {
            0 -> {
                if (recent.isEmpty()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "No recently played songs yet — search something to get started.",
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = 8.dp)
                    ) {
                        val items = recent.map { QueueItem(it.videoId, it.title) }
                        itemsIndexed(recent, key = { _, song -> song.videoId }) { idx, song ->
                            SongRow(
                                song = song,
                                onClick = { playFromQueue(items, idx) },
                                onToggleFavorite = { scope.launch { repo.toggleFavorite(song) } }
                            )
                        }
                    }
                }
            }
            1 -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search YouTube") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val q = query.trim()
                            if (q.isEmpty()) {
                                status = "Error: empty query"
                                return@Button
                            }
                            status = "Searching..."
                            scope.launch {
                                try {
                                    val found = YtdlpHelper.searchYouTube(context, q, 5)
                                    results = found
                                    status = "Found ${found.size} results"
                                } catch (e: Exception) {
                                    val msg = e.message ?: "unknown error"
                                    Log.e("YTDL", "Search failed: $msg")
                                    status = "Error: $msg"
                                    Toast.makeText(context, "Search failed: $msg", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text("Search")
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 8.dp)
                ) {
                    val items = results.map { QueueItem(it.videoId, it.title) }
                    itemsIndexed(results, key = { _, result -> result.videoId }) { idx, result ->
                        Text(
                            text = result.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { playFromQueue(items, idx) }
                                .padding(vertical = 12.dp)
                        )
                        HorizontalDivider()
                    }
                }
            }
            2 -> {
                if (favorites.isEmpty()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "No favorites yet — tap ☆ on a song to add one.",
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = 8.dp)
                    ) {
                        val items = favorites.map { QueueItem(it.videoId, it.title) }
                        itemsIndexed(favorites, key = { _, song -> song.videoId }) { idx, song ->
                            SongRow(
                                song = song,
                                onClick = { playFromQueue(items, idx) },
                                onToggleFavorite = { scope.launch { repo.toggleFavorite(song) } }
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    val c = controller
                    if (c == null) {
                        status = "Connecting..."
                        return@Button
                    }
                    c.setMediaItem(
                        MediaItem.Builder()
                            .setUri("android.resource://${context.packageName}/${R.raw.sample}")
                            .setMediaMetadata(
                                androidx.media3.common.MediaMetadata.Builder()
                                    .setTitle("Sample Track")
                                    .setArtist("Local")
                                    .build()
                            )
                            .build()
                    )
                    c.prepare()
                    c.play()
                    status = "Playing (local)"
                }
            ) {
                Text("Play")
            }
            Button(
                onClick = {
                    val c = controller
                    if (c == null) {
                        status = "Connecting..."
                        return@Button
                    }
                    c.stop()
                    isPlaying = false
                    status = "Stopped"
                },
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text("Stop")
            }
            Button(
                onClick = {
                    val c = controller
                    if (c == null) {
                        status = "Connecting..."
                        return@Button
                    }
                    status = "Extracting..."
                    scope.launch {
                        try {
                            val url = YtdlpHelper.extractAudioUrl(context, TEST_VIDEO_URL)
                            if (url != null) {
                                Log.d("YTDL", "URL: $url")
                                c.setMediaItem(
                                    MediaItem.Builder()
                                        .setUri(url)
                                        .setMediaMetadata(
                                            androidx.media3.common.MediaMetadata.Builder()
                                                .setTitle("Rick Astley - Never Gonna Give You Up")
                                                .setArtist("YouTube")
                                                .build()
                                        )
                                        .build()
                                )
                                c.prepare()
                                c.play()
                                status = "Playing"
                            } else {
                                val msg = "extraction returned null"
                                Log.e("YTDL", "Failed: $msg")
                                status = "Error: $msg"
                                Toast.makeText(context, "Extraction failed: $msg", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            val msg = e.message ?: "unknown error"
                            Log.e("YTDL", "Failed: $msg")
                            status = "Error: $msg"
                            Toast.makeText(context, "Extraction failed: $msg", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text("Play YT")
            }
            Text(
                text = status,
                modifier = Modifier.padding(top = 16.dp)
            )
        }

        if (nowPlayingTitle != null) {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = nowPlayingTitle ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = nowPlayingArtist ?: "",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                androidx.compose.material3.IconButton(onClick = { playPrev() }) {
                    Text("⏮", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                }
                androidx.compose.material3.IconButton(
                    onClick = {
                        val c = controller ?: return@IconButton
                        if (c.isPlaying) {
                            c.pause()
                            isPlaying = false
                        } else {
                            c.play()
                            isPlaying = true
                        }
                    }
                ) {
                    Text(
                        if (isPlaying) "⏸" else "▶",
                        style = androidx.compose.material3.MaterialTheme.typography.titleLarge
                    )
                }
                androidx.compose.material3.IconButton(onClick = { playNext() }) {
                    Text("⏭", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val playLabel = if (song.playCount == 1) "play" else "plays"
            Text(
                text = "${song.artist} · ${song.playCount} $playLabel",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall
            )
        }
        androidx.compose.material3.IconButton(onClick = onToggleFavorite) {
            Text(
                text = if (song.isFavorite) "★" else "☆",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge
            )
        }
    }
    HorizontalDivider()
}

@Preview(showBackground = true)
@Composable
fun PlayerScreenPreview() {
    MusicAppTheme {
        PlayerScreen()
    }
}
