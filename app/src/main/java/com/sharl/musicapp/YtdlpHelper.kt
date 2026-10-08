package com.sharl.musicapp

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object YtdlpHelper {

    @Volatile
    private var initialized = false

    private fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            YoutubeDL.getInstance().init(context.applicationContext)
            initialized = true
        }
    }

    suspend fun extractAudioUrl(context: Context, videoUrl: String): String? =
        withContext(Dispatchers.IO) {
            ensureInitialized(context)

            val request = YoutubeDLRequest(videoUrl).apply {
                addOption("-f", "bestaudio")
                addOption("-g")
                addOption("--no-update")
                addOption("--no-warnings")
            }

            val response = YoutubeDL.getInstance().execute(request)
            val url = response.out
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }

            if (url == null) {
                Log.e("YTDL", "empty URL returned — SABR may be blocking. See Phase 2b.")
            }
            url
        }
}
