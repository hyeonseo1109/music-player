package com.luminara.player.data

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
    @Query("SELECT uri FROM tracks") suspend fun allUris(): List<String>
    @Upsert suspend fun upsertTracks(tracks: List<TrackEntity>)
    @Query("DELETE FROM tracks WHERE uri NOT IN (:validUris)") suspend fun deleteMissing(validUris: List<String>)
    @Query("DELETE FROM tracks") suspend fun deleteAllTracks()
    @Query("UPDATE tracks SET isFavorite = NOT isFavorite, updatedAt = :now WHERE id = :id") suspend fun toggleFavorite(id: String, now: Long)
    @Query("UPDATE tracks SET title=:title, artist=:artist, album=:album, albumArtist=:albumArtist, updatedAt=:now WHERE id=:id")
    suspend fun updateMetadata(id: String, title: String, artist: String, album: String, albumArtist: String?, now: Long)
    @Query("UPDATE tracks SET playCount=playCount+1, lastPlayedAt=:now WHERE id=:id") suspend fun countPlay(id: String, now: Long)

    @Query("SELECT * FROM user_albums ORDER BY sortOrder") fun observeAlbums(): Flow<List<UserAlbumEntity>>
    @Insert suspend fun insertAlbum(album: UserAlbumEntity): Long
    @Query("UPDATE user_albums SET name=:name WHERE id=:id") suspend fun renameAlbum(id: Long, name: String)
    @Query("UPDATE user_albums SET folderId=:folderId, sortOrder=:sortOrder WHERE id=:id") suspend fun moveAlbum(id: Long, folderId: Long?, sortOrder: Int)
    @Query("DELETE FROM user_albums WHERE id=:id") suspend fun deleteAlbum(id: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addAlbumTrack(item: AlbumTrackEntity)
    @Query("SELECT tracks.* FROM tracks INNER JOIN album_tracks ON tracks.id=album_tracks.trackId WHERE album_tracks.albumId=:albumId ORDER BY album_tracks.sortOrder")
    fun observeAlbumTracks(albumId: Long): Flow<List<TrackEntity>>
    @Insert suspend fun insertFolder(folder: AlbumFolderEntity): Long
    @Query("SELECT * FROM album_folders ORDER BY sortOrder") fun observeFolders(): Flow<List<AlbumFolderEntity>>

    @Insert suspend fun insertLyrics(lyrics: LyricsEntity): Long
    @Insert suspend fun insertLyricLines(lines: List<LyricLineEntity>)
    @Query("SELECT * FROM lyrics WHERE trackId=:trackId ORDER BY updatedAt DESC LIMIT 1") fun observeLyrics(trackId: String): Flow<LyricsEntity?>
    @Query("SELECT * FROM lyric_lines WHERE lyricsId=:lyricsId ORDER BY lineIndex") fun observeLyricLines(lyricsId: Long): Flow<List<LyricLineEntity>>
    @Query("DELETE FROM lyrics WHERE trackId=:trackId") suspend fun deleteLyrics(trackId: String)

    @Query("SELECT * FROM playback_session WHERE singletonId=1") suspend fun session(): PlaybackSessionEntity?
    @Upsert suspend fun saveSession(session: PlaybackSessionEntity)
    @Query("SELECT * FROM playback_queue ORDER BY position") suspend fun queue(): List<PlaybackQueueEntity>
    @Query("DELETE FROM playback_queue") suspend fun clearQueue()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertQueue(items: List<PlaybackQueueEntity>)
    @Transaction suspend fun replaceQueue(items: List<PlaybackQueueEntity>) { clearQueue(); insertQueue(items) }
    @Insert suspend fun insertHistory(history: PlaybackHistoryEntity)
}
