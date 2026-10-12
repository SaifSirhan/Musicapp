package com.sharl.musicapp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LyricLine(val startMs: Long, val text: String)

object LyricsHelper {

    // In-memory cache: key = "artist|title"
    private val cache = mutableMapOf<String, List<LyricLine>>()

    suspend fun fetch(artist: String, title: String): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            val cleanedTitle = cleanTitle(title)
            val cleanedArtist = cleanArtist(artist)
            val key = "$cleanedArtist|$cleanedTitle".lowercase()

            cache[key]?.let { return@withContext it }

            val url = "https://lrclib.net/api/get?" +
                "artist_name=" + URLEncoder.encode(cleanedArtist, "UTF-8") +
                "&track_name=" + URLEncoder.encode(cleanedTitle, "UTF-8")

            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent",
                        "MusicApp v1.0 (https://github.com/SaifSirhan/Musicapp)")
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }
                val code = conn.responseCode
                if (code == 404) return@withContext null
                if (code == 429) {
                    val retry = conn.getHeaderField("Retry-After")
                    Log.w("LYRICS", "429 rate limited, retry-after=$retry")
                    return@withContext null
                }
                if (code != 200) {
                    Log.w("LYRICS", "unexpected code $code")
                    return@withContext null
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val synced = json.optString("syncedLyrics", "")
                if (synced.isBlank()) {
                    // Fall back to plain lyrics as a single block
                    val plain = json.optString("plainLyrics", "")
                    if (plain.isBlank()) return@withContext null
                    val lines = plain.lines().map { LyricLine(0L, it) }
                    cache[key] = lines
                    return@withContext lines
                }
                val parsed = parseLrc(synced)
                if (parsed.isNotEmpty()) cache[key] = parsed
                parsed.ifEmpty { null }
            } catch (e: Exception) {
                Log.e("LYRICS", "fetch failed: ${e.message}", e)
                null
            }
        }

    // Strip common YouTube title noise
    private fun cleanTitle(t: String): String {
        var s = t
        val stripPatterns = listOf(
            Regex("""\s*[\(\[]\s*(Official\s+)?(HD\s+)?(Music\s+)?Video\s*[\)\]]""", RegexOption.IGNORE_CASE),
            Regex("""\s*[\(\[]\s*Official\s+Audio\s*[\)\]]""", RegexOption.IGNORE_CASE),
            Regex("""\s*[\(\[]\s*Lyrics?\s*[\)\]]""", RegexOption.IGNORE_CASE),
            Regex("""\s*[\(\[]\s*HD\s*[\)\]]""", RegexOption.IGNORE_CASE),
            Regex("""\s*[\(\[]\s*Audio\s*[\)\]]""", RegexOption.IGNORE_CASE)
        )
        stripPatterns.forEach { s = s.replace(it, "") }
        // Strip "Artist - " prefix if present
        if (s.contains(" - ")) {
            s = s.substringAfter(" - ").trim()
        }
        return s.trim()
    }

    private fun cleanArtist(a: String): String =
        a.removeSuffix(" - Topic").trim()

    // LRC format: [mm:ss.xx] text  or  [mm:ss.xxx]
    private val lrcRegex = Regex("""\[(\d+):(\d+)(?:\.(\d+))?](.*)""")

    private fun parseLrc(raw: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        raw.lines().forEach { line ->
            val m = lrcRegex.find(line.trim()) ?: return@forEach
            val min = m.groupValues[1].toLongOrNull() ?: return@forEach
            val sec = m.groupValues[2].toLongOrNull() ?: return@forEach
            val fracRaw = m.groupValues[3]
            val ms = when {
                fracRaw.isEmpty() -> 0L
                fracRaw.length == 2 -> fracRaw.toLong() * 10
                fracRaw.length == 3 -> fracRaw.toLong()
                else -> fracRaw.padEnd(3, '0').substring(0, 3).toLong()
            }
            val text = m.groupValues[4].trim()
            if (text.isNotEmpty()) {
                out.add(LyricLine(min * 60_000 + sec * 1000 + ms, text))
            }
        }
        return out.sortedBy { it.startMs }
    }
}
