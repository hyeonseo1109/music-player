package com.hendo.hendomusic.library

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.hendo.hendomusic.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.security.MessageDigest

data class ScanResult(val found: Int, val addedOrUpdated: Int, val removed: Int, val errors: Int, val removedTrackIds: List<String> = emptyList())

class MusicRepository(private val context: Context, private val dao: AppDao) {
    val tracks: Flow<List<TrackEntity>> = dao.observeTracks()
    private val extensions = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "3gp", "amr")

    suspend fun scanMediaStore(): ScanResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.RELATIVE_PATH,
        )
        val found = mutableListOf<TrackEntity>()
        var errors = 0
        val cursor = resolver.query(collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null)
            ?: error("MediaStore 음악 목록을 읽을 수 없습니다.")
        cursor.use { c ->
            fun col(name: String) = c.getColumnIndexOrThrow(name)
            while (c.moveToNext()) runCatching {
                val mediaId = c.getLong(col(MediaStore.Audio.Media._ID))
                val uri = ContentUris.withAppendedId(collection, mediaId)
                val fileName = c.getString(col(MediaStore.Audio.Media.DISPLAY_NAME)) ?: "Unknown"
                val title = c.getString(col(MediaStore.Audio.Media.TITLE)).nullIfUnknown() ?: fileName.substringBeforeLast('.')
                val artist = c.getString(col(MediaStore.Audio.Media.ARTIST)).nullIfUnknown() ?: "알 수 없는 아티스트"
                val album = c.getString(col(MediaStore.Audio.Media.ALBUM)).nullIfUnknown() ?: "알 수 없는 앨범"
                val albumId = c.getLong(col(MediaStore.Audio.Media.ALBUM_ID))
                found += TrackEntity(
                    id = "ms:$mediaId", mediaStoreId = mediaId, uri = uri.toString(),
                    relativePath = c.getString(col(MediaStore.Audio.Media.RELATIVE_PATH)), fileName = fileName,
                    title = title, artist = artist, album = album, albumArtist = null,
                    durationMs = c.getLong(col(MediaStore.Audio.Media.DURATION)),
                    dateAdded = c.getLong(col(MediaStore.Audio.Media.DATE_ADDED)) * 1000,
                    dateModified = c.getLong(col(MediaStore.Audio.Media.DATE_MODIFIED)) * 1000,
                    albumArtUri = if (albumId > 0) "content://media/external/audio/albumart/$albumId" else null,
                )
            }.onFailure { errors++ }
        }
        val before = dao.allUris().toSet()
        val merged = preserveAppState(found)
        dao.upsertTracks(merged)
        val removedIds = if (found.isEmpty()) dao.allTrackIds() else dao.trackIdsMissing(found.map { it.uri })
        dao.deleteTracksCompletely(removedIds)
        val after = found.map { it.uri }.toSet()
        ScanResult(found.size, (after - before).size, (before - after).size, errors, removedIds)
    }

    suspend fun scanTrees(treeUris: Set<String>): ScanResult = withContext(Dispatchers.IO) {
        check(treeUris.isNotEmpty()) { "선택한 음악 폴더가 없습니다." }
        val items = mutableListOf<TrackEntity>()
        var errors = 0
        var accessibleRoots = 0
        treeUris.forEach { raw ->
            val root = DocumentFile.fromTreeUri(context, Uri.parse(raw)) ?: return@forEach
            if (!root.exists() || !root.canRead()) { errors++; return@forEach }
            accessibleRoots++
            walk(root) { file ->
                runCatching { readDocumentTrack(file) }.onSuccess { it?.let(items::add) }.onFailure { errors++ }
            }
        }
        check(accessibleRoots > 0) { "등록된 음악 폴더 중 읽을 수 있는 폴더가 없습니다. 폴더 권한을 다시 확인하세요." }
        val before = dao.allUris().toSet()
        val merged = preserveAppState(items)
        dao.upsertTracks(merged)
        val removedIds = if (items.isEmpty()) dao.allTrackIds() else dao.trackIdsMissing(items.map { it.uri })
        dao.deleteTracksCompletely(removedIds)
        val after = items.map { it.uri }.toSet()
        ScanResult(items.size, (after - before).size, (before - after).size, errors, removedIds)
    }

    private fun walk(file: DocumentFile, onAudio: (DocumentFile) -> Unit) {
        if (file.isDirectory) file.listFiles().forEach { walk(it, onAudio) }
        else if (file.name?.substringAfterLast('.', "")?.lowercase() in extensions) onAudio(file)
    }

    private fun readDocumentTrack(file: DocumentFile): TrackEntity? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, file.uri)
            val name = file.name ?: return null
            TrackEntity(
                id = "saf:${sha256(file.uri.toString())}", mediaStoreId = null, uri = file.uri.toString(),
                relativePath = null, fileName = name,
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).nullIfUnknown() ?: name.substringBeforeLast('.'),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).nullIfUnknown() ?: "알 수 없는 아티스트",
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).nullIfUnknown() ?: "알 수 없는 앨범",
                albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).nullIfUnknown(),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                dateAdded = file.lastModified(), dateModified = file.lastModified(), albumArtUri = null,
            )
        } finally { retriever.release() }
    }

    suspend fun toggleFavorite(id: String) = dao.toggleFavorite(id, System.currentTimeMillis())
    suspend fun track(id: String) = dao.track(id)
    suspend fun updateMetadata(track: TrackEntity, title: String, artist: String, album: String, albumArtist: String?) {
        withContext(Dispatchers.IO) {
            if (track.mediaStoreId != null) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.TITLE, title); put(MediaStore.Audio.Media.ARTIST, artist); put(MediaStore.Audio.Media.ALBUM, album)
                    albumArtist?.let { put("album_artist", it) }
                }
                val updated = context.contentResolver.update(Uri.parse(track.uri), values, null, null)
                check(updated > 0) { "MediaStore가 메타데이터 변경을 반영하지 않았습니다." }
            } else throw UnsupportedOperationException("선택한 문서 공급자는 표준 태그 쓰기를 지원하지 않습니다.")
            dao.updateMetadata(track.id, title, artist, album, albumArtist, System.currentTimeMillis())
        }
    }

    private suspend fun preserveAppState(scanned: List<TrackEntity>): List<TrackEntity> {
        if (scanned.isEmpty()) return scanned
        val existing = dao.tracksByUris(scanned.map { it.uri }).associateBy { it.uri }
        return scanned.map { fresh -> existing[fresh.uri]?.let { old -> mergeScannedTrack(fresh, old) } ?: fresh }
    }

    private fun String?.nullIfUnknown() = this?.takeUnless { it.isBlank() || it == "<unknown>" }
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

internal fun mergeScannedTrack(fresh: TrackEntity, old: TrackEntity): TrackEntity = fresh.copy(
    customArtworkUri = old.customArtworkUri,
    customArtworkSource = old.customArtworkSource,
    autoArtworkUri = old.autoArtworkUri,
    autoArtworkSource = old.autoArtworkSource,
    playCount = old.playCount,
    lastPlayedAt = old.lastPlayedAt,
    isFavorite = old.isFavorite,
    customLyricsId = old.customLyricsId,
    createdAt = old.createdAt,
    updatedAt = maxOf(old.updatedAt, fresh.updatedAt),
)
