package com.hendo.hendomusic.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Persisted provenance is deliberately separate from the display priority. */
enum class ArtworkSource { USER, EMBEDDED, AUTO_ITUNES }
enum class LyricsSource { USER_MANUAL, USER_SEARCH, USER_LRC, AUTO_LRCLIB, AUTO_GENIE, AUTO_HENDOMUSIC }

@Entity(tableName = "tracks", indices = [Index(value = ["uri"], unique = true), Index("title"), Index("artist"), Index("album")])
data class TrackEntity(
    @PrimaryKey val id: String,
    val mediaStoreId: Long?,
    val uri: String,
    val relativePath: String?,
    val fileName: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String?,
    val durationMs: Long,
    val dateAdded: Long,
    val dateModified: Long,
    val albumArtUri: String?,
    val customArtworkUri: String? = null,
    val customArtworkSource: String? = null,
    val autoArtworkUri: String? = null,
    val autoArtworkSource: String? = null,
    val playCount: Int = 0,
    val lastPlayedAt: Long? = null,
    val isFavorite: Boolean = false,
    /** Stable user-defined order inside the favorites collection. */
    val favoriteOrder: Int? = null,
    val customLyricsId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

private val unknownAlbumNames = setOf(
    "<unknown>",
    "unknown",
    "unknown album",
    "알 수 없는 앨범",
)

fun String?.isMissingAlbumName(): Boolean {
    val normalized = this?.trim()?.lowercase().orEmpty()
    return normalized.isEmpty() || normalized in unknownAlbumNames
}

/**
 * USER artwork is track-specific and remains valid without an album. MediaStore album artwork
 * and automatic album search results are album-scoped, so an unknown album must never inherit
 * them from another track which happens to share Android's synthetic unknown-album id.
 */
fun TrackEntity.displayArtworkUri(): String? =
    customArtworkUri ?: if (album.isMissingAlbumName()) null else albumArtUri ?: autoArtworkUri

@Entity(tableName = "lyrics", indices = [Index("trackId")])
data class LyricsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val source: String = LyricsSource.USER_MANUAL.name,
    val plainText: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "lyric_lines",
    foreignKeys = [ForeignKey(entity = LyricsEntity::class, parentColumns = ["id"], childColumns = ["lyricsId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("lyricsId")],
)
data class LyricLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lyricsId: Long,
    val lineIndex: Int,
    val startTimeMs: Long,
    val text: String,
)

@Entity(tableName = "album_folders")
data class AlbumFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int,
    /** Null follows the first contained album artwork; empty string intentionally shows the placeholder. */
    val artworkUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "user_albums", indices = [Index("folderId")])
data class UserAlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val folderId: Long? = null,
    val sortOrder: Int,
    /** Null follows the first track artwork; empty string intentionally shows the placeholder. */
    val artworkUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "album_tracks",
    primaryKeys = ["albumId", "trackId"],
    indices = [Index("trackId")],
)
data class AlbumTrackEntity(val albumId: Long, val trackId: String, val sortOrder: Int)

@Entity(tableName = "playback_history", indices = [Index("trackId"), Index("playedAt")])
data class PlaybackHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val playedAt: Long,
    val listenedMs: Long,
)

@Entity(tableName = "playback_session")
data class PlaybackSessionEntity(
    @PrimaryKey val singletonId: Int = 1,
    val currentTrackId: String?,
    val currentIndex: Int,
    val positionMs: Long,
    val repeatMode: Int,
    val shuffleEnabled: Boolean,
    val shuffleOrder: String,
    val updatedAt: Long,
)

@Entity(tableName = "playback_queue", indices = [Index(value = ["position"], unique = true)])
data class PlaybackQueueEntity(
    @PrimaryKey val queueItemId: String,
    val trackId: String,
    val position: Int,
    val isPlayNext: Boolean = false,
)
