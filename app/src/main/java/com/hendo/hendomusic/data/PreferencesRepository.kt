package com.hendo.hendomusic.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

enum class ThemeMode { DARK, LIGHT, SYSTEM }
enum class ScanMode { MEDIA_STORE, SELECTED_FOLDERS }

data class AppSettings(
    val onboardingDone: Boolean = false,
    val theme: ThemeMode = ThemeMode.DARK,
    val scanMode: ScanMode = ScanMode.MEDIA_STORE,
    val sort: String = "TITLE",
    val floatingLyrics: Boolean = false,
    val floatingLines: Int = 2,
    val floatingFontSize: Int = 16,
    val floatingAlpha: Float = .72f,
    val overlayX: Int = 24,
    val overlayY: Int = 240,
    val keepScreenOn: Boolean = false,
    val trackListening: Boolean = true,
    val treeUris: Set<String> = emptySet(),
)

class PreferencesRepository(private val context: Context) {
    private object Keys {
        val onboarding = booleanPreferencesKey("onboarding")
        val theme = stringPreferencesKey("theme")
        val scanMode = stringPreferencesKey("scan_mode")
        val sort = stringPreferencesKey("sort")
        val floating = booleanPreferencesKey("floating")
        val lines = intPreferencesKey("floating_lines")
        val fontSize = intPreferencesKey("floating_font_size")
        val alpha = floatPreferencesKey("floating_alpha")
        val x = intPreferencesKey("overlay_x")
        val y = intPreferencesKey("overlay_y")
        val keepOn = booleanPreferencesKey("keep_on")
        val trackListening = booleanPreferencesKey("track_listening")
        val trees = stringSetPreferencesKey("tree_uris")
    }
    val settings = context.dataStore.data.map { p ->
        AppSettings(
            p[Keys.onboarding] ?: false,
            runCatching { ThemeMode.valueOf(p[Keys.theme] ?: "DARK") }.getOrDefault(ThemeMode.DARK),
            runCatching { ScanMode.valueOf(p[Keys.scanMode] ?: "MEDIA_STORE") }.getOrDefault(ScanMode.MEDIA_STORE),
            p[Keys.sort] ?: "TITLE", p[Keys.floating] ?: false, p[Keys.lines] ?: 2,
            p[Keys.fontSize] ?: 16, p[Keys.alpha] ?: .72f, p[Keys.x] ?: 24, p[Keys.y] ?: 240,
            p[Keys.keepOn] ?: false, p[Keys.trackListening] ?: true, p[Keys.trees] ?: emptySet()
        )
    }
    suspend fun completeOnboarding() = context.dataStore.edit { it[Keys.onboarding] = true }
    suspend fun setTheme(value: ThemeMode) = context.dataStore.edit { it[Keys.theme] = value.name }
    suspend fun setScanMode(value: ScanMode) = context.dataStore.edit { it[Keys.scanMode] = value.name }
    suspend fun setSort(value: String) = context.dataStore.edit { it[Keys.sort] = value }
    suspend fun setFloating(value: Boolean) = context.dataStore.edit { it[Keys.floating] = value }
    suspend fun setFloatingLines(value: Int) = context.dataStore.edit { it[Keys.lines] = value.coerceIn(1, 3) }
    suspend fun setKeepScreenOn(value: Boolean) = context.dataStore.edit { it[Keys.keepOn] = value }
    suspend fun setTrackListening(value: Boolean) = context.dataStore.edit { it[Keys.trackListening] = value }
    suspend fun setOverlayPosition(x: Int, y: Int) = context.dataStore.edit { it[Keys.x] = x; it[Keys.y] = y }
    suspend fun addTree(uri: String) = context.dataStore.edit { it[Keys.trees] = (it[Keys.trees] ?: emptySet()) + uri }
}
