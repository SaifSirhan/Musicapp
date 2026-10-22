$ErrorActionPreference = "Stop"
$path = "app\src\main\java\com\sharl\musicapp\MainActivity.kt"
$dead = @(
    "import android.widget.Toast",
    "import androidx.compose.foundation.combinedClickable",
    "import androidx.compose.foundation.horizontalScroll",
    "import androidx.compose.foundation.rememberScrollState",
    "import androidx.compose.foundation.verticalScroll",
    "import androidx.compose.foundation.layout.Arrangement",
    "import androidx.compose.foundation.layout.aspectRatio",
    "import androidx.compose.foundation.lazy.LazyRow",
    "import androidx.compose.foundation.lazy.itemsIndexed",
    "import androidx.compose.foundation.lazy.rememberLazyListState",
    "import androidx.compose.foundation.shape.CircleShape",
    "import androidx.compose.material3.Button",
    "import androidx.compose.material3.FilterChip",
    "import androidx.compose.material3.OutlinedButton",
    "import androidx.compose.ui.text.style.TextAlign",
    "import com.sharl.musicapp.data.ImportPreview",
    "import com.sharl.musicapp.data.ImportSource",
    "import com.sharl.musicapp.data.ImportState",
    "import com.sharl.musicapp.data.ImportTrack",
    "import com.sharl.musicapp.data.NewDrop",
    "import com.sharl.musicapp.data.UpdateInfo",
    "import com.sharl.musicapp.ui.GoogleSignInButton",
    "import com.sharl.musicapp.ui.components.FriendsStrip",
    "import com.sharl.musicapp.ui.components.HorizontalCarousel",
    "import com.sharl.musicapp.ui.components.ImportTrackRow",
    "import com.sharl.musicapp.ui.components.NewDropCard",
    "import com.sharl.musicapp.ui.components.RecommendationRow",
    "import com.sharl.musicapp.ui.components.SettingsSectionHeader",
    "import com.sharl.musicapp.ui.components.SharedSongRow",
    "import com.sharl.musicapp.ui.components.SongRow",
    "import com.sharl.musicapp.ui.components.SpeedDialTile",
    "import com.sharl.musicapp.ui.screens.DownloadsScreen",
    "import com.sharl.musicapp.ui.screens.FriendsScreen",
    "import com.sharl.musicapp.ui.screens.PlaylistSongsContent",
    "import com.sharl.musicapp.ui.util.formatBytes",
    "import com.sharl.musicapp.ui.util.formatDate",
    "import com.sharl.musicapp.ui.util.formatTime",
    "import com.sharl.musicapp.ui.util.parseIsoMs"
)
$lines = [System.IO.File]::ReadAllLines($path)
$before = $lines.Count
$out = New-Object System.Collections.Generic.List[string]
$removed = 0
foreach ($l in $lines) {
    if ($dead -contains $l.Trim()) { $removed++ } else { $out.Add($l) }
}
[System.IO.File]::WriteAllLines($path, $out, [System.Text.UTF8Encoding]::new($false))
"REMOVED=$removed EXPECTED=38 BEFORE=$before AFTER=$($out.Count)"
