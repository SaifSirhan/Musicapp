package com.sharl.musicapp.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.sharl.musicapp.data.ImportPreview
import com.sharl.musicapp.data.ImportSource
import com.sharl.musicapp.data.ImportState
import com.sharl.musicapp.ui.components.ImportTrackRow

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

    LaunchedEffect(state) {
        if (state is ImportState.Previewing) importedName = state.preview.sourceName
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onSourceChosen(ImportSource.CsvFile(uri)) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = onDismiss) {
                    Text("←", style = MaterialTheme.typography.titleLarge)
                }
            }
            Text(
                "Import",
                style = MaterialTheme.typography.headlineSmall,
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
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                LinearProgressIndicator(
                    progress = {
                        if (state.total > 0) state.done.toFloat() / state.total else 0f
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(24.dp))
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }

            is ImportState.Previewing -> {
                val preview = state.preview
                Text(
                    "${preview.sourceName} · ${preview.tracks.size + preview.unresolved.size} tracks",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    item {
                        Text(
                            "Matched (${preview.tracks.size})",
                            style = MaterialTheme.typography.titleSmall,
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
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                            )
                        }
                        item {
                            Text(
                                "These couldn't be matched on YouTube and won't be imported.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                LinearProgressIndicator(
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
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { onOpenPlaylist(state.playlistId) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open playlist") }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss) { Text("Done") }
            }

            is ImportState.Error -> Column(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    state.message,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
        }
    }
}
