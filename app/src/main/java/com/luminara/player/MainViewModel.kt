package com.luminara.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.luminara.player.data.*
import com.luminara.player.library.KoreanSearch
import com.luminara.player.library.ScanResult
import com.luminara.player.lyrics.LrcCodec
import com.luminara.player.lyrics.SyncedLyricLine
import com.luminara.player.playback.PlayerConnection
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class MainUiState(
    val tracks: List<TrackEntity> = emptyList(),
    val visibleTracks: List<TrackEntity> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val query: String = "",
    val scanning: Boolean = false,
    val scanMessage: String? = null,
    val albums: List<UserAlbumEntity> = emptyList(),
    val folders: List<AlbumFolderEntity> = emptyList(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as LuminaraApplication).container
    private val dao = container.database.dao()
    val player = PlayerConnection(application)
    private val query = MutableStateFlow("")
    private val scanState = MutableStateFlow<Pair<Boolean, String?>>(false to null)
    val uiState: StateFlow<MainUiState> = combine(
        container.musicRepository.tracks, container.preferences.settings, query.debounce(180),
        dao.observeAlbums(), dao.observeFolders(), scanState,
    ) { values ->
        val tracks = values[0] as List<TrackEntity>
        val settings = values[1] as AppSettings
        val q = values[2] as String
        val albums = values[3] as List<UserAlbumEntity>
        val folders = values[4] as List<AlbumFolderEntity>
        val scan = values[5] as Pair<Boolean, String?>
        val filtered = tracks.filter { KoreanSearch.matches(q, it.title, it.artist, it.album) }
        val sorted = when (settings.sort) {
            "RECENT" -> filtered.sortedByDescending { it.dateAdded }
            "PLAYED" -> filtered.sortedByDescending { it.lastPlayedAt ?: 0 }
            "COUNT" -> filtered.sortedByDescending { it.playCount }
            else -> filtered.sortedBy { it.title.lowercase() }
        }
        MainUiState(tracks, sorted, settings, q, scan.first, scan.second, albums, folders)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    fun setQuery(value: String) { query.value = value }
    fun scan() = viewModelScope.launch {
        scanState.value = true to null
        val settings = uiState.value.settings
        val result = runCatching {
            if (settings.scanMode == ScanMode.MEDIA_STORE) container.musicRepository.scanMediaStore()
            else container.musicRepository.scanTrees(settings.treeUris)
        }
        scanState.value = false to result.fold(
            { "${it.found}곡 검색 · ${it.addedOrUpdated}곡 반영 · ${it.removed}곡 제거" },
            { "검색 실패: ${it.localizedMessage}" },
        )
    }
    fun completeOnboarding() = viewModelScope.launch { container.preferences.completeOnboarding(); scan() }
    fun setTheme(value: ThemeMode) = viewModelScope.launch { container.preferences.setTheme(value) }
    fun setSort(value: String) = viewModelScope.launch { container.preferences.setSort(value) }
    fun setScanMode(value: ScanMode) = viewModelScope.launch { container.preferences.setScanMode(value) }
    fun addTree(uri: String) = viewModelScope.launch { container.preferences.addTree(uri); container.preferences.setScanMode(ScanMode.SELECTED_FOLDERS); scan() }
    fun setFloating(value: Boolean) = viewModelScope.launch { container.preferences.setFloating(value) }
    fun setFloatingLines(value: Int) = viewModelScope.launch { container.preferences.setFloatingLines(value) }
    fun toggleFavorite(id: String) = viewModelScope.launch { container.musicRepository.toggleFavorite(id) }
    fun updateMetadata(track: TrackEntity, title: String, artist: String, album: String, albumArtist: String?, done: (String) -> Unit) = viewModelScope.launch {
        done(runCatching { container.musicRepository.updateMetadata(track, title, artist, album, albumArtist); "저장했습니다" }.getOrElse { "저장 실패: ${it.localizedMessage}" })
    }
    fun createAlbum(name: String) = viewModelScope.launch { dao.insertAlbum(UserAlbumEntity(name = name, sortOrder = uiState.value.albums.size)) }
    fun createFolder(name: String) = viewModelScope.launch { dao.insertFolder(AlbumFolderEntity(name = name, sortOrder = uiState.value.folders.size)) }
    fun addToAlbum(albumId: Long, trackId: String) = viewModelScope.launch { dao.addAlbumTrack(AlbumTrackEntity(albumId, trackId, Int.MAX_VALUE)) }
    fun saveLyrics(trackId: String, text: String, synced: List<SyncedLyricLine>) = viewModelScope.launch {
        dao.deleteLyrics(trackId)
        val id = dao.insertLyrics(LyricsEntity(trackId = trackId, plainText = text))
        if (synced.isNotEmpty()) dao.insertLyricLines(synced.mapIndexed { i, l -> LyricLineEntity(lyricsId = id, lineIndex = i, startTimeMs = l.startTimeMs, text = l.text) })
    }
    fun importLrc(text: String) = LrcCodec.parse(text)

    override fun onCleared() { player.disconnect(); super.onCleared() }
}
