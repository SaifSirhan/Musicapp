package com.sharl.musicapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Song::class, Playlist::class, PlaylistSongCrossRef::class, SpeedDialSlot::class, Download::class, LocalMedia::class],
    version = 13,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun speedDialDao(): SpeedDialDao
    abstract fun downloadDao(): DownloadDao
    abstract fun localMediaDao(): LocalMediaDao

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

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "musicapp.db"
                )
                    .addMigrations(MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
