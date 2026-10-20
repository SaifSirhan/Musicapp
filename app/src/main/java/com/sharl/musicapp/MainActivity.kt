package com.sharl.musicapp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.LinearProgressIndicator
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import com.sharl.musicapp.data.FollowRepository
import com.sharl.musicapp.data.FriendsRepository
import com.sharl.musicapp.data.ImportRepository
import com.sharl.musicapp.data.InnerTubeHelper
import com.sharl.musicapp.data.LocalMedia
import com.sharl.musicapp.data.LocalMediaRepository
import com.sharl.musicapp.data.MixRepository
import com.sharl.musicapp.data.ResolutionStats
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
import com.sharl.musicapp.data.SpotifyImporter
import com.sharl.musicapp.data.UpdateRepository
import com.sharl.musicapp.data.UpdateState
import java.io.File
import com.sharl.musicapp.ui.components.MenuItem
import com.sharl.musicapp.ui.components.Thumbnail
import com.sharl.musicapp.ui.components.asSong
import com.sharl.musicapp.ui.screens.ForYouScreen
import com.sharl.musicapp.ui.screens.HomeTab
import com.sharl.musicapp.ui.screens.ImportAccountScreen
import com.sharl.musicapp.ui.screens.ImportScreen
import com.sharl.musicapp.ui.screens.LibraryTab
import com.sharl.musicapp.ui.screens.NewDropsScreen
import com.sharl.musicapp.ui.screens.NowPlayingScreen
import com.sharl.musicapp.ui.screens.PlaylistsScreen
import com.sharl.musicapp.ui.screens.PrivacyPolicyScreen
import com.sharl.musicapp.ui.screens.SearchTab
import com.sharl.musicapp.ui.screens.SettingsScreen
import com.sharl.musicapp.ui.screens.SignInScreen
import com.sharl.musicapp.ui.screens.SongPickerScreen
import com.sharl.musicapp.ui.screens.VideoPlayerScreen
import com.sharl.musicapp.ui.theme.AccentOptions
import com.sharl.musicapp.ui.theme.DefaultAccent
import com.sharl.musicapp.ui.theme.MusicAppTheme
import com.sharl.musicapp.ui.util.formatRelative
import com.sharl.musicapp.ui.util.youtubeThumb
import kotlinx.coroutines.launch

data class QueueItem(
    val videoId: String,
    val title: String,
    val artist: String = "",
    val localUri: String? = null,
    val isVideo: Boolean = false
)

private fun isLocalUri(uri: String): Boolean =
    uri.startsWith("content://") || uri.startsWith("file://")

class MainActivity : ComponentActivity() {
    // Set when the Spotify redirect arrives before the UI exists (cold start),
    // so PlayerScreen can open the import screen once it composes.
    private var pendingImportRedirect = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SpotifyImporter.init(applicationContext)
        handleSpotifyRedirect(intent)
        setContent {
            val context = LocalContext.current
            val prefs = remember {
                context.getSharedPreferences("theme", Context.MODE_PRIVATE)
            }
            var accentIndex by remember {
                mutableStateOf(prefs.getInt("accent_index", 0))
            }
            val openImport = pendingImportRedirect
            pendingImportRedirect = false
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
                        snackbarHostState = snackbarHostState,
                        openImportOnStart = openImport
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSpotifyRedirect(intent)
    }

    private fun handleSpotifyRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "com.sharl.musicapp" && data.host == "callback") {
            pendingImportRedirect = true
            SpotifyImporter.handleRedirect(applicationContext, data)
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    modifier: Modifier = Modifier,
    accentIndex: Int,
    onAccentChange: (Int) -> Unit,
    snackbarHostState: SnackbarHostState = SnackbarHostState(),
    openImportOnStart: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { SongRepository(context) }
    val recent by repo.recentlyPlayed().collectAsState(initial = emptyList())
    val favorites by repo.favorites().collectAsState(initial = emptyList())
    val forYouRecent by repo.recentSongs(10).collectAsState(initial = emptyList())
    val forYouTop by repo.mostPlayed(10).collectAsState(initial = emptyList())
    val forYouFavorites by repo.favoriteSongs(10).collectAsState(initial = emptyList())
    val allSongs by repo.allSongs().collectAsState(initial = emptyList())
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
    // In-flight queue rebuild. A new play request cancels it so rapid skips
    // can't race two setMediaItems calls against a stale queue index.
    var playJob by remember { mutableStateOf<Job?>(null) }
    // Queue index that maps to player position 0 of the currently loaded
    // MediaItem window. The player only ever holds a contiguous slice
    // [windowStart .. windowStart + mediaItemCount - 1] of [queue].
    var windowStart by remember { mutableStateOf(0) }
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
    var innerTubeFailCount by remember { mutableStateOf(ResolutionStats.innerTubeFailCount(context)) }
    var fastEngine by remember { mutableStateOf(ResolutionStats.lastWasFast(context)) }

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
    var showSongPickerForSlot by remember { mutableStateOf<Int?>(null) }

    val mixRepo = remember { MixRepository(context) }
    var mixBuilding by remember { mutableStateOf(false) }
    // Seed title of the radio queue currently loaded, null for normal playback.
    var activeMixTitle by remember { mutableStateOf<String?>(null) }
    val followRepo = remember { FollowRepository(context) }
    var followedArtists by remember { mutableStateOf(followRepo.artists()) }

    val downloadRepo = remember { DownloadRepository(context) }
    val downloads by downloadRepo.all().collectAsState(initial = emptyList())
    val downloadIds by downloadRepo.allIds().collectAsState(initial = emptyList())
    val localMediaRepo = remember { LocalMediaRepository(context) }
    val localMedia by localMediaRepo.all().collectAsState(initial = emptyList())
    var mediaPermissionGranted by remember { mutableStateOf(false) }
    val downloadStates by Downloader.states.collectAsState()
    val settings = remember { SettingsRepo(context) }
    var storageExternalState by remember { mutableStateOf(settings.storageExternal) }
    var showSettings by remember { mutableStateOf(false) }
    var showImportAccount by remember { mutableStateOf(openImportOnStart) }
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

    // seed artists: followed ∪ top-played (SongDao) ∪ favorites' artists
    val newDropsSeedArtists by remember {
        androidx.compose.runtime.derivedStateOf {
            val top = forYouTop.sortedByDescending { it.playCount }.take(10).map { it.artist }
            val favs = favorites.map { it.artist }
            (followedArtists + top + favs).map { it.trim() }.filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }.take(20)
        }
    }

    // Keyed on the seed list (not Unit) so a cold start still fires once Room's
    // flows have emitted the played/favorite artists.
    androidx.compose.runtime.LaunchedEffect(newDropsSeedArtists, newDropsLoading) {
        if (newDropsSeedArtists.isNotEmpty() && newDropsRepo.isStale() && !newDropsLoading) {
            newDropsRepo.refresh(context, newDropsSeedArtists, followedArtists)
        }
    }

    val importRepo = remember { ImportRepository(context) }
    val importState by importRepo.state.collectAsState()
    var showImport by remember { mutableStateOf(false) }
    var importJob by remember { mutableStateOf<Job?>(null) }

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

    // Local media (Phase 20). API 33+ splits audio/video; older releases use
    // the legacy storage permission. Requests are staggered after the
    // notification dialog: two simultaneous permission prompts can clobber
    // each other on some OEM builds.
    fun mediaPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun hasMediaPermissions(): Boolean = mediaPermissions().all {
        ContextCompat.checkSelfPermission(context, it) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = mediaPermissions().all { result[it] == true }
        mediaPermissionGranted = granted
        if (granted) scope.launch { localMediaRepo.refresh() }
    }

    LaunchedEffect(Unit) {
        mediaPermissionGranted = hasMediaPermissions()
        if (hasMediaPermissions()) {
            localMediaRepo.refresh()
        } else {
            kotlinx.coroutines.delay(1200)
            mediaPermissionLauncher.launch(mediaPermissions())
        }
    }

    LaunchedEffect(libraryTab) {
        if (libraryTab == 4 && hasMediaPermissions()) localMediaRepo.refresh()
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

    // Triple hybrid: InnerTube first (fast, ~200ms), yt-dlp as the safety net.
    // Metadata comes from the library row, or the queue title when the row
    // doesn't exist yet (search-and-play inserts it only after resolution).
    suspend fun resolveRemote(
        videoId: String,
        fallbackTitle: String? = null
    ): Pair<String, SongMetadata>? {
        val started = System.currentTimeMillis()
        val song = runCatching { repo.get(videoId) }.getOrNull()
        val title = song?.title ?: fallbackTitle ?: videoId
        val fast = InnerTubeHelper.getStreamUrl(videoId)
        if (fast != null) {
            ResolutionStats.recordFast(context)
            fastEngine = true
            Log.d("RESOLVE", "InnerTube hit for $videoId in ${System.currentTimeMillis() - started}ms")
            return fast to SongMetadata(
                title = title,
                artist = song?.artist ?: "",
                album = song?.album,
                thumbnailUrl = song?.thumbnailUrl ?: youtubeThumb(videoId)
            )
        }
        Log.w("RESOLVE", "InnerTube failed, falling back to yt-dlp ($videoId)")
        ResolutionStats.recordFallback(context)
        innerTubeFailCount += 1
        fastEngine = false
        return YtdlpHelper.extractUrlAndMetadata(context, videoId)
    }

    // Fire-and-forget background extraction into the cache. Never plays, never
    // blocks UI, swallows errors. Serialized against user-initiated extraction
    // via the shared mutex so only one yt-dlp process runs at a time.
    suspend fun preExtract(videoId: String, title: String? = null) {
        if (videoId.isBlank()) return
        if (isLocalUri(videoId)) return
        if (PlaybackCache.get(videoId) != null) return
        if (downloadRepo.get(videoId)?.let { java.io.File(it.filePath).exists() } == true) return
        extractMutex.withLock {
            if (PlaybackCache.get(videoId) != null) return@withLock
            try {
                val pre = resolveRemote(videoId, title)
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
        (forYouRecent.firstOrNull() ?: forYouTop.firstOrNull())?.let { preExtract(it.videoId, it.title) }
    }

    suspend fun extractOrCache(
        videoId: String,
        fallbackTitle: String? = null,
        localUri: String? = null,
        localArtist: String = ""
    ): Pair<String, SongMetadata> {
        // Local Media Hub: the queue item already points at playable content.
        if (localUri != null) {
            return localUri to SongMetadata(
                title = fallbackTitle ?: localUri,
                artist = localArtist,
                album = null,
                thumbnailUrl = null
            )
        }
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
        // 3. Cold extract — InnerTube first, yt-dlp fallback; serialized so only
        // one resolver runs at a time.
        loadingVideoId = videoId
        try {
            val result = extractMutex.withLock {
                resolveRemote(videoId, fallbackTitle)
            } ?: throw java.io.IOException("No stream URL for $videoId")
            val (u, m) = result
            PlaybackCache.put(videoId, u, m.title, m.artist, m.album, m.thumbnailUrl)
            urlCache[videoId] = result
            return result
        } finally {
            if (loadingVideoId == videoId) loadingVideoId = null
        }
    }

    // --- Gapless playback helpers -------------------------------------------
    // How many tracks ahead of the current one we try to keep resolved inside
    // the ExoPlayer playlist so it can advance natively (no gap).
    val PRELOAD_AHEAD = 3

    // Non-blocking cache lookup: in-session map first, then the persistent
    // PlaybackCache. Returns null when a track isn't resolved yet, so callers
    // skip it instead of triggering a cold (blocking) yt-dlp extraction.
    fun cachedOnly(item: QueueItem): Pair<String, SongMetadata>? {
        // Local files need no resolution and never expire, so the gapless
        // window can always include them.
        item.localUri?.let { uri ->
            return uri to SongMetadata(
                title = item.title,
                artist = item.artist,
                album = null,
                thumbnailUrl = null
            )
        }
        val videoId = item.videoId
        // Downloaded files win over stream URLs: they never expire and need no
        // network, so the gapless window can include them unconditionally.
        downloads.firstOrNull { it.videoId == videoId }?.let { row ->
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
        urlCache[videoId]?.let { return it }
        PlaybackCache.get(videoId)?.let { c ->
            return c.url to SongMetadata(c.title, c.artist, c.album, c.thumbnailUrl)
        }
        return null
    }

    // Build a MediaItem tagged with its queue index in the request-metadata
    // extras so PlaybackService can report exactly which track the playlist
    // advanced to on a native (gapless) transition.
    fun buildMediaItem(
        videoId: String,
        queueIdx: Int,
        url: String,
        meta: SongMetadata?
    ): MediaItem {
        val extras = android.os.Bundle().apply {
            putInt("queue_index", queueIdx)
            putString("video_id", videoId)
        }
        val builder = MediaItem.Builder()
            .setMediaId(videoId)
            .setUri(url)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder().setExtras(extras).build()
            )
        if (meta != null) {
            builder.setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(meta.title)
                    .setArtist(meta.artist)
                    .setAlbumTitle(meta.album)
                    .setArtworkUri(meta.thumbnailUrl?.let { android.net.Uri.parse(it) })
                    .build()
            )
        }
        return builder.build()
    }

    // Extend the player's playlist forward with already-resolved tracks so it
    // stays up to PRELOAD_AHEAD ahead of [currentIndex]. Stops at the first
    // track that isn't cached yet, keeping the window strictly contiguous:
    // player position p maps to queue index (windowStart + p).
    fun syncPlayerWindow(currentIndex: Int) {
        val c = controller ?: return
        if (currentIndex < 0 || queue.isEmpty()) return
        var nextToLoad = windowStart + c.mediaItemCount
        while (nextToLoad <= currentIndex + PRELOAD_AHEAD && nextToLoad < queue.size) {
            val q = queue[nextToLoad]
            val cached = cachedOnly(q) ?: break
            c.addMediaItem(buildMediaItem(q.videoId, nextToLoad, cached.first, cached.second))
            nextToLoad++
        }
    }

    // Update the now-playing UI, record the play and (re)fetch lyrics for the
    // track at [index]. Shared by an explicit playFromQueue and by a native
    // playlist transition reported through QueueAdvanceSignaler.trackChanged.
    fun presentTrack(index: Int, meta: SongMetadata?) {
        val q = queue.getOrNull(index) ?: return
        nowPlayingTitle = meta?.title ?: q.title
        nowPlayingArtist = meta?.artist ?: q.artist
        nowPlayingThumbnail = meta?.thumbnailUrl
        isVideoMode = q.isVideo
        status = "Playing: ${nowPlayingTitle}"
        if (q.localUri != null) {
            // Local files have no library row to record and no lyrics source.
            lyrics = emptyList()
            lyricsLoading = false
            return
        }
        scope.launch {
            repo.recordPlay(
                q.videoId,
                meta?.title ?: q.title,
                meta?.artist ?: "",
                meta?.album,
                meta?.thumbnailUrl
            )
        }
        lyrics = emptyList()
        lyricsLoading = true
        val fetchTitle = meta?.title ?: q.title
        val fetchArtist = meta?.artist ?: ""
        scope.launch {
            val result = LyricsHelper.fetch(fetchArtist, fetchTitle)
            // Only apply if still the same track
            if (nowPlayingTitle == fetchTitle) {
                lyrics = result ?: emptyList()
                lyricsLoading = false
            }
        }
    }

    // Silently download the next two suggestions so their playback is instant
    // too. Serialised by Downloader's mutex; failures are swallowed.
    fun downloadSuggestions(items: List<QueueItem>, startIndex: Int) {
        val suggestions = (startIndex..(startIndex + 1)).mapNotNull { items.getOrNull(it) }
        if (suggestions.isEmpty()) return
        scope.launch {
            for (s in suggestions) {
                if (s.localUri != null) continue
                if (downloadRepo.get(s.videoId)?.let { java.io.File(it.filePath).exists() } == true) continue
                val song = runCatching { repo.get(s.videoId) }.getOrNull()
                try {
                    Downloader.download(
                        context, settings, s.videoId,
                        song?.title ?: s.title,
                        song?.artist ?: "",
                        song?.thumbnailUrl,
                        0L
                    )
                } catch (e: Exception) {
                    Log.w("DOWNLOAD", "suggestion download failed ${s.videoId}", e)
                }
            }
        }
    }

    fun playFromQueue(
        items: List<QueueItem>,
        index: Int,
        downloadAhead: Boolean = false,
        prefetchAhead: Int = PRELOAD_AHEAD,
        mixTitle: String? = null
    ) {
        if (controller == null) {
            status = "Connecting..."
            return
        }
        if (index !in items.indices) return
        // Any regular play request leaves the radio station; a mix request sets
        // its seed so the Queue tab can show "Mix: …".
        activeMixTitle = mixTitle
        // Supersede any in-flight rebuild: rapid Next taps must not leave two
        // setMediaItems calls racing against different queue indices.
        playJob?.cancel()
        queue = items
        queueIndex = index
        val item = items[index]
        status = "Extracting..."
        if (downloadAhead) downloadSuggestions(items, index + 1)
        playJob = scope.launch {
            try {
                val result = extractOrCache(item.videoId, item.title, item.localUri, item.artist)
                // A superseded request may finish extraction after cancellation;
                // never apply its stale window.
                ensureActive()
                val (url, meta) = result

                // Build the initial MediaItem window: the current track plus any
                // following tracks already resolved in cache. setMediaItems lets
                // ExoPlayer advance between them natively (gapless).
                val mediaItems = mutableListOf(
                    buildMediaItem(item.videoId, index, url, meta)
                )
                val endIdx = minOf(index + PRELOAD_AHEAD, items.size - 1)
                for (i in (index + 1)..endIdx) {
                    val next = items[i]
                    val cached = cachedOnly(next) ?: break
                    mediaItems.add(buildMediaItem(next.videoId, i, cached.first, cached.second))
                }
                windowStart = index

                controller!!.setMediaItems(mediaItems, 0, 0)
                controller!!.prepare()
                controller!!.play()
                isPlaying = true
                presentTrack(index, meta)

                // Warm the cache for the upcoming tracks, then extend the loaded
                // window with whatever became available so the next transitions
                // are gapless too. Serialized via extractMutex. Mixes ask for a
                // wider warm-up (first five tracks) so skips start instantly.
                val upcoming = (index + 1..index + prefetchAhead)
                    .filter { it < items.size }
                for (idx in upcoming) {
                    ensureActive()
                    if (items[idx].localUri == null) preExtract(items[idx].videoId, items[idx].title)
                    syncPlayerWindow(queueIndex)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: "unknown error"
                Log.e("YTDL", "Failed: $msg")
                status = "Error: $msg"
                snackbarHostState.showSnackbar(
                    "Couldn't play this track. Check your connection and try again."
                )
            }
        }
    }

    fun playNext() {
        if (queueIndex < 0) return
        val c = controller ?: return
        if (queueIndex < queue.size - 1) {
            if (c.hasNextMediaItem()) {
                // Native, gapless advance; queueIndex updates via trackChanged.
                c.seekToNextMediaItem()
            } else {
                // Next track wasn't cached when the window was built — reload.
                playFromQueue(queue, queueIndex + 1)
            }
        } else if (repeatMode == 1 && queue.isNotEmpty()) {
            // App-level repeat-all: wrap to the true queue start.
            playFromQueue(queue, 0)
        } else {
            status = "End of queue"
        }
    }

    fun playPrev() {
        if (queueIndex < 0) return
        val c = controller ?: return
        if (queueIndex > 0) {
            if (c.hasPreviousMediaItem()) {
                // Native, gapless back; queueIndex updates via trackChanged.
                c.seekToPreviousMediaItem()
            } else {
                playFromQueue(queue, queueIndex - 1)
            }
        } else {
            status = "Start of queue"
        }
    }

    // Native playlist transitions (auto-advance or seek) report the new queue
    // index; sync the UI and keep the loaded window ahead of playback.
    LaunchedEffect(Unit) {
        QueueAdvanceSignaler.trackChanged.collect { newIndex ->
            // Ignore the transition that echoes our own setMediaItems call in
            // playFromQueue (same index we already presented).
            if (newIndex == queueIndex) return@collect
            if (newIndex !in queue.indices) return@collect
            queueIndex = newIndex
            val q = queue[newIndex]
            presentTrack(newIndex, cachedOnly(q)?.second)
            val upcoming = listOf(newIndex + 1, newIndex + 2, newIndex + 3)
                .filter { it < queue.size }
            scope.launch {
                upcoming.forEach { idx ->
                    preExtract(queue[idx].videoId, queue[idx].title)
                    syncPlayerWindow(queueIndex)
                }
            }
        }
    }

    // The player exhausted its loaded items (STATE_ENDED). Load the next window
    // or, at the true end of the queue, honour repeat-all.
    LaunchedEffect(Unit) {
        QueueAdvanceSignaler.queueEnded.collect {
            if (queueIndex < queue.size - 1) {
                playFromQueue(queue, queueIndex + 1)
            } else if (repeatMode == 1 && queue.isNotEmpty()) {
                // Last item of the app-side queue finished — wrap to the
                // start (the controller deliberately runs REPEAT_MODE_OFF).
                playFromQueue(queue, 0)
            } else {
                isPlaying = false
                status = "End of queue"
            }
        }
    }

    fun appendToQueue(song: Song) {
        val item = QueueItem(song.videoId, song.title)
        queue = queue + item
        status = "Added to queue: ${song.title}"
        // Appending leaves existing indices intact; let the window pick it up
        // once it resolves.
        syncPlayerWindow(queueIndex)
    }

    // "Play next" inserts mid-queue, shifting the queue index of every loaded
    // MediaItem after the insertion point. Drop the stale tail and re-add it
    // from the new queue so the queue_index tags match again. The current item
    // and playback position are untouched (no prepare/reset).
    fun relinkPlayerWindowTail() {
        val c = controller ?: return
        if (queueIndex < 0) return
        val currentPos = c.currentMediaItemIndex
        if (currentPos < 0) return
        val suffixStart = currentPos + 1
        if (c.mediaItemCount > suffixStart) {
            c.removeMediaItems(suffixStart, c.mediaItemCount)
        }
        windowStart = queueIndex - currentPos
        syncPlayerWindow(queueIndex)
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
        // Let any in-flight rebuild finish first so we relink the final window,
        // then resolve the inserted track and refresh the tail.
        val pending = playJob
        scope.launch {
            pending?.join()
            preExtract(item.videoId, item.title)
            relinkPlayerWindowTail()
        }
    }

    // Phase 20: local songs/videos are already playable content URIs; no
    // resolution step, and a video opens straight into full-screen playback.
    fun playLocal(items: List<LocalMedia>, index: Int) {
        if (index !in items.indices) return
        val queueItems = items.map {
            QueueItem(
                videoId = it.id,
                title = it.title,
                artist = it.artist,
                localUri = it.id,
                isVideo = it.isVideo
            )
        }
        playFromQueue(queueItems, index)
        if (items[index].isVideo) showNowPlaying = true
    }

    // Phase 19: build the radio queue off the UI thread, then hand it to the
    // player. Only the first five tracks get warmed; the rest resolve lazily.
    fun startMix(seed: Song) {
        if (mixBuilding) return
        menuSong = null
        scope.launch {
            mixBuilding = true
            status = "Building your mix..."
            try {
                val mix = mixRepo.generateMix(seed)
                if (mix.size <= 1) {
                    status = "Mix unavailable"
                    snackbarHostState.showSnackbar("Couldn't build a mix — check your connection.")
                    return@launch
                }
                playFromQueue(
                    mix.map { QueueItem(it.videoId, it.title) },
                    0,
                    prefetchAhead = 4,
                    mixTitle = seed.title
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Mix failed: ${e.message}"
                snackbarHostState.showSnackbar("Couldn't build a mix — check your connection.")
            } finally {
                mixBuilding = false
            }
        }
    }

    fun toggleFollow(artist: String) {
        followRepo.toggle(artist)
        followedArtists = followRepo.artists()
        val seeds = (followedArtists + newDropsSeedArtists)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
        if (seeds.isNotEmpty()) {
            scope.launch { newDropsRepo.refresh(context, seeds, followedArtists) }
        }
    }

    fun onSpeedDialTap(slot: SpeedDialSlot?, position: Int) {        if (slot == null || slot.type == "empty") {
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
        // Local files carry both streams in the same URI — flipping the view
        // is purely a UI concern and needs no re-extraction.
        if (item.localUri != null) {
            isVideoMode = !isVideoMode
            status = if (isVideoMode) "Video mode" else "Audio mode"
            return
        }
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
                // The playlist was replaced by a single item for the current
                // track; keep the window invariant (player pos 0 == queueIndex).
                windowStart = queueIndex
                status = if (newMode) "Video mode" else "Audio mode"
            } catch (e: Exception) {
                status = "Toggle failed: ${e.message}"
            } finally {
                videoModeLoading = false
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
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
                queueTabLabel = activeMixTitle?.let { "Mix: $it" } ?: "Queue",
                onQueueItemClick = { idx -> playFromQueue(queue, idx) },
                onStartMix = {
                    queue.getOrNull(queueIndex)?.let { q ->
                        startMix(
                            Song(
                                videoId = q.videoId,
                                title = q.title,
                                artist = nowPlayingArtist ?: "",
                                thumbnailUrl = nowPlayingThumbnail,
                                lastPlayedAt = 0L,
                                playCount = 0
                            )
                        )
                    }
                },
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
                    // Repeat-all is app-level: the controller must NOT wrap
                    // inside its loaded window, or ExoPlayer never reports
                    // STATE_ENDED at the true queue end and the wrap to the
                    // full queue (see queueEnded collector) is unreachable.
                    controller?.repeatMode = when (repeatMode) {
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
        } else if (showSongPickerForSlot != null) {
            androidx.activity.compose.BackHandler(enabled = true) {
                showSongPickerForSlot = null
            }
            val slot = showSongPickerForSlot!!
            SongPickerScreen(
                recent = forYouRecent,
                favorites = favorites,
                allSongs = allSongs,
                onBack = { showSongPickerForSlot = null },
                onPick = { song ->
                    scope.launch {
                        speedDialRepo.assignSong(
                            slot,
                            song.videoId,
                            song.title,
                            song.thumbnailUrl ?: youtubeThumb(song.videoId)
                        )
                    }
                    showSongPickerForSlot = null
                },
                onToggleFavorite = { song -> scope.launch { repo.toggleFavorite(song) } }
            )
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
                fastEngine = fastEngine,
                innerTubeFailCount = innerTubeFailCount,
                onCheckForUpdates = { scope.launch { updateRepo.check() } },
                onDownloadUpdate = { info -> scope.launch { updateRepo.download(info) } },
                onInstallUpdate = { file -> installUpdate(context, file) },
                onImportAccount = {
                    showSettings = false
                    showImportAccount = true
                }
            )
        } else if (showImportAccount) {
            androidx.activity.compose.BackHandler(enabled = true) {
                showImportAccount = false
                showSettings = true
            }
            ImportAccountScreen(
                signedIn = signedIn,
                accountEmail = authRepo.currentUserEmail(),
                onBack = {
                    showImportAccount = false
                    showSettings = true
                }
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
                            newDropsRepo.refresh(context, newDropsSeedArtists, followedArtists)
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
                importJob?.cancel()
                importJob = null
                importRepo.cancel()
                showImport = false
            }
            ImportScreen(
                state = importState,
                onSourceChosen = { src ->
                    importJob = scope.launch {
                        importRepo.preview(src, coroutineContext[Job])
                    }
                },
                onConfirm = { preview, downloadOffline ->
                    importJob = scope.launch { importRepo.commit(preview, downloadOffline) }
                },
                onOpenPlaylist = { playlistId ->
                    importJob = null
                    importRepo.reset()
                    showImport = false
                    tab = 2
                    libraryTab = 1
                    openPlaylistId = playlistId
                },
                onDismiss = {
                    importJob?.cancel()
                    importJob = null
                    importRepo.cancel()
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
                HomeTab(
                    forYouTop = forYouTop,
                    forYouRecent = forYouRecent,
                    onPreExtract = { id -> preExtract(id) },
                    onPlayFromQueue = { items, index -> playFromQueue(items, index) },
                    downloadIds = downloadIds,
                    loadingVideoId = loadingVideoId,
                    onSongMenu = { menuSong = it },
                    signedIn = signedIn,
                    friendsNowPlaying = friendsNowPlaying,
                    friendsList = friendsList,
                    onFriendClick = { nowPlayingFriend = it },
                    newDrops = newDrops,
                    newDropsLoading = newDropsLoading,
                    onSeeAllDrops = { showNewDropsAll = true },
                    speedDialSlots = speedDialSlots,
                    onSpeedDialTap = { slot, position -> onSpeedDialTap(slot, position) },
                    onSpeedDialLongPress = { speedDialMenuPosition = it },
                    topArtistName = topArtistName,
                    forYouByArtist = forYouByArtist,
                    forYouFavorites = forYouFavorites,
                    isFollowingTopArtist = topArtistName?.let { a ->
                        followedArtists.any { it.equals(a, ignoreCase = true) }
                    } ?: false,
                    onToggleFollowTopArtist = { topArtistName?.let { toggleFollow(it) } },
                )
            }
            3 -> {
                ForYouScreen(
                    forYouRecent = forYouRecent,
                    forYouTop = forYouTop,
                    topArtistName = topArtistName,
                    forYouByArtist = forYouByArtist,
                    forYouFavorites = forYouFavorites,
                    playFromQueue = { items, index ->
                        // Recommendations: warm the next two suggestions to disk
                        // so their playback skips yt-dlp entirely.
                        playFromQueue(items, index, downloadAhead = true)
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            1 -> {
                SearchTab(
                    context = context,
                    query = query,
                    onQueryChange = { query = it },
                    results = results,
                    onResultsChange = { results = it },
                    isSearching = isSearching,
                    onSearchingChange = { isSearching = it },
                    localMatches = localMatches,
                    onLocalMatchesChange = { localMatches = it },
                    onSearchLocal = { q -> repo.searchLocal(q) },
                    onPreExtract = { id -> preExtract(id) },
                    scope = scope,
                    onPlayFromQueue = { items, index -> playFromQueue(items, index) },
                    onToggleFavorite = { song -> scope.launch { repo.toggleFavorite(song) } },
                    onSongMenu = { menuSong = it },
                    downloadIds = downloadIds,
                    loadingVideoId = loadingVideoId,
                )
            }
            2 -> {
                LibraryTab(
                    context = context,
                    scope = scope,
                    libraryTab = libraryTab,
                    onLibraryTabChange = { libraryTab = it },
                    libraryLayout = libraryLayout,
                    onLibraryLayoutChange = { libraryLayout = it },
                    onImportClick = { importRepo.reset(); showImport = true },
                    downloads = downloads,
                    downloadStates = downloadStates,
                    onRemoveAllDownloads = { showRemoveAllDownloads = true },
                    onFindMusic = { tab = 1 },
                    playlists = playlists,
                    openPlaylistId = openPlaylistId,
                    onOpenPlaylistChange = { openPlaylistId = it },
                    playlistRepo = playlistRepo,
                    onCreatePlaylist = { showNewPlaylistDialog = true },
                    onRequestRename = { name ->
                        renamePlaylistName = name
                        showRenamePlaylist = true
                    },
                    onRequestDelete = { showDeletePlaylistConfirm = true },
                    onPlaylistSongMenu = { ps ->
                        playlistMenuSong = ps
                        playlistMenuPlaylistId = openPlaylistId
                    },
                    onPlaylistMenu = { pl -> playlistListMenu = pl },
                    favorites = favorites,
                    onToggleFavorite = { song -> scope.launch { repo.toggleFavorite(song) } },
                    onSongMenu = { menuSong = it },
                    downloadIds = downloadIds,
                    loadingVideoId = loadingVideoId,
                    onPreExtract = { id -> preExtract(id) },
                    onPlayFromQueue = { items, index -> playFromQueue(items, index) },
                    friendsConfigured = authRepo.configured,
                    signedIn = signedIn,
                    friends = friendsList,
                    friendsLoading = friendsLoading,
                    friendsError = friendsError,
                    selectedFriend = selectedFriend,
                    selectedFriendSongs = selectedFriendSongs,
                    selectedFriendSongsLoading = selectedFriendSongsLoading,
                    friendsNowPlaying = friendsNowPlaying,
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
                    onFriendBack = { selectedFriend = null },
                    onSignIn = { showSignIn = true },
                    localMedia = localMedia,
                    hasMediaPermission = mediaPermissionGranted,
                    onPlayLocal = { items, index -> playLocal(items, index) },
                    onRequestMediaPermission = { mediaPermissionLauncher.launch(mediaPermissions()) },
                    onRescanLocal = { scope.launch { localMediaRepo.refresh() } },
                )
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
                    if (queue.getOrNull(queueIndex)?.isVideo == true) {
                        Text(
                            "🎥",
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
                                .padding(1.dp)
                        )
                    }
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

                    MenuItem("Start Mix", icon = "📻") {
                        startMix(song)
                    }
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
                    MenuItem("Choose a song") {
                        showSongPickerForSlot = sdPos
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

        val videoItem = queue.getOrNull(queueIndex)
        if (showNowPlaying && isVideoMode && videoItem?.isVideo == true) {
            VideoPlayerScreen(
                mediaController = controller,
                title = nowPlayingTitle ?: videoItem.title,
                artist = nowPlayingArtist ?: videoItem.artist,
                onBack = { showNowPlaying = false },
                onAudioOnly = { isVideoMode = false }
            )
        }

        if (mixBuilding) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Building your mix…")
                }
            }
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

@Preview(showBackground = true)
@Composable
fun PlayerScreenPreview() {
    MusicAppTheme {
        PlayerScreen(accentIndex = 0, onAccentChange = {})
    }
}
