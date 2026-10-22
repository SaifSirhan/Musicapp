package com.sharl.musicapp.data

import android.content.Context

class SettingsRepo(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var storageExternal: Boolean
        get() = prefs.getBoolean("storage_external", false)
        set(v) { prefs.edit().putBoolean("storage_external", v).apply() }

    var dismissUpdateForVersion: Int
        get() = prefs.getInt("dismiss_update_for_version", 0)
        set(v) { prefs.edit().putInt("dismiss_update_for_version", v).apply() }

    var notifyUpdates: Boolean
        get() = prefs.getBoolean("notify_updates", true)
        set(v) { prefs.edit().putBoolean("notify_updates", v).apply() }

    // Phase 22 playback customisation. The keys are watched by PlaybackService,
    // so writing them is enough to apply a change to the running player.
    var skipSeconds: Int
        get() = prefs.getInt("skip_seconds", 15)
        set(v) { prefs.edit().putInt("skip_seconds", v.coerceIn(5, 30)).apply() }

    var playbackSpeed: Float
        get() = prefs.getFloat("playback_speed", 1f).coerceIn(0.5f, 5f)
        set(v) { prefs.edit().putFloat("playback_speed", v.coerceIn(0.5f, 5f)).apply() }

    var crossfadeSeconds: Int
        get() = prefs.getInt("crossfade_seconds", 0)
        set(v) { prefs.edit().putInt("crossfade_seconds", v.coerceIn(0, 30)).apply() }

    var normalizeVolume: Boolean
        get() = prefs.getBoolean("normalize_volume", false)
        set(v) { prefs.edit().putBoolean("normalize_volume", v).apply() }

    // OAuth access token for the YouTube Data API (account import). Google
    // access tokens live ~1h; storedAt lets us treat stale ones as missing.
    var googleAccessToken: String
        get() = prefs.getString("google_access_token", "").orEmpty()
        set(v) {
            prefs.edit()
                .putString("google_access_token", v)
                .putLong("google_access_token_at", System.currentTimeMillis())
                .apply()
        }

    var googleAccessTokenAt: Long
        get() = prefs.getLong("google_access_token_at", 0L)
        set(v) { prefs.edit().putLong("google_access_token_at", v).apply() }

    fun hasFreshGoogleToken(): Boolean =
        googleAccessToken.isNotBlank() &&
            System.currentTimeMillis() - googleAccessTokenAt < 50 * 60 * 1000L

    fun clearGoogleAccessToken() {
        prefs.edit()
            .remove("google_access_token")
            .remove("google_access_token_at")
            .apply()
    }

    fun downloadDir(context: Context): java.io.File {
        val base = if (storageExternal) context.getExternalFilesDir(null)
                   else context.filesDir
        return java.io.File(base, "downloads").apply { mkdirs() }
    }
}
