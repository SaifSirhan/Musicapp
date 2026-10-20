package com.sharl.musicapp.data

import android.content.Context

// Local diagnostics for the InnerTube fast path. When YouTube rotates its
// client config the fast call starts missing; this counter makes that visible
// in Settings before the user notices the slow fallback.
object ResolutionStats {
    private const val PREFS = "resolution_stats"
    private const val KEY_INNERTUBE_FAILS = "innertube_fail_count"
    private const val KEY_LAST_FAST = "last_engine_fast"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun innerTubeFailCount(context: Context): Int =
        prefs(context).getInt(KEY_INNERTUBE_FAILS, 0)

    fun lastWasFast(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LAST_FAST, true)

    fun recordFast(context: Context) {
        prefs(context).edit().putBoolean(KEY_LAST_FAST, true).apply()
    }

    fun recordFallback(context: Context) {
        val p = prefs(context)
        p.edit()
            .putInt(KEY_INNERTUBE_FAILS, p.getInt(KEY_INNERTUBE_FAILS, 0) + 1)
            .putBoolean(KEY_LAST_FAST, false)
            .apply()
    }
}
