package com.sharl.musicapp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.sharl.musicapp.data.AppDatabase
import com.sharl.musicapp.data.AuthRepository
import com.sharl.musicapp.data.Download
import com.sharl.musicapp.data.DownloadRepository
import com.sharl.musicapp.data.FriendsRepository
import com.sharl.musicapp.data.ImportPreview
import com.sharl.musicapp.data.ImportRepository
import com.sharl.musicapp.data.ImportSource
import com.sharl.musicapp.data.ImportState
import com.sharl.musicapp.data.ImportTrack
import com.sharl.musicapp.data.NewDrop
import com.sharl.musicapp.data.NewDropsRepository
import com.sharl.musicapp.data.NowPlaying
import com.sharl.musicapp.data.PlaybackCache
import com.sharl.musicapp.data.Playlist
import com.sharl.musicapp.data.PlaylistRepository
import com.sharl.musicapp.data.PlaylistSong
import com.sharl.musicapp.data.Profile
import com.sharl.musicapp.data.ProfileRepository
import com.sharl.musicapp.data.SettingsRepo
import com.sharl.musicapp.data.SharedPlaylistSong
import com.sharl.musicapp.data.Song
import com.sharl.musicapp.data.SongRepository
import com.sharl.musicapp.data.SpeedDialRepository
import com.sharl.musicapp.data.SpeedDialSlot
import com.sharl.musicapp.data.UpdateInfo
import com.sharl.musicapp.data.UpdateRepository
import com.sharl.musicapp.data.UpdateState
import java.io.File
import com.sharl.musicapp.ui.GoogleSignInButton
import com.sharl.musicapp.ui.theme.AccentOptions
import com.sharl.musicapp.ui.theme.DefaultAccent
import com.sharl.musicapp.ui.theme.MusicAppTheme
import kotlinx.coroutines.launch

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
            val snackbarHostState = remember { SnackbarHostState() }

            MusicAppTheme(accent = accent) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { innerPadding ->
                    PlayerScreen(
                        modifier = Modifier.padding(innerPadding),
                        accentIndex = accentIndex,
                        onAccentChange = { idx ->
                            accentIndex = idx
                            prefs.edit().putInt("accent_index", idx).apply()
                        },
                        snackbarHostState = snackbarHostState
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
    onAccentChange: (Int) -> Unit,
    snackbarHostState: SnackbarHostState = SnackbarHostState()
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
    var tab by remember { mutableStateOf(0) }  // 0 = Home, 1 = Search, 2 = Library, 3 = For You
    var libraryTab by remember { mutableStateOf(0) }  // 0 = Downloads, 1 = Playlists, 2 = Favorites
    var libraryLayout by remember { mutableStateOf(0) }  // 0 = List, 1 = Grid
    var showColorPicker by remember { mutableStateOf(false) }
    var menuSong by remember { mutableStateOf<Song?>(null) }

    var status by remember { mutableStateOf("Connecting...") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var localMatches by remember { mutableStateOf<List<Song>>(emptyList()) }

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var connected by remember { mutableStateOf(false) }

    var queue by remember { mutableStateOf<List<QueueItem>>(emptyList()) }
    var queueIndex by remember { mutableStateOf(-1) }
    var nowPlayingTitle by remember { mutableStateOf<String?>(null) }
    var nowPlayingArtist by remember { mutableStateOf<String?>(null) }
    var nowPlayingThumbnail by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    var showNowPlaying by remember { mutableStateOf(false) }
    var isShuffled by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableStateOf(0) }  // 0=off, 1=all, 2=one

    var lyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var lyricsLoading by remember { mutableStateOf(false) }
    var nowPlayingTab by remember { mutableStateOf(0) }  // inside Now Playing: 0 = Art, 1 = Lyrics, 2 = Queue
    var currentPosMs by remember { mutableStateOf(0L) }

    var currentDurationMs by remember { mutableStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableStateOf(0L) }
    var isVideoMode by remember { mutableStateOf(false) }
    var videoModeLoading by remember { mutableStateOf(false) }

    val urlCache = remember { mutableStateMapOf<String, Pair<String, SongMetadata>>() }
    var loadingVideoId by remember { mutableStateOf<String?>(null) }
    val extractMutex = remember { Mutex() }

    LaunchedEffect(Unit) { PlaybackCache.init(context) }

    val playlistRepo = remember { PlaylistRepository(context) }
    val playlists by playlistRepo.all().collectAsState(initial = emptyList())
    var showPlaylists by remember { mutableStateOf(false) }
    var openPlaylistId by remember { mutableStateOf<Long?>(null) }
    var showAddToPlaylist by remember { mutableStateOf<Song?>(null) }
    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var showRenamePlaylist by remember { mutableStateOf(false) }
    var renamePlaylistName by remember { mutableStateOf("") }
    var showDeletePlaylistConfirm by remember { mutableStateOf(false) }
    var playlistMenuSong by remember { mutableStateOf<PlaylistSong?>(null) }
    var playlistMenuPlaylistId by remember { mutableStateOf<Long?>(null) }

    val speedDialRepo = remember { SpeedDialRepository(context) }
    val speedDialSlots by speedDialRepo.allSlots().collectAsState(initial = emptyList())
    var speedDialMenuPosition by remember { mutableStateOf<Int?>(null) }
    var speedDialAssignPosition by remember { mutableStateOf<Int?>(null) }  // for song picker
    var speedDialPickerSource by remember { mutableStateOf<String?>(null) }  // "favorites" | "recent"

    val downloadRepo = remember { DownloadRepository(context) }
    val downloads by downloadRepo.all().collectAsState(initial = emptyList())
    val downloadIds by downloadRepo.allIds().collectAsState(initial = emptyList())
    val downloadStates by Downloader.states.collectAsState()
    val settings = remember { SettingsRepo(context) }
    var storageExternalState by remember { mutableStateOf(settings.storageExternal) }
    var showSettings by remember { mutableStateOf(false) }
    var playlistListMenu by remember { mutableStateOf<Playlist?>(null) }
    var playlistListSongCount by remember { mutableStateOf(0) }
    var playlistListDownloaded by remember { mutableStateOf(0) }
    var showRemoveAllDownloads by remember { mutableStateOf(false) }
    var showClearLibraryConfirm by remember { mutableStateOf(false) }
    var showPrivacyPolicy by remember { mutableStateOf(false) }
    var notifyUpdates by remember { mutableStateOf(settings.notifyUpdates) }
    var removalProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // --- In-app updater (Phase 15c). Never auto-downloads. ---
    val updateRepo = remember { UpdateRepository(context) }
    val updateState by updateRepo.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!BuildConfig.DEBUG) updateRepo.check()
    }
    androidx.compose.runtime.LaunchedEffect(updateState) {
        val s = updateState
        if (s is UpdateState.Available &&
            s.info.versionCode != settings.dismissUpdateForVersion &&
            settings.notifyUpdates
        ) {
            val result = snackbarHostState.showSnackbar(
                message = "Update available — tap to install",
                actionLabel = "Install"
            )
            if (result == SnackbarResult.ActionPerformed) {
                showSettings = true
            } else {
                settings.dismissUpdateForVersion = s.info.versionCode
            }
        }
    }

    val newDropsRepo = remember { NewDropsRepository(context) }
    val newDrops by newDropsRepo.drops.collectAsState()
    val newDropsLoading by newDropsRepo.loading.collectAsState()
    var showNewDropsAll by remember { mutableStateOf(false) }

    // seed artists: top-played (SongDao) ∪ favorites' artists
    val newDropsSeedArtists by remember {
        androidx.compose.runtime.derivedStateOf {
            val top = forYouTop.sortedByDescending { it.playCount }.take(10).map { it.artist }
            val favs = favorites.map { it.artist }
            (top + favs).map { it.trim() }.filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }.take(20)
        }
    }

    // Keyed on the seed list (not Unit) so a cold start still fires once Room's
    // flows have emitted the played/favorite artists.
    androidx.compose.runtime.LaunchedEffect(newDropsSeedArtists, newDropsLoading) {
        if (newDropsSeedArtists.isNotEmpty() && newDropsRepo.isStale() && !newDropsLoading) {
            newDropsRepo.refresh(context, newDropsSeedArtists)
        }
    }

    val importRepo = remember { ImportRepository(context) }
    val importState by importRepo.state.collectAsState()
    var showImport by remember { mutableStateOf(false) }

    // --- Sync with friends (Phase 14). Local-only when signed out. ---
    val authRepo = remember { AuthRepository() }
    val profileRepo = remember { ProfileRepository(authRepo) }
    val friendsRepo = remember { FriendsRepository(authRepo) }
    val session by authRepo.session.collectAsState(initial = null)
    val signedIn = session != null
    var showSignIn by remember { mutableStateOf(false) }
    var myProfile by remember { mutableStateOf<Profile?>(null) }
    var friendsList by remember { mutableStateOf<List<Profile>>(emptyList()) }
    var friendsLoading by remember { mutableStateOf(false) }
    var friendsError by remember { mutableStateOf<String?>(null) }
    var addFriendCode by remember { mutableStateOf("") }
    var addFriendStatus by remember { mutableStateOf<String?>(null) }
    var selectedFriend by remember { mutableStateOf<Profile?>(null) }
    var selectedFriendSongs by remember { mutableStateOf<List<SharedPlaylistSong>>(emptyList()) }
    var selectedFriendSongsLoading by remember { mutableStateOf(false) }
    var friendsNowPlaying by remember { mutableStateOf<List<NowPlaying>>(emptyList()) }
    var nowPlayingFriend by remember { mutableStateOf<NowPlaying?>(null) }
    var playlistListIsShared by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(signedIn) {
        if (signedIn) {
            friendsLoading = true
            friendsError = null
            runCatching { profileRepo.ensureProfile() }
                .onFailure { friendsError = it.message }
                .getOrNull()?.let { myProfile = it }
            runCatching { profileRepo.friends() }
                .onSuccess { friendsList = it }
                .onFailure { friendsError = it.message }
            friendsLoading = false
        } else {
            myProfile = null
            friendsList = emptyList()
            friendsNowPlaying = emptyList()
            selectedFriend = null
        }
    }

    // Realtime subscription for friends' now-playing. One channel for the whole
    // screen (the Home strip and Friends screen both read this state).
    androidx.compose.runtime.LaunchedEffect(signedIn, friendsList) {
        if (signedIn && friendsList.isNotEmpty()) {
            friendsRepo.observeFriendsNowPlaying(friendsList.map { it.id })
                .collect { friendsNowPlaying = it }
        } else {
            friendsNowPlaying = emptyList()
        }
    }

    // Debounced now-playing push. Uses the queue (not playFromQueue) so that
    // helper is untouched.
    androidx.compose.runtime.LaunchedEffect(signedIn, queueIndex, nowPlayingTitle) {
        if (signedIn && nowPlayingTitle != null) {
            kotlinx.coroutines.delay(5000)
            val vid = queue.getOrNull(queueIndex)?.videoId
            runCatching { friendsRepo.updateNowPlaying(vid, nowPlayingTitle, nowPlayingArtist) }
        }
    }

    androidx.compose.runtime.LaunchedEffect(forYouRecent) {
        if (forYouRecent.isNotEmpty()) {
            speedDialRepo.seedIfEmpty(forYouRecent)
        }
    }

    androidx.compose.runtime.LaunchedEffect(playlistListMenu, downloadIds) {
        val pl = playlistListMenu
        if (pl != null) {
            val songs = playlistRepo.songs(pl.id).first()
            playlistListSongCount = songs.size
            playlistListDownloaded = songs.count { downloadIds.contains(it.videoId) }
            playlistListIsShared = if (signedIn && authRepo.configured) {
                runCatching { friendsRepo.mySharedPlaylist()?.playlistName == pl.name }
                    .getOrDefault(false)
            } else false
        }
    }

    fun startDownload(
        videoId: String,
        title: String,
        artist: String,
        thumb: String?,
        durationMs: Long
    ) {
        scope.launch {
            try {
                Downloader.download(context, settings, videoId, title, artist, thumb, durationMs)
            } catch (e: Exception) {
                status = "Download failed: ${e.message}"
            }
        }
    }

    fun removeAllDownloads() {
        val items = downloads
        scope.launch {
            val showProgress = items.size > 5
            items.forEachIndexed { idx, d ->
                if (showProgress) removalProgress = (idx + 1) to items.size
                try {
                    Downloader.remove(context, d)
                } catch (e: Exception) {
                    Log.w("DOWNLOAD", "remove failed for ${d.videoId}", e)
                }
            }
            removalProgress = null
            snackbarHostState.showSnackbar("Downloads removed.")
        }
    }

    fun clearLibrary() {
        scope.launch {
            val db = AppDatabase.get(context)
            db.songDao().clearAll()
            db.playlistDao().clearAll()
            db.speedDialDao().clearAll()
            snackbarHostState.showSnackbar("Library cleared.")
        }
    }

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

    LaunchedEffect(showNowPlaying, nowPlayingTitle) {
        while (showNowPlaying && nowPlayingTitle != null) {
            controller?.let { c ->
                if (!isScrubbing) currentPosMs = c.currentPosition
                val d = c.duration
                currentDurationMs = if (d > 0) d else 0L
            }
            kotlinx.coroutines.delay(250)
        }
    }

    // Fire-and-forget background extraction into the cache. Never plays, never
    // blocks UI, swallows errors. Serialized against user-initiated extraction
    // via the shared mutex so only one yt-dlp process runs at a time.
    suspend fun preExtract(videoId: String) {
        if (videoId.isBlank()) return
        if (PlaybackCache.get(videoId) != null) return
        if (downloadRepo.get(videoId)?.let { java.io.File(it.filePath).exists() } == true) return
        extractMutex.withLock {
            if (PlaybackCache.get(videoId) != null) return@withLock
            try {
                val pre = YtdlpHelper.extractUrlAndMetadata(context, videoId)
                if (pre != null) {
                    val (u, m) = pre
                    PlaybackCache.put(videoId, u, m.title, m.artist, m.album, m.thumbnailUrl)
                    urlCache[videoId] = pre
                    Log.d("PRECACHE", "cached: $videoId")
                }
            } catch (e: Exception) {
                Log.w("PRECACHE", "pre-extract failed for $videoId", e)
            }
        }
    }

    // Warm the top of the For You rows when they compose (one per screen).
    androidx.compose.runtime.LaunchedEffect(
        forYouRecent.firstOrNull()?.videoId,
        forYouTop.firstOrNull()?.videoId
    ) {
        (forYouRecent.firstOrNull() ?: forYouTop.firstOrNull())?.videoId?.let { preExtract(it) }
    }

    suspend fun extractOrCache(videoId: String): Pair<String, SongMetadata>? {
        // Offline short-circuit: a downloaded song resolves to its local file,
        // no network / no yt-dlp re-extraction.
        downloadRepo.get(videoId)?.let { row ->
            val f = java.io.File(row.filePath)
            if (f.exists()) {
                return android.net.Uri.fromFile(f).toString() to SongMetadata(
                    title = row.title,
                    artist = row.artist,
                    album = null,
                    thumbnailUrl = row.thumbnailUrl
                )
            }
        }
        // 1. In-memory (this session).
        urlCache[videoId]?.let {
            urlCache.remove(videoId)
            return it
        }
        // 2. Persistent cache (survives restart).
        PlaybackCache.get(videoId)?.let { c ->
            return c.url to SongMetadata(
                title = c.title,
                artist = c.artist,
                album = c.album,
                thumbnailUrl = c.thumbnailUrl
            )
        }
        // 3. Cold extract — surface a loading state and serialize yt-dlp.
        loadingVideoId = videoId
        try {
            val result = extractMutex.withLock {
                YtdlpHelper.extractUrlAndMetadata(context, videoId)
            }
            if (result != null) {
                val (u, m) = result
                PlaybackCache.put(videoId, u, m.title, m.artist, m.album, m.thumbnailUrl)
                urlCache[videoId] = result
            }
            return result
        } finally {
            if (loadingVideoId == videoId) loadingVideoId = null
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
        status = "Extracting..."
        scope.launch {
            try {
                val result = extractOrCache(item.videoId)
                if (result == null) {
                    status = "Error: extraction returned null"
                    return@launch
                }
                val (url, meta) = result
                repo.recordPlay(item.videoId, meta.title, meta.artist, meta.album, meta.thumbnailUrl)
                controller!!.setMediaItem(
                    MediaItem.Builder()
                        .setUri(url)
                        .setMediaMetadata(
                            androidx.media3.common.MediaMetadata.Builder()
                                .setTitle(meta.title)
                                .setArtist(meta.artist)
                                .setAlbumTitle(meta.album)
                                .setArtworkUri(meta.thumbnailUrl?.let { android.net.Uri.parse(it) })
                                .build()
                        )
                        .build()
                )
                controller!!.prepare()
                controller!!.play()
                isPlaying = true
                nowPlayingTitle = meta.title
                nowPlayingArtist = meta.artist
                nowPlayingThumbnail = meta.thumbnailUrl
                status = "Playing: ${meta.title}"

                lyrics = emptyList()
                lyricsLoading = true
                isVideoMode = false
                val fetchTitle = meta.title
                val fetchArtist = meta.artist
                scope.launch {
                    val result = LyricsHelper.fetch(fetchArtist, fetchTitle)
                    // Only apply if still the same track
                    if (nowPlayingTitle == fetchTitle) {
                        lyrics = result ?: emptyList()
                        lyricsLoading = false
                    }
                }

                val nextIndex = index + 1
                // Pre-extract the next two queue items so skips/auto-advance are
                // instant. Fire-and-forget; one at a time via the shared mutex.
                listOfNotNull(items.getOrNull(nextIndex), items.getOrNull(index + 2))
                    .forEach { next ->
                        scope.launch { preExtract(next.videoId) }
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

    DisposableEffect(Unit) {
        QueueAdvanceSignaler.setListener {
            scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                playNext()
            }
        }
        onDispose { QueueAdvanceSignaler.setListener(null) }
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

    fun onSpeedDialTap(slot: SpeedDialSlot?, position: Int) {
        if (slot == null || slot.type == "empty") {
            speedDialMenuPosition = position
            return
        }
        when (slot.type) {
            "song" -> {
                slot.refId?.let { vid ->
                    playFromQueue(listOf(QueueItem(vid, slot.title ?: "")), 0)
                }
            }
            "playlist" -> {
                val pid = slot.refId?.toLongOrNull() ?: return
                scope.launch {
                    val songs = playlistRepo.songs(pid).first()
                    if (songs.isNotEmpty()) {
                        playFromQueue(songs.map { QueueItem(it.videoId, it.title) }, 0)
                    }
                }
            }
            "shuffle_fav" -> {
                val shuffled = favorites.shuffled()
                if (shuffled.isNotEmpty()) {
                    playFromQueue(shuffled.map { QueueItem(it.videoId, it.title) }, 0)
                }
            }
            "shuffle_recent" -> {
                val shuffled = forYouRecent.shuffled()
                if (shuffled.isNotEmpty()) {
                    playFromQueue(shuffled.map { QueueItem(it.videoId, it.title) }, 0)
                }
            }
        }
    }

    fun toggleVideoMode() {
        val c = controller ?: return
        if (queueIndex < 0) return
        val item = queue[queueIndex]
        val wasPlaying = c.isPlaying
        val pos = c.currentPosition
        val newMode = !isVideoMode
        videoModeLoading = true
        scope.launch {
            try {
                val meta = urlCache[item.videoId]?.second
                    ?: YtdlpHelper.extractUrlAndMetadata(context, item.videoId)?.second

                val url = if (newMode) {
                    YtdlpHelper.extractVideoUrl(context, item.videoId)
                } else {
                    urlCache[item.videoId]?.first
                        ?: YtdlpHelper.extractUrlAndMetadata(context, item.videoId)?.first
                }
                if (url == null) {
                    status = "Toggle failed: extraction returned null"
                    return@launch
                }

                val builder = androidx.media3.common.MediaItem.Builder().setUri(url)
                if (meta != null) {
                    builder.setMediaMetadata(
                        androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(meta.title)
                            .setArtist(meta.artist)
                            .setAlbumTitle(meta.album)
                            .apply {
                                meta.thumbnailUrl?.let {
                                    setArtworkUri(android.net.Uri.parse(it))
                                }
                            }
                            .build()
                    )
                }
                c.setMediaItem(builder.build())
                c.prepare()
                c.seekTo(pos)
                if (wasPlaying) c.play()
                isVideoMode = newMode
                status = if (newMode) "Video mode" else "Audio mode"
            } catch (e: Exception) {
                status = "Toggle failed: ${e.message}"
            } finally {
                videoModeLoading = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (showPlaylists) {
            androidx.activity.compose.BackHandler {
                if (openPlaylistId != null) openPlaylistId = null else showPlaylists = false
            }
            PlaylistsScreen(
                playlists = playlists,
                openPlaylistId = openPlaylistId,
                playlistRepo = playlistRepo,
                onBack = {
                    if (openPlaylistId != null) openPlaylistId = null else showPlaylists = false
                },
                onOpenPlaylist = { id -> openPlaylistId = id },
                onPlayFromPlaylist = { songs, index ->
                    playFromQueue(songs.map { QueueItem(it.videoId, it.title) }, index)
                },
                onCreatePlaylist = { showNewPlaylistDialog = true },
                playlistName = playlists.firstOrNull { it.id == openPlaylistId }?.name ?: "",
                playlistCreatedAt = playlists.firstOrNull { it.id == openPlaylistId }?.createdAt ?: 0L,
                onRequestRename = {
                    renamePlaylistName = playlists.firstOrNull { it.id == openPlaylistId }?.name ?: ""
                    showRenamePlaylist = true
                },
                onRequestDelete = { showDeletePlaylistConfirm = true },
                onSongMenu = { ps ->
                    playlistMenuSong = ps
                    playlistMenuPlaylistId = openPlaylistId
                },
                onPlaylistMenu = { pl -> playlistListMenu = pl },
                loadingVideoId = loadingVideoId,
                onPreExtract = { id -> scope.launch { preExtract(id) } }
            )
        } else if (showNowPlaying && nowPlayingTitle != null) {
            androidx.activity.compose.BackHandler(enabled = true) {
                showNowPlaying = false
            }
            NowPlayingScreen(
                title = nowPlayingTitle ?: "",
                artist = nowPlayingArtist ?: "",
                thumbnailUrl = nowPlayingThumbnail,
                isPlaying = isPlaying,
                isLoading = loadingVideoId != null && loadingVideoId == queue.getOrNull(queueIndex)?.videoId,
                isShuffled = isShuffled,
                repeatMode = repeatMode,
                lyrics = lyrics,
                lyricsLoading = lyricsLoading,
                currentPosMs = currentPosMs,
                currentDurationMs = currentDurationMs,
                isScrubbing = isScrubbing,
                scrubPositionMs = scrubPositionMs,
                onScrubStart = { isScrubbing = true; scrubPositionMs = currentPosMs },
                onScrubChange = { scrubPositionMs = it },
                onScrubEnd = {
                    controller?.seekTo(scrubPositionMs)
                    currentPosMs = scrubPositionMs
                    isScrubbing = false
                },
                isVideoMode = isVideoMode,
                videoModeLoading = videoModeLoading,
                onToggleVideoMode = { toggleVideoMode() },
                mediaController = controller,
                nowPlayingTab = nowPlayingTab,
                onNowPlayingTabChange = { nowPlayingTab = it },
                queue = queue,
                queueIndex = queueIndex,
                onQueueItemClick = { idx -> playFromQueue(queue, idx) },
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
        } else if (showPrivacyPolicy) {
            androidx.activity.compose.BackHandler(enabled = true) {
                showPrivacyPolicy = false
            }
            PrivacyPolicyScreen(onBack = { showPrivacyPolicy = false })
        } else if (showSettings) {
            androidx.activity.compose.BackHandler(enabled = true) {
                showSettings = false
            }
            SettingsScreen(
                storageExternal = storageExternalState,
                onStorageChange = { ext ->
                    storageExternalState = ext
                    settings.storageExternal = ext
                },
                downloadDirPath = settings.downloadDir(context).absolutePath,
                downloadCount = downloads.size,
                totalBytes = downloads.sumOf { it.fileSizeBytes },
                onRemoveAll = { showRemoveAllDownloads = true },
                onClearLibrary = { showClearLibraryConfirm = true },
                onViewPrivacyPolicy = { showPrivacyPolicy = true },
                notifyUpdates = notifyUpdates,
                onNotifyUpdatesChange = { v ->
                    notifyUpdates = v
                    settings.notifyUpdates = v
                },
                accentName = AccentOptions.getOrNull(accentIndex)?.name ?: "Default",
                accentColor = AccentOptions.getOrNull(accentIndex)?.color ?: DefaultAccent,
                onOpenAccentPicker = { showColorPicker = true },
                onBack = { showSettings = false },
                signedIn = signedIn,
                syncConfigured = authRepo.configured,
                accountEmail = authRepo.currentUserEmail(),
                inviteCode = myProfile?.inviteCode,
                onSignIn = { showSignIn = true },
                onSignOut = {
                    scope.launch { runCatching { authRepo.signOut() } }
                },
                onCopyInvite = {
                    val code = myProfile?.inviteCode
                    if (!code.isNullOrBlank()) {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Invite code", code))
                        addFriendStatus = "Invite code copied."
                    }
                },
                friends = friendsList,
                addFriendCode = addFriendCode,
                onAddFriendCodeChange = { addFriendCode = it; addFriendStatus = null },
                onAddFriend = {
                    scope.launch {
                        addFriendStatus = "Adding…"
                        profileRepo.addFriendByCode(addFriendCode)
                            .onSuccess {
                                addFriendStatus = "Friend added."
                                addFriendCode = ""
                                friendsList = runCatching { profileRepo.friends() }
                                    .getOrDefault(friendsList)
                            }
                            .onFailure { addFriendStatus = it.message ?: "Could not add friend." }
                    }
                },
                addFriendStatus = addFriendStatus,
                updateState = updateState,
                onCheckForUpdates = { scope.launch { updateRepo.check() } },
                onDownloadUpdate = { info -> scope.launch { updateRepo.download(info) } },
                onInstallUpdate = { file -> installUpdate(context, file) }
            )
        } else if (showNewDropsAll) {
            androidx.activity.compose.BackHandler(enabled = true) {
                showNewDropsAll = false
            }
            NewDropsScreen(
                drops = newDrops,
                loading = newDropsLoading,
                downloadIds = downloadIds,
                onBack = { showNewDropsAll = false },
                onRefresh = {
                    scope.launch {
                        if (newDropsSeedArtists.isNotEmpty()) {
                            newDropsRepo.refresh(context, newDropsSeedArtists)
                        }
                    }
                },
                onPlay = { drop -> playFromQueue(listOf(QueueItem(drop.videoId, drop.title)), 0) },
                onLongClick = { drop -> menuSong = drop.asSong() },
                onToggleFavorite = { drop ->
                    scope.launch { repo.toggleFavorite(drop.asSong()) }
                }
            )
        } else if (showImport) {
            androidx.activity.compose.BackHandler(enabled = true) {
                importRepo.reset()
                showImport = false
            }
            ImportScreen(
                state = importState,
                onSourceChosen = { src -> scope.launch { importRepo.preview(src) } },
                onConfirm = { preview -> scope.launch { importRepo.commit(preview) } },
                onOpenPlaylist = { playlistId ->
                    importRepo.reset()
                    showImport = false
                    tab = 2
                    libraryTab = 1
                    openPlaylistId = playlistId
                },
                onDismiss = {
                    importRepo.reset()
                    showImport = false
                }
            )
        } else if (showSignIn) {
            androidx.activity.compose.BackHandler(enabled = true) { showSignIn = false }
            SignInScreen(
                configured = authRepo.configured,
                externalError = friendsError,
                onSignIn = { idToken, nonce ->
                    friendsError = null
                    scope.launch {
                        runCatching { authRepo.signInWithGoogle(idToken, nonce) }
                            .onSuccess { showSignIn = false }
                            .onFailure { friendsError = it.message ?: "Sign-in failed." }
                    }
                },
                onBack = { showSignIn = false }
            )
        } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                    )
                    .clickable {
                        tab = 0
                        urlCache.clear()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "♪",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge
                )
            }
            Spacer(Modifier.width(12.dp))

            Text(
                text = when (tab) {
                    0 -> "Music"
                    1 -> "Search"
                    2 -> "Library"
                    3 -> "For You"
                    else -> "Music"
                },
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )

            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.IconButton(onClick = { showSettings = true }) {
                    Text("⚙", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                }
            }
        }

        when (tab) {
            0 -> {
                val quickPicks30 = androidx.compose.runtime.remember(forYouTop, forYouRecent) {
                    val combined = (forYouTop + forYouRecent).distinctBy { it.videoId }
                    combined.take(30)
                }
                androidx.compose.runtime.LaunchedEffect(quickPicks30.firstOrNull()?.videoId) {
                    quickPicks30.firstOrNull()?.videoId?.let { preExtract(it) }
                }
                val quickPickPages = (quickPicks30.size + 5) / 6
                LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = 8.dp)
                    ) {
                        item {
                            if (quickPicks30.isNotEmpty()) {
                                Text(
                                    "Quick picks",
                                    style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
                                )
                                val quickPagerState = androidx.compose.foundation.pager.rememberPagerState(
                                    pageCount = { quickPickPages }
                                )
                                androidx.compose.foundation.pager.HorizontalPager(
                                    state = quickPagerState,
                                    modifier = Modifier.fillMaxWidth()
                                ) { page ->
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        val start = page * 6
                                        val pageItems = quickPicks30.drop(start).take(6)
                                        pageItems.forEach { song ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        playFromQueue(
                                                            quickPicks30.map { QueueItem(it.videoId, it.title) },
                                                            quickPicks30.indexOf(song)
                                                        )
                                                    }
                                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Thumbnail(song.thumbnailUrl ?: youtubeThumb(song.videoId), 56.dp)
                                                Spacer(Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(song.title, maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface)
                                                    Text(
                                                        "${song.artist} · ${song.playCount} " +
                                                        if (song.playCount == 1) "play" else "plays",
                                                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                                if (downloadIds.contains(song.videoId)) {
                                                    Text(
                                                        "✓",
                                                        color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                                        modifier = Modifier.padding(end = 2.dp)
                                                    )
                                                }
                                                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                                    if (song.videoId == loadingVideoId) {
                                                        androidx.compose.material3.CircularProgressIndicator(
                                                            modifier = Modifier.size(16.dp),
                                                            strokeWidth = 2.dp,
                                                            color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                                                        )
                                                    } else {
                                                        IconButton(onClick = { menuSong = song }) {
                                                            Text("⋮", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    repeat(quickPickPages) { idx ->
                                        Box(
                                            modifier = Modifier
                                                .padding(horizontal = 3.dp)
                                                .size(if (idx == quickPagerState.currentPage) 8.dp else 6.dp)
                                                .background(
                                                    if (idx == quickPagerState.currentPage)
                                                        androidx.compose.material3.MaterialTheme.colorScheme.primary
                                                    else
                                                        androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                                    androidx.compose.foundation.shape.CircleShape
                                                )
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            if (signedIn && friendsNowPlaying.isNotEmpty()) {
                                FriendsStrip(
                                    items = friendsNowPlaying,
                                    friends = friendsList,
                                    onClick = { nowPlayingFriend = it }
                                )
                            }
                        }

                        item {
                            if (newDrops.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "New drops",
                                        style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (newDropsLoading) {
                                        androidx.compose.material3.CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                    androidx.compose.material3.TextButton(
                                        onClick = { showNewDropsAll = true }
                                    ) { Text("See all") }
                                }
                                HorizontalCarousel(
                                    items = newDrops.take(20),
                                    key = { it.videoId }
                                ) { drop ->
                                    NewDropCard(drop) {
                                        playFromQueue(listOf(QueueItem(drop.videoId, drop.title)), 0)
                                    }
                                }
                            }
                        }

                        item {
                            Text(
                                "Speed Dial",
                                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
                            )
                            val dialPagerState = androidx.compose.foundation.pager.rememberPagerState(
                                pageCount = { 5 }
                            )
                            androidx.compose.foundation.pager.HorizontalPager(
                                state = dialPagerState,
                                modifier = Modifier.fillMaxWidth()
                            ) { page ->
                                val startPos = page * 9
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                                ) {
                                    for (row in 0 until 3) {
                                        Row(modifier = Modifier.fillMaxWidth()) {
                                            for (col in 0 until 3) {
                                                val position = startPos + row * 3 + col
                                                val slot = speedDialSlots.firstOrNull { it.position == position }
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .aspectRatio(1f)
                                                        .padding(6.dp)
                                                ) {
                                                    SpeedDialTile(
                                                        slot = slot,
                                                        onClick = { onSpeedDialTap(slot, position) },
                                                        onLongClick = { speedDialMenuPosition = position },
                                                        showDownloaded = slot?.type == "song" &&
                                                            slot.refId != null &&
                                                            downloadIds.contains(slot.refId)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                repeat(5) { idx ->
                                    Box(
                                        modifier = Modifier
                                            .padding(horizontal = 3.dp)
                                            .size(if (idx == dialPagerState.currentPage) 8.dp else 6.dp)
                                            .background(
                                                if (idx == dialPagerState.currentPage)
                                                    androidx.compose.material3.MaterialTheme.colorScheme.primary
                                                else
                                                    androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                                androidx.compose.foundation.shape.CircleShape
                                            )
                                    )
                                }
                            }
                        }

                        item {
                            HorizontalCarousel(
                                title = "Recently played",
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
                            if (topArtistName != null && forYouByArtist.isNotEmpty()) {
                                HorizontalCarousel(
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
                            HorizontalCarousel(
                                title = "Your favorites",
                                songs = forYouFavorites,
                                onSongClick = { song ->
                                    playFromQueue(
                                        forYouFavorites.map { QueueItem(it.videoId, it.title) },
                                        forYouFavorites.indexOf(song)
                                    )
                                }
                            )
                        }

                        item { Spacer(Modifier.height(16.dp)) }
                    }
                }
            3 -> {
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
            1 -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search songs, artists…") },
                        singleLine = true,
                        leadingIcon = {
                            Text("🔍",
                                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(start = 12.dp))
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
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
                        results = emptyList()
                        isSearching = false
                        return@LaunchedEffect
                    }
                    if (trimmed.length < 2) return@LaunchedEffect
                    kotlinx.coroutines.delay(400)
                    isSearching = true
                    try {
                        val t0 = android.os.SystemClock.elapsedRealtime()
                        val found = YtdlpHelper.searchYouTube(context, trimmed)
                        Log.d("YTDL", "search '${trimmed}' took ${android.os.SystemClock.elapsedRealtime() - t0}ms")
                        if (query.trim() == trimmed) {
                            results = found
                            // Warm the first result so an immediate tap is instant.
                            found.firstOrNull()?.videoId?.let { scope.launch { preExtract(it) } }
                        }
                    } catch (e: Exception) {
                        Log.e("YTDL", "live search failed: ${e.message}")
                    } finally {
                        if (query.trim() == trimmed) isSearching = false
                    }
                }

                LaunchedEffect(query) {
                    val q = query.trim()
                    if (q.isBlank()) {
                        localMatches = emptyList()
                        return@LaunchedEffect
                    }
                    localMatches = repo.searchLocal(q)
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
                                    playFromQueue(
                                        localMatches.map { QueueItem(it.videoId, it.title) },
                                        localMatches.indexOf(song)
                                    )
                                },
                                onToggleFavorite = { scope.launch { repo.toggleFavorite(song) } },
                                onLongClick = { menuSong = song },
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
                                    onClick = { playFromQueue(items, idx) },
                                    onLongClick = {
                                        menuSong = Song(
                                            videoId = result.videoId,
                                            title = result.title,
                                            artist = "YouTube",
                                            thumbnailUrl = youtubeThumb(result.videoId),
                                            lastPlayedAt = 0L,
                                            playCount = 0,
                                            isFavorite = false,
                                            isHidden = false
                                        )
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
            2 -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Only the chip strip scrolls; the trailing icons stay anchored
                        // to the end so they're always reachable on narrow screens.
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = libraryTab == 0,
                                onClick = { libraryTab = 0 },
                                label = { Text("Downloads", maxLines = 1, softWrap = false) }
                            )
                            Spacer(Modifier.width(8.dp))
                            FilterChip(
                                selected = libraryTab == 1,
                                onClick = { libraryTab = 1 },
                                label = { Text("Playlists", maxLines = 1, softWrap = false) }
                            )
                            Spacer(Modifier.width(8.dp))
                            FilterChip(
                                selected = libraryTab == 2,
                                onClick = { libraryTab = 2 },
                                label = { Text("Favorites", maxLines = 1, softWrap = false) }
                            )
                            Spacer(Modifier.width(8.dp))
                            FilterChip(
                                selected = libraryTab == 3,
                                onClick = { libraryTab = 3 },
                                label = { Text("Friends", maxLines = 1, softWrap = false) }
                            )
                        }
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            IconButton(onClick = { importRepo.reset(); showImport = true }) {
                                Text(
                                    "⤓",
                                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            IconButton(onClick = { libraryLayout = if (libraryLayout == 0) 1 else 0 }) {
                                Text(
                                    if (libraryLayout == 0) "▦" else "☰",
                                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    when (libraryTab) {
                        0 -> Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            DownloadsScreen(
                                downloads = downloads,
                                downloadStates = downloadStates,
                                onPlay = { index ->
                                    playFromQueue(
                                        downloads.map { QueueItem(it.videoId, it.title) },
                                        index
                                    )
                                },
                                onRemove = { d ->
                                    scope.launch { Downloader.remove(context, d) }
                                },
                                onRemoveAll = { showRemoveAllDownloads = true },
                                onFindMusic = { tab = 1 }
                            )
                        }
                        1 -> Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            PlaylistsScreen(
                                playlists = playlists,
                                openPlaylistId = openPlaylistId,
                                playlistRepo = playlistRepo,
                                onBack = { openPlaylistId = null },
                                onOpenPlaylist = { id -> openPlaylistId = id },
                                onPlayFromPlaylist = { songs, index ->
                                    playFromQueue(songs.map { QueueItem(it.videoId, it.title) }, index)
                                },
                                onCreatePlaylist = { showNewPlaylistDialog = true },
                                playlistName = playlists.firstOrNull { it.id == openPlaylistId }?.name ?: "",
                                playlistCreatedAt = playlists.firstOrNull { it.id == openPlaylistId }?.createdAt ?: 0L,
                                onRequestRename = {
                                    renamePlaylistName = playlists.firstOrNull { it.id == openPlaylistId }?.name ?: ""
                                    showRenamePlaylist = true
                                },
                                onRequestDelete = { showDeletePlaylistConfirm = true },
                                onSongMenu = { ps ->
                                    playlistMenuSong = ps
                                    playlistMenuPlaylistId = openPlaylistId
                                },
                                onPlaylistMenu = { pl -> playlistListMenu = pl },
                                loadingVideoId = loadingVideoId,
                                onPreExtract = { id -> scope.launch { preExtract(id) } }
                            )
                        }
                        2 -> {
                            if (favorites.isEmpty()) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "No favorites yet — tap ☆ on a song to add one.",
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                            } else if (libraryLayout == 0) {
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
                                            onLongClick = { menuSong = song },
                                            showDownloaded = downloadIds.contains(song.videoId),
                                            isLoading = song.videoId == loadingVideoId
                                        )
                                    }
                                }
                            } else {
                                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)
                                ) {
                                    items(favorites, key = { it.videoId }) { song ->
                                        Column(
                                            modifier = Modifier
                                                .padding(8.dp)
                                                .clickable {
                                                    playFromQueue(
                                                        favorites.map { QueueItem(it.videoId, it.title) },
                                                        favorites.indexOf(song)
                                                    )
                                                }
                                        ) {
                                            Thumbnail(
                                                song.thumbnailUrl ?: youtubeThumb(song.videoId),
                                                160.dp
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            Text(
                                                song.title,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                song.artist,
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
                        3 -> Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            FriendsScreen(
                                configured = authRepo.configured,
                                signedIn = signedIn,
                                friends = friendsList,
                                loading = friendsLoading,
                                error = friendsError,
                                selectedFriend = selectedFriend,
                                friendSongs = selectedFriendSongs,
                                friendSongsLoading = selectedFriendSongsLoading,
                                nowPlaying = friendsNowPlaying,
                                onBack = { selectedFriend = null },
                                onBackToList = { selectedFriend = null },
                                onOpenFriend = { f ->
                                    selectedFriend = f
                                    selectedFriendSongs = emptyList()
                                    selectedFriendSongsLoading = true
                                    scope.launch {
                                        selectedFriendSongs = runCatching {
                                            val shared = friendsRepo.friendsPlaylists(listOf(f.id)).firstOrNull()
                                            if (shared?.id != null) friendsRepo.songsOf(shared.id) else emptyList()
                                        }.getOrDefault(emptyList())
                                        selectedFriendSongsLoading = false
                                    }
                                },
                                onPlayFriendSongs = { idx ->
                                    playFromQueue(
                                        selectedFriendSongs.map { QueueItem(it.videoId, it.title ?: "") },
                                        idx
                                    )
                                },
                                onSignIn = { showSignIn = true }
                            )
                        }
                    }
                }
            }
        }

        if (nowPlayingTitle != null) {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                ) {
                    Thumbnail(nowPlayingThumbnail, 56.dp)
                }
                Spacer(Modifier.width(8.dp))
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
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { playPrev() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("⏮", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable {
                            val c = controller ?: return@clickable
                            if (c.isPlaying) {
                                c.pause()
                                isPlaying = false
                            } else {
                                c.play()
                                isPlaying = true
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val miniLoading = loadingVideoId != null && loadingVideoId == queue.getOrNull(queueIndex)?.videoId
                    if (miniLoading) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            if (isPlaying) "⏸" else "▶",
                            style = androidx.compose.material3.MaterialTheme.typography.titleLarge
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { playNext() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("⏭", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                }
            }
            val progress = if (currentDurationMs > 0)
                (currentPosMs.toFloat() / currentDurationMs.toFloat()).coerceIn(0f, 1f)
            else 0f
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                trackColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
                drawStopIndicator = {}
            )
        }

        NavigationBar(
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
            tonalElevation = 0.dp
        ) {
            val navItems = listOf(
                Triple(0, "Home", "🏠"),
                Triple(1, "Search", "🔍"),
                Triple(2, "Library", "📚"),
                Triple(3, "For You", "✨")
            )
            navItems.forEach { (idx, label, glyph) ->
                NavigationBarItem(
                    selected = tab == idx,
                    onClick = { tab = idx },
                    icon = {
                        Text(
                            glyph,
                            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                            color = if (tab == idx)
                                androidx.compose.material3.MaterialTheme.colorScheme.primary
                            else
                                androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    label = {
                        Text(
                            label,
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                            color = if (tab == idx)
                                androidx.compose.material3.MaterialTheme.colorScheme.primary
                            else
                                androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                        indicatorColor = androidx.compose.material3.MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        selectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        selectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        unselectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
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
                    MenuItem("Add to playlist") {
                        showAddToPlaylist = song
                        menuSong = null
                    }
                    MenuItem(
                        if (song.isFavorite) "Remove from favorites" else "Add to favorites"
                    ) {
                        scope.launch { repo.toggleFavorite(song) }
                        menuSong = null
                    }
                    val songDlState = downloadStates[song.videoId]
                    when {
                        songDlState is DownloadState.Downloading ->
                            MenuItem("Downloading… ${songDlState.percent}%") { }
                        songDlState is DownloadState.Queued ->
                            MenuItem("Queued…") { }
                        downloadIds.contains(song.videoId) ->
                            MenuItem("Remove download") {
                                scope.launch {
                                    downloadRepo.get(song.videoId)?.let {
                                        Downloader.remove(context, it)
                                    }
                                }
                                menuSong = null
                            }
                        songDlState is DownloadState.Failed ->
                            MenuItem("Retry download") {
                                startDownload(
                                    song.videoId, song.title, song.artist,
                                    song.thumbnailUrl, 0L
                                )
                                menuSong = null
                            }
                        else ->
                            MenuItem("Download") {
                                startDownload(
                                    song.videoId, song.title, song.artist,
                                    song.thumbnailUrl, 0L
                                )
                                menuSong = null
                            }
                    }
                    MenuItem("Not interested") {
                        scope.launch { repo.hide(song) }
                        menuSong = null
                    }
                }
            }
        }

        nowPlayingFriend?.let { np ->
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { nowPlayingFriend = null }
            ) {
                Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
                    val who = friendsList.firstOrNull { it.id == np.userId }
                    Text(
                        "Last seen ${formatRelative(np.updatedAt)}",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        who?.displayName ?: who?.email ?: "A friend",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Thumbnail(np.videoId?.let { youtubeThumb(it) }, 48.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(np.title ?: "", maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                np.artist ?: "",
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        if (showNewPlaylistDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showNewPlaylistDialog = false; newPlaylistName = "" },
                title = { Text("New playlist") },
                text = {
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        label = { Text("Name") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            val name = newPlaylistName.trim()
                            if (name.isNotBlank()) {
                                scope.launch { playlistRepo.create(name) }
                                newPlaylistName = ""
                                showNewPlaylistDialog = false
                            }
                        }
                    ) { Text("Create") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { showNewPlaylistDialog = false; newPlaylistName = "" }
                    ) { Text("Cancel") }
                }
            )
        }

        val addSong = showAddToPlaylist
        if (addSong != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showAddToPlaylist = null },
                title = { Text("Add to playlist") },
                text = {
                    Column {
                        if (playlists.isEmpty()) {
                            Text("No playlists yet. Create one first.")
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                                items(playlists, key = { it.id }) { pl ->
                                    androidx.compose.material3.TextButton(
                                        onClick = {
                                            scope.launch {
                                                playlistRepo.addSong(
                                                    pl.id,
                                                    addSong.videoId,
                                                    addSong.title,
                                                    addSong.artist,
                                                    addSong.thumbnailUrl
                                                )
                                            }
                                            showAddToPlaylist = null
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(pl.name, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        showAddToPlaylist = null
                        showPlaylists = true
                    }) { Text("Manage playlists") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { showAddToPlaylist = null }
                    ) { Text("Cancel") }
                }
            )
        }

        if (showRenamePlaylist && openPlaylistId != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showRenamePlaylist = false },
                title = { Text("Rename playlist") },
                text = {
                    OutlinedTextField(
                        value = renamePlaylistName,
                        onValueChange = { renamePlaylistName = it },
                        label = { Text("Name") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        val name = renamePlaylistName.trim()
                        val id = openPlaylistId
                        if (name.isNotBlank() && id != null) {
                            scope.launch { playlistRepo.rename(id, name) }
                        }
                        showRenamePlaylist = false
                    }) { Text("Save") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { showRenamePlaylist = false }
                    ) { Text("Cancel") }
                }
            )
        }

        if (showDeletePlaylistConfirm && openPlaylistId != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showDeletePlaylistConfirm = false },
                title = { Text("Delete playlist?") },
                text = { Text("This cannot be undone.") },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        val id = openPlaylistId
                        if (id != null) {
                            scope.launch { playlistRepo.delete(id) }
                        }
                        openPlaylistId = null
                        showDeletePlaylistConfirm = false
                    }) { Text("Delete") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { showDeletePlaylistConfirm = false }
                    ) { Text("Cancel") }
                }
            )
        }

        val ps = playlistMenuSong
        if (ps != null && playlistMenuPlaylistId != null) {
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { playlistMenuSong = null }
            ) {
                Column(modifier = Modifier.padding(bottom = 24.dp)) {
                    Text(
                        text = ps.title,
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                    HorizontalDivider()

                    MenuItem("Play next") {
                        insertIntoQueueNext(
                            Song(
                                videoId = ps.videoId,
                                title = ps.title,
                                artist = ps.artist,
                                thumbnailUrl = ps.thumbnailUrl ?: youtubeThumb(ps.videoId),
                                lastPlayedAt = 0L,
                                playCount = 0
                            )
                        )
                        playlistMenuSong = null
                    }
                    MenuItem("Add to queue") {
                        appendToQueue(
                            Song(
                                videoId = ps.videoId,
                                title = ps.title,
                                artist = ps.artist,
                                thumbnailUrl = ps.thumbnailUrl ?: youtubeThumb(ps.videoId),
                                lastPlayedAt = 0L,
                                playCount = 0
                            )
                        )
                        playlistMenuSong = null
                    }
                    MenuItem("Remove from playlist") {
                        val pid = playlistMenuPlaylistId!!
                        scope.launch { playlistRepo.removeSong(pid, ps.videoId) }
                        playlistMenuSong = null
                    }
                }
            }
        }

        val sdPos = speedDialMenuPosition
        if (sdPos != null) {
            val existing = speedDialSlots.firstOrNull { it.position == sdPos }
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { speedDialMenuPosition = null }
            ) {
                Column(modifier = Modifier.padding(bottom = 24.dp)) {
                    Text(
                        "Slot ${sdPos + 1}",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                    HorizontalDivider()

                    MenuItem("Shuffle Favorites") {
                        scope.launch { speedDialRepo.assignShuffleFavorites(sdPos) }
                        speedDialMenuPosition = null
                    }
                    MenuItem("Shuffle Recently Played") {
                        scope.launch { speedDialRepo.assignShuffleRecent(sdPos) }
                        speedDialMenuPosition = null
                    }
                    MenuItem("Choose from Favorites") {
                        speedDialPickerSource = "favorites"
                        speedDialAssignPosition = sdPos
                        speedDialMenuPosition = null
                    }
                    MenuItem("Choose from Recently Played") {
                        speedDialPickerSource = "recent"
                        speedDialAssignPosition = sdPos
                        speedDialMenuPosition = null
                    }
                    if (existing != null && existing.type != "empty") {
                        MenuItem("Remove") {
                            scope.launch { speedDialRepo.clear(sdPos) }
                            speedDialMenuPosition = null
                        }
                    }
                }
            }
        }

        val assignPos = speedDialAssignPosition
        if (assignPos != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {
                    speedDialAssignPosition = null
                    speedDialPickerSource = null
                },
                title = { Text("Pick a song") },
                text = {
                    val choices = when (speedDialPickerSource) {
                        "favorites" -> favorites.take(20)
                        "recent" -> forYouRecent.take(20)
                        else -> (forYouTop + forYouRecent).distinctBy { it.videoId }.take(20)
                    }
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp)
                    ) {
                        items(choices, key = { it.videoId }) { song ->
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    scope.launch {
                                        speedDialRepo.assignSong(
                                            assignPos,
                                            song.videoId,
                                            song.title,
                                            song.thumbnailUrl ?: youtubeThumb(song.videoId)
                                        )
                                    }
                                    speedDialAssignPosition = null
                                    speedDialPickerSource = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(song.title, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        speedDialAssignPosition = null
                        speedDialPickerSource = null
                    }) { Text("Cancel") }
                }
            )
        }

        val plMenu = playlistListMenu
        if (plMenu != null) {
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { playlistListMenu = null }
            ) {
                Column(modifier = Modifier.padding(bottom = 24.dp)) {
                    Text(
                        plMenu.name,
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                    HorizontalDivider()

                    MenuItem("Download all ($playlistListSongCount)") {
                        val pid = plMenu.id
                        scope.launch {
                            try {
                                val songs = playlistRepo.songs(pid).first()
                                songs.filterNot { downloadIds.contains(it.videoId) }
                                    .forEach { ps ->
                                        try {
                                            Downloader.download(
                                                context, settings, ps.videoId, ps.title,
                                                ps.artist, ps.thumbnailUrl, 0L
                                            )
                                        } catch (e: Exception) {
                                            Log.w("DOWNLOAD", "playlist download failed ${ps.videoId}", e)
                                        }
                                    }
                            } catch (e: Exception) {
                                status = "Download all failed: ${e.message}"
                            }
                        }
                        playlistListMenu = null
                    }
                    if (playlistListDownloaded > 0) {
                        MenuItem("Remove downloads ($playlistListDownloaded of $playlistListSongCount)") {
                            val pid = plMenu.id
                            scope.launch {
                                val songs = playlistRepo.songs(pid).first()
                                songs.filter { downloadIds.contains(it.videoId) }
                                    .forEach { ps ->
                                        downloadRepo.get(ps.videoId)?.let {
                                            Downloader.remove(context, it)
                                        }
                                    }
                            }
                            playlistListMenu = null
                        }
                    }
                    if (signedIn) {
                        if (playlistListIsShared) {
                            MenuItem("Stop sharing") {
                                scope.launch { runCatching { friendsRepo.stopSharing() } }
                                playlistListIsShared = false
                                playlistListMenu = null
                            }
                        } else {
                            MenuItem(
                                "Share with friends",
                                subtitle = "Snapshot — re-share to update."
                            ) {
                                val pid = plMenu.id
                                val pname = plMenu.name
                                scope.launch {
                                    runCatching { friendsRepo.sharePlaylist(context, pid, pname) }
                                        .onSuccess { playlistListIsShared = true }
                                        .onFailure { friendsError = it.message }
                                }
                                playlistListMenu = null
                            }
                        }
                    }
                }
            }
        }

        if (showClearLibraryConfirm) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showClearLibraryConfirm = false },
                title = { Text("Clear library?") },
                text = {
                    Text(
                        "This removes all songs, playlists, favorites, speed dial slots, " +
                            "and play history from MusicApp.\n\n" +
                            "Downloaded audio files are NOT removed — use \"Remove all downloads\" " +
                            "for that.\n\n" +
                            "This cannot be undone."
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        clearLibrary()
                        showClearLibraryConfirm = false
                    }) {
                        Text(
                            "Clear",
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showClearLibraryConfirm = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showRemoveAllDownloads) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showRemoveAllDownloads = false },
                title = { Text("Remove all downloads?") },
                text = {
                    Text(
                        "This deletes every downloaded song from device storage.\n\n" +
                            "Your library, playlists, and favorites stay intact — only the " +
                            "offline audio files are removed.\n\n" +
                            "You can re-download songs later."
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        removeAllDownloads()
                        showRemoveAllDownloads = false
                    }) {
                        Text(
                            "Remove",
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showRemoveAllDownloads = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        removalProgress?.let { (done, total) ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {},
                title = { Text("Removing downloads") },
                text = { Text("Removing $done of $total…") },
                confirmButton = {}
            )
        }
    }
}

@Composable
fun MenuItem(label: String, subtitle: String? = null, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 4.dp)) {
            Text(
                label,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Start
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
                    Thumbnail(song.thumbnailUrl ?: youtubeThumb(song.videoId), size = 132.dp)
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
fun SpeedDialTile(
    slot: com.sharl.musicapp.data.SpeedDialSlot?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    showDownloaded: Boolean = false
) {
    val bg = if (slot == null || slot.type == "empty")
        androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    else
        androidx.compose.material3.MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        when (slot?.type) {
            "song" -> {
                if (slot.thumbnailUrl != null || slot.refId != null) {
                    Thumbnail(
                        slot.thumbnailUrl ?: youtubeThumb(slot.refId ?: ""),
                        200.dp  // clipped by parent
                    )
                }
                // Overlay title at the bottom
                Column(
                    modifier = Modifier.fillMaxSize().padding(6.dp),
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Text(
                        slot.title ?: "",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            "playlist" -> {
                Text(
                    slot.title ?: "Playlist",
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(6.dp)
                )
            }
            "shuffle_fav" -> {
                Text(
                    "🔀 Favorites",
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            "shuffle_recent" -> {
                Text(
                    "🔀 Recent",
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            else -> {
                Text(
                    "+",
                    style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (showDownloaded) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            ) {
                Text(
                    "✓",
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun <T> HorizontalCarousel(
    items: List<T>,
    key: (T) -> Any,
    content: @Composable (T) -> Unit
) {
    if (items.isEmpty()) return
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)
    ) {
        this.items(items, key = key) { item -> content(item) }
    }
}

@Composable
fun NewDropCard(drop: NewDrop, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .padding(horizontal = 4.dp)
            .clickable { onClick() }
    ) {
        Box(
            Modifier
                .size(140.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
        ) {
            Thumbnail(drop.thumbnailUrl ?: youtubeThumb(drop.videoId), 140.dp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            drop.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
        )
        Text(
            "New from ${drop.artist}",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// Transient Song used only to reuse SongRow / the song menu for a not-yet-played drop.
fun NewDrop.asSong(): Song = Song(
    videoId = videoId,
    title = title,
    artist = artist,
    thumbnailUrl = thumbnailUrl ?: youtubeThumb(videoId),
    lastPlayedAt = 0L,
    playCount = 0,
    isFavorite = false,
    isHidden = false
)

@Composable
fun HorizontalCarousel(
    title: String,
    songs: List<Song>,
    onSongClick: (Song) -> Unit
) {
    if (songs.isEmpty()) return
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 12.dp)) {
        Text(
            title,
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, bottom = 12.dp)
        )
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)
        ) {
            items(songs, key = { it.videoId }) { song ->
                Column(
                    modifier = Modifier
                        .width(160.dp)
                        .padding(end = 12.dp)
                        .clickable { onSongClick(song) }
                ) {
                    Thumbnail(song.thumbnailUrl ?: youtubeThumb(song.videoId), 160.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        song.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        song.artist,
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
fun Thumbnail(url: String?, size: androidx.compose.ui.unit.Dp) {
    if (url == null) {
        Box(
            modifier = Modifier
                .size(size)
                .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant)
        )
        return
    }
    coil.compose.AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant)
    )
}

@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onLongClick: () -> Unit,
    showDownloaded: Boolean = false,
    isLoading: Boolean = false
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
        Thumbnail(song.thumbnailUrl ?: youtubeThumb(song.videoId), 48.dp)
        Spacer(Modifier.width(8.dp))
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
        if (showDownloaded) {
            Text(
                text = "✓",
                color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        androidx.compose.material3.IconButton(onClick = onToggleFavorite) {
            Text(
                text = if (song.isFavorite) "★" else "☆",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge
            )
        }
        if (isLoading) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                )
            }
        }
    }
    HorizontalDivider()
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0L)
    return "%d:%02d".format(s / 60, s % 60)
}

fun youtubeThumb(videoId: String): String =
    "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

fun formatDate(epochMs: Long): String {
    val sdf = java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(epochMs))
}

// Parses a PostgREST timestamptz string (e.g. "2026-10-13T10:31:00.123456+00:00").
fun parseIsoMs(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return try {
        val noFrac = iso.replace(Regex("\\.\\d+"), "")
        val normalized = noFrac
            .replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
            .replace("Z", "+0000")
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.getDefault())
            .parse(normalized)?.time
    } catch (_: Exception) {
        null
    }
}

fun formatRelative(iso: String?): String {
    val ms = parseIsoMs(iso) ?: return "just now"
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000} min ago"
        diff < 86_400_000 -> "${diff / 3_600_000} h ago"
        else -> "${diff / 86_400_000} d ago"
    }
}

@Composable
fun thumbCell(url: String?, modifier: Modifier) {
    if (url == null) {
        Box(modifier.background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant))
    } else {
        coil.compose.AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = modifier
        )
    }
}

@Composable
fun NowPlayingScreen(
    title: String,
    artist: String,
    thumbnailUrl: String?,
    isPlaying: Boolean,
    isLoading: Boolean,
    isShuffled: Boolean,
    repeatMode: Int,
    lyrics: List<LyricLine>,
    lyricsLoading: Boolean,
    currentPosMs: Long,
    currentDurationMs: Long,
    isScrubbing: Boolean,
    scrubPositionMs: Long,
    onScrubStart: () -> Unit,
    onScrubChange: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    isVideoMode: Boolean,
    videoModeLoading: Boolean,
    onToggleVideoMode: () -> Unit,
    mediaController: MediaController?,
    nowPlayingTab: Int,
    onNowPlayingTabChange: (Int) -> Unit,
    queue: List<QueueItem>,
    queueIndex: Int,
    onQueueItemClick: (Int) -> Unit,
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
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.IconButton(onClick = onBack) {
                    Text(
                        "←",
                        style = androidx.compose.material3.MaterialTheme.typography.headlineMedium
                    )
                }
            }
        }

        androidx.compose.material3.TabRow(selectedTabIndex = nowPlayingTab) {
            androidx.compose.material3.Tab(
                selected = nowPlayingTab == 0,
                onClick = { onNowPlayingTabChange(0) },
                text = { Text("Art") }
            )
            androidx.compose.material3.Tab(
                selected = nowPlayingTab == 1,
                onClick = { onNowPlayingTabChange(1) },
                text = { Text("Lyrics") }
            )
            androidx.compose.material3.Tab(
                selected = nowPlayingTab == 2,
                onClick = { onNowPlayingTabChange(2) },
                text = { Text("Queue") }
            )
        }

        val queueListState = androidx.compose.foundation.lazy.rememberLazyListState()

        var showExtracting by remember { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(isLoading) {
            if (isLoading) {
                kotlinx.coroutines.delay(2000)
                showExtracting = true
            } else {
                showExtracting = false
            }
        }

        androidx.compose.runtime.LaunchedEffect(nowPlayingTab, queueIndex) {
            if (nowPlayingTab == 2 && queueIndex >= 0) {
                queueListState.animateScrollToItem(queueIndex)
            }
        }

        when (nowPlayingTab) {
            0 -> {
                Spacer(modifier = Modifier.weight(0.6f))

                // Artwork (or video), centered
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (isVideoMode) {
                        androidx.compose.ui.viewinterop.AndroidView(
                            factory = { ctx ->
                                androidx.media3.ui.PlayerView(ctx).apply {
                                    useController = false
                                    player = mediaController
                                }
                            },
                            update = { view ->
                                (view as androidx.media3.ui.PlayerView).player = mediaController
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                        )
                    } else {
                        Thumbnail(thumbnailUrl, 280.dp)
                    }
                }
                Spacer(modifier = Modifier.size(16.dp))

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
                if (isLoading && showExtracting) {
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = "Extracting audio…",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.weight(0.4f))

                // Video/audio toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(56.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.IconButton(
                            onClick = { onToggleVideoMode() },
                            enabled = !videoModeLoading
                        ) {
                            Text(
                                if (isVideoMode) "🎬" else "🎵",
                                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                                color = if (isVideoMode)
                                    androidx.compose.material3.MaterialTheme.colorScheme.primary
                                else
                                    androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (videoModeLoading) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            1 -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    when {
                        lyricsLoading -> {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }
                        lyrics.isEmpty() -> {
                            Text(
                                "No lyrics available.",
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        else -> {
                            val currentIndex = lyrics.indexOfLast { it.startMs <= currentPosMs }
                            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                            androidx.compose.runtime.LaunchedEffect(currentIndex) {
                                if (currentIndex >= 0) listState.animateScrollToItem(currentIndex)
                            }
                            androidx.compose.foundation.lazy.LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            ) {
                                itemsIndexed(lyrics) { idx, line ->
                                    val active = idx == currentIndex
                                    Text(
                                        text = line.text,
                                        color = if (active)
                                            androidx.compose.material3.MaterialTheme.colorScheme.primary
                                        else
                                            androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = if (active)
                                            androidx.compose.material3.MaterialTheme.typography.titleMedium
                                        else
                                            androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp, horizontal = 16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            2 -> {
                if (queue.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Queue is empty.")
                    }
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(
                        state = queueListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        itemsIndexed(queue) { idx, item ->
                            val isCurrent = idx == queueIndex
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onQueueItemClick(idx) }
                                    .padding(vertical = 12.dp, horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isCurrent) {
                                    Text(
                                        "▶",
                                        color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                } else {
                                    Text(
                                        "${idx + 1}.",
                                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                }
                                Text(
                                    text = item.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (isCurrent)
                                        androidx.compose.material3.MaterialTheme.colorScheme.primary
                                    else
                                        androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }

        // Progress bar + time labels (visible in all tabs)
        val displayPos = if (isScrubbing) scrubPositionMs else currentPosMs
        val maxVal = currentDurationMs.coerceAtLeast(1L).toFloat()
        androidx.compose.material3.Slider(
            value = displayPos.coerceIn(0L, currentDurationMs.coerceAtLeast(0L)).toFloat(),
            onValueChange = { v -> if (!isScrubbing) onScrubStart(); onScrubChange(v.toLong()) },
            onValueChangeFinished = { onScrubEnd() },
            valueRange = 0f..maxVal,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Text(
                formatTime(displayPos),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                formatTime(currentDurationMs),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Shuffle + repeat row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 64.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(56.dp),
                contentAlignment = Alignment.Center
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
            }
            Box(
                modifier = Modifier.size(56.dp),
                contentAlignment = Alignment.Center
            ) {
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
        }

        Spacer(modifier = Modifier.size(16.dp))

        // Big transport row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(72.dp),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.IconButton(onClick = onPrev) {
                    Text(
                        "⏮",
                        style = androidx.compose.material3.MaterialTheme.typography.displaySmall
                    )
                }
            }
            Box(
                modifier = Modifier.size(72.dp),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.IconButton(onClick = onPlayPause) {
                    if (isLoading) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            if (isPlaying) "⏸" else "▶",
                            style = androidx.compose.material3.MaterialTheme.typography.displayMedium,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Box(
                modifier = Modifier.size(72.dp),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.IconButton(onClick = onNext) {
                    Text(
                        "⏭",
                        style = androidx.compose.material3.MaterialTheme.typography.displaySmall
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(0.4f))
    }
}

@Composable
fun PlaylistSongsContent(
    playlistSongs: List<PlaylistSong>,
    playlistName: String,
    playlistCreatedAt: Long,
    onBack: () -> Unit,
    onPlayFromPlaylist: (List<PlaylistSong>, Int) -> Unit,
    onRequestRename: () -> Unit,
    onRequestDelete: () -> Unit,
    onSongMenu: (PlaylistSong) -> Unit,
    loadingVideoId: String? = null
) {
    val listState = rememberLazyListState()
    var showOverflow by remember(playlistName) { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.background),
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
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        Text(
                            "←",
                            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.IconButton(onClick = { showOverflow = true }) {
                        Text(
                            "⋮",
                            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium
                        )
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = showOverflow,
                        onDismissRequest = { showOverflow = false }
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = { showOverflow = false; onRequestRename() }
                        )
                        androidx.compose.material3.DropdownMenuItem(
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
                                .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }
                    thumbs.size == 1 -> {
                        coil.compose.AsyncImage(
                            model = thumbs[0],
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
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
                style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
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
                            androidx.compose.material3.MaterialTheme.colorScheme.primary,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Y",
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "You",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                )
            }

            Text(
                text = "${playlistSongs.size} songs · Created ${formatDate(playlistCreatedAt)}",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
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
                            androidx.compose.material3.MaterialTheme.colorScheme.primary,
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
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                        style = androidx.compose.material3.MaterialTheme.typography.titleLarge
                    )
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.IconButton(onClick = { onRequestRename() }) {
                        Text(
                            "✏",
                            style = androidx.compose.material3.MaterialTheme.typography.titleLarge
                        )
                    }
                }
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.IconButton(onClick = { onRequestDelete() }) {
                        Text(
                            "🗑",
                            style = androidx.compose.material3.MaterialTheme.typography.titleLarge
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
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        ps.artist,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (ps.videoId == loadingVideoId) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                        )
                    } else {
                        androidx.compose.material3.IconButton(onClick = { onSongMenu(ps) }) {
                            Text(
                                "⋮",
                                style = androidx.compose.material3.MaterialTheme.typography.titleMedium
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
            .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
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
                        androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        androidx.compose.foundation.shape.CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "+",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "New playlist",
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
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
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            IconButton(onClick = { onPlaylistMenu(pl) }) {
                                Text(
                                    "⋮",
                                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge
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

@Composable
fun ImportScreen(
    state: ImportState,
    onSourceChosen: (ImportSource) -> Unit,
    onConfirm: (ImportPreview) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var chosen by remember { mutableStateOf(-1) }   // -1 = chooser; 0 YTM, 1 Spotify, 2 paste
    var urlText by remember { mutableStateOf("") }
    var pasteText by remember { mutableStateOf("") }
    var importedName by remember { mutableStateOf("") }

    androidx.compose.runtime.LaunchedEffect(state) {
        if (state is ImportState.Previewing) importedName = state.preview.sourceName
    }

    val fileLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onSourceChosen(ImportSource.CsvFile(uri)) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = onDismiss) {
                    Text("←", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                }
            }
            Text(
                "Import",
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
        }

        when (state) {
            is ImportState.Idle -> Column(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (chosen == -1) {
                    Button(
                        onClick = { chosen = 0 },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("From YouTube Music") }
                    Button(
                        onClick = { chosen = 1 },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("From Spotify") }
                    Button(
                        onClick = { chosen = 2 },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Paste a list") }
                    Button(
                        onClick = { fileLauncher.launch(arrayOf("text/*", "application/json")) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("From a file") }
                } else if (chosen == 0 || chosen == 1) {
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        label = { Text("Playlist URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (chosen == 1) {
                        Text(
                            "Public playlists only",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = {
                            val url = urlText.trim()
                            if (url.isNotEmpty()) {
                                onSourceChosen(
                                    if (chosen == 0) ImportSource.YtmUrl(url)
                                    else ImportSource.SpotifyUrl(url)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Import") }
                } else {
                    OutlinedTextField(
                        value = pasteText,
                        onValueChange = { pasteText = it },
                        label = { Text("Artist - Title, one per line") },
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                    Button(
                        onClick = {
                            if (pasteText.isNotBlank()) onSourceChosen(ImportSource.TextPaste(pasteText))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Import") }
                }
            }

            is ImportState.Resolving -> Column(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (state.total > 0) "Resolving track ${state.done} of ${state.total}…"
                    else "Resolving…"
                )
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = {
                        if (state.total > 0) state.done.toFloat() / state.total else 0f
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(24.dp))
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
            }

            is ImportState.Previewing -> {
                val preview = state.preview
                Text(
                    "${preview.sourceName} · ${preview.tracks.size + preview.unresolved.size} tracks",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    item {
                        Text(
                            "Matched (${preview.tracks.size})",
                            style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                        )
                    }
                    items(preview.tracks, key = { it.videoId ?: it.title }) { track ->
                        ImportTrackRow(track, enabled = true)
                    }
                    if (preview.unresolved.isNotEmpty()) {
                        item {
                            Text(
                                "Unmatched (${preview.unresolved.size})",
                                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                            )
                        }
                        item {
                            Text(
                                "These couldn't be matched on YouTube and won't be imported.",
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                        items(preview.unresolved, key = { "u:" + it.title + it.artist }) { track ->
                            ImportTrackRow(track, enabled = false)
                        }
                    }
                }
                Button(
                    onClick = { onConfirm(preview) },
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) { Text("Confirm import") }
            }

            is ImportState.Committing -> Column(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Adding songs…")
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = {
                        if (state.total > 0) state.done.toFloat() / state.total else 0f
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            is ImportState.Done -> Column(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Imported ${state.imported} songs" +
                        (if (importedName.isNotEmpty()) " to $importedName" else ""),
                    textAlign = TextAlign.Center
                )
                if (state.skipped > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${state.skipped} track(s) could not be matched.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { onOpenPlaylist(state.playlistId) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open playlist") }
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Done") }
            }

            is ImportState.Error -> Column(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    state.message,
                    textAlign = TextAlign.Center,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
        }
    }
}

@Composable
fun ImportTrackRow(track: ImportTrack, enabled: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumbnail(track.thumbnailUrl, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                track.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!enabled) {
            Text(
                "Unmatched",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    HorizontalDivider()
}

@Composable
fun SignInScreen(
    configured: Boolean,
    externalError: String?,
    onSignIn: (idToken: String, rawNonce: String?) -> Unit,
    onBack: () -> Unit
) {
    var localError by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "♪",
            style = androidx.compose.material3.MaterialTheme.typography.displayMedium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "MusicApp",
            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Your library stays on your device. Sign in only to sync with friends.",
            textAlign = TextAlign.Center,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        if (!configured) {
            Text(
                "Sync isn't configured yet. Add SUPABASE_URL, SUPABASE_ANON_KEY and " +
                    "GOOGLE_WEB_CLIENT_ID to secrets.properties, then rebuild.",
                textAlign = TextAlign.Center,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            GoogleSignInButton(
                onIdToken = { token, nonce ->
                    localError = null
                    onSignIn(token, nonce)
                },
                onError = { localError = it }
            )
        }
        (externalError ?: localError)?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                it,
                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(24.dp))
        androidx.compose.material3.TextButton(onClick = onBack) { Text("Not now") }
    }
}

@Composable
fun FriendsScreen(
    configured: Boolean,
    signedIn: Boolean,
    friends: List<Profile>,
    loading: Boolean,
    error: String?,
    selectedFriend: Profile?,
    friendSongs: List<SharedPlaylistSong>,
    friendSongsLoading: Boolean,
    nowPlaying: List<NowPlaying>,
    onBack: () -> Unit,
    onBackToList: () -> Unit,
    onOpenFriend: (Profile) -> Unit,
    onPlayFriendSongs: (Int) -> Unit,
    onSignIn: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (selectedFriend != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    IconButton(onClick = onBackToList) {
                        Text("←", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                    }
                }
                Text(
                    selectedFriend.displayName ?: selectedFriend.email ?: "Friend",
                    style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        when {
            !configured -> CenteredMessage(
                "Sync isn't configured. Add your Supabase + Google keys to secrets.properties, then rebuild.",
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            !signedIn -> Column(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "Sign in to sync playlists and see what friends are playing.",
                    textAlign = TextAlign.Center,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onSignIn) { Text("Sign in") }
            }
            selectedFriend != null -> when {
                friendSongsLoading -> Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
                friendSongs.isEmpty() -> CenteredMessage(
                    "This friend hasn't shared a playlist.",
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
                else -> Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Text(
                        "Snapshot — re-share to update.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        itemsIndexed(friendSongs, key = { _, s -> s.videoId }) { idx, s ->
                            SharedSongRow(s) { onPlayFriendSongs(idx) }
                        }
                    }
                }
            }
            loading -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.CircularProgressIndicator()
            }
            error != null -> CenteredMessage(
                error,
                modifier = Modifier.fillMaxWidth().weight(1f),
                isError = true
            )
            friends.isEmpty() -> CenteredMessage(
                "No friends yet. Add one by invite code in Settings.",
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            else -> LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(friends, key = { it.id }) { f ->
                    val np = nowPlaying.firstOrNull { it.userId == f.id }
                    val staleMs = np?.updatedAt?.let { parseIsoMs(it) }?.let {
                        System.currentTimeMillis() - it
                    }
                    val subtitle = if (staleMs != null && staleMs > 2 * 60_000) {
                        "Last seen ${formatRelative(np?.updatedAt)}"
                    } else {
                        f.email ?: ""
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenFriend(f) }
                            .padding(vertical = 12.dp, horizontal = 4.dp)
                    ) {
                        Text(
                            f.displayName ?: f.email ?: "Friend",
                            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            subtitle,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String, modifier: Modifier = Modifier, isError: Boolean = false) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            textAlign = TextAlign.Center,
            color = if (isError) androidx.compose.material3.MaterialTheme.colorScheme.error
            else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SharedSongRow(song: SharedPlaylistSong, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumbnail(song.thumbnailUrl ?: youtubeThumb(song.videoId), 48.dp)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(song.title ?: "", maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                song.artist ?: "",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    HorizontalDivider()
}

@Composable
fun FriendsStrip(
    items: List<NowPlaying>,
    friends: List<Profile>,
    onClick: (NowPlaying) -> Unit
) {
    if (items.isEmpty()) return
    Text(
        "Friends — last seen",
        style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
    )
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)
    ) {
        items(items, key = { it.userId }) { np ->
            val who = friends.firstOrNull { it.id == np.userId }
            val name = who?.displayName ?: who?.email ?: "Friend"
            Column(
                modifier = Modifier
                    .width(96.dp)
                    .padding(end = 12.dp)
                    .clickable { onClick(np) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(
                            androidx.compose.material3.MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        name.firstOrNull()?.uppercase() ?: "?",
                        style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                )
                Text(
                    np.title ?: "",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun NewDropsScreen(
    drops: List<NewDrop>,
    loading: Boolean,
    downloadIds: List<String>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onPlay: (NewDrop) -> Unit,
    onLongClick: (NewDrop) -> Unit,
    onToggleFavorite: (NewDrop) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = onBack) {
                    Text("←", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                }
            }
            Text(
                "New drops",
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
            if (loading) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(4.dp))
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = onRefresh) {
                    Text("⟳", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                }
            }
        }

        if (drops.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "No new drops right now. Play a few songs or favorite some artists and pull back here.",
                    textAlign = TextAlign.Center,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(drops, key = { it.videoId }) { drop ->
                    SongRow(
                        song = drop.asSong(),
                        onClick = { onPlay(drop) },
                        onToggleFavorite = { onToggleFavorite(drop) },
                        onLongClick = { onLongClick(drop) },
                        showDownloaded = downloadIds.contains(drop.videoId)
                    )
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }
}

@Composable
fun DownloadsScreen(
    downloads: List<Download>,
    downloadStates: Map<String, DownloadState>,
    onPlay: (Int) -> Unit,
    onRemove: (Download) -> Unit,
    onRemoveAll: () -> Unit,
    onFindMusic: () -> Unit
) {
    if (downloads.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "✓",
                style = androidx.compose.material3.MaterialTheme.typography.displaySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Music and podcasts that you download will appear here.",
                textAlign = TextAlign.Center,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
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
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = onRemoveAll) {
                    Text(
                        "⋮",
                        style = androidx.compose.material3.MaterialTheme.typography.titleLarge
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
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            d.artist,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (st is DownloadState.Downloading) {
                        Text(
                            "${st.percent}%",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    } else {
                        Text(
                            "✓",
                            color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        IconButton(onClick = { onRemove(d) }) {
                            Text(
                                "⋮",
                                style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}

fun installUpdate(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

@Composable
fun SettingsScreen(
    storageExternal: Boolean,
    onStorageChange: (Boolean) -> Unit,
    downloadDirPath: String,
    downloadCount: Int,
    totalBytes: Long,
    onRemoveAll: () -> Unit,
    onClearLibrary: () -> Unit,
    onViewPrivacyPolicy: () -> Unit,
    notifyUpdates: Boolean,
    onNotifyUpdatesChange: (Boolean) -> Unit,
    accentName: String,
    accentColor: androidx.compose.ui.graphics.Color,
    onOpenAccentPicker: () -> Unit,
    onBack: () -> Unit,
    signedIn: Boolean = false,
    syncConfigured: Boolean = false,
    accountEmail: String? = null,
    inviteCode: String? = null,
    onSignIn: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onCopyInvite: () -> Unit = {},
    friends: List<Profile> = emptyList(),
    addFriendCode: String = "",
    onAddFriendCodeChange: (String) -> Unit = {},
    onAddFriend: () -> Unit = {},
    addFriendStatus: String? = null,
    updateState: UpdateState = UpdateState.Idle,
    onCheckForUpdates: () -> Unit = {},
    onDownloadUpdate: (UpdateInfo) -> Unit = {},
    onInstallUpdate: (File) -> Unit = {}
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = onBack) {
                    Text(
                        "←",
                        style = androidx.compose.material3.MaterialTheme.typography.headlineMedium
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Settings",
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall
            )
        }
        HorizontalDivider()

        SettingsSectionHeader("About")
        Text(
            "MusicApp",
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Made by Saif Sirhan",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "github.com/SaifSirhan/Musicapp",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
            modifier = Modifier.clickable {
                openLink(context, "https://github.com/SaifSirhan/Musicapp")
            }
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "saifsirhan01@gmail.com",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
            modifier = Modifier.clickable {
                openLink(context, "mailto:saifsirhan01@gmail.com")
            }
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider()

        // Account & Sync + Friends — only when signed in (Phase 14, unchanged).
        if (signedIn) {
            SettingsSectionHeader("Account & Sync")
            if (!syncConfigured) {
                Text(
                    "Sync isn't configured. Add SUPABASE_URL, SUPABASE_ANON_KEY and " +
                        "GOOGLE_WEB_CLIENT_ID to secrets.properties, then rebuild.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Signed in as ${accountEmail ?: "unknown"}",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Invite code: ${inviteCode ?: "…"}",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.TextButton(onClick = onCopyInvite) { Text("Copy") }
                }
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedButton(onClick = onSignOut) { Text("Sign out") }
            }

            Spacer(Modifier.height(12.dp))
            SettingsSectionHeader("Friends")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = addFriendCode,
                    onValueChange = onAddFriendCodeChange,
                    label = { Text("Invite code") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.TextButton(onClick = onAddFriend) { Text("Add") }
            }
            addFriendStatus?.let {
                Text(
                    it,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            if (friends.isEmpty()) {
                Text(
                    "No friends yet.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                friends.forEach { f ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(
                            f.displayName ?: f.email ?: "Friend",
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            f.email ?: "",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            HorizontalDivider()
        }

        // Playback — intentionally empty in 15d; gapless toggle lands here in 15e.
        SettingsSectionHeader("Playback")
        HorizontalDivider()

        SettingsSectionHeader("Notifications")
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Update available",
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            androidx.compose.material3.Switch(
                checked = notifyUpdates,
                onCheckedChange = onNotifyUpdatesChange
            )
        }
        HorizontalDivider()

        SettingsSectionHeader("Appearance")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenAccentPicker() }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Accent colour",
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(accentColor, androidx.compose.foundation.shape.CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "$accentName ▾",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider()

        SettingsSectionHeader("Storage")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onStorageChange(false) }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (!storageExternal) "◉" else "○",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = androidx.compose.material3.MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Text("Internal (default)")
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onStorageChange(true) }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (storageExternal) "◉" else "○",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = androidx.compose.material3.MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Text("External")
        }
        Text(
            "Download folder: $downloadDirPath",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            "Total: $downloadCount songs, ${formatBytes(totalBytes)}",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider()

        SettingsSectionHeader("Updates")
        Text(
            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        when (val s = updateState) {
            is UpdateState.Checking -> {
                Text(
                    "Checking for updates…",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is UpdateState.Available -> {
                Text(
                    "Update available: ${s.info.versionName}",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                )
                if (s.info.notes.isNotBlank()) {
                    Text(
                        s.info.notes,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onDownloadUpdate(s.info) }) { Text("Download update") }
            }
            is UpdateState.Downloading -> {
                Text(
                    "Downloading… ${s.percent}%",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { s.percent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            is UpdateState.Downloaded -> {
                Text(
                    "Downloaded. Ready to install.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onInstallUpdate(s.file) }) { Text("Install update") }
            }
            is UpdateState.Failed -> {
                Text(
                    s.message,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onCheckForUpdates) { Text("Check for updates") }
            }
            is UpdateState.UpToDate -> {
                Text(
                    "You're up to date.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onCheckForUpdates) { Text("Check for updates") }
            }
            is UpdateState.Idle -> {
                OutlinedButton(onClick = onCheckForUpdates) { Text("Check for updates") }
            }
        }
        HorizontalDivider()

        SettingsSectionHeader("Privacy")
        OutlinedButton(onClick = onViewPrivacyPolicy) { Text("View privacy policy") }
        HorizontalDivider()

        SettingsSectionHeader("Danger zone")
        Button(
            onClick = onClearLibrary,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.error,
                contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onError
            ),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Clear library") }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onRemoveAll,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.error,
                contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onError
            ),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Remove all downloads") }
    }
}

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        title,
        style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
        color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

private fun openLink(context: Context, uri: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)))
    }
}

@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = onBack) {
                    Text(
                        "←",
                        style = androidx.compose.material3.MaterialTheme.typography.headlineMedium
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Privacy policy",
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall
            )
        }
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text(
            "MusicApp — Privacy Policy",
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(12.dp))
        Text(
            """
            MusicApp does not collect or share your personal data.

            Your library, playlists, favorites, and downloads are stored only on your device.

            If you sign in with Google to use friend sync, your email and the data you choose to share (invite code, shared playlists, now-playing) are stored on a Supabase database operated by the developer.

            Music is streamed from YouTube via yt-dlp. yt-dlp requests go directly from your device to YouTube's servers.

            No analytics, no crash reporting, no third-party tracking.
            """.trimIndent(),
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Contact: saifsirhan01@gmail.com\ngithub.com/SaifSirhan/Musicapp",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
        )
    }
}

fun formatBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val kb = b / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.1f GB".format(mb / 1024.0)
}

@Preview(showBackground = true)
@Composable
fun PlayerScreenPreview() {
    MusicAppTheme {
        PlayerScreen(accentIndex = 0, onAccentChange = {})
    }
}
