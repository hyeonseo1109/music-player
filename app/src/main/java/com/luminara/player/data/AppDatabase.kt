package com.luminara.player.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TrackEntity::class, LyricsEntity::class, LyricLineEntity::class, AlbumFolderEntity::class,
        UserAlbumEntity::class, AlbumTrackEntity::class, PlaybackHistoryEntity::class,
        PlaybackSessionEntity::class, PlaybackQueueEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext, AppDatabase::class.java, "luminara.db"
        ).fallbackToDestructiveMigration(false).build()
    }
}
