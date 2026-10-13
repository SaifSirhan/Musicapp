package com.sharl.musicapp.data

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class NewDropsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _drops = MutableStateFlow<List<NewDrop>>(emptyList())
    val drops: StateFlow<List<NewDrop>> = _drops
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val mutex = Mutex()

    @Volatile
    private var initialized = false

    fun lastFetchedAt(): Long = prefs.getLong("newdrops_last_fetch", 0L)

    fun isStale(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - lastFetchedAt() > 6L * 60 * 60 * 1000

    fun cached(): List<NewDrop> = _drops.value

    // Runs yt-dlp through the youtubedl-android library (the same binding the rest
    // of the app uses) — there is no standalone binary to ProcessBuilder here.
    private fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            YoutubeDL.getInstance().init(context.applicationContext)
            try {
                YoutubeDL.getInstance().updateYoutubeDL(context, UpdateChannel.NIGHTLY)
            } catch (e: Exception) {
                Log.e("NEWDROPS", "yt-dlp update failed", e)
            }
            initialized = true
        }
    }

    suspend fun refresh(context: Context, seedArtists: List<String>) {
        if (seedArtists.isEmpty()) return
        mutex.withLock {
            _loading.value = true
            try {
                withContext(Dispatchers.IO) {
                    ensureInitialized(context.applicationContext)
                    val acc = LinkedHashMap<String, NewDrop>()
                    for (artist in seedArtists.take(20)) {
                        try {
                            val request = YoutubeDLRequest("ytsearchdate5:$artist").apply {
                                addOption("--flat-playlist")
                                addOption("--dump-single-json")
                                addOption("--no-update")
                                addOption("--no-warnings")
                            }
                            val response = YoutubeDL.getInstance().execute(request)
                            for (d in parse(response.out, artist)) {
                                acc.putIfAbsent(d.videoId, d)
                            }
                        } catch (e: Exception) {
                            Log.e("NEWDROPS", "search failed for $artist: ${e.message}")
                        }
                    }
                    _drops.value = acc.values.take(100).toList()
                    prefs.edit().putLong("newdrops_last_fetch", System.currentTimeMillis()).apply()
                    Log.d("NEWDROPS", "refresh done, ${acc.size} drops")
                }
            } finally {
                _loading.value = false
            }
        }
    }

    private fun parse(out: String, seedArtist: String): List<NewDrop> {
        val json = out.trim()
        if (json.isEmpty()) return emptyList()
        return try {
            val entries = JSONObject(json).optJSONArray("entries") ?: return emptyList()
            val list = ArrayList<NewDrop>()
            for (i in 0 until entries.length()) {
                val e = entries.optJSONObject(i) ?: continue
                val id = e.optString("id", "")
                if (id.isEmpty()) continue
                val title = e.optString("title", "")
                val uploader = clean(e.optString("uploader", ""))
                    ?: clean(e.optString("channel", ""))
                val matches = title.contains(seedArtist, ignoreCase = true) ||
                    (uploader?.contains(seedArtist, ignoreCase = true) == true)
                if (!matches) continue
                val thumb = e.optJSONArray("thumbnails")
                    ?.takeIf { it.length() > 0 }
                    ?.let { arr -> clean(arr.optJSONObject(arr.length() - 1)?.optString("url", "") ?: "") }
                list.add(
                    NewDrop(
                        videoId = id,
                        title = title,
                        artist = seedArtist,
                        uploader = uploader,
                        thumbnailUrl = thumb,
                        durationMs = (e.optDouble("duration", 0.0) * 1000).toLong()
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.e("NEWDROPS", "json parse failed for $seedArtist: ${e.message}")
            emptyList()
        }
    }

    private fun clean(v: String): String? = v.takeIf { it.isNotEmpty() && it != "null" && it != "NA" }
}
