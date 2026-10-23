package com.sharl.musicapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sharl.musicapp.data.AppDatabase
import com.sharl.musicapp.data.Download
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class DownloaderRemovalTest {
    @Test fun explicitRemovalDeletesOnlyAppAudioAndItsRow() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "remove-${UUID.randomUUID()}"
        val file = File(File(context.filesDir, "downloads").apply { mkdirs() }, "$id.m4a")
        file.writeBytes(byteArrayOf(1, 2, 3))
        val row = Download(id, file.absolutePath, 3, 0, "Test", "Artist", null)
        val dao = AppDatabase.get(context).downloadDao()
        dao.upsert(row)
        try {
            Downloader.remove(context, row)
            assertFalse(file.exists())
            assertNull(dao.get(id))
        } finally {
            file.delete()
            dao.delete(id)
        }
    }

    @Test fun removalNeverDeletesOutsideAudioRoot() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "outside-${UUID.randomUUID()}"
        val file = File(context.cacheDir, "$id.m4a")
        file.writeBytes(byteArrayOf(1, 2, 3))
        try {
            val row = Download(id, file.absolutePath, 3, 0, "Test", "Artist", null)
            runCatching { Downloader.remove(context, row) }
            assertTrue(file.isFile)
        } finally { file.delete() }
    }
}
