package com.sharl.musicapp.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CachedStream(
    val url: String, // Now always a local file URI (file://)
    val title: String,
    val artist: String,
    val album: String?,
    val thumbnailUrl: String?
)

/**
 * Phase 42/43: Metadata cache for local files. In-memory LRU (max 30), backed by
 * SharedPreferences so the hot set survives an app restart. URLs are now always
 * local file paths from Downloader.cacheForPlayback(), so no TTL needed.
 */
object PlaybackCache {
    private const val PREFS = "playback_cache"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 30

    private val mem = LinkedHashMap<String, CachedStream>()
    private var appContext: Context? = null

    @Synchronized
    fun init(context: Context) {
        val ctx = context.applicationContext
        // Reloading would drop entries published earlier in this process, such as a
        // download that finished before the service was ever created.
        if (appContext != null) return
        appContext = ctx
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        val persisted = LinkedHashMap<String, CachedStream>()
        if (raw != null) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    val url = o.optString("url")
                    if (id.isBlank() || url.isBlank()) continue
                    persisted[id] = CachedStream(
                        url = url,
                        title = o.optString("title"),
                        artist = o.optString("artist"),
                        album = o.optString("album").ifBlank { null },
                        thumbnailUrl = o.optString("thumbnail").ifBlank { null }
                    )
                }
            } catch (_: Exception) {
                persisted.clear()
            }
        }
        persisted.putAll(mem)
        mem.clear()
        mem.putAll(persisted)
        save()
    }

    @Synchronized
    fun get(videoId: String): CachedStream? {
        val e = mem[videoId] ?: return null
        // LRU touch
        mem.remove(videoId)
        mem[videoId] = e
        DiagnosticsManager.recordCacheHit(videoId)
        return e
    }

    @Synchronized
    fun put(
        videoId: String,
        url: String,
        title: String,
        artist: String,
        album: String?,
        thumbnailUrl: String?
    ) {
        if (videoId.isBlank() || url.isBlank()) return
        mem.remove(videoId)
        mem[videoId] = CachedStream(
            url = url,
            title = title,
            artist = artist,
            album = album,
            thumbnailUrl = thumbnailUrl
        )
        while (mem.size > MAX_ENTRIES) {
            val oldest = mem.keys.firstOrNull() ?: break
            mem.remove(oldest)
        }
        save()
    }

    /** Drops one entry, e.g. after ExoPlayer reports the CDN URL is 403. */
    @Synchronized
    fun remove(videoId: String) {
        if (mem.remove(videoId) != null) save()
    }

    @Synchronized
    fun clear(context: Context) {
        mem.clear()
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }

    private fun save() {
        val ctx = appContext ?: return
        val arr = JSONArray()
        for ((id, e) in mem) {
            arr.put(JSONObject().apply {
                put("id", id)
                put("url", e.url)
                put("title", e.title)
                put("artist", e.artist)
                put("album", e.album ?: "")
                put("thumbnail", e.thumbnailUrl ?: "")
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
