package com.luminara.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.luminara.player.artwork.ArtworkRepository
import com.luminara.player.artwork.ArtworkSearchState
import com.luminara.player.data.*
import com.luminara.player.library.KoreanSearch
import com.luminara.player.library.ScanResult
import com.luminara.player.lyrics.LrcCodec
import com.luminara.player.lyrics.SyncedLyricLine
import com.luminara.player.lyrics.LyricsSearchResult
import com.luminara.player.lyrics.LyricsSearchState
import com.luminara.player.network.LrcLibLyricsProvider
import com.luminara.player.network.SupabaseLyricsProvider
import com.luminara.player.network.CommunityActionState
import com.luminara.player.network.CommunityLyricsRepository
import com.luminara.player.domain.VoteDuplicateGuard
import com.luminara.player.playback.PlayerConnection
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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
    private val artworkRepository = ArtworkRepository(application)
    private val mutableArtworkSearch = MutableStateFlow<ArtworkSearchState>(ArtworkSearchState.Idle)
    val artworkSearch = mutableArtworkSearch.asStateFlow()
    private val lrcLibProvider = LrcLibLyricsProvider()
    private val sharedLyricsProvider = SupabaseLyricsProvider()
    private val communityRepository = CommunityLyricsRepository()
    private val mutableCommunityAction = MutableStateFlow<CommunityActionState>(CommunityActionState.Idle)
    val communityAction = mutableCommunityAction.asStateFlow()
    private val voteGuard = VoteDuplicateGuard()
    private val mutableLyricsSearch = MutableStateFlow<LyricsSearchState>(LyricsSearchState.Idle)
    val lyricsSearch = mutableLyricsSearch.asStateFlow()
    private val mutableStagedLyrics = MutableStateFlow<LyricsSearchResult?>(null)
    val stagedLyrics = mutableStagedLyrics.asStateFlow()
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
        result.getOrNull()?.removedTrackIds?.forEach(player::removeTrack)
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
    fun setKeepScreenOn(value: Boolean) = viewModelScope.launch { container.preferences.setKeepScreenOn(value) }
    fun toggleFavorite(id: String) = viewModelScope.launch { container.musicRepository.toggleFavorite(id) }
    fun updateMetadata(track: TrackEntity, title: String, artist: String, album: String, albumArtist: String?, done: (String) -> Unit) = viewModelScope.launch {
        done(runCatching { container.musicRepository.updateMetadata(track, title, artist, album, albumArtist); "저장했습니다" }.getOrElse { "저장 실패: ${it.localizedMessage}" })
    }
    fun createAlbum(name: String) = viewModelScope.launch { dao.insertAlbum(UserAlbumEntity(name = name, sortOrder = uiState.value.albums.size)) }
    fun createFolder(name: String) = viewModelScope.launch { dao.insertFolder(AlbumFolderEntity(name = name, sortOrder = uiState.value.folders.size)) }
    fun reorderAlbums(ids: List<Long>, folderId: Long? = null) = viewModelScope.launch { dao.reorderAlbums(ids, folderId) }
    fun createFolderFromAlbums(name: String, firstId: Long, secondId: Long) = viewModelScope.launch {
        dao.createFolderWithAlbums(name, listOf(firstId, secondId), uiState.value.folders.size)
        normalizeRootAlbums()
    }
    fun moveAlbumToFolder(albumId: Long, folderId: Long) = viewModelScope.launch {
        dao.moveAlbum(albumId, folderId, dao.albumsInFolder(folderId).size)
        normalizeRootAlbums()
    }
    fun moveAlbumToRoot(albumId: Long) = viewModelScope.launch {
        val root = uiState.value.albums.filter { it.folderId == null }
        dao.moveAlbum(albumId, null, root.size)
        dao.reorderAlbums((root.map { it.id } + albumId).distinct(), null)
    }
    fun renameFolder(id: Long, name: String) = viewModelScope.launch { dao.renameFolder(id, name) }
    fun dissolveFolder(id: Long) = viewModelScope.launch {
        dao.dissolveFolder(id, uiState.value.albums.count { it.folderId == null })
        normalizeRootAlbums()
    }
    private suspend fun normalizeRootAlbums() { dao.reorderAlbums(dao.rootAlbums().map { it.id }, null) }
    fun addToAlbum(albumId: Long, trackId: String) = viewModelScope.launch { dao.addAlbumTrack(AlbumTrackEntity(albumId, trackId, Int.MAX_VALUE)) }
    fun saveLyrics(trackId: String, text: String, synced: List<SyncedLyricLine>) = viewModelScope.launch {
        dao.deleteLyrics(trackId)
        val id = dao.insertLyrics(LyricsEntity(trackId = trackId, plainText = text))
        if (synced.isNotEmpty()) dao.insertLyricLines(synced.mapIndexed { i, l -> LyricLineEntity(lyricsId = id, lineIndex = i, startTimeMs = l.startTimeMs, text = l.text) })
    }
    fun importLrc(text: String) = LrcCodec.parse(text)
    fun searchLyrics(title: String, artist: String) = viewModelScope.launch {
        mutableLyricsSearch.value = LyricsSearchState.Loading
        mutableLyricsSearch.value = runCatching {
            val results = coroutineScope {
                val localApi = async { runCatching { lrcLibProvider.search(title, artist) } }
                val community = async { runCatching { sharedLyricsProvider.search(title, artist) } }
                val providers = listOf(localApi.await(), community.await())
                if (providers.all { it.isFailure }) throw providers.first().exceptionOrNull() ?: error("가사 검색에 실패했습니다")
                providers.flatMap { it.getOrDefault(emptyList()) }
            }
            LyricsSearchState.Success(results)
        }.getOrElse { LyricsSearchState.Error(it.localizedMessage ?: "가사 검색에 실패했습니다") }
    }
    fun resetLyricsSearch() { mutableLyricsSearch.value = LyricsSearchState.Idle }
    fun stageLyrics(result: LyricsSearchResult?) {
        mutableStagedLyrics.value = result
        if (result != null && result.source.startsWith("Luminara") && result.synced && result.syncedText == null) viewModelScope.launch {
            runCatching { communityRepository.syncedText(result.id) }.getOrNull()?.let { synced ->
                mutableStagedLyrics.value = result.copy(syncedText = synced)
            }
        }
    }
    fun resetCommunityAction() { mutableCommunityAction.value = CommunityActionState.Idle }
    fun shareLyrics(trackId: String, text: String, synced: List<SyncedLyricLine>) = viewModelScope.launch {
        mutableCommunityAction.value = CommunityActionState.Loading
        mutableCommunityAction.value = runCatching {
            val track = dao.track(trackId) ?: error("곡 정보를 찾을 수 없습니다.")
            communityRepository.share(track, text, synced)
            CommunityActionState.Success("Luminara 공유 가사로 업로드했습니다.")
        }.getOrElse { CommunityActionState.Error(it.localizedMessage ?: "가사 공유에 실패했습니다.") }
    }
    fun voteLyrics(lyricsId: String) = viewModelScope.launch {
        if (!voteGuard.begin(lyricsId)) {
            mutableCommunityAction.value = CommunityActionState.Error("이 가사에는 이미 추천했습니다.")
            return@launch
        }
        mutableCommunityAction.value = CommunityActionState.Loading
        mutableCommunityAction.value = runCatching { communityRepository.vote(lyricsId); CommunityActionState.Success("추천했습니다.") }
            .getOrElse { voteGuard.failed(lyricsId); CommunityActionState.Error(it.localizedMessage ?: "추천에 실패했습니다.") }
    }
    fun reportLyrics(lyricsId: String, reason: String) = viewModelScope.launch {
        mutableCommunityAction.value = CommunityActionState.Loading
        mutableCommunityAction.value = runCatching { communityRepository.report(lyricsId, reason); CommunityActionState.Success("신고를 접수했습니다.") }
            .getOrElse { CommunityActionState.Error(it.localizedMessage ?: "신고에 실패했습니다.") }
    }
    fun setArtwork(trackId: String, uri: String?) = viewModelScope.launch { dao.updateCustomArtwork(trackId, uri, System.currentTimeMillis()) }
    fun searchArtwork(query: String) = viewModelScope.launch {
        mutableArtworkSearch.value = ArtworkSearchState.Loading
        mutableArtworkSearch.value = runCatching { ArtworkSearchState.Success(artworkRepository.search(query)) }
            .getOrElse { ArtworkSearchState.Error(it.localizedMessage ?: "이미지 검색에 실패했습니다") }
    }
    fun resetArtworkSearch() { mutableArtworkSearch.value = ArtworkSearchState.Idle }
    fun prepareRemoteArtwork(url: String, done: (Result<Uri>) -> Unit) = viewModelScope.launch {
        done(runCatching { artworkRepository.downloadForCrop(url) })
    }
    fun applyCroppedArtwork(trackId: String, source: Uri, zoom: Float, offsetX: Float, offsetY: Float, done: (Result<Uri>) -> Unit) = viewModelScope.launch {
        val result = runCatching { artworkRepository.cropToAppStorage(source, trackId, zoom, offsetX, offsetY) }
        result.getOrNull()?.let { dao.updateCustomArtwork(trackId, it.toString(), System.currentTimeMillis()) }
        done(result)
    }
    fun completeApprovedDelete(trackId: String) = viewModelScope.launch { player.removeTrack(trackId); dao.deleteTrackCompletely(trackId) }
    suspend fun track(id: String) = dao.track(id)
    suspend fun lyrics(id: String): Pair<String, List<SyncedLyricLine>> {
        val lyrics = dao.lyrics(id) ?: return "" to emptyList()
        return lyrics.plainText to dao.lyricLines(lyrics.id).map { SyncedLyricLine(it.id.toString(), it.startTimeMs, it.text) }
    }

    override fun onCleared() { player.disconnect(); super.onCleared() }
}
