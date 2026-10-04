package com.hendo.hendomusic

import android.app.Application
import com.hendo.hendomusic.data.AppDatabase
import com.hendo.hendomusic.data.PreferencesRepository
import com.hendo.hendomusic.library.MusicRepository
import com.hendo.hendomusic.metadata.AutoMetadataEnricher
import com.hendo.hendomusic.ambient.AmbientPaletteRepository

class LuminaraApplication : Application() {
    lateinit var container: AppContainer
        private set
    override fun onCreate() {
        super.onCreate()
        val database = AppDatabase.create(this)
    container = AppContainer(
        database,
        PreferencesRepository(this),
        MusicRepository(this, database.dao()),
        AutoMetadataEnricher(database.dao()),
        AmbientPaletteRepository(this),
    )
    }
}

data class AppContainer(
    val database: AppDatabase,
    val preferences: PreferencesRepository,
    val musicRepository: MusicRepository,
    val metadataEnricher: AutoMetadataEnricher,
    val ambientPaletteRepository: AmbientPaletteRepository,
)
