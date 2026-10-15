package com.sharl.musicapp.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.sharl.musicapp.BuildConfig
import com.sharl.musicapp.data.Profile
import com.sharl.musicapp.data.UpdateInfo
import com.sharl.musicapp.data.UpdateState
import com.sharl.musicapp.ui.components.SettingsSectionHeader
import com.sharl.musicapp.ui.util.formatBytes
import java.io.File

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
    accentColor: Color,
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
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineSmall
            )
        }
        HorizontalDivider()

        SettingsSectionHeader("About")
        Text(
            "MusicApp",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Made by Saif Sirhan",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "github.com/SaifSirhan/Musicapp",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable {
                openLink(context, "https://github.com/SaifSirhan/Musicapp")
            }
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "saifsirhan01@gmail.com",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable {
                openLink(context, "mailto:saifsirhan01@gmail.com")
            }
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider()

        // Account & Sync — always visible so the sign-in button is reachable.
        SettingsSectionHeader("Account & Sync")
        if (signedIn) {
            if (!syncConfigured) {
                Text(
                    "Sync isn't configured. Add SUPABASE_URL, SUPABASE_ANON_KEY and " +
                        "GOOGLE_WEB_CLIENT_ID to secrets.properties, then rebuild.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Signed in as ${accountEmail ?: "unknown"}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Invite code: ${inviteCode ?: "…"}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onCopyInvite) { Text("Copy") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onSignOut) { Text("Sign out") }
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
                TextButton(onClick = onAddFriend) { Text("Add") }
            }
            addFriendStatus?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            if (friends.isEmpty()) {
                Text(
                    "No friends yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                friends.forEach { f ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(
                            f.displayName ?: f.email ?: "Friend",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            f.email ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            // Signed out — show sign-in button (or config hint if sync isn't set up).
            if (!syncConfigured) {
                Text(
                    "Sync isn't configured. Add SUPABASE_URL, SUPABASE_ANON_KEY and " +
                        "GOOGLE_WEB_CLIENT_ID to secrets.properties, then rebuild.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Button(onClick = onSignIn) {
                    Text("Sign in with Google")
                }
            }
        }
        HorizontalDivider()

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
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(
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
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(accentColor, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "$accentName ▾",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
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
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Text("External")
        }
        Text(
            "Download folder: $downloadDirPath",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            "Downloads: $downloadCount songs, ${formatBytes(totalBytes)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider()

        SettingsSectionHeader("Updates")
        Text(
            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        when (val s = updateState) {
            is UpdateState.Checking -> {
                Text(
                    "Checking for updates…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is UpdateState.Available -> {
                Text(
                    "Update available: ${s.info.versionName}",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (s.info.notes.isNotBlank()) {
                    Text(
                        s.info.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onDownloadUpdate(s.info) }) { Text("Download update") }
            }
            is UpdateState.Downloading -> {
                Text(
                    "Downloading… ${s.percent}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onInstallUpdate(s.file) }) { Text("Install update") }
            }
            is UpdateState.Failed -> {
                Text(
                    s.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onCheckForUpdates) { Text("Check for updates") }
            }
            is UpdateState.UpToDate -> {
                Text(
                    "You're up to date.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Clear library") }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onRemoveAll,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Remove all downloads") }
    }
}

internal fun openLink(context: Context, uri: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)))
    }
}
