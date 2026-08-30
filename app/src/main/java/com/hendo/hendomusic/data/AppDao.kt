package com.hendo.hendomusic.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE")
    fun observeTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id") suspend fun track(id: String): TrackEntity?
    @Query("SELECT * FROM tracks WHERE id IN (:ids)") suspend fun tracks(ids: List<String>): List<TrackEntity>
    @Query("SELECT * FROM tracks WHERE uri IN (:uris)") suspend fun tracksByUris(uris: List<String>): List<TrackEntity>
    @Query("SELECT uri FROM tracks") suspend fun allUris(): List<String>
    @Query("SELECT id FROM tracks") suspend fun allTrackIds(): List<String>
    @Query("SELECT id FROM tracks WHERE uri NOT IN (:validUris)") suspend fun trackIdsMissing(validUris: List<String>): List<String>
    @Upsert suspend fun upsertTracks(tracks: List<TrackEntity>)
    @Query("DELETE FROM tracks WHERE uri NOT IN (:validUris)") suspend fun deleteMissing(validUris: List<String>)
    @Query("DELETE FROM tracks") suspend fun deleteAllTracks()
    @Query("UPDATE tracks SET isFavorite = NOT isFavorite, updatedAt = :now WHERE id = :id") suspend fun toggleFavorite(id: String, now: Long)
    @Query("UPDATE tracks SET title=:title, artist=:artist, album=:album, albumArtist=:albumArtist, updatedAt=:now WHERE id=:id")
    suspend fun updateMetadata(id: String, title: String, artist: String, album: String, albumArtist: String?, now: Long)
    @Query("UPDATE tracks SET customArtworkUri=:uri, customArtworkSource=:source, updatedAt=:now WHERE id=:id")
    suspend fun updateCustomArtwork(id: String, uri: String?, source: String?, now: Long)
    @Query("UPDATE tracks SET autoArtworkUri=:uri, autoArtworkSource=:source, updatedAt=:now WHERE id=:id AND customArtworkUri IS NULL AND (albumArtUri IS NULL OR albumArtUri = '')")
    suspend fun applyAutoArtworkIfEligible(id: String, uri: String, source: String, now: Long): Int
    @Query("SELECT * FROM tracks WHERE customArtworkUri IS NULL AND (albumArtUri IS NULL OR albumArtUri = '') AND autoArtworkUri IS NULL LIMIT :limit")
    suspend fun artworkEnrichmentCandidates(limit: Int): List<TrackEntity>
    @Query("SELECT tracks.* FROM tracks LEFT JOIN lyrics ON lyrics.trackId = tracks.id WHERE lyrics.id IS NULL LIMIT :limit")
    suspend fun lyricEnrichmentCandidates(limit: Int): List<TrackEntity>
    @Query("DELETE FROM album_tracks WHERE trackId=:trackId") suspend fun removeTrackFromAlbums(trackId: String)
    @Query("DELETE FROM playback_queue WHERE trackId=:trackId") suspend fun removeTrackFromSavedQueue(trackId: String)
    @Query("DELETE FROM lyrics WHERE trackId=:trackId") suspend fun removeTrackLyrics(trackId: String)
    @Query("DELETE FROM playback_history WHERE trackId=:trackId") suspend fun removeTrackHistory(trackId: String)
    @Query("DELETE FROM tracks WHERE id=:trackId") suspend fun deleteTrack(trackId: String)
    @Transaction suspend fun deleteTrackCompletely(trackId: String) {
        removeTrackFromAlbums(trackId); removeTrackFromSavedQueue(trackId); removeTrackLyrics(trackId); removeTrackHistory(trackId); deleteTrack(trackId)
    }
    @Transaction suspend fun deleteTracksCompletely(trackIds: List<String>) { trackIds.forEach { deleteTrackCompletely(it) } }
    @Query("UPDATE tracks SET playCount=playCount+1, lastPlayedAt=:now WHERE id=:id") suspend fun countPlay(id: String, now: Long)

    @Query("SELECT * FROM user_albums ORDER BY sortOrder") fun observeAlbums(): Flow<List<UserAlbumEntity>>
    @Query("SELECT * FROM user_albums WHERE folderId IS NULL ORDER BY sortOrder") suspend fun rootAlbums(): List<UserAlbumEntity>
    @Query("SELECT * FROM user_albums WHERE name=:name AND folderId IS NULL LIMIT 1") suspend fun rootAlbumNamed(name: String): UserAlbumEntity?
    @Insert suspend fun insertAlbum(album: UserAlbumEntity): Long
    @Query("UPDATE user_albums SET name=:name WHERE id=:id") suspend fun renameAlbum(id: Long, name: String)
    @Query("UPDATE user_albums SET artworkUri=:uri WHERE id=:id") suspend fun updateAlbumArtwork(id: Long, uri: String?)
    @Query("UPDATE user_albums SET folderId=:folderId, sortOrder=:sortOrder WHERE id=:id") suspend fun moveAlbum(id: Long, folderId: Long?, sortOrder: Int)
    @Transaction suspend fun reorderAlbums(ids: List<Long>, folderId: Long?) { ids.forEachIndexed { index, id -> moveAlbum(id, folderId, index) } }
    @Query("DELETE FROM user_albums WHERE id=:id") suspend fun deleteAlbum(id: Long)
    @Transaction suspend fun deleteAlbumCompletely(id: Long) { clearAlbumTracks(id); deleteAlbum(id) }
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addAlbumTrack(item: AlbumTrackEntity)
    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM album_tracks WHERE albumId=:albumId") suspend fun nextAlbumTrackSortOrder(albumId: Long): Int
    @Query("UPDATE album_tracks SET sortOrder=:sortOrder WHERE albumId=:albumId AND trackId=:trackId") suspend fun updateAlbumTrackOrder(albumId: Long, trackId: String, sortOrder: Int)
    @Transaction suspend fun reorderAlbumTracks(albumId: Long, trackIds: List<String>) {
        trackIds.distinct().forEachIndexed { index, trackId -> updateAlbumTrackOrder(albumId, trackId, index) }
    }
    @Query("DELETE FROM album_tracks WHERE albumId=:albumId") suspend fun clearAlbumTracks(albumId: Long)
    @Transaction suspend fun createAlbumWithTracks(album: UserAlbumEntity, trackIds: List<String>): Long {
        val albumId = insertAlbum(album)
        trackIds.distinct().forEachIndexed { index, trackId ->
            addAlbumTrack(AlbumTrackEntity(albumId, trackId, index))
        }
        return albumId
    }
    @Transaction suspend fun replaceRootAlbumTracks(name: String, sortOrder: Int, trackIds: List<String>): Long {
        val existing = rootAlbumNamed(name)
        val albumId = existing?.id ?: insertAlbum(UserAlbumEntity(name = name, sortOrder = sortOrder))
        clearAlbumTracks(albumId)
        trackIds.distinct().forEachIndexed { index, trackId -> addAlbumTrack(AlbumTrackEntity(albumId, trackId, index)) }
        return albumId
    }
    @Query("SELECT tracks.* FROM tracks INNER JOIN album_tracks ON tracks.id=album_tracks.trackId WHERE album_tracks.albumId=:albumId ORDER BY album_tracks.sortOrder")
    fun observeAlbumTracks(albumId: Long): Flow<List<TrackEntity>>
    @Insert suspend fun insertFolder(folder: AlbumFolderEntity): Long
    @Query("SELECT * FROM album_folders ORDER BY sortOrder") fun observeFolders(): Flow<List<AlbumFolderEntity>>
    @Query("UPDATE album_folders SET sortOrder=:sortOrder WHERE id=:id") suspend fun updateFolderOrder(id: Long, sortOrder: Int)
    @Transaction suspend fun reorderFolders(ids: List<Long>) { ids.distinct().forEachIndexed { index, id -> updateFolderOrder(id, index) } }
    @Query("UPDATE album_folders SET name=:name WHERE id=:id") suspend fun renameFolder(id: Long, name: String)
    @Query("UPDATE album_folders SET artworkUri=:uri WHERE id=:id") suspend fun updateFolderArtwork(id: Long, uri: String?)
    @Query("DELETE FROM album_folders WHERE id=:id") suspend fun deleteFolderRow(id: Long)
    @Query("SELECT * FROM user_albums WHERE folderId=:folderId ORDER BY sortOrder") suspend fun albumsInFolder(folderId: Long): List<UserAlbumEntity>
    @Transaction suspend fun createFolderWithAlbums(name: String, albumIds: List<Long>, sortOrder: Int): Long {
        val folderId = insertFolder(AlbumFolderEntity(name = name, sortOrder = sortOrder))
        albumIds.forEachIndexed { index, id -> moveAlbum(id, folderId, index) }
        return folderId
    }
    @Transaction suspend fun dissolveFolder(folderId: Long, rootStartOrder: Int) {
        albumsInFolder(folderId).forEachIndexed { index, album -> moveAlbum(album.id, null, rootStartOrder + index) }
        deleteFolderRow(folderId)
    }

    @Insert suspend fun insertLyrics(lyrics: LyricsEntity): Long
    @Insert suspend fun insertLyricLines(lines: List<LyricLineEntity>)
    @Query("SELECT * FROM lyrics WHERE trackId=:trackId ORDER BY updatedAt DESC LIMIT 1") fun observeLyrics(trackId: String): Flow<LyricsEntity?>
    @Query("SELECT * FROM lyrics WHERE trackId=:trackId ORDER BY updatedAt DESC LIMIT 1") suspend fun lyrics(trackId: String): LyricsEntity?
    @Query("SELECT * FROM lyric_lines WHERE lyricsId=:lyricsId ORDER BY lineIndex") fun observeLyricLines(lyricsId: Long): Flow<List<LyricLineEntity>>
    @Query("SELECT * FROM lyric_lines WHERE lyricsId=:lyricsId ORDER BY lineIndex") suspend fun lyricLines(lyricsId: Long): List<LyricLineEntity>
    @Query("DELETE FROM lyrics WHERE trackId=:trackId") suspend fun deleteLyrics(trackId: String)

    @Transaction suspend fun replaceLyrics(lyrics: LyricsEntity, lines: List<LyricLineEntity>) {
        deleteLyrics(lyrics.trackId)
        val id = insertLyrics(lyrics)
        if (lines.isNotEmpty()) insertLyricLines(lines.map { it.copy(lyricsId = id) })
    }
    /** Background results may only fill NONE; they must never replace a user save. */
    @Transaction suspend fun insertAutoLyricsIfMissing(lyrics: LyricsEntity, lines: List<LyricLineEntity>): Boolean {
        if (this.lyrics(lyrics.trackId) != null) return false
        val id = insertLyrics(lyrics)
        if (lines.isNotEmpty()) insertLyricLines(lines.map { it.copy(lyricsId = id) })
        return true
    }

    @Query("SELECT * FROM playback_session WHERE singletonId=1") suspend fun session(): PlaybackSessionEntity?
    @Upsert suspend fun saveSession(session: PlaybackSessionEntity)
    @Query("SELECT * FROM playback_queue ORDER BY position") suspend fun queue(): List<PlaybackQueueEntity>
    @Query("DELETE FROM playback_queue") suspend fun clearQueue()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertQueue(items: List<PlaybackQueueEntity>)
    @Transaction suspend fun replaceQueue(items: List<PlaybackQueueEntity>) { clearQueue(); insertQueue(items) }
    @Insert suspend fun insertHistory(history: PlaybackHistoryEntity)
}
