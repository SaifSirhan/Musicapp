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

    fun downloadDir(context: Context): java.io.File {
        val base = if (storageExternal) context.getExternalFilesDir(null)
                   else context.filesDir
        return java.io.File(base, "downloads").apply { mkdirs() }
    }
}
