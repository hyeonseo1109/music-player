package com.hendo.hendomusic.library

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import com.hendo.hendomusic.data.AppDao
import com.hendo.hendomusic.data.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AudioEditMode { KEEP_SELECTION, REMOVE_SELECTION }

data class AudioSegment(val startUs: Long, val endUs: Long)

data class AudioEditResult(val uri: Uri, val displayName: String, val durationMs: Long)

sealed interface AudioEditState {
    data object Idle : AudioEditState
    data object Saving : AudioEditState
    data class Success(val result: AudioEditResult) : AudioEditState
    data class Error(val message: String) : AudioEditState
}

internal fun editSegments(durationMs: Long, startMs: Long, endMs: Long, mode: AudioEditMode): List<AudioSegment> {
    require(durationMs > 0) { "재생 시간을 확인할 수 없는 음원입니다." }
    val safeStart = startMs.coerceIn(0, durationMs)
    val safeEnd = endMs.coerceIn(0, durationMs)
    require(safeEnd - safeStart >= 100) { "선택 구간은 0.1초 이상이어야 합니다." }
    val durationUs = durationMs * 1_000
    val startUs = safeStart * 1_000
    val endUs = safeEnd * 1_000
    return when (mode) {
        AudioEditMode.KEEP_SELECTION -> listOf(AudioSegment(startUs, endUs))
        AudioEditMode.REMOVE_SELECTION -> buildList {
            if (startUs > 0) add(AudioSegment(0, startUs))
            if (endUs < durationUs) add(AudioSegment(endUs, durationUs))
            require(isNotEmpty()) { "전체 음원을 삭제하는 결과는 저장할 수 없습니다." }
        }
    }
}

internal fun editedAudioName(fileName: String, now: Long): String {
    val base = fileName.substringBeforeLast('.').ifBlank { "audio" }
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
    return "${base}_edited_$stamp.m4a"
}

internal fun editedAudioRelativePath(sourcePath: String?): String {
    val normalized = sourcePath?.trim()?.trimStart('/')?.replace('\\', '/')
    val allowedRoots = listOf(
        "Alarms",
        "Audiobooks",
        "Music",
        "Notifications",
        "Podcasts",
        "Recordings",
        "Ringtones",
    )
    val allowed = normalized?.takeIf { path ->
        allowedRoots.any { root -> path == root || path.startsWith("$root/", ignoreCase = true) }
    }
    return (allowed ?: "Music/HendoMusic/").let { path ->
        if (path.endsWith('/')) path else "$path/"
    }
}

/** Creates a new file only. The source URI is never opened for writing. */
class AudioSegmentEditor(private val context: Context, private val dao: AppDao) {
    suspend fun edit(track: TrackEntity, startMs: Long, endMs: Long, mode: AudioEditMode): AudioEditResult = withContext(Dispatchers.IO) {
        val segments = editSegments(track.durationMs, startMs, endMs, mode)
        val temp = File.createTempFile("hendo-audio-edit-", ".m4a", context.cacheDir)
        var published: Uri? = null
        try {
            val resultDurationMs = mux(track, segments, temp)
            val expectedDurationMs = segments.sumOf { it.endUs - it.startUs } / 1_000L
            // Do not publish a file whose sample timeline does not match the chosen operation.
            // This explicitly catches a REMOVE_SELECTION request accidentally producing only
            // the selected range, while allowing one codec frame of boundary rounding.
            val toleranceMs = 1_500L.coerceAtLeast(expectedDurationMs / 50L)
            require(kotlin.math.abs(resultDurationMs - expectedDurationMs) <= toleranceMs) {
                "편집 결과 길이가 선택한 방식과 일치하지 않습니다. 다시 시도해 주세요."
            }
            val now = System.currentTimeMillis()
            val displayName = editedAudioName(track.fileName, now)
            val relativePath = editedAudioRelativePath(track.relativePath)
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.TITLE, "${track.title} (편집)")
                put(MediaStore.Audio.Media.ARTIST, track.artist)
                put(MediaStore.Audio.Media.ALBUM, track.album)
                track.albumArtist?.let { put(MediaStore.Audio.Media.ALBUM_ARTIST, it) }
                put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("편집 결과 파일을 만들 수 없습니다.")
            published = uri
            resolver.openOutputStream(uri, "w")?.use { output -> temp.inputStream().use { it.copyTo(output) } }
                ?: error("편집 결과 파일을 저장할 수 없습니다.")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            val mediaId = ContentUris.parseId(uri)
            dao.upsertTracks(listOf(track.copy(
                id = "ms:$mediaId",
                mediaStoreId = mediaId,
                uri = uri.toString(),
                relativePath = relativePath,
                fileName = displayName,
                title = "${track.title} (편집)",
                durationMs = resultDurationMs,
                dateAdded = now,
                dateModified = now,
                albumArtUri = null,
                customArtworkUri = null,
                customArtworkSource = null,
                autoArtworkUri = null,
                autoArtworkSource = null,
                playCount = 0,
                lastPlayedAt = null,
                isFavorite = false,
                customLyricsId = null,
                createdAt = now,
                updatedAt = now,
            )))
            AudioEditResult(uri, displayName, resultDurationMs)
        } catch (failure: Throwable) {
            published?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            throw failure
        } finally {
            temp.delete()
        }
    }

    private fun mux(track: TrackEntity, segments: List<AudioSegment>, output: File): Long {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, Uri.parse(track.uri), null)
            val inputTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("오디오 트랙을 찾을 수 없습니다.")
            val format = extractor.getTrackFormat(inputTrack)
            // The source duration describes the whole original file. Carrying it into the MP4
            // output made some players report/play the old range even though different samples
            // were muxed (especially when deleting a middle section).
            format.removeKey(MediaFormat.KEY_DURATION)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            require(mime in setOf("audio/mp4a-latm", "audio/aac", "audio/mpeg", "audio/3gpp")) {
                "${track.fileName.substringAfterLast('.', "알 수 없는 형식")} 형식은 안전한 구간 편집을 지원하지 않습니다. MP3, M4A/AAC 또는 3GP를 사용해 주세요."
            }
            extractor.selectTrack(inputTrack)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTrack = try { muxer.addTrack(format) } catch (error: Throwable) {
                throw UnsupportedOperationException("이 기기는 ${track.fileName.substringAfterLast('.')} 음원의 무손실 구간 편집을 지원하지 않습니다.", error)
            }
            muxer.start()
            val maxInput = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 1_048_576
            val buffer = ByteBuffer.allocateDirect(maxInput.coerceAtLeast(1_048_576).coerceAtMost(8_388_608))
            val info = MediaCodec.BufferInfo()
            var lastOutputUs = -1L
            var lastDeltaUs = 1L
            segments.forEach { segment ->
                extractor.seekTo(segment.startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                while (extractor.sampleTime in 0 until segment.startUs) extractor.advance()
                val firstInputUs = extractor.sampleTime.takeIf { it >= 0 } ?: return@forEach
                val outputBaseUs = if (lastOutputUs < 0) 0L else lastOutputUs + lastDeltaUs.coerceAtLeast(1L)
                var previousInputUs = firstInputUs
                while (extractor.sampleTime >= 0 && extractor.sampleTime < segment.endUs) {
                    val inputUs = extractor.sampleTime
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val outputUs = (outputBaseUs + inputUs - firstInputUs).coerceAtLeast(lastOutputUs + 1L)
                    val outputFlags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else 0
                    info.set(0, size, outputUs, outputFlags)
                    muxer.writeSampleData(outputTrack, buffer, info)
                    if (inputUs > previousInputUs) lastDeltaUs = inputUs - previousInputUs
                    previousInputUs = inputUs
                    lastOutputUs = outputUs
                    extractor.advance()
                }
            }
            check(lastOutputUs >= 0) { "선택 구간에서 저장할 오디오 샘플을 찾지 못했습니다." }
            return (lastOutputUs / 1_000).coerceAtLeast(1)
        } finally {
            extractor.release()
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }
}
