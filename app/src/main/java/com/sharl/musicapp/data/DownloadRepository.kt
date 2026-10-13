package com.sharl.musicapp.data

import android.content.Context

class DownloadRepository(context: Context) {
    private val dao = AppDatabase.get(context).downloadDao()

    fun all() = dao.all()
    fun allIds() = dao.allIds()
    suspend fun get(videoId: String) = dao.get(videoId)
    suspend fun upsert(d: Download) = dao.upsert(d)
    suspend fun delete(videoId: String) = dao.delete(videoId)
    fun totalBytes() = dao.totalBytes()
}
