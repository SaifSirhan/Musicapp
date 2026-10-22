package com.sharl.musicapp.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.Identity
import com.sharl.musicapp.ImportService
import com.sharl.musicapp.data.AuthRepository
import com.sharl.musicapp.data.SettingsRepo
import com.sharl.musicapp.data.SpotifyImporter
import com.sharl.musicapp.data.YtmImporter
import com.sharl.musicapp.importer.ImportManager
import com.sharl.musicapp.importer.ImportState
import com.sharl.musicapp.ui.components.SettingsSectionHeader
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Account import: connects Spotify (Authorization Code) and YouTube Music
 * (Google Authorization API), lets the user pick playlists, and hands the
 * selected tracks to [ImportService] which does the yt-dlp resolution.
 */
@Composable
fun ImportAccountScreen(
    signedIn: Boolean,
    accountEmail: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsRepo(context.applicationContext) }

    val spotifyConnected by SpotifyImporter.connected.collectAsState()
    val spotifyError by SpotifyImporter.error.collectAsState()
    val importState by ImportManager.state.collectAsState()

    var spotifyPlaylists by remember { mutableStateOf<List<SpotifyImporter.Playlist>>(emptyList()) }
    var spotifyLoading by remember { mutableStateOf(false) }
    var ytmPlaylists by remember { mutableStateOf<List<YtmImporter.Playlist>>(emptyList()) }
    var ytmLoading by remember { mutableStateOf(false) }
    var ytmToken by remember {
        mutableStateOf(settings.googleAccessToken.takeIf { settings.hasFreshGoogleToken() })
    }
    var status by remember { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateMapOf<String, Boolean>() }

    val running = importState is ImportState.Running

    fun applyYtmToken(token: String?) {
        if (token.isNullOrBlank()) {
            localError = "Google returned no access token."
            return
        }
        settings.googleAccessToken = token
        ytmToken = token
        localError = null
    }

    val authRepo = remember { AuthRepository() }
    val ytmConnecting by YtmImporter.connecting.collectAsState()
    val ytmError by YtmImporter.error.collectAsState()

    // The browser redirects back into MainActivity, which hands the code to
    // YtmImporter; the token shows up here and is stored like before.
    LaunchedEffect(Unit) {
        YtmImporter.tokenResult.collect { token ->
            if (token != null) applyYtmToken(YtmImporter.consumeToken())
        }
    }

    fun connectYoutube() {
        localError = null
        YtmImporter.clearError()
        scope.launch {
            if (!YtmImporter.authorize(context)) {
                localError = YtmImporter.error.value ?: "Could not start Google sign-in."
            }
        }
    }

    fun disconnectYoutube() {
        localError = null
        // Clear local state first so the UI reacts immediately.
        settings.clearGoogleAccessToken()
        ytmToken = null
        ytmPlaylists = emptyList()
        selected.keys.filter { it.startsWith("ytm:") }.forEach { selected.remove(it) }
        scope.launch {
            val revoked = authRepo.revokeYoutubeAccess(context)
            localError = if (revoked) {
                "YouTube disconnected. Tap Connect YouTube Music to choose an account."
            } else {
                "YouTube disconnected on this device, but Google could not revoke the " +
                    "grant. To switch accounts, remove the app's access in your Google " +
                    "Account permissions, then connect again."
            }
        }
    }

    LaunchedEffect(spotifyConnected) {
        if (!spotifyConnected) return@LaunchedEffect
        spotifyLoading = true
        runCatching { SpotifyImporter.fetchPlaylists(context) }
            .onSuccess { spotifyPlaylists = it }
            .onFailure { localError = it.message ?: "Could not load Spotify playlists." }
        spotifyLoading = false
    }

    LaunchedEffect(ytmToken) {
        val token = ytmToken ?: return@LaunchedEffect
        ytmLoading = true
        runCatching { YtmImporter.fetchPlaylists(token) }
            .onSuccess { ytmPlaylists = it }
            .onFailure {
                localError = it.message ?: "Could not load YouTube Music playlists."
                if (it.message?.contains("expired") == true) {
                    settings.clearGoogleAccessToken()
                    ytmToken = null
                }
            }
        ytmLoading = false
    }

    fun startImport() {
        if (running) return
        localError = null
        scope.launch {
            try {
                val jobs = JSONArray()
                if (selected["spotify:liked"] == true) {
                    status = "Fetching Liked Songs…"
                    val queries = SpotifyImporter.fetchLikedTracks(context)
                    if (queries.isNotEmpty()) {
                        jobs.put(
                            JSONObject()
                                .put("name", "Spotify Liked Songs")
                                .put("queries", JSONArray(queries))
                        )
                    }
                }
                for (p in spotifyPlaylists) {
                    if (selected["spotify:${p.id}"] != true) continue
                    status = "Fetching ${p.name}…"
                    val queries = SpotifyImporter.fetchPlaylistTracks(context, p.id)
                    if (queries.isNotEmpty()) {
                        jobs.put(
                            JSONObject()
                                .put("name", "Spotify: ${p.name}")
                                .put("queries", JSONArray(queries))
                        )
                    }
                }
                val token = ytmToken
                if (token != null) {
                    for (p in ytmPlaylists) {
                        if (selected["ytm:${p.id}"] != true) continue
                        status = "Fetching ${p.title}…"
                        val tracks = YtmImporter.fetchPlaylistTracks(token, p.id)
                        if (tracks.isNotEmpty()) {
                            val arr = JSONArray()
                            tracks.forEach { t ->
                                arr.put(
                                    JSONObject()
                                        .put("videoId", t.videoId)
                                        .put("title", t.title)
                                        .put("artist", t.artist)
                                        .put("thumbnailUrl", t.thumbnailUrl ?: "")
                                )
                            }
                            jobs.put(
                                JSONObject()
                                    .put("name", "YT Music: ${p.title}")
                                    .put("tracks", arr)
                            )
                        }
                    }
                }
                status = null
                if (jobs.length() == 0) {
                    localError = "Select at least one playlist with tracks."
                    return@launch
                }
                ImportManager.reset()
                ImportManager.begin()
                ImportService.start(context, jobs)
            } catch (e: Exception) {
                status = null
                localError = e.message ?: "Import could not start."
            }
        }
    }

    val hasSelection = selected.values.any { it }

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
            IconButton(onClick = onBack) {
                Text("←", style = MaterialTheme.typography.headlineMedium)
            }
            Spacer(Modifier.width(8.dp))
            Text("Import Library", style = MaterialTheme.typography.headlineSmall)
        }
        HorizontalDivider()

        Text(
            "Pick playlists from Spotify or YouTube Music. Tracks are matched " +
                "with YouTube search, so an import of 500 songs takes a few minutes " +
                "and keeps running in the background.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        // Progress from the foreground service.
        when (val s = importState) {
            is ImportState.Running -> {
                Text(
                    "Resolving track ${s.current} of ${s.total}…",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = {
                        if (s.total > 0) s.current.toFloat() / s.total else 0f
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        ImportService.cancel(context)
                        ImportManager.reset()
                        status = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel Import")
                }
                Spacer(Modifier.height(8.dp))
            }
            is ImportState.Done -> {
                Text(
                    "Import complete — ${s.imported} added" +
                        if (s.skipped > 0) ", ${s.skipped} not found." else ".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
            }
            is ImportState.Error -> {
                Text(
                    s.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
            }
            ImportState.Idle -> Unit
        }

        SettingsSectionHeader("Spotify")
        if (!SpotifyImporter.configured) {
            Text(
                "Spotify import isn't configured. Add SPOTIFY_CLIENT_ID and " +
                    "SPOTIFY_CLIENT_SECRET to secrets.properties, then rebuild.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (!spotifyConnected) {
            Button(onClick = {
                SpotifyImporter.clearError()
                SpotifyImporter.authorize(context)
            }) {
                Text("Connect Spotify")
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Spotify connected",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    SpotifyImporter.disconnect(context)
                    spotifyPlaylists = emptyList()
                    selected.keys.filter { it.startsWith("spotify:") }
                        .forEach { selected.remove(it) }
                }) { Text("Disconnect") }
            }
            if (spotifyLoading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Loading playlists…", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                CheckRow(
                    label = "Liked Songs",
                    checked = selected["spotify:liked"] == true,
                    onCheckedChange = { selected["spotify:liked"] = it }
                )
                spotifyPlaylists.forEach { p ->
                    CheckRow(
                        label = p.name,
                        detail = "${p.trackCount} tracks",
                        checked = selected["spotify:${p.id}"] == true,
                        onCheckedChange = { selected["spotify:${p.id}"] = it }
                    )
                }
            }
        }
        HorizontalDivider()

        SettingsSectionHeader("YouTube Music")
        if (ytmToken == null) {
            if (!signedIn) {
                Text(
                    "Sign in with Google first, then connect YouTube Music.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { connectYoutube() }, enabled = !ytmConnecting) {
                Text(if (ytmConnecting) "Waiting for Google…" else "Connect YouTube Music")
            }
            Text(
                "Opens your browser so you can pick or type any Google account.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            (localError ?: ytmError)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "YouTube connected" + (accountEmail?.let { " ($it)" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { disconnectYoutube() }) { Text("Disconnect") }
            }
            if (ytmLoading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Loading playlists…", style = MaterialTheme.typography.bodySmall)
                }
            } else if (ytmPlaylists.isEmpty()) {
                Text(
                    "No playlists found on this YouTube account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                ytmPlaylists.forEach { p ->
                    CheckRow(
                        label = p.title,
                        detail = if (p.itemCount > 0) "${p.itemCount} tracks" else null,
                        checked = selected["ytm:${p.id}"] == true,
                        onCheckedChange = { selected["ytm:${p.id}"] = it }
                    )
                }
            }
        }
        HorizontalDivider()

        Spacer(Modifier.height(12.dp))
        status?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
        }
        (localError ?: spotifyError)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = { startImport() },
            enabled = hasSelection && !running && status == null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (running) "Import running…" else "Start Import")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}

@Composable
private fun CheckRow(
    label: String,
    detail: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
