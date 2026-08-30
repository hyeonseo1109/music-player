package com.hendo.hendomusic.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TrackEntity::class, LyricsEntity::class, LyricLineEntity::class, AlbumFolderEntity::class,
        UserAlbumEntity::class, AlbumTrackEntity::class, PlaybackHistoryEntity::class,
        PlaybackSessionEntity::class, PlaybackQueueEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE tracks ADD COLUMN customArtworkSource TEXT")
                database.execSQL("ALTER TABLE tracks ADD COLUMN autoArtworkUri TEXT")
                database.execSQL("ALTER TABLE tracks ADD COLUMN autoArtworkSource TEXT")
                // Existing local artwork was set through the old user-facing editor.
                database.execSQL("UPDATE tracks SET customArtworkSource = 'USER' WHERE customArtworkUri IS NOT NULL")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE album_folders ADD COLUMN artworkUri TEXT")
            }
        }
        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext, AppDatabase::class.java, "luminara.db"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).fallbackToDestructiveMigration(false).build()
    }
}
