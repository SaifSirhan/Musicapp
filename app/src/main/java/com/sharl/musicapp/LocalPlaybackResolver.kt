package com.sharl.musicapp

import android.content.Context
import android.net.Uri
import com.sharl.musicapp.data.AppDatabase
import com.sharl.musicapp.data.PlaybackCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Never extracts or downloads. Only app-owned files and explicit MediaStore entries are playable. */
internal class LocalPlaybackResolver(context: Context) {
    private val context = context.applicationContext

    suspend fun resolve(item: QueueItem): QueueItem? = withContext(Dispatchers.IO) {
        if (item.localUri != null) {
            val uri = Uri.parse(item.localUri)
            if (!isMediaStore(uri) || (item.videoId != item.localUri && !validVideoId(item.videoId)))
                return@withContext null
            val readable = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
            }.getOrDefault(false)
            return@withContext item.takeIf { readable }
        }
        if (!validVideoId(item.videoId)) return@withContext null
        val download = AppDatabase.get(context).downloadDao().get(item.videoId)
        val cached = PlaybackCache.get(item.videoId)
        val file = listOfNotNull(download?.filePath, cached?.url)
            .firstNotNullOfOrNull { ownedFile(it) } ?: return@withContext null
        item.copy(
            title = item.title.ifBlank { download?.title ?: cached?.title ?: item.videoId },
            artist = item.artist.ifBlank { download?.artist ?: cached?.artist.orEmpty() },
            // File URIs are service-produced, never accepted as explicit incoming localUri.
            localUri = Uri.fromFile(file).toString(),
        )
    }

    private fun ownedFile(path: String): File? = runCatching {
        val uri = Uri.parse(path)
        if (uri.scheme != null && uri.scheme != "file") return null
        val file = File(if (uri.scheme == "file") uri.path ?: return null else path).canonicalFile
        val roots = listOfNotNull(context.filesDir, context.cacheDir,
            context.getExternalFilesDir(null), context.externalCacheDir)
        if (roots.none { file.path.startsWith(it.canonicalPath + File.separator) }) return null
        file.takeIf { it.isFile && it.canRead() && it.length() > 0 }
    }.getOrNull()

    private fun isMediaStore(uri: Uri): Boolean {
        val parts = uri.pathSegments
        return uri.scheme == "content" && uri.authority == "media" &&
            parts.size == 4 && parts[0] in setOf("external", "external_primary", "internal") &&
            parts[1] in setOf("audio", "video") && parts[2] == "media" &&
            parts[3].toLongOrNull()?.let { it >= 0 } == true && uri.query == null && uri.fragment == null
    }

    private fun validVideoId(id: String) = Regex("[A-Za-z0-9_-]{11}").matches(id)
}
