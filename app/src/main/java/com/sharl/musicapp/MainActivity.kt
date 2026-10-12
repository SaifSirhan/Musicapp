package com.sharl.musicapp

import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.sharl.musicapp.ui.theme.MusicAppTheme
import kotlinx.coroutines.launch

private const val TEST_VIDEO_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

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

    var status by remember { mutableStateOf("Connecting...") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var connected by remember { mutableStateOf(false) }

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
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
            items(results, key = { it.videoId }) { result ->
                Text(
                    text = result.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val c = controller
                            if (c == null) {
                                status = "Connecting..."
                                return@clickable
                            }
                            status = "Extracting..."
                            scope.launch {
                                try {
                                    val url = YtdlpHelper.extractAudioUrlForId(context, result.videoId)
                                    if (url != null) {
                                        Log.d("YTDL", "URL: $url")
                                        c.setMediaItem(MediaItem.fromUri(url))
                                        c.prepare()
                                        c.play()
                                        status = "Playing: ${result.title}"
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
                        }
                        .padding(vertical = 12.dp)
                )
                HorizontalDivider()
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
                        MediaItem.fromUri("android.resource://${context.packageName}/${R.raw.sample}")
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
                                c.setMediaItem(MediaItem.fromUri(url))
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
    }
}

@Preview(showBackground = true)
@Composable
fun PlayerScreenPreview() {
    MusicAppTheme {
        PlayerScreen()
    }
}