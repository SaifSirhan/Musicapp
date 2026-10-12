package com.sharl.musicapp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.sharl.musicapp.data.Song
import com.sharl.musicapp.data.SongRepository
import com.sharl.musicapp.ui.theme.AccentOptions
import com.sharl.musicapp.ui.theme.DefaultAccent
import com.sharl.musicapp.ui.theme.MusicAppTheme
import kotlinx.coroutines.launch

private const val TEST_VIDEO_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

data class QueueItem(val videoId: String, val title: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val prefs = remember {
                context.getSharedPreferences("theme", Context.MODE_PRIVATE)
            }
            var accentIndex by remember {
                mutableStateOf(prefs.getInt("accent_index", 0))
            }
            val accent = AccentOptions.getOrNull(accentIndex)?.color ?: DefaultAccent

            MusicAppTheme(accent = accent) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PlayerScreen(
                        modifier = Modifier.padding(innerPadding),
                        accentIndex = accentIndex,
                        onAccentChange = { idx ->
                            accentIndex = idx
                            prefs.edit().putInt("accent_index", idx).apply()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    modifier: Modifier = Modifier,
    accentIndex: Int,
    onAccentChange: (Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { SongRepository(context) }
    val recent by repo.recentlyPlayed().collectAsState(initial = emptyList())
    val favorites by repo.favorites().collectAsState(initial = emptyList())
    val forYouRecent by repo.recentSongs(10).collectAsState(initial = emptyList())
    val forYouTop by repo.mostPlayed(10).collectAsState(initial = emptyList())
    val forYouFavorites by repo.favoriteSongs(10).collectAsState(initial = emptyList())
    val topArtistName by repo.topArtist().collectAsState(initial = null)
    val forYouByArtist by (topArtistName?.let {
        repo.songsByArtist(it, 10)
    } ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsState(initial = emptyList())
    var tab by remember { mutableStateOf(0) }  // 0 = Home, 1 = For You, 2 = Search, 3 = Favorites
    var showColorPicker by remember { mutableStateOf(false) }
    var menuSong by remember { mutableStateOf<Song?>(null) }

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

    var showNowPlaying by remember { mutableStateOf(false) }
    var isShuffled by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableStateOf(0) }  // 0=off, 1=all, 2=one

    val urlCache = remember { mutableStateMapOf<String, Pair<String, SongMetadata>>() }
    var pendingExtraction by remember { mutableStateOf<String?>(null) }

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

    suspend fun extractOrCache(videoId: String): Pair<String, SongMetadata>? {
        urlCache[videoId]?.let {
            urlCache.remove(videoId)
            return it
        }
        return YtdlpHelper.extractUrlAndMetadata(context, videoId)
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
        status = "Extracting..."
        scope.launch {
            try {
                val result = extractOrCache(item.videoId)
                if (result == null) {
                    status = "Error: extraction returned null"
                    return@launch
                }
                val (url, meta) = result
                repo.recordPlay(item.videoId, meta.title, meta.artist, meta.album)
                controller!!.setMediaItem(
                    MediaItem.Builder()
                        .setUri(url)
                        .setMediaMetadata(
                            androidx.media3.common.MediaMetadata.Builder()
                                .setTitle(meta.title)
                                .setArtist(meta.artist)
                                .setAlbumTitle(meta.album)
                                .build()
                        )
                        .build()
                )
                controller!!.prepare()
                controller!!.play()
                isPlaying = true
                nowPlayingTitle = meta.title
                nowPlayingArtist = meta.artist
                status = "Playing: ${meta.title}"

                val nextIndex = index + 1
                if (nextIndex in items.indices) {
                    val nextId = items[nextIndex].videoId
                    if (!urlCache.containsKey(nextId) && pendingExtraction != nextId) {
                        pendingExtraction = nextId
                        scope.launch {
                            try {
                                val pre = YtdlpHelper.extractUrlAndMetadata(context, nextId)
                                if (pre != null) {
                                    urlCache[nextId] = pre
                                    Log.d("PRECACHE", "cached: $nextId")
                                }
                            } catch (e: Exception) {
                                Log.w("PRECACHE", "pre-extract failed for $nextId", e)
                            } finally {
                                if (pendingExtraction == nextId) pendingExtraction = null
                            }
                        }
                    }
                }
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

    fun appendToQueue(song: Song) {
        val item = QueueItem(song.videoId, song.title)
        queue = queue + item
        status = "Added to queue: ${song.title}"
    }

    fun insertIntoQueueNext(song: Song) {
        val item = QueueItem(song.videoId, song.title)
        if (queueIndex < 0) {
            // Nothing playing — start it
            playFromQueue(listOf(item), 0)
            return
        }
        val insertAt = queueIndex + 1
        queue = queue.toMutableList().apply { add(insertAt, item) }
        status = "Playing next: ${song.title}"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (showNowPlaying && nowPlayingTitle != null) {
            androidx.activity.compose.BackHandler(enabled = true) {
                showNowPlaying = false
            }
            NowPlayingScreen(
                title = nowPlayingTitle ?: "",
                artist = nowPlayingArtist ?: "",
                isPlaying = isPlaying,
                isShuffled = isShuffled,
                repeatMode = repeatMode,
                onBack = { showNowPlaying = false },
                onPlayPause = {
                    val c = controller ?: return@NowPlayingScreen
                    if (c.isPlaying) {
                        c.pause()
                        isPlaying = false
                    } else {
                        c.play()
                        isPlaying = true
                    }
                },
                onPrev = { playPrev() },
                onNext = { playNext() },
                onShuffleToggle = {
                    isShuffled = !isShuffled
                    controller?.shuffleModeEnabled = isShuffled
                },
                onRepeatToggle = {
                    repeatMode = (repeatMode + 1) % 3
                    controller?.repeatMode = when (repeatMode) {
                        1 -> androidx.media3.common.Player.REPEAT_MODE_ALL
                        2 -> androidx.media3.common.Player.REPEAT_MODE_ONE
                        else -> androidx.media3.common.Player.REPEAT_MODE_OFF
                    }
                }
            )
        } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            androidx.compose.material3.IconButton(onClick = { showColorPicker = true }) {
                Text("⚙", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            }
        }

        androidx.compose.material3.TabRow(selectedTabIndex = tab) {
            androidx.compose.material3.Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text("Home") }
            )
            androidx.compose.material3.Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text("For You") }
            )
            androidx.compose.material3.Tab(
                selected = tab == 2,
                onClick = { tab = 2 },
                text = { Text("Search") }
            )
            androidx.compose.material3.Tab(
                selected = tab == 3,
                onClick = { tab = 3 },
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
                                onToggleFavorite = { scope.launch { repo.toggleFavorite(song) } },
                                onLongClick = { menuSong = song }
                            )
                        }
                    }
                }
            }
            1 -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 8.dp)
                ) {
                    item {
                        RecommendationRow(
                            title = "Recently Played",
                            songs = forYouRecent,
                            onSongClick = { song ->
                                playFromQueue(
                                    forYouRecent.map { QueueItem(it.videoId, it.title) },
                                    forYouRecent.indexOf(song)
                                )
                            }
                        )
                    }
                    item {
                        RecommendationRow(
                            title = "Most Played",
                            songs = forYouTop,
                            onSongClick = { song ->
                                playFromQueue(
                                    forYouTop.map { QueueItem(it.videoId, it.title) },
                                    forYouTop.indexOf(song)
                                )
                            }
                        )
                    }
                    item {
                        if (topArtistName != null) {
                            RecommendationRow(
                                title = "More from $topArtistName",
                                songs = forYouByArtist,
                                onSongClick = { song ->
                                    playFromQueue(
                                        forYouByArtist.map { QueueItem(it.videoId, it.title) },
                                        forYouByArtist.indexOf(song)
                                    )
                                }
                            )
                        }
                    }
                    item {
                        RecommendationRow(
                            title = "Your Favorites",
                            songs = forYouFavorites,
                            onSongClick = { song ->
                                playFromQueue(
                                    forYouFavorites.map { QueueItem(it.videoId, it.title) },
                                    forYouFavorites.indexOf(song)
                                )
                            }
                        )
                    }
                    if (forYouRecent.isEmpty() && forYouTop.isEmpty() && forYouFavorites.isEmpty()) {
                        item {
                            Text(
                                text = "Play some songs and they'll show up here.",
                                modifier = Modifier.padding(16.dp),
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            2 -> {
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
                                .combinedClickable(
                                    onClick = { playFromQueue(items, idx) },
                                    onLongClick = {
                                        menuSong = Song(
                                            videoId = result.videoId,
                                            title = result.title,
                                            artist = "YouTube",
                                            lastPlayedAt = 0L,
                                            playCount = 0,
                                            isFavorite = false,
                                            isHidden = false
                                        )
                                    }
                                )
                                .padding(vertical = 12.dp)
                        )
                        HorizontalDivider()
                    }
                }
            }
            3 -> {
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
                                onToggleFavorite = { scope.launch { repo.toggleFavorite(song) } },
                                onLongClick = { menuSong = song }
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
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showNowPlaying = true }
                ) {
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

        if (showColorPicker) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showColorPicker = false },
                title = { Text("Accent color") },
                text = {
                    Column {
                        AccentOptions.forEachIndexed { idx, option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onAccentChange(idx)
                                        showColorPicker = false
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(option.color)
                                )
                                androidx.compose.material3.Text(
                                    text = option.name,
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                                if (idx == accentIndex) {
                                    androidx.compose.material3.Text(
                                        text = "  ✓",
                                        modifier = Modifier.padding(start = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { showColorPicker = false }) {
                        Text("Close")
                    }
                }
            )
        }
        }

        val song = menuSong
        if (song != null) {
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { menuSong = null }
            ) {
                Column(modifier = Modifier.padding(bottom = 24.dp)) {
                    Text(
                        text = song.title,
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                    HorizontalDivider()

                    MenuItem("Play next") {
                        insertIntoQueueNext(song)
                        menuSong = null
                    }
                    MenuItem("Add to queue") {
                        appendToQueue(song)
                        menuSong = null
                    }
                    MenuItem(
                        if (song.isFavorite) "Remove from favorites" else "Add to favorites"
                    ) {
                        scope.launch { repo.toggleFavorite(song) }
                        menuSong = null
                    }
                    MenuItem("Not interested") {
                        scope.launch { repo.hide(song) }
                        menuSong = null
                    }
                }
            }
        }
    }
}

@Composable
fun MenuItem(label: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            label,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Start
        )
    }
}

@Composable
fun RecommendationRow(
    title: String,
    songs: List<Song>,
    onSongClick: (Song) -> Unit
) {
    if (songs.isEmpty()) return

    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 8.dp)
    ) {
        Text(
            text = title,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        androidx.compose.foundation.lazy.LazyRow {
            items(songs, key = { it.videoId }) { song ->
                Column(
                    modifier = Modifier
                        .width(140.dp)
                        .padding(horizontal = 4.dp)
                        .clickable { onSongClick(song) }
                ) {
                    Text(
                        text = song.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = song.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = buildString {
                append(song.artist)
                song.album?.let { append(" · $it") }
                append(" · ")
                append(song.playCount)
                append(" ")
                append(if (song.playCount == 1) "play" else "plays")
            }
            Text(
                text = subtitle,
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

@Composable
fun NowPlayingScreen(
    title: String,
    artist: String,
    isPlaying: Boolean,
    isShuffled: Boolean,
    repeatMode: Int,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        // Top bar: back button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.IconButton(onClick = onBack) {
                Text(
                    "←",
                    style = androidx.compose.material3.MaterialTheme.typography.headlineMedium
                )
            }
        }

        Spacer(modifier = Modifier.weight(0.6f))

        // Title and artist, centered
        Text(
            text = title,
            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = artist,
            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.weight(0.4f))

        // Shuffle + repeat row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.IconButton(onClick = onShuffleToggle) {
                Text(
                    "🔀",
                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                    color = if (isShuffled)
                        androidx.compose.material3.MaterialTheme.colorScheme.primary
                    else
                        androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.IconButton(onClick = onRepeatToggle) {
                val glyph = when (repeatMode) {
                    1 -> "🔁"
                    2 -> "🔂"
                    else -> "🔁"
                }
                Text(
                    glyph,
                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                    color = if (repeatMode == 0)
                        androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        androidx.compose.material3.MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.size(16.dp))

        // Big transport row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.IconButton(onClick = onPrev) {
                Text(
                    "⏮",
                    style = androidx.compose.material3.MaterialTheme.typography.displaySmall
                )
            }
            androidx.compose.material3.IconButton(onClick = onPlayPause) {
                Text(
                    if (isPlaying) "⏸" else "▶",
                    style = androidx.compose.material3.MaterialTheme.typography.displayMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                )
            }
            androidx.compose.material3.IconButton(onClick = onNext) {
                Text(
                    "⏭",
                    style = androidx.compose.material3.MaterialTheme.typography.displaySmall
                )
            }
        }

        Spacer(modifier = Modifier.weight(0.4f))
    }
}

@Preview(showBackground = true)
@Composable
fun PlayerScreenPreview() {
    MusicAppTheme {
        PlayerScreen(accentIndex = 0, onAccentChange = {})
    }
}
