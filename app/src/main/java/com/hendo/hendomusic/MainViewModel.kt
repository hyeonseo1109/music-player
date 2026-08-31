package com.hendo.hendomusic

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import android.provider.MediaStore
import com.hendo.hendomusic.artwork.ArtworkRepository
import com.hendo.hendomusic.artwork.ArtworkSearchState
import com.hendo.hendomusic.data.*
import com.hendo.hendomusic.library.KoreanSearch
import com.hendo.hendomusic.library.PlaylistImportCodec
import com.hendo.hendomusic.library.ScanResult
import com.hendo.hendomusic.lyrics.LrcCodec
import com.hendo.hendomusic.lyrics.SyncedLyricLine
import com.hendo.hendomusic.lyrics.LyricsSearchResult
import com.hendo.hendomusic.lyrics.LyricsSearchState
import com.hendo.hendomusic.network.LrcLibLyricsProvider
import com.hendo.hendomusic.network.GenieLyricsProvider
import com.hendo.hendomusic.network.SupabaseLyricsProvider
import com.hendo.hendomusic.network.CommunityActionState
import com.hendo.hendomusic.network.CommunityLyricsRepository
import com.hendo.hendomusic.domain.VoteDuplicateGuard
import com.hendo.hendomusic.playback.PlayerConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

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

sealed interface LrcImportState {
    data object Idle : LrcImportState
    data class Success(val plainText: String, val synced: List<SyncedLyricLine>) : LrcImportState
    data class Error(val message: String) : LrcImportState
}

sealed interface PlaylistImportState {
    data object Idle : PlaylistImportState
    data class Success(val message: String) : PlaylistImportState
    data class Error(val message: String) : PlaylistImportState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as LuminaraApplication).container
    private val dao = container.database.dao()
    val player = PlayerConnection(application)
    private val artworkRepository = ArtworkRepository(application)
    private val mutableArtworkSearch = MutableStateFlow<ArtworkSearchState>(ArtworkSearchState.Idle)
    val artworkSearch = mutableArtworkSearch.asStateFlow()
    private val lrcLibProvider = LrcLibLyricsProvider()
    private val genieLyricsProvider = GenieLyricsProvider()
    private val sharedLyricsProvider = SupabaseLyricsProvider()
    private val communityRepository = CommunityLyricsRepository()
    private val mutableCommunityAction = MutableStateFlow<CommunityActionState>(CommunityActionState.Idle)
    val communityAction = mutableCommunityAction.asStateFlow()
    private val voteGuard = VoteDuplicateGuard()
    private val mutableLyricsSearch = MutableStateFlow<LyricsSearchState>(LyricsSearchState.Idle)
    val lyricsSearch = mutableLyricsSearch.asStateFlow()
    private val mutableStagedLyrics = MutableStateFlow<LyricsSearchResult?>(null)
    val stagedLyrics = mutableStagedLyrics.asStateFlow()
    private val mutableLrcImport = MutableStateFlow<LrcImportState>(LrcImportState.Idle)
    val lrcImport = mutableLrcImport.asStateFlow()
    private val mutablePlaylistImport = MutableStateFlow<PlaylistImportState>(PlaylistImportState.Idle)
    val playlistImport = mutablePlaylistImport.asStateFlow()
    private val query = MutableStateFlow("")
    private val scanState = MutableStateFlow<Pair<Boolean, String?>>(false to null)
    private val mutableSettingsLoaded = MutableStateFlow(false)
    /** Prevents the default onboarding value from flashing before DataStore has emitted. */
    val settingsLoaded: StateFlow<Boolean> = mutableSettingsLoaded.asStateFlow()
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

    // Start with a small batch, then continue in the ViewModel background until missing
    // automatic metadata has been considered for the whole local library.
    init {
        viewModelScope.launch { container.preferences.settings.first(); mutableSettingsLoaded.value = true }
        viewModelScope.launch { delay(1_000); container.metadataEnricher.enrichAllMissing() }
    }

    fun setQuery(value: String) { query.value = value }
    fun scan() = viewModelScope.launch {
        scanState.value = true to null
        val settings = uiState.value.settings
        val result = runCatching {
            if (settings.scanMode == ScanMode.MEDIA_STORE) container.musicRepository.scanMediaStore()
            else container.musicRepository.scanTrees(settings.treeUris)
        }
        result.getOrNull()?.removedTrackIds?.forEach(player::removeTrack)
        // Continue auto metadata discovery after the local library has been persisted.
        if (result.isSuccess) viewModelScope.launch { container.metadataEnricher.enrichAllMissing() }
        scanState.value = false to result.fold(
            { "${it.found}곡 검색 · ${it.addedOrUpdated}곡 반영 · ${it.removed}곡 제거" },
            { "검색 실패: ${it.localizedMessage}" },
        )
        delay(3_000)
        if (!scanState.value.first) scanState.value = false to null
    }
    fun completeOnboarding() = viewModelScope.launch { container.preferences.completeOnboarding(); scan() }
    fun setTheme(value: ThemeMode) = viewModelScope.launch { container.preferences.setTheme(value) }
    fun setSort(value: String) = viewModelScope.launch { container.preferences.setSort(value) }
    fun setScanMode(value: ScanMode) = viewModelScope.launch { container.preferences.setScanMode(value) }
    fun addTree(uri: String) = viewModelScope.launch { container.preferences.addTree(uri); container.preferences.setScanMode(ScanMode.SELECTED_FOLDERS); scan() }
    fun setFloating(value: Boolean) = viewModelScope.launch { container.preferences.setFloating(value) }
    fun setFloatingLines(value: Int) = viewModelScope.launch { container.preferences.setFloatingLines(value) }
    fun setKeepScreenOn(value: Boolean) = viewModelScope.launch { container.preferences.setKeepScreenOn(value) }
    fun setTrackListening(value: Boolean) = viewModelScope.launch { container.preferences.setTrackListening(value) }
    fun setCoverLyricsPreview(value: Boolean) = viewModelScope.launch { container.preferences.setCoverLyricsPreview(value) }
    fun toggleFavorite(id: String) = viewModelScope.launch { container.musicRepository.toggleFavorite(id) }
    fun deleteSelectedTracks(ids: List<String>) = viewModelScope.launch { ids.forEach(player::removeTrack); dao.deleteTracksCompletely(ids) }
    fun updateMetadata(track: TrackEntity, title: String, artist: String, album: String, albumArtist: String?, done: (String) -> Unit) = viewModelScope.launch {
        done(runCatching { container.musicRepository.updateMetadata(track, title, artist, album, albumArtist); "저장했습니다" }.getOrElse { "저장 실패: ${it.localizedMessage}" })
    }
    fun createAlbum(name: String) = viewModelScope.launch { dao.insertAlbum(UserAlbumEntity(name = name, sortOrder = uiState.value.albums.size)) }
    fun createFolder(name: String) = viewModelScope.launch { dao.insertFolder(AlbumFolderEntity(name = name, sortOrder = uiState.value.folders.size)) }
    fun reorderAlbums(ids: List<Long>, folderId: Long? = null) = viewModelScope.launch { dao.reorderAlbums(ids, folderId) }
    fun reorderAlbumTracks(albumId: Long, ids: List<String>) = viewModelScope.launch { dao.reorderAlbumTracks(albumId, ids) }
    fun reorderFolders(ids: List<Long>) = viewModelScope.launch { dao.reorderFolders(ids) }
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
    fun renameAlbum(id: Long, name: String) = viewModelScope.launch { dao.renameAlbum(id, name) }
    /** null restores automatic first-track artwork; an empty URI deliberately hides it. */
    fun setAlbumArtwork(id: Long, uri: String?) = viewModelScope.launch {
        val stored = uri?.takeIf { it.isNotEmpty() }?.let { artworkRepository.cropToAppStorage(Uri.parse(it), "album_$id", 1f, 0f, 0f).toString() } ?: uri
        dao.updateAlbumArtwork(id, stored)
    }
    /** null restores automatic collage artwork; an empty URI deliberately shows the placeholder. */
    fun setFolderArtwork(id: Long, uri: String?) = viewModelScope.launch {
        val stored = uri?.takeIf { it.isNotEmpty() }?.let { artworkRepository.cropToAppStorage(Uri.parse(it), "folder_$id", 1f, 0f, 0f).toString() } ?: uri
        dao.updateFolderArtwork(id, stored)
    }
    fun deleteAlbum(id: Long) = viewModelScope.launch { dao.deleteAlbumCompletely(id) }
    fun dissolveFolder(id: Long) = viewModelScope.launch {
        dao.dissolveFolder(id, uiState.value.albums.count { it.folderId == null })
        normalizeRootAlbums()
    }
    private suspend fun normalizeRootAlbums() { dao.reorderAlbums(dao.rootAlbums().map { it.id }, null) }
    /** New songs always go to the end of an album; a later drag is the only way to reorder them. */
    fun addToAlbum(albumId: Long, trackId: String) = viewModelScope.launch {
        dao.addAlbumTrack(AlbumTrackEntity(albumId, trackId, dao.nextAlbumTrackSortOrder(albumId)))
    }
    fun importPlaylistUri(uri: Uri) = viewModelScope.launch {
        mutablePlaylistImport.value = runCatching {
            val resolver = getApplication<Application>().contentResolver
            val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: "가져온 재생목록.m3u"
            val contents = resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("재생목록 파일을 읽을 수 없습니다.")
            val playlist = PlaylistImportCodec.parse(name, contents)
            require(playlist.entries.isNotEmpty()) { "곡이 없는 재생목록입니다." }
            val tracks = uiState.value.tracks
            val byFileName = tracks.associateBy { it.fileName.lowercase() }
            val byRelativePath = tracks.filter { it.relativePath != null }.associateBy { PlaylistImportCodec.normalizedPath("${it.relativePath}/${it.fileName}") }
            val trackIds = playlist.entries.mapNotNull { entry ->
                val path = PlaylistImportCodec.normalizedPath(entry)
                byRelativePath[path]?.id
                    ?: tracks.firstOrNull { path.endsWith("/${PlaylistImportCodec.normalizedPath(it.fileName)}") }?.id
                    ?: byFileName[PlaylistImportCodec.fileName(entry).lowercase()]?.id
            }.distinct()
            require(trackIds.isNotEmpty()) { "현재 음악 보관함과 일치하는 곡이 없습니다. 먼저 전체 곡을 새로고침해 주세요." }
            dao.createAlbumWithTracks(UserAlbumEntity(name = playlist.name, sortOrder = uiState.value.albums.count { it.folderId == null }), trackIds)
            val skipped = playlist.entries.size - trackIds.size
            PlaylistImportState.Success("${playlist.name} 앨범에 ${trackIds.size}곡을 가져왔습니다" + if (skipped > 0) " · ${skipped}곡은 찾지 못했습니다" else "")
        }.getOrElse { PlaylistImportState.Error(it.localizedMessage ?: "재생목록 가져오기에 실패했습니다.") }
    }
    /** Reads only playlists intentionally exposed by Android's shared MediaStore. */
    fun importPublicPlaylists() = viewModelScope.launch {
        mutablePlaylistImport.value = runCatching {
            val resolver = getApplication<Application>().contentResolver
            val playlists = resolver.query(
                MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Playlists._ID, MediaStore.Audio.Playlists.NAME), null, null, null,
            )?.use { cursor ->
                buildList {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists._ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists.NAME)
                    while (cursor.moveToNext()) add(cursor.getLong(idColumn) to cursor.getString(nameColumn))
                }
            }.orEmpty()
            require(playlists.isNotEmpty()) { "공개된 기기 재생목록이 없습니다. 삼성뮤직에서 내보낸 파일을 선택해 주세요." }
            val localIds = uiState.value.tracks.mapNotNull { track -> track.mediaStoreId?.let { it to track.id } }.toMap()
            var imported = 0
            playlists.forEach { (playlistId, name) ->
                val memberUri = MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId)
                val trackIds = resolver.query(memberUri, arrayOf(MediaStore.Audio.Playlists.Members.AUDIO_ID), null, null, "play_order ASC")
                    ?.use { cursor -> buildList { val column = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists.Members.AUDIO_ID); while (cursor.moveToNext()) localIds[cursor.getLong(column)]?.let(::add) } }
                    .orEmpty()
                if (trackIds.isNotEmpty()) {
                    dao.replaceRootAlbumTracks(name, uiState.value.albums.count { it.folderId == null }, trackIds)
                    imported++
                }
            }
            require(imported > 0) { "공개 재생목록에 현재 보관함과 일치하는 곡이 없습니다." }
            PlaylistImportState.Success("공개 재생목록 ${imported}개를 내 앨범으로 동기화했습니다")
        }.getOrElse { PlaylistImportState.Error(it.localizedMessage ?: "공개 재생목록을 가져오지 못했습니다.") }
    }
    fun exportAlbumM3u(albumId: Long, done: (String, String) -> Unit) = viewModelScope.launch {
        val album = uiState.value.albums.firstOrNull { it.id == albumId } ?: return@launch
        dao.observeAlbumTracks(albumId).first().takeIf { it.isNotEmpty() }?.let { tracks ->
            val body = buildString {
                appendLine("#EXTM3U")
                tracks.forEach { track ->
                    appendLine("#EXTINF:${track.durationMs / 1_000},${track.artist} - ${track.title}")
                    appendLine(track.relativePath?.let { "${it.trimEnd('/')}/${track.fileName}" } ?: track.fileName)
                }
            }
            done("${album.name}.m3u", body)
        }
    }
    fun resetPlaylistImport() { mutablePlaylistImport.value = PlaylistImportState.Idle }
    fun observeAlbumTracks(albumId: Long) = dao.observeAlbumTracks(albumId)
    fun startSyncPlayback(trackId: String) = viewModelScope.launch {
        dao.track(trackId)?.let { play(it) }
    }
    fun play(track: TrackEntity, library: List<TrackEntity> = emptyList()) = viewModelScope.launch {
        player.play(track, library)
        container.metadataEnricher.enrichTrack(track)
    }
    fun saveLyrics(trackId: String, text: String, synced: List<SyncedLyricLine>, source: LyricsSource = LyricsSource.USER_MANUAL) = viewModelScope.launch {
        dao.replaceLyrics(
            LyricsEntity(trackId = trackId, source = source.name, plainText = text),
            synced.mapIndexed { i, l -> LyricLineEntity(lyricsId = 0, lineIndex = i, startTimeMs = l.startTimeMs, text = l.text) },
        )
    }
    fun importLrc(text: String) = LrcCodec.parse(text)
    fun importLrcUri(uri: Uri) = viewModelScope.launch {
        mutableLrcImport.value = runCatching {
            val text = getApplication<Application>().contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?.trim()
                .orEmpty()
            require(text.isNotBlank()) { "빈 LRC 파일입니다." }
            val synced = LrcCodec.parse(text)
            require(synced.isNotEmpty()) { "시간 정보가 있는 LRC 형식이 아닙니다." }
            LrcImportState.Success(synced.joinToString("\n") { it.text }, synced)
        }.getOrElse { LrcImportState.Error(it.localizedMessage ?: "LRC 파일을 읽을 수 없습니다.") }
    }
    fun resetLrcImport() { mutableLrcImport.value = LrcImportState.Idle }
    fun searchLyrics(title: String, artist: String) = viewModelScope.launch {
        mutableLyricsSearch.value = LyricsSearchState.Loading
        mutableLyricsSearch.value = runCatching {
            val results = coroutineScope {
                val lrcLib = async { runCatching { lrcLibProvider.search(title, artist) } }
                val community = async { runCatching { sharedLyricsProvider.search(title, artist) } }
                val lrcResult = lrcLib.await()
                val communityResult = community.await()
                val lrcLyrics = lrcResult.getOrDefault(emptyList())
                val communityLyrics = communityResult.getOrDefault(emptyList())
                // Manual search is user-confirmed. Always include permissively matched Genie
                // candidates: LRCLIB can return a loosely related row even when it has no
                // useful lyric for this song, so treating a non-empty response as a fallback
                // blocker hides valid public Genie results.
                val genieLyrics = runCatching { genieLyricsProvider.searchManual(title, artist) }.getOrDefault(emptyList()).map { genie ->
                    LyricsSearchResult(
                        id = "genie:${genie.songId}", preview = genie.plainText.take(180), source = "Genie",
                        synced = true, votes = 0, updatedAt = "", plainText = genie.plainText,
                        syncedText = LrcCodec.encode(genie.syncedLines.mapIndexed { index, line ->
                            SyncedLyricLine("genie_${genie.songId}_$index", line.startTimeMs, line.text)
                        }), trackTitle = genie.title, trackArtist = genie.artist, album = genie.album,
                    )
                }
                // A disabled or empty optional community provider must not hide a
                // real LRCLIB request failure as a misleading empty-result state.
                if (lrcResult.isFailure && communityLyrics.isEmpty() && genieLyrics.isEmpty()) {
                    throw lrcResult.exceptionOrNull() ?: error("LRCLIB 가사 검색에 실패했습니다")
                }
                lrcLyrics + communityLyrics + genieLyrics
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
    fun setArtwork(trackId: String, uri: String?) = viewModelScope.launch {
        dao.updateCustomArtwork(trackId, uri, uri?.let { ArtworkSource.USER.name }, System.currentTimeMillis())
    }
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
        val result = runCatching {
            val cropped = artworkRepository.cropToAppStorage(source, trackId, zoom, offsetX, offsetY)
            val track = dao.track(trackId) ?: error("곡을 찾을 수 없습니다.")
            container.musicRepository.updateArtwork(track, cropped.toString())
            dao.updateCustomArtwork(trackId, cropped.toString(), ArtworkSource.USER.name, System.currentTimeMillis())
            cropped
        }
        done(result)
    }
    fun completeApprovedDelete(trackId: String) = viewModelScope.launch { player.removeTrack(trackId); dao.deleteTrackCompletely(trackId) }
    suspend fun track(id: String) = dao.track(id)
    suspend fun lyrics(id: String): Pair<String, List<SyncedLyricLine>> {
        val lyrics = dao.lyrics(id) ?: return "" to emptyList()
        return lyrics.plainText to dao.lyricLines(lyrics.id).map { SyncedLyricLine(it.id.toString(), it.startTimeMs, it.text) }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeLyrics(id: String): Flow<Pair<String, List<SyncedLyricLine>>> =
        dao.observeLyrics(id).flatMapLatest { lyrics ->
            if (lyrics == null) flowOf("" to emptyList())
            else dao.observeLyricLines(lyrics.id).map { lines ->
                lyrics.plainText to lines.map { SyncedLyricLine(it.id.toString(), it.startTimeMs, it.text) }
            }
        }

    override fun onCleared() { player.disconnect(); super.onCleared() }
}
