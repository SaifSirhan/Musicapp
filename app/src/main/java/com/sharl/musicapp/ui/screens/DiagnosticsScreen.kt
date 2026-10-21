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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sharl.musicapp.data.DiagnosticsManager
import com.sharl.musicapp.ui.components.SettingsSectionHeader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
    onReset: () -> Unit
) {
    val state by DiagnosticsManager.state.collectAsState()
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
                    Text("←", style = MaterialTheme.typography.headlineMedium)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text("Diagnostics", style = MaterialTheme.typography.headlineSmall)
        }
        HorizontalDivider()

        SettingsSectionHeader("Resolution")
        StatLine("InnerTube", state.lastInnerTube?.let { "${it.text}  ·  ${time(it.at)}" })
        StatLine("yt-dlp", state.lastYtdlp?.let { "${it.text}  ·  ${time(it.at)}" })
        StatLine("PlaybackCache hits", "${state.cacheHits} this session")
        StatLine("Last cache hit", state.lastCacheHit?.let { "${it.text}  ·  ${time(it.at)}" })
        StatLine("Prefetch", state.prefetch?.let { "${it.text}  ·  ${time(it.at)}" })

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onCopy(DiagnosticsManager.report()) }) { Text("Copy to Clipboard") }
            OutlinedButton(onClick = onReset) { Text("Clear") }
        }

        SettingsSectionHeader("Timeline")
        if (state.recent.isEmpty()) {
            Text(
                "Nothing recorded yet. Play a track.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            state.recent.forEach { event ->
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        time(event.at),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(64.dp)
                    )
                    Text(event.text, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String?) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            value ?: "No result yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun time(at: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at))
