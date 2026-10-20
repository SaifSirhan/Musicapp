package com.sharl.musicapp.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class LocalMediaRepository(context: Context) {
    private val dao = AppDatabase.get(context).localMediaDao()
    private val scanner = LocalMediaScanner(context)

    fun all(): Flow<List<LocalMedia>> = dao.all()

    suspend fun refresh(): Int {
        val found = scanner.scan()
        if (found.isEmpty()) {
            dao.clearAll()
        } else {
            dao.upsertAll(found)
            dao.deleteMissing(found.map { it.id })
        }
        return found.size
    }
}
