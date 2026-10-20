package com.sharl.musicapp.data

import android.content.Context

// Artists the user explicitly follows. Kept in SharedPreferences: it's a small
// local preference list, not relational data.
class FollowRepository(context: Context) {
    private val prefs = context.getSharedPreferences("follows", Context.MODE_PRIVATE)

    fun artists(): List<String> =
        prefs.getStringSet(KEY, emptySet()).orEmpty().sortedBy { it.lowercase() }

    fun isFollowing(artist: String): Boolean =
        artists().any { it.equals(artist.trim(), ignoreCase = true) }

    fun toggle(artist: String): Boolean {
        val name = artist.trim()
        if (name.isEmpty()) return false
        val current = artists().toMutableSet()
        val existing = current.firstOrNull { it.equals(name, ignoreCase = true) }
        val following = existing == null
        if (following) current.add(name) else current.remove(existing)
        prefs.edit().putStringSet(KEY, current.toSet()).apply()
        return following
    }

    private companion object {
        const val KEY = "followed_artists"
    }
}
