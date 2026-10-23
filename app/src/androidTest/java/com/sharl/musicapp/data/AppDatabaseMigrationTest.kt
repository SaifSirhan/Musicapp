package com.sharl.musicapp.data

import kotlinx.coroutines.async
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private companion object {
        const val DB_NAME = "migration-test.db"
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate13To14AddsDuplicateIdentityWithSafeDefaults() {
        helper.createDatabase(DB_NAME, 13).use { db ->
            db.execSQL(
                "INSERT INTO songs (videoId, title, artist, lastPlayedAt, playCount, isFavorite, isHidden) " +
                    "VALUES ('old', 'Old Song', 'Old Artist', 5, 2, 0, 0)"
            )
        }

        helper.runMigrationsAndValidate(
            DB_NAME,
            14,
            true,
            AppDatabase.MIGRATION_13_14
        ).use { db ->
            db.query(
                "SELECT title, durationMs, normalizedTitle, normalizedTitleVersion " +
                    "FROM songs WHERE videoId = 'old'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Old Song", cursor.getString(0))
                assertEquals(0L, cursor.getLong(1))
                assertEquals("", cursor.getString(2))
                assertEquals(0, cursor.getInt(3))
            }

            var hasIndex = false
            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' " +
                    "AND name = 'index_songs_normalizedTitle'"
            ).use { cursor -> hasIndex = cursor.moveToFirst() }
            assertTrue("normalizedTitle index missing", hasIndex)
        }
    }

    @Test
    fun concurrentClaimsHaveOneOwnerAndStaleCompletionCannotWin() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.downloadJobDao()
            val seed = DownloadJob("claim", "Title", "Artist", null, 0, "QUEUED", 0, 1, 1)
            val claims = kotlinx.coroutines.coroutineScope {
                (1..8).map {
                    async(kotlinx.coroutines.Dispatchers.IO) { dao.claim(seed, 2) }
                }.map { it.await() }
            }
            assertEquals(1, claims.count { it != null })
            assertEquals("DOWNLOADING", dao.get("claim")!!.status)
            assertEquals(1, dao.get("claim")!!.attempts)
            dao.upsert(seed)
            assertEquals("DOWNLOADING", dao.get("claim")!!.status)
            assertEquals(0, dao.finish("claim", "COMPLETED", 0, 3, null))
            assertEquals(1, dao.finish("claim", "WAITING_FOR_SPACE", 1, 3, null))
            assertEquals(listOf("claim"), dao.resumable().map { it.videoId })
            assertEquals(2, dao.claim(seed, 4)!!.attempts)
            assertEquals(0, dao.finish("claim", "COMPLETED", 1, 5, null))
            assertEquals("DOWNLOADING", dao.get("claim")!!.status)
        } finally { db.close() }
    }

    @Test
    fun migrate16To17PreservesJobsWithUnknownDestination() {
        helper.createDatabase(DB_NAME, 16).use { db ->
            db.execSQL("INSERT INTO download_jobs VALUES ('old', 'Title', 'Artist', NULL, 1000, 'COMPLETED', 3, 10, 20, NULL)")
        }
        helper.runMigrationsAndValidate(DB_NAME, 17, true, AppDatabase.MIGRATION_16_17).use { db ->
            db.query("SELECT status, attempts, destinationDir FROM download_jobs WHERE videoId = 'old'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("COMPLETED", cursor.getString(0))
                assertEquals(3, cursor.getInt(1))
                assertTrue(cursor.isNull(2))
            }
        }
    }

    @Test
    fun discardQueuedCannotRemoveLiveOrCompletedJobs() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.downloadJobDao()
            for (status in listOf("QUEUED", "WAITING_FOR_SPACE", "FAILED", "DOWNLOADING", "COMPLETED")) {
                val seed = DownloadJob(status, "Title", "Artist", null, 0, status, 1, 1, 1,
                    destinationDir = "/original")
                dao.upsert(seed)
                dao.upsert(seed.copy(title = "Replacement", destinationDir = "/changed"))
                dao.bindDestination(status, "/changed")
                assertEquals("/original", dao.get(status)!!.destinationDir)
                assertEquals("Title", dao.get(status)!!.title)
                assertEquals(if (status in listOf("DOWNLOADING", "COMPLETED")) 0 else 1, dao.discardQueued(status))
            }
        } finally { db.close() }
    }

    @Test
    fun migrate15To16PreservesJobsAndRenamesRunning() {
        helper.createDatabase(DB_NAME, 15).use { db ->
            for (status in listOf("RUNNING", "QUEUED", "WAITING_FOR_SPACE", "COMPLETED", "FAILED")) {
                db.execSQL("INSERT INTO download_jobs VALUES (?, 'Title', 'Artist', NULL, 1000, ?, 3, 10, 20, NULL)", arrayOf(status, status))
            }
        }
        helper.runMigrationsAndValidate(DB_NAME, 16, true, AppDatabase.MIGRATION_15_16).use { db ->
            db.query("SELECT videoId, status, attempts, requestedAt FROM download_jobs").use { cursor ->
                assertEquals(5, cursor.count)
                while (cursor.moveToNext()) {
                    val old = cursor.getString(0)
                    assertEquals(if (old == "RUNNING") "DOWNLOADING" else old, cursor.getString(1))
                    assertEquals(3, cursor.getInt(2))
                    assertEquals(10L, cursor.getLong(3))
                }
            }
        }
    }

    @Test
    fun migrate14To15AddsDownloadJobsTable() {
        helper.createDatabase(DB_NAME, 14).use { db ->
            db.execSQL(
                "INSERT INTO songs (videoId, title, artist, lastPlayedAt, playCount, isFavorite, isHidden, " +
                    "durationMs, normalizedTitle, normalizedTitleVersion) " +
                    "VALUES ('keep', 'Kept Song', 'Artist', 1, 0, 0, 0, 0, '', 0)"
            )
        }

        helper.runMigrationsAndValidate(
            DB_NAME,
            15,
            true,
            AppDatabase.MIGRATION_14_15
        ).use { db ->
            db.execSQL(
                "INSERT INTO download_jobs " +
                    "(videoId, title, artist, thumbnailUrl, durationMs, status, attempts, requestedAt, updatedAt, lastError) " +
                    "VALUES ('job', 'T', 'A', NULL, 1000, 'QUEUED', 0, 1, 1, NULL)"
            )
            db.query("SELECT status, attempts FROM download_jobs WHERE videoId = 'job'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("QUEUED", cursor.getString(0))
                    assertEquals(0, cursor.getInt(1))
                }
            db.query("SELECT title FROM songs WHERE videoId = 'keep'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Kept Song", cursor.getString(0))
            }
            var hasIndex = false
            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' " +
                    "AND name = 'index_download_jobs_status'"
            ).use { cursor -> hasIndex = cursor.moveToFirst() }
            assertTrue("download_jobs status index missing", hasIndex)
        }
    }
}
