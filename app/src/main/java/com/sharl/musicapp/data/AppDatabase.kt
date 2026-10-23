package com.sharl.musicapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Song::class, Playlist::class, PlaylistSongCrossRef::class, SpeedDialSlot::class, Download::class, LocalMedia::class, DownloadJob::class],
    version = 17,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun speedDialDao(): SpeedDialDao
    abstract fun downloadDao(): DownloadDao
    abstract fun localMediaDao(): LocalMediaDao
    abstract fun downloadJobDao(): DownloadJobDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `local_media` (" +
                        "`id` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`artist` TEXT NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, " +
                        "`isVideo` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        // Phase 44: Add PlaylistSongCrossRef junction table for Many-to-Many
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playlist_song_cross_ref` (" +
                        "`playlistId` INTEGER NOT NULL, " +
                        "`videoId` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL DEFAULT 0, " +
                        "`addedAt` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`playlistId`, `videoId`), " +
                        "FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON DELETE CASCADE, " +
                        "FOREIGN KEY(`videoId`) REFERENCES `songs`(`videoId`) ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_song_cross_ref_videoId` ON `playlist_song_cross_ref` (`videoId`)")
            }
        }

        // Phase 44b: Drop old playlist_songs table, migrate data to cross_ref
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Migrate existing data from playlist_songs to playlist_song_cross_ref
                db.execSQL("""
                    INSERT OR IGNORE INTO playlist_song_cross_ref (playlistId, videoId, position, addedAt)
                    SELECT playlistId, videoId, 0, addedAt FROM playlist_songs
                """)
                // Drop old table
                db.execSQL("DROP TABLE IF EXISTS playlist_songs")
            }
        }

        // Phase 48: track order for downloaded album playlists.
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN trackNumber INTEGER")
            }
        }

        // Phase 50: duplicate identity needs a normalized title, a duration to
        // disambiguate same-name recordings, and a version so old rows can be
        // re-normalized after rule changes.
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE songs ADD COLUMN normalizedTitle TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE songs ADD COLUMN normalizedTitleVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_normalizedTitle` ON `songs` (`normalizedTitle`)")
            }
        }

        // Phase 50: durable download jobs so interrupted work can be resumed and
        // completed audio can be re-linked after process death.
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `download_jobs` (" +
                        "`videoId` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`artist` TEXT NOT NULL, " +
                        "`thumbnailUrl` TEXT, " +
                        "`durationMs` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`attempts` INTEGER NOT NULL, " +
                        "`requestedAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`lastError` TEXT, " +
                        "PRIMARY KEY(`videoId`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_jobs_status` ON `download_jobs` (`status`)")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE download_jobs SET status = 'DOWNLOADING' WHERE status = 'RUNNING'")
                // Legacy errors may contain raw extractor output or private filesystem paths.
                db.execSQL("UPDATE download_jobs SET lastError = NULL")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_jobs ADD COLUMN destinationDir TEXT")
            }
        }

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "musicapp.db"
                )
                    .addMigrations(
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17
                    )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
