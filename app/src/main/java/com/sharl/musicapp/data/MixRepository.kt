package com.sharl.musicapp.data

import android.content.Context
import android.util.Log

// Phase 42: Mix generation disabled - depended on InnerTube streaming.
// Local-first architecture only plays downloaded content.
class MixRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.get(context).songDao()

    // Phase 42: Disabled - requires InnerTube streaming
    suspend fun generateMix(seed: Song): List<Song> {
        Log.w("MIX", "Mix generation disabled (local-first only)")
        return listOf(seed)
    }
}
