package com.sharl.musicapp.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Privacy policy",
                style = MaterialTheme.typography.headlineSmall
            )
        }
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text(
            "MusicApp — Privacy Policy",
            style = MaterialTheme.typography.titleMedium
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
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Contact: saifsirhan01@gmail.com\ngithub.com/SaifSirhan/Musicapp",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
