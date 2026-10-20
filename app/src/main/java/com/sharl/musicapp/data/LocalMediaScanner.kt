package com.sharl.musicapp.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalMediaScanner(private val context: Context) {

    suspend fun scan(): List<LocalMedia> = withContext(Dispatchers.IO) {
        val out = ArrayList<LocalMedia>()
        out += query(
            collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            isVideo = false
        )
        out += query(
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            isVideo = true
        )
        out
    }

    private fun query(
        collection: android.net.Uri,
        isVideo: Boolean
    ): List<LocalMedia> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.TITLE,
            MediaStore.MediaColumns.DURATION,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME
        )
        val items = ArrayList<LocalMedia>()
        try {
            context.contentResolver.query(
                collection,
                projection,
                "${MediaStore.MediaColumns.DURATION} > 0",
                null,
                "${MediaStore.MediaColumns.TITLE} COLLATE NOCASE"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)
                val durCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
                val artistCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val nameCol = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, c.getLong(idCol)).toString()
                    val rawTitle = c.getString(titleCol)
                        ?.takeUnless { it.isBlank() || it == "<unknown>" }
                        ?: c.getString(nameCol)?.substringBeforeLast('.')
                        ?: continue
                    val artist = if (artistCol >= 0) {
                        c.getString(artistCol)?.takeUnless { it == "<unknown>" } ?: ""
                    } else ""
                    items += LocalMedia(
                        id = uri,
                        title = rawTitle,
                        artist = artist,
                        durationMs = c.getLong(durCol),
                        isVideo = isVideo
                    )
                }
            }
        } catch (_: Exception) {
            // Missing permission or a broken provider: return what we have.
        }
        return items
    }
}
