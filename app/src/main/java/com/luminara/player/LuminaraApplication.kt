package com.luminara.player

import android.app.Application
import com.luminara.player.data.AppDatabase
import com.luminara.player.data.PreferencesRepository
import com.luminara.player.library.MusicRepository

class LuminaraApplication : Application() {
    lateinit var container: AppContainer
        private set
    override fun onCreate() {
        super.onCreate()
        val database = AppDatabase.create(this)
        container = AppContainer(database, PreferencesRepository(this), MusicRepository(this, database.dao()))
    }
}

data class AppContainer(
    val database: AppDatabase,
    val preferences: PreferencesRepository,
    val musicRepository: MusicRepository,
)
