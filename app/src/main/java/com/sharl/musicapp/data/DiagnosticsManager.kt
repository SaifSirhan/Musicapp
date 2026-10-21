package com.sharl.musicapp.data

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Rolling record of every resolution path, surfaced by the hidden diagnostics
 * screen. In-memory only: it describes this process, and a restart is itself
 * the signal that something needed restarting.
 */
object DiagnosticsManager {

    data class Event(val at: Long, val text: String)

    data class State(
        val lastInnerTube: Event? = null,
        val lastYtdlp: Event? = null,
        val lastCacheHit: Event? = null,
        val cacheHits: Int = 0,
        val prefetch: Event? = null,
        val recent: List<Event> = emptyList()
    )

    private const val RECENT_LIMIT = 60

    private val _state = MutableStateFlow(State())
    val state: MutableStateFlow<State> = _state

    fun recordInnerTube(success: Boolean, elapsedMs: Long, detail: String) {
        val text = if (success) "Success in ${elapsedMs}ms · $detail" else "Failed in ${elapsedMs}ms · $detail"
        push("InnerTube: $text") { it.copy(lastInnerTube = Event(now(), text)) }
    }

    fun recordYtdlp(success: Boolean, elapsedMs: Long, detail: String = "") {
        val base = if (success) "Ok in ${elapsedMs}ms" else "Failed in ${elapsedMs}ms"
        val text = if (detail.isBlank()) base else "$base · $detail"
        push("yt-dlp: $text") { it.copy(lastYtdlp = Event(now(), text)) }
    }

    fun recordCacheHit(videoId: String) {
        push("Cache hit: $videoId") { s ->
            s.copy(lastCacheHit = Event(now(), videoId), cacheHits = s.cacheHits + 1)
        }
    }

    fun recordPrefetch(text: String) {
        push("Prefetch: $text") { it.copy(prefetch = Event(now(), text)) }
    }

    fun reset() {
        _state.value = State()
    }

    private fun push(line: String, transform: (State) -> State) {
        val event = Event(now(), line)
        _state.update { current ->
            transform(current).copy(recent = (listOf(event) + current.recent).take(RECENT_LIMIT))
        }
    }

    fun report(): String {
        val s = _state.value
        val sb = StringBuilder()
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        sb.appendLine("MusicApp ${com.sharl.musicapp.BuildConfig.VERSION_NAME} " +
            "(${com.sharl.musicapp.BuildConfig.VERSION_CODE}) · $stamp")
        sb.appendLine()
        sb.appendLine(s.lastInnerTube?.let { "InnerTube: ${it.text} (${formatAgo(it.at)})" }
            ?: "InnerTube: no result yet")
        sb.appendLine(s.lastYtdlp?.let { "yt-dlp: ${it.text} (${formatAgo(it.at)})" }
            ?: "yt-dlp: no result yet")
        sb.appendLine("PlaybackCache hits: ${s.cacheHits}")
        sb.appendLine(s.lastCacheHit?.let { "Last cache hit: ${it.text} (${formatAgo(it.at)})" }
            ?: "Last cache hit: none")
        sb.appendLine(s.prefetch?.let { "Prefetch: ${it.text} (${formatAgo(it.at)})" }
            ?: "Prefetch: idle")
        sb.appendLine()
        sb.appendLine("Timeline (newest first):")
        s.recent.forEach { sb.appendLine("${formatAgo(it.at).padStart(10)}  ${it.text}") }
        return sb.toString()
    }

    private fun now() = SystemClock.elapsedRealtime()

    private fun formatAgo(then: Long): String {
        val secs = ((now() - then) / 1000L).coerceAtLeast(0L)
        return when {
            secs < 60 -> "${secs}s"
            secs < 3600 -> "${secs / 60}m${secs % 60}s"
            else -> "${secs / 3600}h${(secs % 3600) / 60}m"
        }
    }
}
